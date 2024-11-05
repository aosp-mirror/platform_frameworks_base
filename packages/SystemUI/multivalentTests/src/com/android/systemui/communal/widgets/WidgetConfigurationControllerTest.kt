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
import android.content.IntentSender
import android.os.Binder
import android.os.OutcomeReceiver
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.model.fakeGlanceableHubMultiUserHelper
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class WidgetConfigurationControllerTest : SysuiTestCase() {
    private val appWidgetHost = mock<CommunalAppWidgetHost>()
    private val ownerActivity = mock<ComponentActivity>()

    private val outcomeReceiverCaptor = argumentCaptor<OutcomeReceiver<IntentSender?, Throwable>>()

    private val kosmos = testKosmos()

    private lateinit var underTest: WidgetConfigurationController

    @Before
    fun setUp() {
        underTest =
            WidgetConfigurationController(
                ownerActivity,
                { appWidgetHost },
                kosmos.testDispatcher,
                kosmos.fakeGlanceableHubMultiUserHelper,
                { kosmos.mockGlanceableHubWidgetManager },
                kosmos.fakeExecutor,
            )
    }

    @Test
    fun configureWidget_activityNotFound_returnsFalse() =
        with(kosmos) {
            testScope.runTest {
                whenever(
                        appWidgetHost.startAppWidgetConfigureActivityForResult(
                            eq(ownerActivity),
                            eq(123),
                            anyInt(),
                            eq(WidgetConfigurationController.REQUEST_CODE),
                            any(),
                        )
                    )
                    .thenThrow(ActivityNotFoundException())

                assertThat(underTest.configureWidget(123)).isFalse()
            }
        }

    @Test
    fun configureWidget_configurationFails_returnsFalse() =
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
    fun configureWidget_configurationSucceeds_returnsTrue() =
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

    @Test
    fun configureWidget_headlessSystemUser_activityNotFound_returnsFalse() =
        with(kosmos) {
            testScope.runTest {
                fakeGlanceableHubMultiUserHelper.setIsInHeadlessSystemUser(true)

                // Activity not found
                whenever(
                        mockGlanceableHubWidgetManager.getIntentSenderForConfigureActivity(
                            anyInt(),
                            outcomeReceiverCaptor.capture(),
                            any(),
                        )
                    )
                    .then { outcomeReceiverCaptor.firstValue.onError(ActivityNotFoundException()) }

                val result = async { underTest.configureWidget(123) }
                runCurrent()

                assertThat(result.await()).isFalse()
                result.cancel()
            }
        }

    @Test
    fun configureWidget_headlessSystemUser_intentSenderNull_returnsFalse() =
        with(kosmos) {
            testScope.runTest {
                fakeGlanceableHubMultiUserHelper.setIsInHeadlessSystemUser(true)

                prepareIntentSender(null)

                assertThat(underTest.configureWidget(123)).isFalse()
            }
        }

    @Test
    fun configureWidget_headlessSystemUser_configurationFails_returnsFalse() =
        with(kosmos) {
            testScope.runTest {
                fakeGlanceableHubMultiUserHelper.setIsInHeadlessSystemUser(true)

                val intentSender = IntentSender(Binder())
                prepareIntentSender(intentSender)

                val result = async { underTest.configureWidget(123) }
                runCurrent()
                assertThat(result.isCompleted).isFalse()

                verify(ownerActivity)
                    .startIntentSenderForResult(
                        eq(intentSender),
                        eq(WidgetConfigurationController.REQUEST_CODE),
                        anyOrNull(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        any(),
                    )

                underTest.setConfigurationResult(Activity.RESULT_CANCELED)
                runCurrent()

                assertThat(result.await()).isFalse()
                result.cancel()
            }
        }

    @Test
    fun configureWidget_headlessSystemUser_configurationSucceeds_returnsTrue() =
        with(kosmos) {
            testScope.runTest {
                fakeGlanceableHubMultiUserHelper.setIsInHeadlessSystemUser(true)

                val intentSender = IntentSender(Binder())
                prepareIntentSender(intentSender)

                val result = async { underTest.configureWidget(123) }
                runCurrent()
                assertThat(result.isCompleted).isFalse()

                verify(ownerActivity)
                    .startIntentSenderForResult(
                        eq(intentSender),
                        eq(WidgetConfigurationController.REQUEST_CODE),
                        anyOrNull(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        any(),
                    )

                underTest.setConfigurationResult(Activity.RESULT_OK)
                runCurrent()

                assertThat(result.await()).isTrue()
                result.cancel()
            }
        }

    private fun prepareIntentSender(intentSender: IntentSender?) =
        with(kosmos) {
            whenever(
                    mockGlanceableHubWidgetManager.getIntentSenderForConfigureActivity(
                        anyInt(),
                        outcomeReceiverCaptor.capture(),
                        any(),
                    )
                )
                .then { outcomeReceiverCaptor.firstValue.onResult(intentSender) }
        }
}
