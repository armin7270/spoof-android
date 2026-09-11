# SNI Spoofing Android

<div dir="rtl" align="right">

کلاینت VPN اندروید برای دورزدن فیلترینگ مبتنی بر DPI، بازطراحی‌شده بر پایه معماری
[**UAC SNI Spoofer Android 2.0.5**](https://github.com/Floxu1/UAC-SNI-Spoofer-Android)
(هسته Xray + مسیر بومی TUN) با هویت، امضا و بسته‌بندی مستقل این پروژه.

</div>

## Features

- **Global VPN tunnel** — Android `VpnService` + Xray core + native TUN path (hev-socks5-tunnel / libv2ray-native-tun)
- **Config support** — `vless://`, `vmess://`, `trojan://` with full transport / security / **SNI** / Host / Path / ALPN / Fingerprint preservation
- **Adaptive connection** — per-network fingerprint (connection type, operator, ASN, provider) drives route ordering, learns winners, connects faster next time
- **Edge sets** — main + alternative edge servers per network, Direct Compatibility mode, ALPN switching, FinalMask
- **Auto-reconnect** — network changes and quality drops recover using the saved winner, backup route and failure cooldowns
- **Route Speed Test** — full `Edge × DNS × Fragment × MTU` combinatorics; multi-stage racing (screening → stability → stress → A-B-B-A final) with cold Xray starts, multi-destination HTTP probes, DNS checks, volume/speed/ping/jitter/success-rate/confidence metrics
- **Live ranking** — best-route leaderboard, pause/resume, manual stage advance, per-config per-network result storage, winner + backup selection
- **DNS resolvers** — Cloudflare / Google / Quad9 / AdGuard / OpenDNS over DoH with bootstrap IPs
- **Config Maker** — Quick Scan and Deep Adaptive Test modes, stop-on-first-healthy-route
- **Import** — text, clipboard, file or subscription links; multi-subscription merge without losing results; automatic dedupe
- **Per-app routing** — all apps through VPN / bypass selected apps / only selected apps through VPN
- **Connection modes** — Tunnel and local **SOCKS** proxy; Fragment / FinalMask / MTU / Mux / Keepalive / QUIC / routing controls
- **Live monitoring** — ping, traffic, exit country + IP, connection health, technical logs
- **Quick Settings tile** — connect/disconnect from the Android pull-down shade + notification controls

## Requirements

- Android 7.0+ (minSdk 24)
- Standard VPN permission on first connect
- Other VPN apps must be off while this one is active

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

JDK 17 + Android SDK 35 + NDK 26.3.11579264 + CMake 3.22.1 + `python` on PATH
(two AAR-patch tasks run python scripts at build time).

ABI splits produce per-arch APKs plus a universal one; release output names look
like `SNI-Spoofing-2.0.5-arm64-v8a-Android7plus.apk` (64-bit phones — recommended)
and the universal build is also copied to `app-release.apk`.

### Signing

Release signing reads a git-ignored `keystore.properties` in the repo root
(CI may instead set `UAC_SIGNING_PROPERTIES` or the `uacSigningProperties`
Gradle property):

```properties
storeFile=keystore/snispoof-release.keystore
storePassword=...
keyAlias=snispoof
keyPassword=...
```

Without it, `assembleRelease` still succeeds and produces unsigned APKs.

> **Keep `keystore/` and `keystore.properties` backed up and never commit them.**
> Android refuses updates signed with a different key.

## Upstream & licenses

This is a rebuild on top of [Floxu1/UAC-SNI-Spoofer-Android](https://github.com/Floxu1/UAC-SNI-Spoofer-Android) — all credits for the UI and engine design belong to its author. Third-party components (Xray-core MPL-2.0, Psiphon tunnel-core GPL-3.0, UAC PoW / WARP core AGPL-3.0, quiche BSD-2, hev-socks5-tunnel MIT, flag-icons MIT, Vazirmatn OFL) are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Local adaptations in this repo:

- `applicationId` → `com.armin7270.snispoof` (namespace kept as upstream to avoid touching 137 source files)
- signing wired to this repo's `keystore.properties`
- APK naming rebranded (`SNI-Spoofing-*`), CI updated (python shim, artifact paths)
- upstream root helper scripts removed (no root-helper concept in this codebase)
