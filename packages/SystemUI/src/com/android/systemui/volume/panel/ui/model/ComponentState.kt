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
 * limitations under the License.
 */

package com.android.systemui.volume.panel.ui.model

import com.android.systemui.volume.panel.VolumePanelComponentKey

/**
 * State of the [VolumePanelComponent].
 *
 * @property key uniquely identifies this component
 * @property component is an inflated component obtained be the View Model
 * @property isVisible determines component visibility in the UI
 */
data class ComponentState(
    val key: VolumePanelComponentKey,
    val isVisible: Boolean,
)
