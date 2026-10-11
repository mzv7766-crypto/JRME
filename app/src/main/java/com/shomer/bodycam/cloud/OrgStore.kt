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
) {
    fun toMap() = mapOf(
        "preRecordSeconds" to preRecordSeconds, "recordAudio" to recordAudio, "fallDetection" to fallDetection,
        "autoLock" to autoLock, "allowDiscard" to allowDiscard, "allowDelete" to allowDelete,
    )

    companion object {
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

    fun onSignedIn(e: String?) {
        if (e == email && !listeners.isEmpty) return
        email = e
        listeners.clear(); orgListeners.clear()
        _state.value = OrgState(loading = e != null)
        SettingsStore.setPolicy(null, null)
        if (e == null || !Cloud.configured) { _state.value = OrgState(); return }
        listeners += Cloud.index(e).addSnapshotListener { snap, err ->
            if (err != null) { Log.w(Cloud.TAG, "index", err); _state.update { it.copy(loading = false) }; return@addSnapshotListener }
            val orgId = snap?.getString("orgId")
            val role = snap?.getString("role")
            orgListeners.clear()
            if (orgId == null) { _state.value = OrgState(); SettingsStore.setPolicy(null, null); return@addSnapshotListener }
            _state.update { it.copy(role = role, loading = true) }
            orgListeners += Cloud.org(orgId).addSnapshotListener { o, oe ->
                if (oe != null || o == null || !o.exists()) { _state.update { it.copy(loading = false, org = null) }; applyPolicy(); return@addSnapshotListener }
                _state.update { it.copy(org = parseOrg(o), loading = false) }
                applyPolicy()
            }
            if (role == "member") orgListeners += Cloud.org(orgId).collection("members").document(e).addSnapshotListener { m, _ ->
                _state.update { it.copy(me = m?.takeIf { d -> d.exists() }?.let { d -> MemberInfo(e, d.getString("name").orEmpty(), d.getString("badge").orEmpty()) }) }
                applyPolicy()
            }
        }
    }

    private fun applyPolicy() {
        val s = current
        if (s.activeMember) SettingsStore.setPolicy(s.org!!.policy, s.me!!.stamp) else SettingsStore.setPolicy(null, null)
    }

    private fun parseOrg(d: DocumentSnapshot): OrgInfo {
        @Suppress("UNCHECKED_CAST") val plan = d.get("plan") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val managers = (d.get("managers") as? List<String>).orEmpty()
        return OrgInfo(
            id = d.id, name = d.getString("name").orEmpty(), ownerEmail = d.getString("ownerEmail").orEmpty(), managers = managers,
            seats = d.getLong("seats")?.toInt() ?: 0, memberCount = d.getLong("memberCount")?.toInt() ?: 0,
            planSource = plan?.get("source") as? String, planBase = plan?.get("basePlan") as? String, planToken = plan?.get("token") as? String,
            policy = Policy.from(d.get("policy")),
        )
    }

    // ------------------------------------------------------------------ manager actions

    /** Opens a new company with the signed-in user as its owner/manager (no devices until a plan is bought). */
    suspend fun createOrg(name: String): Boolean {
        val me = email ?: return false
        return runCatching {
            val ref = Cloud.db.collection("shomer_orgs").document()
            Cloud.db.batch()
                .set(ref, mapOf(
                    "name" to name.trim().take(80), "ownerEmail" to me, "managers" to listOf(me),
                    "seats" to 0, "memberCount" to 0, "policy" to Policy().toMap(), "createdAt" to FieldValue.serverTimestamp(),
                ))
                .set(Cloud.index(me), mapOf("orgId" to ref.id, "role" to "manager"))
                .commit().await()
            true
        }.onFailure { Log.w(Cloud.TAG, "create org", it) }.getOrDefault(false)
    }

    fun applyTeamPurchase(basePlan: String, seats: Int, token: String) {
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
