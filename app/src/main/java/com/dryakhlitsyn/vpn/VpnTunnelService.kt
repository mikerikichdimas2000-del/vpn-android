package com.dryakhlitsyn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VpnTunnelService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var engineStarted = false
    @Volatile private var startQueued = false
    @Volatile private var stopRequested = false
    @Volatile private var reconnecting = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var sawNetwork = false
    private var lastRecoverAttempt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("start", "action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                log("stop", "user requested")
                stopRequested = true
                worker.execute { stopTunnel() }
            }
            ACTION_START, null -> {
                if (engineStarted || startQueued) return START_STICKY
                stopRequested = false
                startQueued = true
                ensureNotificationChannel()
                startInForeground("Подключаемся к серверу…")
                VpnState.write(this, VpnStatus.CONNECTING, "Связываемся с сервером…")
                worker.execute {
                    try {
                        if (!stopRequested) {
                            startTunnel()
                            if (engineStarted && !stopRequested) watchHandshake()
                        }
                    } finally {
                        startQueued = false
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopRequested = true
        worker.execute { stopTunnel("VPN отключён системой") }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopRequested = true
        if (engineStarted) runCatching { GoEngine.stop() }
        engineStarted = false
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel() {
        val connectStarted = SystemClock.elapsedRealtime()
        try {
            log("tunnel", "begin, ruDirect=${Prefs.ruDirect(this)}, sdk=${Build.VERSION.SDK_INT}")
            val profileText = ProfileStore.load(this)
            val profile = AwgProfile.parse(profileText)
            log("profile", "endpoint=${profile.peer["endpoint"]} mtu=${profile.mtu} keys=${profile.options.keys.sorted().joinToString(",")}")
            log("profile", "json=" + DebugLog.maskKeys(profile.toEngineJson()))
            val builder = Builder()
                .setSession("VPN")
                .setMtu(profile.mtu ?: DEFAULT_MTU)
            profile.addresses.forEach { address ->
                val cidr = parseCidr(address, "Address")
                builder.addAddress(cidr.first, cidr.second)
            }
            profile.dns.forEach(builder::addDnsServer)
            profile.peer["allowedips"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).forEach { route ->
                val cidr = parseCidr(route, "AllowedIPs")
                builder.addRoute(cidr.first, cidr.second)
            }
            if (Prefs.ruDirect(this) && Build.VERSION.SDK_INT >= 33) {
                runCatching { RuRoutes.apply(builder, this) }
                    .onFailure { android.util.Log.w("VpnTunnelService", "exclude RU routes failed", it) }
            }

            log("tunnel", "routes ready in ${SystemClock.elapsedRealtime() - connectStarted} ms")
            val tun = builder.establish() ?: throw IllegalStateException("Android не создал VPN-интерфейс")
            log("tunnel", "tun established in ${SystemClock.elapsedRealtime() - connectStarted} ms")
            val fd = tun.detachFd()
            GoEngine.start(fd, profile.toEngineJson(), ::protect)
            engineStarted = true
            log("engine", "started in ${SystemClock.elapsedRealtime() - connectStarted} ms")
            VpnState.setConnectMillis(this, SystemClock.elapsedRealtime() - connectStarted)
            registerNetworkWatcher()
            VpnState.write(this, VpnStatus.CONNECTING, "Туннель запущен, ждём ответ сервера…")
            updateNotification("Проверяем соединение…")
            awaitHandshake()
        } catch (error: Exception) {
            val message = safeMessage(error)
            log("error", "${error.javaClass.simpleName}: ${error.message}")
            if (engineStarted) runCatching { GoEngine.stop() }
            engineStarted = false
            VpnState.write(this, VpnStatus.ERROR, message)
            updateNotification(message)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun awaitHandshake() {
        val handshakeStarted = SystemClock.elapsedRealtime()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HANDSHAKE_WAIT_SECONDS)
        while (!stopRequested && System.nanoTime() < deadline) {
            val stats = runCatching { JSONObject(GoEngine.stats()) }.getOrNull()
            if (stats != null && stats.optLong("handshakeMs", 0L) > 0L) {
                log("handshake", "ok in ${SystemClock.elapsedRealtime() - handshakeStarted} ms, stats=${stats.toString().take(140)}")
                VpnState.touchHandshake(this)
                VpnState.write(this, VpnStatus.CONNECTED, "Подключено к AmneziaWG")
                updateNotification("VPN подключён")
                return
            }
            try {
                Thread.sleep(HANDSHAKE_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (!stopRequested && engineStarted) {
            val stats = runCatching { JSONObject(GoEngine.stats()) }.getOrNull()
            log("handshake", "timeout $HANDSHAKE_WAIT_SECONDS s, stats=${stats?.toString()?.take(160)}")
            VpnState.write(this, VpnStatus.NO_HANDSHAKE, "Туннель включён, но сервер пока не ответил")
            updateNotification("Туннель включён · нет ответа сервера")
        }
    }


    /** Держит отметку последнего handshake свежей, пока туннель жив. */
    private fun watchHandshake() {
        while (!stopRequested && engineStarted) {
            try {
                Thread.sleep(10_000L)
            } catch (_: InterruptedException) {
                return
            }
            if (stopRequested || !engineStarted) return
            val stats = runCatching { JSONObject(GoEngine.stats()) }.getOrNull() ?: continue
            val handshake = stats.optLong("handshakeMs", 0L)
            if (handshake > 0L) {
                VpnState.touchHandshake(this)
                val age = System.currentTimeMillis() - handshake
                if (age in 0..MAX_HANDSHAKE_AGE_MS) {
                    if (VpnState.read(this).status == VpnStatus.NO_HANDSHAKE) {
                        VpnState.write(this, VpnStatus.CONNECTED, "Связь восстановлена")
                        updateNotification("VPN подключён")
                    }
                } else if (age > MAX_HANDSHAKE_AGE_MS) {
                    if (VpnState.read(this).status == VpnStatus.CONNECTED) {
                        VpnState.write(this, VpnStatus.NO_HANDSHAKE, "Сервер давно не отвечал")
                        updateNotification("Нет ответа сервера")
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRecoverAttempt > RECOVER_RETRY_MS) {
                        lastRecoverAttempt = now
                        scheduleReconnect()
                        return
                    }
                }
            }
        }
    }

    /** Смена сети (Wi-Fi ↔ LTE) — поднимаем туннель заново. */
    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!sawNetwork) {
                    sawNetwork = true
                    return
                }
                scheduleReconnect()
            }
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun scheduleReconnect() {
        if (stopRequested || reconnecting) return
        log("network", "reconnect scheduled")
        reconnecting = true
        worker.execute {
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
                if (stopRequested) return@execute
                runCatching { GoEngine.stop() }
                engineStarted = false
                startTunnel()
                if (engineStarted && !stopRequested) watchHandshake()
            } finally {
                reconnecting = false
            }
        }
    }

    private fun log(tag: String, message: String) = DebugLog.add(this, tag, message)

    private fun stopTunnel(message: String = "") {
        if (engineStarted) runCatching { GoEngine.stop() }
        engineStarted = false
        VpnState.write(this, VpnStatus.DISCONNECTED, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun parseCidr(value: String, field: String): Pair<String, Int> {
        val parts = value.split('/', limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Некорректный CIDR в поле $field")
        val prefix = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Некорректная маска в поле $field")
        val max = if (parts[0].contains(':')) 128 else 32
        if (prefix !in 0..max) throw IllegalArgumentException("Некорректная маска в поле $field")
        return parts[0] to prefix
    }

    private fun safeMessage(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.contains("permission", ignoreCase = true) -> "Нужно разрешить приложению VPN-подключение"
            message.contains("config", ignoreCase = true) || message.contains("профил", ignoreCase = true) -> message
            message.isNotEmpty() -> message.take(120)
            else -> "Не удалось запустить туннель"
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Состояние VPN-подключения" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startInForeground(message: String) {
        val notification = makeNotification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, makeNotification(message))
    }

    private fun makeNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, VpnTunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, NOTIFICATION_CHANNEL) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(message)
            .setContentIntent(openApp)
            .setWhen(if (VpnState.read(this).status == VpnStatus.CONNECTED) VpnState.read(this).connectedAt else 0L)
            .setUsesChronometer(VpnState.read(this).status == VpnStatus.CONNECTED)
            .setOngoing(engineStarted || message.contains("Подключаемся", ignoreCase = true))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_vpn), "Отключить", disconnect).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.dryakhlitsyn.vpn.START"
        const val ACTION_STOP = "com.dryakhlitsyn.vpn.STOP"
        private const val NOTIFICATION_CHANNEL = "vpn-connection"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_MTU = 1280
        private const val HANDSHAKE_WAIT_SECONDS = 15L
        private const val MAX_HANDSHAKE_AGE_MS = 180_000L
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val RECOVER_RETRY_MS = 60_000L
        private const val HANDSHAKE_POLL_MS = 300L
    }
}
