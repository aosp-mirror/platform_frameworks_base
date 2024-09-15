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

import kotlinx.coroutines.flow.MutableStateFlow

class FakeHeadsUpRowRepository(override val key: String, override val elementKey: Any = Any()) :
    HeadsUpRowRepository {
    constructor(key: String, isPinned: Boolean) : this(key = key) {
        this.isPinned.value = isPinned
    }

    override val isPinned: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
