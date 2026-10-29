#!/usr/bin/env python3
"""Static pre-build audit for NetLimiterPro; no Android SDK required."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []
warnings = []

def fail(message):
    errors.append(message)

def parse_xml(path):
    try:
        return ET.parse(path).getroot()
    except Exception as exc:
        fail(f"XML parse error: {path.relative_to(ROOT)}: {exc}")
        return None

# XML well-formedness.
xml_files = list(ROOT.rglob("*.xml"))
for path in xml_files:
    parse_xml(path)

# Gather Android resources by resource type/name.
resource_map = {}
for path in (ROOT / "app/src/main/res").rglob("*.xml"):
    parent = path.parent.name
    root = parse_xml(path)
    if root is None:
        continue
    for child in root:
        name = child.attrib.get("name")
        if not name:
            continue
        typ = "string" if child.tag == "string" else "dimen" if child.tag == "dimen" else "color" if child.tag == "color" else None
        if typ:
            resource_map.setdefault((typ, name), set()).add(parent)

# Ensure base and Arabic strings exist for every declared string resource.
base_strings = {name for (typ, name) in resource_map if typ == "string" and "values" in resource_map[(typ, name)]}
arabic_strings = {name for (typ, name) in resource_map if typ == "string" and "values-ar" in resource_map[(typ, name)]}
for name in sorted(base_strings - arabic_strings):
    fail(f"Missing Arabic string resource: {name}")

# Check every explicit R.string/color/dimen reference in Kotlin.
for path in (ROOT / "app/src/main/java").rglob("*.kt"):
    source = path.read_text(encoding="utf-8")
    for typ, name in re.findall(r"R\.(string|color|dimen)\.([A-Za-z_][A-Za-z0-9_]*)", source):
        if (typ, name) not in resource_map:
            fail(f"Missing R.{typ}.{name} referenced by {path.relative_to(ROOT)}")
        elif typ == "string" and not {"values", "values-ar"}.issubset(resource_map[(typ, name)]):
            fail(f"R.string.{name} is not defined in both default and Arabic resources")

# Check manifest @string/@color/@dimen/@drawable/@mipmap references.
manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
manifest = manifest_path.read_text(encoding="utf-8")
for typ, name in re.findall(r"@(?:\+)?(string|color|dimen|drawable|mipmap)/([A-Za-z_][A-Za-z0-9_]*)", manifest):
    if typ in ("string", "color", "dimen") and (typ, name) not in resource_map:
        fail(f"Manifest references missing @{typ}/{name}")
    if typ in ("drawable", "mipmap"):
        found = any((ROOT / f"app/src/main/res/{folder}/{name}.xml").exists()
                    or (ROOT / f"app/src/main/res/{folder}/{name}.png").exists()
                    or (ROOT / f"app/src/main/res/{folder}/{name}.webp").exists()
                    for folder in (f"drawable", f"drawable-nodpi", f"mipmap-hdpi", f"mipmap-mdpi",
                                   f"mipmap-xhdpi", f"mipmap-xxhdpi", f"mipmap-xxxhdpi"))
        if not found:
            fail(f"Manifest references missing @{typ}/{name}")

# Build toolchain checks.
root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
app_build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
workflow = (ROOT / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
kotlin_match = re.search(r'org\.jetbrains\.kotlin\.android"\)\s+version\s+"([^"]+)"', root_build)
compose_match = re.search(r'kotlinCompilerExtensionVersion\s*=\s*"([^"]+)"', app_build)
if not kotlin_match:
    fail("Could not identify Kotlin Gradle plugin version")
if not compose_match:
    fail("Compose compiler extension version is missing")
if kotlin_match and compose_match:
    compatible = {
        "1.9.25": {"1.5.15"},
        "1.9.24": {"1.5.14"},
        "1.9.23": {"1.5.13"},
        "1.9.22": {"1.5.12"},
        "1.9.21": {"1.5.11"},
        "1.9.20": {"1.5.4"},
    }
    if compose_match.group(1) not in compatible.get(kotlin_match.group(1), set()):
        fail(f"Kotlin {kotlin_match.group(1)} / Compose compiler {compose_match.group(1)} compatibility not in audit matrix")
for required in ("actions/checkout@v4", "actions/setup-java@v4", "actions/upload-artifact@v4", ":app:assembleDebug"):
    if required not in workflow:
        fail(f"GitHub Actions workflow missing required entry: {required}")
if "gradle wrapper --gradle-version 8.9" not in workflow:
    fail("Workflow does not generate Gradle wrapper when missing")
if 'java-version: "17"' not in workflow:
    fail("Workflow does not configure JDK 17")

# Check imports are syntactically package-qualified and catch obvious TODO/stub markers.
for path in (ROOT / "app/src/main/java").rglob("*.kt"):
    source = path.read_text(encoding="utf-8")
    for line in source.splitlines():
        if line.strip().startswith("import ") and not re.match(r"import\s+[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+(\.\*)?$", line.strip()):
            fail(f"Malformed import in {path.relative_to(ROOT)}: {line.strip()}")
    if re.search(r"\bTODO\b|NotImplementedError|throw UnsupportedOperationException", source):
        fail(f"TODO/stub marker found in {path.relative_to(ROOT)}")


# Detect common Android/JDK classes referenced by simple name without an import.
# This is intentionally a focused heuristic, not a Kotlin compiler replacement.
known_classes = {
    "Locale": "java.util.Locale",
    "Canvas": "android.graphics.Canvas",
    "Bitmap": "android.graphics.Bitmap",
    "Drawable": "android.graphics.drawable.Drawable",
    "FileInputStream": "java.io.FileInputStream",
    "FileOutputStream": "java.io.FileOutputStream",
    "ByteBuffer": "java.nio.ByteBuffer",
    "InetAddress": "java.net.InetAddress",
    "DatagramSocket": "java.net.DatagramSocket",
    "Socket": "java.net.Socket",
    "ServerSocket": "java.net.ServerSocket",
    "InetSocketAddress": "java.net.InetSocketAddress",
    "ProxyInfo": "android.net.ProxyInfo",
    "ConnectivityManager": "android.net.ConnectivityManager",
    "OsConstants": "android.system.OsConstants",
    "URI": "java.net.URI",
    "BufferedInputStream": "java.io.BufferedInputStream",
    "BufferedOutputStream": "java.io.BufferedOutputStream",
    "ByteArrayOutputStream": "java.io.ByteArrayOutputStream",
    "ExecutorService": "java.util.concurrent.ExecutorService",
    "Executors": "java.util.concurrent.Executors",
    "ConcurrentHashMap": "java.util.concurrent.ConcurrentHashMap",
    "AtomicBoolean": "java.util.concurrent.atomic.AtomicBoolean",
    "PowerManager": "android.os.PowerManager",
    "Settings": "android.provider.Settings",
    "Uri": "android.net.Uri",
    "SocketChannel": "java.nio.channels.SocketChannel",
    "DatagramChannel": "java.nio.channels.DatagramChannel",
    "Inet4Address": "java.net.Inet4Address",
    "AtomicLong": "java.util.concurrent.atomic.AtomicLong",
    "File": "java.io.File",
}
for path in (ROOT / "app/src/main/java").rglob("*.kt"):
    source = path.read_text(encoding="utf-8")
    imports = set(re.findall(r"^import\s+([\w.]+)", source, re.MULTILINE))
    for simple_name, qualified_name in known_classes.items():
        # Only inspect a real identifier occurrence and ignore declarations of a
        # class with the same name; fully-qualified references are accepted.
        if re.search(rf"\b{simple_name}\b", source) and not re.search(rf"\b(class|interface|object|typealias)\s+{simple_name}\b", source):
            if qualified_name not in imports and f"{qualified_name}." not in source:
                fail(f"Possible missing import for {simple_name} ({qualified_name}) in {path.relative_to(ROOT)}")

# Verify compact app-card switches use the Compose scale modifier and its import.
main_activity_source = (ROOT / "app/src/main/java/com/netlimiterpro/ui/MainActivity.kt").read_text(encoding="utf-8")
if "Modifier.scale(0.8f)" not in main_activity_source:
    fail("App-card switches must use Modifier.scale(0.8f)")
if "import androidx.compose.ui.draw.scale" not in main_activity_source:
    fail("MainActivity is missing the Compose scale import")
if "stringResource(R.string.speed_limit)" in main_activity_source:
    fail("Duplicate speed_limit row must be removed from the app card")

# Verify service safety invariants and required foreground-service declarations.
if 'android:name=".service.ServiceCommandReceiver" android:exported="false"' not in manifest:
    fail("ServiceCommandReceiver must be explicitly non-exported")
for permission in ("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.POST_NOTIFICATIONS", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"):
    if f'<uses-permission android:name="{permission}"' not in manifest:
        fail(f"Missing required manifest permission: {permission}")
service_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/NetLimiterVpnService.kt").read_text(encoding="utf-8")
receiver_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/ServiceCommandReceiver.kt").read_text(encoding="utf-8")
workflow_source = (ROOT / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
if 'android:foregroundServiceType="specialUse"' not in manifest:
    fail("VPN service must declare android:foregroundServiceType=\"specialUse\"")
if "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" not in manifest or "vpn_traffic_shaper_and_firewall" not in manifest:
    fail("Manifest must declare the special-use foreground service subtype")
if "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" not in service_source:
    fail("VPN service must call startForeground with FOREGROUND_SERVICE_TYPE_SPECIAL_USE on API 34+")
if "sdkmanager --licenses" not in workflow_source:
    fail("GitHub Actions must accept Android SDK licenses before package installation")
viewmodel_source = (ROOT / "app/src/main/java/com/netlimiterpro/ui/MainViewModel.kt").read_text(encoding="utf-8")
custom_speed_body = viewmodel_source.split("fun applyCustomSpeed(", 1)[-1].split("fun setDefaultSpeed(", 1)[0]
if "parsed == 0.0" not in custom_speed_body or "NetworkPolicy.BLOCK" not in custom_speed_body:
    fail("applyCustomSpeed must map custom speed 0.0 to NetworkPolicy.BLOCK")
proxy_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/LocalThrottledProxyServer.kt").read_text(encoding="utf-8")
blocked_body = proxy_source.split("private fun isBlocked(uid: Int?)", 1)[-1].split("private fun throttlerFor(", 1)[0]
if "uid == null" not in blocked_body or "activeRules.all" not in blocked_body or "NetworkPolicy.BLOCK" not in blocked_body:
    fail("isBlocked(null) must fail closed when all active current-network rules are BLOCK")
if "@Volatile var isRunning" not in service_source:
    fail("VPN service must expose a volatile isRunning state")
if "configureEnforcement(rules, defaultLimit)" not in service_source:
    fail("reloadRules must reconfigure the active enforcement backend")
if "proxyServer!!.updatePolicy()" not in service_source:
    fail("reloadRules must refresh the live proxy policy")
if "if (NetLimiterVpnService.isRunning)" not in receiver_source:
    fail("Rule receiver must not start service when it is stopped")

# v8.3 notification and adaptive launcher icon regression guards.
if "android.R.drawable.stat_sys_warning" in service_source:
    fail("VPN notification must not use the generic system warning icon")
if "R.drawable.ic_stat_shield_speed" not in service_source:
    fail("VPN notification must use the custom ic_stat_shield_speed icon")
small_icon = ROOT / "app/src/main/res/drawable/ic_stat_shield_speed.xml"
if not small_icon.is_file():
    fail("Missing custom monochrome status-bar shield icon")
else:
    small_icon_source = small_icon.read_text(encoding="utf-8")
    if 'android:width="24dp"' not in small_icon_source or 'android:height="24dp"' not in small_icon_source or "#FFFFFFFF" not in small_icon_source:
        fail("Status-bar icon must be 24dp and use opaque white paths")
adaptive_icon_path = ROOT / "app/src/main/res/drawable-anydpi-v26/ic_app_icon.xml"
if not adaptive_icon_path.is_file():
    fail("Missing API 26+ adaptive launcher icon")
else:
    adaptive_icon_source = adaptive_icon_path.read_text(encoding="utf-8")
    for token in ("<adaptive-icon", "@drawable/ic_launcher_background", "@drawable/ic_launcher_foreground", "<monochrome"):
        if token not in adaptive_icon_source:
            fail(f"Adaptive launcher icon missing required element: {token}")
for icon_name in ("ic_launcher_background", "ic_launcher_foreground"):
    if not (ROOT / f"app/src/main/res/drawable/{icon_name}.xml").is_file():
        fail(f"Missing adaptive launcher icon layer: {icon_name}")
if 'android:roundIcon="@drawable/ic_app_icon"' not in manifest:
    fail("Manifest must set android:roundIcon to @drawable/ic_app_icon")

# Ensure v1 and v2 cumulative files remain present.
required_files = [
    ".github/workflows/android-build.yml", "README.md", "settings.gradle.kts",
    "build.gradle.kts", "gradle.properties", "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/netlimiterpro/data/repository/RuleRepository.kt",
    "app/src/main/java/com/netlimiterpro/data/source/InstalledAppsProvider.kt",
    "app/src/main/java/com/netlimiterpro/domain/model/AppRule.kt",
    "app/src/main/java/com/netlimiterpro/domain/network/TokenBucketThrottler.kt",
    "app/src/main/java/com/netlimiterpro/domain/network/NetworkMonitor.kt",
    "app/src/main/java/com/netlimiterpro/service/NetLimiterVpnService.kt",
    "app/src/main/java/com/netlimiterpro/service/PacketShaperEngine.kt",
    "app/src/main/java/com/netlimiterpro/service/TrafficEnforcementBackend.kt",
    "app/src/main/java/com/netlimiterpro/service/LocalThrottledProxyServer.kt",
    "app/src/main/java/com/netlimiterpro/service/RootTrafficShaper.kt",
    "app/src/main/java/com/netlimiterpro/service/Tun2SocksBridge.kt",
    "app/src/main/java/com/netlimiterpro/service/TrafficStatsTracker.kt",
    "app/src/main/cpp/tun2socks_jni.c",
    "app/src/main/cpp/CMakeLists.txt",
    "app/src/main/java/com/netlimiterpro/ui/MainActivity.kt",
    "app/src/main/java/com/netlimiterpro/ui/MainViewModel.kt",
    "app/src/main/java/com/netlimiterpro/ui/MainUiState.kt",
    "app/src/main/res/drawable/ic_app_icon.xml",
    "app/src/main/res/drawable/ic_stat_shield_speed.xml",
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/drawable-anydpi-v26/ic_app_icon.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-ar/strings.xml",
    "app/src/main/res/values/colors.xml",
    "app/src/main/res/values/dimens.xml",
    "app/src/main/res/values/themes.xml",
]
for rel in required_files:
    if not (ROOT / rel).is_file():
        fail(f"Missing required cumulative file: {rel}")

# Verify dual-plane backend artifacts and explicitly avoid claiming arbitrary traffic coverage.
proxy_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/LocalThrottledProxyServer.kt").read_text(encoding="utf-8")
root_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/RootTrafficShaper.kt").read_text(encoding="utf-8")
for token in ("ServerSocket()", "127.0.0.1", "CONNECT", "pumpThrottled", "nanosUntilAvailable(1)", "protectSocket"):
    if token not in proxy_source:
        fail(f"Local proxy implementation missing required element: {token}")
for token in ("iptables", "ip6tables", "NETLIMITER_OUT", "NETLIMITER_IN", "--uid-owner", "-m connmark", "CONNMARK --set-mark", "--hashlimit-above", "fun clear()"):
    if token not in root_source:
        fail(f"Root shaper implementation missing required element: {token}")
if "setHttpProxy(ProxyInfo.buildDirectProxy" not in service_source:
    fail("VPN service does not configure the local HTTP proxy")
if 'builder.addRoute("0.0.0.0", 0)' in service_source and "sinkholeOnly" not in service_source:
    fail("A default route may only be installed for the explicit BLOCK-only sinkhole mode")
main_activity_source = (ROOT / "app/src/main/java/com/netlimiterpro/ui/MainActivity.kt").read_text(encoding="utf-8")
for token in ("isIgnoringBatteryOptimizations(packageName)", "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "R.string.keep_alive"):
    if token not in main_activity_source:
        fail(f"Battery optimization UI missing required element: {token}")
if ("string", "keep_alive") not in resource_map:
    fail("Missing keep_alive string resource")
readme_source = (ROOT / "README.md").read_text(encoding="utf-8")
if "Universal non-root TUN forwarding is not implemented" not in readme_source:
    fail("README must clearly disclose that universal TUN forwarding is not implemented")

# v7 SOCKS5, telemetry, packet checksum, and native-bridge fail-safe checks.
proxy_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/LocalThrottledProxyServer.kt").read_text(encoding="utf-8")
packet_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/PacketShaperEngine.kt").read_text(encoding="utf-8")
tracker_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/TrafficStatsTracker.kt").read_text(encoding="utf-8")
bridge_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/Tun2SocksBridge.kt").read_text(encoding="utf-8")
if "client.remoteSocketAddress as? InetSocketAddress" not in proxy_source:
    fail("findOwnerUid must inspect the accepted socket's remoteSocketAddress first")
if "queryOwnerUid(clientAddress, serverAddress)" not in proxy_source:
    fail("findOwnerUid must query the remote/client address before the reverse tuple")
if "SOCKS5_VERSION = 0x05" not in proxy_source or "handleSocks5" not in proxy_source:
    fail("Local proxy is missing SOCKS5 negotiation/CONNECT handling")
for token in ("SOCKS5_IPV4", "SOCKS5_DOMAIN", "SOCKS5_IPV6", "SOCKS5_CONNECT", "pumpThrottled"):
    if token not in proxy_source:
        fail(f"SOCKS5 implementation missing required support: {token}")
for token in ("fun record(", "totalSnapshot", "bytesPerSecond"):
    if token not in tracker_source:
        fail(f"TrafficStatsTracker missing required telemetry element: {token}")
if "TrafficStatsTracker.recordProxyBytes(uid, allowed.toLong())" not in proxy_source:
    fail("Proxy data pumps must report forwarded bytes through the proxy-only telemetry counter")
for token in ("buildIpv4UdpPacket", "internetChecksum", "udpChecksum", "putU16"):
    if token not in packet_source:
        fail(f"PacketShaperEngine missing IPv4/UDP checksum builder element: {token}")
for token in ('System.loadLibrary("netlimiter_tun2socks")', "nativeStartTun2Socks", "nativeStopTun2Socks", "fullTunnelReady = false"):
    if token not in bridge_source:
        fail(f"Tun2SocksBridge must expose safe JNI bindings and fail safe without an integrated native data plane: {token}")
service_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/NetLimiterVpnService.kt").read_text(encoding="utf-8")
if "TrafficStatsTracker.totalSnapshot().bytesPerSecond" not in service_source or "delay(1_000L)" not in service_source:
    fail("Foreground notification must refresh with live aggregate proxy throughput")
for key in ("proxy_socks5_supported", "tun2socks_native_unavailable", "vpn_service_active_traffic", "traffic_rate_bytes"):
    if ("string", key) not in resource_map or not {"values", "values-ar"}.issubset(resource_map[("string", key)]):
        fail(f"Missing bilingual resource string: {key}")
readme_source = (ROOT / "README.md").read_text(encoding="utf-8")
if "does not claim universal non-root coverage" not in readme_source:
    fail("README must disclose that v7 does not yet provide universal non-root data-plane coverage")

# v8 policies, bulk actions, shaping, block rejection, and per-app live rate checks.
model_source = (ROOT / "app/src/main/java/com/netlimiterpro/domain/model/AppRule.kt").read_text(encoding="utf-8")
viewmodel_source = (ROOT / "app/src/main/java/com/netlimiterpro/ui/MainViewModel.kt").read_text(encoding="utf-8")
root_shaper_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/RootTrafficShaper.kt").read_text(encoding="utf-8")
for policy in ("ALLOW", "THROTTLE", "BLOCK"):
    if policy not in model_source:
        fail(f"NetworkPolicy missing {policy}")
for action in ("blockAllWifi", "allowAllWifi", "blockAllMobile", "allowAllMobile", "throttleAllToDefault", "repository.saveRules(targets)"):
    if action not in viewmodel_source:
        fail(f"MainViewModel missing bulk policy action: {action}")
for token in ("block_all_wifi", "allow_all_wifi", "block_all_mobile", "allow_all_mobile", "throttle_all_default"):
    if f"R.string.{token}" not in main_activity_source:
        fail(f"MainActivity missing bulk-action button resource: {token}")
for token in ("tc qdisc add", "tc class add", "tc filter add", "-j REJECT", "NetworkPolicy.BLOCK", "--hashlimit-above"):
    if token not in root_shaper_source:
        fail(f"RootTrafficShaper missing policy/shaping element: {token}")
if "if (isBlocked(uid))" not in proxy_source or "403 Forbidden" not in proxy_source or "SOCKS5_CONNECTION_NOT_ALLOWED" not in proxy_source:
    fail("Local proxy must reject BLOCK policy before opening an upstream socket")
if "state.liveRatesByUid[row.app.uid]" not in main_activity_source or "TrafficStatsTracker.snapshots()" not in viewmodel_source:
    fail("Per-app live rate must be sourced from TrafficStatsTracker snapshots")
if "addAllowedApplication(rule.packageName)" not in service_source or "sinkholeOnly" not in service_source:
    fail("VPN BLOCK-only sinkhole mode must allowlist the blocked apps and isolate its default route")
cpp_source = (ROOT / "app/src/main/cpp/tun2socks_jni.c").read_text(encoding="utf-8")
if "Java_com_netlimiterpro_service_Tun2SocksBridge_nativeStartTun2Socks" not in cpp_source or "return -1;" not in cpp_source:
    fail("Native JNI boundary must build safely and fail closed until a real engine is linked")
if 'ndkVersion = "27.2.12479018"' not in app_build or "cmake { path = file(\"src/main/cpp/CMakeLists.txt\")" not in app_build:
    fail("Android build must configure the pinned NDK and CMake JNI target")
if 'sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"' not in workflow:
    fail("GitHub Actions must install Android platform, NDK, and CMake packages")


# v8.1 regression guards: policy labels, installable release APK, and native sinkhole drain.
if "private fun policyLabel(" not in main_activity_source:
    fail("MainActivity must define policyLabel to avoid unresolved reference during Kotlin compilation")
for token in ("NetworkPolicy.ALLOW -> stringResource(R.string.policy_allow)",
              "NetworkPolicy.BLOCK -> stringResource(R.string.policy_block)",
              "NetworkPolicy.THROTTLE -> formatLimit(limitBytesPerSecond)"):
    if token not in main_activity_source:
        fail(f"policyLabel is missing expected policy/limit mapping: {token}")
if 'signingConfig = signingConfigs.getByName("debug")' not in app_build or "isMinifyEnabled = false" not in app_build:
    fail("Release build must use the debug signing config and disable minification for CI-installable APK output")
for token in ("python3 tools/static_audit.py", ":app:assembleDebug :app:assembleRelease", "app/build/outputs/apk/release/app-release.apk"):
    if token not in workflow:
        fail(f"GitHub Actions must audit and build/upload both APK variants: {token}")
if "Java_com_netlimiterpro_service_Tun2SocksBridge_nativeDrainSinkholePacket" not in cpp_source:
    fail("Native sinkhole packet drain JNI function is missing")
for token in ("#include <poll.h>", "#include <unistd.h>", "#include <stdint.h>", "poll(&descriptor, 1, timeout_ms)", "uint8_t buf[4096]", "read(tun_fd, buf, sizeof(buf))"):
    if token not in cpp_source:
        fail(f"Native sinkhole drain missing required implementation detail: {token}")
bridge_source = (ROOT / "app/src/main/java/com/netlimiterpro/service/Tun2SocksBridge.kt").read_text(encoding="utf-8")
if "nativeDrainSinkholePacket" not in bridge_source or "drainSinkholePacket(tun.fd" not in service_source:
    fail("VPN service must call the JNI packet-drain helper while in sinkhole-only mode")
for token in ("fun recordProxyBytes(", "systemWindows", "maxOf(proxy.bytesPerSecond, system.bytesPerSecond)"):
    if token not in tracker_source:
        fail(f"TrafficStatsTracker must separate proxy/system samples and avoid double counting: {token}")

print("NetLimiterPro Static Pre-Build Audit")
print(f"XML files parsed: {len(xml_files)}")
print(f"Kotlin source files: {len(list((ROOT/'app/src/main/java').rglob('*.kt')))}")
print(f"Declared resource keys: {len(resource_map)}")
print(f"Kotlin plugin: {kotlin_match.group(1) if kotlin_match else 'UNKNOWN'}")
print(f"Compose compiler: {compose_match.group(1) if compose_match else 'UNKNOWN'}")
print(f"Workflow action versions: checkout@v4 / setup-java@v4 / upload-artifact@v4")
if warnings:
    print("WARNINGS:")
    for item in warnings:
        print(" -", item)
if errors:
    print(f"RESULT: FAIL ({len(errors)} error(s))")
    for item in errors:
        print(" -", item)
    sys.exit(1)
print("RESULT: PASS (static checks only; not a substitute for Gradle/Android SDK build)")
