package com.financedashboard.app.data

import android.content.Context

/**
 * Multi-profile support without any cloud: each profile is an independent,
 * separately-encrypted database file on this device. The registry (names +
 * which is active) lives in plain prefs because it must be readable before any
 * database opens; the sensitive data itself is in each profile's encrypted DB.
 *
 * The first profile keeps the original database filename and key so existing
 * installs migrate seamlessly into "Personal".
 */
object ProfileManager {

    data class Profile(val id: String, val name: String)

    private const val PREFS = "profiles"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    const val DEFAULT_ID = "default"
    private const val SEP = "\u001F"
    private const val REC = "\u001E"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun profiles(context: Context): List<Profile> {
        val raw = prefs(context).getString(KEY_LIST, null)
        if (raw.isNullOrBlank()) {
            val seed = listOf(Profile(DEFAULT_ID, "Personal"))
            save(context, seed)
            return seed
        }
        return raw.split(REC).filter { it.isNotBlank() }.mapNotNull {
            val p = it.split(SEP)
            if (p.size == 2) Profile(p[0], p[1]) else null
        }
    }

    fun activeId(context: Context): String {
        val id = prefs(context).getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID
        // Guard against a dangling active id after a delete.
        return if (profiles(context).any { it.id == id }) id else DEFAULT_ID
    }

    fun activeProfile(context: Context): Profile =
        profiles(context).firstOrNull { it.id == activeId(context) } ?: Profile(DEFAULT_ID, "Personal")

    fun add(context: Context, name: String): Profile {
        val id = "p_" + System.currentTimeMillis().toString(36)
        val profile = Profile(id, name.ifBlank { "Profile" })
        save(context, profiles(context) + profile)
        return profile
    }

    fun rename(context: Context, id: String, name: String) {
        save(context, profiles(context).map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Deletes a profile and its database. The default profile cannot be deleted. */
    fun delete(context: Context, id: String) {
        if (id == DEFAULT_ID) return
        save(context, profiles(context).filter { it.id != id })
        if (activeId(context) == id) setActive(context, DEFAULT_ID)
        // Remove the database files for the deleted profile.
        val base = dbFileName(id)
        listOf("", "-wal", "-shm", ".plain-bak").forEach { context.getDatabasePath(base + it).delete() }
    }

    /** Switches the active profile. The caller must recreate the Activity so a fresh DB opens. */
    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Original filename for the default profile; suffixed for others. */
    fun dbFileName(id: String): String =
        if (id == DEFAULT_ID) "finance-dashboard.db" else "finance-dashboard-$id.db"

    private fun save(context: Context, list: List<Profile>) {
        prefs(context).edit()
            .putString(KEY_LIST, list.joinToString(REC) { "${it.id}$SEP${it.name}" })
            .apply()
    }
}
