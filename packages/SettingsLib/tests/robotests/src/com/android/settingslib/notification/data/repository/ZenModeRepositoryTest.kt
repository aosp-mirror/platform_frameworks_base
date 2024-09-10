/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.notification.data.repository

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Parcelable
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings.Global
import androidx.test.filters.SmallTest
import com.android.settingslib.flags.Flags
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.settingslib.notification.modes.ZenMode
import com.android.settingslib.notification.modes.ZenModesBackend
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@SmallTest
class ZenModeRepositoryTest {
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var context: Context

    @Mock private lateinit var notificationManager: NotificationManager

    @Mock private lateinit var zenModesBackend: ZenModesBackend

    @Mock private lateinit var contentResolver: ContentResolver

    @Captor private lateinit var receiverCaptor: ArgumentCaptor<BroadcastReceiver>

    @Captor private lateinit var zenModeObserverCaptor: ArgumentCaptor<ContentObserver>

    @Captor private lateinit var zenConfigObserverCaptor: ArgumentCaptor<ContentObserver>

    private lateinit var underTest: ZenModeRepository

    private val testScope: TestScope = TestScope()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            ZenModeRepositoryImpl(
                context,
                notificationManager,
                zenModesBackend,
                contentResolver,
                testScope.backgroundScope,
                testScope.testScheduler,
                backgroundHandler = null,
            )
    }

    @DisableFlags(Flags.FLAG_VOLUME_PANEL_BROADCAST_FIX)
    @Test
    fun consolidatedPolicyChanges_repositoryEmits_flagsOff() {
        testScope.runTest {
            val values = mutableListOf<NotificationManager.Policy?>()
            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy1)
            underTest.consolidatedNotificationPolicy
                .onEach { values.add(it) }
                .launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy2)
            triggerIntent(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            runCurrent()

            assertThat(values).containsExactly(null, testPolicy1, testPolicy2).inOrder()
        }
    }

    @EnableFlags(android.app.Flags.FLAG_MODES_API, Flags.FLAG_VOLUME_PANEL_BROADCAST_FIX)
    @Test
    fun consolidatedPolicyChanges_repositoryEmits_flagsOn() {
        testScope.runTest {
            val values = mutableListOf<NotificationManager.Policy?>()
            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy1)
            underTest.consolidatedNotificationPolicy
                .onEach { values.add(it) }
                .launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy2)
            triggerIntent(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
            runCurrent()

            assertThat(values).containsExactly(null, testPolicy1, testPolicy2).inOrder()
        }
    }

    @EnableFlags(android.app.Flags.FLAG_MODES_API, Flags.FLAG_VOLUME_PANEL_BROADCAST_FIX)
    @Test
    fun consolidatedPolicyChanges_repositoryEmitsFromExtras() {
        testScope.runTest {
            val values = mutableListOf<NotificationManager.Policy?>()
            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy1)
            underTest.consolidatedNotificationPolicy
                .onEach { values.add(it) }
                .launchIn(backgroundScope)
            runCurrent()

            triggerIntent(
                NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED,
                extras = mapOf(NotificationManager.EXTRA_NOTIFICATION_POLICY to testPolicy2))
            runCurrent()

            assertThat(values).containsExactly(null, testPolicy1, testPolicy2).inOrder()
        }
    }

    @Test
    fun zenModeChanges_repositoryEmits() {
        testScope.runTest {
            val values = mutableListOf<Int?>()
            `when`(notificationManager.zenMode).thenReturn(Global.ZEN_MODE_OFF)
            underTest.globalZenMode.onEach { values.add(it) }.launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.zenMode).thenReturn(Global.ZEN_MODE_ALARMS)
            triggerIntent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            runCurrent()

            assertThat(values)
                .containsExactly(null, Global.ZEN_MODE_OFF, Global.ZEN_MODE_ALARMS)
                .inOrder()
        }
    }

    @EnableFlags(android.app.Flags.FLAG_MODES_UI)
    @Test
    fun modesListEmitsOnSettingsChange() {
        testScope.runTest {
            val values = mutableListOf<List<ZenMode>>()
            val modes1 = listOf(TestModeBuilder().setId("One").build())
            `when`(zenModesBackend.modes).thenReturn(modes1)
            underTest.modes.onEach { values.add(it) }.launchIn(backgroundScope)
            runCurrent()

            // zen mode change triggers update
            val modes2 = listOf(TestModeBuilder().setId("Two").build())
            `when`(zenModesBackend.modes).thenReturn(modes2)
            triggerZenModeSettingUpdate()
            runCurrent()

            // zen config change also triggers update
            val modes3 = listOf(TestModeBuilder().setId("Three").build())
            `when`(zenModesBackend.modes).thenReturn(modes3)
            triggerZenConfigSettingUpdate()
            runCurrent()

            // setting update with no list change doesn't trigger update
            triggerZenModeSettingUpdate()
            runCurrent()

            assertThat(values).containsExactly(modes1, modes2, modes3).inOrder()
        }
    }

    @EnableFlags(android.app.Flags.FLAG_MODES_UI)
    @Test
    fun getModes_returnsModes() {
        val modesList = listOf(TestModeBuilder().setId("One").build())
        `when`(zenModesBackend.modes).thenReturn(modesList)

        assertThat(underTest.getModes()).isEqualTo(modesList)
    }

    private fun triggerIntent(action: String, extras: Map<String, Parcelable>? = null) {
        verify(context).registerReceiver(receiverCaptor.capture(), any(), any(), any())
        val intent = Intent(action)
        if (extras?.isNotEmpty() == true) {
            extras.forEach { (key, value) -> intent.putExtra(key, value) }
        }
        receiverCaptor.value.onReceive(context, intent)
    }

    private fun triggerZenModeSettingUpdate() {
        verify(contentResolver)
            .registerContentObserver(
                eq(Global.getUriFor(Global.ZEN_MODE)),
                eq(false),
                zenModeObserverCaptor.capture(),
            )
        zenModeObserverCaptor.value.onChange(false)
    }

    private fun triggerZenConfigSettingUpdate() {
        verify(contentResolver)
            .registerContentObserver(
                eq(Global.getUriFor(Global.ZEN_MODE_CONFIG_ETAG)),
                eq(false),
                zenConfigObserverCaptor.capture(),
            )
        zenConfigObserverCaptor.value.onChange(false)
    }

    private companion object {
        val testPolicy1 =
            NotificationManager.Policy(
                /* priorityCategories = */ 1,
                /* priorityCallSenders =*/ 1,
                /* priorityMessageSenders = */ 1,
            )
        val testPolicy2 =
            NotificationManager.Policy(
                /* priorityCategories = */ 2,
                /* priorityCallSenders =*/ 2,
                /* priorityMessageSenders = */ 2,
            )
    }
}
