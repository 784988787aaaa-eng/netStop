package com.netlimiterpro.data.source

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class InstalledAppKind {
    USER,
    SYSTEM
}

data class InstalledApp(
    val label: String,
    val packageName: String,
    val uid: Int,
    val kind: InstalledAppKind,
    val icon: Drawable
)

class InstalledAppsProvider(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        applications.asSequence()
            .filter { it.packageName != appContext.packageName }
            .filter(::hasInternetPermission)
            .mapNotNull { info ->
                try {
                    InstalledApp(
                        label = packageManager.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        uid = info.uid,
                        kind = if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                            InstalledAppKind.SYSTEM
                        } else {
                            InstalledAppKind.USER
                        },
                        icon = packageManager.getApplicationIcon(info)
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            }
            .sortedWith(compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName })
            .toList()
    }

    private fun hasInternetPermission(info: ApplicationInfo): Boolean {
        return try {
            packageManager.getPackageInfoCompat(info.packageName)
                .requestedPermissions
                ?.contains(android.Manifest.permission.INTERNET) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageInfoCompat(packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
}
