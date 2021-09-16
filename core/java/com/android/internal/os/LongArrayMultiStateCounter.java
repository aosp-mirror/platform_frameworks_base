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
 *   arrayContainer.setValues(new long[]{{30, 300}};
 *   counter.updateValues(arrayContainer, 3000);
 *
 *   // The values are distributed between states 0 and 1 according to the time
 *   // spent in those respective states. In this specific case, 1000 and 2000 ms.
 *   counter.getValues(arrayContainer, 0);
 *   // arrayContainer now has values {10, 100}
 *   counter.getValues(arrayContainer, 1);
 *   // arrayContainer now has values {20, 200}
 * </pre>
 *
 * The tracked values are expected to increase monotonically.
 *
 * @hide
 */
public final class LongArrayMultiStateCounter implements Parcelable {

    /**
     * Container for a native equivalent of a long[].
     */
    public static class LongArrayContainer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        LongArrayContainer.class.getClassLoader(), native_getReleaseFunc());

        private final long mNativeObject;
        private final int mLength;

        public LongArrayContainer(int length) {
            mLength = length;
            mNativeObject = native_init(length);
            sRegistry.registerNativeAllocation(this, mNativeObject);
        }

        /**
         * Copies the supplied values into the underlying native array.
         */
        public void setValues(long[] array) {
            if (array.length != mLength) {
                throw new IllegalArgumentException(
                        "Invalid array length: " + mLength + ", expected: " + mLength);
            }
            native_setValues(mNativeObject, array);
        }

        /**
         * Copies the underlying native array values to the supplied array.
         */
        public void getValues(long[] array) {
            if (array.length != mLength) {
                throw new IllegalArgumentException(
                        "Invalid array length: " + mLength + ", expected: " + mLength);
            }
            native_getValues(mNativeObject, array);
        }

        @CriticalNative
        private static native long native_init(int length);

        @CriticalNative
        private static native long native_getReleaseFunc();

        @FastNative
        private native void native_setValues(long nativeObject, long[] array);

        @FastNative
        private native void native_getValues(long nativeObject, long[] array);
    }

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(
                    LongArrayMultiStateCounter.class.getClassLoader(), native_getReleaseFunc());

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
        sRegistry.registerNativeAllocation(this, mNativeObject);
    }

    private LongArrayMultiStateCounter(Parcel in) {
        mNativeObject = native_initFromParcel(in);
        sRegistry.registerNativeAllocation(this, mNativeObject);

        mStateCount = native_getStateCount(mNativeObject);
        mLength = native_getArrayLength(mNativeObject);
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
                    "State: " + state + ", outside the range: [0-" + mStateCount + "]");
        }
        native_setState(mNativeObject, state, timestampMs);
    }

    /**
     * Sets the new values.  The delta between the previously set values and these values
     * is distributed among the state according to the time the object spent in those states
     * since the previous call to updateValues.
     */
    public void updateValues(LongArrayContainer longArrayContainer, long timestampMs) {
        if (longArrayContainer.mLength != mLength) {
            throw new IllegalArgumentException(
                    "Invalid array length: " + longArrayContainer.mLength + ", expected: "
                            + mLength);
        }
        native_updateValues(mNativeObject, longArrayContainer.mNativeObject, timestampMs);
    }

    /**
     * Resets the accumulated counts to 0.
     */
    public void reset() {
        native_reset(mNativeObject);
    }

    /**
     * Populates longArrayContainer with the accumulated counts for the specified state.
     */
    public void getCounts(LongArrayContainer longArrayContainer, int state) {
        if (state < 0 || state >= mStateCount) {
            throw new IllegalArgumentException(
                    "State: " + state + ", outside the range: [0-" + mStateCount + "]");
        }
        native_getCounts(mNativeObject, longArrayContainer.mNativeObject, state);
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

    public static final Creator<LongArrayMultiStateCounter> CREATOR =
            new Creator<LongArrayMultiStateCounter>() {
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
    private static native long native_init(int stateCount, int arrayLength);

    @CriticalNative
    private static native long native_getReleaseFunc();

    @CriticalNative
    private static native void native_setEnabled(long nativeObject, boolean enabled,
            long timestampMs);

    @CriticalNative
    private static native void native_setState(long nativeObject, int state, long timestampMs);

    @CriticalNative
    private static native void native_updateValues(long nativeObject,
            long longArrayContainerNativeObject, long timestampMs);

    @CriticalNative
    private static native void native_reset(long nativeObject);

    @CriticalNative
    private static native void native_getCounts(long nativeObject,
            long longArrayContainerNativeObject, int state);

    @FastNative
    private native String native_toString(long nativeObject);

    @FastNative
    private native void native_writeToParcel(long nativeObject, Parcel dest, int flags);

    @FastNative
    private static native long native_initFromParcel(Parcel parcel);

    @CriticalNative
    private static native int native_getStateCount(long nativeObject);

    @CriticalNative
    private static native int native_getArrayLength(long nativeObject);
}
