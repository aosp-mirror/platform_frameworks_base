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

import android.app.ActivityManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.navigationbar.gestural.data.gestureRepository
import com.android.systemui.navigationbar.gestural.domain.GestureInteractor
import com.android.systemui.navigationbar.gestural.domain.TaskMatcher
import com.android.systemui.shared.system.activityManagerWrapper
import com.android.systemui.shared.system.taskStackChangeListeners
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class GestureInteractorTest : SysuiTestCase() {
    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos()

    val dispatcher = kosmos.testDispatcher
    val repository = spy(kosmos.gestureRepository)
    val testScope = TestScope(dispatcher)

    private val underTest by lazy { createInteractor() }

    private fun createInteractor(): GestureInteractor {
        return GestureInteractor(
            repository,
            dispatcher,
            kosmos.backgroundCoroutineContext,
            testScope,
            kosmos.activityManagerWrapper,
            kosmos.taskStackChangeListeners
        )
    }

    private fun setTopActivity(componentName: ComponentName) {
        val task = mock<ActivityManager.RunningTaskInfo>()
        task.topActivity = componentName
        whenever(kosmos.activityManagerWrapper.runningTask).thenReturn(task)

        kosmos.taskStackChangeListeners.listenerImpl.onTaskStackChanged()
    }

    @Test
    fun addBlockedActivity_testCombination() =
        testScope.runTest {
            val globalComponent = mock<ComponentName>()
            repository.addGestureBlockedMatcher(TaskMatcher.TopActivityComponent(globalComponent))

            val localComponent = mock<ComponentName>()

            val blocked by collectLastValue(underTest.topActivityBlocked)

            underTest.addGestureBlockedMatcher(
                TaskMatcher.TopActivityComponent(localComponent),
                GestureInteractor.Scope.Local
            )

            assertThat(blocked).isFalse()

            setTopActivity(localComponent)

            assertThat(blocked).isTrue()
        }

    @Test
    fun initialization_testEmit() =
        testScope.runTest {
            val globalComponent = mock<ComponentName>()
            repository.addGestureBlockedMatcher(TaskMatcher.TopActivityComponent(globalComponent))
            setTopActivity(globalComponent)

            val interactor = createInteractor()

            val blocked by collectLastValue(interactor.topActivityBlocked)
            assertThat(blocked).isTrue()
        }

    @Test
    fun addBlockedActivityLocally_onlyAffectsLocalInteractor() =
        testScope.runTest {
            val interactor1 = createInteractor()
            val interactor1Blocked by collectLastValue(interactor1.topActivityBlocked)
            val interactor2 = createInteractor()
            val interactor2Blocked by collectLastValue(interactor2.topActivityBlocked)

            val localComponent = mock<ComponentName>()

            interactor1.addGestureBlockedMatcher(
                TaskMatcher.TopActivityComponent(localComponent),
                GestureInteractor.Scope.Local
            )
            setTopActivity(localComponent)

            assertThat(interactor1Blocked).isTrue()
            assertThat(interactor2Blocked).isFalse()
        }

    @Test
    fun matchingBlockers_separatelyManaged() =
        testScope.runTest {
            val interactor = createInteractor()
            val interactorBlocked by collectLastValue(interactor.topActivityBlocked)

            val localComponent = mock<ComponentName>()

            val matcher1 = TaskMatcher.TopActivityComponent(localComponent)
            val matcher2 = TaskMatcher.TopActivityComponent(localComponent)

            interactor.addGestureBlockedMatcher(matcher1, GestureInteractor.Scope.Local)
            interactor.addGestureBlockedMatcher(matcher2, GestureInteractor.Scope.Local)
            setTopActivity(localComponent)
            assertThat(interactorBlocked).isTrue()

            interactor.removeGestureBlockedMatcher(matcher1, GestureInteractor.Scope.Local)
            assertThat(interactorBlocked).isTrue()

            interactor.removeGestureBlockedMatcher(matcher2, GestureInteractor.Scope.Local)
            assertThat(interactorBlocked).isFalse()
        }
}
