# DATAONE VPN

A standalone Android VPN client project.

## Current implementation

The Android app is a Kotlin / Jetpack Compose client with:

- An actual WireGuard tunnel backend using the official embeddable WireGuard Android tunnel library.
- Android's VPN permission flow.
- Import of a WireGuard `.conf` file.
- AES-GCM encryption of the imported configuration using an Android Keystore key.
- Connect / disconnect controls driven by the backend tunnel state.
- A connection timer and public exit-IP location lookup.

The app does **not** include a VPN server. A real tunnel requires a reachable WireGuard server and a valid client configuration. Until one is imported and successfully connected, the app remains disconnected; it does not create an empty TUN interface or pretend to be connected.

## Free-use approach

The app itself has no subscription or payment code. Server access is separate: use a WireGuard server you operate or have permission to use. A free cloud tier may be available from some providers, but eligibility, capacity, region, and billing terms vary. Do not assume a cloud server is permanently free.

Do not import configuration files from unknown sources. A WireGuard configuration contains a private key; anyone who obtains it may be able to use that VPN identity.

## Build a debug APK

A GitHub Actions workflow builds the debug APK on pushes and pull requests to `main`, and can also be started manually:

1. Open the repository's **Actions** tab.
2. Select **Android debug APK**.
3. Open the latest successful run.
4. Download the `DATAONE-VPN-debug` artifact.
5. Extract the ZIP to get `app-debug.apk`.

The APK is a debug build, not a Play Store release. It has not been certified through device testing merely because CI compiles it.

## Technology

- Kotlin
- Jetpack Compose
- Android VPN APIs
- Official WireGuard Android tunnel backend
- Android Keystore (AES-GCM configuration encryption)


## Server setup

A starter Ubuntu WireGuard server script and setup guide are available:

- [Server setup guide](docs/WIREGUARD_SERVER_SETUP.md)
- [Ubuntu setup script](server/setup-wireguard.sh)

The starter profile tunnels IPv4 only and is not leak-proof or production-ready. Review the limitations in the guide before using it for sensitive traffic. Never commit the generated client configuration or private keys.
