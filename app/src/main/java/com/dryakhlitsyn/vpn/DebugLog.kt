package com.dryakhlitsyn.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Простой файловый лог: пишем только свои события, без системного logcat. */
object DebugLog {
    private const val NAME = "vpn-debug.log"
    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(context: Context, tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val file = File(context.filesDir, NAME)
                if (file.length() > MAX_BYTES) file.delete()
                val line = "${format.format(Date())} [$tag] $message\n"
                file.appendText(line)
            }
        }
    }

    fun read(context: Context): String = runCatching {
        File(context.filesDir, NAME).takeIf { it.isFile }?.readText().orEmpty()
    }.getOrDefault("")

    fun tail(context: Context, lines: Int): String {
        val all = read(context).lines()
        return all.takeLast(lines).joinToString("\n")
    }

    fun clear(context: Context) {
        synchronized(lock) { runCatching { File(context.filesDir, NAME).delete() } }
    }

    /** Прячет значения ключей в JSON, чтобы лог можно было пересылать. */
    fun maskKeys(json: String): String = json
        .replace(Regex("\"(privateKey|publicKey|presharedKey)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"***\"")
}
