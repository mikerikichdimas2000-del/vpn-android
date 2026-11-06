package com.dryakhlitsyn.vpn

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class VpnTileService : TileService() {
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshTile()
            ticker.postDelayed(this, 30_000L)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
        ticker.postDelayed(tick, 30_000L)
    }

    override fun onStopListening() {
        ticker.removeCallbacksAndMessages(null)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val snapshot = VpnState.read(this)
        if (snapshot.status.active) {
            startService(Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_STOP))
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = getString(R.string.tile_label)
                updateTile()
            }
            return
        }

        if (!ProfileStore.hasProfile(this) || VpnService.prepare(this) != null) {
            openAppForSetup()
            return
        }

        val start = Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start) else startService(start)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "VPN · запуск"
            updateTile()
        }
    }

    private fun refreshTile() {
        val active = VpnState.read(this).status.active
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (active) tileLabel() else getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= 29) stateDescription = if (active) "Подключён" else "Отключён"
            updateTile()
        }
    }

    private fun tileLabel(): String {
        val snapshot = VpnState.read(this)
        if (snapshot.status != VpnStatus.CONNECTED || snapshot.connectedAt <= 0L) return "VPN · вкл"
        val seconds = ((System.currentTimeMillis() - snapshot.connectedAt) / 1000L).coerceAtLeast(0L)
        return String.format(java.util.Locale.US, "VPN · %02d:%02d", seconds / 60, seconds % 60)
    }

    private fun openAppForSetup() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONNECT_FROM_TILE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
