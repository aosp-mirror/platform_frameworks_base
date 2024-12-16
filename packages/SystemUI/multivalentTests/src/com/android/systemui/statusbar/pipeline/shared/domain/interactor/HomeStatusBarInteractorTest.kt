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

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO
import android.telephony.CarrierConfigManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.disableflags.shared.model.DisableFlagsModel
import com.android.systemui.statusbar.pipeline.airplane.data.repository.airplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.data.repository.fake
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.carrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.configWithOverride
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fake
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.fakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.shared.connectivityConstants
import com.android.systemui.statusbar.pipeline.shared.fake
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeStatusBarInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val disableFlagsRepo = kosmos.fakeDisableFlagsRepository

    val underTest = kosmos.homeStatusBarInteractor

    @Test
    fun visibilityViaDisableFlags_allDisabled() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(
                    DISABLE_CLOCK or DISABLE_NOTIFICATION_ICONS or DISABLE_SYSTEM_INFO,
                    DISABLE2_NONE,
                    animate = false,
                )

            assertThat(latest!!.isClockAllowed).isFalse()
            assertThat(latest!!.areNotificationIconsAllowed).isFalse()
            assertThat(latest!!.isSystemInfoAllowed).isFalse()
        }

    @Test
    fun visibilityViaDisableFlags_allEnabled() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE, animate = false)

            assertThat(latest!!.isClockAllowed).isTrue()
            assertThat(latest!!.areNotificationIconsAllowed).isTrue()
            assertThat(latest!!.isSystemInfoAllowed).isTrue()
        }

    @Test
    fun visibilityViaDisableFlags_animateFalse() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE, animate = false)

            assertThat(latest!!.animate).isFalse()
        }

    @Test
    fun visibilityViaDisableFlags_animateTrue() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE, animate = true)

            assertThat(latest!!.animate).isTrue()
        }

    @Test
    fun shouldShowOperatorName_trueIfCarrierConfigSaysSoAndDeviceHasData() =
        kosmos.runTest {
            // GIVEN default data subId is 1
            fakeMobileIconsInteractor.defaultDataSubId.value = 1
            // GIVEN Config is enabled
            carrierConfigRepository.fake.configsById[1] =
                SystemUiCarrierConfig(
                    1,
                    configWithOverride(
                        CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL,
                        true,
                    ),
                )

            // GIVEN airplane mode is off
            airplaneModeRepository.fake.isAirplaneMode.value = false

            // GIVEN hasDataCapabilities is true
            connectivityConstants.fake.hasDataCapabilities = true

            val latest by collectLastValue(underTest.shouldShowOperatorName)

            // THEN we should show the operator name
            assertThat(latest).isTrue()
        }

    @Test
    fun shouldShowOperatorName_falseNoDataCapabilities() =
        kosmos.runTest {
            // GIVEN default data subId is 1
            fakeMobileIconsInteractor.defaultDataSubId.value = 1
            // GIVEN Config is enabled
            carrierConfigRepository.fake.configsById[1] =
                SystemUiCarrierConfig(
                    1,
                    configWithOverride(
                        CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL,
                        true,
                    ),
                )

            // GIVEN airplane mode is off
            airplaneModeRepository.fake.isAirplaneMode.value = true

            // WHEN hasDataCapabilities is false
            connectivityConstants.fake.hasDataCapabilities = false

            val latest by collectLastValue(underTest.shouldShowOperatorName)

            // THEN we should not show the operator name
            assertThat(latest).isFalse()
        }

    @Test
    fun shouldShowOperatorName_falseWhenConfigIsOff() =
        kosmos.runTest {
            // GIVEN default data subId is 1
            fakeMobileIconsInteractor.defaultDataSubId.value = 1
            // GIVEN airplane mode is off
            airplaneModeRepository.fake.isAirplaneMode.value = false

            // WHEN Config is disabled
            carrierConfigRepository.fake.configsById[1] =
                SystemUiCarrierConfig(
                    1,
                    configWithOverride(
                        CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL,
                        false,
                    ),
                )

            val latest by collectLastValue(underTest.shouldShowOperatorName)

            // THEN we should not show the operator name
            assertThat(latest).isFalse()
        }

    @Test
    fun shouldShowOperatorName_falseIfAirplaneMode() =
        kosmos.runTest {
            // GIVEN default data subId is 1
            fakeMobileIconsInteractor.defaultDataSubId.value = 1
            // GIVEN Config is enabled
            carrierConfigRepository.fake.configsById[1] =
                SystemUiCarrierConfig(
                    1,
                    configWithOverride(
                        CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL,
                        true,
                    ),
                )

            // WHEN airplane mode is on
            airplaneModeRepository.fake.isAirplaneMode.value = true

            val latest by collectLastValue(underTest.shouldShowOperatorName)

            // THEN we should not show the operator name
            assertThat(latest).isFalse()
        }
}
