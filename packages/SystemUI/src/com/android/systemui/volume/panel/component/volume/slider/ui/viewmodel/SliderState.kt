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

package com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel

import com.android.systemui.common.shared.model.Icon

/**
 * Models a state of a volume slider.
 *
 * @property disabledMessage is shown when [isEnabled] is false
 */
sealed interface SliderState {
    val value: Float
    val valueRange: ClosedFloatingPointRange<Float>
    val icon: Icon?
    val isEnabled: Boolean
    val label: String
    /**
     * A11y slider controls works by adjusting one step up or down. The default slider step isn't
     * enough to trigger rounding to the correct value.
     */
    val a11yStep: Int
    val a11yClickDescription: String?
    val a11yStateDescription: String?
    val disabledMessage: String?
    val isMutable: Boolean

    data object Empty : SliderState {
        override val value: Float = 0f
        override val valueRange: ClosedFloatingPointRange<Float> = 0f..1f
        override val icon: Icon? = null
        override val label: String = ""
        override val disabledMessage: String? = null
        override val a11yStep: Int = 0
        override val a11yClickDescription: String? = null
        override val a11yStateDescription: String? = null
        override val isEnabled: Boolean = true
        override val isMutable: Boolean = false
    }
}
