/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.os;

import android.os.Parcel;
import android.os.Parcelable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodRedirect;
import android.ravenwood.annotation.RavenwoodRedirectionClass;
import android.ravenwood.annotation.RavenwoodReplace;

import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

/**
 * Performs per-state counting of multi-element values over time. The class' behavior is illustrated
 * by this example:
 * <pre>
 *   // At 0 ms, the state of the tracked object is 0
 *   counter.setState(0, 0);
 *
 *   // At 1000 ms, the state changes to 1
 *   counter.setState(1, 1000);
 *
 *   // At 3000 ms, the tracked values are updated to {30, 300}
 *   counter.updateValues(arrayContainer, new long[]{{30, 300}, 3000);
 *
 *   // The values are distributed between states 0 and 1 according to the time
 *   // spent in those respective states. In this specific case, 1000 and 2000 ms.
 *   counter.getCounts(array, 0);
 *   // array now has values {10, 100}
 *   counter.getCounts(array, 1);
 *   // array now has values {20, 200}
 * </pre>
 *
 * The tracked values are expected to increase monotonically.
 *
 * @hide
 */
@RavenwoodKeepWholeClass
@RavenwoodRedirectionClass("LongArrayMultiStateCounter_ravenwood")
public final class LongArrayMultiStateCounter implements Parcelable {
    private static volatile NativeAllocationRegistry sRegistry;
    private final int mStateCount;
    private final int mLength;

    // Visible to other objects in this package so that it can be passed to @CriticalNative
    // methods.
    final long mNativeObject;

    public LongArrayMultiStateCounter(int stateCount, int arrayLength) {
        Preconditions.checkArgumentPositive(stateCount, "stateCount must be greater than 0");
        mStateCount = stateCount;
        mLength = arrayLength;
        mNativeObject = native_init(stateCount, arrayLength);
        registerNativeAllocation();
    }

    @RavenwoodReplace
    private void registerNativeAllocation() {
        if (sRegistry == null) {
            synchronized (LongArrayMultiStateCounter.class) {
                if (sRegistry == null) {
                    sRegistry = NativeAllocationRegistry.createMalloced(
                            LongArrayMultiStateCounter.class.getClassLoader(),
                            native_getReleaseFunc());
                }
            }
        }
        sRegistry.registerNativeAllocation(this, mNativeObject);
    }

    private void registerNativeAllocation$ravenwood() {
        // No-op under ravenwood
    }

    private LongArrayMultiStateCounter(Parcel in) {
        mNativeObject = native_initFromParcel(in);
        registerNativeAllocation();

        mStateCount = native_getStateCount(mNativeObject);
        mLength = native_getArrayLength(mNativeObject);
    }

    public int getStateCount() {
        return mStateCount;
    }

    public int getArrayLength() {
        return mLength;
    }

    /**
     * Enables or disables the counter.  When the counter is disabled, it does not
     * accumulate counts supplied by the {@link #updateValues} method.
     */
    public void setEnabled(boolean enabled, long timestampMs) {
        native_setEnabled(mNativeObject, enabled, timestampMs);
    }

    /**
     * Sets the current state to the supplied value.
     */
    public void setState(int state, long timestampMs) {
        if (state < 0 || state >= mStateCount) {
            throw new IllegalArgumentException(
                    "State: " + state + ", outside the range: [0-" + (mStateCount - 1) + "]");
        }
        native_setState(mNativeObject, state, timestampMs);
    }

    /**
     * Copies time-in-state and timestamps from the supplied counter.
     */
    public void copyStatesFrom(LongArrayMultiStateCounter counter) {
        if (mStateCount != counter.mStateCount) {
            throw new IllegalArgumentException(
                    "State count is not the same: " + mStateCount + " vs. " + counter.mStateCount);
        }
        native_copyStatesFrom(mNativeObject, counter.mNativeObject);
    }

    /**
     * Sets the new values for the given state.
     */
    public void setValues(int state, long[] values) {
        if (state < 0 || state >= mStateCount) {
            throw new IllegalArgumentException(
                    "State: " + state + ", outside the range: [0-" + (mStateCount - 1) + "]");
        }
        if (values.length != mLength) {
            throw new IllegalArgumentException(
                    "Invalid array length: " + values.length + ", expected: " + mLength);
        }
        native_setValues(mNativeObject, state, values);
    }

    /**
     * Adds the supplied values to the current accumulated values in the counter.
     */
    public void incrementValues(long[] values, long timestampMs) {
        native_incrementValues(mNativeObject, values, timestampMs);
    }

    /**
     * Sets the new values.  The delta between the previously set values and these values
     * is distributed among the state according to the time the object spent in those states
     * since the previous call to updateValues.
     */
    public void updateValues(long[] values, long timestampMs) {
        if (values.length != mLength) {
            throw new IllegalArgumentException(
                    "Invalid array length: " + values.length + ", expected: " + mLength);
        }
        native_updateValues(mNativeObject, values, timestampMs);
    }

    /**
     * Adds the supplied values to the current accumulated values in the counter.
     */
    public void addCounts(long[] counts) {
        if (counts.length != mLength) {
            throw new IllegalArgumentException(
                    "Invalid array length: " + counts.length + ", expected: " + mLength);
        }
        native_addCounts(mNativeObject, counts);
    }

    /**
     * Resets the accumulated counts to 0.
     */
    public void reset() {
        native_reset(mNativeObject);
    }

    /**
     * Populates the array with the accumulated counts for the specified state.
     */
    public void getCounts(long[] counts, int state) {
        if (state < 0 || state >= mStateCount) {
            throw new IllegalArgumentException(
                    "State: " + state + ", outside the range: [0-" + mStateCount + "]");
        }
        if (counts.length != mLength) {
            throw new IllegalArgumentException(
                    "Invalid array length: " + counts.length + ", expected: " + mLength);
        }
        native_getCounts(mNativeObject, counts, state);
    }

    @Override
    public String toString() {
        return native_toString(mNativeObject);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        native_writeToParcel(mNativeObject, dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LongArrayMultiStateCounter> CREATOR = new Creator<>() {
        @Override
        public LongArrayMultiStateCounter createFromParcel(Parcel in) {
            return new LongArrayMultiStateCounter(in);
        }

        @Override
        public LongArrayMultiStateCounter[] newArray(int size) {
            return new LongArrayMultiStateCounter[size];
        }
    };


    @CriticalNative
    @RavenwoodRedirect
    private static native long native_init(int stateCount, int arrayLength);

    @CriticalNative
    @RavenwoodRedirect
    private static native long native_getReleaseFunc();

    @CriticalNative
    @RavenwoodRedirect
    private static native void native_setEnabled(long nativeObject, boolean enabled,
            long timestampMs);

    @CriticalNative
    @RavenwoodRedirect
    private static native void native_setState(long nativeObject, int state, long timestampMs);

    @CriticalNative
    @RavenwoodRedirect
    private static native void native_copyStatesFrom(long nativeObjectTarget,
            long nativeObjectSource);

    @FastNative
    @RavenwoodRedirect
    private static native void native_setValues(long nativeObject, int state, long[] values);

    @FastNative
    @RavenwoodRedirect
    private static native void native_updateValues(long nativeObject, long[] values,
            long timestampMs);

    @FastNative
    @RavenwoodRedirect
    private static native void native_incrementValues(long nativeObject, long[] values,
            long timestampMs);

    @FastNative
    @RavenwoodRedirect
    private static native void native_addCounts(long nativeObject, long[] counts);

    @CriticalNative
    @RavenwoodRedirect
    private static native void native_reset(long nativeObject);

    @FastNative
    @RavenwoodRedirect
    private static native void native_getCounts(long nativeObject, long[] counts, int state);

    @FastNative
    @RavenwoodRedirect
    private static native String native_toString(long nativeObject);

    @FastNative
    @RavenwoodRedirect
    private static native void native_writeToParcel(long nativeObject, Parcel dest, int flags);

    @FastNative
    @RavenwoodRedirect
    private static native long native_initFromParcel(Parcel parcel);

    @CriticalNative
    @RavenwoodRedirect
    private static native int native_getStateCount(long nativeObject);

    @CriticalNative
    @RavenwoodRedirect
    private static native int native_getArrayLength(long nativeObject);
}
