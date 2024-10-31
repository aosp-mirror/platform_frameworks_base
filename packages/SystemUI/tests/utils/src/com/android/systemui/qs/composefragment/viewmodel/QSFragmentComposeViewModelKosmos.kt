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

package com.android.systemui.qs.composefragment.viewmodel

import android.content.res.mainResources
import androidx.lifecycle.LifecycleCoroutineScope
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.footerActionsController
import com.android.systemui.qs.footerActionsViewModelFactory
import com.android.systemui.qs.panels.domain.interactor.tileSquishinessInteractor
import com.android.systemui.qs.panels.ui.viewmodel.paginatedGridViewModel
import com.android.systemui.qs.ui.viewmodel.quickSettingsContainerViewModelFactory
import com.android.systemui.shade.largeScreenHeaderHelper
import com.android.systemui.shade.transition.largeScreenShadeInterpolator
import com.android.systemui.statusbar.disableflags.data.repository.disableFlagsRepository
import com.android.systemui.statusbar.sysuiStatusBarStateController
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
val Kosmos.qsFragmentComposeViewModelFactory by
    Kosmos.Fixture {
        object : QSFragmentComposeViewModel.Factory {
            override fun create(
                lifecycleScope: LifecycleCoroutineScope
            ): QSFragmentComposeViewModel {
                return QSFragmentComposeViewModel(
                    quickSettingsContainerViewModelFactory,
                    mainResources,
                    footerActionsViewModelFactory,
                    footerActionsController,
                    sysuiStatusBarStateController,
                    deviceEntryInteractor,
                    disableFlagsRepository,
                    keyguardTransitionInteractor,
                    largeScreenShadeInterpolator,
                    configurationInteractor,
                    largeScreenHeaderHelper,
                    tileSquishinessInteractor,
                    paginatedGridViewModel,
                    lifecycleScope,
                )
            }
        }
    }
