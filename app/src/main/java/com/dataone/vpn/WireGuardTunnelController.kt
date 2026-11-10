package com.dataone.vpn

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.Closeable
import java.io.StringReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the embedded WireGuard backend.
 *
 * This class deliberately requires a real, user-supplied WireGuard configuration.
 * It never reports a connection as active before the backend confirms the tunnel is UP.
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

    /** Returns the Android system VPN consent intent, or null if consent was already granted. */
    fun permissionIntent() = VpnService.prepare(appContext)

    /**
     * Connects using a complete WireGuard .conf string.
     * Call only after permissionIntent() returned null or the user approved the system dialog.
     */
    fun connect(configText: String, onResult: (Result<Unit>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(configText.isNotBlank()) { "WireGuard configuration is empty." }
                check(VpnService.prepare(appContext) == null) {
                    "VPN permission has not been granted."
                }

                val config = Config.parse(StringReader(configText))
                backend.setState(tunnel, Tunnel.State.UP, config)
                check(backend.getState(tunnel) == Tunnel.State.UP) {
                    "WireGuard did not bring the tunnel up."
                }
            }
            onResult(result)
        }
    }

    fun disconnect(onResult: (Result<Unit>) -> Unit = {}) {
        executor.execute {
            val result = runCatching {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
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
