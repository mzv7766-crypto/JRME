package com.shomer.bodycam.cloud

import android.accounts.Account as AndroidAccount
import android.app.Activity
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.shomer.bodycam.R
import com.shomer.bodycam.data.Recording
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * PRO: backs up every sealed recording to the user's own Google Drive, folder "Shomer", so the evidence
 * survives even if the phone is taken or broken. Uses the narrow drive.file scope (Shomer only sees the
 * files it created). The SHA-256 fingerprint goes into the Drive file's description. Uploads run in
 * WorkManager and resume after network loss.
 */
object DriveBackup {
    private const val TAG = "ShomerDrive"
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FOLDER = "Shomer"

    /** True when Google needs the user to (re)approve Drive access – shown in Settings. */
    val needsAuth = MutableStateFlow(false)

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .apply { AccountStore.current.email?.let { setAccount(AndroidAccount(it, "com.google")) } }
        .build()

    /** UI: asks for Drive access. [launch] must start the PendingIntent (consent screen) if one is needed. */
    fun authorize(activity: Activity, launch: (android.app.PendingIntent) -> Unit, onGranted: () -> Unit, onError: (String) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) r.pendingIntent?.let(launch) ?: onError(activity.getString(R.string.drive_auth_failed))
                else { needsAuth.value = false; onGranted() }
            }
            .addOnFailureListener { Log.w(TAG, "authorize failed", it); onError(activity.getString(R.string.drive_auth_failed)) }
    }

    fun resultFromIntent(context: Context, data: Intent?): AuthorizationResult? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }.getOrNull()

    /** Background: a fresh access token, or null when the user must approve again. */
    suspend fun token(context: Context): String? {
        val r = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (r.hasResolution()) { needsAuth.value = true; return null }
        return r.accessToken
    }

    // ---- scheduling ----

    fun onRecordAdded(context: Context, r: Recording) {
        if (!SettingsStore.proActive.value || AccountStore.current.email == null) return
        if (SettingsStore.current.driveBackup && !r.isShift) enqueue(context, r, manual = false)
    }

    fun enqueue(context: Context, r: Recording, manual: Boolean) {
        if (r.driveId != null) return
        val net = if (manual || SettingsStore.current.driveCellular) NetworkType.CONNECTED else NetworkType.UNMETERED
        val req = OneTimeWorkRequestBuilder<DriveUploadWorker>()
            .setInputData(workDataOf("id" to r.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag("drive")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(r.id), if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    fun workName(id: String) = "drive-$id"

    /** RUNNING / ENQUEUED / FAILED etc. for one clip (null = never queued). */
    fun status(context: Context, id: String): Flow<WorkInfo.State?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(id)).map { it.firstOrNull()?.state }

    fun openUrl(driveId: String) = "https://drive.google.com/file/d/$driveId/view"

    // ---- Drive REST (v3) ----

    private fun conn(url: String, method: String, token: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000; readTimeout = 60_000
        setRequestProperty("Authorization", "Bearer $token")
    }

    private fun HttpURLConnection.body(): String {
        val ok = responseCode in 200..299
        val text = (if (ok) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (!ok) throw DriveHttpException(responseCode, text.take(300))
        return text
    }

    class DriveHttpException(val code: Int, msg: String) : IOException("Drive HTTP $code: $msg")

    private fun folderId(context: Context, token: String): String {
        val prefs = context.getSharedPreferences("shomer_drive", Context.MODE_PRIVATE)
        val key = "folder_${AccountStore.current.email}"
        prefs.getString(key, null)?.let { id ->
            // still there (and not in the trash)?
            val c = conn("https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed", "GET", token)
            if (c.responseCode == 200 && !JSONObject(c.body()).optBoolean("trashed")) return id
        }
        val q = URLEncoder.encode("name='$FOLDER' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8")
        val found = JSONObject(conn("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&spaces=drive", "GET", token).body())
            .getJSONArray("files").let { if (it.length() > 0) it.getJSONObject(0).getString("id") else null }
        val id = found ?: run {
            val c = conn("https://www.googleapis.com/drive/v3/files?fields=id", "POST", token)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray()) }
            JSONObject(c.body()).getString("id")
        }
        prefs.edit().putString(key, id).apply()
        return id
    }

    private const val CHUNK = 8L * 1024 * 1024   // multiple of 256 KB, as Drive requires

    /**
     * Resumable upload of one MP4 in 8 MB chunks. The upload session is remembered, so if the job is
     * interrupted (network loss, time limit) the next attempt continues where it stopped. Returns the Drive file id.
     */
    fun upload(context: Context, token: String, r: Recording, onProgress: (Int) -> Unit): String {
        val file: File = r.file
        val total = file.length()
        val prefs = context.getSharedPreferences("shomer_drive", Context.MODE_PRIVATE)
        val key = "session_${r.id}"

        var session = prefs.getString(key, null)
        var offset = 0L
        if (session != null) {
            when (val st = status(session, total)) {
                is Status.Done -> { prefs.edit().remove(key).apply(); return st.id }
                is Status.Partial -> offset = st.next
                Status.Gone -> session = null
            }
        }
        if (session == null) {
            val parent = folderId(context, token)
            val start = conn("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id", "POST", token)
            start.doOutput = true
            start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            start.setRequestProperty("X-Upload-Content-Type", "video/mp4")
            start.setRequestProperty("X-Upload-Content-Length", total.toString())
            val meta = JSONObject().put("name", fileName(r)).put("parents", org.json.JSONArray().put(parent))
                .put("description", "Shomer body camera" + (r.title?.let { " – $it" } ?: "") + (r.sha256?.let { "\nSHA-256: $it" } ?: ""))
                .apply { r.sha256?.let { put("appProperties", JSONObject().put("sha256", it)) } }
            start.outputStream.use { it.write(meta.toString().toByteArray()) }
            start.body()
            session = start.getHeaderField("Location") ?: throw IOException("no upload session")
            prefs.edit().putString(key, session).apply()
            offset = 0L
        }

        java.io.RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val end = minOf(offset + CHUNK, total) - 1
                val len = end - offset + 1
                val put = (URL(session).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; doOutput = true
                    connectTimeout = 20_000; readTimeout = 120_000
                    setFixedLengthStreamingMode(len)
                    setRequestProperty("Content-Type", "video/mp4")
                    setRequestProperty("Content-Range", "bytes $offset-$end/$total")
                }
                raf.seek(offset)
                put.outputStream.use { out ->
                    var left = len
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("file shrank")
                        out.write(buf, 0, n); left -= n
                    }
                }
                when (put.responseCode) {
                    200, 201 -> {
                        prefs.edit().remove(key).apply()
                        onProgress(100)
                        return JSONObject(put.body()).getString("id")
                    }
                    308 -> {
                        offset = put.getHeaderField("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: (end + 1)
                        onProgress((offset * 100 / total.coerceAtLeast(1)).toInt())
                    }
                    404, 410 -> { prefs.edit().remove(key).apply(); throw DriveHttpException(put.responseCode, "upload session expired") }
                    else -> put.body() // throws DriveHttpException
                }
            }
        }
    }

    private sealed class Status {
        data class Done(val id: String) : Status()
        data class Partial(val next: Long) : Status()
        object Gone : Status()
    }

    /** Asks Drive how much of an interrupted upload it already has. */
    private fun status(session: String, total: Long): Status = try {
        val c = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 20_000; readTimeout = 60_000
            setFixedLengthStreamingMode(0)
            setRequestProperty("Content-Range", "bytes */$total")
        }
        c.outputStream.close()
        when (c.responseCode) {
            200, 201 -> Status.Done(JSONObject(c.body()).getString("id"))
            308 -> Status.Partial(c.getHeaderField("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L)
            else -> Status.Gone
        }
    } catch (e: IOException) { Status.Gone }

    private fun fileName(r: Recording): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(r.triggerTime))
        return "SHOMER_$ts" + (r.title?.let { "_" + it.replace(Regex("""[\\/:*?"<>|]"""), "") } ?: "") + ".mp4"
    }

    fun notification(context: Context, pct: Int): Notification =
        NotificationCompat.Builder(context, com.shomer.bodycam.service.Notifications.CH_BACKUP)
            .setSmallIcon(R.drawable.ic_stat_shomer)
            .setContentTitle(context.getString(R.string.drive_uploading))
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true).setSilent(true)
            .build()
}

class DriveUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        RecordRepository.init(applicationContext)
        val r = RecordRepository.get(id) ?: return Result.success()          // deleted meanwhile
        if (r.driveId != null || !r.file.exists()) return Result.success()
        if (!SettingsStore.proActive.value || AccountStore.current.email == null) return Result.success()
        return try {
            val token = DriveBackup.token(applicationContext) ?: return Result.failure()
            val fileId = DriveBackup.upload(applicationContext, token, r) { pct -> setProgressAsync(workDataOf("pct" to pct)) }
            RecordRepository.setDrive(id, fileId)
            Log.i("ShomerDrive", "uploaded ${r.id}")
            Result.success()
        } catch (e: DriveBackup.DriveHttpException) {
            Log.w("ShomerDrive", "upload failed", e)
            if (e.code == 401 || e.code == 403) DriveBackup.needsAuth.value = e.code == 401
            if (runAttemptCount < 12) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.w("ShomerDrive", "upload failed", e)
            if (runAttemptCount < 12) Result.retry() else Result.failure()
        }
    }

}
