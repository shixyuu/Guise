# Guise

**Blend in, get through.** Guise 是一个安卓代理客户端，目标是在审查严格的网络里更稳地连出去：既能连你自己的代理服务器，也能在没有服务器的时候，用 ByeDPI 对付基于 SNI 的封锁。

*Guise is an anti-censorship Android proxy client. It is a fork of [Exclave](https://github.com/ExclaveNetwork/Exclave) (SagerNet lineage) that adds built-in ByeDPI desync, a DPI strategy prober and Wi-Fi + mobile-data connection splitting.*

> ⚠️ 目前只发布 debug 预览版，签名和包名（`io.github.guise`）都跟 Exclave 不同，可以和 Exclave 同时安装。这是独立的分支，问题请提到本仓库，不要去打扰上游。

## Guise 加了什么

### ByeDPI 直连模式
- 内置 [ByeDPI](https://github.com/hufrea/byedpi)，以独立进程运行，不需要服务器就能对 TLS 握手做拆分、乱序、伪造包等处理，绕过只看 SNI 的封锁。
- 两种用法：
  - 添加一个“仅 ByeDPI”配置，所有流量都经 ByeDPI 直接出去。
  - 平时照常用代理节点，同时把**指定域名**或**全部直连流量**交给 ByeDPI。
- 走 ByeDPI 的域名改用加密 DNS（默认 `https://1.1.1.1/dns-query`、`https://8.8.8.8/dns-query`）解析，避开被污染的系统 DNS。DNS 查询本身也可以选择经过 ByeDPI 发出。
- 默认参数 `-d1 -r1+s`。没有使用 OOB，因为有些中间设备会把 OOB 字节塞进数据流，导致 TLS 握手失败。

### 连通性测试（策略探测）
- 对你勾选的网站批量尝试几十组 ByeDPI 参数，并以直连作为基线，按成功率和延迟排序。好用的参数可以一键应用。
- 内置常见目标：X/Twitter、Twitch、Pixiv、Wikipedia、Reddit、Steam 社区、Internet Archive 等，并以 GitHub 作对照。
- 测试时用 DoH 解析域名，结果不会被 DNS 污染干扰。

### 双网络分流（WiFi + 移动数据）
- 开启后，连着 WiFi 也会让移动数据保持在线，新的代理连接会分配到两个网络上。
- 只在使用远程代理（SS、VMess、VLESS、Trojan、Hysteria2 等）时分流，所以出口 IP 始终是你的服务器。使用 ByeDPI 配置时固定只走一个网络。
- 两种分配方式：1:1 轮流；或者自动定时测两边的延迟和抖动，按结果调整比例，某条线路断了就全部切到另一条。
- 分流以连接为单位，单个连接不会叠加带宽。包级聚合（类似 Speedify）在计划中。

### 继承自 Exclave 的能力
uTLS 指纹、REALITY、ECH、自定义验证 SNI、TLS 分片、XHTTP、Hysteria2 端口跳跃与混淆、mux padding 等抗检测手段，以及分组、订阅、路由、代理链。

支持的协议：

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

## 下载

到 [Releases](https://github.com/shixyuu/Guise/releases) 下载最新版。大多数手机选 `arm64-v8a`。这些是 debug 签名的预览版，更新时直接覆盖安装即可。

NaïveProxy 插件请从 [klzgrad/naiveproxy](https://github.com/klzgrad/naiveproxy/releases) 获取。

## 从源码构建

- 准备 JDK 21 或 25、Go 1.27 与 gomobile、Android SDK Platform 37.2、Build-Tools 37.0.0、NDK r30。
- 拉取子模块：`git submodule update --init library/byedpi`
- 编译 ByeDPI：`./bin/lib/byedpi.sh`，会输出到 `app/src/main/jniLibs/<abi>/libbyedpi.so`。
- 编译内核：需要 [libexclavecore](https://github.com/exclavenetwork/libexclavecore)，并先打上 `library/core/patches/libexclavecore-guise-dualnet.patch`（双网络分流需要），然后执行 `cd library/core && ./build.sh`。
- 下载资源：`./gradlew :app:downloadAssets`
- 构建：`./gradlew :app:assembleOssDebug`（debug）或 `./gradlew :app:assembleOssRelease`（release）。
- 签名：仓库里不包含任何密钥。请用 `keytool` 生成你自己的 keystore，然后通过 `local.properties`、环境变量，或者 `GUISE_SIGNING_PROPERTIES` 指向的文件，提供 `KEYSTORE_FILE`、`KEYSTORE_PASS`、`ALIAS_NAME`、`ALIAS_PASS`。不提供时，release 包不签名。

更详细的设计说明和改动记录见 [GUISE.md](GUISE.md)。

## 致谢

- [Exclave](https://github.com/ExclaveNetwork/Exclave) 与 [SagerNet](https://github.com/SagerNet/SagerNet)：Guise 的基础，绝大部分功劳属于原作者。
- [hufrea/byedpi](https://github.com/hufrea/byedpi)（MIT）：DPI 绕过引擎。
- [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI)（GPL-3.0）：参数处理、策略列表和测试逻辑的参考与部分代码。
- [URenko/Accesser](https://github.com/URenko/Accesser)、[SpaceTimee/Cealing-Host](https://github.com/SpaceTimee/Cealing-Host)：测试目标列表的参考。
- [Shadowsocks Android](https://github.com/shadowsocks/shadowsocks-android)、[husi](https://github.com/xchacha20-poly1305/husi) 以及其他 SagerNet 分支。

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

## Notice

Guise 与 Exclave 一样，以 GPL-3.0-or-later 授权。如果 `libexclavecore` 编译时带有 `with_clash` tag，整个程序适用 GPL-3.0。
