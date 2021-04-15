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

package android.app.admin;

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

/**
 * {@hide}
 */
public class PasswordPolicy {
    public static final int DEF_MINIMUM_LENGTH = 0;
    public static final int DEF_MINIMUM_LETTERS = 1;
    public static final int DEF_MINIMUM_UPPER_CASE = 0;
    public static final int DEF_MINIMUM_LOWER_CASE = 0;
    public static final int DEF_MINIMUM_NUMERIC = 1;
    public static final int DEF_MINIMUM_SYMBOLS = 1;
    public static final int DEF_MINIMUM_NON_LETTER = 0;

    public int quality = PASSWORD_QUALITY_UNSPECIFIED;
    public int length = DEF_MINIMUM_LENGTH;
    public int letters = DEF_MINIMUM_LETTERS;
    public int upperCase = DEF_MINIMUM_UPPER_CASE;
    public int lowerCase = DEF_MINIMUM_LOWER_CASE;
    public int numeric = DEF_MINIMUM_NUMERIC;
    public int symbols = DEF_MINIMUM_SYMBOLS;
    public int nonLetter = DEF_MINIMUM_NON_LETTER;

    /**
     * Returns a minimum password metrics that the password should have to satisfy current policy.
     */
    public PasswordMetrics getMinMetrics() {
        if (quality == PASSWORD_QUALITY_UNSPECIFIED) {
            return new PasswordMetrics(CREDENTIAL_TYPE_NONE);
        } else if (quality == PASSWORD_QUALITY_BIOMETRIC_WEAK
                || quality == PASSWORD_QUALITY_SOMETHING) {
            return new PasswordMetrics(CREDENTIAL_TYPE_PATTERN);
        } else if (quality == PASSWORD_QUALITY_NUMERIC
                || quality == PASSWORD_QUALITY_NUMERIC_COMPLEX) {
            PasswordMetrics result = new PasswordMetrics(CREDENTIAL_TYPE_PIN);
            result.length = length;
            if (quality == PASSWORD_QUALITY_NUMERIC_COMPLEX) {
                result.seqLength = PasswordMetrics.MAX_ALLOWED_SEQUENCE;
            }
            return result;
        } // quality is ALPHABETIC or stronger.

        PasswordMetrics result = new PasswordMetrics(CREDENTIAL_TYPE_PASSWORD);
        result.length = length;

        if (quality == PASSWORD_QUALITY_ALPHABETIC) {
            result.nonNumeric = 1;
        } else if (quality == PASSWORD_QUALITY_ALPHANUMERIC) {
            result.numeric = 1;
            result.nonNumeric = 1;
        } else if (quality == PASSWORD_QUALITY_COMPLEX) {
            result.numeric = numeric;
            result.letters = letters;
            result.upperCase = upperCase;
            result.lowerCase = lowerCase;
            result.nonLetter = nonLetter;
            result.symbols = symbols;
        }
        return result;
    }
}
