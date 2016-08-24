/*
 * Copyright 2016 The Android Open Source Project
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

package com.android.mediaframeworktest.helpers;

import java.util.Collection;
import java.util.Objects;

/**
 * Helper set of methods to perform precondition checks before starting method execution.
 *
 * <p>Typically used to sanity check arguments or the current object state.</p>
 */
/**
 * (non-Javadoc)
 * @see android.hardware.camera2.cts.helpers.Preconditions
 */
public final class Preconditions {

    /**
     * Checks that the value has the expected bitwise flags set.
     *
     * @param argName Name of the argument
     * @param arg Argument to check
     * @param flagsName Name of the bitwise flags
     * @param flags Bit flags to check.
     * @return arg
     *
     * @throws IllegalArgumentException if the bitwise flags weren't set
     */
    public static int checkBitFlags(String argName, int arg, String flagsName, int flags) {
        if ((arg & flags) == 0) {
            throw new IllegalArgumentException(
                    String.format("Argument '%s' must have flags '%s' set", argName, flagsName));
        }

        return arg;
    }

    /**
     * Checks that the value is {@link Object#equals equal} to the expected value.
     *
     * @param argName Name of the argument
     * @param arg Argument to check
     * @param expectedName Name of the expected value
     * @param expectedValue Expected value
     * @return arg
     *
     * @throws IllegalArgumentException if the values were not equal
     */
    public static <T> T checkEquals(String argName, T arg,
            String expectedName, T expectedValue) {
        if (!Objects.equals(arg, expectedValue)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Argument '%s' must be equal to '%s' (was '%s', but expected '%s')",
                            argName, expectedName, arg, expectedValue));
        }

        return arg;
    }

    /**
     * Checks that the value is not {@code null}.
     *
     * <p>
     * Returns the value directly, so you can use {@code checkNotNull("value", value)} inline.
     * </p>
     *
     * @param argName Name of the argument
     * @param arg Argument to check
     * @return arg
     *
     * @throws NullPointerException if arg was {@code null}
     */
    public static <T> T checkNotNull(String argName, T arg) {
        if (arg == null) {
            throw new NullPointerException("Argument '" + argName + "' must not be null");
        }

        return arg;
    }

    /**
     * Checks that the value is not {@code null}.
     *
     * <p>
     * Returns the value directly, so you can use {@code checkNotNull("value", value)} inline.
     * </p>
     *
     * @param arg Argument to check
     * @return arg
     *
     * @throws NullPointerException if arg was {@code null}
     */
    public static <T> T checkNotNull(T arg) {
        return checkNotNull("", arg);
    }

    /**
     * Checks that the state is currently {@link true}.
     *
     * @param message Message to raise an exception with if the state checking fails.
     * @param state State to check
     *
     * @throws IllegalStateException if state was {@code false}
     *
     * @return The state value (always {@code true}).
     */
    public static boolean checkState(String message, boolean state) {
        if (!state) {
            throw new IllegalStateException(message);
        }

        return state;
    }

        /**
     * Ensures that the {@link Collection} is not {@code null}, and none of its elements are
     * {@code null}.
     *
     * @param value a {@link Collection} of boxed objects
     * @param valueName the name of the argument to use if the check fails
     *
     * @return the validated {@link Collection}
     *
     * @throws NullPointerException if the {@code value} or any of its elements were {@code null}
     */
    public static <T> Collection<T> checkCollectionElementsNotNull(final Collection<T> value,
            final String valueName) {
        if (value == null) {
            throw new NullPointerException(valueName + " must not be null");
        }

        long ctr = 0;
        for (T elem : value) {
            if (elem == null) {
                throw new NullPointerException(
                        String.format("%s[%d] must not be null", valueName, ctr));
            }
            ++ctr;
        }

        return value;
    }

    /**
     * Ensures that the {@link Collection} is not {@code null}, and contains at least one element.
     *
     * @param value a {@link Collection} of boxed elements.
     * @param valueName the name of the argument to use if the check fails.

     * @return the validated {@link Collection}
     *
     * @throws NullPointerException if the {@code value} was {@code null}
     * @throws IllegalArgumentException if the {@code value} was empty
     */
    public static <T> Collection<T> checkCollectionNotEmpty(final Collection<T> value,
            final String valueName) {
        if (value == null) {
            throw new NullPointerException(valueName + " must not be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(valueName + " is empty");
        }
        return value;
    }

    // Suppress default constructor for noninstantiability
    private Preconditions() { throw new AssertionError(); }
}
