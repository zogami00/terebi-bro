package com.terebibro.tv.device

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import com.terebibro.tv.BuildConfig

/** Read-only diagnostics for the TV hardware, app and WebView provider. */
class DeviceInfo(private val context: Context) {

    data class WebViewInfo(val provider: String, val version: String)

    val androidVersion: String get() = Build.VERSION.RELEASE
    val sdkInt: Int get() = Build.VERSION.SDK_INT
    val appVersion: String get() = BuildConfig.VERSION_NAME

    /** Uptime of the app process in milliseconds. */
    val uptimeMs: Long get() = SystemClock.elapsedRealtime() - appStartElapsed

    fun webViewInfo(): WebViewInfo {
        val pkg: PackageInfo? = try {
            WebView.getCurrentWebViewPackage()
        } catch (e: Exception) {
            null
        }
        if (pkg == null) return WebViewInfo("unknown", "unknown")
        return WebViewInfo(pkg.packageName, pkg.versionName ?: "unknown")
    }

    companion object {
        @Volatile
        var appStartElapsed: Long = SystemClock.elapsedRealtime()
    }
}
