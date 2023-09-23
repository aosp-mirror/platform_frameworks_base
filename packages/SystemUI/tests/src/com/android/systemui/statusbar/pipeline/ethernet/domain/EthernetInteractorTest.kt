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

package com.android.systemui.statusbar.pipeline.ethernet.domain

import androidx.test.filters.SmallTest
import com.android.settingslib.AccessibilityContentDescriptions
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@SmallTest
class EthernetInteractorTest : SysuiTestCase() {
    private val connectivityRepository = FakeConnectivityRepository()
    private val underTest = EthernetInteractor(connectivityRepository)

    private val testScope = TestScope()

    @Test
    fun icon_default_validated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            connectivityRepository.setEthernetConnected(default = true, validated = true)

            val expected =
                Icon.Resource(
                    R.drawable.stat_sys_ethernet_fully,
                    ContentDescription.Resource(
                        AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES[1]
                    )
                )

            assertThat(latest).isEqualTo(expected)
        }

    @Test
    fun icon_default_notValidated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            connectivityRepository.setEthernetConnected(default = true, validated = false)

            val expected =
                Icon.Resource(
                    R.drawable.stat_sys_ethernet,
                    ContentDescription.Resource(
                        AccessibilityContentDescriptions.ETHERNET_CONNECTION_VALUES[0]
                    )
                )

            assertThat(latest).isEqualTo(expected)
        }

    @Test
    fun icon_notDefault_validated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            connectivityRepository.setEthernetConnected(default = false, validated = true)

            assertThat(latest).isNull()
        }

    @Test
    fun icon_notDefault_notValidated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.icon)

            connectivityRepository.setEthernetConnected(default = false, validated = false)

            assertThat(latest).isNull()
        }
}
