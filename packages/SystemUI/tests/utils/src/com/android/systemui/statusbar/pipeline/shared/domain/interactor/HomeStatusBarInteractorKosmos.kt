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

package com.android.systemui.statusbar.pipeline.shared.domain.interactor

import android.telephony.CarrierConfigManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.statusbar.disableflags.domain.interactor.disableFlagsInteractor
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.airplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.carrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.configWithOverride
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fake
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.carrierConfigInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.fakeMobileIconsInteractor

val Kosmos.homeStatusBarInteractor: HomeStatusBarInteractor by
    Kosmos.Fixture {
        HomeStatusBarInteractor(
            airplaneModeInteractor,
            carrierConfigInteractor,
            disableFlagsInteractor,
        )
    }

/** Set the default data subId to 1, and sets the carrier config setting to [show] */
fun Kosmos.setHomeStatusBarInteractorShowOperatorName(show: Boolean) {
    fakeMobileIconsInteractor.defaultDataSubId.value = 1
    carrierConfigRepository.fake.configsById[1] =
        SystemUiCarrierConfig(
            1,
            configWithOverride(CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL, show),
        )
}
