package com.netlimiterpro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netlimiterpro.R
import com.netlimiterpro.data.repository.RuleRepository
import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy
import com.netlimiterpro.domain.network.ActiveNetworkKind
import com.netlimiterpro.domain.network.NetworkMonitor
import com.netlimiterpro.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Coordinates policy, root enforcement, and the local HTTP proxy backend. */
class NetLimiterVpnService : VpnService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var repository: RuleRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var backend: TrafficEnforcementBackend
    @Volatile private var currentNetwork = ActiveNetworkKind.OFFLINE
    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: LocalThrottledProxyServer? = null
    private val tun2SocksBridge by lazy { Tun2SocksBridge(applicationContext) }
    @Volatile private var sinkholeOnlyMode: Boolean = false
    private var sinkholeDrainJob: Job? = null
    private var notificationManager: NotificationManager? = null
    @Volatile private var cachedRules = emptyList<AppRule>()
    @Volatile private var defaultLimitBytesPerSecond: Long = 0L

    override fun onCreate() {
        super.onCreate()
        repository = RuleRepository(applicationContext)
        networkMonitor = NetworkMonitor(applicationContext)
        backend = TrafficEnforcementBackend(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        isRunning = true
        activeModeLabel = getString(R.string.mode_detecting)
        serviceScope.launch {
            networkMonitor.observe().collect { kind ->
                if (kind != currentNetwork) {
                    currentNetwork = kind
                    reloadRules()
                }
                notificationManager?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                notificationManager?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
        reloadRules()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_RULES -> reloadRules()
            else -> reloadRules()
        }
        return START_STICKY
    }

    fun reloadRules() {
        if (!isRunning) return
        serviceScope.launch {
            runCatching {
                val rules = repository.getAllRules()
                val defaultLimit = repository.getDefaultLimitBytesPerSecond()
                cachedRules = rules
                defaultLimitBytesPerSecond = defaultLimit
                configureEnforcement(rules, defaultLimit)
                notificationManager?.notify(NOTIFICATION_ID, buildNotification())
            }.onFailure {
                activeModeLabel = getString(R.string.mode_unavailable)
                notificationManager?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    /** Root is preferred only when its actual rules were installed successfully. */
    private fun configureEnforcement(rules: List<AppRule>, defaultLimit: Long) {
        val isWifi = currentNetwork == ActiveNetworkKind.WIFI
        val enabledRules = rules.filter { rule ->
            (currentNetwork == ActiveNetworkKind.WIFI || currentNetwork == ActiveNetworkKind.CELLULAR) &&
                rule.policyFor(isWifi) != NetworkPolicy.ALLOW
        }
        if (enabledRules.isEmpty()) {
            backend.clearRoot()
            closeProxyAndVpn()
            activeModeLabel = getString(R.string.mode_no_active_rules)
            return
        }
        val rootCapability = backend.activateRootIfAvailable(enabledRules, currentNetwork)
        if (rootCapability != null) {
            closeProxyAndVpn()
            activeModeLabel = getString(R.string.mode_root_iptables)
            return
        }
        backend.clearRoot()

        runCatching {
            val sinkholeOnly = enabledRules.isNotEmpty() && enabledRules.all {
                it.policyFor(isWifi) == NetworkPolicy.BLOCK
            }

            var proxyPort = -1
            if (!sinkholeOnly) {
                if (proxyServer == null) {
                    val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    proxyServer = LocalThrottledProxyServer(
                        connectivityManager = manager,
                        protectSocket = backend::protect,
                        rulesProvider = { cachedRules },
                        networkProvider = { currentNetwork },
                        defaultLimitProvider = { defaultLimitBytesPerSecond }
                    )
                }
                proxyPort = proxyServer!!.start()
                proxyServer!!.updatePolicy()
            }

            stopSinkholeDrain()
            runCatching { vpnInterface?.close() }
            vpnInterface = null
            sinkholeOnlyMode = false

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.111.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
            runCatching {
                builder.addAddress("fd00:111:111::1", 64)
                builder.addRoute("::", 0)
            }

            var allowedApplicationCount = 0
            enabledRules.forEach { rule ->
                if (runCatching { builder.addAllowedApplication(rule.packageName); true }.getOrDefault(false)) {
                    allowedApplicationCount++
                }
            }
            if (allowedApplicationCount == 0) {
                closeProxyAndVpn()
                activeModeLabel = getString(R.string.mode_no_active_rules)
                return
            }

            if (!sinkholeOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && proxyPort > 0) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))
            }

            val established = builder.establish()
            if (established == null) {
                closeProxyAndVpn()
                activeModeLabel = getString(R.string.mode_unavailable)
            } else {
                vpnInterface = established
                sinkholeOnlyMode = sinkholeOnly
                startSinkholeDrain(established)
                activeModeLabel = if (sinkholeOnly) getString(R.string.mode_sinkhole_block)
                    else getString(R.string.mode_local_proxy)
            }
        }.onFailure {
            closeProxyAndVpn()
            activeModeLabel = getString(R.string.mode_unavailable)
        }
    }

    private fun startSinkholeDrain(tun: ParcelFileDescriptor) {
        sinkholeDrainJob?.cancel()
        sinkholeDrainJob = serviceScope.launch {
            val fallbackBuf = ByteArray(4096)
            var useFallbackStream = false
            var inputStream: java.io.FileInputStream? = null
            while (isActive && vpnInterface === tun) {
                if (!useFallbackStream) {
                    val result = tun2SocksBridge.drainSinkholePacket(tun.fd, timeoutMs = 100)
                    if (result < 0) {
                        useFallbackStream = true
                        inputStream = runCatching { java.io.FileInputStream(tun.fileDescriptor) }.getOrNull()
                    } else if (result == 0) {
                        delay(10L)
                    }
                } else {
                    val bytes = runCatching { inputStream?.read(fallbackBuf) ?: -1 }.getOrDefault(-1)
                    if (bytes <= 0) delay(20L)
                }
            }
            runCatching { inputStream?.close() }
        }
    }

    private fun stopSinkholeDrain() {
        sinkholeOnlyMode = false
        sinkholeDrainJob?.cancel()
        sinkholeDrainJob = null
    }

    private fun closeProxyAndVpn() {
        stopSinkholeDrain()
        runCatching { proxyServer?.close() }
        proxyServer = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    override fun onDestroy() {
        isRunning = false
        activeModeLabel = getString(R.string.mode_inactive)
        closeProxyAndVpn()
        backend.clearRoot()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_description)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ServiceCommandReceiver::class.java).setAction(ACTION_STOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPendingIntent = PendingIntent.getBroadcast(this, 10, stopIntent, pendingFlags)
        val openPendingIntent = PendingIntent.getActivity(
            this, 11, Intent(this, MainActivity::class.java), pendingFlags
        )
        val networkLabel = when (currentNetwork) {
            ActiveNetworkKind.WIFI -> getString(R.string.network_wifi)
            ActiveNetworkKind.CELLULAR -> getString(R.string.network_mobile)
            ActiveNetworkKind.OTHER -> getString(R.string.network_other)
            ActiveNetworkKind.OFFLINE -> getString(R.string.network_offline)
        }
        val rate = getString(R.string.traffic_rate_bytes, TrafficStatsTracker.totalSnapshot().bytesPerSecond)
        val status = getString(R.string.vpn_service_active_traffic, networkLabel, activeModeLabel, rate)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield_speed)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.service_stop), stopPendingIntent)
            .build()
    }

    companion object {
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var activeModeLabel: String = ""
            private set

        const val ACTION_RELOAD_RULES = "com.netlimiterpro.action.RELOAD_RULES"
        const val ACTION_STOP = "com.netlimiterpro.action.STOP"
        private const val CHANNEL_ID = "net_limiter_vpn"
        private const val NOTIFICATION_ID = 4102
    }
}
