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

package com.android.systemui.gesture.domain

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.navigationbar.gestural.data.respository.GestureRepository
import com.android.systemui.navigationbar.gestural.domain.GestureInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class GestureInteractorTest : SysuiTestCase() {
    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()

    val dispatcher = StandardTestDispatcher()
    val testScope = TestScope(dispatcher)

    @Mock private lateinit var gestureRepository: GestureRepository

    private val underTest by lazy {
        GestureInteractor(gestureRepository, testScope.backgroundScope)
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        whenever(gestureRepository.gestureBlockedActivities).thenReturn(MutableStateFlow(setOf()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addBlockedActivity_testCombination() =
        testScope.runTest {
            val globalComponent = mock<ComponentName>()
            whenever(gestureRepository.gestureBlockedActivities)
                .thenReturn(MutableStateFlow(setOf(globalComponent)))
            val localComponent = mock<ComponentName>()
            underTest.addGestureBlockedActivity(localComponent, GestureInteractor.Scope.Local)
            val lastSeen by collectLastValue(underTest.gestureBlockedActivities)
            testScope.runCurrent()
            verify(gestureRepository, never()).addGestureBlockedActivity(any())
            assertThat(lastSeen).hasSize(2)
            assertThat(lastSeen).containsExactly(globalComponent, localComponent)
        }

    @Test
    fun addBlockedActivityLocally_onlyAffectsLocalInteractor() =
        testScope.runTest {
            val component = mock<ComponentName>()
            underTest.addGestureBlockedActivity(component, GestureInteractor.Scope.Local)
            val lastSeen by collectLastValue(underTest.gestureBlockedActivities)
            testScope.runCurrent()
            verify(gestureRepository, never()).addGestureBlockedActivity(any())
            assertThat(lastSeen).contains(component)
        }

    @Test
    fun removeBlockedActivityLocally_onlyAffectsLocalInteractor() =
        testScope.runTest {
            val component = mock<ComponentName>()
            underTest.addGestureBlockedActivity(component, GestureInteractor.Scope.Local)
            val lastSeen by collectLastValue(underTest.gestureBlockedActivities)
            testScope.runCurrent()
            underTest.removeGestureBlockedActivity(component, GestureInteractor.Scope.Local)
            testScope.runCurrent()
            verify(gestureRepository, never()).removeGestureBlockedActivity(any())
            assertThat(lastSeen).isEmpty()
        }

    @Test
    fun addBlockedActivity_invokesRepository() =
        testScope.runTest {
            val component = mock<ComponentName>()
            underTest.addGestureBlockedActivity(component, GestureInteractor.Scope.Global)
            runCurrent()
            val captor = argumentCaptor<ComponentName>()
            verify(gestureRepository).addGestureBlockedActivity(captor.capture())
            assertThat(captor.firstValue).isEqualTo(component)
        }

    @Test
    fun removeBlockedActivity_invokesRepository() =
        testScope.runTest {
            val component = mock<ComponentName>()
            underTest.removeGestureBlockedActivity(component, GestureInteractor.Scope.Global)
            runCurrent()
            val captor = argumentCaptor<ComponentName>()
            verify(gestureRepository).removeGestureBlockedActivity(captor.capture())
            assertThat(captor.firstValue).isEqualTo(component)
        }
}
