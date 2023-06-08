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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.media.AudioManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.RingerModeTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class MuteQuickAffordanceConfigTest : SysuiTestCase() {

    private lateinit var underTest: MuteQuickAffordanceConfig
    @Mock
    private lateinit var ringerModeTracker: RingerModeTracker
    @Mock
    private lateinit var audioManager: AudioManager
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var userFileManager: UserFileManager

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        whenever(userTracker.userContext).thenReturn(context)
        whenever(userFileManager.getSharedPreferences(any(), any(), any()))
                .thenReturn(context.getSharedPreferences("mutequickaffordancetest", Context.MODE_PRIVATE))

        underTest = MuteQuickAffordanceConfig(
                context,
                userTracker,
                userFileManager,
                ringerModeTracker,
                audioManager,
                testScope.backgroundScope,
                testDispatcher,
                testDispatcher,
        )
    }

    @Test
    fun `picker state - volume fixed - not available`() = testScope.runTest {
        //given
        whenever(audioManager.isVolumeFixed).thenReturn(true)

        //when
        val result = underTest.getPickerScreenState()

        //then
        assertEquals(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice, result)
    }

    @Test
    fun `picker state - volume not fixed - available`() = testScope.runTest {
        //given
        whenever(audioManager.isVolumeFixed).thenReturn(false)

        //when
        val result = underTest.getPickerScreenState()

        //then
        assertEquals(KeyguardQuickAffordanceConfig.PickerScreenState.Default(), result)
    }

    @Test
    fun `triggered - state was previously NORMAL - currently SILENT - move to previous state`() = testScope.runTest {
        //given
        val ringerModeCapture = argumentCaptor<Int>()
        whenever(audioManager.ringerModeInternal).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        underTest.onTriggered(null)
        whenever(audioManager.ringerModeInternal).thenReturn(AudioManager.RINGER_MODE_SILENT)

        //when
        val result = underTest.onTriggered(null)
        runCurrent()
        verify(audioManager, times(2)).ringerModeInternal = ringerModeCapture.capture()

        //then
        assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, ringerModeCapture.value)
    }

    @Test
    fun `triggered - state is not SILENT - move to SILENT ringer`() = testScope.runTest {
        //given
        val ringerModeCapture = argumentCaptor<Int>()
        whenever(audioManager.ringerModeInternal).thenReturn(AudioManager.RINGER_MODE_NORMAL)

        //when
        val result = underTest.onTriggered(null)
        runCurrent()
        verify(audioManager).ringerModeInternal = ringerModeCapture.capture()

        //then
        assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
        assertEquals(AudioManager.RINGER_MODE_SILENT, ringerModeCapture.value)
    }
}