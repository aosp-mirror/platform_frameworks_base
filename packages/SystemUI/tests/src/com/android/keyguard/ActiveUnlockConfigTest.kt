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
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ERRORS
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_LEGACY
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_WAKE
import android.provider.Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS
import android.provider.Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import dagger.Lazy
import java.io.PrintWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class ActiveUnlockConfigTest : SysuiTestCase() {
    private lateinit var secureSettings: FakeSettings
    @Mock private lateinit var contentResolver: ContentResolver
    @Mock private lateinit var handler: Handler
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var selectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var lazyKeyguardUpdateMonitor: Lazy<KeyguardUpdateMonitor>
    @Mock private lateinit var mockPrintWriter: PrintWriter

    @Captor private lateinit var settingsObserverCaptor: ArgumentCaptor<ContentObserver>

    private lateinit var activeUnlockConfig: ActiveUnlockConfig
    private var currentUser: Int = 0

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(selectedUserInteractor.getSelectedUserId()).thenReturn(currentUser)
        whenever(lazyKeyguardUpdateMonitor.get()).thenReturn(keyguardUpdateMonitor)
        secureSettings = FakeSettings()
        activeUnlockConfig =
            ActiveUnlockConfig(
                handler,
                secureSettings,
                contentResolver,
                selectedUserInteractor,
                lazyKeyguardUpdateMonitor,
                dumpManager
            )
    }

    @Test
    fun registersForSettingsChanges() {
        verifyRegisterSettingObserver()
    }

    @Test
    fun onWakeupSettingChanged() {
        // GIVEN no active unlock settings enabled
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE
            )
        )

        // WHEN unlock on wake is allowed
        secureSettings.putIntForUser(ACTIVE_UNLOCK_ON_WAKE, 1, currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_WAKE))

        // THEN active unlock triggers allowed on: wake, unlock-intent, and biometric failure
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE
            )
        )
        assertFalse(
                activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT_LEGACY
                )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL
            )
        )
    }

    @Test
    fun onUnlockIntentLegacySettingChanged() {
        // GIVEN no active unlock settings enabled
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT_LEGACY
            )
        )

        // WHEN unlock on unlock intent legacy is allowed
        secureSettings.putIntForUser(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_LEGACY, 1, currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_LEGACY))

        // THEN active unlock triggers allowed on unlock_intent_legacy, unlock_intent,
        // AND biometric fail
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE
            )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT_LEGACY
            )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL
            )
        )
    }

    @Test
    fun onUnlockIntentSettingChanged() {
        // GIVEN no active unlock settings enabled
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )

        // WHEN unlock on unlock intent is allowed
        secureSettings.putIntForUser(ACTIVE_UNLOCK_ON_UNLOCK_INTENT, 1, currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT))

        // THEN active unlock triggers allowed on: unlock intent AND biometric failure
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE
            )
        )
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT_LEGACY
            )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL
            )
        )
    }

    @Test
    fun onBioFailSettingChanged() {
        // GIVEN no active unlock settings enabled and triggering unlock intent on biometric
        // enrollment setting is disabled (empty string is disabled, null would use the default)
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
            "",
            currentUser
        )
        updateSetting(
            secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED)
        )
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL
            )
        )

        // WHEN unlock on biometric failed is allowed
        secureSettings.putIntForUser(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, 1, currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL))

        // THEN active unlock triggers allowed on: biometric failure ONLY
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE
            )
        )
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT_LEGACY
            )
        )
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL
            )
        )
    }

    @Test
    fun faceErrorSettingsChanged() {
        // GIVEN unlock on biometric fail
        secureSettings.putIntForUser(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, 1, currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL))

        // WHEN face error timeout (3), allow trigger active unlock
        secureSettings.putStringForUser(ACTIVE_UNLOCK_ON_FACE_ERRORS, "3", currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ERRORS))

        // THEN active unlock triggers allowed on error TIMEOUT
        assertTrue(
            activeUnlockConfig.shouldRequestActiveUnlockOnFaceError(
                BiometricFaceConstants.FACE_ERROR_TIMEOUT
            )
        )

        assertFalse(
            activeUnlockConfig.shouldRequestActiveUnlockOnFaceError(
                BiometricFaceConstants.FACE_ERROR_CANCELED
            )
        )
    }

    @Test
    fun faceAcquiredSettingsChanged() {
        // GIVEN unlock on biometric fail
        secureSettings.putStringForUser(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, "1", currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL))

        // WHEN face acquiredMsg DARK_GLASSESand MOUTH_COVERING are allowed to trigger
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO,
            "${BiometricFaceConstants.FACE_ACQUIRED_MOUTH_COVERING_DETECTED}" +
                "|${BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED}",
            currentUser
        )
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO))

        // THEN active unlock triggers allowed on acquired messages DARK_GLASSES & MOUTH_COVERING
        assertTrue(
            activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_MOUTH_COVERING_DETECTED
            )
        )
        assertTrue(
            activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED
            )
        )

        assertFalse(
            activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_GOOD
            )
        )
        assertFalse(
            activeUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                BiometricFaceConstants.FACE_ACQUIRED_NOT_DETECTED
            )
        )
    }

    @Test
    fun triggerOnUnlockIntentWhenBiometricEnrolledNone() {
        // GIVEN unlock on biometric fail
        secureSettings.putIntForUser(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, 1, currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL))

        // GIVEN fingerprint and face are NOT enrolled
        `when`(keyguardUpdateMonitor.isFaceEnabledAndEnrolled).thenReturn(false)
        `when`(keyguardUpdateMonitor.isUnlockWithFingerprintPossible(0)).thenReturn(false)

        // WHEN unlock intent is allowed when NO biometrics are enrolled (0)

        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
            "${ActiveUnlockConfig.BiometricType.NONE.intValue}",
            currentUser
        )
        updateSetting(
            secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED)
        )

        // THEN active unlock triggers allowed on unlock intent
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )
    }

    @Test
    fun triggerOnUnlockIntentWhenBiometricEnrolledFingerprintOrFaceOnly() {
        // GIVEN unlock on biometric fail
        secureSettings.putIntForUser(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, 1, currentUser)
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL))

        // GIVEN fingerprint and face are both enrolled
        `when`(keyguardUpdateMonitor.isFaceEnabledAndEnrolled).thenReturn(true)
        `when`(keyguardUpdateMonitor.isUnlockWithFingerprintPossible(0)).thenReturn(true)

        // WHEN unlock intent is allowed when ONLY fingerprint is enrolled or NO biometircs
        // are enrolled
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
            "${ActiveUnlockConfig.BiometricType.ANY_FACE.intValue}" +
                "|${ActiveUnlockConfig.BiometricType.ANY_FINGERPRINT.intValue}",
            currentUser
        )
        updateSetting(
            secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED)
        )

        // THEN active unlock triggers NOT allowed on unlock intent
        assertFalse(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )

        // WHEN fingerprint ONLY enrolled
        `when`(keyguardUpdateMonitor.isFaceEnabledAndEnrolled).thenReturn(false)
        `when`(keyguardUpdateMonitor.isUnlockWithFingerprintPossible(0)).thenReturn(true)

        // THEN active unlock triggers allowed on unlock intent
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )

        // WHEN face ONLY enrolled
        `when`(keyguardUpdateMonitor.isFaceEnabledAndEnrolled).thenReturn(true)
        `when`(keyguardUpdateMonitor.isUnlockWithFingerprintPossible(0)).thenReturn(false)

        // THEN active unlock triggers allowed on unlock intent
        assertTrue(
            activeUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
            )
        )
    }

    @Test
    fun isWakeupConsideredUnlockIntent_singleValue() {
        // GIVEN lift is considered an unlock intent
        secureSettings.putIntForUser(
            ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
            PowerManager.WAKE_REASON_LIFT,
            currentUser
        )
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS))

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
        // GIVEN lift and tap are considered an unlock intent
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
            PowerManager.WAKE_REASON_LIFT.toString() +
                "|" +
                PowerManager.WAKE_REASON_TAP.toString(),
            currentUser
        )
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS))

        // THEN WAKE_REASON_LIFT and WAKE_REASON TAP are considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            if (
                wakeReason == PowerManager.WAKE_REASON_LIFT ||
                    wakeReason == PowerManager.WAKE_REASON_TAP
            ) {
                assertTrue(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
            } else {
                assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
            }
        }
        assertTrue(activeUnlockConfig.isWakeupConsideredUnlockIntent(PowerManager.WAKE_REASON_LIFT))
        assertTrue(activeUnlockConfig.isWakeupConsideredUnlockIntent(PowerManager.WAKE_REASON_TAP))
        assertFalse(
            activeUnlockConfig.isWakeupConsideredUnlockIntent(
                PowerManager.WAKE_REASON_UNFOLD_DEVICE
            )
        )
    }

    @Test
    fun isWakeupConsideredUnlockIntent_emptyValues() {
        // GIVEN lift and tap are considered an unlock intent
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
            " ",
            currentUser
        )
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS))

        // THEN no wake up gestures are considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason))
        }
        assertFalse(
            activeUnlockConfig.isWakeupConsideredUnlockIntent(PowerManager.WAKE_REASON_LIFT)
        )
        assertFalse(activeUnlockConfig.isWakeupConsideredUnlockIntent(PowerManager.WAKE_REASON_TAP))
        assertFalse(
            activeUnlockConfig.isWakeupConsideredUnlockIntent(
                PowerManager.WAKE_REASON_UNFOLD_DEVICE
            )
        )
    }

    @Test
    fun isWakeupForceDismissKeyguard_singleValue() {
        verifyRegisterSettingObserver()

        // GIVEN lift is considered an unlock intent
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD,
            PowerManager.WAKE_REASON_LIFT.toString(),
            currentUser
        )
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD))

        // THEN only WAKE_REASON_LIFT is considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            if (wakeReason == PowerManager.WAKE_REASON_LIFT) {
                assertTrue(activeUnlockConfig.shouldWakeupForceDismissKeyguard(wakeReason))
            } else {
                assertFalse(activeUnlockConfig.shouldWakeupForceDismissKeyguard(wakeReason))
            }
        }
    }

    @Test
    fun isWakeupForceDismissKeyguard_emptyValues() {
        verifyRegisterSettingObserver()

        // GIVEN lift and tap are considered an unlock intent
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD,
            " ",
            currentUser
        )
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD))

        // THEN no wake up gestures are considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            assertFalse(activeUnlockConfig.shouldWakeupForceDismissKeyguard(wakeReason))
        }
    }

    @Test
    fun isWakeupForceDismissKeyguard_multiValue() {
        verifyRegisterSettingObserver()

        // GIVEN lift and tap are considered an unlock intent
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD,
            PowerManager.WAKE_REASON_LIFT.toString() +
                "|" +
                PowerManager.WAKE_REASON_TAP.toString(),
            currentUser
        )
        updateSetting(secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD))

        // THEN WAKE_REASON_LIFT and WAKE_REASON TAP are considered an unlock intent
        for (wakeReason in 0..WAKE_REASON_BIOMETRIC) {
            if (
                wakeReason == PowerManager.WAKE_REASON_LIFT ||
                    wakeReason == PowerManager.WAKE_REASON_TAP
            ) {
                assertTrue(activeUnlockConfig.shouldWakeupForceDismissKeyguard(wakeReason))
            } else {
                assertFalse(activeUnlockConfig.shouldWakeupForceDismissKeyguard(wakeReason))
            }
        }
    }

    @Test
    fun dump_onUnlockIntentWhenBiometricEnrolled_invalidNum_noArrayOutOfBoundsException() {
        // GIVEN an invalid input (-1)
        secureSettings.putStringForUser(
            ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
            "-1",
            currentUser
        )

        // WHEN the setting updates
        updateSetting(
            secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED)
        )

        // THEN no exception thrown
        activeUnlockConfig.dump(mockPrintWriter, emptyArray())
    }

    private fun updateSetting(uri: Uri) {
        verifyRegisterSettingObserver()
        settingsObserverCaptor.value.onChange(false, listOf(uri), 0, 0 /* flags */)
    }

    private fun verifyRegisterSettingObserver() {
        verifyRegisterSettingObserver(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_WAKE))
        verifyRegisterSettingObserver(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT))
        verifyRegisterSettingObserver(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL))
        verifyRegisterSettingObserver(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ERRORS))
        verifyRegisterSettingObserver(secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO))
        verifyRegisterSettingObserver(
            secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED)
        )
        verifyRegisterSettingObserver(
            secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS)
        )
    }

    private fun verifyRegisterSettingObserver(uri: Uri) {
        verify(contentResolver)
            .registerContentObserver(
                eq(uri),
                eq(false),
                capture(settingsObserverCaptor),
                eq(UserHandle.USER_ALL)
            )
    }
}
