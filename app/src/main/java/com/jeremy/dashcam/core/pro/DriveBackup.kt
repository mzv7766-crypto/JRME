package com.jeremy.dashcam.core.pro

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
import com.jeremy.dashcam.R
import com.jeremy.dashcam.data.DriveMode
import com.jeremy.dashcam.data.EventRecord
import com.jeremy.dashcam.data.EventRepository
import com.jeremy.dashcam.data.ProStore
import com.jeremy.dashcam.data.SettingsStore
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
 * PRO: backs up saved clips to the user's own Google Drive, folder "Jeremy".
 * Uses the narrow drive.file scope (Jeremy only sees the files it created). Uploads run in WorkManager,
 * on Wi-Fi only unless the user allowed mobile data, and resume automatically after network loss.
 */
object DriveBackup {
    private const val TAG = "JeremyDrive"
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FOLDER = "Jeremy"

    /** True when Google needs the user to (re)approve Drive access – shown in Settings. */
    val needsAuth = MutableStateFlow(false)

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .apply { ProStore.current.email?.let { setAccount(AndroidAccount(it, "com.google")) } }
        .build()

    /** UI: asks for Drive access. [launch] must start the PendingIntent (consent screen) if one is needed. */
    fun authorize(activity: Activity, launch: (android.app.PendingIntent) -> Unit, onGranted: () -> Unit, onError: (String) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) r.pendingIntent?.let(launch) ?: onError("לא ניתן לפתוח את מסך האישור")
                else { needsAuth.value = false; onGranted() }
            }
            .addOnFailureListener { Log.w(TAG, "authorize failed", it); onError("האישור מול Google נכשל. בדוק חיבור לאינטרנט.") }
    }

    fun resultFromIntent(context: Context, data: Intent?): AuthorizationResult? =
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }.getOrNull()

    /** Background: a fresh access token, or null when the user must approve again. */
    suspend fun token(context: Context): String? {
        val r = Identity.getAuthorizationClient(context).authorize(request()).awaitTask()
        if (r.hasResolution()) { needsAuth.value = true; return null }
        return r.accessToken
    }

    // ---- scheduling ----

    fun onRecordAdded(context: Context, r: EventRecord) {
        if (!ProStore.current.isPro || ProStore.current.email == null) return
        when (SettingsStore.current.driveMode) {
            DriveMode.EVENTS -> if (!r.isTrip) enqueue(context, r, manual = false)
            DriveMode.ALL -> enqueue(context, r, manual = false)
            DriveMode.OFF, DriveMode.MANUAL -> Unit
        }
    }

    fun enqueue(context: Context, r: EventRecord, manual: Boolean) {
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
        val prefs = context.getSharedPreferences("jeremy_drive", Context.MODE_PRIVATE)
        val key = "folder_${ProStore.current.email}"
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

    /** Resumable upload of one MP4. Returns the Drive file id. */
    fun upload(context: Context, token: String, r: EventRecord, onProgress: (Int) -> Unit): String {
        val file: File = r.file
        val parent = folderId(context, token)
        val start = conn("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id", "POST", token)
        start.doOutput = true
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        start.setRequestProperty("X-Upload-Content-Type", "video/mp4")
        start.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        val meta = JSONObject().put("name", fileName(r)).put("parents", org.json.JSONArray().put(parent))
            .put("description", "Jeremy dashcam" + (r.name?.let { " – $it" } ?: ""))
        start.outputStream.use { it.write(meta.toString().toByteArray()) }
        start.body()
        val session = start.getHeaderField("Location") ?: throw IOException("no upload session")

        val put = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 20_000; readTimeout = 120_000
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", "video/mp4")
        }
        val total = file.length().coerceAtLeast(1)
        var sent = 0L; var lastPct = -1
        file.inputStream().use { input ->
            put.outputStream.use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); sent += n
                    val pct = (sent * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
            }
        }
        return JSONObject(put.body()).getString("id")
    }

    private fun fileName(r: EventRecord): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(r.triggerTime))
        val kind = if (r.isTrip) "נסיעה" else "אירוע"
        return "Jeremy_${kind}_$ts" + (r.name?.let { "_" + it.replace(Regex("""[\\/:*?"<>|]"""), "") } ?: "") + ".mp4"
    }

    fun notification(context: Context, pct: Int): Notification =
        NotificationCompat.Builder(context, com.jeremy.dashcam.service.Notifications.CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_stat_jeremy)
            .setContentTitle("מגבה ל־Google Drive")
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true).setSilent(true)
            .build()
}

class DriveUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        EventRepository.init(applicationContext)
        val r = EventRepository.get(id) ?: return Result.success()          // deleted meanwhile
        if (r.driveId != null || !r.file.exists()) return Result.success()
        if (!ProStore.current.isPro || ProStore.current.email == null) return Result.success()
        runCatching { setForeground(foreground(0)) }
        return try {
            val token = DriveBackup.token(applicationContext) ?: return Result.failure()
            var lastShown = 0
            val fileId = DriveBackup.upload(applicationContext, token, r) { pct ->
                if (pct - lastShown >= 5) { lastShown = pct; runCatching { setForegroundAsync(foreground(pct)) } }
            }
            EventRepository.setDrive(id, fileId)
            Log.i("JeremyDrive", "uploaded ${r.id} → $fileId")
            Result.success()
        } catch (e: DriveBackup.DriveHttpException) {
            Log.w("JeremyDrive", "upload failed", e)
            if (e.code == 401 || e.code == 403) DriveBackup.needsAuth.value = e.code == 401
            if (runAttemptCount < 6 && e.code != 404) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.w("JeremyDrive", "upload failed", e)
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }

    private fun foreground(pct: Int): ForegroundInfo {
        val n = DriveBackup.notification(applicationContext, pct)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(4207, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4207, n)
    }
}
