/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.timezonedetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * A test support class used for tracking a piece of state in test objects like fakes and mocks.
 * State can optionally be initialized using {@link #init}, which sets the value to an initial
 * value, but is not treated as a change. Calls to {@link #set} are tracked and can be checked for
 * during tests. The change-tracking can be cleared by calling {@link #commitLatest}, which puts the
 * object into an unchanged state and sets the initial value to the latest value passed to
 * {@link #set}.
 */
public class TestState<T> {
    private T mInitialValue;
    private final ArrayList<T> mValues = new ArrayList<>(5);

    /** Sets the initial value for the state. */
    public void init(T value) {
        mValues.clear();
        mInitialValue = value;
    }

    /** Sets the latest value for the state. */
    public void set(T value) {
        mValues.add(value);
    }

    /** Returns {@code true} if {@link #set} has been called. */
    public boolean hasBeenSet() {
        return mValues.size() > 0;
    }

    /** Fails if {@link #set} has been called. */
    public void assertHasNotBeenSet() {
        assertFalse(hasBeenSet());
    }

    /** Fails if {@link #set} has not been called. */
    public void assertHasBeenSet() {
        assertTrue(hasBeenSet());
    }

    /**
     * Clears tracked changes and re-initializes using the latest set value as the initial value.
     */
    public void commitLatest() {
        if (hasBeenSet()) {
            mInitialValue = mValues.get(mValues.size() - 1);
            mValues.clear();
        }
    }

    /** Asserts the latest value passed to {@link #set} equals {@code expected}. */
    public void assertLatestEquals(T expected) {
        assertEquals(expected, getLatest());
    }

    /** Asserts the number of times {@link #set} has been called. */
    public void assertChangeCount(int expectedCount) {
        assertEquals(expectedCount, getChangeCount());
    }

    /** Asserts the value has been {@link #set} to the expected values in the order given. */
    public void assertChanges(T... expected) {
        assertEquals(Arrays.asList(expected), mValues);
    }

    /**
     * Returns the latest value passed to {@link #set}. If {@link #set} hasn't been called then the
     * initial value is returned.
     */
    public T getLatest() {
        if (hasBeenSet()) {
            return mValues.get(mValues.size() - 1);
        }
        return mInitialValue;
    }

    /** Returns the number of times {@link #set} has been called. */
    public int getChangeCount() {
        return mValues.size();
    }

    /**
     * Returns an historic value of the state. Values for {@code age} can be from {@code 0}, the
     * latest value, through {@code getChangeCount() - 1}, which returns the oldest change, to
     * {@code getChangeCount()}, which returns the initial value. Values outside of this range will
     * cause {@link IndexOutOfBoundsException} to be thrown.
     */
    public T getPrevious(int age) {
        int size = mValues.size();
        if (age < size) {
            return mValues.get(size - 1 - age);
        } else if (age == size) {
            return mInitialValue;
        }
        throw new IndexOutOfBoundsException("age=" + age + " is too big.");
    }
}
