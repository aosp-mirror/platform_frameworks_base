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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.panels.domain.interactor.PaginatedGridInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class PaginatedGridViewModel
@Inject
constructor(
    iconTilesViewModel: IconTilesViewModel,
    gridSizeViewModel: FixedColumnsSizeViewModel,
    iconLabelVisibilityViewModel: IconLabelVisibilityViewModel,
    paginatedGridInteractor: PaginatedGridInteractor,
    @Application applicationScope: CoroutineScope,
) :
    IconTilesViewModel by iconTilesViewModel,
    FixedColumnsSizeViewModel by gridSizeViewModel,
    IconLabelVisibilityViewModel by iconLabelVisibilityViewModel {
    val rows =
        paginatedGridInteractor.rows.stateIn(
            applicationScope,
            SharingStarted.WhileSubscribed(),
            paginatedGridInteractor.defaultRows,
        )

    /*
     * Tracks whether the current HorizontalPager (using this viewmodel) is in the first page.
     * This requires it to be a `@SysUISingleton` to be shared between viewmodels.
     */
    var inFirstPage = true
}
