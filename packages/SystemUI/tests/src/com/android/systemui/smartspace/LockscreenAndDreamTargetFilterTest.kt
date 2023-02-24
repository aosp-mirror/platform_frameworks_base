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
package com.android.systemui.smartspace

import android.app.smartspace.SmartspaceTarget
import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.smartspace.filters.LockscreenAndDreamTargetFilter
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class LockscreenAndDreamTargetFilterTest : SysuiTestCase() {
    @Mock
    private lateinit var secureSettings: SecureSettings

    @Mock
    private lateinit var userTracker: UserTracker

    @Mock
    private lateinit var execution: Execution

    @Mock
    private lateinit var handler: Handler

    @Mock
    private lateinit var contentResolver: ContentResolver

    @Mock
    private lateinit var uiExecution: Executor

    @Mock
    private lateinit var userHandle: UserHandle

    @Mock
    private lateinit var listener: SmartspaceTargetFilter.Listener

    @Mock
    private lateinit var lockScreenAllowPrivateNotificationsUri: Uri

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(userTracker.userHandle).thenReturn(userHandle)
        `when`(secureSettings
                .getUriFor(eq(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS)))
                .thenReturn(lockScreenAllowPrivateNotificationsUri)
    }

    /**
     * Ensures {@link Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS} is
     * tracked.
     */
    @Test
    fun testLockscreenAllowPrivateNotifications() {
        var setting = Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
        `when`(secureSettings
                .getIntForUser(eq(setting) ?: setting, anyInt(), anyInt()))
                .thenReturn(0)
        var filter = LockscreenAndDreamTargetFilter(secureSettings, userTracker, execution, handler,
                contentResolver, uiExecution)

        filter.addListener(listener)
        var smartspaceTarget = mock(SmartspaceTarget::class.java)
        `when`(smartspaceTarget.userHandle).thenReturn(userHandle)
        `when`(smartspaceTarget.isSensitive).thenReturn(true)
        assertThat(filter.filterSmartspaceTarget(smartspaceTarget)).isFalse()

        var settingCaptor = ArgumentCaptor.forClass(ContentObserver::class.java)

        verify(contentResolver).registerContentObserver(eq(lockScreenAllowPrivateNotificationsUri),
                anyBoolean(), settingCaptor.capture(), anyInt())

        `when`(secureSettings
                .getIntForUser(eq(setting) ?: setting, anyInt(), anyInt()))
                .thenReturn(1)

        clearInvocations(listener)
        settingCaptor.value.onChange(false, mock(Uri::class.java))
        verify(listener, atLeast(1)).onCriteriaChanged()
        assertThat(filter.filterSmartspaceTarget(smartspaceTarget)).isTrue()
    }

    /**
     * Ensures user switches are tracked.
     */
    @Test
    fun testUserSwitchCallback() {
        var setting = Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
        `when`(secureSettings
                .getIntForUser(eq(setting) ?: setting, anyInt(), anyInt()))
                .thenReturn(0)
        var filter = LockscreenAndDreamTargetFilter(secureSettings, userTracker, execution, handler,
                contentResolver, uiExecution)

        filter.addListener(listener)

        var userTrackerCallback = withArgCaptor<UserTracker.Callback> {
            verify(userTracker).addCallback(capture(), any())
        }

        clearInvocations(listener)
        userTrackerCallback.onUserChanged(0, context)

        verify(listener).onCriteriaChanged()
    }
}
