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
 *
 */

package com.android.systemui.multishade.data.model

import com.android.systemui.multishade.shared.model.ShadeId

/** Models the current interaction with one of the shades. */
data class MultiShadeInteractionModel(
    /** The ID of the shade that the user is currently interacting with. */
    val shadeId: ShadeId,
    /** Whether the interaction is proxied (as in: coming from an external app or different UI). */
    val isProxied: Boolean,
)
