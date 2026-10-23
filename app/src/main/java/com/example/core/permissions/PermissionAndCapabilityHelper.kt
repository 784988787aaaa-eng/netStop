package com.example.core.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.ControlCapabilityStatus

/**
 * فاحص الصلاحيات وإمكانات التحكم على مستوى نظام أندرويد (القسم السادس والتاسع).
 * يلتزم بمبدأ أقل الصلاحيات ولا يفترض وجود صلاحيات الجذر (Root).
 */
object PermissionAndCapabilityHelper {

    /**
     * يتحقق مما إذا كان المستخدم قد منح موافقة خدمة VPN المحلية.
     * إذا أعادت الدالة Intent غير فارغ فيجب عرضه عبر ActivityResultLauncher.
     */
    fun prepareVpnIntent(context: Context): Intent? {
        return try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            null
        }
    }

    fun isVpnPermissionGranted(context: Context): Boolean {
        return prepareVpnIntent(context) == null
    }

    /**
     * يتحقق من صلاحية PACKAGE_USAGE_STATS لقراءة إحصاءات استهلاك البيانات التاريخية لكل UID.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun createUsageAccessSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * يتحقق من إذن الإشعارات على أندرويد 13+ (API 33+).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * يكشف نوع الشبكة النشطة حاليًا (Wi-Fi مقابل بيانات الهاتف مقابل Ethernet) بدقة عبر NetworkCapabilities.
     */
    fun detectActiveNetworkTransport(context: Context): ActiveNetworkTransport {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ActiveNetworkTransport.UNKNOWN
            val activeNetwork = cm.activeNetwork ?: return ActiveNetworkTransport.DISCONNECTED
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return ActiveNetworkTransport.DISCONNECTED

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ActiveNetworkTransport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ActiveNetworkTransport.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ActiveNetworkTransport.ETHERNET
                else -> ActiveNetworkTransport.WIFI
            }
        } catch (e: Exception) {
            ActiveNetworkTransport.UNKNOWN
        }
    }

    /**
     * يقيّم مستوى إمكانية المراقبة والتحكم الفعلي لتطبيق معين وفق قيود أندرويد:
     * - الحزم ذات UID < 10000 (مثل root/system/radio/dns) لا تمر غالبًا عبر VPN المستخدم أو لا يمكن فصلها بأمان.
     * - الحزم المشتركة في نفس UID (sharedUserId) تتأثر معًا عند الحظر على مستوى UID.
     */
    fun evaluateAppCapability(
        uid: Int,
        isSystemApp: Boolean,
        sharedUidPackageCount: Int,
        isVpnActive: Boolean
    ): ControlCapabilityStatus {
        return when {
            uid < 0 -> ControlCapabilityStatus.UNATTRIBUTED_SOURCE
            uid in 0 until Process.FIRST_APPLICATION_UID -> ControlCapabilityStatus.UNSUPPORTED_ON_DEVICE
            isSystemApp && sharedUidPackageCount > 1 -> ControlCapabilityStatus.LIMITED_CONTROL
            isSystemApp -> if (isVpnActive) ControlCapabilityStatus.LIMITED_CONTROL else ControlCapabilityStatus.MONITORING_AVAILABLE
            isVpnActive -> ControlCapabilityStatus.CONTROL_AVAILABLE
            else -> ControlCapabilityStatus.MONITORING_AVAILABLE
        }
    }

    fun isSystemApplication(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    }
}
