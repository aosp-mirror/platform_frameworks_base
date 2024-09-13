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

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class EditWidgetsActivityStarterTest : SysuiTestCase() {
    private val activityStarter = mock<ActivityStarter>()
    private val kosmos = testKosmos()

    private lateinit var component: ComponentName
    private lateinit var underTest: EditWidgetsActivityStarter

    @Before
    fun setUp() {
        component = ComponentName(context, EditWidgetsActivity::class.java)
        underTest =
            EditWidgetsActivityStarterImpl(
                context.applicationContext,
                activityStarter,
            )
    }

    @Test
    fun activityLaunch_intentIsWellFormed() {
        with(kosmos) {
            testScope.runTest {
                underTest.startActivity(shouldOpenWidgetPickerOnStart = true)

                val captor = argumentCaptor<Intent>()
                verify(activityStarter)
                    .startActivityDismissingKeyguard(captor.capture(), eq(true), eq(true), any())
                assertThat(captor.lastValue.component).isEqualTo(component)
                assertThat(captor.lastValue.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
                assertThat(captor.lastValue.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .isNotEqualTo(0)
                assertThat(
                        captor.lastValue.extras?.getBoolean(
                            EditWidgetsActivity.EXTRA_OPEN_WIDGET_PICKER_ON_START
                        )
                    )
                    .isEqualTo(true)

                underTest.startActivity(shouldOpenWidgetPickerOnStart = false)

                verify(activityStarter, times(2))
                    .startActivityDismissingKeyguard(captor.capture(), eq(true), eq(true), any())
                assertThat(captor.lastValue.component).isEqualTo(component)
                assertThat(captor.lastValue.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
                assertThat(captor.lastValue.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .isNotEqualTo(0)
                assertThat(
                        captor.lastValue.extras?.getBoolean(
                            EditWidgetsActivity.EXTRA_OPEN_WIDGET_PICKER_ON_START
                        )
                    )
                    .isEqualTo(false)
            }
        }
    }
}
