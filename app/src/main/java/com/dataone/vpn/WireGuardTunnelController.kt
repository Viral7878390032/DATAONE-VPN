package com.dataone.vpn

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.Closeable
import java.io.StringReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the embedded WireGuard backend.
 * A real user-supplied WireGuard configuration is required to establish a tunnel.
 */
class WireGuardTunnelController(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var state: Tunnel.State = Tunnel.State.DOWN
        private set

    private val tunnel = object : Tunnel {
        override fun getName(): String = "DATAONE"
        override fun onStateChange(newState: Tunnel.State) {
            state = newState
        }
    }

    /** Returns the Android VPN consent intent, or null if consent is already granted. */
    fun permissionIntent() = VpnService.prepare(appContext)

    /** Call after Android VPN consent has been granted. */
    fun connect(configText: String, onResult: (Result<Unit>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(configText.isNotBlank()) { "WireGuard configuration is empty." }
                check(VpnService.prepare(appContext) == null) {
                    "VPN permission has not been granted."
                }
                val reader = BufferedReader(StringReader(configText))
                val config = Config.parse(reader)
                backend.setState(tunnel, Tunnel.State.UP, config)
                check(backend.getState(tunnel) == Tunnel.State.UP) {
                    "WireGuard did not bring the tunnel up."
                }
                Unit
            }
            onResult(result)
        }
    }

    fun disconnect(onResult: (Result<Unit>) -> Unit = {}) {
        executor.execute {
            val result = runCatching {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                Unit
            }
            onResult(result)
        }
    }

    override fun close() {
        executor.execute {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            executor.shutdown()
        }
    }
}
