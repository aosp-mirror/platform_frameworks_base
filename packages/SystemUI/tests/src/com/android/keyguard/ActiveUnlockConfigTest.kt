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
import android.hardware.biometrics.BiometricFaceConstants
import android.net.Uri
import android.os.Handler
import android.os.PowerManager
import android.os.PowerManager.WAKE_REASON_BIOMETRIC
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
    private val fakeFaceErrorsUri = Uri.Builder().appendPath("face-errors").build()
    private val fakeFaceAcquiredUri = Uri.Builder().appendPath("face-acquired").build()
    private val fakeUnlockIntentBioEnroll = Uri.Builder().appendPath("unlock-intent-bio").build()
    private val fakeWakeupsConsideredUnlockIntents =
        Uri.Builder().appendPath("wakeups-considered-unlock-intent").build()

    @Mock
    private lateinit var secureSettings: SecureSettings
    @Mock
    private lateinit var contentResolver: ContentResolver
    @Mock
    private lateinit var handler: Handler
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

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
        `when`(secureSettings.getUriFor(Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ERRORS))
                .thenReturn(fakeFaceErrorsUri)
        `when`(secureSettings.getUriFor(Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO))
                .thenReturn(fakeFaceAcquiredUri)
        `when`(secureSettings.getUriFor(
                Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED))
                .thenReturn(fakeUnlockIntentBioEnroll)
        `when`(secureSettings.getUriFor(
            Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS))
            .thenReturn(fakeWakeupsConsideredUnlockIntents)

        activeUnlockConfig = ActiveUnlockConfig(
                handler,
                secureSettings,
                contentResolver,
                dumpManager
        )
    }

    @Test
    fun registersForSettingsChanges() {
        verifyRegisterSettingObserver()
    }

    @Test
    fun onWakeupSettingChanged() {
        verifyRegisterSettingObserver()

        // GIVEN no active unlock settings enabled
        assertFalse(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE)
        )

        // WHEN unlock on wake is allowed
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_WAKE,
                0, 0)).thenReturn(1)
        updateSetting(fakeWakeUri)

        // THEN active unlock triggers allowed on: wake, unlock-intent, and biometric failure
        assertTrue(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE)
        )
        assertTrue(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT)
        )
        assertTrue(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL)
        )
    }

    @Test
    fun onUnlockIntentSettingChanged() {
        verifyRegisterSettingObserver()

        // GIVEN no active unlock settings enabled
        assertFalse(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT)
        )

        // WHEN unlock on biometric failed is allowed
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT,
                0, 0)).thenReturn(1)
        updateSetting(fakeUnlockIntentUri)

        // THEN active unlock triggers allowed on: biometric failure ONLY
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE))
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT))
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL))
    }

    @Test
    fun onBioFailSettingChanged() {
        verifyRegisterSettingObserver()

        // GIVEN no active unlock settings enabled and triggering unlock intent on biometric
        // enrollment setting is disabled (empty string is disabled, null would use the default)
        `when`(secureSettings.getStringForUser(
                Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
                0)).thenReturn("")
        updateSetting(fakeUnlockIntentBioEnroll)
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL))

        // WHEN unlock on biometric failed is allowed
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL,
                0, 0)).thenReturn(1)
        updateSetting(fakeBioFailUri)

        // THEN active unlock triggers allowed on: biometric failure ONLY
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE))
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT))
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL))
    }

    @Test
    fun faceErrorSettingsChanged() {
        verifyRegisterSettingObserver()

        // GIVEN unlock on biometric fail
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL,
                0, 0)).thenReturn(1)
        updateSetting(fakeBioFailUri)

        // WHEN face error timeout (3), allow trigger active unlock
        `when`(secureSettings.getStringForUser(Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ERRORS,
                0)).thenReturn("3")
        updateSetting(fakeFaceAcquiredUri)

        // THEN active unlock triggers allowed on error TIMEOUT
        assertTrue(activeUnlockConfig.shouldRequestActiveUnlockOnFaceError(
                BiometricFaceConstants.FACE_ERROR_TIMEOUT))

        assertFalse(activeUnlockConfig.shouldRequestActiveUnlockOnFaceError(
                BiometricFaceConstants.FACE_ERROR_CANCELED))
    }

    @Test
    fun faceAcquiredSettingsChanged() {
        verifyRegisterSettingObserver()

        // GIVEN unlock on biometric fail
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL,
                0, 0)).thenReturn(1)
        updateSetting(fakeBioFailUri)

        // WHEN face acquiredMsg DARK_GLASSESand MOUTH_COVERING are allowed to trigger
        `when`(secureSettings.getStringForUser(Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO,
                0)).thenReturn(
                "${BiometricFaceConstants.FACE_ACQUIRED_MOUTH_COVERING_DETECTED}" +
                        "|${BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED}")
        updateSetting(fakeFaceAcquiredUri)

        // THEN active unlock triggers allowed on acquired messages DARK_GLASSES & MOUTH_COVERING
        assertTrue(activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_MOUTH_COVERING_DETECTED))
        assertTrue(activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED))

        assertFalse(activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_GOOD))
        assertFalse(activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_NOT_DETECTED))
    }

    @Test
    fun triggerOnUnlockIntentWhenBiometricEnrolledNone() {
        verifyRegisterSettingObserver()

        // GIVEN unlock on biometric fail
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL,
                0, 0)).thenReturn(1)
        updateSetting(fakeBioFailUri)

        // GIVEN fingerprint and face are NOT enrolled
        activeUnlockConfig.keyguardUpdateMonitor = keyguardUpdateMonitor
        `when`(keyguardUpdateMonitor.isFaceEnrolled()).thenReturn(false)
        `when`(keyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(0)).thenReturn(false)

        // WHEN unlock intent is allowed when NO biometrics are enrolled (0)
        `when`(secureSettings.getStringForUser(
                Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
                0)).thenReturn("${ActiveUnlockConfig.BiometricType.NONE.intValue}")
        updateSetting(fakeUnlockIntentBioEnroll)

        // THEN active unlock triggers allowed on unlock intent
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT))
    }

    @Test
    fun triggerOnUnlockIntentWhenBiometricEnrolledFingerprintOrFaceOnly() {
        verifyRegisterSettingObserver()

        // GIVEN unlock on biometric fail
        `when`(secureSettings.getIntForUser(Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL,
                0, 0)).thenReturn(1)
        updateSetting(fakeBioFailUri)

        // GIVEN fingerprint and face are both enrolled
        activeUnlockConfig.keyguardUpdateMonitor = keyguardUpdateMonitor
        `when`(keyguardUpdateMonitor.isFaceEnrolled).thenReturn(true)
        `when`(keyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(0)).thenReturn(true)

        // WHEN unlock intent is allowed when ONLY fingerprint is enrolled or NO biometircs
        // are enrolled
        `when`(secureSettings.getStringForUser(
                Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
                0)).thenReturn(
                "${ActiveUnlockConfig.BiometricType.ANY_FACE.intValue}" +
                        "|${ActiveUnlockConfig.BiometricType.ANY_FINGERPRINT.intValue}")
        updateSetting(fakeUnlockIntentBioEnroll)

        // THEN active unlock triggers NOT allowed on unlock intent
        assertFalse(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT))

        // WHEN fingerprint ONLY enrolled
        `when`(keyguardUpdateMonitor.isFaceEnrolled).thenReturn(false)
        `when`(keyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(0)).thenReturn(true)

        // THEN active unlock triggers allowed on unlock intent
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT))

        // WHEN face ONLY enrolled
        `when`(keyguardUpdateMonitor.isFaceEnrolled).thenReturn(true)
        `when`(keyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(0)).thenReturn(false)

        // THEN active unlock triggers allowed on unlock intent
        assertTrue(activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT))
    }

    @Test
    fun isWakeupConsideredUnlockIntent_singleValue() {
        verifyRegisterSettingObserver()

        // GIVEN lift is considered an unlock intent
        `when`(secureSettings.getStringForUser(
            Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
            0)).thenReturn(PowerManager.WAKE_REASON_LIFT.toString())
        updateSetting(fakeWakeupsConsideredUnlockIntents)

        // THEN only WAKE_REASON_LIFT is considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            if (wakeReason == PowerManager.WAKE_REASON_LIFT) {
                assertTrue(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
            } else {
                assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
            }
        }
    }

    @Test
    fun isWakeupConsideredUnlockIntent_multiValue() {
        verifyRegisterSettingObserver()

        // GIVEN lift and tap are considered an unlock intent
        `when`(secureSettings.getStringForUser(
            Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
            0)).thenReturn(
            PowerManager.WAKE_REASON_LIFT.toString() +
                    "|" +
                    PowerManager.WAKE_REASON_TAP.toString()
        )
        updateSetting(fakeWakeupsConsideredUnlockIntents)

        // THEN WAKE_REASON_LIFT and WAKE_REASON TAP are considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            if (wakeReason == PowerManager.WAKE_REASON_LIFT ||
                wakeReason == PowerManager.WAKE_REASON_TAP) {
                assertTrue(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
            } else {
                assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
            }
        }
        assertTrue(activeUnlockConfig.isWakeupConsideredUnlockIntent(PowerManager.WAKE_REASON_LIFT))
        assertTrue(activeUnlockConfig.isWakeupConsideredUnlockIntent(PowerManager.WAKE_REASON_TAP))
        assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(
            PowerManager.WAKE_REASON_UNFOLD_DEVICE))
    }

    @Test
    fun isWakeupConsideredUnlockIntent_emptyValues() {
        verifyRegisterSettingObserver()

        // GIVEN lift and tap are considered an unlock intent
        `when`(secureSettings.getStringForUser(
            Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
            0)).thenReturn(" ")
        updateSetting(fakeWakeupsConsideredUnlockIntents)

        // THEN no wake up gestures are considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
        }
        assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(
            PowerManager.WAKE_REASON_LIFT))
        assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(PowerManager.WAKE_REASON_TAP))
        assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(
            PowerManager.WAKE_REASON_UNFOLD_DEVICE))
    }

    private fun updateSetting(uri: Uri) {
        settingsObserverCaptor.value.onChange(
                false,
                listOf(uri),
                0,
                0 /* flags */
        )
    }

    private fun verifyRegisterSettingObserver() {
        verifyRegisterSettingObserver(fakeWakeUri)
        verifyRegisterSettingObserver(fakeUnlockIntentUri)
        verifyRegisterSettingObserver(fakeBioFailUri)
        verifyRegisterSettingObserver(fakeFaceErrorsUri)
        verifyRegisterSettingObserver(fakeFaceAcquiredUri)
        verifyRegisterSettingObserver(fakeUnlockIntentBioEnroll)
        verifyRegisterSettingObserver(fakeWakeupsConsideredUnlockIntents)
    }

    private fun verifyRegisterSettingObserver(uri: Uri) {
        verify(contentResolver).registerContentObserver(
                eq(uri),
                eq(false),
                capture(settingsObserverCaptor),
                eq(UserHandle.USER_ALL))
    }
}
