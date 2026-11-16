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
