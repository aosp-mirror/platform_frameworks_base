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

package com.android.systemui.keyguard.ui.binder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.view.InWindowLauncherUnlockAnimationManager
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.util.mockito.any
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class InWindowLauncherUnlockAnimationManagerTest : SysuiTestCase() {
    private lateinit var underTest: InWindowLauncherUnlockAnimationManager

    private lateinit var testComponent: TestComponent
    private lateinit var testScope: TestScope

    @Mock private lateinit var launcherUnlockAnimationController: ILauncherUnlockAnimationController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testComponent =
            DaggerInWindowLauncherUnlockAnimationManagerTest_TestComponent.factory()
                .create(
                    test = this,
                )
        underTest = testComponent.underTest
        testScope = testComponent.testScope

        underTest.setLauncherUnlockController("launcherClass", launcherUnlockAnimationController)
    }

    @Test
    fun testPrepareForUnlock_calledOnlyOnce() =
        testScope.runTest {
            underTest.prepareForUnlock()
            underTest.prepareForUnlock()

            verify(launcherUnlockAnimationController)
                .prepareForUnlock(anyBoolean(), any(), anyInt())
        }

    @Test
    fun testPlayUnlockAnimation_onlyCalledIfPrepared() =
        testScope.runTest {
            underTest.playUnlockAnimation(true, 200, 0)
            verify(launcherUnlockAnimationController, never())
                .playUnlockAnimation(any(), any(), any())
        }

    @Test
    fun testForceUnlocked_ifPreparedButNeverStarted() =
        testScope.runTest {
            underTest.prepareForUnlock()
            underTest.ensureUnlockedOrAnimatingUnlocked()

            verify(launcherUnlockAnimationController).setUnlockAmount(1f, true)
        }

    @Test
    fun testForceUnlocked_ifManualUnlockAmountLessThan1() =
        testScope.runTest {
            underTest.prepareForUnlock()
            underTest.setUnlockAmount(0.5f, false)
            underTest.ensureUnlockedOrAnimatingUnlocked()

            verify(launcherUnlockAnimationController).prepareForUnlock(any(), any(), any())
            verify(launcherUnlockAnimationController).setUnlockAmount(0.5f, false)
            verify(launcherUnlockAnimationController).setUnlockAmount(1f, true)
            verifyNoMoreInteractions(launcherUnlockAnimationController)
        }

    @Test
    fun testDoesNotForceUnlocked_ifNeverPrepared() =
        testScope.runTest {
            underTest.ensureUnlockedOrAnimatingUnlocked()

            verifyNoMoreInteractions(launcherUnlockAnimationController)
        }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
            ]
    )
    interface TestComponent {
        val underTest: InWindowLauncherUnlockAnimationManager
        val testScope: TestScope

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
            ): TestComponent
        }
    }
}
