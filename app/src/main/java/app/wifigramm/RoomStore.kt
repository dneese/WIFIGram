package app.wifigramm

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers the room of the phone, so the code and the password are typed
 * only once.
 */
object RoomStore {

    private const val PREFS = "wifigramm_room"
    private const val KEY_CODE = "code"
    private const val KEY_PASSWORD = "password"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_MODE = "mode"
    private const val KEY_HOST = "host"

    data class Room(
        val code: String,
        val password: String,
        val nickname: String,
        val direct: Boolean,
        val host: Boolean
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Room? {
        val code = prefs(context).getString(KEY_CODE, null) ?: return null
        if (code.isBlank()) return null
        return Room(
            code = code,
            password = prefs(context).getString(KEY_PASSWORD, "") ?: "",
            nickname = prefs(context).getString(KEY_NICKNAME, "") ?: "",
            direct = prefs(context).getString(KEY_MODE, "lan") == "direct",
            host = prefs(context).getString(KEY_HOST, "host") == "host"
        )
    }

    fun save(context: Context, room: Room) {
        prefs(context).edit()
            .putString(KEY_CODE, room.code)
            .putString(KEY_PASSWORD, room.password)
            .putString(KEY_NICKNAME, room.nickname)
            .putString(KEY_MODE, if (room.direct) "direct" else "lan")
            .putString(KEY_HOST, if (room.host) "host" else "guest")
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
