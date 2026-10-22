package com.walkmansync.storage

import android.content.Context
import android.content.SharedPreferences
import com.walkmansync.core.model.VolumeBinding
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeRole

/**
 * Rol bağlamalarının kalıcı deposu (SharedPreferences "volume_bindings"). Kimlik = volume UUID + SAF ağaç URI.
 * Bozuk/uyumsuz kayıt sessizce silinir ve "bağlı değil" sayılır.
 */
internal class BindingStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(role: VolumeRole): VolumeBinding? {
        val uuid = prefs.getString(key(role, "uuid"), null) ?: return null
        val uri = prefs.getString(key(role, "uri"), null)
        val docId = prefs.getString(key(role, "docId"), null)
        val volume = VolumeId.of(uuid)
        if (volume == null || uri == null || docId == null) {
            clear(role)
            return null
        }
        return try {
            VolumeBinding(
                role = role,
                volume = volume,
                treeUri = uri,
                treeDocId = docId,
                label = prefs.getString(key(role, "label"), null) ?: volume.uuid,
                boundAtMillis = prefs.getLong(key(role, "boundAt"), 0L),
            )
        } catch (e: IllegalArgumentException) {
            clear(role)
            null
        }
    }

    @Synchronized
    fun put(binding: VolumeBinding) {
        val r = binding.role
        prefs.edit()
            .putString(key(r, "uuid"), binding.volume.uuid)
            .putString(key(r, "uri"), binding.treeUri)
            .putString(key(r, "docId"), binding.treeDocId)
            .putString(key(r, "label"), binding.label)
            .putLong(key(r, "boundAt"), binding.boundAtMillis)
            .commit()
    }

    @Synchronized
    fun clear(role: VolumeRole) {
        prefs.edit()
            .remove(key(role, "uuid")).remove(key(role, "uri")).remove(key(role, "docId"))
            .remove(key(role, "label")).remove(key(role, "boundAt"))
            .commit()
    }

    private fun key(role: VolumeRole, field: String) = "${role.name.lowercase()}_$field"

    private companion object {
        const val PREFS_NAME = "volume_bindings"
    }
}
