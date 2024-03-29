/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.settings

import com.android.internal.logging.uiEventLogger
import com.android.systemui.classifier.falsingManager
import com.android.systemui.haptics.vibratorHelper
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.activityStarter
import com.android.systemui.settings.brightness.BrightnessSliderController
import com.android.systemui.util.time.systemClock

/** This factory creates empty mocks. */
var Kosmos.brightnessSliderControllerFactory by
    Kosmos.Fixture<BrightnessSliderController.Factory> {
        BrightnessSliderController.Factory(
            falsingManager,
            uiEventLogger,
            vibratorHelper,
            systemClock,
            activityStarter,
        )
    }
