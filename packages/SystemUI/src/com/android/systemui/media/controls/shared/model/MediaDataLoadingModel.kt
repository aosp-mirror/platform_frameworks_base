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

package com.android.systemui.media.controls.shared.model

import com.android.internal.logging.InstanceId

/** Models media data loading state. */
sealed class MediaDataLoadingModel {
    /** The initial loading state when no media data has yet loaded. */
    data object Unknown : MediaDataLoadingModel()

    /** Media data has been loaded. */
    data class Loaded(
        val instanceId: InstanceId,
        val immediatelyUpdateUi: Boolean = true,
    ) : MediaDataLoadingModel() {

        /** Returns true if [other] has the same instance id, false otherwise. */
        fun equalInstanceIds(other: MediaDataLoadingModel): Boolean {
            return when (other) {
                is Loaded -> other.instanceId == instanceId
                is Removed -> other.instanceId == instanceId
                Unknown -> false
            }
        }
    }

    /** Media data has been removed. */
    data class Removed(
        val instanceId: InstanceId,
    ) : MediaDataLoadingModel()
}
