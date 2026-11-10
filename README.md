# DATAONE VPN

A standalone Android VPN client project, developed separately from the DATAONE unified-viewing app.

## Current stage

**Stage 1 — Android app foundation.** The current screen is an interface preview only. It deliberately does not claim to be connected and does not route device traffic.

## Planned milestones

1. Build and verify the Android app foundation.
2. Integrate the official WireGuard Android tunnel library.
3. Provision and secure a real WireGuard server.
4. Add configuration handling, Android VPN permission flow, connection state, and leak-conscious error handling.
5. Test real connectivity before enabling the Connect control.

A genuine VPN requires a reachable VPN server and valid client configuration. The app will not display a connected state until a real tunnel is established.

## Build

GitHub Actions builds a debug APK on every push to `main`. Download it from the workflow run's **Artifacts** section.

## Technology

- Kotlin
- Jetpack Compose
- Android VPN APIs
- WireGuard (planned integration)

Official WireGuard Android embedding guidance: https://www.wireguard.com/embedding/
