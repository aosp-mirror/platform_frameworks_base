/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.model

import com.android.systemui.qs.pipeline.shared.TileSpec

/** Strategy for when to track a particular [AutoAddable]. */
sealed interface AutoAddTracking {

    /**
     * Indicates that the signals from the associated [AutoAddable] should all be collected and
     * reacted accordingly. It may have [AutoAddSignal.Add] and [AutoAddSignal.Remove].
     */
    object Always : AutoAddTracking {
        override fun toString(): String {
            return "Always"
        }
    }

    /**
     * Indicates that the associated [AutoAddable] is [Disabled] and doesn't need to be collected.
     */
    object Disabled : AutoAddTracking {
        override fun toString(): String {
            return "Disabled"
        }
    }

    /**
     * Only the first [AutoAddSignal.Add] for each flow of signals needs to be collected, and only
     * if the tile hasn't been auto-added yet. The associated [AutoAddable] will only emit
     * [AutoAddSignal.Add].
     */
    data class IfNotAdded(val spec: TileSpec) : AutoAddTracking
}
