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

package com.android.systemui.power.data.repository

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.isNull
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class PowerRepositoryImplTest : SysuiTestCase() {

    private val systemClock = FakeSystemClock()

    @Mock private lateinit var manager: PowerManager
    @Mock private lateinit var dispatcher: BroadcastDispatcher
    @Captor private lateinit var receiverCaptor: ArgumentCaptor<BroadcastReceiver>
    @Captor private lateinit var filterCaptor: ArgumentCaptor<IntentFilter>

    private lateinit var underTest: PowerRepositoryImpl

    private var isInteractive = true

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        isInteractive = true
        whenever(manager.isInteractive).then { isInteractive }

        underTest =
            PowerRepositoryImpl(
                manager,
                context.applicationContext,
                systemClock,
                dispatcher,
            )
    }

    @Test
    fun isInteractive_registersForBroadcasts() =
        runBlocking(IMMEDIATE) {
            val job = underTest.isInteractive.onEach {}.launchIn(this)

            verifyRegistered()
            assertThat(filterCaptor.value.hasAction(Intent.ACTION_SCREEN_ON)).isTrue()
            assertThat(filterCaptor.value.hasAction(Intent.ACTION_SCREEN_OFF)).isTrue()

            job.cancel()
        }

    @Test
    fun isInteractive_unregistersFromBroadcasts() =
        runBlocking(IMMEDIATE) {
            val job = underTest.isInteractive.onEach {}.launchIn(this)
            verifyRegistered()

            job.cancel()

            verify(dispatcher).unregisterReceiver(receiverCaptor.value)
        }

    @Test
    fun isInteractive_emitsInitialTrueValueIfScreenWasOn() =
        runBlocking(IMMEDIATE) {
            isInteractive = true
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)

            verifyRegistered()

            assertThat(value).isTrue()
            job.cancel()
        }

    @Test
    fun isInteractive_emitsInitialFalseValueIfScreenWasOff() =
        runBlocking(IMMEDIATE) {
            isInteractive = false
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)

            verifyRegistered()

            assertThat(value).isFalse()
            job.cancel()
        }

    @Test
    fun isInteractive_emitsTrueWhenTheScreenTurnsOn() =
        runBlocking(IMMEDIATE) {
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)
            verifyRegistered()

            isInteractive = true
            receiverCaptor.value.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))

            assertThat(value).isTrue()
            job.cancel()
        }

    @Test
    fun isInteractive_emitsFalseWhenTheScreenTurnsOff() =
        runBlocking(IMMEDIATE) {
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)
            verifyRegistered()

            isInteractive = false
            receiverCaptor.value.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))

            assertThat(value).isFalse()
            job.cancel()
        }

    @Test
    fun isInteractive_emitsCorrectlyOverTime() =
        runBlocking(IMMEDIATE) {
            val values = mutableListOf<Boolean>()
            val job = underTest.isInteractive.onEach(values::add).launchIn(this)
            verifyRegistered()

            isInteractive = false
            receiverCaptor.value.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
            isInteractive = true
            receiverCaptor.value.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
            isInteractive = false
            receiverCaptor.value.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))

            assertThat(values).isEqualTo(listOf(true, false, true, false))
            job.cancel()
        }

    @Test
    fun wakeUp_notifiesPowerManager() {
        systemClock.setUptimeMillis(345000)

        underTest.wakeUp("fakeWhy", PowerManager.WAKE_REASON_GESTURE)

        val reasonCaptor = argumentCaptor<String>()
        verify(manager)
            .wakeUp(eq(345000L), eq(PowerManager.WAKE_REASON_GESTURE), capture(reasonCaptor))
        assertThat(reasonCaptor.value).contains("fakeWhy")
    }

    @Test
    fun wakeUp_usesApplicationPackageName() {
        underTest.wakeUp("fakeWhy", PowerManager.WAKE_REASON_GESTURE)

        val reasonCaptor = argumentCaptor<String>()
        verify(manager).wakeUp(any(), any(), capture(reasonCaptor))
        assertThat(reasonCaptor.value).contains(context.applicationContext.packageName)
    }

    @Test
    fun userActivity_notifiesPowerManager() {
        systemClock.setUptimeMillis(345000)

        underTest.userTouch()

        val flagsCaptor = argumentCaptor<Int>()
        verify(manager)
            .userActivity(
                eq(345000L),
                eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                capture(flagsCaptor)
            )
        assertThat(flagsCaptor.value).isNotEqualTo(PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS)
        assertThat(flagsCaptor.value).isNotEqualTo(PowerManager.USER_ACTIVITY_FLAG_INDIRECT)
    }

    @Test
    fun userActivity_notifiesPowerManager_noChangeLightsTrue() {
        systemClock.setUptimeMillis(345000)

        underTest.userTouch(noChangeLights = true)

        val flagsCaptor = argumentCaptor<Int>()
        verify(manager)
            .userActivity(
                eq(345000L),
                eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                capture(flagsCaptor)
            )
        assertThat(flagsCaptor.value).isEqualTo(PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS)
    }

    private fun verifyRegistered() {
        // We must verify with all arguments, even those that are optional because they have default
        // values because Mockito is forcing us to. Once we can use mockito-kotlin, we should be
        // able to remove this.
        verify(dispatcher)
            .registerReceiver(
                capture(receiverCaptor),
                capture(filterCaptor),
                isNull(),
                isNull(),
                anyInt(),
                isNull(),
            )
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
