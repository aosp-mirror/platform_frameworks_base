/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.controls.ui

import android.service.dreams.IDreamManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.settings.ControlsSettingsDialogManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.statusbar.policy.KeyguardStateController

class TestableControlsActivity(
    uiController: ControlsUiController,
    broadcastDispatcher: BroadcastDispatcher,
    dreamManager: IDreamManager,
    featureFlags: FeatureFlags,
    controlsSettingsDialogManager: ControlsSettingsDialogManager,
    keyguardStateController: KeyguardStateController
) :
    ControlsActivity(
        uiController,
        broadcastDispatcher,
        dreamManager,
        featureFlags,
        controlsSettingsDialogManager,
        keyguardStateController
    )
