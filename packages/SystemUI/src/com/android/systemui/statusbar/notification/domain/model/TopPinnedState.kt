/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.domain.model

import com.android.systemui.statusbar.notification.headsup.PinnedStatus

/** A class representing the state of the top pinned row. */
sealed interface TopPinnedState {
    /** Nothing is pinned. */
    data object NothingPinned : TopPinnedState

    /**
     * The top pinned row is a notification with the given key and status.
     *
     * @property status must have [PinnedStatus.isPinned] as true.
     */
    data class Pinned(val key: String, val status: PinnedStatus) : TopPinnedState {
        init {
            check(status.isPinned)
        }
    }
}
