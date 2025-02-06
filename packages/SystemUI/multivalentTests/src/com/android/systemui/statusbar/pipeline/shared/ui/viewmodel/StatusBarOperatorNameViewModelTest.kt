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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.fakeMobileIconsInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarOperatorNameViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val Kosmos.underTest by Kosmos.Fixture { kosmos.statusBarOperatorNameViewModel }

    @Test
    fun operatorName_tracksDefaultDataCarrierName() =
        kosmos.runTest {
            val intr1 = fakeMobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            val intr2 = fakeMobileIconsInteractor.getMobileConnectionInteractorForSubId(2)

            // GIVEN default data subId is 1
            fakeMobileIconsInteractor.defaultDataSubId.value = 1

            intr1.carrierName.value = "Test Name 1"
            intr2.carrierName.value = "Test Name 2"

            val latest by collectLastValue(underTest.operatorName)

            assertThat(latest).isEqualTo("Test Name 1")

            fakeMobileIconsInteractor.defaultDataSubId.value = 2

            assertThat(latest).isEqualTo("Test Name 2")

            fakeMobileIconsInteractor.defaultDataSubId.value = null

            assertThat(latest).isNull()
        }

    @Test
    fun operatorName_noDefaultDataSubId_null() =
        kosmos.runTest {
            // GIVEN defaultDataSubId is null
            fakeMobileIconsInteractor.defaultDataSubId.value = null

            val latest by collectLastValue(underTest.operatorName)

            assertThat(latest).isNull()
        }
}
