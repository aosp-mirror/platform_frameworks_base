/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.dreams

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DreamOverlayCallbackControllerTest : SysuiTestCase() {

    @Mock private lateinit var callback: DreamOverlayCallbackController.Callback

    private lateinit var underTest: DreamOverlayCallbackController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest = DreamOverlayCallbackController()
    }

    @Test
    fun onWakeUpInvokesCallback() {
        underTest.onStartDream()
        assertThat(underTest.isDreaming).isEqualTo(true)

        underTest.addCallback(callback)
        underTest.onWakeUp()
        verify(callback).onWakeUp()
        assertThat(underTest.isDreaming).isEqualTo(false)

        // Adding twice should not invoke twice
        reset(callback)
        underTest.addCallback(callback)
        underTest.onWakeUp()
        verify(callback, times(1)).onWakeUp()

        // After remove, no call to callback
        reset(callback)
        underTest.removeCallback(callback)
        underTest.onWakeUp()
        verify(callback, never()).onWakeUp()
    }

    @Test
    fun onStartDreamInvokesCallback() {
        underTest.addCallback(callback)

        assertThat(underTest.isDreaming).isEqualTo(false)

        underTest.onStartDream()
        verify(callback).onStartDream()
        assertThat(underTest.isDreaming).isEqualTo(true)

        // Adding twice should not invoke twice
        reset(callback)
        underTest.addCallback(callback)
        underTest.onStartDream()
        verify(callback, times(1)).onStartDream()

        // After remove, no call to callback
        reset(callback)
        underTest.removeCallback(callback)
        underTest.onStartDream()
        verify(callback, never()).onStartDream()
    }
}
