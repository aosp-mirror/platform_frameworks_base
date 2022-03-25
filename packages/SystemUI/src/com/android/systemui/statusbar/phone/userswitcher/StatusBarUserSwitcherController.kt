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

package com.android.systemui.statusbar.phone.userswitcher

import android.content.Intent
import android.os.UserHandle
import android.view.View
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager

import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.user.UserSwitcherActivity
import com.android.systemui.util.ViewController

import javax.inject.Inject

/**
 * ViewController for [StatusBarUserSwitcherContainer]
 */
class StatusBarUserSwitcherControllerImpl @Inject constructor(
    view: StatusBarUserSwitcherContainer,
    private val tracker: StatusBarUserInfoTracker,
    private val featureController: StatusBarUserSwitcherFeatureController,
    private val userSwitcherDialogController: UserSwitchDialogController,
    private val featureFlags: FeatureFlags,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager
) : ViewController<StatusBarUserSwitcherContainer>(view),
        StatusBarUserSwitcherController {
    private val listener = object : CurrentUserChipInfoUpdatedListener {
        override fun onCurrentUserChipInfoUpdated() {
            updateChip()
        }

        override fun onStatusBarUserSwitcherSettingChanged(enabled: Boolean) {
            updateEnabled()
        }
    }

    private val featureFlagListener = object : OnUserSwitcherPreferenceChangeListener {
        override fun onUserSwitcherPreferenceChange(enabled: Boolean) {
            updateEnabled()
        }
    }

    public override fun onViewAttached() {
        tracker.addCallback(listener)
        featureController.addCallback(featureFlagListener)
        mView.setOnClickListener { view: View ->
            if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return@setOnClickListener
            }

            if (featureFlags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)) {
                val intent = Intent(context, UserSwitcherActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

                activityStarter.startActivity(intent, true /* dismissShade */,
                        null /* ActivityLaunchAnimator.Controller */,
                        true /* showOverlockscreenwhenlocked */, UserHandle.SYSTEM)
            } else {
                userSwitcherDialogController.showDialog(view)
            }
        }

        updateEnabled()
    }

    override fun onViewDetached() {
        tracker.removeCallback(listener)
        featureController.removeCallback(featureFlagListener)
        mView.setOnClickListener(null)
    }

    private fun updateChip() {
        mView.text.text = tracker.currentUserName
        mView.avatar.setImageDrawable(tracker.currentUserAvatar)
    }

    private fun updateEnabled() {
        if (featureController.isStatusBarUserSwitcherFeatureEnabled() &&
                tracker.userSwitcherEnabled) {
            mView.visibility = View.VISIBLE
            updateChip()
        } else {
            mView.visibility = View.GONE
        }
    }
}

interface StatusBarUserSwitcherController {
    fun init()
}

private const val TAG = "SbUserSwitcherController"
