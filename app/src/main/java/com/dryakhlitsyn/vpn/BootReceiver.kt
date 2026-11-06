package com.dryakhlitsyn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

/** Возвращает туннель после перезагрузки, если пользователь включил автоподключение. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.autoconnect(context)) return
        if (!runCatching { ProfileStore.hasProfile(context) }.getOrDefault(false)) return
        // Согласие на VPN уже дано раньше; если система его отозвала — молча выходим.
        if (runCatching { VpnService.prepare(context) }.getOrNull() != null) return

        val start = Intent(context, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start)
            else context.startService(start)
        }
    }
}
