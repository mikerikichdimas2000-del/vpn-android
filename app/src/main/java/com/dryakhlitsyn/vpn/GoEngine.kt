package com.dryakhlitsyn.vpn

import android.os.ParcelFileDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Thin reflection bridge so Android Studio can sync before the local Go AAR is generated. */
object GoEngine {
    private const val ENGINE_CLASS = "com.dryakhlitsyn.vpn.engine.Engine"
    private const val PROTECTOR_CLASS = "com.dryakhlitsyn.vpn.engine.SocketProtector"

    fun start(fd: Int, configJson: String, protectSocket: (Int) -> Boolean) {
        var invoked = false
        try {
            val engineClass = Class.forName(ENGINE_CLASS)
            val protectorClass = Class.forName(PROTECTOR_CLASS)
            val protector = Proxy.newProxyInstance(
                protectorClass.classLoader,
                arrayOf(protectorClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "protect" -> protectSocket((args?.firstOrNull() as? Number)?.toInt() ?: -1)
                    "toString" -> "AndroidVpnSocketProtector"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            val method = engineClass.getMethod("start", java.lang.Long.TYPE, String::class.java, protectorClass)
            invoked = true
            method.invoke(null, fd.toLong(), configJson, protector)
        } catch (error: Throwable) {
            if (!invoked || error !is InvocationTargetException) closeOwnedFd(fd)
            throw unwrap(error)
        }
    }

    fun stop() {
        call("stop")
    }

    fun stats(): String = call("stats") as? String
        ?: throw IllegalStateException("Ядро не вернуло состояние подключения")

    private fun call(name: String): Any? {
        try {
            val engineClass = Class.forName(ENGINE_CLASS)
            return engineClass.getMethod(name).invoke(null)
        } catch (error: Throwable) {
            throw unwrap(error)
        }
    }

    private fun unwrap(error: Throwable): RuntimeException {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        return RuntimeException(cause.message?.takeIf(String::isNotBlank) ?: "Не удалось запустить AmneziaWG", cause)
    }

    private fun closeOwnedFd(fd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }
}
