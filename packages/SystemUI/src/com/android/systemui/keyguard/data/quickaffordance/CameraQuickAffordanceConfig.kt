/*
 *  Copyright (C) 2022 The Android Open Source Project
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
import android.content.pm.PackageManager
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import com.android.systemui.camera.CameraGestureHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

@SysUISingleton
class CameraQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val packageManager: PackageManager,
    private val cameraGestureHelper: Lazy<CameraGestureHelper>,
    private val userTracker: UserTracker,
    private val devicePolicyManager: DevicePolicyManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : KeyguardQuickAffordanceConfig {

    override val key: String
        get() = BuiltInKeyguardQuickAffordanceKeys.CAMERA

    override fun pickerName(): String = context.getString(R.string.accessibility_camera_button)

    override val pickerIconResourceId: Int
        get() = R.drawable.ic_camera

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState>
        get() = flow {
            emit(
                if (isLaunchable()) {
                    KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                        icon =
                            Icon.Resource(
                                R.drawable.ic_camera,
                                ContentDescription.Resource(R.string.accessibility_camera_button)
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
        cameraGestureHelper
            .get()
            .launchCamera(StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE)
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
    }

    private suspend fun isLaunchable(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            withContext(backgroundDispatcher) {
                !devicePolicyManager.getCameraDisabled(null, userTracker.userId) &&
                    devicePolicyManager.getKeyguardDisabledFeatures(null, userTracker.userId) and
                        DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA == 0
            }
    }
}
