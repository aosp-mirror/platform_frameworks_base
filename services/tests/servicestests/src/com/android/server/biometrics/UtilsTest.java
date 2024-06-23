/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricManager.Authenticators;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.PromptInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@Presubmit
@SmallTest
public class UtilsTest {

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andKeyAuthenticators() {
        final boolean allowDeviceCredential = false;
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final PromptInfo promptInfo = new PromptInfo();

        promptInfo.setDeviceCredentialAllowed(allowDeviceCredential);
        promptInfo.setAuthenticators(authenticators);
        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(authenticators, promptInfo.getAuthenticators());
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andKeyAuthenticators() {
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final PromptInfo promptInfo = new PromptInfo();

        promptInfo.setAuthenticators(authenticators);
        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(authenticators, promptInfo.getAuthenticators());
    }

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andNoKeyAuthenticators() {
        final boolean allowDeviceCredential = true;
        final PromptInfo promptInfo = new PromptInfo();

        promptInfo.setDeviceCredentialAllowed(allowDeviceCredential);
        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK,
                promptInfo.getAuthenticators());
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andNoKeyAuthenticators() {
        final PromptInfo promptInfo = new PromptInfo();

        Utils.combineAuthenticatorBundles(promptInfo);

        assertFalse(promptInfo.isDeviceCredentialAllowed());
        assertEquals(Authenticators.BIOMETRIC_WEAK, promptInfo.getAuthenticators());
    }

    @Test
    public void testIsDeviceCredentialAllowed_withIntegerFlags() {
        int authenticators = 0;
        assertFalse(Utils.isCredentialRequested(authenticators));

        authenticators |= Authenticators.DEVICE_CREDENTIAL;
        assertTrue(Utils.isCredentialRequested(authenticators));

        authenticators |= Authenticators.BIOMETRIC_WEAK;
        assertTrue(Utils.isCredentialRequested(authenticators));
    }

    @Test
    public void testIsDeviceCredentialAllowed_withBundle() {
        PromptInfo promptInfo = new PromptInfo();
        assertFalse(Utils.isCredentialRequested(promptInfo));

        int authenticators = 0;
        promptInfo.setAuthenticators(authenticators);
        assertFalse(Utils.isCredentialRequested(promptInfo));

        authenticators |= Authenticators.DEVICE_CREDENTIAL;
        promptInfo.setAuthenticators(authenticators);
        assertTrue(Utils.isCredentialRequested(promptInfo));

        authenticators |= Authenticators.BIOMETRIC_WEAK;
        promptInfo.setAuthenticators(authenticators);
        assertTrue(Utils.isCredentialRequested(promptInfo));
    }

    @Test
    public void testGetBiometricStrength_removeUnrelatedBits() {
        // BIOMETRIC_MIN_STRENGTH uses all of the allowed bits for biometric strength, so any other
        // bits aside from these should be clipped off.

        int authenticators = Integer.MAX_VALUE;
        assertEquals(Authenticators.BIOMETRIC_WEAK,
                Utils.getPublicBiometricStrength(authenticators));

        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setAuthenticators(authenticators);
        assertEquals(Authenticators.BIOMETRIC_WEAK, Utils.getPublicBiometricStrength(promptInfo));
    }

    @Test
    public void testIsBiometricAllowed() {
        // Only the lowest 8 bits (BIOMETRIC_WEAK mask) are allowed to integrate with the
        // Biometric APIs
        PromptInfo promptInfo = new PromptInfo();
        for (int i = 0; i <= 7; i++) {
            int authenticators = 1 << i;
            promptInfo.setAuthenticators(authenticators);
            assertTrue(Utils.isBiometricRequested(promptInfo));
        }

        // The rest of the bits are not allowed to integrate with the public APIs
        for (int i = 8; i < 32; i++) {
            int authenticators = 1 << i;
            promptInfo.setAuthenticators(authenticators);
            assertFalse(Utils.isBiometricRequested(promptInfo));
        }
    }

    @Test
    public void testIsValidAuthenticatorConfig() {
        assertTrue(Utils.isValidAuthenticatorConfig(Authenticators.EMPTY_SET));

        assertTrue(Utils.isValidAuthenticatorConfig(Authenticators.BIOMETRIC_STRONG));

        assertTrue(Utils.isValidAuthenticatorConfig(Authenticators.BIOMETRIC_WEAK));

        assertTrue(Utils.isValidAuthenticatorConfig(Authenticators.DEVICE_CREDENTIAL));

        assertTrue(Utils.isValidAuthenticatorConfig(Authenticators.DEVICE_CREDENTIAL
                | Authenticators.BIOMETRIC_STRONG));

        assertTrue(Utils.isValidAuthenticatorConfig(Authenticators.DEVICE_CREDENTIAL
                | Authenticators.BIOMETRIC_WEAK));

        assertFalse(Utils.isValidAuthenticatorConfig(Authenticators.BIOMETRIC_CONVENIENCE));

        assertFalse(Utils.isValidAuthenticatorConfig(Authenticators.BIOMETRIC_CONVENIENCE
                | Authenticators.DEVICE_CREDENTIAL));

        assertFalse(Utils.isValidAuthenticatorConfig(Authenticators.BIOMETRIC_MAX_STRENGTH));

        assertFalse(Utils.isValidAuthenticatorConfig(Authenticators.BIOMETRIC_MIN_STRENGTH));

        // The rest of the bits are not allowed to integrate with the public APIs
        for (int i = 8; i < 32; i++) {
            final int authenticator = 1 << i;
            if (authenticator == Authenticators.DEVICE_CREDENTIAL
                    || authenticator == Authenticators.MANDATORY_BIOMETRICS) {
                continue;
            }
            assertFalse(Utils.isValidAuthenticatorConfig(1 << i));
        }
    }

    @Test
    public void testIsAtLeastStrength() {
        int sensorStrength = Authenticators.BIOMETRIC_STRONG;
        int requestedStrength = Authenticators.BIOMETRIC_WEAK;
        assertTrue(Utils.isAtLeastStrength(sensorStrength, requestedStrength));

        requestedStrength = Authenticators.BIOMETRIC_STRONG;
        assertTrue(Utils.isAtLeastStrength(sensorStrength, requestedStrength));

        sensorStrength = Authenticators.BIOMETRIC_WEAK;
        requestedStrength = Authenticators.BIOMETRIC_STRONG;
        assertFalse(Utils.isAtLeastStrength(sensorStrength, requestedStrength));

        requestedStrength = Authenticators.BIOMETRIC_WEAK;
        assertTrue(Utils.isAtLeastStrength(sensorStrength, requestedStrength));


        // Test invalid inputs

        sensorStrength = Authenticators.BIOMETRIC_STRONG;
        requestedStrength = Authenticators.DEVICE_CREDENTIAL;
        assertFalse(Utils.isAtLeastStrength(sensorStrength, requestedStrength));

        requestedStrength = 1 << 2;
        assertFalse(Utils.isAtLeastStrength(sensorStrength, requestedStrength));
    }

    @Test
    public void testBiometricConstantsConversion() {
        final int[][] testCases = {
                {BiometricConstants.BIOMETRIC_SUCCESS,
                        BiometricManager.BIOMETRIC_SUCCESS},
                {BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS,
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED},
                {BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED},
                {BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE},
                {BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE},
                {BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
                        BiometricManager.BIOMETRIC_SUCCESS},
                {BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
                        BiometricManager.BIOMETRIC_SUCCESS}
        };

        for (int i = 0; i < testCases.length; i++) {
            assertEquals(testCases[i][1],
                    Utils.biometricConstantsToBiometricManager(testCases[i][0]));
        }
    }

    @Test
    public void testGetAuthenticationTypeForResult_getsCorrectType() {
        assertEquals(Utils.getAuthenticationTypeForResult(
                BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED),
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL);
        assertEquals(Utils.getAuthenticationTypeForResult(
                BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED),
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC);
        assertEquals(Utils.getAuthenticationTypeForResult(
                BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED),
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetAuthResultType_throwsForInvalidReason() {
        Utils.getAuthenticationTypeForResult(BiometricPrompt.DISMISSED_REASON_NEGATIVE);
    }

    @Test
    public void testConfirmationSupported() {
        assertTrue(Utils.isConfirmationSupported(BiometricAuthenticator.TYPE_FACE));
        assertTrue(Utils.isConfirmationSupported(BiometricAuthenticator.TYPE_IRIS));
        assertFalse(Utils.isConfirmationSupported(BiometricAuthenticator.TYPE_FINGERPRINT));
    }

    @Test
    public void testRemoveBiometricBits() {
        @Authenticators.Types int authenticators = Integer.MAX_VALUE;
        authenticators = Utils.removeBiometricBits(authenticators);
        // All biometric bits are removed
        assertEquals(0, authenticators & Authenticators.BIOMETRIC_MIN_STRENGTH);
    }
}
