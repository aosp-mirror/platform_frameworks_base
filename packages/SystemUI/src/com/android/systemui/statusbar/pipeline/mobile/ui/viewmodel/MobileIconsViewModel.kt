/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * View model for describing the system's current mobile cellular connections. The result is a list
 * of [MobileIconViewModel]s which describe the individual icons and can be bound to
 * [ModernStatusBarMobileView]
 */
class MobileIconsViewModel
@Inject
constructor(
    val subscriptionIdsFlow: StateFlow<List<Int>>,
    private val interactor: MobileIconsInteractor,
    private val logger: ConnectivityPipelineLogger,
    private val constants: ConnectivityConstants,
    @Application private val scope: CoroutineScope,
    private val statusBarPipelineFlags: StatusBarPipelineFlags,
) {
    @VisibleForTesting val mobileIconSubIdCache = mutableMapOf<Int, MobileIconViewModel>()

    init {
        scope.launch { subscriptionIdsFlow.collect { removeInvalidModelsFromCache(it) } }
    }

    fun viewModelForSub(subId: Int, location: StatusBarLocation): LocationBasedMobileViewModel {
        val common =
            mobileIconSubIdCache[subId]
                ?: MobileIconViewModel(
                        subId,
                        interactor.createMobileConnectionInteractorForSubId(subId),
                        logger,
                        constants,
                        scope,
                    )
                    .also { mobileIconSubIdCache[subId] = it }

        return LocationBasedMobileViewModel.viewModelForLocation(
            common,
            statusBarPipelineFlags,
            location,
        )
    }

    private fun removeInvalidModelsFromCache(subIds: List<Int>) {
        val subIdsToRemove = mobileIconSubIdCache.keys.filter { !subIds.contains(it) }
        subIdsToRemove.forEach { mobileIconSubIdCache.remove(it) }
    }

    class Factory
    @Inject
    constructor(
        private val interactor: MobileIconsInteractor,
        private val logger: ConnectivityPipelineLogger,
        private val constants: ConnectivityConstants,
        @Application private val scope: CoroutineScope,
        private val statusBarPipelineFlags: StatusBarPipelineFlags,
    ) {
        fun create(subscriptionIdsFlow: StateFlow<List<Int>>): MobileIconsViewModel {
            return MobileIconsViewModel(
                subscriptionIdsFlow,
                interactor,
                logger,
                constants,
                scope,
                statusBarPipelineFlags,
            )
        }
    }
}
