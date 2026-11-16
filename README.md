# Guise

**Blend in, get through.** Guise is an Android proxy client built to get out of heavily censored networks reliably: it works with your own proxy servers, and when you don't have one, it can still get past SNI-based blocking with ByeDPI.

*Guise is a fork of [Exclave](https://github.com/ExclaveNetwork/Exclave) (SagerNet lineage) that adds built-in ByeDPI desync, a DPI strategy tester, and Wi-Fi + mobile data connection splitting.*

> ⚠️ Only debug preview builds are published for now. The signing key and package name (`io.github.guise`) differ from Exclave, so both apps can be installed side by side. This is an independent fork: please report issues here, not upstream.

## What Guise adds

### ByeDPI direct mode
- Bundles [ByeDPI](https://github.com/hufrea/byedpi), running as a separate process. Without any server, it splits, reorders or fakes parts of the TLS handshake to get past blocking that only looks at the SNI.
- Two ways to use it:
  - Add a "ByeDPI only" profile and send all traffic straight out through ByeDPI.
  - Keep using your proxy servers as usual, and hand **selected domains** or **all direct traffic** to ByeDPI.
- Domains that go through ByeDPI are resolved over encrypted DNS (by default `https://1.1.1.1/dns-query` and `https://8.8.8.8/dns-query`) instead of the poisoned system DNS. The DNS queries themselves can optionally be sent through ByeDPI too.
- Default arguments: `-d1 -r1+s`. OOB is not used, because some middleboxes insert the OOB byte into the stream and break the TLS handshake.

### Connectivity test (strategy tester)
- Tries dozens of ByeDPI argument sets against the sites you select, using a plain direct connection as the baseline, and ranks them by success rate and latency. A working set can be applied with one tap.
- Built-in targets include X/Twitter, Twitch, Pixiv, Wikipedia, Reddit, Steam Community and the Internet Archive, with GitHub as a control.
- Names are resolved over DoH during the test, so DNS poisoning doesn't skew the results.

### Dual-network split (Wi-Fi + mobile data)
- When enabled, mobile data stays up while you are on Wi-Fi, and new proxy connections are spread across both networks.
- Splitting only happens with a remote proxy profile (SS, VMess, VLESS, Trojan, Hysteria2, …), so your exit IP is always your server. With a ByeDPI profile, traffic stays on a single network of your choice.
- Two balancing modes:
  - **1:1**: new connections alternate between the networks.
  - **Auto**: weights follow the latency, jitter and retransmissions measured on the real proxy connections. No probe packets are sent. Every N KB sent on a network (default 256 KB), Guise reads the kernel's TCP statistics (`TCP_INFO`) of the busiest live connection on that network; no traffic, no sampling. A network whose connections fail or stall is taken out of rotation, all new connections go to the other one, and it is retried after 30 seconds.
- Splitting works per connection, so a single connection doesn't get the combined bandwidth. Packet-level aggregation (Speedify-style) is planned.

### Inherited from Exclave
Anti-detection features such as uTLS fingerprints, REALITY, ECH, custom verification SNI, TLS fragmentation, XHTTP, Hysteria2 port hopping and obfuscation, and mux padding, plus groups, subscriptions, routing and proxy chains.

Supported protocols:

<details>

Features:

- Various proxy protocols
- Group and subscription
- Routing
- Proxy chain

Some supported protocols:

- Shadowsocks (with SIP003 plugin support)
- Shadowsocks 2022 (with SIP003 plugin support)
- Trojan
- Hysteria 2
- AnyTLS
- mieru
- NaïveProxy (as a standalone plugin)
- TUIC
- Juicity
- VMess (with various optional sub-protocols)
- VLESS (with various optional sub-protocols)
- WireGuard (TCP and UDP only)
- MASQUE (a.k.a. CONNECT-IP, TCP and UDP only)
- TrustTunnel (no ICMP echo support)
- Snell v4 and Snell v6
- ShadowQUIC
- SSH proxy ("dynamic port forwarding")
- HTTP CONNECT tunnel (HTTP/1.1, HTTP/1.1 with TLS, HTTP/2 and HTTP/3, with CONNECT-UDP support)
- SOCKS4, SOCKS4A and SOCKS5

</details>

## Download

Get the latest build from [Releases](https://github.com/shixyuu/Guise/releases). Most phones need `arm64-v8a`. These are debug-signed preview builds; to update, just install the new APK over the old one.

Get the NaïveProxy plugin from [klzgrad/naiveproxy](https://github.com/klzgrad/naiveproxy/releases).

## Building from source

- Requirements: JDK 21 or 25, Go 1.27 with gomobile, Android SDK Platform 37.2, Build-Tools 37.0.0, NDK r30.
- Fetch the submodule: `git submodule update --init library/byedpi`
- Build ByeDPI: `./bin/lib/byedpi.sh`. The output goes to `app/src/main/jniLibs/<abi>/libbyedpi.so`.
- Build the core: check out [libexclavecore](https://github.com/exclavenetwork/libexclavecore) and apply `library/core/patches/libexclavecore-guise-dualnet.patch` with `git am` (needed for the dual-network split and its passive measurement). Point `library/core/go.work` at your checkout (see [GUISE.md](GUISE.md)), then run `cd library/core && ./build.sh`.
- Download assets: `./gradlew :app:downloadAssets`
- Build: `./gradlew :app:assembleOssDebug` (debug) or `./gradlew :app:assembleOssRelease` (release).
- Signing: the repository contains no keys. Generate your own keystore with `keytool`, then provide `KEYSTORE_FILE`, `KEYSTORE_PASS`, `ALIAS_NAME` and `ALIAS_PASS` via `local.properties`, environment variables, or a file pointed to by `GUISE_SIGNING_PROPERTIES`. Without them, release builds are left unsigned.

See [GUISE.md](GUISE.md) for design notes and the change log.

## Acknowledgments

- [Exclave](https://github.com/ExclaveNetwork/Exclave) and [SagerNet](https://github.com/SagerNet/SagerNet): the foundation of Guise; most of the credit goes to their authors.
- [hufrea/byedpi](https://github.com/hufrea/byedpi) (MIT): the DPI circumvention engine.
- [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI) (GPL-3.0): reference for, and parts of, the argument handling, strategy list and testing logic.
- [URenko/Accesser](https://github.com/URenko/Accesser) and [SpaceTimee/Cealing-Host](https://github.com/SpaceTimee/Cealing-Host): references for the test target list.
- [Shadowsocks Android](https://github.com/shadowsocks/shadowsocks-android), [husi](https://github.com/xchacha20-poly1305/husi) and the other SagerNet forks.

## License

    Copyright (C) 2023  dyhkwong
    Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

## Notice

Exclave is licensed under the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. However, Exclave optionally incorporates code covered by the GNU General Public License as published by the Free Software Foundation, version 3. If `github.com/exclavenetwork/libexclavecore` is compiled with `with_clash` tag, the GNU General Public License as published by the Free Software Foundation, version 3, applies to all of Exclave.

Guise is licensed the same way as Exclave, under GPL-3.0-or-later. If `libexclavecore` is compiled with the `with_clash` tag, GPL-3.0 applies to the whole program.
