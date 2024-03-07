/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.app.StatusBarManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import com.android.systemui.camera.CameraIntents
import com.android.systemui.camera.CameraIntentsWrapper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

@SysUISingleton
class VideoCameraQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val cameraIntents: CameraIntentsWrapper,
    private val activityIntentHelper: ActivityIntentHelper,
    private val userTracker: UserTracker,
    private val devicePolicyManager: DevicePolicyManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : KeyguardQuickAffordanceConfig {

    private val intent: Intent by lazy {
        cameraIntents.getVideoCameraIntent().apply {
            putExtra(
                CameraIntents.EXTRA_LAUNCH_SOURCE,
                StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE,
            )
        }
    }

    override val key: String
        get() = BuiltInKeyguardQuickAffordanceKeys.VIDEO_CAMERA

    override fun pickerName(): String = context.getString(R.string.video_camera)

    override val pickerIconResourceId: Int
        get() = R.drawable.ic_videocam

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState>
        get() = flow {
            emit(
                if (isLaunchable()) {
                    KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                        icon =
                            Icon.Resource(
                                R.drawable.ic_videocam,
                                ContentDescription.Resource(R.string.video_camera)
                            )
                    )
                } else {
                    KeyguardQuickAffordanceConfig.LockScreenState.Hidden
                }
            )
        }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return if (isLaunchable()) {
            super.getPickerScreenState()
        } else {
            KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        }
    }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.StartActivity(
            intent = intent,
            canShowWhileLocked = false,
        )
    }

    private suspend fun isLaunchable(): Boolean {
        return activityIntentHelper.getTargetActivityInfo(
            intent,
            userTracker.userId,
            true,
        ) != null &&
            withContext(backgroundDispatcher) {
                !devicePolicyManager.getCameraDisabled(null, userTracker.userId)
            }
    }
}
