package com.shomer.bodycam.cloud

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.shomer.bodycam.BuildConfig
import com.shomer.bodycam.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Google Play "Shomer Team" base plan: N devices for a monthly price. */
data class TeamOffer(val basePlan: String, val seats: Int, val price: String, val offerToken: String)

/** The company's active Play team subscription, as seen on the manager's phone. */
data class TeamPurchase(val basePlan: String, val seats: Int, val token: String, val autoRenewing: Boolean)

data class AccountState(
    /** Google Play token of the company plan this account bought (verified by the server). */
    val teamToken: String? = null,
    val configured: Boolean = false,
    val email: String? = null,
    val name: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    // personal Pro
    val isFreeAccount: Boolean = false,
    val proSubscribed: Boolean = false,
    val proAutoRenew: Boolean = false,
    val proAvailable: Boolean = false,
    val proTrialAvailable: Boolean = false,
    val proPrice: String = AccountStore.PRO_PRICE,
    // Team (manager)
    val teamOffers: List<TeamOffer> = emptyList(),
    val teamPurchase: TeamPurchase? = null,
    // admin only
    val freeAccounts: List<String> = emptyList(),
) {
    val signedIn get() = email != null
    val isAdmin get() = email.equals(Cloud.ADMIN_EMAIL, ignoreCase = true)
    val personalPro get() = proSubscribed || (email != null && (isAdmin || isFreeAccount))
}

/**
 * Google account + personal Pro state. Google sign-in is needed only for Pro and Team; the camera works without it.
 * The last known values are cached so Pro keeps working offline.
 */
object AccountStore {
    const val PRO_PRICE = "₪19.90"
    const val TRIAL_DAYS = 14

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()
    val current get() = _state.value

    fun init(c: Context) {
        if (::prefs.isInitialized) return
        app = c.applicationContext
        prefs = app.getSharedPreferences("shomer_account", Context.MODE_PRIVATE)
        Cloud.init(app)
        _state.value = AccountState(
            configured = Cloud.configured && (webClientId() != null || BuildConfig.EMULATOR),
            email = Cloud.email(),
            name = prefs.getString("name", null),
            isFreeAccount = prefs.getBoolean("free", false),
            // a cached subscription is trusted for 7 days without a fresh confirmation from Google Play
            proSubscribed = prefs.getBoolean("pro", false) && System.currentTimeMillis() - prefs.getLong("verifiedAt", 0L) < 7L * 86_400_000,
        )
        Billing.init(app)
        if (current.email != null) Cloud.scope.launch { refreshFree() }
        OrgStore.onSignedIn(current.email)
    }

    fun update(f: (AccountState) -> AccountState) {
        _state.update(f)
        val s = _state.value
        prefs.edit().putString("name", s.name).putBoolean("free", s.isFreeAccount).putBoolean("pro", s.proSubscribed).apply()
    }

    fun markVerified() = prefs.edit().putLong("verifiedAt", System.currentTimeMillis()).apply()
    fun message(text: String?) = update { it.copy(message = text) }
    fun messageRes(res: Int, vararg args: Any) = message(app.getString(res, *args))

    private fun webClientId(): String? {
        val id = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        return if (id == 0) null else app.getString(id).takeIf { it.isNotBlank() }
    }

    /** Shows the Google account chooser. Returns an error message, or null on success / cancel. */
    suspend fun signIn(activity: Activity): String? {
        if (!current.configured) return app.getString(R.string.acc_not_configured)
        update { it.copy(busy = true) }
        try {
            val option = GetSignInWithGoogleOption.Builder(webClientId()!!).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val cred = CredentialManager.create(activity).getCredential(activity, request).credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return app.getString(R.string.acc_unsupported)
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            val result = Cloud.auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
            afterSignIn(result.user?.email ?: google.id, result.user?.displayName ?: google.displayName)
            return null
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            return app.getString(R.string.acc_no_google)
        } catch (e: Exception) {
            Log.w(Cloud.TAG, "sign-in failed", e)
            return app.getString(R.string.acc_failed)
        } finally {
            update { it.copy(busy = false) }
        }
    }

    /** End-to-end tests only: the Auth emulator accepts an unsigned Google token. */
    suspend fun signInForTest(email: String) {
        if (!BuildConfig.EMULATOR || !Cloud.configured) return
        val token = """{"sub":"${email.hashCode()}","email":"$email","email_verified":true}"""
        Cloud.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        afterSignIn(email, email.substringBefore('@'))
    }

    private suspend fun afterSignIn(email: String, name: String?) {
        update { it.copy(email = email.lowercase(), name = name, isFreeAccount = false) }
        OrgStore.onSignedIn(email.lowercase())
        refreshFree()
        Billing.refresh()
    }

    suspend fun signOut(context: Context) {
        OrgStore.onSignedIn(null)
        if (Cloud.configured) runCatching { Cloud.auth.signOut() }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        update { it.copy(email = null, name = null, isFreeAccount = false, freeAccounts = emptyList()) }
    }

    /** The admin's free-accounts list (shared with Jeremy). Keeps the cached value when offline. */
    suspend fun refreshFree() {
        val email = current.email ?: return
        try {
            val free = Cloud.db.collection("freeAccounts").document(email).get().await().exists()
            update { it.copy(isFreeAccount = free) }
            if (current.isAdmin) loadFreeList()
        } catch (e: Exception) { Log.w(Cloud.TAG, "free check failed", e) }
    }

    suspend fun loadFreeList() {
        if (!current.isAdmin) return
        runCatching { Cloud.db.collection("freeAccounts").get().await().documents.map { it.id }.sorted() }
            .onSuccess { list -> update { it.copy(freeAccounts = list) } }
    }

    suspend fun addFree(raw: String): Boolean {
        val email = raw.trim().lowercase()
        if (!isEmail(email)) { message(app.getString(R.string.bad_email)); return false }
        return runCatching {
            Cloud.db.collection("freeAccounts").document(email).set(mapOf("addedAt" to FieldValue.serverTimestamp(), "by" to current.email)).await()
            loadFreeList(); true
        }.getOrElse { message(app.getString(R.string.action_failed)); false }
    }

    suspend fun removeFree(email: String) {
        runCatching { Cloud.db.collection("freeAccounts").document(email).delete().await(); loadFreeList() }
    }

    fun isEmail(s: String) = s.matches(Regex("""[^\s@/]+@[^\s@/]+\.[^\s@/]+"""))
}
