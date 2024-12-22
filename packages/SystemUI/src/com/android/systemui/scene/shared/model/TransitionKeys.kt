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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.TransitionKey

/**
 * Defines all known named transitions.
 *
 * These are the subset of transitions that can be referenced by key when asking for a scene change.
 */
object TransitionKeys {
    /** Reference to the gone/lockscreen to shade transition with split shade enabled. */
    val ToSplitShade = TransitionKey("GoneToSplitShade")

    /**
     * Reference to a scene transition that can collapse the shade scene slightly faster than a
     * normal collapse would.
     */
    val SlightlyFasterShadeCollapse = TransitionKey("SlightlyFasterShadeCollapse")
}
