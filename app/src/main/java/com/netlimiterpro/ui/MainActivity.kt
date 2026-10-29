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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.color_background))
            .padding(dimensionResource(R.dimen.space_small))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_small))
        ) {
            TextButton(onClick = { onVpnToggle(!state.vpnRunning) }) {
                Text(
                    if (state.vpnRunning) stringResource(R.string.service_stop)
                    else stringResource(R.string.service_start),
                    fontSize = dimensionResource(R.dimen.text_size_body).value.sp
                )
            }
            if (showKeepAlive) {
                TextButton(onClick = onKeepAlive) {
                    Text(stringResource(R.string.keep_alive), fontSize = dimensionResource(R.dimen.text_size_small).value.sp)
                }
            }
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.search_apps)) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_small)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.filter == AppFilter.ALL,
                onClick = { onFilter(AppFilter.ALL) },
                label = { Text(stringResource(R.string.filter_all)) }
            )
            FilterChip(
                selected = state.filter == AppFilter.USER,
                onClick = { onFilter(AppFilter.USER) },
                label = { Text(stringResource(R.string.filter_user)) }
            )
            FilterChip(
                selected = state.filter == AppFilter.SYSTEM,
                onClick = { onFilter(AppFilter.SYSTEM) },
                label = { Text(stringResource(R.string.filter_system)) }
            )
        }
        DefaultSpeedRow(
            currentBytesPerSecond = state.defaultLimitBytesPerSecond,
            onApply = onDefaultSpeed
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBlockWifiAll) { Text(stringResource(R.string.block_all_wifi)) }
                TextButton(onClick = onAllowWifiAll) { Text(stringResource(R.string.allow_all_wifi)) }
                TextButton(onClick = onBlockMobileAll) { Text(stringResource(R.string.block_all_mobile)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAllowMobileAll) { Text(stringResource(R.string.allow_all_mobile)) }
                TextButton(onClick = onThrottleAll) { Text(stringResource(R.string.throttle_all_default)) }
            }
        }
        Text(
            text = stringResource(
                R.string.active_mode_label,
                state.activeModeLabel.ifBlank { stringResource(R.string.mode_inactive) }
            ),
            modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_xsmall)),
            fontSize = dimensionResource(R.dimen.text_size_small).value.sp
        )
        if (state.isLoading) {
            Text(stringResource(R.string.loading_apps), modifier = Modifier.padding(dimensionResource(R.dimen.space_medium)))
        } else if (state.visibleApps.isEmpty()) {
            Text(stringResource(R.string.no_apps), modifier = Modifier.padding(dimensionResource(R.dimen.space_medium)))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xsmall))
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
private fun DefaultSpeedRow(currentBytesPerSecond: Long, onApply: (Double) -> Unit) {
    var input by remember(currentBytesPerSecond) {
        mutableStateOf(
            String.format(
                Locale.ROOT,
                "%.0f",
                TokenBucketThrottler.bytesPerSecondToKilobytes(currentBytesPerSecond)
            )
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_small))
    ) {
        Text(stringResource(R.string.default_limit), fontSize = dimensionResource(R.dimen.text_size_small).value.sp)
        OutlinedTextField(
            value = input,
            onValueChange = { candidate ->
                if (candidate.all { it.isDigit() || it == '.' || it == ',' }) input = candidate
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            suffix = { Text(stringResource(R.string.unit_kb_per_second)) }
        )
        TextButton(onClick = {
            input.trim().replace(',', '.').toDoubleOrNull()?.let(onApply)
        }) {
            Text(stringResource(R.string.apply))
        }
    }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(R.color.color_surface), RoundedCornerShape(dimensionResource(R.dimen.corner_radius)))
            .clickable(onClick = onExpand)
            .padding(horizontal = dimensionResource(R.dimen.space_small), vertical = dimensionResource(R.dimen.space_xsmall))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.row_min_height)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_small))
        ) {
            Image(
                bitmap = row.app.icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_size))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(row.app.label, fontSize = dimensionResource(R.dimen.text_size_body).value.sp, maxLines = 1)
                Text(
                    "${stringResource(R.string.uid)} ${row.app.uid} · ${row.app.packageName}",
                    fontSize = dimensionResource(R.dimen.text_size_small).value.sp,
                    color = colorResource(R.color.color_text_secondary),
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.wifi), fontSize = dimensionResource(R.dimen.text_size_small).value.sp)
                Switch(checked = row.rule.wifiPolicy != NetworkPolicy.ALLOW, onCheckedChange = onToggleWifi, modifier = Modifier.scale(0.8f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.mobile_data), fontSize = dimensionResource(R.dimen.text_size_small).value.sp)
                Switch(checked = row.rule.mobilePolicy != NetworkPolicy.ALLOW, onCheckedChange = onToggleMobile, modifier = Modifier.scale(0.8f))
            }
        }
        Text(
            text = "${stringResource(R.string.wifi)}: ${policyLabel(row.rule.wifiPolicy, row.rule.wifiLimitBytesPerSecond)} · " +
                "${stringResource(R.string.mobile_data)}: ${policyLabel(row.rule.mobilePolicy, row.rule.mobileDataLimitBytesPerSecond)} · " +
                "${stringResource(R.string.live_now)}: ${formatLiveRate(liveRate)}",
            fontSize = dimensionResource(R.dimen.text_size_small).value.sp,
            color = colorResource(R.color.color_text_secondary),
            maxLines = 1
        )
        if (row.expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = row.targetNetwork == RuleNetworkTarget.BOTH, onClick = { onTargetNetwork(RuleNetworkTarget.BOTH) }, label = { Text(stringResource(R.string.network_both)) })
                FilterChip(selected = row.targetNetwork == RuleNetworkTarget.WIFI, onClick = { onTargetNetwork(RuleNetworkTarget.WIFI) }, label = { Text(stringResource(R.string.wifi)) })
                FilterChip(selected = row.targetNetwork == RuleNetworkTarget.MOBILE, onClick = { onTargetNetwork(RuleNetworkTarget.MOBILE) }, label = { Text(stringResource(R.string.mobile_data)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSetPolicy(row.targetNetwork, NetworkPolicy.BLOCK) }) { Text(stringResource(R.string.policy_block)) }
                TextButton(onClick = { onSetPolicy(row.targetNetwork, NetworkPolicy.ALLOW) }) { Text(stringResource(R.string.policy_allow)) }
                TextButton(onClick = { onSetPolicy(row.targetNetwork, NetworkPolicy.THROTTLE) }) { Text(stringResource(R.string.policy_throttle)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xsmall)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(100L, 250L, 500L, 1000L).forEach { speed ->
                    TextButton(onClick = { onQuickSpeed(speed) }) {
                        Text(
                            when (speed) {
                                1000L -> stringResource(R.string.speed_1m)
                                else -> stringResource(R.string.speed_k, speed)
                            },
                            fontSize = dimensionResource(R.dimen.text_size_small).value.sp
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_small)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = row.customSpeedInput,
                    onValueChange = onSpeedInput,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(stringResource(R.string.custom_speed)) },
                    suffix = { Text(stringResource(R.string.unit_kb_per_second)) }
                )
                Button(onClick = onApplySpeed) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

private fun Drawable.toBitmap(): Bitmap {
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
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
