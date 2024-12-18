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

package com.android.systemui.brightness.ui.viewmodel

import com.android.systemui.brightness.domain.interactor.BrightnessPolicyEnforcementInteractor
import com.android.systemui.brightness.domain.interactor.ScreenBrightnessInteractor
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.utils.PolicyRestriction
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class BrightnessSliderViewModel
@Inject
constructor(
    private val screenBrightnessInteractor: ScreenBrightnessInteractor,
    private val brightnessPolicyEnforcementInteractor: BrightnessPolicyEnforcementInteractor,
    @Application private val applicationScope: CoroutineScope,
) {
    val currentBrightness =
        screenBrightnessInteractor.gammaBrightness.stateIn(
            applicationScope,
            SharingStarted.WhileSubscribed(),
            GammaBrightness(0)
        )

    val maxBrightness = screenBrightnessInteractor.maxGammaBrightness
    val minBrightness = screenBrightnessInteractor.minGammaBrightness

    val label = Text.Resource(R.string.quick_settings_brightness_dialog_title)

    val icon = Icon.Resource(R.drawable.ic_brightness_full, ContentDescription.Resource(label.res))

    val policyRestriction = brightnessPolicyEnforcementInteractor.brightnessPolicyRestriction

    fun showPolicyRestrictionDialog(restriction: PolicyRestriction.Restricted) {
        brightnessPolicyEnforcementInteractor.startAdminSupportDetailsDialog(restriction)
    }

    /**
     * As a brightness slider is dragged, the corresponding events should be sent using this method.
     */
    suspend fun onDrag(drag: Drag) {
        when (drag) {
            is Drag.Dragging -> screenBrightnessInteractor.setTemporaryBrightness(drag.brightness)
            is Drag.Stopped -> screenBrightnessInteractor.setBrightness(drag.brightness)
        }
    }

    /**
     * Format the current value of brightness as a percentage between the minimum and maximum gamma.
     */
    fun formatValue(value: Int): String {
        val min = minBrightness.value
        val max = maxBrightness.value
        val coercedValue = value.coerceIn(min, max)
        val percentage = (coercedValue - min) * 100 / (max - min)
        // This is not finalized UI so using fixed string
        return "$percentage%"
    }
}

/** Represents a drag event in a brightness slider. */
sealed interface Drag {
    val brightness: GammaBrightness
    @JvmInline value class Dragging(override val brightness: GammaBrightness) : Drag
    @JvmInline value class Stopped(override val brightness: GammaBrightness) : Drag
}
