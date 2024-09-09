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
package com.android.systemui.bluetooth.qsdialog

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AudioSharingRepositoryTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var profileManager: LocalBluetoothProfileManager
    @Mock private lateinit var leAudioBroadcastProfile: LocalBluetoothLeBroadcast
    private val kosmos = testKosmos()
    private lateinit var underTest: AudioSharingRepository

    @Before
    fun setUp() {
        whenever(kosmos.localBluetoothManager.profileManager).thenReturn(profileManager)
        whenever(profileManager.leAudioBroadcastProfile).thenReturn(leAudioBroadcastProfile)
        underTest = AudioSharingRepositoryImpl(kosmos.localBluetoothManager, kosmos.testDispatcher)
    }

    @Test
    fun testSwitchActive() =
        with(kosmos) {
            testScope.runTest {
                underTest.setActive(cachedBluetoothDevice)
                verify(cachedBluetoothDevice).setActive()
            }
        }

    @Test
    fun testStartAudioSharing() =
        with(kosmos) {
            testScope.runTest {
                underTest.startAudioSharing()
                verify(leAudioBroadcastProfile).startPrivateBroadcast()
            }
        }
}
