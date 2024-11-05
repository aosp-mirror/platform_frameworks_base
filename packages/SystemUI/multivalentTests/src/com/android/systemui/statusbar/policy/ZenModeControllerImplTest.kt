/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.statusbar.policy

import android.app.NotificationManager
import android.os.Handler
import android.provider.Settings
import android.service.notification.ZenModeConfig
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeGlobalSettings
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class ZenModeControllerImplTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var testableLooper: TestableLooper
    private lateinit var controller: ZenModeControllerImpl

    private val globalSettings = kosmos.fakeGlobalSettings
    private val config: ZenModeConfig = mock<ZenModeConfig>()
    private val mNm: NotificationManager = mock<NotificationManager>()

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        mContext.addMockSystemService(NotificationManager::class.java, mNm)
        whenever(mNm.zenModeConfig).thenReturn(config)

        controller =
            ZenModeControllerImpl(
                mContext,
                Handler.createAsync(testableLooper.looper),
                kosmos.broadcastDispatcher,
                kosmos.dumpManager,
                globalSettings,
                kosmos.userTracker,
            )
    }

    @Test
    fun testRemoveDuringCallback() {
        val callback =
            object : ZenModeController.Callback {
                override fun onConfigChanged(config: ZenModeConfig) {
                    controller.removeCallback(this)
                }
            }

        controller.addCallback(callback)
        val mockCallback = Mockito.mock(ZenModeController.Callback::class.java)
        controller.addCallback(mockCallback)
        controller.fireConfigChanged(config)
        Mockito.verify(mockCallback).onConfigChanged(ArgumentMatchers.eq(config))
    }

    @Test
    fun testAreNotificationsHiddenInShade_zenOffShadeSuppressed() {
        config.suppressedVisualEffects =
            NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
        controller.updateZenMode(Settings.Global.ZEN_MODE_OFF)
        controller.updateZenModeConfig()
        assertThat(controller.areNotificationsHiddenInShade()).isFalse()
    }

    @Test
    fun testAreNotificationsHiddenInShade_zenOnShadeNotSuppressed() {
        val policy =
            NotificationManager.Policy(
                0,
                0,
                0,
                NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR,
            )
        whenever(mNm.consolidatedNotificationPolicy).thenReturn(policy)
        controller.updateConsolidatedNotificationPolicy()
        controller.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
        assertThat(controller.areNotificationsHiddenInShade()).isFalse()
    }

    @Test
    fun testAreNotificationsHiddenInShade_zenOnShadeSuppressed() {
        val policy =
            NotificationManager.Policy(
                0,
                0,
                0,
                NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST,
            )
        whenever(mNm.consolidatedNotificationPolicy).thenReturn(policy)
        controller.updateConsolidatedNotificationPolicy()
        controller.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
        assertThat(controller.areNotificationsHiddenInShade()).isTrue()
    }

    @Test
    fun testModeChange() =
        testScope.runTest {
            val states =
                listOf(
                    Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                    Settings.Global.ZEN_MODE_NO_INTERRUPTIONS,
                    Settings.Global.ZEN_MODE_ALARMS,
                    Settings.Global.ZEN_MODE_ALARMS,
                )

            for (state in states) {
                globalSettings.putInt(Settings.Global.ZEN_MODE, state)
                testScope.runCurrent()
                testableLooper.processAllMessages()
                assertThat(controller.zen).isEqualTo(state)
            }
        }

    @Test
    fun testModeChange_callbackNotified() =
        testScope.runTest {
            val currentState = AtomicInteger(-1)

            val callback: ZenModeController.Callback =
                object : ZenModeController.Callback {
                    override fun onZenChanged(zen: Int) {
                        currentState.set(zen)
                    }
                }

            controller.addCallback(callback)

            val states =
                listOf(
                    Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                    Settings.Global.ZEN_MODE_NO_INTERRUPTIONS,
                    Settings.Global.ZEN_MODE_ALARMS,
                    Settings.Global.ZEN_MODE_ALARMS,
                )

            for (state in states) {
                globalSettings.putInt(Settings.Global.ZEN_MODE, state)
                testScope.runCurrent()
                testableLooper.processAllMessages()
                assertThat(currentState.get()).isEqualTo(state)
            }
        }

    @Test
    fun testCallbackRemovedWhileDispatching_doesntCrash() =
        testScope.runTest {
            val remove = AtomicBoolean(false)
            globalSettings.putInt(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF)
            testableLooper.processAllMessages()
            val callback: ZenModeController.Callback =
                object : ZenModeController.Callback {
                    override fun onZenChanged(zen: Int) {
                        if (remove.get()) {
                            controller.removeCallback(this)
                        }
                    }
                }
            controller.addCallback(callback)
            controller.addCallback(object : ZenModeController.Callback {})

            remove.set(true)

            globalSettings.putInt(
                Settings.Global.ZEN_MODE,
                Settings.Global.ZEN_MODE_NO_INTERRUPTIONS,
            )
            testScope.runCurrent()
            testableLooper.processAllMessages()
        }
}
