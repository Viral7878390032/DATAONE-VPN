use std::sync::{Arc, Mutex, OnceLock};

use jni::{
    objects::{GlobalRef, JObject, JString, JValue},
    sys::{jint, JNI_FALSE, JNI_TRUE},
    JNIEnv, JavaVM,
};
use openvpn_connect::{Client, Config, DnsOptions, Event, EventHandler, TunBuilder};

static CLIENT: OnceLock<Mutex<Option<Client>>> = OnceLock::new();

fn client_slot() -> &'static Mutex<Option<Client>> {
    CLIENT.get_or_init(|| Mutex::new(None))
}

#[derive(Clone)]
struct JavaBridge {
    vm: Arc<JavaVM>,
    service: GlobalRef,
}

impl JavaBridge {
    fn with_env<T>(&self, f: impl FnOnce(&mut JNIEnv) -> T) -> Option<T> {
        let mut env = self.vm.attach_current_thread().ok()?;
        Some(f(&mut env))
    }

    fn bool0(&self, name: &str) -> bool {
        self.with_env(|env| env.call_method(self.service.as_obj(), name, "()Z", &[])
            .ok().and_then(|v| v.z().ok()).unwrap_or(false)).unwrap_or(false)
    }

    fn bool_i(&self, name: &str, value: i32) -> bool {
        self.with_env(|env| env.call_method(self.service.as_obj(), name, "(I)Z", &[JValue::Int(value)])
            .ok().and_then(|v| v.z().ok()).unwrap_or(false)).unwrap_or(false)
    }

    fn bool_s(&self, name: &str, value: &str) -> bool {
        self.with_env(|env| {
            let Ok(s) = env.new_string(value) else { return false; };
            env.call_method(self.service.as_obj(), name, "(Ljava/lang/String;)Z", &[JValue::Object(&s)])
                .ok().and_then(|v| v.z().ok()).unwrap_or(false)
        }).unwrap_or(false)
    }

    fn bool_si(&self, name: &str, value: &str, number: i32) -> bool {
        self.with_env(|env| {
            let Ok(s) = env.new_string(value) else { return false; };
            env.call_method(self.service.as_obj(), name, "(Ljava/lang/String;I)Z",
                &[JValue::Object(&s), JValue::Int(number)])
                .ok().and_then(|v| v.z().ok()).unwrap_or(false)
        }).unwrap_or(false)
    }

    fn reroute(&self, ipv4: bool, ipv6: bool) -> bool {
        self.with_env(|env| env.call_method(self.service.as_obj(), "tunReroute", "(ZZ)Z", &[
            JValue::Bool(if ipv4 { JNI_TRUE } else { JNI_FALSE }),
            JValue::Bool(if ipv6 { JNI_TRUE } else { JNI_FALSE }),
        ]).ok().and_then(|v| v.z().ok()).unwrap_or(false)).unwrap_or(false)
    }

    fn establish(&self) -> i32 {
        self.with_env(|env| env.call_method(self.service.as_obj(), "tunEstablish", "()I", &[])
            .ok().and_then(|v| v.i().ok()).unwrap_or(-1)).unwrap_or(-1)
    }

    fn event(&self, name: &str, info: &str, error: bool, fatal: bool) {
        let _ = self.with_env(|env| {
            let (Ok(n), Ok(i)) = (env.new_string(name), env.new_string(info)) else { return; };
            let _ = env.call_method(self.service.as_obj(), "nativeOnEvent",
                "(Ljava/lang/String;Ljava/lang/String;ZZ)V", &[
                    JValue::Object(&n), JValue::Object(&i),
                    JValue::Bool(if error { JNI_TRUE } else { JNI_FALSE }),
                    JValue::Bool(if fatal { JNI_TRUE } else { JNI_FALSE }),
                ]);
        });
    }
}

struct AndroidTunnel { bridge: JavaBridge }

impl TunBuilder for AndroidTunnel {
    fn new_tunnel(&self) -> bool { self.bridge.bool0("tunNew") }
    fn set_layer(&self, layer: i32) -> bool { layer == 3 }
    fn set_remote_address(&self, address: &str, _ipv6: bool) -> bool {
        self.bridge.bool_s("tunSetRemote", address)
    }
    fn add_address(&self, address: &str, prefix: i32, _gateway: &str, _ipv6: bool, _net30: bool) -> bool {
        self.bridge.bool_si("tunAddAddress", address, prefix)
    }
    fn reroute_gateway(&self, ipv4: bool, ipv6: bool, _flags: u32) -> bool {
        self.bridge.reroute(ipv4, ipv6)
    }
    fn add_route(&self, address: &str, prefix: i32, _metric: i32, _ipv6: bool) -> bool {
        self.bridge.bool_si("tunAddRoute", address, prefix)
    }
    fn exclude_route(&self, address: &str, prefix: i32, _metric: i32, _ipv6: bool) -> bool {
        self.bridge.bool_si("tunExcludeRoute", address, prefix)
    }
    fn set_dns_options(&self, dns: &DnsOptions) -> bool {
        for server in &dns.servers {
            for address in &server.addresses {
                if !self.bridge.bool_s("tunAddDns", &address.address) { return false; }
            }
        }
        for domain in &dns.search_domains {
            if !self.bridge.bool_s("tunAddSearchDomain", domain) { return false; }
        }
        true
    }
    fn set_mtu(&self, mtu: i32) -> bool { self.bridge.bool_i("tunSetMtu", mtu) }
    fn set_session_name(&self, name: &str) -> bool { self.bridge.bool_s("tunSetSessionName", name) }
    fn set_allow_family(&self, _family: i32, _allow: bool) -> bool { true }
    fn set_allow_local_dns(&self, _allow: bool) -> bool { true }
    fn establish(&self) -> i32 { self.bridge.establish() }
    fn teardown(&self, disconnect: bool) {
        let _ = self.bridge.with_env(|env| {
            let _ = env.call_method(self.bridge.service.as_obj(), "tunTeardown", "(Z)V",
                &[JValue::Bool(if disconnect { JNI_TRUE } else { JNI_FALSE })]);
        });
    }
}

struct Handler { bridge: JavaBridge, tunnel: AndroidTunnel }

impl EventHandler for Handler {
    fn event(&self, event: Event) {
        self.bridge.event(&event.name, &event.info, event.error, event.fatal);
    }
    fn log(&self, message: &str) {
        if message.contains("ERROR") || message.contains("FATAL") {
            self.bridge.event("LOG", message, true, false);
        }
    }
    fn socket_protect(&self, socket: isize, _remote: &str, _ipv6: bool) -> bool {
        socket >= 0 && socket <= i32::MAX as isize
            && self.bridge.bool_i("protectSocket", socket as i32)
    }
    fn tun_builder(&self) -> Option<&dyn TunBuilder> { Some(&self.tunnel) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dataone_vpn_DataOneVpnService_nativeStart(
    mut env: JNIEnv, service: JObject, profile: JString,
) -> jint {
    let profile: String = match env.get_string(&profile) {
        Ok(value) => value.into(),
        Err(_) => return -1,
    };
    if profile.trim().is_empty() || profile.len() > openvpn_connect::max_profile_size() {
        return -2;
    }
    let vm = match env.get_java_vm() { Ok(value) => value, Err(_) => return -3 };
    let global = match env.new_global_ref(service) { Ok(value) => value, Err(_) => return -4 };
    let bridge = JavaBridge { vm: Arc::new(vm), service: global };
    let handler = Handler {
        tunnel: AndroidTunnel { bridge: bridge.clone() },
        bridge: bridge.clone(),
    };
    let client = match Client::new(handler) {
        Ok(value) => value,
        Err(error) => { bridge.event("ERROR", &format!("OpenVPN client: {error}"), true, true); return -5; }
    };
    let evaluation = match client.evaluate(&Config::new(profile).with_gui_version("DATAONE VPN")) {
        Ok(value) => value,
        Err(error) => { bridge.event("ERROR", &format!("Profile rejected: {error}"), true, true); return -6; }
    };
    if !evaluation.autologin {
        bridge.event("ERROR", "This VPN Gate profile requires credentials", true, true);
        return -7;
    }

    let control = client.clone();
    {
        let mut slot = client_slot().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(previous) = slot.take() { previous.stop(); }
        *slot = Some(control);
    }
    std::thread::spawn(move || {
        match client.connect() {
            Ok(status) => bridge.event("SESSION_END", &format!("{}: {}", status.status, status.message), false, false),
            Err(error) => bridge.event("ERROR", &format!("OpenVPN connection: {error}"), true, true),
        }
        let mut slot = client_slot().lock().unwrap_or_else(|e| e.into_inner());
        *slot = None;
    });
    0
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dataone_vpn_DataOneVpnService_nativeStop(
    _env: JNIEnv, _service: JObject,
) {
    let slot = client_slot().lock().unwrap_or_else(|e| e.into_inner());
    if let Some(client) = slot.as_ref() { client.stop(); }
}
