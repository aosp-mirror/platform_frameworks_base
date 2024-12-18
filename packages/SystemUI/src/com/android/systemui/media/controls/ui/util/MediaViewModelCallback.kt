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

package com.android.systemui.media.controls.ui.util

import androidx.recyclerview.widget.DiffUtil
import com.android.systemui.media.controls.ui.viewmodel.MediaCommonViewModel

/** A [DiffUtil.Callback] to calculate difference between old and new media view-model list. */
class MediaViewModelCallback(
    private val old: List<MediaCommonViewModel>,
    private val new: List<MediaCommonViewModel>,
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int {
        return old.size
    }

    override fun getNewListSize(): Int {
        return new.size
    }

    override fun areItemsTheSame(oldIndex: Int, newIndex: Int): Boolean {
        val oldItem = old[oldIndex]
        val newItem = new[newIndex]
        return if (
            oldItem is MediaCommonViewModel.MediaControl &&
                newItem is MediaCommonViewModel.MediaControl
        ) {
            oldItem.instanceId == newItem.instanceId
        } else {
            oldItem is MediaCommonViewModel.MediaRecommendations &&
                newItem is MediaCommonViewModel.MediaRecommendations
        }
    }

    override fun areContentsTheSame(oldIndex: Int, newIndex: Int): Boolean {
        val oldItem = old[oldIndex]
        val newItem = new[newIndex]
        return if (
            oldItem is MediaCommonViewModel.MediaControl &&
                newItem is MediaCommonViewModel.MediaControl
        ) {
            oldItem.immediatelyUpdateUi == newItem.immediatelyUpdateUi &&
                oldItem.updateTime == newItem.updateTime
        } else if (
            oldItem is MediaCommonViewModel.MediaRecommendations &&
                newItem is MediaCommonViewModel.MediaRecommendations
        ) {
            oldItem.key == newItem.key && oldItem.loadingEnabled == newItem.loadingEnabled
        } else {
            false
        }
    }
}
