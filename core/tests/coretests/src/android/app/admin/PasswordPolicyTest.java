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

package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

import static org.junit.Assert.assertEquals;

import android.app.admin.PasswordMetrics;
import android.app.admin.PasswordPolicy;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class PasswordPolicyTest {

    public static final int TEST_VALUE = 10;

    @Test
    public void testGetMinMetrics_unspecified() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_UNSPECIFIED);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_NONE, minMetrics.credType);
        assertEquals(0, minMetrics.length);
        assertEquals(0, minMetrics.numeric);
    }

    @Test
    public void testGetMinMetrics_something() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_SOMETHING);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PATTERN, minMetrics.credType);
        assertEquals(0, minMetrics.length);
        assertEquals(0, minMetrics.numeric);
    }

    @Test
    public void testGetMinMetrics_biometricWeak() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_BIOMETRIC_WEAK);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PATTERN, minMetrics.credType);
        assertEquals(0, minMetrics.length);
        assertEquals(0, minMetrics.numeric);
    }

    @Test
    public void testGetMinMetrics_numeric() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_NUMERIC);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PIN, minMetrics.credType);
        assertEquals(TEST_VALUE, minMetrics.length);
        assertEquals(0, minMetrics.numeric); // numeric can doesn't really require digits.
        assertEquals(0, minMetrics.letters);
        assertEquals(0, minMetrics.lowerCase);
        assertEquals(0, minMetrics.upperCase);
        assertEquals(0, minMetrics.symbols);
        assertEquals(0, minMetrics.nonLetter);
        assertEquals(0, minMetrics.nonNumeric);
        assertEquals(Integer.MAX_VALUE, minMetrics.seqLength);
    }

    @Test
    public void testGetMinMetrics_numericDefaultLength() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_NUMERIC);
        policy.length = 0; // reset to default
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(0, minMetrics.length);
    }

    @Test
    public void testGetMinMetrics_numericComplex() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_NUMERIC_COMPLEX);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PIN, minMetrics.credType);
        assertEquals(TEST_VALUE, minMetrics.length);
        assertEquals(0, minMetrics.numeric);
        assertEquals(0, minMetrics.letters);
        assertEquals(0, minMetrics.lowerCase);
        assertEquals(0, minMetrics.upperCase);
        assertEquals(0, minMetrics.symbols);
        assertEquals(0, minMetrics.nonLetter);
        assertEquals(0, minMetrics.nonNumeric);
        assertEquals(PasswordMetrics.MAX_ALLOWED_SEQUENCE, minMetrics.seqLength);
    }

    @Test
    public void testGetMinMetrics_alphabetic() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_ALPHABETIC);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PASSWORD, minMetrics.credType);
        assertEquals(TEST_VALUE, minMetrics.length);
        assertEquals(0, minMetrics.numeric);
        assertEquals(0, minMetrics.letters);
        assertEquals(0, minMetrics.lowerCase);
        assertEquals(0, minMetrics.upperCase);
        assertEquals(0, minMetrics.symbols);
        assertEquals(0, minMetrics.nonLetter);
        assertEquals(1, minMetrics.nonNumeric);
        assertEquals(Integer.MAX_VALUE, minMetrics.seqLength);
    }

    @Test
    public void testGetMinMetrics_alphanumeric() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_ALPHANUMERIC);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PASSWORD, minMetrics.credType);
        assertEquals(TEST_VALUE, minMetrics.length);
        assertEquals(1, minMetrics.numeric);
        assertEquals(0, minMetrics.letters);
        assertEquals(0, minMetrics.lowerCase);
        assertEquals(0, minMetrics.upperCase);
        assertEquals(0, minMetrics.symbols);
        assertEquals(0, minMetrics.nonLetter);
        assertEquals(1, minMetrics.nonNumeric);
        assertEquals(Integer.MAX_VALUE, minMetrics.seqLength);
    }

    @Test
    public void testGetMinMetrics_complex() {
        PasswordPolicy policy = testPolicy(PASSWORD_QUALITY_COMPLEX);
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PASSWORD, minMetrics.credType);
        assertEquals(TEST_VALUE, minMetrics.length);
        assertEquals(TEST_VALUE, minMetrics.letters);
        assertEquals(TEST_VALUE, minMetrics.lowerCase);
        assertEquals(TEST_VALUE, minMetrics.upperCase);
        assertEquals(TEST_VALUE, minMetrics.symbols);
        assertEquals(TEST_VALUE, minMetrics.numeric);
        assertEquals(TEST_VALUE, minMetrics.nonLetter);
        assertEquals(0, minMetrics.nonNumeric);
        assertEquals(Integer.MAX_VALUE, minMetrics.seqLength);
    }

    @Test
    public void testGetMinMetrics_complexDefault() {
        PasswordPolicy policy = new PasswordPolicy();
        policy.quality = PASSWORD_QUALITY_COMPLEX;
        PasswordMetrics minMetrics = policy.getMinMetrics();
        assertEquals(CREDENTIAL_TYPE_PASSWORD, minMetrics.credType);
        assertEquals(0, minMetrics.length);
        assertEquals(1, minMetrics.letters);
        assertEquals(0, minMetrics.lowerCase);
        assertEquals(0, minMetrics.upperCase);
        assertEquals(1, minMetrics.symbols);
        assertEquals(1, minMetrics.numeric);
        assertEquals(0, minMetrics.nonLetter);
        assertEquals(0, minMetrics.nonNumeric);
        assertEquals(Integer.MAX_VALUE, minMetrics.seqLength);
    }

    private PasswordPolicy testPolicy(int quality) {
        PasswordPolicy result = new PasswordPolicy();
        result.quality = quality;
        result.length = TEST_VALUE;
        result.letters = TEST_VALUE;
        result.lowerCase = TEST_VALUE;
        result.upperCase = TEST_VALUE;
        result.numeric = TEST_VALUE;
        result.symbols = TEST_VALUE;
        result.nonLetter = TEST_VALUE;
        return result;
    }
}
