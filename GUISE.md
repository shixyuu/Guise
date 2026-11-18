# Guise fork notes

Guise is a fork of [Exclave](https://github.com/ExclaveNetwork/Exclave) (GPL-3.0). Upstream copyright and license notices are kept intact.

## Rebrand decisions
- applicationId: `io.github.guise` (`version.properties` → `PACKAGE_NAME`). Debug builds: `io.github.guise.debug`.
  Manifest permission `${applicationId}.SERVICE`, FileProvider/startup authorities and process names derive from it automatically.
- Kotlin/Java source namespace stays `io.nekohasekai.sagernet` (android `namespace`, `R`, `BuildConfig`, AIDL, Room schemas).
  Renaming it is cosmetic, would touch ~all files and make upstream rebases painful.
- Internal broadcast/intent actions (`io.nekohasekai.sagernet.SERVICE/CLOSE/RELOAD/...`) are unchanged; they are sent with
  `setPackage(...)` / explicit components, so they don't collide with an installed Exclave.
- Plugin protocol unchanged: `io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN`, `com.github.shadowsocks.plugin.*`, metadata keys.
  Plugin lookup now tries host `BuildConfig.APPLICATION_ID` first, then the original fallbacks (`com.github.dyhkwong.sagernet`,
  `fr.husi`, `io.nekohasekai.sagernet`, `moe.matsuri.lite`), so existing Exclave/SagerNet naive plugins keep working.
  The `plugin/naive` module keeps its upstream applicationId `io.nekohasekai.sagernet.plugin.naive`.
- Internal subscription-import URI scheme `exclave://subscription` → `guise://subscription` (only used between ScannerActivity and MainActivity).
- HTTP User-Agent kept as `Exclave/<version>` for subscription-server compatibility (revisit later).
- Log tag `Exclave` → `Guise`; Go-side log tags (`exclave-core`, `libexclavecore`) unchanged.

## Signing
No keystore is committed (`*.keystore`, `*.jks`, `keystore.properties` are gitignored). Provide
`KEYSTORE_FILE`, `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS` via `local.properties`, env vars, or a properties file whose path is in
`GUISE_SIGNING_PROPERTIES`.

## Not yet renamed (Go side)
`library/core/go.mod` and build scripts still bind `github.com/exclavenetwork/libexclavecore` and depend on
`github.com/exclavenetwork/exclave-core/v5`; Java package of the aar is `libexclavecore.*`; proto package
`exclave.core.app.observatory`; assets dir `exclave-core/`. Renaming these requires forking/renaming the Go modules first.

## Building (verified 2026-11-16 on Linux x86_64)
Toolchain: Go 1.27.4, gomobile/gobind `v0.0.0-20261011034528-197770444867` (same as CI), Android NDK r30
(`30.0.16248370`), JDK 21 (CI uses 25), Android SDK Platform 37.2 + Build-Tools 37.0.0 (auto-installed by AGP).

1. Core AAR: `./run lib core` → `app/libs/libexclavecore.aar` (~66 MB, ~1.5 min). By default this binds the
   *remote* module pinned in `library/core/go.mod`. To build from local forks of the Go repos, create a
   (gitignored) `library/core/go.work`:
   ```
   go 1.27
   use (
   	.
   	/path/to/libexclavecore
   	/path/to/exclave-core
   )
   ```
2. ByeDPI: built automatically by the Gradle task `:app:buildByeDPI` (hooked into `preBuild`), or manually with
   `bin/lib/byedpi.sh`. Needs the `library/byedpi` submodule (`git submodule update --init library/byedpi`) and an
   NDK (`ANDROID_NDK_HOME`, or the newest one under `$ANDROID_HOME/ndk`).
3. `./gradlew :app:downloadAssets` once (geoip/geosite), then `./gradlew :app:assembleOssDebug`.
4. Unit tests (JVM): `./gradlew :app:testOssDebugUnitTest`.

## ByeDPI integration
**Source.** `library/byedpi` is a git submodule of https://github.com/hufrea/byedpi (MIT), pinned to
`ba532298` (v0.17.3-38, 2026-03-27) — the same commit romanvht/ByeByeDPI ships. The last tag v0.17.3 (2025-09) is
too old: it lacks the `c` (connect error) trigger for `-A`, which ByeByeDPI's strategy list relies on. No patches
are needed (byedpi already implements `--protect-path`).

**Packaging.** `bin/lib/byedpi.sh` cross-compiles byedpi as a PIE *executable* for armeabi-v7a/arm64-v8a/x86/x86_64
(API 21, 16 KB page size) into `app/src/main/jniLibs/<abi>/libbyedpi.so` (generated, gitignored). With
`useLegacyPackaging = true` it is extracted to `nativeLibraryDir` and exec'd from there, like `libnaive.so`.
ByeByeDPI instead links byedpi into a JNI library and runs it in-process; Guise uses a separate process so it
fits the existing `GuardedProcessPool` (restart on crash, killed with the service, stderr/stdout to logcat with
tag `libbyedpi`) and so byedpi's global state/`exit()` can't take down the `:bg` process.

**Lifecycle.** `ConfigBuilder` allocates a free port (`mkPort()`) when ByeDPI is needed and returns it as
`V2rayBuildResult.byedpiPort`. `V2RayInstance.launch()` starts
`libbyedpi.so --ip 127.0.0.1 --port <port> [--protect-path <path>] <user args>` before starting the core. Service
stop closes the process pool; any setting change in the ByeDPI section calls `needReload()` so the service restarts
with the new arguments. If byedpi rejects the arguments it exits and the service stops with an error.

**Socket protection (no VPN loop).**
- TUN stack *gVisor* (default): `VpnService` always excludes the app's own package
  (`addDisallowedApplication(packageName)` / not in the allow list), and per-app VPN rules are per-UID, so byedpi
  (same UID) bypasses the tunnel automatically. No protect path is passed.
- TUN stack *System*: the app is inside the VPN (`needIncludeSelf`). `Libexclavecore.newTun2ray()` serves
  `no_backup/protect_path` (shadowsocks-android protocol: fd via SCM_RIGHTS, 1-byte reply), which is exactly what
  byedpi's `--protect-path` speaks (`extend.c: protect()`), so byedpi gets `--protect-path <no_backup>/protect_path`
  (`ProxyInstance.byedpiProtectPath()`). URL tests reuse the test instance's protect path the same way.
- Proxy-only mode (no VPN): nothing to protect.

**Direct mode (Settings → ByeDPI).**
- *Enable ByeDPI direct mode* (`byedpiEnabled`): adds a `socks` outbound `byedpi` → 127.0.0.1:<port>.
- *Scope* (`byedpiMode`): `0` = all direct traffic: every routing rule whose outbound is `bypass` (user "direct"
  rules, and the catch-all of route mode "Direct") is retargeted to `byedpi`, except DNS-internal rules
  (`inboundTag`) and `geoip:private`. `1` = selected domains: a rule `domains → byedpi` is inserted before user rules.
- *Domains* (`byedpiDomains`): one per line; bare `example.com` becomes `domain:example.com`; V2Ray syntax
  (`full:`, `regexp:`, `geosite:`…) passes through.
- *Arguments* (`byedpiArgs`): raw byedpi CLI. `--ip/--port/--protect-path/--daemon/--pidfile/--help/--version` are
  stripped (Guise owns them). Empty = default `-d1 -r1+s` (byedpi README's first example: disorder at byte 1 + TLS
  record split after the SNI start). guise.1 shipped ByeByeDPI's `-o1 -a1 -r-5+se`; that value is migrated
  automatically. Why it was replaced: `-o` sends a TCP urgent (OOB) byte that only disappears if the URG flag survives
  end to end. Middleboxes/NATs/transparent proxies that clear URG leave the byte in the stream (TLS header becomes
  `16 61 03 ..`), servers answer `protocol_version`/`decode_error` alerts ("remote error: tls: protocol version not
  supported"), and DNS-over-TCP through the profile breaks too, so nothing loads. Reproduced on a NATed Linux host:
  `-o1` failed ~50% of TLS handshakes, `-d1 -r1+s` and `-s1 -r1+s` 0 failures; replaying a ClientHello with the
  OOB byte inline gets a fatal alert from Google, Cloudflare, GitHub and Microsoft. Strategies with `-o`/`-q` remain
  usable when the user's path preserves URG.
- *Diagnostics*: the in-app log shows `Guise: ByeDPI: <full command>`, `ByeDPI listening on 127.0.0.1:<port>`
  (Guise waits up to 1.5 s for the port before starting the core) and byedpi's own stderr under tag `libbyedpi`.
  With log level Debug, byedpi runs with `-x1` (per-connection desync log).
- *Add ByeDPI-only profile*: creates a SOCKS profile with the placeholder address `byedpi.guise.invalid`. When it
  (or a chain/rule using it) is active, ConfigBuilder points it to the local byedpi port, so Guise works with no
  proxy server at all ("select the ByeDPI profile and connect"). This also works in URL tests.

- ByeDPI profiles (placeholder address) are never flagged "insecure": the SOCKS hop is loopback, like
  `127.0.0.1`/`localhost` profiles already were.

**Known limitations.**
- Not runtime-tested on a device yet. ConfigBuilder output was checked by review only (it needs the Android DB);
  `ByeDPI.kt` (arg parsing, domain list) has JVM unit tests; byedpi itself was sanity-checked on Linux (desync log,
  `--protect-path` fd hand-off against a mock server).
- Global ByeDPI is not applied to custom full-JSON (`v2ray`) configs, and not to the connection to remote proxy
  servers (`direct` tag), only to `bypass` traffic.
- UDP goes through byedpi's SOCKS UDP associate (enabled by default); QUIC is not desynced unless args say so.
- ByeDPI defeats SNI/DPI-based blocking (RU/IR/TR style). It cannot help against IP blocking (e.g. most of the GFW
  blocklist); in China it is mainly useful for SNI-filtered sites on non-blocked IPs.

## DNS for ByeDPI (guise.5)
**Problem.** byedpi only rewrites the client's TLS/HTTP stream; the address must already be real. Before guise.5:
- ByeDPI-only profile: names were resolved by the remote DNS (default `tcp://1.1.1.1`) sent *through* byedpi as
  plain DNS-over-TCP, which the GFW can still tamper with; and whenever the core handed a *domain* to the socks
  outbound (fake DNS, `destinationOverride`, HTTP/SOCKS inbound clients, URL tests) byedpi resolved it with
  `getaddrinfo`, i.e. the polluted system resolver.
- "selected domains": the domains were resolved by whatever server matched (often direct/system DNS), so apps got
  poisoned IPs before byedpi was even involved.
- "all direct traffic" + route mode Direct: everything was resolved by the direct (system) DNS.
Verified on the box with exclave-core + host byedpi (`-x2`): with a domain target byedpi logged `resolve: x.com`
→ the system answer (the box's fake-IP 198.18.0.1, standing in for a poisoned answer).

**Fix** (`ByeDPI.dnsPlan`, ConfigBuilder):
- New settings in ByeDPI: "DNS for ByeDPI" (`byedpiDns`, default `https://1.1.1.1/dns-query` +
  `https://8.8.8.8/dns-query`, IP literals so no bootstrap lookup; `cloudflare-dns.com`, `dns.google`,
  `dns.quad9.net` are pinned in `dns.hosts` if used) and "Send ByeDPI DNS through ByeDPI" (`byedpiDnsViaByedpi`,
  default on). Those servers get tag `dns-byedpi` and a routing rule `inboundTag dns-byedpi -> byedpi` (or
  `bypass` when the switch is off). Other DoH host names are resolved via the direct DNS (skip-fakedns list).
- ByeDPI-only profile as the main outbound (also URL tests; not with route mode Direct): "DNS for ByeDPI"
  replaces the remote DNS servers, query strategy UseIPv4.
- Global "selected domains": "DNS for ByeDPI" servers with `domains = <the list>` are inserted first
  (UseIPv4, `fallbackStrategy: disabled`, fake DNS v4 pool attached when fake DNS is on).
- Global "all direct traffic" + route mode Direct: "DNS for ByeDPI" replaces the (direct) DNS for everything.
  With rule-based routing the bypassed (mostly domestic) names keep the direct DNS — use "selected domains" for
  blocked foreign sites.
- Every ByeDPI socks outbound (global `byedpi` tag, now present whenever byedpi runs, and the ByeDPI-only profile
  outbound) has `domainStrategy: UseIPv4`: the core resolves any domain target with its internal DNS (fake DNS
  excluded, so fake-IP mode works too) and byedpi only ever gets an IPv4 address. SNI is untouched because
  byedpi forwards the client's own ClientHello.
- The connectivity test resolves targets with the same servers: RFC 8484 GET (wire format) to the https entries,
  through a temporary byedpi with the current args when the via-ByeDPI switch is on.
Box check (exclave-core + byedpi `-x2`, config shaped like the generated one): the DoH query left via byedpi to
1.1.1.1:443, the core logged `DOH//1.1.1.1 got answer: x.com -> [162.159.140.229]`, and byedpi received
`addr=162.159.140.229:443`, `host: x.com` — no `resolve:` line any more.

## Connectivity test (ByeDPI probe) — implemented in guise.3
Entry points: Settings → ByeDPI → "Connectivity test", and a card on the main screen's Network page. Both open
`ui/ByeDPIProbeActivity`.
- **Targets (guise.4):** a checklist of presets (`byedpi/ByeDPIProbeTargets.kt`) plus user-added domains/URLs,
  selection remembered in `byedpiProbeTargets` / `byedpiProbeCustom`. Presets focus on sites that from mainland
  China are blocked by DNS poisoning + SNI-based DPI while their IPs stay reachable — the case desync can fix.
  Evidence used: inclusion in the SNI-bypass rule sets of URenko/Accesser (`accesser/rules.toml`, `accesser/pac`)
  and SpaceTimee/Cealing-Host (`Cealing-Host.json`, used by Sheas Cealer), both maintained for mainland users.
  | preset | URLs hit | tag | default |
  |---|---|---|---|
  | X / Twitter | x.com, abs.twimg.com, pbs.twimg.com, twitter.com favicons | SNI (Cealing-Host) | on |
  | Twitch | www.twitch.tv favicon, static-cdn.jtvnw.net preview jpg | SNI (both) | on |
  | Pixiv | www.pixiv.net favicon, s.pximg.net logo | SNI (both; Pixiv-Nginx) | on |
  | Wikipedia | zh.wikipedia.org favicon, upload.wikimedia.org logo | SNI (both) | on |
  | Reddit, Steam Community, Internet Archive | favicons | SNI (both) | off |
  | Discord, Pinterest, Instagram | favicons | uncertain (listed, but IPs/CDN often blocked) | off |
  | Google, YouTube | favicon, gstatic generate_204 | usually IP-blocked (Cealing routes Google via its own relay host) | off |
  | GitHub | favicon | control, normally not blocked | off |
  Each preset gets `requests` requests cycling through its URLs; results show per-target counts.
- **Other inputs:** requests per target (3), timeout (5 s), strategies in parallel (3, max 4), fake SNI for
  `{sni}` (`google.com`), strategy list (empty = bundled `assets/byedpi/strategies.list`, a verbatim copy of
  ByeByeDPI's `proxytest_strategies.list` @ 7b7eecf plus an attribution header; `{sni}` becomes `"<sni>"`), and
  "Resolve with DoH" (on).
- **DNS:** blocked sites are usually DNS-poisoned as well, and byedpi resolves SOCKS domain requests with the
  system resolver, so the test resolves every host once up front via DoH JSON to IP-literal endpoints
  (1.1.1.1, 1.0.0.1, 8.8.8.8; system DNS as fallback) and connects to that IP with SNI/Host = real name. In
  normal use the core's DNS settings decide which IP byedpi gets.
- **Plan:** row 1 = direct without ByeDPI (baseline, not applicable), row 2 = the current `byedpiArgs`, then the
  list (current args not repeated). Parsing/plan/median/sorting live in pure-JVM `byedpi/ByeDPIProbe.kt`
  (unit-tested in `ByeDPIProbeTest`).
- **Runner (`byedpi/ByeDPIProbeRunner.kt`, UI process):** per strategy, `ProcessBuilder` starts
  `libbyedpi.so` via the same `ByeDPI.buildCommand` as runtime on a `mkPort()` port, waits for it to listen
  (`ByeDPI.waitForListening`, shared with `V2RayInstance`), runs the requests through
  `Proxy(SOCKS, 127.0.0.1:port)` to the pre-resolved IP (targets in parallel) with a small HTTP/1.1 client over
  `SSLSocket` (SNI + hostname verification on the real name), then kills the process. Output goes to logcat tag
  `libbyedpi` with a `[probe:port]` prefix. Request check = ByeByeDPI `SiteCheckUtils`: `Connection: close`,
  any HTTP status counts, body must reach Content-Length (or end cleanly / 1 MB); a truncated body or TLS
  failure counts as blocked. Redirects are not followed (the first response already proves reachability). Strategies run in parallel under a semaphore; Stop cancels the job and
  the `finally` kills the processes.
- **Network context:** same rule as the URL test in `ConfigurationFragment`: if Guise runs in VPN mode with the
  System stack, the app UID is inside the tunnel, so byedpi gets `--protect-path` and the baseline sockets and DoH lookups use
  `Network.bindSocket`/`openConnection` on the underlying network from `DefaultNetworkListener`. With gVisor or when
  stopped, the app is outside the VPN and nothing extra is needed. Testing *through a proxy node* is not
  supported; the help text says results describe the phone's current network only.
- **Results:** sorted by success rate, then median latency (baseline pinned on top). Tap → dialog: Apply (writes
  `DataStore.byedpiArgs`, `SagerNet.reloadService()` if running; settings screen refreshes the field on resume)
  or Copy.
- Possible later work: option B from the design (measure through a throw-away `V2RayTestInstance` so the result
  includes the core), HTTP-only sites, per-site breakdown.

## Dual network: Wi-Fi + mobile data split (guise.6, passive measurement since guise.7)
Optional, **off by default**. Settings → 双网络（WiFi + 移动数据）.

| Key | Default | Meaning |
|---|---|---|
| `dualNetEnabled` | off | Keep cellular up next to Wi-Fi (`ConnectivityManager.requestNetwork`) and enable the feature |
| `dualNetStrategy` | `0` = 1:1 | `0` alternate networks per new proxy-server connection; `1` auto (latency/jitter weighted) |
| `dualNetSampleKb` | 256 KB | Auto mode: take one passive sample per this many KB sent on a network (clamped 16–65536). Replaces guise.6's `dualNetProbeInterval` timer probe |
| `dualNetSingleNetwork` | `0` = Wi-Fi/default | Network used when not splitting (ByeDPI profile, direct traffic): `0` system default, `1` mobile data |
| `dualNetStatus` | – | Read-only: last status line written by the service (`noBackupFilesDir/dualnet_status`) |

**When it splits.** Only when the selected profile is a real remote proxy (SS/VMess/VLESS/Trojan/Hysteria2/…).
`ConfigBuilder` puts sockopt `mark = 0x47554953` ("GUIS") on every outbound that dials a proxy server. The
patched `libexclavecore` (`library/core/patches/libexclavecore-guise-dualnet.patch`) strips that mark (it is never
set as SO_MARK) and, right after `VpnService.protect()`, hands the fd to the app's `SocketBinder`
(`dualnet/DualNetworkManager.kt`), which calls `Network.bindSocket()` on Wi-Fi or LTE before `connect()`. With the
ByeDPI-only profile nothing is split: ByeDPI (via `protect_path`, now also enabled for gVisor when the feature is on)
and direct sockets are pinned to `dualNetSingleNetwork`. Loopback sockets (core → byedpi, plugins) are never touched.
In proxy-only (non-VPN) mode a protected dialer without VPN protection (`WithGuiseDialer`) is installed so the hook
still runs.

**Passive measurement (auto mode, guise.7).** No probe traffic. `libexclavecore/sampler.go` tracks every split
TCP connection it dialed (via its `syscall.RawConn`, so no raw fd can be reused after close). While at least one is
open, a 1 s ticker reads each one's `TCP_INFO` (local `getsockopt`, no packets) and sums the bytes sent per network
(`tcpi_bytes_acked`, else `segs_out × mss`, else "data sent in the last second" on very old kernels). Each time a
network crosses `dualNetSampleKb`, one sample is taken from that network's busiest connection in that tick: kernel
smoothed RTT (`tcpi_rtt`), RTT variance (`tcpi_rttvar`, used as jitter) and the retransmitted share of segments since
that connection's previous sample (`total_retrans / segs_out` deltas). So at most one sample per network per second,
and none when idle; weights keep their last values. Connect handshakes on split sockets also give one RTT sample;
connect errors/timeouts (except ECONNREFUSED) and connections stuck in ≥3 consecutive RTOs with unacked data are
reported as failures. Samples go to Kotlin through `SocketBinder.ReportSample`. The trigger/delta/loss logic lives in
the pure-Go package `libexclavecore/dualnet` with host-runnable tests (including one on a real loopback TCP socket).

**UDP / QUIC: active probing (guise.8).** UDP sessions (Hysteria2, TUIC, Juicity, ShadowQUIC, mKCP, QUIC
transport, WireGuard…) have no `TCP_INFO`, so `libexclavecore/udpsampler.go` probes them, driven by traffic. Split UDP
sockets are wrapped in a counting `PacketConn` (Hysteria2 takes `PacketConnWrapper.Conn`, so its port-hopping sockets
are covered too). On the same 1 s tick, bytes sent over split UDP sockets are summed. When at least `dualNetSampleKb`
were sent **and** `dualNetUdpProbeInterval` seconds (default 10, 0 = off, clamped 3–600) have passed since the last
round, one probe round runs: for the (up to 2) busiest UDP servers, one probe per available network
(`SocketBinder.ActiveNetworks`). Each probe socket is `VpnService.protect`ed and bound to that network
(`SocketBinder.BindSocketTo`). No traffic means no probes.
- **Probe method per server IP** (`dualnet.DestProber`):
  1. **QUIC version negotiation.** A 1200-byte long-header packet with the reserved version `0x1a2a3a4a`. Every
     QUIC server must answer it with a Version Negotiation packet, and the reply is checked by the echoed
     connection IDs. Verified on the box against quic-go and apernet/quic-go (the Hysteria2 server library).
  2. If 2 rounds get no reply on any network (e.g. Hysteria2 with salamander/gecko obfuscation, WireGuard, mKCP):
     a **TCP connect** to the same ip:port. SYN-ACK or RST both give the RTT. The socket is closed with
     SO_LINGER 0, so no data is sent.
  3. If that is silent for 2 rounds too: **off** for that server (the state is forgotten after 5 min idle).
- **Loss.** Timeouts count as loss/failures only after the method has been answered at least once for that
  server, so a server that simply never answers can't make a network look dead. After 5 silent rounds the proof is
  reset and fallback restarts.
- Probe RTTs feed the same balancer (jitter = variation between consecutive probe RTTs).
- The status line shows `UDP probe: <server> quic-vn | tcp-connect | off`.
- Cost per round: about 1.2 KB + reply per network per server.

**Hysteria2 "disable loss compensation" (guise.8)**, per profile (`Hysteria2Bean.disableLossCompensation`,
bean version 13, older data reads as off). It is written to the outbound `congestion.disableLossCompensation`
only when an upload speed is set, because that is the only time the client uses Brutal. In the hysteria core
(`congestion/brutal`), Brutal paces at `bps / ackRate`, where ackRate = acked/(acked+lost) over the last 5 s
(≥ 50 packets), floored at 0.8. That is up to 1.25× the configured rate, and the congestion window is scaled the
same way. The option pins ackRate to 1, so the client sends exactly the configured upload rate. It doesn't affect
downloads: the server's Brutal toward the client follows the server's own setting. The hysteria2:// URI has no
bandwidth parameters, so there is no share-link parameter either.

**Balancer** (`dualnet/DualNet.kt`, unit-tested in `DualNetTest`): window of the last 10 samples per network;
latency = median RTT, jitter = median kernel RTT variance (or mean RTT difference if unknown), loss = mean
retransmit share with failures counted as 1. Score = (1−loss)² / (latency + 2·jitter + 10 ms). A network with 3
failures in a row is dead (weight 0) and gets an exploration share again after 30 s; a network without samples
(no connections yet, or just came up) gets the average healthy score so it receives connections and gets measured;
if everything is dead the weights are equal. New connections are assigned by smooth weighted round-robin. Network
loss/gain is handled by the `NetworkCallback`s: a lost network is removed and its stats dropped. Log tag `Guise`,
lines `dual-network: …` (at most every 10 s) show networks, weights, RTT/jitter/loss, connection and sample counts,
and `DOWN` for dead networks; the settings status line shows the same.

**Limitations.** Splitting is per underlying connection: one mux / XUDP / Hysteria2 (QUIC) session stays on the
network it was opened on. Auto mode needs traffic (TCP: passive; UDP: probes only while traffic flows): right after start, or when idle, weights are neutral/stale.
External plugin processes are not split. In proxy-only mode ByeDPI is not pinned. Sockets on a network that
disappears die with it; new connections go to the remaining network. Mobile data is used even on Wi-Fi.

**Packet aggregation** (bonding one connection over both links): placeholder, not implemented.

## ByeDPI in front of TCP proxies (guise.9)

Setting: ByeDPI → "ByeDPI in front of TCP proxies" (`byedpiPreLayer`, default off).

- When on, ConfigBuilder starts ByeDPI (even if direct mode is off) and gives every outbound that
  reaches its server over TCP `proxySettings = {tag: "byedpi", transportLayer: true}`. The server
  connection is then made by ByeDPI, which desyncs the first packets (TLS ClientHello / REALITY /
  the protocol header) on the way to the proxy server.
- Covered protocols: Shadowsocks, VMess, VLESS, Trojan, AnyTLS, HTTP, SOCKS, SSH, Snell, mieru (TCP),
  TrustTunnel (non-QUIC). Transports: tcp/raw, TLS, REALITY, ws, grpc, httpupgrade, xhttp/splithttp
  (h1/h2), meek.
- Not covered: Hysteria2, TUIC, Juicity, WireGuard, mKCP, QUIC, xhttp with ALPN h3, mieru UDP,
  TrustTunnel QUIC, and plugin profiles that run an external process (Naive etc.).
- Chains: only the hop that faces the network goes through ByeDPI; inner hops already dial through
  the previous outbound.
- The `byedpi` socks outbound uses `domainStrategy: UseIPv4`, so the core resolves the server name and
  ByeDPI receives an IP. ByeDPI's sockets are protected with protect_path as before.
- Limitations:
  - Shadowsocks / VMess have no SNI; desync only reshuffles the first bytes and rarely matters there.
  - URL tests (latency tests) don't use the pre-layer.
  - Dual network: ByeDPI's sockets are pinned to the single network, so TCP proxies don't split while
    the pre-layer is on. UDP proxies still split.
  - Verified on the box: VLESS (tcp) and Trojan (TLS) through host ciadpi `-d1 -r1+s`; ByeDPI logged
    `new conn ... addr=<server>` + `split ... DESYNC_DISORDER` and both requests succeeded.

## ByeDPI automatic strategy selection (guise.9)

Settings: "Auto-select strategy" (`byedpiAuto`, default off), "Re-test interval (minutes)"
(`byedpiAutoInterval`, default 60, 10–1440), "Auto-selection status" (tap for the pool).

- Runs in the service process while Guise is connected and ByeDPI is running (direct mode,
  ByeDPI-only profile or pre-layer). Code: `byedpi/ByeDPIAuto.kt` (pure logic, unit-tested) and
  `byedpi/ByeDPIAutoStrategy.kt`.
- Full test: all strategies of the connectivity test (built-in 59 or your edited list) plus the current
  arguments, against the targets selected on the connectivity-test page. The best 12 (success rate,
  then median latency; zero-success rows dropped) become the pool and the best one is applied.
- Every interval the pool (plus the current arguments) is re-tested. A full test is run instead when
  the pool has fewer than 3 entries, its best entry is below 50 %, or the last full test is older
  than 24 h.
- Every 5 minutes a 1-request health check of the current arguments runs. If it fails, the pool is
  re-tested and the best working entry is applied at once; if none works, a full test runs.
- A switch only happens to a strictly better success rate, or the same rate with < 80 % latency.
- Applying = write `byedpiArgs`, stop the ByeDPI process and start it again on the same port. Open
  connections through ByeDPI are cut at that moment.
- Tests use the same runner as the connectivity test: throw-away ByeDPI processes, DoH resolution,
  protected sockets (outside the VPN).
- Limitation: candidates are tested against the target sites, not by a TLS handshake to your proxy
  server, so for the pre-layer the ranking is a proxy for "which desync gets through here".

## Setting descriptions (long form)

UI texts were cut to one line in guise.9; the details live here.

- ByeDPI arguments: raw ciadpi options; `--ip`, `--port`, `--protect-path` are managed by Guise.
- ByeDPI domains: `example.com` matches the domain and subdomains; `full:`, `regexp:`, `geosite:` work.
- DNS for ByeDPI: used for names that go through ByeDPI (ByeDPI-only profile, "selected domains",
  "all direct traffic" with route mode Direct). Prefer DoH to IP addresses, e.g.
  `https://1.1.1.1/dns-query`. "Send ByeDPI DNS through ByeDPI" helps when the DoH server itself is
  filtered.
- Connectivity test: one temporary ByeDPI per strategy, small requests to the selected targets over
  the phone's current network. Pick SNI-blocked sites (X, Twitch); IP blocks can't be fixed by desync.
  Testing through a proxy node is not supported. "Resolve with DNS for ByeDPI" uses the same DoH
  servers and route, falling back to system DNS.
- Dual network: only splits with a remote proxy profile; with the ByeDPI-only profile traffic stays on
  one network. Uses mobile data on Wi-Fi and more battery. "Sample every N KB" (default 256) is the
  passive TCP_INFO trigger; "UDP probe interval" (default 10 s, 0 = off) is the minimum gap between
  QUIC version-negotiation / TCP-connect probes for UDP profiles.
- Hysteria2 "Disable loss compensation": only with an upload speed set (Brutal). Brutal normally
  sends up to 1.25× the set rate (1/ackRate, ackRate floored at 0.8); with this on it sends exactly
  the set rate.

## Home screen (Material 3)

The Expressive home screen (`fragment_home_expressive.xml`, `HomeExpressiveFragment`) uses only
Material 3 components and theme color roles (dynamic color on Android 12+): filled / outlined /
elevated cards, a large FAB for connect, assist chips, M3 linear progress indicators (wavy for the
scan progress) and the M3 navigation bar. The hero card is `colorPrimaryContainer` while connected
and `colorSurfaceContainerHighest` while idle. The hero status text uses the Michroma font
(`res/font/michroma.ttf`, Copyright 2011 The Michroma Project Authors, SIL Open Font License 1.1,
full text in `licenses/Michroma-OFL.txt`); everything else uses the M3 type scale.

## Third-party code reused
- hufrea/byedpi (MIT) — submodule `library/byedpi`, notice added to `assets/license/notices.txt`.
- romanvht/ByeByeDPI (GPL-3.0, compatible): `shellSplit` (utility/ArgumentsUtils.kt, itself from a public gist),
  argument filtering idea (core/ByeDpiProxyPreferences.kt `parseCmdToArguments`), default args
  (utility/PreferencesUtils.kt), compiler/linker flags (app/src/main/cpp/CMakeLists.txt), and for the connectivity test:
  `app/src/main/assets/proxytest_strategies.list` (copied verbatim to `assets/byedpi/strategies.list`),
  utility/SiteCheckUtils.kt (request/verification logic, adapted in `byedpi/ByeDPIProbeRunner.kt`),
  services/TestService.kt + activities/TestActivity.kt + data/TestResult.kt (`{sni}` substitution, run flow,
  result model; adapted in `byedpi/ByeDPIProbe.kt` and `ui/ByeDPIProbeActivity.kt`). Attributed in those files
  and here.
- URenko/Accesser and SpaceTimee/Cealing-Host were consulted only as evidence for which sites are SNI-blocked
  (domain names in their rule lists); no code or rule files were copied.
- Note on "domain fronting" for Google (asked in guise.3 feedback): Sheas Cealer reaches Google by connecting to
  a reachable Google front-end IP with a different, unblocked SNI (`g.cn`) and the real Host header, launched as
  Chromium flags (host-rules + ignore cert errors). That needs control of the browser's TLS (or a MITM CA), which
  a VPN/proxy app like Guise does not have on modern Android, and it still depends on finding unblocked Google
  IPs. byedpi's fake-SNI packets (`-f/-n`) are a different trick (decoy packets that the server never sees).
- dovecoteescapee/ByeDPIAndroid (GPL-3.0, last commit 2024-09) was reviewed; ByeByeDPI is its maintained successor
  and supersedes it, so nothing was taken from it directly.
