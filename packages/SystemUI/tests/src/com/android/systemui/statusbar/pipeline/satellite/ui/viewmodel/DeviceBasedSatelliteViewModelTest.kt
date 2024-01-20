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

package com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.satellite.data.prod.FakeDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.domain.interactor.DeviceBasedSatelliteInteractor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.mockito.MockitoAnnotations

@SmallTest
class DeviceBasedSatelliteViewModelTest : SysuiTestCase() {
    private lateinit var underTest: DeviceBasedSatelliteViewModel
    private lateinit var interactor: DeviceBasedSatelliteInteractor

    private val repo = FakeDeviceBasedSatelliteRepository()
    private val mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())

    private val testScope = TestScope()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        interactor =
            DeviceBasedSatelliteInteractor(
                repo,
                mobileIconsInteractor,
                testScope.backgroundScope,
            )

        underTest =
            DeviceBasedSatelliteViewModel(
                interactor,
                testScope.backgroundScope,
            )
    }

    @Test
    fun icon_nullWhenShouldNotShow_satelliteNotAllowed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is not allowed
            repo.isSatelliteAllowedForCurrentLocation.value = false

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false

            // THEN icon is null because we should not be showing it
            assertThat(latest).isNull()
        }

    @Test
    fun icon_nullWhenShouldNotShow_notAllOos() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are not OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = true

            // THEN icon is null because we have service
            assertThat(latest).isNull()
        }

    @Test
    fun icon_satelliteIsOff() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            // GIVEN satellite is allowed
            repo.isSatelliteAllowedForCurrentLocation.value = true

            // GIVEN all icons are OOS
            val i1 = mobileIconsInteractor.getMobileConnectionInteractorForSubId(1)
            i1.isInService.value = false

            // THEN icon is null because we have service
            assertThat(latest).isInstanceOf(Icon::class.java)
        }
}
