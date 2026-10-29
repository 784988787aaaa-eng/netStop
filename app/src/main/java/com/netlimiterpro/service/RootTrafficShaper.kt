package com.netlimiterpro.service

import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy
import com.netlimiterpro.domain.network.ActiveNetworkKind
import java.util.concurrent.TimeUnit

/** Best-effort root enforcement. Kernel capabilities and interface names vary by device. */
class RootTrafficShaper {
    @Volatile private var lastTcInterface: String? = null
    @Volatile private var lastTcRules: List<Pair<Int, Int>> = emptyList() // uid to mark

    data class Result(val success: Boolean, val detail: String)

    fun hasRoot(): Boolean = runRoot("id -u").let {
        it.success && it.output.trim().lineSequence().firstOrNull() == "0"
    }

    fun apply(rules: List<AppRule>, network: ActiveNetworkKind): Result {
        if (!hasRoot()) return Result(false, "Root access unavailable")
        val cleanup = clear()
        if (!cleanup.success) return Result(false, "Could not clear previous NetLimiter rules safely")
        if (network != ActiveNetworkKind.WIFI && network != ActiveNetworkKind.CELLULAR) {
            return Result(false, "No active supported network")
        }
        val active = rules.filter { it.policyFor(network == ActiveNetworkKind.WIFI) != NetworkPolicy.ALLOW }
        val iface = activeInterface(network)
        val script = StringBuilder()
        for (tool in listOf("iptables", "ip6tables")) {
            script.append("$tool -N NETLIMITER_OUT 2>/dev/null || true\n")
            script.append("$tool -N NETLIMITER_IN 2>/dev/null || true\n")
            script.append("$tool -F NETLIMITER_OUT || exit 21\n")
            script.append("$tool -F NETLIMITER_IN || exit 22\n")
            script.append("$tool -C OUTPUT -j NETLIMITER_OUT 2>/dev/null || $tool -I OUTPUT 1 -j NETLIMITER_OUT || exit 23\n")
            script.append("$tool -C INPUT -j NETLIMITER_IN 2>/dev/null || $tool -I INPUT 1 -j NETLIMITER_IN || exit 24\n")
            active.forEach { rule ->
                val uid = rule.uid
                val policy = rule.policyFor(network == ActiveNetworkKind.WIFI)
                if (policy == NetworkPolicy.BLOCK) {
                    script.append("$tool -A NETLIMITER_OUT -m owner --uid-owner $uid -j CONNMARK --set-mark $uid || exit 25\n")
                    script.append("$tool -A NETLIMITER_OUT -m owner --uid-owner $uid -j REJECT || exit 26\n")
                    script.append("$tool -A NETLIMITER_IN -m connmark --mark $uid -j DROP || exit 27\n")
                } else if (policy == NetworkPolicy.THROTTLE) {
                    val bps = rule.limitFor(network == ActiveNetworkKind.WIFI).coerceAtLeast(1L)
                    val kb = ((bps + 999L) / 1000L).coerceAtLeast(1L)
                    val burst = (kb * 2L).coerceAtLeast(2L)
                    script.append("$tool -A NETLIMITER_OUT -m owner --uid-owner $uid -j CONNMARK --set-mark $uid || exit 27\n")
                    script.append("$tool -A NETLIMITER_OUT -m owner --uid-owner $uid -m hashlimit --hashlimit-name nl_out_$uid --hashlimit-mode srcip --hashlimit-above ${kb}kb/s --hashlimit-burst ${burst}kb -j DROP || exit 28\n")
                    script.append("$tool -A NETLIMITER_IN -m connmark --mark $uid -m hashlimit --hashlimit-name nl_in_$uid --hashlimit-mode dstip --hashlimit-above ${kb}kb/s --hashlimit-burst ${burst}kb -j DROP || exit 29\n")
                }
            }
        }
        // Install HTB only if we can create our own root qdisc. Never modify an
        // existing root qdisc; hashlimit remains the fallback on those devices.
        val tcRules = if (iface != null) active.filter {
            it.policyFor(network == ActiveNetworkKind.WIFI) == NetworkPolicy.THROTTLE
        }.mapIndexed { index, rule -> rule.uid to (index + 1) } else emptyList()
        if (iface != null && tcRules.isNotEmpty()) {
            script.append("if tc qdisc add dev $iface root handle 1: htb default 9999 2>/dev/null; then\n")
            script.append("  echo NL_TC_CREATED\n")
            script.append("  if ! tc class add dev $iface parent 1: classid 1:9999 htb rate 1000000kbit ceil 1000000kbit 2>/dev/null; then tc qdisc del dev $iface root 2>/dev/null || true; echo NL_TC_ABORTED; else\n")
            tcRules.forEach { (uid, mark) ->
                val rule = active.first { it.uid == uid }
                val bps = rule.limitFor(network == ActiveNetworkKind.WIFI).coerceAtLeast(1L)
                val kbit = ((bps * 8L + 999L) / 1000L).coerceAtLeast(1L)
                script.append("    iptables -t mangle -A OUTPUT -m owner --uid-owner $uid -j MARK --set-mark $mark 2>/dev/null || true\n")
                script.append("    tc class add dev $iface parent 1: classid 1:$mark htb rate ${kbit}kbit ceil ${kbit}kbit 2>/dev/null || true\n")
                script.append("    tc filter add dev $iface parent 1: protocol ip prio 1 handle $mark fw flowid 1:$mark 2>/dev/null || true\n")
            }
            script.append("  fi\nfi\n")
        }
        val result = runRoot(script.toString())
        val tcReady = result.output.contains("NL_TC_CREATED") && !result.output.contains("NL_TC_ABORTED")
        if (tcReady && iface != null) {
            lastTcInterface = iface
            lastTcRules = tcRules
        }
        return if (result.success) Result(true, "Root policy rules installed; ${if (tcReady) "tc HTB attempted on $iface" else "tc unavailable; hashlimit fallback active"}")
        else Result(false, "Root rules unavailable or rejected (exit ${result.exitCode}): ${result.output.take(240)}")
    }

    fun clear(): Result {
        if (!hasRoot()) return Result(false, "Root access unavailable")
        val script = buildString {
            // Remove exact MARK rules installed by this instance and only its HTB qdisc.
            lastTcRules.forEach { (uid, mark) ->
                append("iptables -t mangle -D OUTPUT -m owner --uid-owner $uid -j MARK --set-mark $mark 2>/dev/null || true\n")
            }
            lastTcInterface?.let { dev -> append("tc qdisc del dev $dev root handle 1: 2>/dev/null || true\n") }
            for (tool in listOf("iptables", "ip6tables")) {
                append("while $tool -D OUTPUT -j NETLIMITER_OUT 2>/dev/null; do :; done\n")
                append("while $tool -D INPUT -j NETLIMITER_IN 2>/dev/null; do :; done\n")
                append("$tool -F NETLIMITER_OUT 2>/dev/null || true\n$tool -F NETLIMITER_IN 2>/dev/null || true\n")
                append("$tool -X NETLIMITER_OUT 2>/dev/null || true\n$tool -X NETLIMITER_IN 2>/dev/null || true\n")
            }
        }
        val result = runRoot(script)
        lastTcRules = emptyList()
        lastTcInterface = null
        return Result(result.success, if (result.success) "Root traffic rules cleared" else "Failed to clear all root rules")
    }

    private fun activeInterface(network: ActiveNetworkKind): String? {
        val result = runRoot("ip route get 1.1.1.1 2>/dev/null")
        val routeIface = Regex("\\bdev\\s+([A-Za-z0-9_.:-]+)").find(result.output)?.groupValues?.getOrNull(1)
        return when (network) {
            ActiveNetworkKind.WIFI -> routeIface?.takeIf { it.startsWith("wlan") } ?: "wlan0"
            ActiveNetworkKind.CELLULAR -> routeIface?.takeIf {
                it.startsWith("rmnet") || it.startsWith("ccmni") || it.startsWith("pdp") || it.startsWith("wwan")
            } ?: "rmnet_data0"
            else -> null
        }
    }

    private data class ShellResult(val success: Boolean, val exitCode: Int, val output: String)
    private fun runRoot(script: String): ShellResult = try {
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); ShellResult(false, -1, "Root command timed out") }
        else { val output = process.inputStream.bufferedReader().use { it.readText() }; val code = process.exitValue(); ShellResult(code == 0, code, output) }
    } catch (exception: Exception) { ShellResult(false, -1, exception.message.orEmpty()) }
}
