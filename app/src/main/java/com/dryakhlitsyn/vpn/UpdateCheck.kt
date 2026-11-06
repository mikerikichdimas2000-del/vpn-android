package com.dryakhlitsyn.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Проверка и установка обновлений без магазина приложений.
 *
 * Манифест лежит рядом с APK в последнем релизе на GitHub и обновляется самим релизом:
 * `https://github.com/<owner>/<repo>/releases/latest/download/latest.json`.
 * Приложение ничего не знает про GitHub — только про этот URL.
 */
object UpdateCheck {
    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val notes: String,
    )

    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val BUFFER_BYTES = 64 * 1024

    class Cancelled : Exception("Скачивание отменено")

    /** Читает манифест последней версии. Блокирующий вызов: только из фонового потока. */
    fun fetch(manifestUrl: String): Manifest {
        require(manifestUrl.startsWith("https://")) { "Манифест обновлений должен быть по https" }
        val body = readBytes(manifestUrl, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw IOException("Сервер вернул не JSON") }

        val versionCode = json.optInt("versionCode", 0)
        if (versionCode <= 0) throw IOException("В манифесте нет versionCode")
        val versionName = json.optString("versionName").trim()
        if (versionName.isEmpty()) throw IOException("В манифесте нет versionName")
        val url = json.optString("url").trim()
        if (!url.startsWith("https://")) throw IOException("В манифесте неверная ссылка на APK")
        val sha256 = json.optString("sha256").trim().lowercase()
        if (!SHA256_PATTERN.matches(sha256)) throw IOException("В манифесте неверная контрольная сумма")

        return Manifest(
            versionCode = versionCode,
            versionName = versionName,
            url = url,
            sha256 = sha256,
            notes = json.optString("notes").trim().take(400),
        )
    }

    /**
     * Качает APK в [directory] и сверяет SHA-256. При любой ошибке файл удаляется,
     * чтобы не остаться с обрезанным пакетом.
     */
    fun download(
        manifest: Manifest,
        directory: File,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Не удалось создать папку для обновления")
        val target = File(directory, "vpn-${manifest.versionCode}.apk")
        if (target.exists() && !target.delete()) throw IOException("Не удалось убрать старый файл обновления")

        val digest = MessageDigest.getInstance("SHA-256")
        var connection: HttpURLConnection? = null
        try {
            connection = open(manifest.url)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Сервер вернул код ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        if (isCancelled()) throw Cancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (total > 0L) {
                            val percent = ((written * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection?.disconnect()
        }

        val actual = digest.digest().toHex()
        if (actual != manifest.sha256) {
            target.delete()
            throw IOException("Файл повреждён: контрольная сумма не совпала")
        }
        return target
    }

    /**
     * Проверяет, что скачанный APK — это наше же приложение, подписанное тем же ключом.
     * Без этой проверки любая подмена манифеста = установка чужого кода с нашими правами.
     */
    fun signatureMatches(context: Context, apk: File): Boolean = runCatching {
        val manager = context.packageManager
        val flags = signatureFlags()
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return@runCatching false
        if (archive.packageName != installed.packageName) return@runCatching false
        val installedSignatures = certificates(installed)
        val archiveSignatures = certificates(archive)
        installedSignatures.isNotEmpty() && installedSignatures == archiveSignatures
    }.getOrDefault(false)

    /** Запускает системный установщик поверх текущей версии. */
    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Android 8+ требует отдельного разрешения «устанавливать приложения». */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun updatesDirectory(context: Context): File = File(context.filesDir, "updates")

    /** Чистит папку обновлений: там не должно копиться больше одного APK. */
    fun clear(context: Context) {
        runCatching {
            updatesDirectory(context).listFiles()?.forEach { it.delete() }
        }
    }

    private fun readBytes(url: String, limit: Int): ByteArray {
        var connection: HttpURLConnection? = null
        try {
            connection = open(url)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Сервер вернул код $code")
            val buffer = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > limit) throw IOException("Ответ сервера слишком большой")
                }
            }
            return buffer.toByteArray()
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        connection.setRequestProperty("User-Agent", "vpn-android/" + BuildConfig.VERSION_NAME)
        return connection
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { signing ->
                if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            }
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { signature -> sha256Hex(signature.toByteArray()) }.toSet()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val HEX = "0123456789abcdef".toCharArray()
}
