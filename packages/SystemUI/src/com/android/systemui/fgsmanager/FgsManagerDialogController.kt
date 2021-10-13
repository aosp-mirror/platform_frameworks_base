/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.fgsmanager

import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.UserHandle
import android.util.ArrayMap
import android.util.Log
import androidx.annotation.GuardedBy
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.RunningFgsController
import com.android.systemui.statusbar.policy.RunningFgsController.UserPackageTime
import javax.inject.Inject

/**
 * Controls events relevant to FgsManagerDialog
 */
class FgsManagerDialogController @Inject constructor(
    private val packageManager: PackageManager,
    @Background private val backgroundHandler: Handler,
    private val runningFgsController: RunningFgsController
) : RunningFgsController.Callback {
    private val lock = Any()
    private val clearCacheToken = Any()

    @GuardedBy("lock")
    private var runningApps: Map<UserPackageTime, RunningApp>? = null
    @GuardedBy("lock")
    private var listener: FgsManagerDialogCallback? = null

    interface FgsManagerDialogCallback {
        fun onRunningAppsChanged(apps: List<RunningApp>)
    }

    data class RunningApp(
        val mUserId: Int,
        val mPackageName: String,
        val mAppLabel: CharSequence,
        val mIcon: Drawable,
        val mTimeStarted: Long
    )

    val runningAppList: List<RunningApp>
        get() {
            synchronized(lock) {
                if (runningApps == null) {
                    onFgsPackagesChangedLocked(runningFgsController.getPackagesWithFgs())
                }
                return convertToRunningAppList(runningApps!!)
            }
        }

    fun registerDialogForChanges(callback: FgsManagerDialogCallback) {
        synchronized(lock) {
            runningFgsController.addCallback(this)
            listener = callback
            backgroundHandler.removeCallbacksAndMessages(clearCacheToken)
        }
    }

    fun onFinishDialog() {
        synchronized(lock) {
            listener = null
            // Keep data such as icons cached for some time since loading can be slow
            backgroundHandler.postDelayed(
                    {
                        synchronized(lock) {
                            runningFgsController.removeCallback(this)
                            runningApps = null
                        }
                    }, clearCacheToken, RUNNING_APP_CACHE_TIMEOUT_MILLIS)
        }
    }

    private fun onRunningAppsChanged(apps: ArrayMap<UserPackageTime, RunningApp>) {
        listener?.let {
            backgroundHandler.post { it.onRunningAppsChanged(convertToRunningAppList(apps)) }
        }
    }

    override fun onFgsPackagesChanged(packages: List<UserPackageTime>) {
        backgroundHandler.post {
            synchronized(lock) { onFgsPackagesChangedLocked(packages) }
        }
    }

    /**
     * Run on background thread
     */
    private fun onFgsPackagesChangedLocked(packages: List<UserPackageTime>) {
        val newRunningApps = ArrayMap<UserPackageTime, RunningApp>()
        for (packageWithFgs in packages) {
            val ra = runningApps?.get(packageWithFgs)
            if (ra == null) {
                val userId = packageWithFgs.userId
                val packageName = packageWithFgs.packageName
                try {
                    val ai = packageManager.getApplicationInfo(packageName, 0)
                    var icon = packageManager.getApplicationIcon(ai)
                    icon = packageManager.getUserBadgedIcon(icon,
                            UserHandle.of(userId))
                    val label = packageManager.getApplicationLabel(ai)
                    newRunningApps[packageWithFgs] = RunningApp(userId, packageName,
                            label, icon, packageWithFgs.startTimeMillis)
                } catch (e: NameNotFoundException) {
                    Log.e(LOG_TAG,
                            "Application info not found: $packageName", e)
                }
            } else {
                newRunningApps[packageWithFgs] = ra
            }
        }
        runningApps = newRunningApps
        onRunningAppsChanged(newRunningApps)
    }

    fun stopAllFgs(userId: Int, packageName: String) {
        runningFgsController.stopFgs(userId, packageName)
    }

    companion object {
        private val LOG_TAG = FgsManagerDialogController::class.java.simpleName
        private const val RUNNING_APP_CACHE_TIMEOUT_MILLIS: Long = 20_000

        private fun convertToRunningAppList(apps: Map<UserPackageTime, RunningApp>):
                List<RunningApp> {
            val result = mutableListOf<RunningApp>()
            result.addAll(apps.values)
            result.sortWith { a: RunningApp, b: RunningApp ->
                b.mTimeStarted.compareTo(a.mTimeStarted)
            }
            return result
        }
    }
}
