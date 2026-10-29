package com.netlimiterpro.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.util.Locale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netlimiterpro.R
import com.netlimiterpro.data.source.InstalledApp
import com.netlimiterpro.domain.network.TokenBucketThrottler
import com.netlimiterpro.domain.model.NetworkPolicy
import com.netlimiterpro.service.NetLimiterVpnService

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private var batteryOptimizationExempt by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        refreshBatteryOptimizationState()
    }

    private fun refreshBatteryOptimizationState() {
        batteryOptimizationExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(PowerManager::class.java)
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        } else true
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryOptimizationExempt) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, NetLimiterVpnService::class.java))
                mainViewModel.setVpnRunning(true)
            } else {
                Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
                mainViewModel.setVpnRunning(false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by mainViewModel.uiState.collectAsState()
                MainScreen(
                    state = state,
                    onSearch = mainViewModel::setSearchQuery,
                    onFilter = mainViewModel::setFilter,
                    onVpnToggle = { enabled ->
                        if (enabled) {
                            val prepareIntent = VpnService.prepare(this)
                            if (prepareIntent == null) {
                                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, NetLimiterVpnService::class.java))
                                mainViewModel.setVpnRunning(true)
                            } else {
                                vpnPermissionLauncher.launch(prepareIntent)
                            }
                        } else {
                            stopService(Intent(this, NetLimiterVpnService::class.java))
                            mainViewModel.setVpnRunning(false)
                        }
                    },
                    onDefaultSpeed = mainViewModel::setDefaultSpeed,
                    onToggleWifi = mainViewModel::setWifiEnabled,
                    onToggleMobile = mainViewModel::setMobileDataEnabled,
                    onExpand = mainViewModel::toggleExpanded,
                    onSpeedInput = mainViewModel::setCustomSpeedInput,
                    onApplySpeed = mainViewModel::applyCustomSpeed,
                    onQuickSpeed = mainViewModel::chooseQuickSpeed,
                    onTargetNetwork = mainViewModel::setTargetNetwork,
                    onSetPolicy = mainViewModel::setPolicy,
                    onBlockWifiAll = mainViewModel::blockAllWifi,
                    onAllowWifiAll = mainViewModel::allowAllWifi,
                    onBlockMobileAll = mainViewModel::blockAllMobile,
                    onAllowMobileAll = mainViewModel::allowAllMobile,
                    onThrottleAll = mainViewModel::throttleAllToDefault,
                    showKeepAlive = !batteryOptimizationExempt,
                    onKeepAlive = ::requestBatteryOptimizationExemption
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: MainUiState,
    onSearch: (String) -> Unit,
    onFilter: (AppFilter) -> Unit,
    onVpnToggle: (Boolean) -> Unit,
    onDefaultSpeed: (Double) -> Unit,
    onToggleWifi: (Int, String, Boolean) -> Unit,
    onToggleMobile: (Int, String, Boolean) -> Unit,
    onExpand: (Int, String) -> Unit,
    onSpeedInput: (Int, String, String) -> Unit,
    onApplySpeed: (Int, String) -> Unit,
    onQuickSpeed: (Int, String, Long) -> Unit,
    onTargetNetwork: (Int, String, RuleNetworkTarget) -> Unit,
    onSetPolicy: (Int, String, RuleNetworkTarget, NetworkPolicy) -> Unit,
    onBlockWifiAll: () -> Unit,
    onAllowWifiAll: () -> Unit,
    onBlockMobileAll: () -> Unit,
    onAllowMobileAll: () -> Unit,
    onThrottleAll: () -> Unit,
    showKeepAlive: Boolean,
    onKeepAlive: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDefaultSpeedDialog by remember { mutableStateOf(false) }

    if (showDefaultSpeedDialog) {
        DefaultSpeedDialog(
            currentBytesPerSecond = state.defaultLimitBytesPerSecond,
            onDismiss = { showDefaultSpeedDialog = false },
            onApply = {
                onDefaultSpeed(it)
                showDefaultSpeedDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.color_background))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Row 1: Compact Top Bar (Service Switch/Button + Search + Overflow Menu)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { onVpnToggle(!state.vpnRunning) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(38.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (state.vpnRunning) colorResource(R.color.color_accent)
                    else colorResource(R.color.color_primary)
                )
            ) {
                Text(
                    text = if (state.vpnRunning) stringResource(R.string.service_stop)
                    else stringResource(R.string.service_start),
                    fontSize = 13.sp
                )
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_apps), fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )

            androidx.compose.foundation.layout.Box {
                androidx.compose.material3.IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Text("⋮", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }

                androidx.compose.material3.DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.default_limit)) },
                        onClick = {
                            showMenu = false
                            showDefaultSpeedDialog = true
                        }
                    )
                    androidx.compose.material3.HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.block_all_wifi)) },
                        onClick = {
                            showMenu = false
                            onBlockWifiAll()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.allow_all_wifi)) },
                        onClick = {
                            showMenu = false
                            onAllowWifiAll()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.block_all_mobile)) },
                        onClick = {
                            showMenu = false
                            onBlockMobileAll()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.allow_all_mobile)) },
                        onClick = {
                            showMenu = false
                            onAllowMobileAll()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.throttle_all_default)) },
                        onClick = {
                            showMenu = false
                            onThrottleAll()
                        }
                    )
                    if (showKeepAlive) {
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.keep_alive)) },
                            onClick = {
                                showMenu = false
                                onKeepAlive()
                            }
                        )
                    }
                }
            }
        }

        // Row 2: Filter Chips + Compact Mode & Traffic Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = state.filter == AppFilter.ALL,
                onClick = { onFilter(AppFilter.ALL) },
                label = { Text(stringResource(R.string.filter_all), fontSize = 11.sp) },
                modifier = Modifier.scale(0.88f)
            )
            FilterChip(
                selected = state.filter == AppFilter.USER,
                onClick = { onFilter(AppFilter.USER) },
                label = { Text(stringResource(R.string.filter_user), fontSize = 11.sp) },
                modifier = Modifier.scale(0.88f)
            )
            FilterChip(
                selected = state.filter == AppFilter.SYSTEM,
                onClick = { onFilter(AppFilter.SYSTEM) },
                label = { Text(stringResource(R.string.filter_system), fontSize = 11.sp) },
                modifier = Modifier.scale(0.88f)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${state.activeModeLabel.ifBlank { stringResource(R.string.mode_inactive) }} · ${formatLiveRate(state.liveRatesByUid.values.sum())}",
                fontSize = 11.sp,
                color = colorResource(R.color.color_text_secondary),
                maxLines = 1
            )
        }

        if (state.isLoading) {
            Text(
                stringResource(R.string.loading_apps),
                modifier = Modifier.padding(16.dp),
                fontSize = 13.sp
            )
        } else if (state.visibleApps.isEmpty()) {
            Text(
                stringResource(R.string.no_apps),
                modifier = Modifier.padding(16.dp),
                fontSize = 13.sp
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = state.visibleApps,
                key = { "${it.app.uid}:${it.app.packageName}" }
            ) { row ->
                AppRow(
                    row = row,
                    onToggleWifi = { onToggleWifi(row.app.uid, row.app.packageName, it) },
                    onToggleMobile = { onToggleMobile(row.app.uid, row.app.packageName, it) },
                    onExpand = { onExpand(row.app.uid, row.app.packageName) },
                    onSpeedInput = { onSpeedInput(row.app.uid, row.app.packageName, it) },
                    onApplySpeed = { onApplySpeed(row.app.uid, row.app.packageName) },
                    onQuickSpeed = { onQuickSpeed(row.app.uid, row.app.packageName, it) },
                    liveRate = state.liveRatesByUid[row.app.uid] ?: 0L,
                    onTargetNetwork = { onTargetNetwork(row.app.uid, row.app.packageName, it) },
                    onSetPolicy = { target, policy -> onSetPolicy(row.app.uid, row.app.packageName, target, policy) }
                )
            }
        }
    }
}

@Composable
private fun DefaultSpeedDialog(
    currentBytesPerSecond: Long,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit
) {
    var input by remember(currentBytesPerSecond) {
        mutableStateOf(
            String.format(
                Locale.ROOT,
                "%.0f",
                TokenBucketThrottler.bytesPerSecondToKilobytes(currentBytesPerSecond)
            )
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.default_limit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.custom_speed), fontSize = 12.sp)
                OutlinedTextField(
                    value = input,
                    onValueChange = { candidate ->
                        if (candidate.all { it.isDigit() || it == '.' || it == ',' }) input = candidate
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    suffix = { Text(stringResource(R.string.unit_kb_per_second)) }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                input.trim().replace(',', '.').toDoubleOrNull()?.let(onApply)
            }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AppRow(
    row: AppRowState,
    onToggleWifi: (Boolean) -> Unit,
    onToggleMobile: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onSpeedInput: (String) -> Unit,
    onApplySpeed: () -> Unit,
    onQuickSpeed: (Long) -> Unit,
    liveRate: Long,
    onTargetNetwork: (RuleNetworkTarget) -> Unit,
    onSetPolicy: (RuleNetworkTarget, NetworkPolicy) -> Unit
) {
    val imageBitmap = remember(row.app.packageName) { row.app.iconBitmap.asImageBitmap() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(R.color.color_surface), RoundedCornerShape(8.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.size(38.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.app.label,
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = "${stringResource(R.string.uid)} ${row.app.uid} · ${row.app.packageName}",
                    fontSize = 11.sp,
                    color = colorResource(R.color.color_text_secondary),
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.wifi), fontSize = 10.sp)
                Switch(
                    checked = row.rule.wifiPolicy != NetworkPolicy.ALLOW,
                    onCheckedChange = onToggleWifi,
                    modifier = Modifier.scale(0.8f)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mobile_data), fontSize = 10.sp)
                Switch(
                    checked = row.rule.mobilePolicy != NetworkPolicy.ALLOW,
                    onCheckedChange = onToggleMobile,
                    modifier = Modifier.scale(0.8f)
                )
            }
        }
        Text(
            text = "${stringResource(R.string.wifi)}: ${policyLabel(row.rule.wifiPolicy, row.rule.wifiLimitBytesPerSecond)} · " +
                "${stringResource(R.string.mobile_data)}: ${policyLabel(row.rule.mobilePolicy, row.rule.mobileDataLimitBytesPerSecond)} · " +
                "${stringResource(R.string.live_now)}: ${formatLiveRate(liveRate)}",
            fontSize = 11.sp,
            color = colorResource(R.color.color_text_secondary),
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (row.expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = row.targetNetwork == RuleNetworkTarget.BOTH,
                    onClick = { onTargetNetwork(RuleNetworkTarget.BOTH) },
                    label = { Text(stringResource(R.string.network_both), fontSize = 11.sp) }
                )
                FilterChip(
                    selected = row.targetNetwork == RuleNetworkTarget.WIFI,
                    onClick = { onTargetNetwork(RuleNetworkTarget.WIFI) },
                    label = { Text(stringResource(R.string.wifi), fontSize = 11.sp) }
                )
                FilterChip(
                    selected = row.targetNetwork == RuleNetworkTarget.MOBILE,
                    onClick = { onTargetNetwork(RuleNetworkTarget.MOBILE) },
                    label = { Text(stringResource(R.string.mobile_data), fontSize = 11.sp) }
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onSetPolicy(row.targetNetwork, NetworkPolicy.BLOCK) }) {
                    Text(stringResource(R.string.policy_block), color = colorResource(R.color.color_error))
                }
                TextButton(onClick = { onSetPolicy(row.targetNetwork, NetworkPolicy.THROTTLE) }) {
                    Text(stringResource(R.string.policy_throttle))
                }
                TextButton(onClick = { onSetPolicy(row.targetNetwork, NetworkPolicy.ALLOW) }) {
                    Text(stringResource(R.string.policy_allow))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(100L, 250L, 500L, 1000L).forEach { speed ->
                    TextButton(onClick = { onQuickSpeed(speed) }) {
                        Text(
                            when (speed) {
                                1000L -> stringResource(R.string.speed_1m)
                                else -> stringResource(R.string.speed_k, speed)
                            },
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = row.customSpeedInput,
                    onValueChange = onSpeedInput,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(stringResource(R.string.custom_speed), fontSize = 11.sp) },
                    suffix = { Text(stringResource(R.string.unit_kb_per_second), fontSize = 11.sp) }
                )
                Button(onClick = onApplySpeed) {
                    Text(stringResource(R.string.apply), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun policyLabel(policy: NetworkPolicy, limitBytesPerSecond: Long): String = when (policy) {
    NetworkPolicy.ALLOW -> stringResource(R.string.policy_allow)
    NetworkPolicy.BLOCK -> stringResource(R.string.policy_block)
    NetworkPolicy.THROTTLE -> formatLimit(limitBytesPerSecond)
}

@Composable
private fun formatLimit(bytesPerSecond: Long): String {
    val kb = TokenBucketThrottler.bytesPerSecondToKilobytes(bytesPerSecond)
    return if (bytesPerSecond >= 1_000_000L) {
        stringResource(R.string.speed_value_mb, TokenBucketThrottler.bytesPerSecondToMegabytes(bytesPerSecond).toFloat())
    } else {
        stringResource(R.string.speed_value_kb, kb.toFloat())
    }
}

@Composable
private fun formatLiveRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000L -> stringResource(R.string.speed_value_mb, bytesPerSecond / 1_000_000.0)
    else -> stringResource(R.string.speed_value_kb, bytesPerSecond / 1_000.0)
}
