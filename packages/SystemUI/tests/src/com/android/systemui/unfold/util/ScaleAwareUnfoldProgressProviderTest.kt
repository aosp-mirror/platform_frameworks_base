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

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.FakeUnfoldTransitionProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class ScaleAwareUnfoldProgressProviderTest : SysuiTestCase() {

    @Mock lateinit var sinkProvider: TransitionProgressListener

    private val sourceProvider = FakeUnfoldTransitionProvider()

    private lateinit var contentResolver: ContentResolver
    private lateinit var progressProvider: ScaleAwareTransitionProgressProvider

    private val animatorDurationScaleListenerCaptor =
        ArgumentCaptor.forClass(ContentObserver::class.java)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        contentResolver = spy(context.contentResolver)

        progressProvider = ScaleAwareTransitionProgressProvider(sourceProvider, contentResolver)

        verify(contentResolver)
            .registerContentObserver(any(), any(), animatorDurationScaleListenerCaptor.capture())

        progressProvider.addCallback(sinkProvider)
    }

    @Test
    fun onTransitionStarted_animationsEnabled_eventReceived() {
        setAnimationsEnabled(true)

        sourceProvider.onTransitionStarted()

        verify(sinkProvider).onTransitionStarted()
    }

    @Test
    fun onTransitionStarted_animationsNotEnabled_eventNotReceived() {
        setAnimationsEnabled(false)

        sourceProvider.onTransitionStarted()

        verifyNoMoreInteractions(sinkProvider)
    }

    @Test
    fun onTransitionEnd_animationsEnabled_eventReceived() {
        setAnimationsEnabled(true)

        sourceProvider.onTransitionFinished()

        verify(sinkProvider).onTransitionFinished()
    }

    @Test
    fun onTransitionEnd_animationsNotEnabled_eventNotReceived() {
        setAnimationsEnabled(false)

        sourceProvider.onTransitionFinished()

        verifyNoMoreInteractions(sinkProvider)
    }

    @Test
    fun onTransitionProgress_animationsEnabled_eventReceived() {
        setAnimationsEnabled(true)

        sourceProvider.onTransitionProgress(42f)

        verify(sinkProvider).onTransitionProgress(42f)
    }

    @Test
    fun onTransitionProgress_animationsNotEnabled_eventNotReceived() {
        setAnimationsEnabled(false)

        sourceProvider.onTransitionProgress(42f)

        verifyNoMoreInteractions(sinkProvider)
    }

    private fun setAnimationsEnabled(enabled: Boolean) {
        val durationScale =
            if (enabled) {
                1f
            } else {
                0f
            }

        // It uses [TestableSettingsProvider] and it will be cleared after the test
        Settings.Global.putString(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            durationScale.toString()
        )

        animatorDurationScaleListenerCaptor.value.dispatchChange(/* selfChange= */ false)
    }
}
