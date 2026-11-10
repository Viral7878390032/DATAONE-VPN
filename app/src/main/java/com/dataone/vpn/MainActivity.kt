package com.dataone.vpn

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF111F32)
private val Blue = Color(0xFF55A6FF)
private val Muted = Color(0xFF91A4BC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DataOneVpnHome() }
    }
}

@Composable
private fun DataOneVpnHome() {
    val context = LocalContext.current
    val controller = remember(context) { WireGuardTunnelController(context) }
    var configReady by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var configMessage by remember { mutableStateOf("No WireGuard configuration imported") }
    var tunnelState by remember { mutableStateOf(Tunnel.State.DOWN) }
    var statusMessage by remember { mutableStateOf("Not connected") }
    var busy by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(controller) {
        onDispose { controller.close() }
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
                    statusMessage = if (tunnelState == Tunnel.State.UP) "Connected" else "Connection not established"
                }.onFailure { error ->
                    tunnelState = Tunnel.State.DOWN
                    statusMessage = error.message ?: "Connection failed"
                }
            }
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) {
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
                configText = if (valid) text else ""
                configReady = valid
                configMessage = if (valid) {
                    "WireGuard configuration imported"
                } else {
                    "Invalid file: expected [Interface] and [Peer] sections"
                }
                if (!valid) statusMessage = "Import a valid configuration first"
            }.onFailure { error ->
                configText = ""
                configReady = false
                configMessage = error.message ?: "Could not read configuration"
            }
        }
    }

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

            Spacer(Modifier.height(42.dp))
            Box(
                modifier = Modifier.size(160.dp).background(Blue.copy(alpha = 0.07f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(120.dp).background(Blue.copy(alpha = 0.10f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Blue, modifier = Modifier.size(58.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Your privacy, connected.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("A secure, device-wide WireGuard connection.", color = Muted, fontSize = 13.sp)

            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(
                            if (tunnelState == Tunnel.State.UP) Color(0xFF55D6A2) else Color(0xFFFFB454),
                            CircleShape
                        ))
                        Text(
                            if (tunnelState == Tunnel.State.UP) "  CONNECTED" else "  NOT CONNECTED",
                            color = if (tunnelState == Tunnel.State.UP) Color(0xFF7DE0B0) else Color(0xFFFFC477),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(statusMessage, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Connection status is reported by the WireGuard tunnel backend.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (tunnelState == Tunnel.State.UP) {
                                busy = true
                                statusMessage = "Disconnecting…"
                                controller.disconnect { result ->
                                    mainHandler.post {
                                        busy = false
                                        result.onSuccess {
                                            tunnelState = Tunnel.State.DOWN
                                            statusMessage = "Disconnected"
                                        }.onFailure { error ->
                                            statusMessage = error.message ?: "Disconnect failed"
                                        }
                                    }
                                }
                            } else {
                                val permissionIntent: Intent? = controller.permissionIntent()
                                if (permissionIntent == null) connectTunnel()
                                else vpnPermission.launch(permissionIntent)
                            }
                        },
                        enabled = configReady && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tunnelState == Tunnel.State.UP) Color(0xFFCC5C67) else Blue,
                            contentColor = Navy,
                            disabledContainerColor = Blue.copy(alpha = 0.25f),
                            disabledContentColor = Muted
                        )
                    ) {
                        Text(
                            when {
                                busy -> "Please wait…"
                                tunnelState == Tunnel.State.UP -> "Disconnect VPN"
                                else -> "Connect VPN"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = Blue)
                    Column {
                        Text("Server location", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Defined by your imported WireGuard profile", color = Muted, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.9f))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("WireGuard configuration", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(configMessage, color = if (configReady) Color(0xFF7DE0B0) else Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { configPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = tunnelState != Tunnel.State.UP && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)
                    ) {
                        Text(if (configReady) "Choose another .conf file" else "Import .conf file", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Use a valid configuration from your WireGuard server provider. Keep this file private; it contains secret keys.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Blue, modifier = Modifier.size(16.dp))
                Text("  Powered by WireGuard technology", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("STAGE 2 • TUNNEL CONTROLS", color = Color(0xFF60758F), fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
    }
}
