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

package com.android.systemui.keyboard.shortcut.data.repository

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.hardware.input.InputGestureData
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
import android.hardware.input.fakeInputManager
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.hardware.input.Flags.FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES
import com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.customInputGesturesRepository
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsInputGestureData
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
class CustomInputGesturesRepositoryTest : SysuiTestCase() {

    private val mockUserContext: Context = mock()
    private val kosmos = testKosmos().also {
        it.userTracker = FakeUserTracker(onCreateCurrentUserContext = { mockUserContext })
    }

    private val inputManager = kosmos.fakeInputManager.inputManager
    private val testScope = kosmos.testScope
    private val customInputGesturesRepository = kosmos.customInputGesturesRepository

    @Before
    fun setup(){
        whenever(mockUserContext.getSystemService(INPUT_SERVICE)).thenReturn(inputManager)
    }

    @Test
    fun customInputGestures_initialValueReturnsDataFromAPI() {
        testScope.runTest {
            val customInputGestures = listOf(allAppsInputGestureData)
            whenever(
                inputManager.getCustomInputGestures(/* filter= */ InputGestureData.Filter.KEY)
            ).then { return@then customInputGestures }

            val inputGestures by collectLastValue(customInputGesturesRepository.customInputGestures)

            assertThat(inputGestures).containsExactly(allAppsInputGestureData)
        }
    }

    @Test
    fun customInputGestures_isUpdatedToMostRecentDataAfterNewGestureIsAdded() {
        testScope.runTest {
            var customInputGestures = listOf<InputGestureData>()
            whenever(
                inputManager.getCustomInputGestures(/* filter= */ InputGestureData.Filter.KEY)
            ).then { return@then customInputGestures }
            whenever(inputManager.addCustomInputGesture(any())).then { invocation ->
                val inputGesture = invocation.getArgument<InputGestureData>(0)
                customInputGestures = customInputGestures + inputGesture
                return@then CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
            }

            val inputGestures by collectLastValue(customInputGesturesRepository.customInputGestures)
            assertThat(inputGestures).isEmpty()

            customInputGesturesRepository.addCustomInputGesture(allAppsInputGestureData)
            assertThat(inputGestures).containsExactly(allAppsInputGestureData)
        }
    }

    @Test
    fun retrieveCustomInputGestures_retrievesMostRecentData() {
        testScope.runTest {
            var customInputGestures = listOf<InputGestureData>()
            whenever(
                inputManager.getCustomInputGestures(/* filter= */ InputGestureData.Filter.KEY)
            ).then { return@then customInputGestures }


            assertThat(customInputGesturesRepository.retrieveCustomInputGestures()).isEmpty()

            customInputGestures = listOf(allAppsInputGestureData)

            assertThat(customInputGesturesRepository.retrieveCustomInputGestures())
                .containsExactly(allAppsInputGestureData)
        }
    }

}