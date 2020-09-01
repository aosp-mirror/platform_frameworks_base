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

package com.android.internal.widget;

/**
 * Password validation error containing an error code and optional requirement.
 */
public class PasswordValidationError {
    // Password validation error codes
    public static final int WEAK_CREDENTIAL_TYPE = 1;
    public static final int CONTAINS_INVALID_CHARACTERS = 2;
    public static final int TOO_SHORT = 3;
    public static final int TOO_LONG = 4;
    public static final int CONTAINS_SEQUENCE = 5;
    public static final int NOT_ENOUGH_LETTERS = 6;
    public static final int NOT_ENOUGH_UPPER_CASE = 7;
    public static final int NOT_ENOUGH_LOWER_CASE = 8;
    public static final int NOT_ENOUGH_DIGITS = 9;
    public static final int NOT_ENOUGH_SYMBOLS = 10;
    public static final int NOT_ENOUGH_NON_LETTER = 11;
    public static final int NOT_ENOUGH_NON_DIGITS = 12;
    public static final int RECENTLY_USED = 13;
    // WARNING: if you add a new error, make sure it is presented to the user correctly in Settings.

    public final int errorCode;
    public final int requirement;

    public PasswordValidationError(int errorCode) {
        this(errorCode, 0);
    }

    public PasswordValidationError(int errorCode, int requirement) {
        this.errorCode = errorCode;
        this.requirement = requirement;
    }

    @Override
    public String toString() {
        return errorCodeToString(errorCode) + (requirement > 0 ? "; required: " + requirement : "");
    }

    /**
     * Returns textual representation of the error for logging purposes.
     */
    private static String errorCodeToString(int error) {
        switch (error) {
            case WEAK_CREDENTIAL_TYPE: return "Weak credential type";
            case CONTAINS_INVALID_CHARACTERS: return "Contains an invalid character";
            case TOO_SHORT: return "Password too short";
            case TOO_LONG: return "Password too long";
            case CONTAINS_SEQUENCE: return "Sequence too long";
            case NOT_ENOUGH_LETTERS: return "Too few letters";
            case NOT_ENOUGH_UPPER_CASE: return "Too few upper case letters";
            case NOT_ENOUGH_LOWER_CASE: return "Too few lower case letters";
            case NOT_ENOUGH_DIGITS: return "Too few numeric characters";
            case NOT_ENOUGH_SYMBOLS: return "Too few symbols";
            case NOT_ENOUGH_NON_LETTER: return "Too few non-letter characters";
            case NOT_ENOUGH_NON_DIGITS: return "Too few non-numeric characters";
            case RECENTLY_USED: return "Pin or password was recently used";
            default: return "Unknown error " + error;
        }
    }

}
