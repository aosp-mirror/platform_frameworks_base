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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.RingerModeTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class MuteQuickAffordanceCoreStartableTest : SysuiTestCase() {

    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var ringerModeTracker: RingerModeTracker
    @Mock
    private lateinit var userFileManager: UserFileManager
    @Mock
    private lateinit var keyguardQuickAffordanceRepository: KeyguardQuickAffordanceRepository

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private lateinit var underTest: MuteQuickAffordanceCoreStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val config: KeyguardQuickAffordanceConfig = mock()
        whenever(config.key).thenReturn(BuiltInKeyguardQuickAffordanceKeys.MUTE)

        val emission = MutableStateFlow(mapOf("testQuickAffordanceKey" to listOf(config)))
        whenever(keyguardQuickAffordanceRepository.selections).thenReturn(emission)

        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        underTest = MuteQuickAffordanceCoreStartable(
            userTracker,
            ringerModeTracker,
            userFileManager,
            keyguardQuickAffordanceRepository,
            testScope.backgroundScope,
            testDispatcher,
        )
    }

    @Test
    fun callToKeyguardQuickAffordanceRepository() = testScope.runTest {
        //given
        val ringerModeInternal = mock<MutableLiveData<Int>>()
        whenever(ringerModeTracker.ringerModeInternal).thenReturn(ringerModeInternal)

        //when
        underTest.start()
        runCurrent()

        //then
        verify(keyguardQuickAffordanceRepository).selections
        coroutineContext.cancelChildren()
    }

    @Test
    fun ringerModeIsChangedToSILENT_doNotSaveToSharedPreferences() = testScope.runTest {
        //given
        val ringerModeInternal = mock<MutableLiveData<Int>>()
        val observerCaptor = argumentCaptor<Observer<Int>>()
        whenever(ringerModeTracker.ringerModeInternal).thenReturn(ringerModeInternal)

        //when
        underTest.start()
        runCurrent()
        verify(ringerModeInternal).observeForever(observerCaptor.capture())
        observerCaptor.value.onChanged(AudioManager.RINGER_MODE_SILENT)

        //then
        verifyZeroInteractions(userFileManager)
        coroutineContext.cancelChildren()
    }

    @Test
    fun ringerModeInternalChangesToSomethingNotSILENT_isSetInSharedpreferences() = testScope.runTest {
        //given
        val newRingerMode = 99
        val observerCaptor = argumentCaptor<Observer<Int>>()
        val ringerModeInternal = mock<MutableLiveData<Int>>()
        val sharedPrefs = context.getSharedPreferences("quick_affordance_mute_ringer_mode_cache_test", Context.MODE_PRIVATE)
        whenever(ringerModeTracker.ringerModeInternal).thenReturn(ringerModeInternal)
        whenever(
            userFileManager.getSharedPreferences(eq("quick_affordance_mute_ringer_mode_cache"), any(), any())
        ).thenReturn(sharedPrefs)

        //when
        underTest.start()
        runCurrent()
        verify(ringerModeInternal).observeForever(observerCaptor.capture())
        observerCaptor.value.onChanged(newRingerMode)
        runCurrent()
        val result = sharedPrefs.getInt("key_last_non_silent_ringer_mode", -1)

        //then
        assertEquals(newRingerMode, result)
        coroutineContext.cancelChildren()
    }

    @Test
    fun MUTEisInSelections_observeRingerModeInternal() = testScope.runTest {
        //given
        val ringerModeInternal = mock<MutableLiveData<Int>>()
        whenever(ringerModeTracker.ringerModeInternal).thenReturn(ringerModeInternal)

        //when
        underTest.start()
        runCurrent()

        //then
        verify(ringerModeInternal).observeForever(any())
        coroutineContext.cancelChildren()
    }

    @Test
    fun MUTEisInSelections2x_observeRingerModeInternal() = testScope.runTest {
        //given
        val config: KeyguardQuickAffordanceConfig = mock()
        whenever(config.key).thenReturn(BuiltInKeyguardQuickAffordanceKeys.MUTE)
        val emission = MutableStateFlow(mapOf("testKey" to listOf(config), "testkey2" to listOf(config)))
        whenever(keyguardQuickAffordanceRepository.selections).thenReturn(emission)
        val ringerModeInternal = mock<MutableLiveData<Int>>()
        whenever(ringerModeTracker.ringerModeInternal).thenReturn(ringerModeInternal)

        //when
        underTest.start()
        runCurrent()

        //then
        verify(ringerModeInternal).observeForever(any())
        coroutineContext.cancelChildren()
    }

    @Test
    fun MUTEisNotInSelections_stopObservingRingerModeInternal() = testScope.runTest {
        //given
        val config: KeyguardQuickAffordanceConfig = mock()
        whenever(config.key).thenReturn("notmutequickaffordance")
        val emission = MutableStateFlow(mapOf("testKey" to listOf(config)))
        whenever(keyguardQuickAffordanceRepository.selections).thenReturn(emission)
        val ringerModeInternal = mock<MutableLiveData<Int>>()
        whenever(ringerModeTracker.ringerModeInternal).thenReturn(ringerModeInternal)

        //when
        underTest.start()
        runCurrent()

        //then
        verify(ringerModeInternal).removeObserver(any())
        coroutineContext.cancelChildren()
    }
}
