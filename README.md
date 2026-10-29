# NetLimiterPro — v1 infrastructure

Stage 1 contains the Android/Gradle project foundation, bilingual resources,
a local rule repository, a token-bucket throttling primitive, a declared
`VpnService`, and a GitHub Actions workflow that builds and uploads the debug APK.

## Build

GitHub Actions installs JDK 17 and Gradle 8.9, generates a Gradle wrapper if
the wrapper script or JAR is missing, runs `:app:assembleDebug`, and uploads
`app/build/outputs/apk/debug/app-debug.apk` as an artifact.

## Important scope boundary

This stage is infrastructure only and intentionally does not include the UI.
The token bucket is a rate-limiting primitive, not a complete VPN packet
forwarder. Per-UID enforcement across Wi-Fi and mobile data requires a
working TUN packet engine and reliable flow-to-UID attribution; Android's
`VpnService` declaration alone does not provide that functionality. Therefore
v1 does not claim to actively throttle application traffic yet.


## Stage 3 engineering status

Added a live underlying-network monitor, VPN foreground-service notification,
rule reload broadcasts, an IPv4/IPv6 packet parser, TCP/UDP tuple parsing,
best-effort `getConnectionOwnerUid` lookup, and per-UID token-bucket selection.

**Critical data-plane limitation:** a `VpnService` TUN interface is not a
transparent forwarding tunnel by itself. The current project does not bundle
a complete TCP/IP userspace relay (for example, a tested tun2socks/IP stack).
The service therefore deliberately does not establish a default-route tunnel
that would black-hole traffic. This means active Internet throttling is not
yet complete; it requires a real packet-forwarding backend and device-level
validation. No successful Android build is claimed until GitHub Actions runs.

## Stage 4 cumulative update

This update fixes the `Locale.ROOT` import, adds a focused missing-import heuristic
to `tools/static_audit.py`, prevents reload broadcasts from launching a stopped
service, stores and forwards refreshed rules/default limits to an attached live
engine, and displays Wi-Fi and mobile-data limits separately in each app row.
The manifest declares the non-exported command receiver, foreground-service
special-use permission, and notification permission.

`TrafficEnforcementBackend` documents and probes backend capabilities without
claiming that root access alone means `tc` rules are installed. **The packet
forwarding data plane is still not implemented.** A reader-and-delay loop alone
would discard packets; a generic TCP relay requires a real userspace TCP/IP stack
(or an established, maintained relay dependency) and must be tested on-device.
For that reason the service still does not establish a TUN route. This archive
passes static checks only; it has not been verified by an Android/Gradle build
and should not be described as production-ready or as actively limiting traffic.


## v5 dual-plane backend status

- `LocalThrottledProxyServer` implements loopback HTTP proxying, HTTPS `CONNECT`, protected upstream sockets, and token-bucket pacing. It only shapes traffic from apps that honor Android's VPN HTTP proxy setting. It is **not** a transparent proxy and does not capture arbitrary raw TCP/UDP, QUIC, or apps that ignore proxy configuration. `setHttpProxy` is advisory at the platform/app level and cannot guarantee universal per-app enforcement.
- `RootTrafficShaper` attempts IPv4/IPv6 `iptables` `owner` + `hashlimit` egress rules when `su` is granted. This is packet-drop rate guarding, not lossless queue-based shaping; kernel module support and command syntax vary by device, and it does not provide a general UID-aware inbound limiter.
- No default TUN route is installed, intentionally. A static audit pass is not proof of successful Android compilation, device connectivity, or accurate throughput. Build with Android SDK and test on representative rooted/non-rooted devices before release.

## v6 cumulative safety and enforcement notes

This cumulative revision adds an explicit battery-optimization exemption entry point and expands the optional root backend to install best-effort INPUT/OUTPUT hashlimit rules using CONNMARK. Root hashlimit rules drop excess packets; they are not a lossless traffic shaper, and inbound UID attribution depends on conntrack/CONNMARK support in the device kernel.

**Universal non-root TUN forwarding is not implemented in this release.** Android's `VpnService` exposes raw IP packets, while `SocketChannel` and `DatagramChannel` are transport sockets, not a TCP/IP stack. A correct transparent TCP relay must implement TCP state, retransmission, sequence/acknowledgement handling, windowing, resets, MTU/fragmentation, IPv4/IPv6 checksums, and UDP flow/NAT mapping. Dropping UDP/443 alone does not guarantee that an app will fall back to TCP. Therefore this version intentionally does not add a default route to TUN and does not claim to throttle every app/protocol. The local HTTP proxy remains limited to apps that honor Android's proxy configuration; the root backend is device/kernel-dependent.

## v7 cumulative data-plane additions

- `LocalThrottledProxyServer` now negotiates SOCKS5 no-auth and supports RFC 1928 CONNECT targets for IPv4, domain names, and IPv6 on the same loopback port as HTTP/HTTPS CONNECT. The accepted-socket owner lookup checks the requested tuple order first and the reverse tuple second, accepting only UIDs present in active rules. When attribution is unavailable, the fallback bucket uses the lowest positive enabled rate rather than an arbitrary UID.
- `TrafficStatsTracker` keeps thread-safe per-UID and aggregate byte counters and estimates bytes/second. Proxy relay writes feed the tracker; the foreground notification refreshes once per second and displays aggregate observed proxy throughput.
- `PacketShaperEngine.buildIpv4UdpPacket` builds IPv4/UDP datagrams with IPv4 header and UDP pseudo-header checksums. This is a packet-construction utility, not a live UDP NAT relay.
- `Tun2SocksBridge` probes for packaged native libraries but does not mistake a loadable `.so` for an integrated tun2socks data plane. No compatible native binary/JNI entry point is bundled, so full-route TUN mode remains disabled and the existing proxy fallback remains in place.

**Coverage limitation:** This release does not claim universal non-root coverage. SOCKS5/HTTP proxy traffic can be shaped when applications explicitly use the local proxy. Direct TCP, arbitrary UDP/DNS, QUIC, and games are not transparently relayed by this release. The checksum builder is not wired to a TUN UDP session table, and no TCP userspace stack is present. A full-tunnel default route must remain disabled until an actual tested tun2socks/IP stack backend is packaged and integrated.

## v8 cumulative policy and UI update

- `AppRule` and `RuleRepository` now persist independent `ALLOW`, `THROTTLE`, and `BLOCK` policies for Wi-Fi and mobile data while migrating older JSON records from the legacy enabled flags.
- The main screen adds bulk actions over the currently visible rows (therefore respecting the active app filter and search), per-row network-target chips, direct policy actions, and a per-UID live-rate field. The live-rate tracker reports observed proxy-relay bytes; it is not a privileged system-wide network monitor, so zero does not prove that an app has no direct traffic.
- The root backend attempts `tc` HTB classes/filters plus OUTPUT marks and retains `iptables` hashlimit/drop rules as a device-dependent fallback. `hashlimit` is lossy packet dropping, not smooth lossless shaping. Root qdisc setup may fail if the device has incompatible qdiscs, drivers, permissions, or kernel modules; test on-device before relying on it.
- The proxy now returns HTTP 403 or SOCKS5 connection-not-allowed for a connection whose owner UID is successfully attributed and has `BLOCK`. If Android cannot attribute the socket to a UID, proxy-only mode cannot guarantee per-app block enforcement.
- A VPN sinkhole route is used only when all rules selected for the active network are `BLOCK`; only those allowlisted packages are routed into a TUN with no packet reader, so their IPv4/IPv6 packets are discarded. This is a block-only path, not a general forwarding backend. When `THROTTLE` rules coexist, the service retains proxy mode and cannot transparently block arbitrary UDP/DNS/QUIC or apps that bypass the proxy.
- CMake/NDK now build a JNI boundary named `netlimiter_tun2socks`. Its native start function deliberately returns `-1`; it is a build-safe integration seam, **not** an included tun2socks engine. Full transparent TCP/UDP forwarding remains unavailable until a real licensed and tested native engine is integrated.

`python3 tools/static_audit.py` validates source/resource/build configuration only. It does not replace `assembleDebug`, device tests, root-kernel compatibility tests, or traffic-enforcement validation. The environment used to prepare this archive has no configured Android SDK, so no local Gradle APK build is claimed.


## v8.1 Verified cumulative fixes

- Added the missing Compose `policyLabel(policy, limitBytesPerSecond)` mapping so `ALLOW`, `BLOCK`, and `THROTTLE` render correctly, with throttled rules displaying their configured rate.
- Configured the CI `release` variant to use the Android debug signing key and disabled minification. This produces an installable CI artifact for testing; it is **not a production signing key** and must not be used for public Play Store releases.
- Added a JNI `poll`/`read` packet-drain helper that discards TUN packets only in BLOCK-only sinkhole mode. This is a sinkhole dropper, **not** a TCP/UDP forwarding engine or a tun2socks implementation. If native loading fails, the helper returns an error and cannot guarantee packet draining.
- Split proxy-forwarded byte counters from sampled kernel UID counters. Per-UID snapshots use the maximum measured rate/total rather than summing potentially duplicated observations. Aggregate notification throughput remains based on proxy-observed bytes.
- GitHub Actions now runs the static audit before building both `assembleDebug` and `assembleRelease`, then uploads both APK artifacts.

Validation performed in this environment: `python3 tools/static_audit.py` reports `RESULT: PASS`; `gcc -fsyntax-only` succeeds for `tun2socks_jni.c` using the installed JDK JNI headers. A full Android Gradle build was not run locally because this workspace has no Gradle wrapper/Android SDK configured; CI must confirm both APK variants compile.
