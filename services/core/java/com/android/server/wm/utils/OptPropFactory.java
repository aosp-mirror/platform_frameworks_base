/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm.utils;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.util.Slog;

import java.util.function.BooleanSupplier;

/**
 * Utility class which helps with handling with properties to opt-in or
 * opt-out a specific feature.
 */
public class OptPropFactory {

    @NonNull
    private final PackageManager mPackageManager;

    @NonNull
    private final String mPackageName;

    /**
     * Object responsible to handle optIn and optOut properties.
     *
     * @param packageManager The PackageManager reference
     * @param packageName    The name of the package.
     */
    public OptPropFactory(@NonNull PackageManager packageManager, @NonNull String packageName) {
        mPackageManager = packageManager;
        mPackageName = packageName;
    }

    /**
     * Creates an OptProp for the given property
     *
     * @param propertyName The name of the property.
     * @return The OptProp for the given property
     */
    @NonNull
    public OptProp create(@NonNull String propertyName) {
        return OptProp.create(
                () -> mPackageManager.getProperty(propertyName, mPackageName).getBoolean(),
                propertyName);
    }

    /**
     * Creates an OptProp for the given property behind a gate condition.
     *
     * @param propertyName  The name of the property.
     * @param gateCondition If this resolves to false, the property is unset. This is evaluated at
     *                      every interaction with the OptProp.
     * @return The OptProp for the given property
     */
    @NonNull
    public OptProp create(@NonNull String propertyName, @NonNull BooleanSupplier gateCondition) {
        return OptProp.create(
                () -> mPackageManager.getProperty(propertyName, mPackageName).getBoolean(),
                propertyName,
                gateCondition);
    }

    @FunctionalInterface
    private interface ThrowableBooleanSupplier {
        boolean get() throws Exception;
    }

    public static class OptProp {

        private static final int VALUE_UNSET = -2;
        private static final int VALUE_UNDEFINED = -1;
        private static final int VALUE_FALSE = 0;
        private static final int VALUE_TRUE = 1;

        @IntDef(prefix = {"VALUE_"}, value = {
                VALUE_UNSET,
                VALUE_UNDEFINED,
                VALUE_FALSE,
                VALUE_TRUE,
        })
        @interface OptionalValue {}

        private static final String TAG = "OptProp";

        // The condition is evaluated every time the OptProp state is accessed.
        @NonNull
        private final BooleanSupplier mCondition;

        // This is evaluated only once in the lifetime of an OptProp.
        @NonNull
        private final ThrowableBooleanSupplier mValueSupplier;

        @NonNull
        private final String mPropertyName;

        @OptionalValue
        private int mValue = VALUE_UNDEFINED;

        private OptProp(@NonNull ThrowableBooleanSupplier valueSupplier,
                @NonNull String propertyName,
                @NonNull BooleanSupplier condition) {
            mValueSupplier = valueSupplier;
            mPropertyName = propertyName;
            mCondition = condition;
        }

        @NonNull
        private static OptProp create(@NonNull ThrowableBooleanSupplier valueSupplier,
                @NonNull String propertyName) {
            return new OptProp(valueSupplier, propertyName, () -> true);
        }

        @NonNull
        private static OptProp create(@NonNull ThrowableBooleanSupplier valueSupplier,
                @NonNull String propertyName, @NonNull BooleanSupplier condition) {
            return new OptProp(valueSupplier, propertyName, condition);
        }

        /**
         * @return {@code true} when the guarding condition is {@code true} and the property has
         * been explicitly set to {@code true}. {@code false} otherwise. The guarding condition is
         * evaluated every time this method is invoked.
         */
        public boolean isTrue() {
            return mCondition.getAsBoolean() && getValue() == VALUE_TRUE;
        }

        /**
         * @return {@code true} when the guarding condition is {@code true} and the property has
         * been explicitly set to {@code false}. {@code false} otherwise. The guarding condition is
         * evaluated every time this method is invoked.
         */
        public boolean isFalse() {
            return mCondition.getAsBoolean() && getValue() == VALUE_FALSE;
        }

        /**
         * Returns {@code true} when the following conditions are met:
         * <ul>
         *     <li>{@code gatingCondition} doesn't evaluate to {@code false}
         *     <li>App developers didn't opt out with a component {@code property}
         *     <li>App developers opted in with a component {@code property} or an OEM opted in with
         *     a per-app override
         * </ul>
         *
         * <p>This is used for the treatments that are enabled only on per-app basis.
         */
        public boolean shouldEnableWithOverrideAndProperty(boolean overrideValue) {
            if (!mCondition.getAsBoolean()) {
                return false;
            }
            if (getValue() == VALUE_FALSE) {
                return false;
            }
            return getValue() == VALUE_TRUE || overrideValue;
        }

        /**
         * Returns {@code true} when the following conditions are met:
         * <ul>
         *     <li>{@code gatingCondition} doesn't evaluate to {@code false}
         *     <li>App developers didn't opt out with a component {@code property}
         *     <li>OEM opted in with a per-app override
         * </ul>
         *
         * <p>This is used for the treatments that are enabled based with the heuristic but can be
         * disabled on per-app basis by OEMs or app developers.
         */
        public boolean shouldEnableWithOptInOverrideAndOptOutProperty(
                boolean overrideValue) {
            if (!mCondition.getAsBoolean()) {
                return false;
            }
            return getValue() != VALUE_FALSE && overrideValue;
        }

        /**
         * Returns {@code true} when the following conditions are met:
         * <ul>
         *     <li>{@code gatingCondition} doesn't resolve to {@code false}
         *     <li>OEM didn't opt out with a per-app override
         *     <li>App developers didn't opt out with a component {@code property}
         * </ul>
         *
         * <p>This is used for the treatments that are enabled based with the heuristic but can be
         * disabled on per-app basis by OEMs or app developers.
         */
        public boolean shouldEnableWithOptOutOverrideAndProperty(boolean overrideValue) {
            if (!mCondition.getAsBoolean()) {
                return false;
            }
            return getValue() != VALUE_FALSE && !overrideValue;
        }

        @OptionalValue
        private int getValue() {
            if (mValue == VALUE_UNDEFINED) {
                try {
                    final Boolean value = mValueSupplier.get();
                    if (TRUE.equals(value)) {
                        mValue = VALUE_TRUE;
                    } else if (FALSE.equals(value)) {
                        mValue = VALUE_FALSE;
                    } else {
                        mValue = VALUE_UNSET;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Cannot read opt property " + mPropertyName);
                    mValue = VALUE_UNSET;
                }
            }
            return mValue;
        }
    }
}
