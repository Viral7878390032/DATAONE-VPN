package com.dataone.vpn

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the public VPN Gate directory. Entries are volunteer-operated OpenVPN
 * relays, not DATAONE-owned servers. The returned profiles must be handled as
 * untrusted remote configuration and must never be logged.
 */
data class VpnGateServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int,
    val speedBytesPerSecond: Long,
    val country: String,
    val countryCode: String,
    val sessions: Long,
    val uptime: Long,
    val operator: String,
    internal val profileBase64: String
) {
    fun decodedOpenVpnProfile(): String =
        String(Base64.decode(profileBase64, Base64.DEFAULT), Charsets.UTF_8)
}

object VpnGateDirectory {
    private const val DIRECTORY_URL = "https://www.vpngate.net/api/iphone/"

    /**
     * Fetch and rank usable VPN Gate rows. Call from Dispatchers.IO; this method
     * performs blocking network I/O and deliberately has bounded timeouts.
     */
    fun fetchServers(): List<VpnGateServer> {
        val connection = (URL(DIRECTORY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "DATAONE-VPN/1.0")
        }
        try {
            check(connection.responseCode in 200..299) {
                "VPN Gate directory returned HTTP ${connection.responseCode}"
            }
            val csv = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(csv)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(csv: String): List<VpnGateServer> {
        val rows = csv.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("#") }
            .toList()
        if (rows.size < 2) return emptyList()

        val parsed = rows.drop(1).mapNotNull { line ->
            val fields = splitCsvLine(line)
            if (fields.size < 15) return@mapNotNull null

            val profile = fields.last().trim()
            val ip = fields.getOrNull(1)?.trim().orEmpty()
            val ping = fields.getOrNull(3)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val speed = fields.getOrNull(4)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            if (ip.isBlank() || ping !in 1..800 || speed <= 0 || profile.length < 150) {
                return@mapNotNull null
            }

            runCatching { Base64.decode(profile, Base64.DEFAULT) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            VpnGateServer(
                hostName = fields[0].trim(),
                ip = ip,
                score = fields[2].trim().toLongOrNull() ?: 0L,
                pingMs = ping,
                speedBytesPerSecond = speed,
                country = fields[5].trim(),
                countryCode = fields[6].trim(),
                sessions = fields[7].trim().toLongOrNull() ?: 0L,
                uptime = fields[8].trim().toLongOrNull() ?: 0L,
                operator = fields.getOrNull(12)?.trim().orEmpty(),
                profileBase64 = profile
            )
        }

        // VPN Gate can publish multiple rows for the same relay IP. Keep the
        // highest-scored row so the server picker has stable unique keys.
        return parsed.groupBy { it.ip }
            .mapNotNull { (_, duplicates) -> duplicates.maxByOrNull { it.score } }
            .sortedWith(compareBy<VpnGateServer> { it.pingMs }.thenByDescending { it.speedBytesPerSecond })
    }

    /** CSV splitter that preserves commas inside quoted fields. */
    private fun splitCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    values += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        values += field.toString()
        return values
    }
}
