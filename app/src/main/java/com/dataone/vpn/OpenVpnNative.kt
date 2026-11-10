package com.dataone.vpn

/**
 * OpenVPN Core runtime access.
 *
 * Native profile validation only checks profile syntax/capabilities. It does
 * not establish a VPN tunnel or guarantee that a remote server is reachable.
 */
internal object OpenVpnNative {
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("dataone_openvpn")
            nativeSmokeTest() == 1
        }.getOrDefault(false)
    }

    fun validateProfile(profile: String): Boolean {
        if (!available || profile.isBlank()) return false
        return runCatching { nativeValidateProfile(profile) == 1 }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeSmokeTest(): Int

    @JvmStatic
    private external fun nativeValidateProfile(profile: String): Int
}
