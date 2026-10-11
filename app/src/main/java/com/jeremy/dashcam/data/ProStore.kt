package com.jeremy.dashcam.data

import android.content.Context
import android.content.SharedPreferences
import com.jeremy.dashcam.core.pro.Account
import com.jeremy.dashcam.core.pro.Billing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Jeremy Pro state.
 *  - Google sign-in (Firebase Auth) is needed only for Pro.
 *  - Pro = an active Google Play subscription, OR the admin, OR an e-mail on the admin's free-accounts list (Firestore).
 *  - The last known values are cached so Pro keeps working offline and at boot (e.g. car Bluetooth auto-start).
 */
data class ProState(
    /** Firebase is configured in this build (google-services.json present). */
    val configured: Boolean = false,
    val email: String? = null,
    val name: String? = null,
    val isFreeAccount: Boolean = false,
    /** Active Play subscription (incl. free trial and cancelled-but-not-yet-expired). */
    val subscribed: Boolean = false,
    val autoRenewing: Boolean = false,
    /** Play Billing: product found in the store (false until it's created in Play Console). */
    val productAvailable: Boolean = false,
    /** This Play account is eligible for the free-trial offer. */
    val trialAvailable: Boolean = false,
    val price: String = ProStore.PRICE,
    val busy: Boolean = false,
    val message: String? = null,
    /** Why the free-account check failed (shown to a signed-in non-Pro user); null when fine. */
    val freeError: String? = null,
    /** Admin only: e-mails with free Pro. */
    val freeAccounts: List<String> = emptyList(),
) {
    val isAdmin get() = email.equals(ProStore.ADMIN_EMAIL, ignoreCase = true)
    val isPro get() = subscribed || (email != null && (isAdmin || isFreeAccount))
}

object ProStore {
    const val ADMIN_EMAIL = "mzv7766@gmail.com"
    const val PRICE = "₪9.90"
    const val TRIAL_DAYS = 7

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(ProState())
    val state: StateFlow<ProState> = _state.asStateFlow()
    val current get() = _state.value

    fun init(c: Context) {
        if (::prefs.isInitialized) return
        val app = c.applicationContext
        prefs = app.getSharedPreferences("jeremy_pro", Context.MODE_PRIVATE)
        _state.value = ProState(
            email = prefs.getString("email", null),
            name = prefs.getString("name", null),
            isFreeAccount = prefs.getBoolean("free", false),
            // a cached subscription is trusted for 7 days without a fresh confirmation from Google Play
            subscribed = prefs.getBoolean("subscribed", false) &&
                System.currentTimeMillis() - prefs.getLong("verifiedAt", 0L) < 7L * 24 * 60 * 60 * 1000,
            autoRenewing = prefs.getBoolean("autoRenew", false),
        )
        Account.init(app)
        Billing.init(app)
    }

    fun update(f: (ProState) -> ProState) {
        _state.update(f)
        val s = _state.value
        prefs.edit()
            .putString("email", s.email)
            .putString("name", s.name)
            .putBoolean("free", s.isFreeAccount)
            .putBoolean("subscribed", s.subscribed)
            .putBoolean("autoRenew", s.autoRenewing)
            .apply()
    }

    fun message(text: String?) = update { it.copy(message = text) }

    /** Google Play just confirmed the subscription state. */
    fun markVerified() = prefs.edit().putLong("verifiedAt", System.currentTimeMillis()).apply()
}
