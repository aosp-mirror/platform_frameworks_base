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

package com.android.systemui.communal.widgets

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class WidgetConfigurationControllerTest : SysuiTestCase() {
    @Mock private lateinit var appWidgetHost: CommunalAppWidgetHost
    @Mock private lateinit var ownerActivity: ComponentActivity

    private val kosmos = testKosmos()

    private lateinit var underTest: WidgetConfigurationController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            WidgetConfigurationController(ownerActivity, appWidgetHost, kosmos.testDispatcher)
    }

    @Test
    fun configurationFailsWhenActivityNotFound() =
        with(kosmos) {
            testScope.runTest {
                whenever(
                        appWidgetHost.startAppWidgetConfigureActivityForResult(
                            eq(ownerActivity),
                            eq(123),
                            anyInt(),
                            eq(WidgetConfigurationController.REQUEST_CODE),
                            any()
                        )
                    )
                    .thenThrow(ActivityNotFoundException())

                assertThat(underTest.configureWidget(123)).isFalse()
            }
        }

    @Test
    fun configurationFails() =
        with(kosmos) {
            testScope.runTest {
                val result = async { underTest.configureWidget(123) }
                runCurrent()
                assertThat(result.isCompleted).isFalse()

                underTest.setConfigurationResult(Activity.RESULT_CANCELED)
                runCurrent()

                assertThat(result.await()).isFalse()
                result.cancel()
            }
        }

    @Test
    fun configurationSuccessful() =
        with(kosmos) {
            testScope.runTest {
                val result = async { underTest.configureWidget(123) }
                runCurrent()
                assertThat(result.isCompleted).isFalse()

                underTest.setConfigurationResult(Activity.RESULT_OK)
                runCurrent()

                assertThat(result.await()).isTrue()
                result.cancel()
            }
        }
}
