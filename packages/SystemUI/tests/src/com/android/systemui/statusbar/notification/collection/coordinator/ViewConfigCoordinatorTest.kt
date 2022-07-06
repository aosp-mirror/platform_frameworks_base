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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationGutsManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ViewConfigCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: ViewConfigCoordinator

    // Captured
    private lateinit var userChangedListener: UserChangedListener
    private lateinit var configurationListener: ConfigurationController.ConfigurationListener
    private lateinit var keyguardUpdateMonitorCallback: KeyguardUpdateMonitorCallback

    // Mocks
    private val entry: NotificationEntry = mock()
    private val row: ExpandableNotificationRow = mock()
    private val pipeline: NotifPipeline = mock()
    private val configurationController: ConfigurationController = mock()
    private val lockscreenUserManager: NotificationLockscreenUserManager = mock()
    private val gutsManager: NotificationGutsManager = mock()
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor = mock()

    @Before
    fun setUp() {
        whenever(pipeline.allNotifs).thenReturn(listOf(entry))
        whenever(entry.row).thenReturn(row)
        coordinator = ViewConfigCoordinator(
            configurationController,
            lockscreenUserManager,
            gutsManager,
            keyguardUpdateMonitor)
        coordinator.attach(pipeline)
        userChangedListener = withArgCaptor {
            verify(lockscreenUserManager).addUserChangedListener(capture())
        }
        configurationListener = withArgCaptor {
            verify(configurationController).addCallback(capture())
        }
        keyguardUpdateMonitorCallback = withArgCaptor {
            verify(keyguardUpdateMonitor).registerCallback(capture())
        }
    }

    @Test
    fun uiModeChangePropagatesToRow() {
        configurationListener.onUiModeChanged()
        verify(entry).row
        verify(row).onUiModeChanged()
        verifyNoMoreInteractions(entry, row)
    }

    @Test
    fun themeChangePropagatesToEntry() {
        configurationListener.onThemeChanged()
        verify(entry).onDensityOrFontScaleChanged()
        verify(entry).areGutsExposed()
        verifyNoMoreInteractions(entry, row)
    }

    @Test
    fun densityChangePropagatesToEntry() {
        configurationListener.onDensityOrFontScaleChanged()
        verify(entry).onDensityOrFontScaleChanged()
        verify(entry).areGutsExposed()
        verifyNoMoreInteractions(entry, row)
    }

    @Test
    fun switchingUserDefersChangesWithUserChangedEventAfter() {
        // GIVEN switching users
        keyguardUpdateMonitorCallback.onUserSwitching(10)

        // WHEN configuration changes happen
        configurationListener.onUiModeChanged()
        configurationListener.onDensityOrFontScaleChanged()
        configurationListener.onThemeChanged()

        // VERIFY no changes are propagated
        verifyNoMoreInteractions(entry, row)

        // WHEN user switch completes
        keyguardUpdateMonitorCallback.onUserSwitchComplete(10)

        // VERIFY the changes propagate
        verify(entry).row
        verify(row).onUiModeChanged()
        verify(entry).onDensityOrFontScaleChanged()
        verify(entry).areGutsExposed()
        verifyNoMoreInteractions(entry, row)
        clearInvocations(entry, row)

        // WHEN user change happens after the switching window
        userChangedListener.onUserChanged(10)

        // VERIFY user change itself does not re-trigger updates
        verifyNoMoreInteractions(entry, row)
    }

    @Test
    fun switchingUserDefersChangesWithUserChangedEventDuring() {
        // GIVEN switching users
        keyguardUpdateMonitorCallback.onUserSwitching(10)

        // WHEN configuration changes happen
        configurationListener.onUiModeChanged()
        configurationListener.onDensityOrFontScaleChanged()
        configurationListener.onThemeChanged()

        // VERIFY no changes are propagated
        verifyNoMoreInteractions(entry, row)

        // WHEN user change happens during the switching window
        userChangedListener.onUserChanged(10)

        // VERIFY the changes propagate
        verify(entry).row
        verify(row).onUiModeChanged()
        verify(entry).onDensityOrFontScaleChanged()
        verify(entry).areGutsExposed()
        verifyNoMoreInteractions(entry, row)
        clearInvocations(entry, row)

        // WHEN user switch completes
        keyguardUpdateMonitorCallback.onUserSwitchComplete(10)

        // VERIFY the switching window closing does not re-propagate
        verifyNoMoreInteractions(entry, row)
    }

    @Test
    fun switchingUserDefersChangesWithUserChangedEventBefore() {
        // WHEN user change happens before configuration changes or switching window
        userChangedListener.onUserChanged(10)

        // VERIFY no changes happen
        verifyNoMoreInteractions(entry, row)

        // WHEN switching users then configuration changes happen
        keyguardUpdateMonitorCallback.onUserSwitching(10)

        configurationListener.onUiModeChanged()
        configurationListener.onDensityOrFontScaleChanged()
        configurationListener.onThemeChanged()

        // VERIFY no changes happen
        verifyNoMoreInteractions(entry, row)

        // WHEN user switch completes
        keyguardUpdateMonitorCallback.onUserSwitchComplete(10)

        // VERIFY the changes propagate
        verify(entry).row
        verify(row).onUiModeChanged()
        verify(entry).onDensityOrFontScaleChanged()
        verify(entry).areGutsExposed()
        verifyNoMoreInteractions(entry, row)
        clearInvocations(entry, row)
    }
}
