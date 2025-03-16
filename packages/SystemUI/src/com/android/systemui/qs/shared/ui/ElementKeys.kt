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

package com.android.systemui.qs.shared.ui

import com.android.compose.animation.scene.ElementKey
import com.android.systemui.qs.pipeline.shared.TileSpec

/** Element keys to be used by the compose implementation of QS for animations. */
object ElementKeys {
    val QuickSettingsContent = ElementKey("QuickSettingsContent")
    val GridAnchor = ElementKey("QuickSettingsGridAnchor")
    val FooterActions = ElementKey("FooterActions")

    fun TileSpec.toElementKey(positionInGrid: Int) =
        ElementKey(this.spec, TileIdentity(this, positionInGrid))

    val TileElementMatcher = ElementKey.withIdentity { it is TileIdentity }
}

private data class TileIdentity(val spec: TileSpec, val position: Int)
