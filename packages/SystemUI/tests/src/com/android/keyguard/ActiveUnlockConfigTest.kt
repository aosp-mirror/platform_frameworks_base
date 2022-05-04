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

package com.android.keyguard

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.settings.SecureSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class ActiveUnlockConfigTest : SysuiTestCase() {
    private val fakeWakeUri = Uri.Builder().appendPath("wake").build()
    private val fakeUnlockIntentUri = Uri.Builder().appendPath("unlock-intent").build()
    private val fakeBioFailUri = Uri.Builder().appendPath("bio-fail").build()

    @Mock
    private lateinit var secureSettings: SecureSettings

    @Mock
    private lateinit var contentResolver: ContentResolver

    @Mock
    private lateinit var handler: Handler

    @Mock
    private lateinit var dumpManager: DumpManager

    @Captor
    private lateinit var settingsObserverCaptor: ArgumentCaptor<ContentObserver>

    private lateinit var activeUnlockConfig: ActiveUnlockConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(secureSettings.getUriFor(Settings.Secure.ACTIVE_UNLOCK_ON_WAKE))
                .thenReturn(fakeWakeUri)
        `when`(secureSettings.getUriFor(Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT))
                .thenReturn(fakeUnlockIntentUri)
        `when`(secureSettings.getUriFor(Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL))
                .thenReturn(fakeBioFailUri)

        activeUnlockConfig = ActiveUnlockConfig(
                handler,
                secureSettings,
                contentResolver,
                dumpManager
        )
    }

    @Test
    fun testRegsitersForSettingsChanges() {
        verifyRegisterSettingObserver()
    }

    @Test
    fun testOnWakeupSettingChanged() {
        verifyRegisterSettingObserver()

        // GIVEN no active unlock settings enabled
        assertFalse(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.WAKE)
        )

        // WHEN unlock on wake is allowed
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_WAKE,
                0, 0)).thenReturn(1)
        settingsObserverCaptor.value.onChange(
                false,
                listOf(fakeWakeUri),
                0,
                0
        )

        // THEN active unlock triggers allowed on: wake, unlock-intent, and biometric failure
        assertTrue(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.WAKE)
        )
        assertTrue(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT)
        )
        assertTrue(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL)
        )
    }

    @Test
    fun testOnUnlockIntentSettingChanged() {
        verifyRegisterSettingObserver()

        // GIVEN no active unlock settings enabled
        assertFalse(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT)
        )

        // WHEN unlock on biometric failed is allowed
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT,
                0, 0)).thenReturn(1)
        settingsObserverCaptor.value.onChange(
                false,
                listOf(fakeUnlockIntentUri),
                0,
                0
        )

        // THEN active unlock triggers allowed on: biometric failure ONLY
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.WAKE))
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT))
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL))
    }

    @Test
    fun testOnBioFailSettingChanged() {
        verifyRegisterSettingObserver()

        // GIVEN no active unlock settings enabled
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL))

        // WHEN unlock on biometric failed is allowed
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL,
                0, 0)).thenReturn(1)
        settingsObserverCaptor.value.onChange(
                false,
                listOf(fakeBioFailUri),
                0,
                0
        )

        // THEN active unlock triggers allowed on: biometric failure ONLY
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.WAKE))
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT))
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL))
    }

    private fun verifyRegisterSettingObserver() {
        verify(contentResolver).registerContentObserver(
                eq(fakeWakeUri),
                eq(false),
                capture(settingsObserverCaptor),
                eq(UserHandle.USER_ALL))

        verify(contentResolver).registerContentObserver(
                eq(fakeUnlockIntentUri),
                eq(false),
                capture(settingsObserverCaptor),
                eq(UserHandle.USER_ALL))

        verify(contentResolver).registerContentObserver(
                eq(fakeBioFailUri),
                eq(false),
                capture(settingsObserverCaptor),
                eq(UserHandle.USER_ALL))
    }
}
