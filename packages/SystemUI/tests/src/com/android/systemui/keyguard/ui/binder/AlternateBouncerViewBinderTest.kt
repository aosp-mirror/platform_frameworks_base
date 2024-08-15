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

package com.android.systemui.keyguard.ui.binder

import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.view.View
import android.view.layoutInflater
import android.view.mockedLayoutInflater
import android.view.windowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.givenCanShowAlternateBouncer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.isNull

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AlternateBouncerViewBinderTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mockedAltBouncerView =
        spy(kosmos.layoutInflater.inflate(R.layout.alternate_bouncer, null, false))

    @Before
    fun setup() {
        whenever(
                kosmos.mockedLayoutInflater.inflate(
                    eq(R.layout.alternate_bouncer),
                    isNull(),
                    anyBoolean()
                )
            )
            .thenReturn(mockedAltBouncerView)
        kosmos.alternateBouncerViewBinder.start()
    }

    @Test
    @EnableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    fun addViewToWindowManager() {
        testScope.runTest {
            kosmos.givenCanShowAlternateBouncer()
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            verify(kosmos.windowManager).addView(any(), any())
        }
    }

    @Test
    @EnableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    fun viewRemovedImmediatelyIfAlreadyAttachedToWindow() {
        testScope.runTest {
            kosmos.givenCanShowAlternateBouncer()
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            verify(kosmos.windowManager).addView(any(), any())
            whenever(mockedAltBouncerView.isAttachedToWindow).thenReturn(true)

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.ALTERNATE_BOUNCER,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )
            verify(kosmos.windowManager).removeView(any())
        }
    }

    @Test
    @EnableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    fun viewNotRemovedUntilAttachedToWindow() {
        testScope.runTest {
            kosmos.givenCanShowAlternateBouncer()
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            verify(kosmos.windowManager).addView(any(), any())

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.ALTERNATE_BOUNCER,
                to = KeyguardState.LOCKSCREEN,
                testScope,
            )

            verify(kosmos.windowManager, never()).removeView(any())
            givenAltBouncerViewAttachedToWindow()
            verify(kosmos.windowManager).removeView(any())
        }
    }

    private fun givenAltBouncerViewAttachedToWindow() {
        val attachStateChangeListenerCaptor =
            ArgumentCaptor.forClass(View.OnAttachStateChangeListener::class.java)
        verify(mockedAltBouncerView, atLeastOnce())
            .addOnAttachStateChangeListener(attachStateChangeListenerCaptor.capture())
        attachStateChangeListenerCaptor.allValues.onEach {
            it.onViewAttachedToWindow(mockedAltBouncerView)
        }
    }
}
