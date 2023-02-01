/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.UserInfo
import android.os.UserHandle
import com.android.internal.annotations.GuardedBy
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Monitors privacy items backed by app ops:
 * - Mic & Camera
 * - Location
 *
 * If [PrivacyConfig.micCameraAvailable] / [PrivacyConfig.locationAvailable] are disabled,
 * the corresponding PrivacyItems will not be reported.
 */
@SysUISingleton
class AppOpsPrivacyItemMonitor @Inject constructor(
    private val appOpsController: AppOpsController,
    private val userTracker: UserTracker,
    private val privacyConfig: PrivacyConfig,
    @Background private val bgExecutor: DelayableExecutor,
    private val logger: PrivacyLogger
) : PrivacyItemMonitor {

    @VisibleForTesting
    companion object {
        val OPS_MIC_CAMERA = intArrayOf(AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_PHONE_CALL_CAMERA, AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_PHONE_CALL_MICROPHONE,
                AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO)
        val OPS_LOCATION = intArrayOf(
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION)
        val OPS = OPS_MIC_CAMERA + OPS_LOCATION
        val USER_INDEPENDENT_OPS = intArrayOf(AppOpsManager.OP_PHONE_CALL_CAMERA,
                AppOpsManager.OP_PHONE_CALL_MICROPHONE)
    }

    private val lock = Any()

    @GuardedBy("lock")
    private var callback: PrivacyItemMonitor.Callback? = null
    @GuardedBy("lock")
    private var micCameraAvailable = privacyConfig.micCameraAvailable
    @GuardedBy("lock")
    private var locationAvailable = privacyConfig.locationAvailable
    @GuardedBy("lock")
    private var listening = false

    private val appOpsCallback = object : AppOpsController.Callback {
        override fun onActiveStateChanged(
            code: Int,
            uid: Int,
            packageName: String,
            active: Boolean
        ) {
            synchronized(lock) {
                // Check if we care about this code right now
                if (code in OPS_MIC_CAMERA && !micCameraAvailable) {
                    return
                }
                if (code in OPS_LOCATION && !locationAvailable) {
                    return
                }
                if (userTracker.userProfiles.any { it.id == UserHandle.getUserId(uid) } ||
                        code in USER_INDEPENDENT_OPS) {
                    logger.logUpdatedItemFromAppOps(code, uid, packageName, active)
                    dispatchOnPrivacyItemsChanged()
                }
            }
        }
    }

    @VisibleForTesting
    internal val userTrackerCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            onCurrentProfilesChanged()
        }

        override fun onProfilesChanged(profiles: List<UserInfo>) {
            onCurrentProfilesChanged()
        }
    }

    private val configCallback = object : PrivacyConfig.Callback {
        override fun onFlagLocationChanged(flag: Boolean) {
            onFlagChanged()
        }

        override fun onFlagMicCameraChanged(flag: Boolean) {
            onFlagChanged()
        }

        private fun onFlagChanged() {
            synchronized(lock) {
                micCameraAvailable = privacyConfig.micCameraAvailable
                locationAvailable = privacyConfig.locationAvailable
                setListeningStateLocked()
            }
            dispatchOnPrivacyItemsChanged()
        }
    }

    init {
        privacyConfig.addCallback(configCallback)
    }

    override fun startListening(callback: PrivacyItemMonitor.Callback) {
        synchronized(lock) {
            this.callback = callback
            setListeningStateLocked()
        }
    }

    override fun stopListening() {
        synchronized(lock) {
            this.callback = null
            setListeningStateLocked()
        }
    }

    /**
     * Updates listening status based on whether there are callbacks and the indicators are enabled.
     *
     * Always listen to all OPS so we don't have to figure out what we should be listening to. We
     * still have to filter anyway. Updates are filtered in the callback.
     *
     * This is only called from private (add/remove)Callback and from the config listener, all in
     * main thread.
     */
    @GuardedBy("lock")
    private fun setListeningStateLocked() {
        val shouldListen = callback != null && (micCameraAvailable || locationAvailable)
        if (listening == shouldListen) {
            return
        }

        listening = shouldListen
        if (shouldListen) {
            appOpsController.addCallback(OPS, appOpsCallback)
            userTracker.addCallback(userTrackerCallback, bgExecutor)
            onCurrentProfilesChanged()
        } else {
            appOpsController.removeCallback(OPS, appOpsCallback)
            userTracker.removeCallback(userTrackerCallback)
        }
    }

    override fun getActivePrivacyItems(): List<PrivacyItem> {
        val activeAppOps = appOpsController.getActiveAppOps(true)
        val currentUserProfiles = userTracker.userProfiles

        return synchronized(lock) {
            activeAppOps.filter {
                currentUserProfiles.any { user -> user.id == UserHandle.getUserId(it.uid) } ||
                        it.code in USER_INDEPENDENT_OPS
            }.mapNotNull { toPrivacyItemLocked(it) }
        }.distinct()
    }

    @GuardedBy("lock")
    private fun privacyItemForAppOpEnabledLocked(code: Int): Boolean {
        if (code in OPS_LOCATION) {
            return locationAvailable
        } else if (code in OPS_MIC_CAMERA) {
            return micCameraAvailable
        } else {
            return false
        }
    }

    @GuardedBy("lock")
    private fun toPrivacyItemLocked(appOpItem: AppOpItem): PrivacyItem? {
        if (!privacyItemForAppOpEnabledLocked(appOpItem.code)) {
            return null
        }
        val type: PrivacyType = when (appOpItem.code) {
            AppOpsManager.OP_PHONE_CALL_CAMERA,
            AppOpsManager.OP_CAMERA -> PrivacyType.TYPE_CAMERA
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_PHONE_CALL_MICROPHONE,
            AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO,
            AppOpsManager.OP_RECORD_AUDIO -> PrivacyType.TYPE_MICROPHONE
            else -> return null
        }
        val app = PrivacyApplication(appOpItem.packageName, appOpItem.uid)
        return PrivacyItem(type, app, appOpItem.timeStartedElapsed, appOpItem.isDisabled)
    }

    private fun onCurrentProfilesChanged() {
        val currentUserIds = userTracker.userProfiles.map { it.id }
        logger.logCurrentProfilesChanged(currentUserIds)
        dispatchOnPrivacyItemsChanged()
    }

    private fun dispatchOnPrivacyItemsChanged() {
        val cb = synchronized(lock) { callback }
        if (cb != null) {
            bgExecutor.execute {
                cb.onPrivacyItemsChanged()
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("AppOpsPrivacyItemMonitor:")
        ipw.withIncreasedIndent {
            synchronized(lock) {
                ipw.println("Listening: $listening")
                ipw.println("micCameraAvailable: $micCameraAvailable")
                ipw.println("locationAvailable: $locationAvailable")
                ipw.println("Callback: $callback")
            }
            ipw.println("Current user ids: ${userTracker.userProfiles.map { it.id }}")
        }
        ipw.flush()
    }
}
