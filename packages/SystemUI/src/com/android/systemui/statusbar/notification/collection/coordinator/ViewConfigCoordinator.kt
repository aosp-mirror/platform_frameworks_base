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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.internal.widget.MessagingGroup
import com.android.internal.widget.MessagingMessage
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.row.NotificationGutsManager
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

/**
 * A coordinator which ensures that notifications within the new pipeline are correctly inflated
 * for the current uiMode and screen properties; additionally deferring those changes when a user
 * change is in progress until that process has completed.
 */
@CoordinatorScope
class ViewConfigCoordinator @Inject internal constructor(
    private val mConfigurationController: ConfigurationController,
    private val mLockscreenUserManager: NotificationLockscreenUserManager,
    private val mGutsManager: NotificationGutsManager,
    private val mKeyguardUpdateMonitor: KeyguardUpdateMonitor
) : Coordinator, UserChangedListener, ConfigurationController.ConfigurationListener {

    private var mReinflateNotificationsOnUserSwitched = false
    private var mDispatchUiModeChangeOnUserSwitched = false
    private var mPipeline: NotifPipeline? = null

    override fun attach(pipeline: NotifPipeline) {
        mPipeline = pipeline
        if (pipeline.isNewPipelineEnabled) {
            mLockscreenUserManager.addUserChangedListener(this)
            mConfigurationController.addCallback(this)
        }
    }

    override fun onDensityOrFontScaleChanged() {
        MessagingMessage.dropCache()
        MessagingGroup.dropCache()
        if (!mKeyguardUpdateMonitor.isSwitchingUser) {
            updateNotificationsOnDensityOrFontScaleChanged()
        } else {
            mReinflateNotificationsOnUserSwitched = true
        }
    }

    override fun onUiModeChanged() {
        if (!mKeyguardUpdateMonitor.isSwitchingUser) {
            updateNotificationsOnUiModeChanged()
        } else {
            mDispatchUiModeChangeOnUserSwitched = true
        }
    }

    override fun onThemeChanged() {
        onDensityOrFontScaleChanged()
    }

    override fun onUserChanged(userId: Int) {
        if (mReinflateNotificationsOnUserSwitched) {
            updateNotificationsOnDensityOrFontScaleChanged()
            mReinflateNotificationsOnUserSwitched = false
        }
        if (mDispatchUiModeChangeOnUserSwitched) {
            updateNotificationsOnUiModeChanged()
            mDispatchUiModeChangeOnUserSwitched = false
        }
    }

    private fun updateNotificationsOnUiModeChanged() {
        mPipeline?.allNotifs?.forEach { entry ->
            val row = entry.row
            row?.onUiModeChanged()
        }
    }

    private fun updateNotificationsOnDensityOrFontScaleChanged() {
        mPipeline?.allNotifs?.forEach { entry ->
            entry.onDensityOrFontScaleChanged()
            val exposedGuts = entry.areGutsExposed()
            if (exposedGuts) {
                mGutsManager.onDensityOrFontScaleChanged(entry)
            }
        }
    }
}