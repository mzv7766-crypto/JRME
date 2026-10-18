package com.shomer.bodycam.cloud

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.Recording
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Company phones: every incident recording is uploaded to the guard's own Google Drive, into a folder named after
 * the company ("Shomer – <company>"), and that folder is shared (view only) with the company's managers.
 * The recording is listed in Firestore so the control room sees it right away. Shift clips stay on the phone.
 * After the company's retention period the phone deletes its old uploads (unless a manager marked them "keep").
 */
object CompanyBackup {
    private const val TAG = "ShomerCompanyDrive"

    fun folderName(orgName: String) = "Shomer – " + orgName.replace(Regex("""[\\/:*?"<>|']"""), "").trim().ifEmpty { "company" }

    /** Returns true when the recording belongs to the company (so the personal Drive backup is skipped). */
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
            .setInputData(workDataOf("id" to r.id, "org" to org.id, "orgName" to org.name, "email" to s.me!!.email))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag("company-drive")
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(workName(r.id), ExistingWorkPolicy.KEEP, req)
    }

    /** Incidents of the last week not uploaded yet, plus the daily clean-up. Called when the phone becomes a company phone. */
    fun start(c: Context) {
        val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
        RecordRepository.records.value.filter { !it.isShift && it.cloudPath == null && it.triggerTime > since }.forEach { enqueue(c, it) }
        val daily = PeriodicWorkRequestBuilder<CompanyCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("company-drive-cleanup", ExistingPeriodicWorkPolicy.KEEP, daily)
    }

    fun workName(id: String) = "cloud-$id"

    fun status(c: Context, id: String): Flow<androidx.work.WorkInfo.State?> =
        WorkManager.getInstance(c).getWorkInfosForUniqueWorkFlow(workName(id)).map { it.firstOrNull()?.state }

    class NeedsAuth : Exception("Drive access not approved")

    /** Uploads to the guard's Drive, shares the company folder with the managers, lists it for the control room. */
    suspend fun upload(c: Context, r: Recording, orgId: String, orgName: String, email: String, onProgress: (Int) -> Unit): String {
        val token = DriveBackup.token(c) ?: throw NeedsAuth()
        val folder = folderName(orgName)
        val fileId = r.driveId ?: DriveBackup.upload(c, token, r, folder, onProgress).also { RecordRepository.setDrive(r.id, it) }
        // managers can open the folder (new managers are added on the next upload)
        val managers = runCatching { Cloud.org(orgId).get().await() }.getOrNull()?.get("managers") as? List<*>
        runCatching { DriveBackup.shareWith(c, token, DriveBackup.folderId(c, token, folder), managers.orEmpty().filterIsInstance<String>()) }
            .onFailure { Log.w(TAG, "share folder", it) }

        val docRef = Cloud.org(orgId).collection("recordings").document(r.id)
        if (!runCatching { docRef.get().await().exists() }.getOrDefault(false)) {
            val me = OrgStore.current.me
            docRef.set(mapOf(
                "email" to email, "name" to (me?.stamp?.ifBlank { null } ?: r.operatorId ?: email),
                "driveId" to fileId, "link" to DriveBackup.openUrl(fileId),
                "start" to r.videoStartTime, "triggerAt" to r.triggerTime, "durationMs" to r.durationMs, "size" to r.file.length(),
                "trigger" to r.trigger.name, "sha256" to r.sha256, "lat" to r.lat, "lon" to r.lon,
                "title" to r.title, "uploadedAt" to System.currentTimeMillis(),
            )).await()
        }
        return "drive:$fileId"
    }

    /** Deletes this guard's uploads that are older than the company's retention (and not marked "keep"). */
    suspend fun cleanup(c: Context, orgId: String, email: String, days: Int) {
        val token = DriveBackup.token(c) ?: return
        val cutoff = System.currentTimeMillis() - days * 24 * 3600_000L
        // one simple filter (no composite index needed); the age check is done here
        val mine = Cloud.org(orgId).collection("recordings").whereEqualTo("email", email).get().await()
        for (d in mine.documents) {
            if ((d.getLong("uploadedAt") ?: Long.MAX_VALUE) >= cutoff) continue
            if (d.getBoolean("keep") == true) continue
            val id = d.getString("driveId") ?: continue
            runCatching { if (DriveBackup.delete(token, id)) d.reference.delete().await() }.onFailure { Log.w(TAG, "cleanup $id", it) }
        }
    }
}

class CompanyUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val org = inputData.getString("org") ?: return Result.success()
        val orgName = inputData.getString("orgName") ?: ""
        val email = inputData.getString("email") ?: return Result.success()
        RecordRepository.init(applicationContext)
        Cloud.init(applicationContext)
        val r = RecordRepository.get(id) ?: return Result.success()          // deleted meanwhile
        if (r.cloudPath != null || !r.file.exists()) return Result.success()
        if (Cloud.email() != email) return Result.success()                  // signed out / other account
        return try {
            val p = CompanyBackup.upload(applicationContext, r, org, orgName, email) { pct -> setProgressAsync(workDataOf("pct" to pct)) }
            RecordRepository.setCloud(id, p)
            Log.i("ShomerCompanyDrive", "uploaded $id")
            Result.success()
        } catch (e: CompanyBackup.NeedsAuth) {
            // the guard has to approve Drive once (banner on the shift screen); try again later
            if (runAttemptCount < 30) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.w("ShomerCompanyDrive", "upload failed", e)
            if (e is DriveBackup.DriveHttpException && e.code == 401) DriveBackup.needsAuth.value = true
            if (runAttemptCount < 15) Result.retry() else Result.failure()
        }
    }
}

class CompanyCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Cloud.init(applicationContext)
        val email = Cloud.email() ?: return Result.success()
        return runCatching {
            val idx = Cloud.index(email).get().await()
            val orgId = idx.getString("orgId") ?: return Result.success()
            if (idx.getString("role") != "member") return Result.success()
            val days = (Cloud.org(orgId).get().await().get("policy") as? Map<*, *>)?.get("retentionDays") as? Number
            CompanyBackup.cleanup(applicationContext, orgId, email, days?.toInt() ?: 30)
            Result.success()
        }.getOrElse { Log.w("ShomerCompanyDrive", "cleanup", it); Result.retry() }
    }
}
