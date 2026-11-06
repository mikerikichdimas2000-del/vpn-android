package com.dryakhlitsyn.vpn

import android.content.Context
import android.net.IpPrefix
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress

/**
 * Исключает российские префиксы из туннеля. Разобранный список кешируется
 * в бинарный файл, чтобы не парсить 10 831 строку на каждый коннект.
 */
object RuRoutes {
    private const val TAG = "RuRoutes"
    private const val CACHE_NAME = "ru-routes.bin"
    private const val CACHE_VERSION = 1
    private const val MAGIC = 0x52554331
    private fun files(context: Context) =
        if (Prefs.fastRoutes(context)) listOf("ru-v4-fast.txt") else listOf("ru-v4.txt", "ru-v6.txt")

    private fun cacheName(context: Context) =
        if (Prefs.fastRoutes(context)) "ru-routes-fast.bin" else CACHE_NAME

    @Volatile
    private var cached: List<IpPrefix>? = null

    fun apply(builder: VpnService.Builder, context: Context): Int {
        val startedAt = SystemClock.elapsedRealtime()
        val prefixes = obtain(context)
        val loadedAt = SystemClock.elapsedRealtime()
        for (prefix in prefixes) builder.excludeRoute(prefix)
        val addedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "excluded ${prefixes.size}: load ${loadedAt - startedAt} ms, add ${addedAt - loadedAt} ms")
        return prefixes.size
    }

    fun count(context: Context): Int = runCatching { obtain(context).size }.getOrDefault(0)

    private fun obtain(context: Context): List<IpPrefix> {
        cached?.let { return it }
        val loaded = readCache(context) ?: build(context).also { writeCache(context, it) }
        cached = loaded
        return loaded
    }

    private fun readCache(context: Context): List<IpPrefix>? = runCatching {
        val file = File(context.filesDir, cacheName(context))
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC) return null
            if (input.readInt() != CACHE_VERSION) return null
            val count = input.readInt()
            if (count <= 0 || count > 50_000) return null
            val list = ArrayList<IpPrefix>(count)
            repeat(count) {
                val family = input.readByte().toInt()
                if (family != 4 && family != 16) return null
                val length = input.readByte().toInt()
                val bytes = ByteArray(family)
                input.readFully(bytes)
                list.add(IpPrefix(InetAddress.getByAddress(bytes), length))
            }
            list
        }
    }.getOrNull()

    private fun writeCache(context: Context, prefixes: List<IpPrefix>) = runCatching {
        val file = File(context.filesDir, cacheName(context))
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(CACHE_VERSION)
            out.writeInt(prefixes.size)
            for (prefix in prefixes) {
                val bytes = prefix.address.address
                out.writeByte(bytes.size)
                out.writeByte(prefix.prefixLength)
                out.write(bytes)
            }
        }
    }.onFailure { Log.w(TAG, "cannot write cache", it) }

    private fun build(context: Context): List<IpPrefix> {
        val prefixes = ArrayList<IpPrefix>(11_000)
        for (file in files(context)) {
            runCatching {
                context.assets.open(file).bufferedReader().useLines { lines ->
                    lines.forEach { line -> parse(line)?.let(prefixes::add) }
                }
            }.onFailure { Log.w(TAG, "cannot read $file", it) }
        }
        return prefixes
    }

    private fun parse(line: String): IpPrefix? {
        val value = line.trim()
        if (value.isEmpty() || value.startsWith("#")) return null
        val slash = value.indexOf('/')
        if (slash <= 0) return null
        val length = value.substring(slash + 1).toIntOrNull() ?: return null
        val address = value.substring(0, slash)
        if (address.contains(':')) {
            if (length !in 0..128) return null
            val ip = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return null
            return runCatching { IpPrefix(ip, length) }.getOrNull()
        }
        if (length !in 0..32) return null
        val octets = address.split('.')
        if (octets.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val octet = octets[i].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            bytes[i] = octet.toByte()
        }
        return runCatching { IpPrefix(InetAddress.getByAddress(bytes), length) }.getOrNull()
    }
}
