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

import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHeadsUpRowRepository(override val key: String, override val elementKey: Any = Any()) :
    HeadsUpRowRepository {
    constructor(
        key: String,
        elementKey: Any = Any(),
        isPinned: Boolean,
    ) : this(key = key, elementKey = elementKey) {
        this.pinnedStatus.value = if (isPinned) PinnedStatus.PinnedBySystem else PinnedStatus.NotPinned
    }

    constructor(
        key: String,
        elementKey: Any = Any(),
        pinnedStatus: PinnedStatus,
    ) : this(key = key, elementKey = elementKey) {
        this.pinnedStatus.value = pinnedStatus
    }

    override val pinnedStatus: MutableStateFlow<PinnedStatus> =
        MutableStateFlow(PinnedStatus.NotPinned)
}
