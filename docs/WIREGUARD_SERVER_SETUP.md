# Set up a real DATAONE VPN server

The Android app is a WireGuard client and needs a reachable server. This guide creates a single-peer server on Ubuntu 22.04 or 24.04.

## Cost and safety

- A VPS is not guaranteed to be free. Check current free-tier limits, billing rules, region availability, and payment requirements before creating one.
- Use a server you own or are allowed to use. Do not import random public VPN configs.
- The client file contains a private key. Treat it like a password. Never commit it to GitHub or share it publicly.

## 1. Create an Ubuntu VPS

Choose Ubuntu 22.04 or 24.04 with a public IPv4 address. Allow inbound UDP port 51820 in the provider firewall/security group. Restrict SSH access to your IP if possible.

## 2. Run the setup script

Upload \`server/setup-wireguard.sh\` securely, then run:

\`\`\`bash
sudo bash setup-wireguard.sh
\`\`\`

The script installs WireGuard, enables IPv4 forwarding, creates a server and one client peer, starts \`wg-quick@wg0\`, and writes the client file to \`/root/dataone-client.conf\`. Keys are generated on the VPS and are not sent to this repository.

If UFW is active, run:

\`\`\`bash
sudo ufw allow 51820/udp
\`\`\`

Also allow UDP 51820 in the VPS provider firewall.

## 3. Import the client config

Copy \`/root/dataone-client.conf\` to your phone over a secure channel (for example USB). In DATAONE VPN, choose **Import config**, select the file, approve Android's VPN permission prompt, and connect. Keep the file private.

If the file is exposed, revoke that peer from \`/etc/wireguard/wg0.conf\`, generate a replacement client key/config, and restart WireGuard.

## 4. Verify

On the VPS run:

\`\`\`bash
sudo wg show
\`\`\`

After the phone connects, the peer should show a recent handshake and increasing transfer counters. Check that the phone's public IPv4 changes to the VPS address and test browsing and DNS.

## Important limitation

This first profile routes IPv4 (\`0.0.0.0/0\`) through WireGuard only. IPv6 is not tunnelled and may use the phone's normal network if IPv6 is available. Do not treat this initial profile as leak-proof or production-ready. Full IPv6 tunnelling, firewall hardening, multiple-client management, and device testing remain necessary.

## Troubleshooting

- **No handshake:** check the client endpoint IP, UDP 51820 in both firewalls, and \`sudo systemctl status wg-quick@wg0\`.
- **Handshake but no browsing:** check \`net.ipv4.ip_forward=1\`, the outbound interface in \`/etc/wireguard/wg0.conf\`, and provider network rules.
- Never paste private keys into issue reports or chat. Redact keys, endpoint secrets, and personal IP details from logs.
