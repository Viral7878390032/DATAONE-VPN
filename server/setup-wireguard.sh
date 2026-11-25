#!/usr/bin/env bash
set -euo pipefail
if [[ "$(id -u)" -ne 0 ]]; then echo "Run as root: sudo bash setup-wireguard.sh" >&2; exit 1; fi
if [[ ! -r /etc/os-release ]]; then echo "Cannot identify Linux distribution." >&2; exit 1; fi
. /etc/os-release
if [[ "\${ID:-}" != "ubuntu" ]]; then echo "This setup script supports Ubuntu only." >&2; exit 1; fi
SERVER_IF="$(ip -4 route show default | awk 'NR==1 {print $5}')"
if [[ -z "$SERVER_IF" ]]; then echo "Could not detect outbound network interface." >&2; exit 1; fi
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y wireguard iptables curl
install -d -m 700 /etc/wireguard
umask 077
wg genkey | tee /etc/wireguard/server_private.key | wg pubkey > /etc/wireguard/server_public.key
wg genkey | tee /root/dataone_client_private.key | wg pubkey > /root/dataone_client_public.key
SERVER_PRIVATE="$(cat /etc/wireguard/server_private.key)"
SERVER_PUBLIC="$(cat /etc/wireguard/server_public.key)"
CLIENT_PRIVATE="$(cat /root/dataone_client_private.key)"
CLIENT_PUBLIC="$(cat /root/dataone_client_public.key)"
PUBLIC_IP="$(curl -4fsS --max-time 10 https://api.ipify.org)"
if [[ -z "$PUBLIC_IP" ]]; then echo "Could not detect public IPv4." >&2; exit 1; fi
cat > /etc/sysctl.d/99-dataone-wireguard.conf <<'SYSCTL'
net.ipv4.ip_forward=1
SYSCTL
sysctl --system >/dev/null
cat > /etc/wireguard/wg0.conf <<WG
[Interface]
Address = 10.66.66.1/24
ListenPort = 51820
PrivateKey = \${SERVER_PRIVATE}
PostUp = iptables -t nat -A POSTROUTING -o \${SERVER_IF} -j MASQUERADE; iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -o \${SERVER_IF} -j MASQUERADE; iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT
[Peer]
PublicKey = \${CLIENT_PUBLIC}
AllowedIPs = 10.66.66.2/32
WG
chmod 600 /etc/wireguard/wg0.conf
cat > /root/dataone-client.conf <<CLIENT
[Interface]
PrivateKey = \${CLIENT_PRIVATE}
Address = 10.66.66.2/32
DNS = 1.1.1.1
[Peer]
PublicKey = \${SERVER_PUBLIC}
Endpoint = \${PUBLIC_IP}:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
CLIENT
chmod 600 /root/dataone-client.conf
systemctl enable --now wg-quick@wg0
echo "WireGuard started. Allow inbound UDP 51820 in the VPS firewall."
echo "If UFW is active: ufw allow 51820/udp"
echo "Private client config: /root/dataone-client.conf"
echo "This initial profile tunnels IPv4 only; IPv6 is not tunnelled."
echo "Never upload the client config or private keys to GitHub."
