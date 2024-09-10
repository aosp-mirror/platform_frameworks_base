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

package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.statusbar.notification.shared.HeadsUpRowKey
import kotlinx.coroutines.flow.StateFlow

/** Representation of a top-level heads up row. */
interface HeadsUpRowRepository : HeadsUpRowKey {
    /**
     * The key for this notification. Guaranteed to be immutable and unique.
     *
     * @see com.android.systemui.statusbar.notification.collection.NotificationEntry.getKey
     */
    val key: String

    /** A key to identify this row in the view hierarchy. */
    val elementKey: Any

    /** Whether this notification is "pinned", meaning that it should stay on top of the screen. */
    val isPinned: StateFlow<Boolean>
}
