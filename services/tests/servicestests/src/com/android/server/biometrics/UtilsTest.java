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

import static org.junit.Assert.assertNull;

import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class UtilsTest {

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andKeyAuthenticators() {
        final boolean allowDeviceCredential = false;
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final Bundle bundle = new Bundle();

        bundle.putBoolean(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL, allowDeviceCredential);
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED), authenticators);
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andKeyAuthenticators() {
        final @Authenticators.Types int authenticators =
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK;
        final Bundle bundle = new Bundle();

        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED), authenticators);
    }

    @Test
    public void testCombineAuthenticatorBundles_withKeyDeviceCredential_andNoKeyAuthenticators() {
        final boolean allowDeviceCredential = true;
        final Bundle bundle = new Bundle();

        bundle.putBoolean(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL, allowDeviceCredential);
        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED),
                Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK);
    }

    @Test
    public void testCombineAuthenticatorBundles_withNoKeyDeviceCredential_andNoKeyAuthenticators() {
        final Bundle bundle = new Bundle();

        Utils.combineAuthenticatorBundles(bundle);

        assertNull(bundle.get(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL));
        assertEquals(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED),
                Authenticators.BIOMETRIC_WEAK);
    }

    @Test
    public void testIsDeviceCredentialAllowed_withIntegerFlags() {
        int authenticators = 0;
        assertFalse(Utils.isDeviceCredentialAllowed(authenticators));

        authenticators |= Authenticators.DEVICE_CREDENTIAL;
        assertTrue(Utils.isDeviceCredentialAllowed(authenticators));

        authenticators |= Authenticators.BIOMETRIC_WEAK;
        assertTrue(Utils.isDeviceCredentialAllowed(authenticators));
    }

    @Test
    public void testIsDeviceCredentialAllowed_withBundle() {
        Bundle bundle = new Bundle();
        assertFalse(Utils.isDeviceCredentialAllowed(bundle));

        int authenticators = 0;
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        assertFalse(Utils.isDeviceCredentialAllowed(bundle));

        authenticators |= Authenticators.DEVICE_CREDENTIAL;
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        assertTrue(Utils.isDeviceCredentialAllowed(bundle));

        authenticators |= Authenticators.BIOMETRIC_WEAK;
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        assertTrue(Utils.isDeviceCredentialAllowed(bundle));
    }

    @Test
    public void testGetBiometricStrength_removeUnrelatedBits() {
        // BIOMETRIC_MIN_STRENGTH uses all of the allowed bits for biometric strength, so any other
        // bits aside from these should be clipped off.

        int authenticators = Integer.MAX_VALUE;
        assertEquals(Authenticators.BIOMETRIC_WEAK,
                Utils.getBiometricStrength(authenticators));

        Bundle bundle = new Bundle();
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        assertEquals(Authenticators.BIOMETRIC_WEAK, Utils.getBiometricStrength(bundle));
    }

    @Test
    public void testIsBiometricAllowed() {
        // Only the lowest 8 bits (BIOMETRIC_WEAK mask) are allowed to integrate with the
        // Biometric APIs
        final int lastBiometricPosition = 10;
        Bundle bundle = new Bundle();
        for (int i = 0; i <= 7; i++) {
            int authenticators = 1 << i;
            bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
            assertTrue(Utils.isBiometricAllowed(bundle));
        }

        // The rest of the bits are not allowed to integrate with BiometricPrompt
        for (int i = 8; i < 32; i++) {
            int authenticators = 1 << i;
            bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
            assertFalse(Utils.isBiometricAllowed(bundle));
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
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE}
        };

        for (int i = 0; i < testCases.length; i++) {
            assertEquals(testCases[i][1],
                    Utils.biometricConstantsToBiometricManager(testCases[i][0]));
        }
    }
}
