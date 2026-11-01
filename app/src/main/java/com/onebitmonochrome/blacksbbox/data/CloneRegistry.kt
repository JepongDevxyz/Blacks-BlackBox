package com.onebitmonochrome.blacksbbox.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase B registry for unified clones.
 *
 * One clone = one dedicated BlackBox userId (data isolation by construction).
 * Records live in the host app's own SharedPreferences as JSON:
 *   { "packageName": String, "userId": Int, "number": Int, "customName": String? }
 *
 * Lessons carried from the v1 host (CloneStore, stress-fixed):
 *  - the userId allocator is a persisted monotonic counter, NEVER derived
 *    from a list size (a gap would collide) and NEVER reused after a delete
 *    (reuse = two clones silently sharing one data folder);
 *  - per-app display numbers come from a persisted per-app counter, so
 *    deleting "Muse 2" never renumbers "Muse 3" and never reissues 2;
 *  - counters are written with commit() (not apply()) before the record
 *    that uses them;
 *  - parsing is tolerant per record: one bad record must not wipe the
 *    whole store; the previous good JSON is kept as a backup.
 */
data class CloneRecord(
    val packageName: String,
    val userId: Int,
    val number: Int,
    val customName: String?
)

class CloneRegistry(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRecords(): MutableList<CloneRecord> {
        val raw = prefs.getString(KEY_RECORDS, null)
        val parsed = parseRecords(raw)
        if (parsed != null) return parsed
        // Fall back to the previous good copy rather than losing everything.
        return parseRecords(prefs.getString(KEY_RECORDS_BACKUP, null)) ?: mutableListOf()
    }

    private fun parseRecords(raw: String?): MutableList<CloneRecord>? {
        if (raw.isNullOrEmpty()) return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val out = mutableListOf<CloneRecord>()
            for (i in 0 until array.length()) {
                try {
                    val obj = array.getJSONObject(i)
                    val pkg = obj.getString("packageName")
                    val userId = obj.getInt("userId")
                    val number = obj.getInt("number")
                    val custom = if (obj.isNull("customName")) null
                    else obj.optString("customName", "").ifEmpty { null }
                    if (pkg.isNotEmpty() && userId >= 0 && number >= 2) {
                        out.add(CloneRecord(pkg, userId, number, custom))
                    }
                } catch (e: Exception) {
                    // Skip the malformed record, keep the rest.
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun saveRecords(records: List<CloneRecord>) {
        val array = JSONArray()
        for (r in records) {
            val obj = JSONObject()
            obj.put("packageName", r.packageName)
            obj.put("userId", r.userId)
            obj.put("number", r.number)
            if (r.customName != null) obj.put("customName", r.customName)
            array.put(obj)
        }
        val editor = prefs.edit()
        // Keep the previous good value as backup before overwriting.
        prefs.getString(KEY_RECORDS, null)?.let { editor.putString(KEY_RECORDS_BACKUP, it) }
        editor.putString(KEY_RECORDS, array.toString())
        editor.commit()
    }

    /** Replace the whole record list (used when pruning stale records). */
    fun replaceAll(records: List<CloneRecord>) {
        saveRecords(records.toList())
    }

    fun addRecord(record: CloneRecord) {
        val records = loadRecords()
        records.removeAll { it.userId == record.userId }
        records.add(record)
        saveRecords(records)
    }

    fun removeRecord(userId: Int) {
        val records = loadRecords()
        if (records.removeAll { it.userId == userId }) saveRecords(records)
    }

    fun setCustomName(userId: Int, name: String?) {
        val records = loadRecords()
        val idx = records.indexOfFirst { it.userId == userId }
        if (idx >= 0) {
            records[idx] = records[idx].copy(customName = name)
            saveRecords(records)
        }
    }

    /**
     * Allocate the next dedicated userId for a new clone.
     * Starts at 2 (0 = owner profile, 1 = the first upstream profile), is
     * monotonic, and is committed immediately so a crash cannot reissue it.
     */
    fun allocateUserId(): Int {
        val id = prefs.getInt(KEY_NEXT_USER_ID, FIRST_USER_ID)
        prefs.edit().putInt(KEY_NEXT_USER_ID, id + 1).commit()
        return id
    }

    /**
     * Issue the next display number for [packageName] ("Muse 2", "Muse 3", ...).
     * The persisted counter starts at 1, so the first clone is number 2.
     */
    fun issueNumber(packageName: String): Int {
        val key = KEY_COUNTER_PREFIX + packageName
        val next = prefs.getInt(key, 1) + 1
        prefs.edit().putInt(key, next).commit()
        return next
    }

    companion object {
        private const val PREFS_NAME = "CloneRegistry"
        private const val KEY_RECORDS = "records"
        private const val KEY_RECORDS_BACKUP = "records_backup"
        private const val KEY_NEXT_USER_ID = "nextUserId"
        private const val KEY_COUNTER_PREFIX = "counter:"
        private const val FIRST_USER_ID = 2
    }
}
