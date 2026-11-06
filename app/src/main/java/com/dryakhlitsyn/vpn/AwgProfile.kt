package com.dryakhlitsyn.vpn

import org.json.JSONArray
import org.json.JSONObject

/** A single AmneziaWG client profile; this app intentionally supports one peer. */
data class AwgProfile(
    val privateKey: String,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int?,
    val options: Map<String, String>,
    val peer: Map<String, String>,
) {
    fun toEngineJson(): String {
        val result = JSONObject()
            .put("privateKey", privateKey)
            .put("addresses", JSONArray(addresses))
            .put("dns", JSONArray(dns))
        if (mtu != null) result.put("mtu", mtu)

        val intFields = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
        intFields.forEach { field -> options[field]?.toIntOrNull()?.let { result.put(field, it) } }
        listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5").forEach { field ->
            options[field]?.let { result.put(field, it) }
        }
        listOf(
            "headerProtectionKey" to "headerprotectionkey",
            "contentPadding" to "contentpadding",
            "rekeyAfterTime" to "rekeyaftertime",
            "rekeyTimeout" to "rekeytimeout",
            "rejectAfterTime" to "rejectaftertime",
            "keepaliveTimeout" to "keepalivetimeout",
            "maxHandshakeAttempts" to "maxhandshakeattempts",
        ).forEach { (jsonName, configName) -> options[configName]?.let { result.put(jsonName, it) } }
        options["randomtrailers"]?.let { result.put("randomTrailers", parseFlag(it)) }
        options["disablecookies"]?.let { result.put("disableCookies", parseFlag(it)) }

        val remote = JSONObject()
            .put("publicKey", requiredPeer("publickey"))
            .put("endpoint", requiredPeer("endpoint"))
            .put("allowedIps", JSONArray(splitList(peer["allowedips"].orEmpty())))
        peer["presharedkey"]?.takeIf(String::isNotBlank)?.let { remote.put("presharedKey", it) }
        peer["persistentkeepalive"]?.let { remote.put("keepalive", it) }
        result.put("peer", remote)
        return result.toString()
    }

    private fun requiredPeer(key: String): String = peer[key]
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("В профиле не заполнено поле Peer.$key")

    companion object {
        fun parse(text: String): AwgProfile {
            val sections = linkedMapOf<String, MutableMap<String, String>>()
            var section: String? = null
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    if (section !in setOf("interface", "peer")) {
                        throw IllegalArgumentException("Неизвестный раздел профиля в строке ${index + 1}")
                    }
                    if (section == "peer" && sections.containsKey("peer")) {
                        throw IllegalArgumentException("Приложение поддерживает один серверный профиль")
                    }
                    sections.getOrPut(section!!) { linkedMapOf() }
                    return@forEachIndexed
                }
                val current = section ?: throw IllegalArgumentException("В профиле нет раздела [Interface]")
                val splitAt = line.indexOf('=')
                if (splitAt <= 0) throw IllegalArgumentException("Не удалось разобрать строку ${index + 1}")
                val key = line.substring(0, splitAt).trim().lowercase()
                val value = line.substring(splitAt + 1).trim().trimSurroundingQuotes()
                sections.getValue(current)[key] = value
            }

            val iface = sections["interface"] ?: throw IllegalArgumentException("Не найден раздел [Interface]")
            val peer = sections["peer"] ?: throw IllegalArgumentException("Не найден раздел [Peer]")
            val privateKey = iface["privatekey"]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("В профиле не заполнено поле PrivateKey")
            val addresses = splitList(iface["address"].orEmpty())
            if (addresses.isEmpty()) throw IllegalArgumentException("В профиле не заполнено поле Address")
            if (peer["publickey"].isNullOrBlank()) throw IllegalArgumentException("В профиле не заполнено поле Peer.PublicKey")
            if (peer["endpoint"].isNullOrBlank()) throw IllegalArgumentException("В профиле не заполнено поле Peer.Endpoint")
            if (splitList(peer["allowedips"].orEmpty()).isEmpty()) {
                throw IllegalArgumentException("В профиле не заполнено поле Peer.AllowedIPs")
            }

            val mtu = iface["mtu"]?.toIntOrNull()
            if (iface["mtu"] != null && (mtu == null || mtu !in 576..9000)) {
                throw IllegalArgumentException("В профиле указано некорректное значение MTU")
            }
            listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4").forEach { key ->
                val raw = iface[key] ?: return@forEach
                val number = raw.toIntOrNull()
                if (number == null || number < 0) throw IllegalArgumentException("В профиле указано некорректное поле ${key.uppercase()}")
            }
            return AwgProfile(
                privateKey = privateKey,
                addresses = addresses,
                dns = splitList(iface["dns"].orEmpty()),
                mtu = mtu,
                options = iface,
                peer = peer,
            )
        }

        /** WireGuard-конфиги пишут флаги как on/off, не только true/false. */
        fun parseFlag(value: String): Boolean {
            val normalized = value.trim().lowercase()
            return normalized == "true" || normalized == "on" || normalized == "yes" || normalized == "1"
        }

        private fun splitList(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)

        private fun String.trimSurroundingQuotes(): String =
            if (length >= 2 && first() == '"' && last() == '"') substring(1, length - 1) else this
    }
}
