use jni::objects::{JClass, JString};
use jni::sys::jboolean;
use jni::JNIEnv;

/// Validates an inline OpenVPN profile with the actual linked OpenVPN Core.
/// Returns JNI_TRUE only when Core accepts the profile and its capabilities.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dataone_vpn_OpenVpnNative_nativeValidateProfile(
    mut env: JNIEnv,
    _class: JClass,
    profile: JString,
) -> jboolean {
    let content = match env.get_string(&profile) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };

    if content.trim().is_empty() || content.len() > openvpn_connect::max_profile_size() {
        return 0;
    }

    let config = openvpn_connect::Config::new(content);
    match openvpn_connect::evaluate_config(&config) {
        Ok(_) => 1,
        Err(_) => 0,
    }
}

/// Confirms that the Android process can load OpenVPN Core and call its API.
/// This is a runtime smoke test, not evidence of an active VPN tunnel.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dataone_vpn_OpenVpnNative_nativeSmokeTest(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jint {
    let platform = openvpn_connect::platform();
    let profile_limit = openvpn_connect::max_profile_size();
    if !platform.is_empty() && profile_limit > 0 { 1 } else { 0 }
}
