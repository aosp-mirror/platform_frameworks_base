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
import android.content.Context
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.camera.CameraGestureHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@SysUISingleton
class CameraQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val cameraGestureHelper: Lazy<CameraGestureHelper>,
) : KeyguardQuickAffordanceConfig {

    override val key: String
        get() = BuiltInKeyguardQuickAffordanceKeys.CAMERA

    override val pickerName: String
        get() = context.getString(R.string.accessibility_camera_button)

    override val pickerIconResourceId: Int
        get() = R.drawable.ic_statusbar_camera

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState>
        get() =
            flowOf(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    icon =
                        Icon.Resource(
                            R.drawable.ic_statusbar_camera,
                            ContentDescription.Resource(R.string.accessibility_camera_button)
                        )
                )
            )

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        cameraGestureHelper
            .get()
            .launchCamera(StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE)
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
    }
}
