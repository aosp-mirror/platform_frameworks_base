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

package com.android.systemui.qs.panels.domain.startable

import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.domain.interactor.QSPreferencesInteractor
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.receiveAsFlow

class QSPanelsCoreStartable
@Inject
constructor(
    private val tileSpecRepository: TileSpecRepository,
    private val preferenceInteractor: QSPreferencesInteractor,
    @Background private val backgroundApplicationScope: CoroutineScope,
) : CoreStartable {
    override fun start() {
        backgroundApplicationScope.launchTraced("QSPanelsCoreStartable.startingLargeTiles") {
            tileSpecRepository.tilesReadFromSetting.receiveAsFlow().collect { (tiles, userId) ->
                preferenceInteractor.setInitialLargeTilesSpecs(tiles, userId)
            }
        }
    }
}
