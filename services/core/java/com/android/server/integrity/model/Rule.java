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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;

/**
 * Represent rules to be used in the rule evaluation engine to match against app installs.
 *
 * <p>Instances of this class are immutable.
 */
public final class Rule {

    // Holds an empty rule instance.
    public static final Rule EMPTY = new Rule();

    enum Key {
        PACKAGE_NAME,
        APP_CERTIFICATE,
        INSTALLER_NAME,
        INSTALLER_CERTIFICATE,
        VERSION_CODE,
        PRE_INSTALLED
    }

    enum Effect {
        DENY
    }

    enum Operator {
        EQ,
        LT,
        LE,
        GT,
        GE
    }

    enum Connector {
        AND,
        OR,
        NOT
    }

    private final Formula mFormula;
    private final Effect mEffect;

    private Rule() {
        this.mFormula = null;
        this.mEffect = null;
    }

    public Rule(Formula formula, Effect effect) {
        this.mFormula = checkNotNull(formula);
        this.mEffect = checkNotNull(effect);
    }

    /**
     * Indicates whether the rule is empty or not.
     *
     * @return {@code true} if the rule is empty, and {@code false} otherwise.
     */
    public boolean isEmpty() {
        return mFormula == null && mEffect == null;
    }

    public Formula getFormula() {
        return mFormula;
    }

    public Effect getEffect() {
        return mEffect;
    }

    // TODO: Consider moving the sub-components to their respective model class.

    /**
     * Represents a rule logic/content.
     */
    abstract static class Formula {

    }

    /**
     * Represents a simple formula consisting of an app install metadata field and a value.
     */
    public static final class AtomicFormula extends Formula {

        final Key mKey;
        final Operator mOperator;

        // The value of a key can take either 1 of 3 forms: String, Integer, or Boolean.
        // It cannot have multiple values.
        @Nullable
        final String mStringValue;
        @Nullable
        final Integer mIntValue;
        @Nullable
        final Boolean mBoolValue;

        public AtomicFormula(Key key, Operator operator, String stringValue) {
            // TODO: Add validators
            this.mKey = key;
            this.mOperator = operator;
            this.mStringValue = stringValue;
            this.mIntValue = null;
            this.mBoolValue = null;
        }

        public AtomicFormula(Key key, Operator operator, Integer intValue) {
            // TODO: Add validators
            this.mKey = key;
            this.mOperator = operator;
            this.mStringValue = null;
            this.mIntValue = intValue;
            this.mBoolValue = null;
        }

        public AtomicFormula(Key key, Operator operator, Boolean boolValue) {
            // TODO: Add validators
            this.mKey = key;
            this.mOperator = operator;
            this.mStringValue = null;
            this.mIntValue = null;
            this.mBoolValue = boolValue;
        }
    }

    /**
     * Represents a complex formula consisting of other simple and complex formulas.
     */
    public static final class OpenFormula extends Formula {

        final Connector mConnector;
        final Formula mMainFormula;
        final Formula mAuxiliaryFormula;

        public OpenFormula(Connector connector, Formula mainFormula,
                @Nullable Formula auxiliaryFormula) {
            this.mConnector = checkNotNull(connector);
            this.mMainFormula = checkNotNull(mainFormula);
            this.mAuxiliaryFormula = auxiliaryFormula;
        }
    }
}
