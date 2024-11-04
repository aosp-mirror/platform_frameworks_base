/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.unfold.util

import android.testing.TestableLooper
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.FakeUnfoldTransitionProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.updates.RotationChangeProvider
import com.android.systemui.unfold.updates.RotationChangeProvider.RotationListener
import com.android.systemui.util.mockito.capture
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
class NaturalRotationUnfoldProgressProviderTest : SysuiTestCase() {

    @Mock lateinit var rotationChangeProvider: RotationChangeProvider

    private val sourceProvider = FakeUnfoldTransitionProvider()

    @Mock lateinit var transitionListener: TransitionProgressListener

    @Captor private lateinit var rotationListenerCaptor: ArgumentCaptor<RotationListener>

    lateinit var progressProvider: NaturalRotationUnfoldProgressProvider
    private lateinit var testableLooper: TestableLooper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        progressProvider =
            NaturalRotationUnfoldProgressProvider(context, rotationChangeProvider, sourceProvider)

        progressProvider.init()

        verify(rotationChangeProvider).addCallback(capture(rotationListenerCaptor))

        progressProvider.addCallback(transitionListener)
    }

    @Test
    fun testNaturalRotation0_sendTransitionStartedEvent_eventReceived() {
        onRotationChanged(Surface.ROTATION_0)

        sourceProvider.onTransitionStarted()

        verify(transitionListener).onTransitionStarted()
    }

    @Test
    fun testNaturalRotation0_sendTransitionProgressEvent_eventReceived() {
        onRotationChanged(Surface.ROTATION_0)

        sourceProvider.onTransitionProgress(0.5f)

        verify(transitionListener).onTransitionProgress(0.5f)
    }

    @Test
    fun testNotNaturalRotation90_sendTransitionStartedEvent_eventNotReceived() {
        onRotationChanged(Surface.ROTATION_90)

        sourceProvider.onTransitionStarted()

        verify(transitionListener, never()).onTransitionStarted()
    }

    @Test
    fun testNaturalRotation90_sendTransitionProgressEvent_eventNotReceived() {
        onRotationChanged(Surface.ROTATION_90)

        sourceProvider.onTransitionProgress(0.5f)

        verify(transitionListener, never()).onTransitionProgress(0.5f)
    }

    @Test
    fun testRotationBecameUnnaturalDuringTransition_sendsTransitionFinishedEvent() {
        onRotationChanged(Surface.ROTATION_0)
        sourceProvider.onTransitionStarted()
        clearInvocations(transitionListener)

        onRotationChanged(Surface.ROTATION_90)

        verify(transitionListener).onTransitionFinished()
    }

    @Test
    fun testRotationBecameNaturalDuringTransition_sendsTransitionStartedEvent() {
        onRotationChanged(Surface.ROTATION_90)
        sourceProvider.onTransitionStarted()
        clearInvocations(transitionListener)

        onRotationChanged(Surface.ROTATION_0)

        verify(transitionListener).onTransitionStarted()
    }

    private fun onRotationChanged(rotation: Int) {
        rotationListenerCaptor.value.onRotationChanged(rotation)
        testableLooper.processAllMessages()
    }
}
