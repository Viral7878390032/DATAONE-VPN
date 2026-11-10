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
        val lines = csv.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val headerIndex = lines.indexOfFirst {
            it.removePrefix("#").startsWith("HostName,", ignoreCase = true)
        }
        if (headerIndex < 0) return emptyList()

        // Parse columns by their official header names instead of assuming that
        // every API response always has the same ordering.
        val headers = splitCsvLine(lines[headerIndex].removePrefix("#"))
            .map { it.trim().lowercase() }
        fun column(name: String): Int = headers.indexOf(name.lowercase())

        val hostIndex = column("HostName")
        val ipIndex = column("IP")
        val scoreIndex = column("Score")
        val pingIndex = column("Ping")
        val speedIndex = column("Speed")
        val countryIndex = column("CountryLong")
        val countryCodeIndex = column("CountryShort")
        val sessionsIndex = column("NumVpnSessions")
        val uptimeIndex = column("Uptime")
        val operatorIndex = column("Operator")
        val profileIndex = column("OpenVPN_ConfigData_Base64")
        if (listOf(hostIndex, ipIndex, scoreIndex, pingIndex, speedIndex, countryIndex,
                countryCodeIndex, sessionsIndex, uptimeIndex, profileIndex).any { it < 0 }) {
            return emptyList()
        }

        val parsed = lines.drop(headerIndex + 1).mapNotNull { line ->
            if (line.startsWith("*") || line.startsWith("#")) return@mapNotNull null
            val fields = splitCsvLine(line)
            val requiredMax = maxOf(hostIndex, ipIndex, scoreIndex, pingIndex, speedIndex,
                countryIndex, countryCodeIndex, sessionsIndex, uptimeIndex, profileIndex)
            if (fields.size <= requiredMax) return@mapNotNull null

            val profile = fields[profileIndex].trim()
            val ip = fields[ipIndex].trim()
            val ping = fields[pingIndex].trim().toIntOrNull() ?: return@mapNotNull null
            val speed = fields[speedIndex].trim().toLongOrNull() ?: return@mapNotNull null
            if (!ip.matches(Regex("^\\\\d{1,3}(?:\\\\.\\\\d{1,3}){3}$")) ||
                ping !in 1..800 || speed <= 0 || profile.length < 150) {
                return@mapNotNull null
            }

            val decoded = runCatching { Base64.decode(profile, Base64.DEFAULT) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            // Only retain actual OpenVPN profiles; never execute or log directives.
            val profileText = decoded.toString(Charsets.UTF_8)
            if (!profileText.contains("client") || !profileText.contains("remote ")) {
                return@mapNotNull null
            }

            VpnGateServer(
                hostName = fields[hostIndex].trim(),
                ip = ip,
                score = fields[scoreIndex].trim().toLongOrNull() ?: 0L,
                pingMs = ping,
                speedBytesPerSecond = speed,
                country = fields[countryIndex].trim(),
                countryCode = fields[countryCodeIndex].trim(),
                sessions = fields[sessionsIndex].trim().toLongOrNull() ?: 0L,
                uptime = fields[uptimeIndex].trim().toLongOrNull() ?: 0L,
                operator = fields.getOrNull(operatorIndex)?.trim().orEmpty(),
                profileBase64 = profile
            )
        }

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
