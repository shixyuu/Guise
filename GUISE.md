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
