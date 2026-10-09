package com.jeremy.dashcam.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DEMO ONLY – simulates Jeremy Pro (Google sign-in, Play subscription, admin-managed free accounts)
 * locally on the phone. No real login, no real billing, no server.
 */
enum class ProPlan { NONE, TRIAL, ACTIVE, CANCELLED }

data class ProState(
    val email: String? = null,
    val plan: ProPlan = ProPlan.NONE,
    val trialEndsAt: Long = 0L,
    val renewsAt: Long = 0L,
    val freeAccounts: Set<String> = emptySet(),
    val usedTrialEmails: Set<String> = emptySet(),
) {
    val isAdmin get() = email.equals(ProStore.ADMIN_EMAIL, ignoreCase = true)
    val isFreeAccount get() = email != null && freeAccounts.any { it.equals(email, ignoreCase = true) }
    val trialActive get() = plan == ProPlan.TRIAL && System.currentTimeMillis() < trialEndsAt
    val subscribed get() = plan == ProPlan.ACTIVE || trialActive || (plan == ProPlan.CANCELLED && System.currentTimeMillis() < renewsAt)
    val isPro get() = email != null && (isAdmin || isFreeAccount || subscribed)
    val canStartTrial get() = email != null && usedTrialEmails.none { it.equals(email, ignoreCase = true) }
}

object ProStore {
    const val ADMIN_EMAIL = "mzv7766@gmail.com"
    const val PRICE = "₪9.90"
    const val TRIAL_DAYS = 7
    private const val DAY = 24L * 60 * 60 * 1000

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(ProState())
    val state: StateFlow<ProState> = _state.asStateFlow()

    fun init(c: Context) {
        if (::prefs.isInitialized) return
        prefs = c.getSharedPreferences("jeremy_pro_demo", Context.MODE_PRIVATE)
        _state.value = ProState(
            email = prefs.getString("email", null),
            plan = runCatching { ProPlan.valueOf(prefs.getString("plan", "NONE")!!) }.getOrDefault(ProPlan.NONE),
            trialEndsAt = prefs.getLong("trialEnds", 0),
            renewsAt = prefs.getLong("renews", 0),
            freeAccounts = prefs.getStringSet("free", emptySet())!!.toSet(),
            usedTrialEmails = prefs.getStringSet("usedTrial", emptySet())!!.toSet(),
        )
    }

    private fun set(f: (ProState) -> ProState) {
        val s = f(_state.value)
        _state.value = s
        prefs.edit()
            .putString("email", s.email)
            .putString("plan", s.plan.name)
            .putLong("trialEnds", s.trialEndsAt)
            .putLong("renews", s.renewsAt)
            .putStringSet("free", s.freeAccounts)
            .putStringSet("usedTrial", s.usedTrialEmails)
            .apply()
    }

    fun signIn(email: String) = set { it.copy(email = email.trim().lowercase(), plan = ProPlan.NONE) }
    fun signOut() = set { it.copy(email = null, plan = ProPlan.NONE, trialEndsAt = 0, renewsAt = 0) }

    fun startTrial() = set {
        val now = System.currentTimeMillis()
        it.copy(plan = ProPlan.TRIAL, trialEndsAt = now + TRIAL_DAYS * DAY, renewsAt = now + TRIAL_DAYS * DAY,
            usedTrialEmails = it.usedTrialEmails + (it.email ?: ""))
    }
    fun subscribe() = set { it.copy(plan = ProPlan.ACTIVE, renewsAt = System.currentTimeMillis() + 30 * DAY) }
    fun cancel() = set { it.copy(plan = ProPlan.CANCELLED) }
    /** Demo helper: pretend the trial is over. */
    fun endTrialNow() = set { if (it.plan == ProPlan.TRIAL) it.copy(plan = ProPlan.ACTIVE, renewsAt = System.currentTimeMillis() + 30 * DAY) else it }

    fun addFree(email: String) {
        val e = email.trim().lowercase()
        if (e.contains("@")) set { it.copy(freeAccounts = it.freeAccounts + e) }
    }
    fun removeFree(email: String) = set { it.copy(freeAccounts = it.freeAccounts - email) }

    fun daysLeft(until: Long): Int = (((until - System.currentTimeMillis()) + DAY - 1) / DAY).toInt().coerceAtLeast(0)
}
