package com.shomer.bodycam.cloud

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.shomer.bodycam.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        if (t.isSuccessful) cont.resume(t.result)
        else cont.resumeWithException(t.exception ?: IllegalStateException("task failed"))
    }
}

/**
 * Firebase entry point. Everything in the cloud package is a no-op when this build has no Firebase config,
 * so the camera itself never depends on the network.
 */
object Cloud {
    const val TAG = "ShomerCloud"
    const val ADMIN_EMAIL = "mzv7766@gmail.com"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var configured = false; private set

    fun init(c: Context) {
        if (configured) return
        configured = runCatching { FirebaseApp.getApps(c).isNotEmpty() || FirebaseApp.initializeApp(c) != null }.getOrDefault(false)
        if (configured && BuildConfig.EMULATOR) runCatching {
            // end-to-end tests: local Firebase emulators on the test machine (10.0.2.2 = the host, seen from the Android emulator)
            FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
            FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080)
            com.google.firebase.functions.FirebaseFunctions.getInstance().useEmulator("10.0.2.2", 5001)
        }.onFailure { Log.w(TAG, "emulator setup", it) }
    }

    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val functions: com.google.firebase.functions.FirebaseFunctions get() = com.google.firebase.functions.FirebaseFunctions.getInstance("us-central1")
    fun email(): String? = if (!configured) null else auth.currentUser?.email?.lowercase()

    fun org(orgId: String) = db.collection("shomer_orgs").document(orgId)
    fun index(email: String) = db.collection("shomer_index").document(email)
}

/** Removes Firestore listeners together. */
class Listeners {
    private val list = ArrayList<ListenerRegistration>()
    operator fun plusAssign(l: ListenerRegistration) { list += l }
    fun clear() { list.forEach { runCatching { it.remove() } }; list.clear() }
    val isEmpty get() = list.isEmpty()
}
