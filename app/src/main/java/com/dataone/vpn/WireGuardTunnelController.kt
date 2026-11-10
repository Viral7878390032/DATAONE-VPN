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
 * One application-wide controller is important: GoBackend tracks its active
 * Tunnel object by identity. Creating a new backend for a recreated Activity
 * can incorrectly make an already-running tunnel appear DOWN.
 */
class WireGuardTunnelController private constructor(context: Context) : Closeable {

    companion object {
        @Volatile
        private var instance: WireGuardTunnelController? = null

        fun getInstance(context: Context): WireGuardTunnelController =
            instance ?: synchronized(this) {
                instance ?: WireGuardTunnelController(context.applicationContext).also {
                    instance = it
                }
            }
    }

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

    /** Read the running tunnel name from the same persistent backend instance. */
    fun refreshState(): Tunnel.State {
        state = if (backend.runningTunnelNames.contains(tunnel.name)) {
            Tunnel.State.UP
        } else {
            Tunnel.State.DOWN
        }
        return state
    }

    fun permissionIntent() = VpnService.prepare(appContext)

    fun connect(configText: String, onResult: (Result<Unit>) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(configText.isNotBlank()) { "WireGuard configuration is empty." }
                check(VpnService.prepare(appContext) == null) {
                    "VPN permission has not been granted."
                }
                val config = Config.parse(BufferedReader(StringReader(configText)))
                backend.setState(tunnel, Tunnel.State.UP, config)
                check(refreshState() == Tunnel.State.UP) {
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
                check(refreshState() == Tunnel.State.DOWN) {
                    "WireGuard is still reporting an active tunnel."
                }
                Unit
            }
            onResult(result)
        }
    }

    /** Do not call from Activity/composition teardown; the VPN must survive it. */
    override fun close() {
        // Intentionally keep the application-wide backend and executor alive.
    }
}
