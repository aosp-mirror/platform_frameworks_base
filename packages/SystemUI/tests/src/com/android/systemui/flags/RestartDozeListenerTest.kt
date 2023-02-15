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

package com.android.systemui.flags

import android.os.PowerManager
import android.test.suitebuilder.annotation.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class RestartDozeListenerTest : SysuiTestCase() {

    lateinit var restartDozeListener: RestartDozeListener

    val settings = FakeSettings()
    @Mock lateinit var statusBarStateController: StatusBarStateController
    @Mock lateinit var powerManager: PowerManager
    val clock = FakeSystemClock()
    lateinit var listener: StatusBarStateController.StateListener

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        restartDozeListener =
            RestartDozeListener(settings, statusBarStateController, powerManager, clock)

        val captor = ArgumentCaptor.forClass(StatusBarStateController.StateListener::class.java)
        restartDozeListener.init()
        verify(statusBarStateController).addCallback(captor.capture())
        listener = captor.value
    }

    @Test
    fun testStoreDreamState_onDreamingStarted() {
        listener.onDreamingChanged(true)
        assertThat(settings.getBool(RestartDozeListener.RESTART_NAP_KEY)).isTrue()
    }

    @Test
    fun testStoreDreamState_onDreamingStopped() {
        listener.onDreamingChanged(false)
        assertThat(settings.getBool(RestartDozeListener.RESTART_NAP_KEY)).isFalse()
    }

    @Test
    fun testRestoreDreamState_dreamingShouldStart() {
        settings.putBool(RestartDozeListener.RESTART_NAP_KEY, true)
        restartDozeListener.maybeRestartSleep()
        verify(powerManager).wakeUp(clock.uptimeMillis())
        verify(powerManager).goToSleep(clock.uptimeMillis())
    }

    @Test
    fun testRestoreDreamState_dreamingShouldNot() {
        settings.putBool(RestartDozeListener.RESTART_NAP_KEY, false)
        restartDozeListener.maybeRestartSleep()
        verify(powerManager, never()).wakeUp(anyLong())
        verify(powerManager, never()).goToSleep(anyLong())
    }
}
