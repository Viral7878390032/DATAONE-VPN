use std::ffi::c_void;

// Minimal JNI smoke-test entry point. This verifies that the Android process
// can load the linked OpenVPN Core and call into its Rust API. It does not
// establish a VPN session; Android VpnService/TunBuilder integration follows.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dataone_vpn_OpenVpnNative_nativeSmokeTest(
    _env: *mut c_void,
    _class: *mut c_void,
) -> i32 {
    let platform = openvpn_connect::platform();
    let profile_limit = openvpn_connect::max_profile_size();
    if !platform.is_empty() && profile_limit > 0 { 1 } else { 0 }
}
