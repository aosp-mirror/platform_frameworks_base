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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.statusbar.notification.shared.NotificationViewFlipperPausing
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(NotificationViewFlipperPausing.FLAG_NAME)
class NotificationViewFlipperViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    val underTest
        get() = kosmos.notificationViewFlipperViewModel

    @Test
    fun testIsPaused_falseWhenViewingShade() =
        kosmos.testScope.runTest {
            val isPaused by collectLastValue(underTest.isPaused)

            // WHEN shade is open
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            runCurrent()

            // THEN view flippers should NOT be paused
            assertThat(isPaused).isFalse()
        }

    @Test
    fun testIsPaused_trueWhenViewingKeyguard() =
        kosmos.testScope.runTest {
            val isPaused by collectLastValue(underTest.isPaused)

            // WHEN on keyguard
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            runCurrent()

            // THEN view flippers should be paused
            assertThat(isPaused).isTrue()
        }

    @Test
    fun testIsPaused_trueWhenStartingToSleep() =
        kosmos.testScope.runTest {
            val isPaused by collectLastValue(underTest.isPaused)

            // WHEN shade is open
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.SHADE)
            // AND device is starting to go to sleep
            kosmos.fakePowerRepository.updateWakefulness(WakefulnessState.STARTING_TO_SLEEP)
            runCurrent()

            // THEN view flippers should be paused
            assertThat(isPaused).isTrue()
        }
}
