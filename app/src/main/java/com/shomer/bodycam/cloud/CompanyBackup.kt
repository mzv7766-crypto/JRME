package com.shomer.bodycam.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Recording
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Company phones: every incident recording is uploaded to the COMPANY's cloud (Firebase Storage,
 * shomer_orgs/{org}/rec/{guard}/{id}.mp4) and listed for the managers – not to the guard's private Drive.
 * Uploads resume after a lost connection; full-shift clips stay on the phone (too big).
 */
object CompanyBackup {
    private const val TAG = "ShomerCloudRec"

    fun path(orgId: String, email: String, id: String) = "shomer_orgs/$orgId/rec/$email/$id.mp4"

    /** Returns true when the recording belongs to the company (so it never goes to a private Drive). */
    fun onRecordAdded(c: Context, r: Recording): Boolean {
        val s = OrgStore.current
        if (!s.activeMember) return false
        if (!r.isShift && s.org!!.policy.cloudBackup != Policy.CLOUD_OFF) enqueue(c, r)
        return true
    }

    fun enqueue(c: Context, r: Recording) {
        val s = OrgStore.current
        if (!s.activeMember || r.cloudPath != null || r.isShift) return
        val org = s.org!!
        val net = if (org.policy.cloudBackup == Policy.CLOUD_WIFI) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<CompanyUploadWorker>()
            .setInputData(workDataOf("id" to r.id, "org" to org.id, "email" to s.me!!.email))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag("company-cloud")
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(workName(r.id), ExistingWorkPolicy.KEEP, req)
    }

    /** Incidents of the last week that are not in the company cloud yet. */
    fun enqueuePending(c: Context) {
        val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
        RecordRepository.records.value.filter { !it.isShift && it.cloudPath == null && it.triggerTime > since }.forEach { enqueue(c, it) }
    }

    fun workName(id: String) = "cloud-$id"

    fun status(c: Context, id: String): Flow<androidx.work.WorkInfo.State?> =
        WorkManager.getInstance(c).getWorkInfosForUniqueWorkFlow(workName(id)).map { it.firstOrNull()?.state }

    suspend fun upload(c: Context, r: Recording, orgId: String, email: String, onProgress: (Int) -> Unit): String {
        val p = path(orgId, email, r.id)
        val ref = Cloud.storage.reference.child(p)
        val prefs = c.getSharedPreferences("shomer_cloud", Context.MODE_PRIVATE)
        val key = "session_${r.id}"

        // a previous attempt may have finished the upload but not the listing (the rules never allow overwriting)
        val already = runCatching { ref.metadata.await() }.isSuccess
        if (!already) {
            val meta = StorageMetadata.Builder()
                .setContentType("video/mp4")
                .setCustomMetadata("sha256", r.sha256 ?: "")
                .setCustomMetadata("guard", email)
                .setCustomMetadata("start", r.videoStartTime.toString())
                .build()
            val uri = Uri.fromFile(r.file)
            val session = prefs.getString(key, null)?.let(Uri::parse)
            val task = if (session != null) ref.putFile(uri, meta, session) else ref.putFile(uri, meta)
            task.addOnProgressListener { snap ->
                snap.uploadSessionUri?.let { u -> if (prefs.getString(key, null) != u.toString()) prefs.edit().putString(key, u.toString()).apply() }
                onProgress((snap.bytesTransferred * 100 / snap.totalByteCount.coerceAtLeast(1)).toInt())
            }
            try {
                task.await()
            } catch (e: StorageException) {
                // an expired / broken session: start over next time
                if (session != null) prefs.edit().remove(key).apply()
                throw e
            }
            prefs.edit().remove(key).apply()
        }

        val docRef = Cloud.org(orgId).collection("recordings").document(r.id)
        if (!runCatching { docRef.get().await().exists() }.getOrDefault(false)) {
            val me = OrgStore.current.me
            docRef.set(mapOf(
                "email" to email, "name" to (me?.stamp?.ifBlank { null } ?: r.operatorId ?: email), "path" to p,
                "start" to r.videoStartTime, "triggerAt" to r.triggerTime, "durationMs" to r.durationMs, "size" to r.file.length(),
                "trigger" to r.trigger.name, "sha256" to r.sha256, "lat" to r.lat, "lon" to r.lon,
                "title" to r.title, "uploadedAt" to System.currentTimeMillis(),
            )).await()
        }
        return p
    }
}

class CompanyUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val org = inputData.getString("org") ?: return Result.success()
        val email = inputData.getString("email") ?: return Result.success()
        RecordRepository.init(applicationContext)
        Cloud.init(applicationContext)
        val r = RecordRepository.get(id) ?: return Result.success()          // deleted meanwhile
        if (r.cloudPath != null || !r.file.exists()) return Result.success()
        if (Cloud.email() != email) return Result.success()                  // signed out / other account
        return try {
            val p = CompanyBackup.upload(applicationContext, r, org, email) { pct -> setProgressAsync(workDataOf("pct" to pct)) }
            RecordRepository.setCloud(id, p)
            Log.i("ShomerCloudRec", "uploaded $id")
            Result.success()
        } catch (e: Exception) {
            Log.w("ShomerCloudRec", "upload failed", e)
            val denied = (e as? StorageException)?.errorCode == StorageException.ERROR_NOT_AUTHORIZED
            when {
                denied && runAttemptCount >= 3 -> Result.failure()   // not (or no longer) a guard of a paying company
                runAttemptCount < 15 -> Result.retry()
                else -> Result.failure()
            }
        }
    }
}
