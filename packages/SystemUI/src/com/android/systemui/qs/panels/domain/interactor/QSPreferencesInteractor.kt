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
import com.android.systemui.qs.panels.data.repository.QSPreferencesRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QSPreferencesInteractor @Inject constructor(private val repo: QSPreferencesRepository) {
    val largeTilesSpecs: Flow<Set<TileSpec>> = repo.largeTilesSpecs

    fun setLargeTilesSpecs(specs: Set<TileSpec>) {
        repo.setLargeTilesSpecs(specs)
    }
}
