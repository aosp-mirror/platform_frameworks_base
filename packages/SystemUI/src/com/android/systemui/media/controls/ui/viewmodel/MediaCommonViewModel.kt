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

package com.android.systemui.media.controls.ui.viewmodel

import com.android.internal.logging.InstanceId

/** Models media view model UI state. */
sealed class MediaCommonViewModel {

    abstract val onAdded: (MediaCommonViewModel) -> Unit
    abstract val onRemoved: (Boolean) -> Unit
    abstract val onUpdated: (MediaCommonViewModel) -> Unit

    data class MediaControl(
        val instanceId: InstanceId,
        val immediatelyUpdateUi: Boolean,
        val controlViewModel: MediaControlViewModel,
        override val onAdded: (MediaCommonViewModel) -> Unit,
        override val onRemoved: (Boolean) -> Unit,
        override val onUpdated: (MediaCommonViewModel) -> Unit,
        val isMediaFromRec: Boolean = false,
        val updateTime: Long = 0,
    ) : MediaCommonViewModel()

    data class MediaRecommendations(
        val key: String,
        val loadingEnabled: Boolean,
        val recsViewModel: MediaRecommendationsViewModel,
        override val onAdded: (MediaCommonViewModel) -> Unit,
        override val onRemoved: (Boolean) -> Unit,
        override val onUpdated: (MediaCommonViewModel) -> Unit,
    ) : MediaCommonViewModel()
}
