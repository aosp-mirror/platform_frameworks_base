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

import android.view.View

import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.util.ViewController

import javax.inject.Inject

/**
 * ViewController for [StatusBarUserSwitcherContainer]
 */
class StatusBarUserSwitcherControllerImpl @Inject constructor(
    view: StatusBarUserSwitcherContainer,
    private val tracker: StatusBarUserInfoTracker,
    private val featureController: StatusBarUserSwitcherFeatureController,
    private val userSwitcherDialogController: UserSwitchDialogController
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

    override fun onViewAttached() {
        tracker.addCallback(listener)
        featureController.addCallback(featureFlagListener)
        mView.setOnClickListener {
            userSwitcherDialogController.showDialog(it)
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
