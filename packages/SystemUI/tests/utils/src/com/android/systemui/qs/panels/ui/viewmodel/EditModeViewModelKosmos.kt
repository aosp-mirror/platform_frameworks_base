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

package com.android.systemui.qs.panels.ui.viewmodel

import android.content.applicationContext
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.qs.panels.domain.interactor.editTilesListInteractor
import com.android.systemui.qs.panels.domain.interactor.gridLayoutMap
import com.android.systemui.qs.panels.domain.interactor.gridLayoutTypeInteractor
import com.android.systemui.qs.panels.domain.interactor.infiniteGridLayout
import com.android.systemui.qs.panels.domain.interactor.tilesAvailabilityInteractor
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.domain.interactor.minimumTilesInteractor

val Kosmos.editModeViewModel by
    Kosmos.Fixture {
        EditModeViewModel(
            editTilesListInteractor,
            currentTilesInteractor,
            tilesAvailabilityInteractor,
            minimumTilesInteractor,
            configurationInteractor,
            applicationContext,
            infiniteGridLayout,
            applicationCoroutineScope,
            gridLayoutTypeInteractor,
            gridLayoutMap,
        )
    }
