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

import androidx.recyclerview.widget.ListUpdateCallback
import com.android.systemui.media.controls.ui.viewmodel.MediaCommonViewModel

/** A [ListUpdateCallback] to apply media events needed to reach the new state. */
class MediaViewModelListUpdateCallback(
    private val old: List<MediaCommonViewModel>,
    private val new: List<MediaCommonViewModel>,
    private val onAdded: (MediaCommonViewModel, Int) -> Unit,
    private val onUpdated: (MediaCommonViewModel) -> Unit,
    private val onRemoved: (MediaCommonViewModel) -> Unit,
    private val onMoved: (MediaCommonViewModel, Int, Int) -> Unit,
) : ListUpdateCallback {

    override fun onInserted(position: Int, count: Int) {
        for (i in position until position + count) {
            onAdded(new[i], i)
        }
    }

    override fun onRemoved(position: Int, count: Int) {
        for (i in position until position + count) {
            onRemoved(old[i])
        }
    }

    override fun onMoved(fromPosition: Int, toPosition: Int) {
        onMoved(old[fromPosition], fromPosition, toPosition)
    }

    override fun onChanged(position: Int, count: Int, payload: Any?) {
        for (i in position until position + count) {
            onUpdated(new[i])
        }
    }
}
