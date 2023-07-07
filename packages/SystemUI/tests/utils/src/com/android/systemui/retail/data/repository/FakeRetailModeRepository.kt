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

package com.android.systemui.retail.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeRetailModeRepository : RetailModeRepository {

    private val _retailMode = MutableStateFlow(false)
    override val retailMode: StateFlow<Boolean> = _retailMode.asStateFlow()

    private var _retailModeValue = false
    override val inRetailMode: Boolean
        get() = _retailModeValue

    fun setRetailMode(value: Boolean) {
        _retailMode.value = value
        _retailModeValue = value
    }
}
