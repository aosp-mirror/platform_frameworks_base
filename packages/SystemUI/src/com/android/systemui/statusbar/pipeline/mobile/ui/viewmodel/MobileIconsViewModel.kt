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

@file:OptIn(InternalCoroutinesApi::class)

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import javax.inject.Inject
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow

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
) {
    /** TODO: do we need to cache these? */
    fun viewModelForSub(subId: Int): MobileIconViewModel =
        MobileIconViewModel(
            subId,
            interactor.createMobileConnectionInteractorForSubId(subId),
            logger
        )

    class Factory
    @Inject
    constructor(
        private val interactor: MobileIconsInteractor,
        private val logger: ConnectivityPipelineLogger,
    ) {
        fun create(subscriptionIdsFlow: StateFlow<List<Int>>): MobileIconsViewModel {
            return MobileIconsViewModel(
                subscriptionIdsFlow,
                interactor,
                logger,
            )
        }
    }
}
