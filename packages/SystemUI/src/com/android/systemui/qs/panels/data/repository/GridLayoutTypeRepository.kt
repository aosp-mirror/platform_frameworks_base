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

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.qs.panels.shared.model.InfiniteGridLayoutType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface GridLayoutTypeRepository {
    val layout: StateFlow<GridLayoutType>
}

@SysUISingleton
class GridLayoutTypeRepositoryImpl @Inject constructor() : GridLayoutTypeRepository {
    private val _layout: MutableStateFlow<GridLayoutType> = MutableStateFlow(InfiniteGridLayoutType)
    override val layout = _layout.asStateFlow()
}
