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

package com.android.systemui.qs.tiles.impl.modes.domain.model

import com.android.systemui.common.shared.model.Icon

data class ModesTileModel(
    val isActivated: Boolean,
    val activeModes: List<String>,
    val icon: Icon.Loaded,

    /**
     * Resource id corresponding to [icon]. Will only be present if it's know to correspond to a
     * resource with a known id in SystemUI (such as resources from `android.R`,
     * `com.android.internal.R`, or `com.android.systemui.res` itself).
     */
    val iconResId: Int? = null
)
