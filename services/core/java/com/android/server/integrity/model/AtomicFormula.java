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

package com.android.server.integrity.model;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;

/**
 * Represents a simple formula consisting of an app install metadata field and a value.
 *
 * <p>Instances of this class are immutable.
 */
public final class AtomicFormula extends Formula {

    enum Key {
        PACKAGE_NAME,
        APP_CERTIFICATE,
        INSTALLER_NAME,
        INSTALLER_CERTIFICATE,
        VERSION_CODE,
        PRE_INSTALLED
    }

    enum Operator {
        EQ,
        LT,
        LE,
        GT,
        GE
    }

    private final Key mKey;
    private final Operator mOperator;

    // The value of a key can take either 1 of 3 forms: String, Integer, or Boolean.
    // It cannot have multiple values.
    @Nullable
    private final String mStringValue;
    @Nullable
    private final Integer mIntValue;
    @Nullable
    private final Boolean mBoolValue;

    public AtomicFormula(Key key, Operator operator, String stringValue) {
        validateOperator(key, operator);
        checkArgument(
                key == Key.PACKAGE_NAME || key == Key.APP_CERTIFICATE || key == Key.INSTALLER_NAME
                        || key == Key.INSTALLER_CERTIFICATE,
                String.format("Key %s cannot have string value", key));
        this.mKey = checkNotNull(key);
        this.mOperator = checkNotNull(operator);
        this.mStringValue = checkNotNull(stringValue);
        this.mIntValue = null;
        this.mBoolValue = null;
    }

    public AtomicFormula(Key key, Operator operator, Integer intValue) {
        validateOperator(key, operator);
        checkArgument(key == Key.VERSION_CODE,
                String.format("Key %s cannot have integer value", key));
        this.mKey = checkNotNull(key);
        this.mOperator = checkNotNull(operator);
        this.mStringValue = null;
        this.mIntValue = checkNotNull(intValue);
        this.mBoolValue = null;
    }

    public AtomicFormula(Key key, Operator operator, Boolean boolValue) {
        validateOperator(key, operator);
        checkArgument(key == Key.PRE_INSTALLED,
                String.format("Key %s cannot have boolean value", key));
        this.mKey = checkNotNull(key);
        this.mOperator = checkNotNull(operator);
        this.mStringValue = null;
        this.mIntValue = null;
        this.mBoolValue = checkNotNull(boolValue);
    }

    public Key getKey() {
        return mKey;
    }

    public Operator getOperator() {
        return mOperator;
    }

    public String getStringValue() {
        return mStringValue;
    }

    public Integer getIntValue() {
        return mIntValue;
    }

    public Boolean getBoolValue() {
        return mBoolValue;
    }

    /**
     * Get string representation of the value of the key in the formula.
     *
     * @return string representation of the value of the key.
     */
    public String getValue() {
        if (mStringValue != null) {
            return mStringValue;
        }
        if (mIntValue != null) {
            return mIntValue.toString();
        }
        return mBoolValue.toString();
    }

    @Override
    public String toString() {
        return String.format("%s %s %s", mKey, mOperator, getValue());
    }

    private void validateOperator(Key key, Operator operator) {
        boolean validOperator;
        switch (key) {
            case PACKAGE_NAME:
            case APP_CERTIFICATE:
            case INSTALLER_NAME:
            case INSTALLER_CERTIFICATE:
            case PRE_INSTALLED:
                validOperator = (operator == Operator.EQ);
                break;
            case VERSION_CODE:
                validOperator = true;
                break;
            default:
                validOperator = false;
        }
        if (!validOperator) {
            throw new IllegalArgumentException(
                    String.format("Invalid operator %s used for key %s", operator, key));
        }
    }
}
