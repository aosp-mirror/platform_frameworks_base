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

package com.android.systemui.statusbar.pipeline.mobile.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.carrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.createDefaultTestConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fake
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CarrierConfigInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    private val Kosmos.underTest by Kosmos.Fixture { kosmos.carrierConfigInteractor }

    @Test
    fun defaultDataSubscriptionCarrierConfig_tracksDefaultSubId() =
        kosmos.runTest {
            val carrierConfig1 = SystemUiCarrierConfig(1, createDefaultTestConfig())
            val carrierConfig2 = SystemUiCarrierConfig(2, createDefaultTestConfig())

            // Put some configs in so we can check by identity
            carrierConfigRepository.fake.configsById[1] = carrierConfig1
            carrierConfigRepository.fake.configsById[2] = carrierConfig2

            val latest by collectLastValue(underTest.defaultDataSubscriptionCarrierConfig)

            fakeMobileIconsInteractor.defaultDataSubId.value = 1

            assertThat(latest).isEqualTo(carrierConfig1)

            fakeMobileIconsInteractor.defaultDataSubId.value = 2

            assertThat(latest).isEqualTo(carrierConfig2)
        }
}
