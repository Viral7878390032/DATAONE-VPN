package com.dataone.vpn

/**
 * Native OpenVPN engine loader.
 *
 * Availability only confirms that the ARM64 native library loads and answers
 * a basic OpenVPN Core API call. It does not mean a VPN tunnel is connected.
 */
internal object OpenVpnNative {
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("dataone_openvpn")
            nativeSmokeTest() == 1
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeSmokeTest(): Int
}
