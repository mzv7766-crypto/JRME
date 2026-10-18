package com.shomer.bodycam.cloud

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.shomer.bodycam.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Rules a company sets for all its guards. Null = the guard chooses.
 * Live view and remote recording always notify the guard; that is not configurable.
 */
data class Policy(
    val preRecordSeconds: Int? = 30,
    val recordAudio: Boolean? = true,
    val fallDetection: Boolean? = true,
    /** Location is always on for a company phone during a shift (the control room needs it). */
    val autoLock: Boolean = true,
    val allowDiscard: Boolean = false,
    val allowDelete: Boolean = false,
    /** Company cloud backup of every recording: "all" networks, "wifi" only, or "off". */
    val cloudBackup: String = CLOUD_ALL,
    /** Cloud recordings are deleted after this many days (unless marked "keep"). */
    val retentionDays: Int = 30,
) {
    fun toMap() = mapOf(
        "preRecordSeconds" to preRecordSeconds, "recordAudio" to recordAudio, "fallDetection" to fallDetection,
        "autoLock" to autoLock, "allowDiscard" to allowDiscard, "allowDelete" to allowDelete,
        "cloudBackup" to cloudBackup, "retentionDays" to retentionDays,
    )

    companion object {
        const val CLOUD_ALL = "all"
        const val CLOUD_WIFI = "wifi"
        const val CLOUD_OFF = "off"
        val RETENTION_OPTIONS = listOf(7, 30, 60, 90, 180)

        @Suppress("UNCHECKED_CAST")
        fun from(m: Any?): Policy {
            val p = m as? Map<String, Any?> ?: return Policy()
            return Policy(
                preRecordSeconds = (p["preRecordSeconds"] as? Number)?.toInt(),
                recordAudio = p["recordAudio"] as? Boolean,
                fallDetection = p["fallDetection"] as? Boolean,
                autoLock = p["autoLock"] as? Boolean ?: true,
                allowDiscard = p["allowDiscard"] as? Boolean ?: false,
                allowDelete = p["allowDelete"] as? Boolean ?: false,
                cloudBackup = (p["cloudBackup"] as? String)?.takeIf { it in listOf(CLOUD_ALL, CLOUD_WIFI, CLOUD_OFF) } ?: CLOUD_ALL,
                retentionDays = (p["retentionDays"] as? Number)?.toInt()?.takeIf { it > 0 } ?: 30,
            )
        }
    }
}

data class OrgInfo(
    val id: String,
    val name: String,
    val ownerEmail: String,
    val managers: List<String>,
    val seats: Int,
    val memberCount: Int,
    val planSource: String?,
    val planBase: String?,
    val planToken: String?,
    val policy: Policy,
    /** Google Play confirmed the payment on the server. */
    val planVerified: Boolean = false,
    val planExpiry: Long = 0L,
)

data class MemberInfo(val email: String, val name: String, val badge: String) {
    /** What is stamped in the corner of the video. */
    val stamp get() = listOf(name, badge).filter { it.isNotBlank() }.joinToString(" ")
}

data class OrgState(
    val loading: Boolean = false,
    val role: String? = null,      // "manager" | "member" | null
    val org: OrgInfo? = null,
    val me: MemberInfo? = null,
    val error: String? = null,
) {
    val isManager get() = role == "manager" && org != null
    val isMember get() = role == "member" && org != null && me != null
    /** A guard of a company with a paid plan: Pro features + reporting to the control room. */
    val activeMember get() = isMember && (org?.seats ?: 0) > 0
}

/** The company the signed-in Google account belongs to (if any), live from Firestore. */
object OrgStore {
    private val _state = MutableStateFlow(OrgState())
    val state: StateFlow<OrgState> = _state.asStateFlow()
    val current get() = _state.value
    private val listeners = Listeners()
    private val orgListeners = Listeners()
    private var email: String? = null

    private var retryMs = 5_000L
    private var retryJob: kotlinx.coroutines.Job? = null

    fun onSignedIn(e: String?) {
        if (e == email && !listeners.isEmpty) return
        email = e
        subscribe(fresh = true)
    }

    /** Re-reads the company after a sign-in, an error or coming back to the app. Keeps the last known data meanwhile. */
    fun refresh() { if (email != null) subscribe(fresh = false) }

    private fun subscribe(fresh: Boolean) {
        val e = email
        retryJob?.cancel(); retryJob = null
        listeners.clear(); orgListeners.clear()
        if (fresh) { _state.value = OrgState(loading = e != null); applyPolicy() }
        if (e == null || !Cloud.configured) { _state.value = OrgState(); applyPolicy(); return }
        listeners += Cloud.index(e).addSnapshotListener { snap, err ->
            if (err != null) { onError("index", err); return@addSnapshotListener }
            retryMs = 5_000L
            val orgId = snap?.getString("orgId")
            val role = snap?.getString("role")
            orgListeners.clear()
            if (orgId == null) { _state.value = OrgState(); applyPolicy(); return@addSnapshotListener }
            _state.update { it.copy(role = role, loading = it.org?.id != orgId, error = null) }
            orgListeners += Cloud.org(orgId).addSnapshotListener { o, oe ->
                if (oe != null) { onError("org", oe); return@addSnapshotListener }
                if (o == null || !o.exists()) { _state.update { it.copy(loading = false, org = null) }; applyPolicy(); return@addSnapshotListener }
                _state.update { it.copy(org = parseOrg(o), loading = false, error = null) }
                applyPolicy()
            }
            if (role == "member") orgListeners += Cloud.org(orgId).collection("members").document(e).addSnapshotListener { m, me ->
                if (me != null) { onError("member", me); return@addSnapshotListener }
                _state.update { it.copy(me = m?.takeIf { d -> d.exists() }?.let { d -> MemberInfo(e, d.getString("name").orEmpty(), d.getString("badge").orEmpty()) }) }
                applyPolicy()
            }
        }
    }

    /**
     * Firestore drops a listener after an error (no network at start-up, rules just changed, sign-in token
     * still refreshing). Keep the last known company – the guard must not lose Pro or the company rules
     * because of a hiccup – and try again with a growing delay.
     */
    private fun onError(where: String, e: Exception) {
        Log.w(Cloud.TAG, "company $where", e)
        _state.update { it.copy(loading = false, error = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: e.javaClass.simpleName) }
        if (retryJob?.isActive == true) return
        val wait = retryMs
        retryMs = (retryMs * 2).coerceAtMost(120_000L)
        retryJob = Cloud.scope.launch { kotlinx.coroutines.delay(wait); subscribe(fresh = false) }
    }

    private fun applyPolicy() {
        val s = current
        if (s.activeMember) SettingsStore.setPolicy(s.org!!.policy, s.me!!.stamp) else SettingsStore.setPolicy(null, null)
        // the manager of a company with a plan gets the Pro features too (without the guards' rules)
        SettingsStore.setOrgPro(s.isManager && (s.org?.seats ?: 0) > 0)
    }

    private fun parseOrg(d: DocumentSnapshot): OrgInfo {
        @Suppress("UNCHECKED_CAST") val plan = d.get("plan") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val managers = (d.get("managers") as? List<String>).orEmpty()
        return OrgInfo(
            id = d.id, name = d.getString("name").orEmpty(), ownerEmail = d.getString("ownerEmail").orEmpty(), managers = managers,
            seats = d.getLong("seats")?.toInt() ?: 0, memberCount = d.getLong("memberCount")?.toInt() ?: 0,
            planSource = plan?.get("source") as? String, planBase = plan?.get("basePlan") as? String, planToken = plan?.get("token") as? String,
            policy = Policy.from(d.get("policy")),
            planVerified = plan?.get("verified") == true, planExpiry = (plan?.get("expiry") as? Number)?.toLong() ?: 0L,
        )
    }

    // ------------------------------------------------------------------ manager actions

    /** Opens a new company with the signed-in user as its owner/manager (no devices until a plan is bought). */
    /** Opens a new company. Returns null on success, else the reason (shown to the user). */
    suspend fun createOrg(name: String): String? {
        val me = email ?: Cloud.email() ?: return "NOT_SIGNED_IN"
        if (email == null) onSignedIn(me)
        return try {
            val ref = Cloud.db.collection("shomer_orgs").document()
            kotlinx.coroutines.withTimeout(25_000) {
                Cloud.db.batch()
                    .set(ref, mapOf(
                        "name" to name.trim().take(80), "ownerEmail" to me, "managers" to listOf(me),
                        "seats" to 0, "memberCount" to 0, "policy" to Policy().toMap(), "createdAt" to FieldValue.serverTimestamp(),
                    ))
                    .set(Cloud.index(me), mapOf("orgId" to ref.id, "role" to "manager"))
                    .commit().await()
            }
            refresh()
            null
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "TIMEOUT"
        } catch (e: Exception) {
            Log.w(Cloud.TAG, "create org", e)
            (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: (e.javaClass.simpleName + ": " + e.message)
        }
    }

    private var verifying: String? = null

    /**
     * The server asks Google Play whether this company plan is really paid and only then gives the company
     * its devices. Until the server is deployed (function missing) the old client-side write is used.
     */
    fun verifyTeam(token: String, basePlanHint: String?) {
        val org = current.org ?: return
        if (!current.isManager || verifying == token) return
        // already confirmed for this token and not near renewal
        if (org.planVerified && org.planToken == token && org.planExpiry > System.currentTimeMillis() + 24 * 3600_000L) return
        verifying = token
        Cloud.scope.launch {
            try {
                Cloud.functions.getHttpsCallable("verifyTeam").call(mapOf("orgId" to org.id, "token" to token)).await()
                Log.i(Cloud.TAG, "plan verified by server")
            } catch (e: Exception) {
                val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
                Log.w(Cloud.TAG, "verify plan: $code", e)
                val ours = e.message?.let { it.contains("PLAY") || it.contains("NO_COMPANY") } == true
                if (code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND && !ours) {
                    // no server yet → old way (the old rules allow it; the new rules don't)
                    val seats = Billing.seatsOf(basePlanHint)
                    if (basePlanHint != null && seats != null && org.planToken != token) applyTeamPurchase(basePlanHint, seats, token)
                } else if (code != null) {
                    AccountStore.messageRes(com.shomer.bodycam.R.string.plan_verify_failed, e.message ?: code.name)
                }
            } finally {
                verifying = null
            }
        }
    }

    private fun applyTeamPurchase(basePlan: String, seats: Int, token: String) {
        val org = current.org ?: return
        if (!current.isManager) return
        Cloud.org(org.id).update(mapOf(
            "seats" to seats,
            "plan" to mapOf("source" to "play", "basePlan" to basePlan, "token" to token, "by" to email, "at" to System.currentTimeMillis()),
        )).addOnFailureListener { Log.w(Cloud.TAG, "apply plan", it) }
    }

    enum class AddResult { OK, NO_SEATS, TAKEN, BAD_EMAIL, FAILED }

    suspend fun addMember(rawEmail: String, name: String, badge: String): AddResult {
        val org = current.org ?: return AddResult.FAILED
        val e = rawEmail.trim().lowercase()
        if (!AccountStore.isEmail(e)) return AddResult.BAD_EMAIL
        if (org.memberCount >= org.seats) return AddResult.NO_SEATS
        return try {
            Cloud.db.batch()
                .set(Cloud.org(org.id).collection("members").document(e), mapOf("name" to name.trim().take(60), "badge" to badge.trim().take(32), "addedBy" to email, "addedAt" to FieldValue.serverTimestamp()))
                .update(Cloud.org(org.id), "memberCount", FieldValue.increment(1))
                .set(Cloud.index(e), mapOf("orgId" to org.id, "role" to "member"))
                .commit().await()
            AddResult.OK
        } catch (ex: Exception) {
            Log.w(Cloud.TAG, "add member", ex)
            // the e-mail already belongs to a company (its index can't be overwritten)
            if (runCatching { Cloud.index(e).get().await().exists() }.getOrDefault(false)) AddResult.TAKEN else AddResult.FAILED
        }
    }

    suspend fun updateMember(e: String, name: String, badge: String): Boolean {
        val org = current.org ?: return false
        return runCatching { Cloud.org(org.id).collection("members").document(e).update(mapOf("name" to name.trim().take(60), "badge" to badge.trim().take(32))).await(); true }.getOrDefault(false)
    }

    suspend fun removeMember(e: String): Boolean {
        val org = current.org ?: return false
        return runCatching {
            Cloud.db.batch()
                .delete(Cloud.org(org.id).collection("members").document(e))
                .update(Cloud.org(org.id), "memberCount", FieldValue.increment(-1))
                .delete(Cloud.index(e))
                .commit().await()
            true
        }.onFailure { Log.w(Cloud.TAG, "remove member", it) }.getOrDefault(false)
    }

    suspend fun setPolicy(p: Policy): Boolean {
        val org = current.org ?: return false
        return runCatching { Cloud.org(org.id).set(mapOf("policy" to p.toMap()), SetOptions.merge()).await(); true }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ system admin

    data class AdminOrg(val id: String, val name: String, val owner: String, val seats: Int, val members: Int, val source: String?)

    suspend fun adminList(): List<AdminOrg> = runCatching {
        Cloud.db.collection("shomer_orgs").get().await().documents.map { d ->
            @Suppress("UNCHECKED_CAST") val plan = d.get("plan") as? Map<String, Any?>
            AdminOrg(d.id, d.getString("name").orEmpty(), d.getString("ownerEmail").orEmpty(),
                d.getLong("seats")?.toInt() ?: 0, d.getLong("memberCount")?.toInt() ?: 0, plan?.get("source") as? String)
        }.sortedBy { it.name }
    }.onFailure { Log.w(Cloud.TAG, "admin list", it) }.getOrDefault(emptyList())

    /** Free devices for a company (the rules allow this only for the system admin). */
    suspend fun adminSetSeats(orgId: String, seats: Int): Boolean = runCatching {
        Cloud.org(orgId).update(mapOf("seats" to seats, "plan" to mapOf("source" to "admin", "by" to email, "at" to System.currentTimeMillis()))).await()
        true
    }.onFailure { Log.w(Cloud.TAG, "admin seats", it) }.getOrDefault(false)

    // ------------------------------------------------------------------ control-room data (manager screens)

    data class Member(val email: String, val name: String, val badge: String)
    data class Device(
        val email: String, val onShift: Boolean, val recording: Boolean, val trigger: String?, val battery: Int?,
        val lat: Double?, val lon: Double?, val acc: Float?, val updatedAt: Long, val live: Boolean, val model: String?,
    ) {
        /** No report for 2 minutes while "on shift" = the phone lost network or was switched off. */
        fun stale(now: Long) = onShift && now - updatedAt > 120_000
    }
    data class Event(val id: String, val type: String, val email: String, val name: String, val at: Long, val lat: Double?, val lon: Double?, val acked: Boolean, val detail: String?)

    val members = MutableStateFlow<List<Member>>(emptyList())
    val devices = MutableStateFlow<List<Device>>(emptyList())
    val events = MutableStateFlow<List<Event>>(emptyList())
    private val boardListeners = Listeners()
    private var boardUsers = 0

    /** Called by manager screens while visible (reference counted). */
    fun openBoard() {
        boardUsers++
        val org = current.org ?: return
        if (!boardListeners.isEmpty) return
        val o = Cloud.org(org.id)
        boardListeners += o.collection("members").addSnapshotListener { s, _ ->
            members.value = s?.documents.orEmpty().map { Member(it.id, it.getString("name").orEmpty(), it.getString("badge").orEmpty()) }.sortedBy { it.name.ifBlank { it.email } }
        }
        boardListeners += o.collection("devices").addSnapshotListener { s, _ ->
            devices.value = s?.documents.orEmpty().map { d ->
                Device(
                    d.id, d.getBoolean("onShift") == true, d.getBoolean("recording") == true, d.getString("trigger"),
                    d.getLong("battery")?.toInt(), d.getDouble("lat"), d.getDouble("lon"), d.getDouble("acc")?.toFloat(),
                    d.getLong("updatedAt") ?: 0L, d.getBoolean("live") == true, d.getString("model"),
                )
            }
        }
        boardListeners += o.collection("events").orderBy("at", Query.Direction.DESCENDING).limit(100).addSnapshotListener { s, _ ->
            events.value = s?.documents.orEmpty().map { d ->
                Event(d.id, d.getString("type").orEmpty(), d.getString("email").orEmpty(), d.getString("name").orEmpty(),
                    d.getLong("at") ?: 0L, d.getDouble("lat"), d.getDouble("lon"), d.getString("ackBy") != null, d.getString("detail"))
            }
        }
    }

    fun closeBoard() {
        boardUsers = (boardUsers - 1).coerceAtLeast(0)
        if (boardUsers == 0) boardListeners.clear()
    }

    // ------------------------------------------------------------------ company cloud recordings

    data class CloudRec(
        val id: String, val email: String, val name: String, val link: String, val start: Long, val durationMs: Long,
        val size: Long, val trigger: String?, val sha256: String?, val lat: Double?, val lon: Double?, val uploadedAt: Long, val keep: Boolean,
    )

    val recordings = MutableStateFlow<List<CloudRec>>(emptyList())
    private val recListeners = Listeners()
    private var recUsers = 0

    fun openRecordings() {
        recUsers++
        val org = current.org ?: return
        if (!recListeners.isEmpty) return
        recListeners += Cloud.org(org.id).collection("recordings").orderBy("start", Query.Direction.DESCENDING).limit(300).addSnapshotListener { s, e ->
            if (e != null) { Log.w(Cloud.TAG, "recordings", e); return@addSnapshotListener }
            recordings.value = s?.documents.orEmpty().map { d ->
                CloudRec(
                    d.id, d.getString("email").orEmpty(), d.getString("name").orEmpty(),
                    d.getString("link") ?: d.getString("driveId")?.let { DriveBackup.openUrl(it) }.orEmpty(),
                    d.getLong("start") ?: 0L, d.getLong("durationMs") ?: 0L, d.getLong("size") ?: 0L, d.getString("trigger"),
                    d.getString("sha256"), d.getDouble("lat"), d.getDouble("lon"), d.getLong("uploadedAt") ?: 0L, d.getBoolean("keep") == true,
                )
            }
        }
    }

    fun closeRecordings() {
        recUsers = (recUsers - 1).coerceAtLeast(0)
        if (recUsers == 0) recListeners.clear()
    }

    fun setKeep(r: CloudRec, keep: Boolean) {
        val org = current.org ?: return
        Cloud.org(org.id).collection("recordings").document(r.id).update("keep", keep)
    }

    /** Removes the recording from the list (the video itself stays in the guard's Drive until its retention ends). */
    suspend fun removeRecording(r: CloudRec): Boolean {
        val org = current.org ?: return false
        return runCatching { Cloud.org(org.id).collection("recordings").document(r.id).delete().await(); true }
            .onFailure { Log.w(Cloud.TAG, "remove recording", it) }.getOrDefault(false)
    }

    /** start / stop a recording on a guard's phone. */
    fun sendCommand(guard: String, type: String) {
        val org = current.org ?: return
        Cloud.org(org.id).collection("cmd").document(guard)
            .set(mapOf("id" to java.util.UUID.randomUUID().toString(), "type" to type, "by" to email, "at" to System.currentTimeMillis()))
    }

    fun ackEvent(id: String) {
        val org = current.org ?: return
        Cloud.org(org.id).collection("events").document(id).update(mapOf("ackBy" to email, "ackAt" to System.currentTimeMillis()))
    }
}
