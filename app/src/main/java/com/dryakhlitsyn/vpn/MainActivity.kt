package com.dryakhlitsyn.vpn

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class Screen { HOME, PROFILE, HELP, DIAGNOSTICS }

    private class Page(val scroll: ScrollView, val root: LinearLayout)

    private lateinit var palette: Palette
    private lateinit var content: FrameLayout
    private var screen = Screen.HOME

    private var pendingConnectAfterNotification = false
    private var pendingConnectAfterProfile = false
    private var receiverRegistered = false
    private var serverHost: String? = null

    private var updateBusy = false
    @Volatile private var updateCancelled = false
    private var availableUpdate: UpdateCheck.Manifest? = null
    private var pendingUpdate: UpdateCheck.Manifest? = null
    private var updateExecutor: ExecutorService? = null

    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var timerView: TextView? = null
    private var switchView: SwitchView? = null
    private var profileValueView: TextView? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            ticker.postDelayed(this, 1000L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (screen == Screen.HOME) updateHome(VpnState.read(this@MainActivity)) else render()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Palettes.resolve(this)
        content = FrameLayout(this).apply { setBackgroundColor(palette.background) }
        setContentView(content)
        applyInsets(content)
        applySystemBars()
        registerStateReceiver()
        DebugLog.add(this, "app", "onCreate v${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT}")
        serverHost = readServerHost()
        render()
        ticker.post(tick)

        if (intent.getBooleanExtra(EXTRA_CONNECT_FROM_TILE, false)) {
            content.post { startFromTile() }
        }
    }

    override fun onBackPressed() {
        if (screen == Screen.HOME) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            back()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONNECT_FROM_TILE, false)) content.post { startFromTile() }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) {
            serverHost = readServerHost()
            if (screen == Screen.HOME) updateHome(VpnState.read(this)) else render()
            // Вернулись из настроек «Установка неизвестных приложений» — продолжаем начатое обновление.
            val pending = pendingUpdate
            if (pending != null && UpdateCheck.canInstall(this)) {
                pendingUpdate = null
                content.post { startDownload(pending) }
            }
        }
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        updateExecutor?.shutdownNow()
        if (receiverRegistered) runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        content.removeAllViews()
        titleView = null
        subtitleView = null
        timerView = null
        switchView = null
        profileValueView = null
        val view = when (screen) {
            Screen.HOME -> buildHome()
            Screen.PROFILE -> buildProfile()
            Screen.HELP -> buildHelp()
            Screen.DIAGNOSTICS -> buildDiagnostics()
        }
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (screen == Screen.HOME) updateHome(VpnState.read(this))
    }

    private fun buildHome(): View {
        val page = columnScroll()
        val root = page.root
        root.addView(buildHeader())

        titleView = text("Отключено", 54f, palette.primary, bold = true, letterSpacing = -0.035f).apply {
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 28, 54, 1, TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM,
            )
        }
        root.addView(titleView)

        subtitleView = text("Профиль не выбран", 20f, palette.secondary, bold = true).apply {
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(subtitleView)

        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(56)))

        val connection = card()
        connection.addView(buildConnectionRow())
        root.addView(connection, margins(top = 0, bottom = 16))

        val list = card()
        list.addView(listRow("Профиль", "→") {
            screen = Screen.PROFILE
            render()
        })
        list.addView(divider())
        list.addView(listRow("Автоподключение", if (Prefs.autoconnect(this)) "вкл" else "выкл") { toggleAutoconnect() })
        list.addView(divider())
        list.addView(listRow("RU-адреса — напрямую", if (Prefs.ruDirect(this)) "вкл" else "выкл") { toggleRuDirect() })
        list.addView(divider())
        list.addView(listRow("Быстрый старт", if (Prefs.fastRoutes(this)) "вкл" else "выкл") { toggleFastRoutes() })
        list.addView(divider())
        list.addView(listRow("Тема", Prefs.themeLabel(this)) { cycleTheme() })
        list.addView(divider())
        list.addView(listRow("Помощь", "3 шага →") {
            screen = Screen.HELP
            render()
        })
        list.addView(divider())
        list.addView(listRow("Обновление", updateLabel()) { checkForUpdates() })
        root.addView(list)

        root.addView(spacer(1f))
        return page.scroll
    }

    private fun buildConnectionRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text("Подключение", 16f, palette.primary, bold = true))
        timerView = text("AmneziaWG · без аккаунта", 12f, palette.secondary).apply {
            setPadding(0, dp(4), 0, 0)
        }
        column.addView(timerView)
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = SwitchView(this, palette)
        toggle.isChecked = VpnState.read(this).status.active
        toggle.onCheckedChange = { checked ->
            if (checked) connect() else stopTunnelService()
        }
        switchView = toggle
        row.addView(toggle, LinearLayout.LayoutParams(dp(64), dp(38)))
        return row
    }

    private fun updateHome(snapshot: VpnSnapshot) {
        val server = serverHost
        titleView?.text = when (snapshot.status) {
            VpnStatus.DISCONNECTED -> "Отключено"
            VpnStatus.CONNECTING -> "Подключаемся"
            VpnStatus.CONNECTED -> "Защищено"
            VpnStatus.NO_HANDSHAKE -> "Нет ответа"
            VpnStatus.ERROR -> "Ошибка"
        }
        titleView?.setTextColor(if (snapshot.status == VpnStatus.ERROR) palette.danger else palette.primary)

        subtitleView?.text = when (snapshot.status) {
            VpnStatus.DISCONNECTED -> server ?: "Профиль не выбран"
            VpnStatus.CONNECTING -> snapshot.message.ifBlank { "Связываемся с сервером…" }
            VpnStatus.CONNECTED -> server ?: "AmneziaWG"
            VpnStatus.NO_HANDSHAKE -> "Туннель включён, сервер молчит"
            VpnStatus.ERROR -> snapshot.message.ifBlank { "Не удалось подключиться" }.take(80)
        }
        subtitleView?.setTextColor(if (snapshot.status == VpnStatus.ERROR) palette.danger else palette.secondary)

        switchView?.isChecked = snapshot.status.active
        profileValueView?.text = if (server == null) "не выбран →" else "готов →"
        updateTimer()
    }

    private fun updateTimer() {
        val view = timerView ?: return
        val snapshot = VpnState.read(this)
        val text = when {
            snapshot.status == VpnStatus.CONNECTED && snapshot.connectedAt > 0L -> {
                val seconds = ((System.currentTimeMillis() - snapshot.connectedAt) / 1000L).coerceAtLeast(0L)
                val session = String.format(
                    Locale.US,
                    "%02d:%02d:%02d",
                    seconds / 3600,
                    (seconds % 3600) / 60,
                    seconds % 60,
                )
                val age = if (snapshot.lastHandshakeAt > 0L) {
                    ((System.currentTimeMillis() - snapshot.lastHandshakeAt) / 1000L).coerceAtLeast(0L)
                } else {
                    -1L
                }
                if (age >= 0L) "$session · связь ${age} с назад" else session
            }
            snapshot.status == VpnStatus.CONNECTING -> "Связываемся с сервером…"
            else -> "AmneziaWG · без аккаунта"
        }
        if (view.text != text) view.text = text
    }

    private fun buildProfile(): View {
        val page = columnScroll()
        val root = page.root
        root.addView(backRow { back() })

        root.addView(text("Профиль", 40f, palette.primary, bold = true, letterSpacing = -0.03f).apply {
            setPadding(0, dp(18), 0, 0)
        })
        val endpoint = readEndpoint()
        root.addView(text(endpoint ?: "Ничего не импортировано", 17f, palette.secondary, bold = true).apply {
            setPadding(0, dp(7), 0, dp(22))
        })

        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(32)))

        val card = card()
        card.addView(actionRow("Сканировать QR", "камера →") {
            if (checkCameraPermission()) launchQrScanner() else requestCameraPermission()
        })
        card.addView(divider())
        card.addView(actionRow("Выбрать .conf", "файл →") { openProfilePicker() })
        if (ProfileStore.hasProfile(this)) {
            card.addView(divider())
            card.addView(actionRow("Удалить профиль", "", danger = true) { confirmRemoveProfile() })
        }
        root.addView(card)

        root.addView(spacer(0.6f))
        return page.scroll
    }

    private fun buildHelp(): View {
        val page = columnScroll()
        val root = page.root
        root.addView(backRow { back() })

        root.addView(text("Как подключиться", 36f, palette.primary, bold = true, letterSpacing = -0.03f).apply {
            setPadding(0, dp(18), 0, dp(18))
        })

        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(32)))

        val card = card()
        card.addView(stepRow("1", "Получи свой .conf", "Попроси отдельный файл для этого телефона у того, кто выдал доступ. Чужой файл не подойдёт."))
        card.addView(divider())
        card.addView(stepRow("2", "Загрузи профиль", "«Профиль» → «Сканировать QR» или «Выбрать .conf». Ключи сохранятся только на телефоне."))
        card.addView(divider())
        card.addView(stepRow("3", "Включи тумблер", "Android один раз спросит разрешение на VPN — разреши. Готово."))
        root.addView(card)

        root.addView(spacer(0.6f))
        return page.scroll
    }


    private fun buildDiagnostics(): View {
        val page = columnScroll()
        val root = page.root
        root.addView(backRow { back() })

        root.addView(text("Диагностика", 34f, palette.primary, bold = true, letterSpacing = -0.03f).apply {
            setPadding(0, dp(18), 0, dp(18))
        })

        val snapshot = VpnState.read(this)
        val endpoint = readEndpoint()
        val permission = if (Build.VERSION.SDK_INT < 14) true else VpnService.prepare(this) == null
        val handshakeAge = if (snapshot.lastHandshakeAt > 0L) {
            ((System.currentTimeMillis() - snapshot.lastHandshakeAt) / 1000L).coerceAtLeast(0L)
        } else {
            -1L
        }

        val card = card()
        card.addView(infoRow("Профиль", if (ProfileStore.hasProfile(this)) "загружен" else "нет"))
        card.addView(divider())
        card.addView(infoRow("Разрешение VPN", if (permission) "выдано" else "не выдано"))
        card.addView(divider())
        card.addView(infoRow("Сервер", endpoint ?: "—"))
        card.addView(divider())
        card.addView(infoRow("Последний handshake", if (handshakeAge >= 0L) "$handshakeAge с назад" else "не было"))
        card.addView(divider())
        card.addView(infoRow("Подключение заняло", if (snapshot.connectMillis > 0L) "${snapshot.connectMillis} мс" else "—"))
        card.addView(divider())
        card.addView(infoRow("DNS", readDns().ifEmpty { "из профиля" }))
        card.addView(divider())
        card.addView(infoRow("RU-маршрутов", if (Prefs.ruDirect(this) && Build.VERSION.SDK_INT >= 33) "${RuRoutes.count(this)} · ${if (Prefs.fastRoutes(this)) "быстрый" else "полный"}" else "выключено"))
        card.addView(divider())
        card.addView(infoRow("Версия", "${BuildConfig.VERSION_NAME} · API ${Build.VERSION.SDK_INT}"))
        card.addView(divider())
        card.addView(infoRow("Обновления", lastUpdateCheckLabel()))
        card.addView(divider())
        card.addView(infoRow("Состояние", "${snapshot.status} ${snapshot.message}".trim().take(40)))
        card.addView(divider())
        card.addView(infoRow("Поля конфига", readOptionKeys()))
        card.addView(divider())
        card.addView(infoRow("Движок", readEngineStats()))
        root.addView(card)

        val logCard = card()
        logCard.addView(infoRow("Лог", "${DebugLog.read(this).lines().size} строк"))
        logCard.addView(divider())
        logCard.addView(actionRow("Поделиться логом", "→") { shareLog() })
        logCard.addView(divider())
        logCard.addView(actionRow("Скопировать", "→") { copyLog() })
        logCard.addView(divider())
        logCard.addView(actionRow("Очистить лог", "", danger = true) {
            DebugLog.clear(this)
            render()
        })
        root.addView(logCard, margins(top = 14))

        val tail = text(DebugLog.tail(this, 14).ifBlank { "лог пуст" }, 9.5f, palette.tertiary)
        tail.typeface = android.graphics.Typeface.MONOSPACE
        tail.setLineSpacing(dpF(2f), 1f)
        root.addView(tail, margins(top = 12))

        val note = text("Открывается долгим нажатием на «VPN» в шапке.", 11f, palette.tertiary)
        note.setLineSpacing(dpF(3f), 1f)
        root.addView(note, margins(top = 14))
        return page.scroll
    }

    private fun shareLog() {
        val body = DebugLog.read(this).ifBlank { "лог пуст" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VPN debug log")
            putExtra(Intent.EXTRA_TEXT, body.takeLast(40_000))
        }
        startActivity(Intent.createChooser(intent, "Отправить лог"))
    }

    private fun copyLog() {
        val body = DebugLog.read(this).ifBlank { "лог пуст" }
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("vpn-log", body.takeLast(40_000)))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun infoRow(title: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(17), dp(20), dp(17))
        }
        row.addView(text(title, 14.5f, palette.primary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(value, 13f, palette.secondary, gravity = Gravity.END))
        return row
    }

    private fun readOptionKeys(): String = runCatching {
        val options = AwgProfile.parse(ProfileStore.load(this)).options.keys
        if (options.isEmpty()) "нет" else options.sorted().joinToString(",").take(60)
    }.getOrDefault("нет профиля")

    private fun readEngineStats(): String = runCatching {
        GoEngine.stats().take(60)
    }.getOrElse { "нет данных" }

    private fun readDns(): String = runCatching {
        AwgProfile.parse(ProfileStore.load(this)).dns.joinToString(", ")
    }.getOrDefault("")

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply { background = rounded(palette.accent, 999) }
        row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(9) })
        row.addView(text("VPN", 20f, palette.primary, bold = true))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val chip = text("AMNEZIAWG", 9f, palette.secondary, bold = true, letterSpacing = 0.18f).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(palette.chipBackground, 999, palette.chipBorder)
        }
        row.addView(chip)
        row.isLongClickable = true
        row.setOnLongClickListener {
            screen = Screen.DIAGNOSTICS
            render()
            true
        }
        return row
    }

    private fun backRow(onBack: () -> Unit): View = text("←  Назад", 14f, palette.secondary, bold = true).apply {
        setPadding(0, dp(10), 0, dp(6))
        isClickable = true
        setOnClickListener { onBack() }
    }

    private fun back() {
        screen = Screen.HOME
        serverHost = readServerHost()
        render()
    }

    // ---------------------------------------------------------------- building blocks

    private fun columnScroll(): Page {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setBackgroundColor(palette.background)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(20))
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -1))
        return Page(scroll, column)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.surface, 28, palette.cardBorder)
    }

    private fun listRow(title: String, value: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(text(title, 16f, palette.primary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val trailing = text(value, 13f, palette.secondary)
        if (title == "Профиль") profileValueView = trailing
        row.addView(trailing)
        return row
    }

    private fun actionRow(title: String, value: String, danger: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            text(title, 16f, if (danger) palette.danger else palette.primary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (value.isNotEmpty()) row.addView(text(value, 13f, palette.secondary))
        return row
    }

    private fun stepRow(number: String, title: String, body: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        val badge = text(number, 13f, palette.accent, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(if (palette.dark) 0xFF13251F.toInt() else 0xFFE8F8F1.toInt(), 999)
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(1) })
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        column.addView(text(title, 15f, palette.primary, bold = true))
        val description = text(body, 12.5f, palette.secondary)
        description.setLineSpacing(dpF(3f), 1f)
        description.setPadding(0, dp(5), 0, 0)
        column.addView(description)
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(palette.divider)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
        }
    }

    private fun margins(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun spacer(weight: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 0, weight)
    }

    private fun footerText(): String =
        if (Build.VERSION.SDK_INT >= 24) "Плитка VPN — в шторке" else "Плитка появится в шторке на Android 7+"

    // ---------------------------------------------------------------- actions

    private fun toggleAutoconnect() {
        val next = !Prefs.autoconnect(this)
        Prefs.setAutoconnect(this, next)
        Toast.makeText(this, if (next) "Автоподключение включено" else "Автоподключение выключено", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun toggleRuDirect() {
        val next = !Prefs.ruDirect(this)
        Prefs.setRuDirect(this, next)
        if (next && Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Разделение адресов доступно с Android 13", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                if (next) "Российские адреса пойдут напрямую" else "Весь трафик пойдёт через VPN",
                Toast.LENGTH_SHORT,
            ).show()
        }
        render()
        if (VpnState.read(this).status.active) {
            startService(Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_STOP))
            content.postDelayed({
                startService(Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_START))
            }, 900L)
        }
    }

    private fun toggleFastRoutes() {
        val next = !Prefs.fastRoutes(this)
        Prefs.setFastRoutes(this, next)
        Toast.makeText(
            this,
            if (next) "Быстрый старт: меньше маршрутов, подключение за ~1 с" else "Полный список: все RU-адреса напрямую",
            Toast.LENGTH_LONG,
        ).show()
        render()
        restartTunnelIfActive()
    }

    private fun restartTunnelIfActive() {
        if (VpnState.read(this).status.active) {
            startService(Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_STOP))
            content.postDelayed({
                startService(Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_START))
            }, 900L)
        }
    }

    private fun cycleTheme() {
        val next = when (Prefs.themeMode(this)) {
            Prefs.THEME_AUTO -> Prefs.THEME_LIGHT
            Prefs.THEME_LIGHT -> Prefs.THEME_DARK
            else -> Prefs.THEME_AUTO
        }
        Prefs.setThemeMode(this, next)
        recreate()
    }

    private fun connect() {
        DebugLog.add(this, "app", "connect pressed")
        if (!ProfileStore.hasProfile(this)) {
            Toast.makeText(this, "Сначала выбери свой профиль AmneziaWG", Toast.LENGTH_SHORT).show()
            screen = Screen.PROFILE
            render()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnectAfterNotification = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        requestVpnPermission()
    }

    private fun requestVpnPermission() {
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQUEST_VPN_PERMISSION) else startTunnelService()
    }

    private fun startFromTile() {
        if (!ProfileStore.hasProfile(this)) {
            pendingConnectAfterProfile = true
            screen = Screen.PROFILE
            render()
            return
        }
        requestVpnPermission()
    }

    private fun startTunnelService() {
        val start = Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start) else startService(start)
    }

    private fun stopTunnelService() {
        startService(Intent(this, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_STOP))
    }

    private fun showTileHelp() {
        if (Build.VERSION.SDK_INT < 24) return
        val request = if (Build.VERSION.SDK_INT >= 33) {
            val manager = getSystemService(StatusBarManager::class.java)
            if (manager == null) false else runCatching {
                manager.requestAddTileService(
                    ComponentName(this, VpnTileService::class.java),
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    Executors.newSingleThreadExecutor(),
                ) { }
                true
            }.getOrDefault(false)
        } else {
            false
        }
        val text = if (request) {
            "Открыл системное окно: подтверди добавление плитки VPN."
        } else {
            "Открой шторку, нажми «Изменить» (или карандаш) и перетащи плитку «VPN» наверх."
        }
        AlertDialog.Builder(this)
            .setTitle("Плитка в шторке")
            .setMessage(text)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun confirmRemoveProfile() {
        AlertDialog.Builder(this)
            .setTitle("Удалить профиль?")
            .setMessage("Ключи будут стёрты с телефона. Чтобы подключиться снова, понадобится новый .conf.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                ProfileStore.clear(this)
                stopTunnelService()
                VpnState.write(this, VpnStatus.DISCONNECTED, "")
                serverHost = null
                Toast.makeText(this, "Профиль удалён", Toast.LENGTH_SHORT).show()
                screen = Screen.HOME
                render()
            }
            .show()
    }

    // ---------------------------------------------------------------- обновления

    private fun executor(): ExecutorService =
        updateExecutor ?: Executors.newSingleThreadExecutor().also { updateExecutor = it }

    private fun updateLabel(): String =
        availableUpdate?.let { "${it.versionName} →" } ?: BuildConfig.VERSION_NAME

    private fun lastUpdateCheckLabel(): String {
        val checkedAt = Prefs.lastUpdateCheckAt(this)
        if (checkedAt == 0L) return "не проверялось"
        val format = SimpleDateFormat("d MMMM, HH:mm", Locale("ru"))
        return format.format(Date(checkedAt))
    }

    private fun checkForUpdates() {
        if (updateBusy) return
        updateBusy = true
        Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        executor().execute {
            val result = runCatching { UpdateCheck.fetch(BuildConfig.UPDATE_MANIFEST_URL) }
            runOnUiThread {
                updateBusy = false
                result.onSuccess { manifest ->
                    Prefs.setLastUpdateCheckAt(this, System.currentTimeMillis())
                    DebugLog.add(this, "update", "manifest v${manifest.versionName} (${manifest.versionCode})")
                    if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                        UpdateCheck.clear(this)
                        availableUpdate = manifest
                        render()
                        offerUpdate(manifest)
                    } else {
                        availableUpdate = null
                        render()
                        Toast.makeText(
                            this,
                            "Установлена последняя версия ${BuildConfig.VERSION_NAME}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }.onFailure { error ->
                    DebugLog.add(this, "update", "check failed: ${error.message}")
                    Toast.makeText(
                        this,
                        error.message ?: "Не удалось проверить обновления",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun offerUpdate(manifest: UpdateCheck.Manifest) {
        val message = buildString {
            append("Сейчас установлено: ${BuildConfig.VERSION_NAME}\n")
            if (manifest.notes.isNotEmpty()) append("\n${manifest.notes}\n")
            append("\nПрофиль и настройки останутся на месте.")
        }
        AlertDialog.Builder(this)
            .setTitle("Доступно обновление ${manifest.versionName}")
            .setMessage(message)
            .setNegativeButton("Позже", null)
            .setPositiveButton("Скачать и установить") { _, _ -> startUpdate(manifest) }
            .show()
    }

    private fun startUpdate(manifest: UpdateCheck.Manifest) {
        if (!UpdateCheck.canInstall(this)) {
            pendingUpdate = manifest
            AlertDialog.Builder(this)
                .setTitle("Разреши установку")
                .setMessage(
                    "Android требует разово разрешить установку обновлений из этого приложения. " +
                        "Открою нужный экран — включи переключатель и вернись сюда, скачивание продолжится само.",
                )
                .setNegativeButton("Отмена") { _, _ -> pendingUpdate = null }
                .setPositiveButton("Открыть") { _, _ -> UpdateCheck.openInstallSettings(this) }
                .show()
            return
        }
        startDownload(manifest)
    }

    private fun startDownload(manifest: UpdateCheck.Manifest) {
        updateCancelled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Скачиваем ${manifest.versionName}")
            .setMessage("0 %")
            .setCancelable(false)
            .setNegativeButton("Отмена") { _, _ -> updateCancelled = true }
            .create()
        dialog.show()

        executor().execute {
            val result = runCatching {
                val directory = UpdateCheck.updatesDirectory(this)
                val file = UpdateCheck.download(manifest, directory, { updateCancelled }) { percent ->
                    runOnUiThread { if (dialog.isShowing) dialog.setMessage("$percent %") }
                }
                if (!UpdateCheck.signatureMatches(this, file)) {
                    file.delete()
                    throw IllegalStateException("Подпись не совпала — обновление отклонено")
                }
                file
            }
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                result.onSuccess { file ->
                    DebugLog.add(this, "update", "downloaded ${file.name}")
                    runCatching { UpdateCheck.launchInstaller(this, file) }.onFailure { error ->
                        DebugLog.add(this, "update", "installer failed: ${error.message}")
                        Toast.makeText(this, "Не удалось открыть установщик", Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    when (error) {
                        is UpdateCheck.Cancelled -> Toast.makeText(this, "Скачивание отменено", Toast.LENGTH_SHORT).show()
                        else -> {
                            DebugLog.add(this, "update", "download failed: ${error.message}")
                            Toast.makeText(
                                this,
                                error.message ?: "Не удалось скачать обновление",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- profile import

    private fun openProfilePicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
        }
        startActivityForResult(picker, REQUEST_PROFILE)
    }

    private fun importProfile(data: Intent?) {
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalArgumentException("Не удалось открыть файл")
            importProfileText(text)
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Не удалось прочитать профиль", Toast.LENGTH_LONG).show()
        }
    }

    private fun importProfileText(text: String) {
        try {
            if (text.length > MAX_PROFILE_CHARS) throw IllegalArgumentException("Профиль слишком большой")
            AwgProfile.parse(text)
            ProfileStore.save(this, text)
            serverHost = readServerHost()
            VpnState.write(this, VpnStatus.DISCONNECTED, "Профиль сохранён на устройстве")
            Toast.makeText(this, "Профиль сохранён", Toast.LENGTH_SHORT).show()
            screen = Screen.HOME
            render()
            if (pendingConnectAfterProfile) {
                pendingConnectAfterProfile = false
                connect()
            }
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Не удалось прочитать профиль", Toast.LENGTH_LONG).show()
        }
    }

    private fun readServerHost(): String? = runCatching {
        val endpoint = AwgProfile.parse(ProfileStore.load(this)).peer["endpoint"] ?: return@runCatching null
        endpoint.substringBeforeLast(':').ifBlank { endpoint }
    }.getOrNull()

    private fun readEndpoint(): String? = runCatching {
        AwgProfile.parse(ProfileStore.load(this)).peer["endpoint"]
    }.getOrNull()

    private fun hasCamera(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun checkCameraPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (!hasCamera()) {
            Toast.makeText(this, "На этом устройстве нет камеры — используй файл .conf", Toast.LENGTH_LONG).show()
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    private fun launchQrScanner() {
        startActivityForResult(Intent(this, QrScanActivity::class.java), REQUEST_QR)
    }

    // ---------------------------------------------------------------- plumbing

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PROFILE -> if (resultCode == RESULT_OK) importProfile(data) else pendingConnectAfterProfile = false
            REQUEST_QR -> if (resultCode == RESULT_OK) {
                data?.getStringExtra(QrScanActivity.EXTRA_TEXT)?.let { importProfileText(it) }
            }
            REQUEST_VPN_PERMISSION -> {
                if (resultCode == RESULT_OK) startTunnelService()
                else Toast.makeText(this, "Для подключения нужно разрешить VPN", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATIONS -> if (pendingConnectAfterNotification) {
                pendingConnectAfterNotification = false
                requestVpnPermission()
            }
            REQUEST_CAMERA -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchQrScanner()
                else Toast.makeText(this, "Без камеры можно выбрать .conf файлом", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter(VpnState.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    /** Android 15+ рисует приложение под статус-баром и навигацией — добавляем отступы. */
    private fun applyInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !palette.dark
        controller.isAppearanceLightNavigationBars = !palette.dark
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    companion object {
        const val EXTRA_CONNECT_FROM_TILE = "connect-from-tile"
        private const val REQUEST_PROFILE = 10
        private const val REQUEST_VPN_PERMISSION = 11
        private const val REQUEST_NOTIFICATIONS = 12
        private const val REQUEST_CAMERA = 13
        private const val REQUEST_QR = 14
        private const val MAX_PROFILE_CHARS = 32_768
    }
}
