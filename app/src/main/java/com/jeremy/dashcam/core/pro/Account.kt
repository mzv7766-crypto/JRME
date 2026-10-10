package com.jeremy.dashcam.core.pro

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.jeremy.dashcam.data.ProStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        if (t.isSuccessful) cont.resume(t.result)
        else cont.resumeWithException(t.exception ?: IllegalStateException("task failed"))
    }
}

/**
 * Google sign-in (Credential Manager → Firebase Auth) and the admin-managed free-accounts list (Firestore:
 * freeAccounts/{email}). Everything is a no-op when Firebase isn't configured in this build.
 */
object Account {
    private const val TAG = "JeremyAccount"
    private const val FREE = "freeAccounts"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var configured = false; private set

    fun init(c: Context) {
        app = c
        configured = runCatching { FirebaseApp.getApps(c).isNotEmpty() || FirebaseApp.initializeApp(c) != null }.getOrDefault(false) &&
            webClientId() != null
        ProStore.update { it.copy(configured = configured) }
        if (!configured) return
        // The Firebase session is the source of truth for who is signed in.
        val user = FirebaseAuth.getInstance().currentUser
        if (user?.email == null) {
            if (ProStore.current.email != null) ProStore.update { it.copy(email = null, name = null, isFreeAccount = false) }
        } else {
            ProStore.update { it.copy(email = user.email!!.lowercase(), name = user.displayName) }
            scope.launch { refreshFree() }
        }
    }

    private fun webClientId(): String? {
        val id = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        return if (id == 0) null else app.getString(id).takeIf { it.isNotBlank() }
    }

    /** Shows the Google account chooser. Returns an error message for the user, or null on success / cancel. */
    suspend fun signIn(activity: Activity): String? {
        if (!configured) return "ההתחברות עדיין לא הופעלה בגרסה הזו"
        ProStore.update { it.copy(busy = true) }
        try {
            val option = GetSignInWithGoogleOption.Builder(webClientId()!!).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val cred = CredentialManager.create(activity).getCredential(activity, request).credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return "סוג חשבון לא נתמך"
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            val result = FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).awaitTask()
            val email = (result.user?.email ?: google.id).lowercase()
            ProStore.update { it.copy(email = email, name = result.user?.displayName ?: google.displayName, isFreeAccount = false) }
            refreshFree()
            Billing.refresh()
            return null
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            return "לא נמצא חשבון Google בטלפון. הוסף חשבון בהגדרות הטלפון ונסה שוב."
        } catch (e: Exception) {
            Log.w(TAG, "sign-in failed", e)
            return "ההתחברות נכשלה. בדוק חיבור לאינטרנט ונסה שוב."
        } finally {
            ProStore.update { it.copy(busy = false) }
        }
    }

    suspend fun signOut(context: Context) {
        if (configured) runCatching { FirebaseAuth.getInstance().signOut() }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        ProStore.update { it.copy(email = null, name = null, isFreeAccount = false, freeAccounts = emptyList()) }
    }

    /** Checks whether the signed-in e-mail is on the free list (keeps the cached value when offline). */
    suspend fun refreshFree() {
        val email = ProStore.current.email ?: return
        if (!configured) return
        try {
            val free = FirebaseFirestore.getInstance().collection(FREE).document(email).get().awaitTask().exists()
            ProStore.update { it.copy(isFreeAccount = free) }
            if (ProStore.current.isAdmin) loadFreeList()
        } catch (e: Exception) {
            Log.w(TAG, "free check failed", e)
        }
    }

    // ---- admin ----

    suspend fun loadFreeList() {
        if (!configured || !ProStore.current.isAdmin) return
        try {
            val list = FirebaseFirestore.getInstance().collection(FREE).get().awaitTask().documents.map { it.id }.sorted()
            ProStore.update { it.copy(freeAccounts = list) }
        } catch (e: Exception) {
            Log.w(TAG, "free list failed", e)
            ProStore.message("לא ניתן לטעון את הרשימה – בדוק את כללי Firestore")
        }
    }

    suspend fun addFree(raw: String): Boolean {
        val email = raw.trim().lowercase()
        if (!email.matches(Regex("""[^\s@/]+@[^\s@/]+\.[^\s@/]+"""))) { ProStore.message("כתובת מייל לא תקינה"); return false }
        return try {
            FirebaseFirestore.getInstance().collection(FREE).document(email)
                .set(mapOf("addedAt" to FieldValue.serverTimestamp(), "by" to ProStore.current.email)).awaitTask()
            loadFreeList(); true
        } catch (e: Exception) {
            Log.w(TAG, "add free failed", e); ProStore.message("ההוספה נכשלה"); false
        }
    }

    suspend fun removeFree(email: String) {
        try {
            FirebaseFirestore.getInstance().collection(FREE).document(email).delete().awaitTask()
            loadFreeList()
        } catch (e: Exception) {
            Log.w(TAG, "remove free failed", e); ProStore.message("ההסרה נכשלה")
        }
    }
}
