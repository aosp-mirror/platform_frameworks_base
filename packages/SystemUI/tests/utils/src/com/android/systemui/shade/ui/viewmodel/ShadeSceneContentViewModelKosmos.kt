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

package com.android.systemui.shade.ui.viewmodel

import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaCarouselInteractor
import com.android.systemui.qs.footerActionsController
import com.android.systemui.qs.footerActionsViewModelFactory
import com.android.systemui.qs.ui.adapter.qsSceneAdapter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.settings.brightness.ui.viewmodel.brightnessMirrorViewModelFactory
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.unfold.domain.interactor.unfoldTransitionInteractor

val Kosmos.shadeSceneContentViewModel: ShadeSceneContentViewModel by Fixture {
    ShadeSceneContentViewModel(
        shadeHeaderViewModelFactory = shadeHeaderViewModelFactory,
        qsSceneAdapter = qsSceneAdapter,
        brightnessMirrorViewModelFactory = brightnessMirrorViewModelFactory,
        mediaCarouselInteractor = mediaCarouselInteractor,
        shadeInteractor = shadeInteractor,
        footerActionsViewModelFactory = footerActionsViewModelFactory,
        footerActionsController = footerActionsController,
        unfoldTransitionInteractor = unfoldTransitionInteractor,
        deviceEntryInteractor = deviceEntryInteractor,
        sceneInteractor = sceneInteractor,
    )
}
