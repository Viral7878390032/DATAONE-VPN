package com.dataone.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireguard.android.backend.Tunnel
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.delay

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF111F32)
private val Blue = Color(0xFF55A6FF)
private val Muted = Color(0xFF91A4BC)
private val Green = Color(0xFF55D6A2)

private const val CONFIG_PREFS = "dataone_vpn_secure_config"
private const val CONFIG_ALIAS = "dataone_vpn_config_key"

private fun configKey(): java.security.Key {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(CONFIG_ALIAS, null) as? java.security.Key)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec.Builder(
            CONFIG_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
    )
    return generator.generateKey()
}

private fun saveConfigSecurely(context: android.content.Context, value: String) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, configKey())
    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    context.getSharedPreferences(CONFIG_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        .apply()
}

private fun loadConfigSecurely(context: android.content.Context): String? {
    val prefs = context.getSharedPreferences(CONFIG_PREFS, android.content.Context.MODE_PRIVATE)
    val ciphertext = prefs.getString("ciphertext", null) ?: return null
    val iv = prefs.getString("iv", null) ?: return null
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        configKey(),
        GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
    )
    return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DataOneVpnHome() }
    }
}

@Composable
private fun DataOneVpnHome() {
    val context = LocalContext.current
    val controller = remember { WireGuardTunnelController.getInstance(context) }
    var configReady by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var configMessage by remember { mutableStateOf("No WireGuard configuration imported") }
    var tunnelState by remember { mutableStateOf(Tunnel.State.DOWN) }
    var statusMessage by remember { mutableStateOf("Not connected") }
    var busy by remember { mutableStateOf(false) }
    var connectedAt by remember { mutableStateOf<Long?>(null) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    var serverLocation by remember { mutableStateOf("Available after connecting") }
    var serverNetwork by remember { mutableStateOf("Exit location is estimated from your public IP") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(Unit) {
        runCatching { loadConfigSecurely(context) }.onSuccess { saved ->
            if (!saved.isNullOrBlank()) {
                configText = saved
                configReady = true
                configMessage = "Saved WireGuard configuration loaded securely"
            }
        }.onFailure {
            configMessage = "Saved configuration could not be opened. Please import it again."
        }

        // Recheck Android's real tunnel state while the dashboard is open.
        // The system VPN key icon may remain visible when this Activity is closed.
        while (true) {
            runCatching { controller.refreshState() }.onSuccess { current ->
                if (current != tunnelState) {
                    tunnelState = current
                    if (current == Tunnel.State.UP) {
                        if (connectedAt == null) connectedAt = SystemClock.elapsedRealtime()
                        statusMessage = "DATAONE WireGuard tunnel is active."
                    } else {
                        connectedAt = null
                        statusMessage = "VPN is not connected."
                    }
                }
            }
            delay(1500)
        }
    }

    LaunchedEffect(tunnelState, connectedAt) {
        if (tunnelState == Tunnel.State.UP && connectedAt != null) {
            while (true) {
                elapsedSeconds = ((SystemClock.elapsedRealtime() - connectedAt!!) / 1000L).coerceAtLeast(0L)
                delay(1000)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    fun refreshServerLocation() {
        serverLocation = "Checking VPN exit location…"
        serverNetwork = "Looking up public IP location"
        Thread {
            val result = runCatching {
                val connection = (URL("https://ipapi.co/json/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val city = json.optString("city").takeIf { it.isNotBlank() && it != "null" }
                    val region = json.optString("region").takeIf { it.isNotBlank() && it != "null" }
                    val country = json.optString("country_name").takeIf { it.isNotBlank() && it != "null" }
                    val network = json.optString("org").takeIf { it.isNotBlank() && it != "null" }
                    val location = listOfNotNull(city, region, country).joinToString(", ")
                    require(location.isNotBlank()) { "Location details were not returned." }
                    location to (network ?: "Network provider not reported")
                } finally {
                    connection.disconnect()
                }
            }
            mainHandler.post {
                result.onSuccess { (location, network) ->
                    serverLocation = location
                    serverNetwork = network
                }.onFailure {
                    serverLocation = "Could not retrieve location"
                    serverNetwork = "You can verify it with an external IP-check website."
                }
            }
        }.start()
    }

    fun connectTunnel() {
        if (!configReady || configText.isBlank() || busy) return
        busy = true
        statusMessage = "Connecting…"
        controller.connect(configText) { result ->
            mainHandler.post {
                busy = false
                result.onSuccess {
                    tunnelState = controller.state
                    statusMessage = if (tunnelState == Tunnel.State.UP) "Your traffic is routed through the VPN tunnel." else "Connection not established"
                    if (tunnelState == Tunnel.State.UP) {
                        connectedAt = SystemClock.elapsedRealtime()
                        refreshServerLocation()
                    }
                }.onFailure { error ->
                    tunnelState = Tunnel.State.DOWN
                    connectedAt = null
                    statusMessage = error.message ?: "Connection failed"
                }
            }
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            connectTunnel()
        } else {
            statusMessage = "VPN permission was not granted"
        }
    }

    val configPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to read the selected file.")
            }
            result.onSuccess { text ->
                val valid = text.contains("[Interface]", ignoreCase = true) &&
                    text.contains("[Peer]", ignoreCase = true)
                if (valid) {
                    runCatching { saveConfigSecurely(context, text) }
                        .onSuccess {
                            configText = text
                            configReady = true
                            configMessage = "Configuration imported and saved securely on this device"
                        }
                        .onFailure { error ->
                            configText = ""
                            configReady = false
                            configMessage = error.message ?: "Could not save configuration securely"
                        }
                } else {
                    configText = ""
                    configReady = false
                    configMessage = "Invalid file: expected [Interface] and [Peer] sections"
                    statusMessage = "Import a valid configuration first"
                }
            }.onFailure { error ->
                configText = ""
                configReady = false
                configMessage = error.message ?: "Could not read configuration"
            }
        }
    }

    val connected = tunnelState == Tunnel.State.UP
    val statusColor = if (connected) Green else Color(0xFFFFC477)
    val totalMinutes = elapsedSeconds / 60
    val duration = "%02d:%02d:%02d".format(totalMinutes / 60, totalMinutes % 60, elapsedSeconds % 60)

    Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF10243D), Navy, Navy)))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).background(Blue.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Security, contentDescription = null, tint = Blue) }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("DATAONE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("VPN • PRIVATE CONNECTION", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.Lock, contentDescription = "Privacy", tint = Muted)
            }

            Spacer(Modifier.height(30.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(104.dp).background(statusColor.copy(alpha = 0.10f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier.size(78.dp).background(statusColor.copy(alpha = 0.13f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = statusColor, modifier = Modifier.size(42.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (connected) "You are protected" else "Your privacy, connected.",
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                        Text(
                            if (connected) "  CONNECTED" else if (busy) "  CONNECTING" else "  NOT CONNECTED",
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(statusMessage, color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (connected) {
                                busy = true
                                statusMessage = "Disconnecting…"
                                controller.disconnect { result ->
                                    mainHandler.post {
                                        busy = false
                                        result.onSuccess {
                                            tunnelState = Tunnel.State.DOWN
                                            connectedAt = null
                                            serverLocation = "Available after connecting"
                                            serverNetwork = "Exit location is estimated from your public IP"
                                            statusMessage = "VPN disconnected"
                                        }.onFailure { error ->
                                            statusMessage = error.message ?: "Disconnect failed"
                                        }
                                    }
                                }
                            } else {
                                val permissionIntent: Intent? = controller.permissionIntent()
                                if (permissionIntent == null) connectTunnel() else vpnPermission.launch(permissionIntent)
                            }
                        },
                        enabled = configReady && !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connected) Color(0xFFCC5C67) else Blue,
                            contentColor = Navy,
                            disabledContainerColor = Blue.copy(alpha = 0.25f),
                            disabledContentColor = Muted
                        )
                    ) {
                        Text(
                            when {
                                busy -> "Please wait…"
                                connected -> "Disconnect VPN"
                                else -> "Connect VPN"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoCard(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.Timer, contentDescription = null, tint = Blue) },
                    label = "CONNECTION TIME",
                    value = if (connected) duration else "—"
                )
                InfoCard(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.Security, contentDescription = null, tint = Blue) },
                    label = "TUNNEL",
                    value = if (connected) "WireGuard • Active" else "Standby"
                )
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp).background(Blue.copy(alpha = 0.12f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Blue)
                    }
                    Column(Modifier.padding(start = 13.dp).weight(1f)) {
                        Text("VPN exit location", color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(serverLocation, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(serverNetwork, color = Muted, fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.9f))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("WireGuard configuration", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(configMessage, color = if (configReady) Green else Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { configPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !connected && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)
                    ) {
                        Text(if (configReady) "Choose another .conf file" else "Import .conf file", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your configuration stays private on this device. Never share the file or its secret keys.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Blue, modifier = Modifier.size(16.dp))
                Text("  Powered by WireGuard technology", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("LIVE VPN DASHBOARD", color = Color(0xFF60758F), fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.92f))
    ) {
        Column(Modifier.padding(15.dp)) {
            icon()
            Spacer(Modifier.height(12.dp))
            Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
