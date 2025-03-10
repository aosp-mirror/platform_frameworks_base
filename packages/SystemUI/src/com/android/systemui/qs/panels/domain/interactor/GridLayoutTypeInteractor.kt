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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.data.repository.GridLayoutTypeRepository
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class GridLayoutTypeInteractor
@Inject
constructor(private val repo: GridLayoutTypeRepository, shadeModeInteractor: ShadeModeInteractor) {
    val layout: Flow<GridLayoutType> =
        shadeModeInteractor.shadeMode.flatMapLatest { shadeMode ->
            when (shadeMode) {
                is ShadeMode.Dual -> repo.dualShadeLayoutType
                else -> repo.defaultLayoutType
            }
        }
}
