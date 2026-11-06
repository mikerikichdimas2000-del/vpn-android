package com.dryakhlitsyn.vpn

import android.content.Context
import android.content.Intent

enum class VpnStatus(val active: Boolean) {
    DISCONNECTED(false),
    CONNECTING(true),
    CONNECTED(true),
    NO_HANDSHAKE(true),
    ERROR(false),
}

data class VpnSnapshot(
    val status: VpnStatus,
    val message: String,
    val connectedAt: Long,
    val lastHandshakeAt: Long = 0L,
    val connectMillis: Long = 0L,
)

object VpnState {
    const val ACTION_STATE = "com.dryakhlitsyn.vpn.STATE"
    const val EXTRA_STATUS = "status"
    const val EXTRA_MESSAGE = "message"
    const val PREFS = "vpn-state"
    const val KEY_STATUS = "status"
    const val KEY_MESSAGE = "message"
    const val KEY_CONNECTED_AT = "connected-at"
    const val KEY_HANDSHAKE_AT = "handshake-at"
    const val KEY_CONNECT_MS = "connect-ms"
    const val KEY_TILE_PROMPTED = "tile-prompted"

    fun read(context: Context): VpnSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = runCatching {
            VpnStatus.valueOf(prefs.getString(KEY_STATUS, VpnStatus.DISCONNECTED.name)!!)
        }.getOrDefault(VpnStatus.DISCONNECTED)
        return VpnSnapshot(
            status = status,
            message = prefs.getString(KEY_MESSAGE, "") ?: "",
            connectedAt = prefs.getLong(KEY_CONNECTED_AT, 0L),
            lastHandshakeAt = prefs.getLong(KEY_HANDSHAKE_AT, 0L),
            connectMillis = prefs.getLong(KEY_CONNECT_MS, 0L),
        )
    }

    fun write(context: Context, status: VpnStatus, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = read(context).status
        val editor = prefs.edit()
            .putString(KEY_STATUS, status.name)
            .putString(KEY_MESSAGE, message)
        when {
            status == VpnStatus.CONNECTED && previous != VpnStatus.CONNECTED ->
                editor.putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
            !status.active -> editor.remove(KEY_CONNECTED_AT)
        }
        editor.apply()
        context.sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATUS, status.name)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    fun touchHandshake(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_HANDSHAKE_AT, System.currentTimeMillis())
            .apply()
    }

    fun setConnectMillis(context: Context, millis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CONNECT_MS, millis)
            .apply()
    }
}
