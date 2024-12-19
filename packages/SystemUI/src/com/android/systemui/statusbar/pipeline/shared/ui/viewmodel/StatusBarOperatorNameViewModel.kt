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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * View model for the operator name (aka carrier name) of the carrier for the default data
 * subscription.
 */
@SysUISingleton
class StatusBarOperatorNameViewModel
@Inject
constructor(mobileIconsInteractor: MobileIconsInteractor) {
    val operatorName: Flow<String?> =
        mobileIconsInteractor.defaultDataSubId.flatMapLatest {
            if (it == null) {
                flowOf(null)
            } else {
                mobileIconsInteractor.getMobileConnectionInteractorForSubId(it).carrierName
            }
        }
}
