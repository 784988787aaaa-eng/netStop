package com.netlimiterpro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            NetLimiterVpnService.ACTION_STOP -> {
                // Stopping an absent service is safe and never starts it.
                context.stopService(Intent(context, NetLimiterVpnService::class.java))
            }
            NetLimiterVpnService.ACTION_RELOAD_RULES -> {
                // Do not accidentally launch a foreground service for a rule edit
                // when the user has not enabled the VPN.
                if (NetLimiterVpnService.isRunning) {
                    context.startService(
                        Intent(context, NetLimiterVpnService::class.java)
                            .setAction(NetLimiterVpnService.ACTION_RELOAD_RULES)
                    )
                }
            }
        }
    }
}
