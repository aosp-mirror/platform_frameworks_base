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

import android.content.applicationContext
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.activityStarter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.domain.interactor.privacyChipInteractor
import com.android.systemui.shade.domain.interactor.shadeHeaderClockInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.mobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.mobileIconsViewModel

val Kosmos.shadeHeaderViewModel: ShadeHeaderViewModel by
    Kosmos.Fixture {
        ShadeHeaderViewModel(
            context = applicationContext,
            activityStarter = activityStarter,
            sceneInteractor = sceneInteractor,
            shadeInteractor = shadeInteractor,
            mobileIconsInteractor = mobileIconsInteractor,
            mobileIconsViewModel = mobileIconsViewModel,
            privacyChipInteractor = privacyChipInteractor,
            clockInteractor = shadeHeaderClockInteractor,
            broadcastDispatcher = broadcastDispatcher,
        )
    }

val Kosmos.shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory by
    Kosmos.Fixture {
        object : ShadeHeaderViewModel.Factory {
            override fun create(): ShadeHeaderViewModel {
                return shadeHeaderViewModel
            }
        }
    }
