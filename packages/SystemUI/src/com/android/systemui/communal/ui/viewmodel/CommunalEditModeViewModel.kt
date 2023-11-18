/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.ui.viewmodel

import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.media.dagger.MediaModule
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.Flow

/** The view model for communal hub in edit mode. */
@SysUISingleton
class CommunalEditModeViewModel
@Inject
constructor(
    private val communalInteractor: CommunalInteractor,
    @Named(MediaModule.COMMUNAL_HUB) mediaHost: MediaHost,
) : BaseCommunalViewModel(communalInteractor, mediaHost) {

    override val isEditMode = true

    // Only widgets are editable.
    override val communalContent: Flow<List<CommunalContentModel>> =
        communalInteractor.widgetContent

    override fun onDeleteWidget(id: Int) = communalInteractor.deleteWidget(id)

    override fun onReorderWidgets(ids: List<Int>) = communalInteractor.updateWidgetOrder(ids)
}
