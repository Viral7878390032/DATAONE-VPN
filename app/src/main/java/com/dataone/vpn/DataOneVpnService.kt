package com.dataone.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-owned TUN interface for the embedded OpenVPN Core engine.
 * The native engine calls the tun* methods while processing its profile.
 */
class DataOneVpnService : VpnService() {
    private var builder: Builder? = null
    private var tun: ParcelFileDescriptor? = null
    private val stopping = AtomicBoolean(false)

    external fun nativeStart(profile: String): Int
    external fun nativeStop()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                runCatching { nativeStop() }
                closeTun()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val profile = intent.getStringExtra(EXTRA_PROFILE)
                if (profile.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                stopping.set(false)
                startForeground(NOTIFICATION_ID, notification("Starting VPN…"))
                Thread({
                    val result = runCatching { nativeStart(profile) }.getOrDefault(-1)
                    if (result != 0) {
                        updateNotification("VPN failed to start")
                        closeTun()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }, "DATAONE-OpenVPN-start").start()
            }
        }
        return START_NOT_STICKY
    }

    // Called from OpenVPN Core through JNI.
    fun tunNew(): Boolean {
        closeTun()
        builder = Builder().setSession("DATAONE VPN").setMtu(1500)
        return builder != null
    }

    fun tunSetRemote(address: String): Boolean = address.isNotBlank()

    fun tunAddAddress(address: String, prefix: Int): Boolean = runCatching {
        builder?.addAddress(address, prefix) ?: return false
        true
    }.getOrDefault(false)

    fun tunAddRoute(address: String, prefix: Int): Boolean = runCatching {
        builder?.addRoute(address, prefix) ?: return false
        true
    }.getOrDefault(false)

    fun tunExcludeRoute(address: String, prefix: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching {
            val ip = InetAddress.getByName(address)
            builder?.excludeRoute(android.net.IpPrefix(ip, prefix)) ?: return false
            true
        }.getOrDefault(false)
    }

    fun tunReroute(ipv4: Boolean, ipv6: Boolean): Boolean = runCatching {
        val b = builder ?: return false
        if (ipv4) b.addRoute("0.0.0.0", 0)
        if (ipv6) b.addRoute("::", 0)
        true
    }.getOrDefault(false)

    fun tunAddDns(address: String): Boolean = runCatching {
        builder?.addDnsServer(address) ?: return false
        true
    }.getOrDefault(false)

    fun tunAddSearchDomain(domain: String): Boolean = runCatching {
        builder?.addSearchDomain(domain) ?: return false
        true
    }.getOrDefault(false)

    fun tunSetMtu(mtu: Int): Boolean = runCatching {
        if (mtu !in 576..9000) return false
        builder?.setMtu(mtu) ?: return false
        true
    }.getOrDefault(false)

    fun tunSetSessionName(name: String): Boolean = runCatching {
        builder?.setSession(name.take(64)) ?: return false
        true
    }.getOrDefault(false)

    fun tunEstablish(): Int {
        closeTun()
        val established = runCatching { builder?.establish() }.getOrNull() ?: return -1
        tun = established
        builder = null
        return established.fd
    }

    fun tunTeardown(disconnect: Boolean) {
        closeTun()
        builder = null
        if (disconnect || stopping.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun protectSocket(fd: Int): Boolean = protect(fd)

    fun nativeOnEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        if (name == "CONNECTED") updateNotification("Connected")
        if (fatal || name == "SESSION_END") {
            updateNotification(if (error) "VPN connection failed" else "Disconnected")
            closeTun()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopping.set(true)
        runCatching { nativeStop() }
        closeTun()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onRevoke()
        stopSelf()
    }

    override fun onDestroy() {
        stopping.set(true)
        runCatching { nativeStop() }
        closeTun()
        super.onDestroy()
    }

    @Synchronized
    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DATAONE VPN", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return n.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("DATAONE VPN")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    companion object {
        const val ACTION_START = "com.dataone.vpn.START_OPENVPN"
        const val ACTION_STOP = "com.dataone.vpn.STOP_OPENVPN"
        const val EXTRA_PROFILE = "openvpn_profile"
        private const val CHANNEL_ID = "dataone_vpn_connection"
        private const val NOTIFICATION_ID = 9201
    }
}
