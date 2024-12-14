/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.disableflags.data.repository

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE
import android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS
import android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ALERTS
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.res.R
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DisableFlagsRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: DisableFlagsRepository

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val commandQueue: CommandQueue = mock()
    private val configurationController: ConfigurationController = mock()
    private val remoteInputQuickSettingsDisabler =
        RemoteInputQuickSettingsDisabler(
            context,
            commandQueue,
            ResourcesSplitShadeStateController(),
            configurationController,
        )
    private val logBuffer = LogBufferFactory(DumpManager(), mock()).create("buffer", 10)
    private val disableFlagsLogger = DisableFlagsLogger()

    @Before
    fun setUp() {
        underTest =
            DisableFlagsRepositoryImpl(
                commandQueue,
                DISPLAY_ID,
                testScope.backgroundScope,
                remoteInputQuickSettingsDisabler,
                logBuffer,
                disableFlagsLogger,
            )
    }

    @Test
    fun disableFlags_initialValue_none() {
        assertThat(underTest.disableFlags.value)
            .isEqualTo(DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE, animate = false))
    }

    @Test
    fun disableFlags_noSubscribers_callbackStillRegistered() =
        testScope.runTest { verify(commandQueue).addCallback(any()) }

    @Test
    fun disableFlags_notifAlertsNotDisabled_notifAlertsEnabledTrue() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isTrue()
        }

    @Test
    fun disableFlags_notifAlertsDisabled_notifAlertsEnabledFalse() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )

            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isFalse()
        }

    @Test
    fun disableFlags_notifAlertsDisabled_differentDisplay_notifAlertsEnabledTrue() =
        testScope.runTest {
            val wrongDisplayId = DISPLAY_ID + 10

            getCommandQueueCallback()
                .disable(
                    wrongDisplayId,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )

            // THEN our repo reports them as still enabled
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isTrue()
        }

    @Test
    fun disableFlags_shadeNotDisabled_shadeEnabledTrue() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isShadeEnabled()).isTrue()
        }

    @Test
    fun disableFlags_shadeDisabled_shadeEnabledFalse() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NONE,
                    DISABLE2_NOTIFICATION_SHADE,
                    /* animate= */ false,
                )

            assertThat(underTest.disableFlags.value.isShadeEnabled()).isFalse()
        }

    @Test
    fun disableFlags_shadeDisabled_differentDisplay_shadeEnabledTrue() =
        testScope.runTest {
            val wrongDisplayId = DISPLAY_ID + 10

            getCommandQueueCallback()
                .disable(
                    wrongDisplayId,
                    DISABLE_NONE,
                    DISABLE2_NOTIFICATION_SHADE,
                    /* animate= */ false,
                )

            // THEN our repo reports them as still enabled
            assertThat(underTest.disableFlags.value.isShadeEnabled()).isTrue()
        }

    @Test
    fun disableFlags_quickSettingsNotDisabled_quickSettingsEnabledTrue() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isQuickSettingsEnabled()).isTrue()
        }

    @Test
    fun disableFlags_quickSettingsDisabled_quickSettingsEnabledFalse() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_QUICK_SETTINGS, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isQuickSettingsEnabled()).isFalse()
        }

    @Test
    fun disableFlags_quickSettingsDisabled_differentDisplay_quickSettingsEnabledTrue() =
        testScope.runTest {
            val wrongDisplayId = DISPLAY_ID + 10

            getCommandQueueCallback()
                .disable(
                    wrongDisplayId,
                    DISABLE_NONE,
                    DISABLE2_QUICK_SETTINGS,
                    /* animate= */ false,
                )

            // THEN our repo reports them as still enabled
            assertThat(underTest.disableFlags.value.isQuickSettingsEnabled()).isTrue()
        }

    @Test
    fun disableFlags_remoteInputActive_quickSettingsEnabledFalse() =
        testScope.runTest {
            // WHEN remote input is set up to be active
            val configuration = Configuration(mContext.resources.configuration)
            configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
            mContext.orCreateTestableResources.addOverride(
                R.bool.config_use_split_notification_shade,
                /* value= */ false,
            )
            remoteInputQuickSettingsDisabler.setRemoteInputActive(true)
            remoteInputQuickSettingsDisabler.onConfigChanged(configuration)

            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            // THEN quick settings is disabled (even if the disable flags don't say so)
            assertThat(underTest.disableFlags.value.isQuickSettingsEnabled()).isFalse()
        }

    @Test
    fun disableFlags_clockDisabled() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_CLOCK, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isClockEnabled).isFalse()
        }

    @Test
    fun disableFlags_clockEnabled() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isClockEnabled).isTrue()
        }

    @Test
    fun disableFlags_notificationIconsDisabled() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ICONS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )

            assertThat(underTest.disableFlags.value.areNotificationIconsEnabled).isFalse()
        }

    @Test
    fun disableFlags_notificationIconsEnabled() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.areNotificationIconsEnabled).isTrue()
        }

    @Test
    fun disableFlags_systemInfoDisabled_viaDisable1() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_SYSTEM_INFO, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isSystemInfoEnabled).isFalse()
        }

    @Test
    fun disableFlags_systemInfoDisabled_viaDisable2() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_SYSTEM_ICONS, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isSystemInfoEnabled).isFalse()
        }

    @Test
    fun disableFlags_systemInfoEnabled() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.isSystemInfoEnabled).isTrue()
        }

    @Test
    fun disableFlags_reactsToChanges() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isFalse()

            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_CLOCK, // Unrelated to notifications
                    DISABLE2_NONE,
                    /* animate= */ false,
                )
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isTrue()

            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_QUICK_SETTINGS or DISABLE2_NOTIFICATION_SHADE,
                    /* animate= */ false,
                )
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isFalse()
            assertThat(underTest.disableFlags.value.isShadeEnabled()).isFalse()
            assertThat(underTest.disableFlags.value.isQuickSettingsEnabled()).isFalse()
        }

    @Test
    fun disableFlags_animateFalse() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )

            assertThat(underTest.disableFlags.value.animate).isFalse()
        }

    @Test
    fun disableFlags_animateTrue() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ true,
                )

            assertThat(underTest.disableFlags.value.animate).isTrue()
        }

    private fun getCommandQueueCallback(): CommandQueue.Callbacks {
        val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
        verify(commandQueue).addCallback(callbackCaptor.capture())
        return callbackCaptor.value
    }

    private companion object {
        const val DISPLAY_ID = 1
    }
}
