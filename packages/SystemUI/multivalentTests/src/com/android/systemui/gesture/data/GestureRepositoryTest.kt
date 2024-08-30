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

package com.android.systemui.gesture.data

import android.app.WindowConfiguration
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.navigationbar.gestural.data.respository.GestureRepositoryImpl
import com.android.systemui.navigationbar.gestural.domain.TaskMatcher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
@SmallTest
class GestureRepositoryTest : SysuiTestCase() {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val underTest by lazy { GestureRepositoryImpl(testDispatcher) }

    @Test
    fun addRemoveComponentToBlock_updatesBlockedComponentSet() =
        testScope.runTest {
            val matcher = TaskMatcher.TopActivityComponent(mock<ComponentName>())

            kotlin.run {
                underTest.addGestureBlockedMatcher(matcher)
                val blockedMatchers by collectLastValue(underTest.gestureBlockedMatchers)
                assertThat(blockedMatchers).contains(matcher)
            }

            kotlin.run {
                underTest.removeGestureBlockedMatcher(matcher)
                val blockedMatchers by collectLastValue(underTest.gestureBlockedMatchers)
                assertThat(blockedMatchers).doesNotContain(matcher)
            }
        }

    @Test
    fun addRemoveActivityTypeToBlock_updatesBlockedActivityTypesSet() =
        testScope.runTest {
            val matcher = TaskMatcher.TopActivityType(WindowConfiguration.ACTIVITY_TYPE_STANDARD)

            kotlin.run {
                underTest.addGestureBlockedMatcher(matcher)
                val blockedMatchers by collectLastValue(underTest.gestureBlockedMatchers)
                assertThat(blockedMatchers).contains(matcher)
            }

            kotlin.run {
                underTest.removeGestureBlockedMatcher(matcher)
                val blockedMatchers by collectLastValue(underTest.gestureBlockedMatchers)
                assertThat(blockedMatchers).doesNotContain(matcher)
            }
        }
}
