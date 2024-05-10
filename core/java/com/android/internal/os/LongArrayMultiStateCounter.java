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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

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
@android.ravenwood.annotation.RavenwoodKeepWholeClass
@android.ravenwood.annotation.RavenwoodNativeSubstitutionClass(
        "com.android.hoststubgen.nativesubstitution.LongArrayMultiStateCounter_host")
public final class LongArrayMultiStateCounter implements Parcelable {

    /**
     * Container for a native equivalent of a long[].
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    @android.ravenwood.annotation.RavenwoodNativeSubstitutionClass(
            "com.android.hoststubgen.nativesubstitution"
            + ".LongArrayMultiStateCounter_host$LongArrayContainer_host")
    public static class LongArrayContainer {
        private static NativeAllocationRegistry sRegistry;

        // Visible to other objects in this package so that it can be passed to @CriticalNative
        // methods.
        final long mNativeObject;
        private final int mLength;

        public LongArrayContainer(int length) {
            mLength = length;
            mNativeObject = native_init(length);
            registerNativeAllocation();
        }

        @android.ravenwood.annotation.RavenwoodReplace
        private void registerNativeAllocation() {
            if (sRegistry == null) {
                synchronized (LongArrayMultiStateCounter.class) {
                    if (sRegistry == null) {
                        sRegistry = NativeAllocationRegistry.createMalloced(
                                LongArrayContainer.class.getClassLoader(), native_getReleaseFunc());
                    }
                }
            }
            sRegistry.registerNativeAllocation(this, mNativeObject);
        }

        private void registerNativeAllocation$ravenwood() {
            // No-op under ravenwood
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

        /**
         * Combines contained values into a smaller array by aggregating them
         * according to an index map.
         */
        public boolean combineValues(long[] array, int[] indexMap) {
            if (indexMap.length != mLength) {
                throw new IllegalArgumentException(
                        "Wrong index map size " + indexMap.length + ", expected " + mLength);
            }
            return native_combineValues(mNativeObject, array, indexMap);
        }

        @Override
        public String toString() {
            final long[] array = new long[mLength];
            getValues(array);
            return Arrays.toString(array);
        }

        @CriticalNative
        private static native long native_init(int length);

        @CriticalNative
        private static native long native_getReleaseFunc();

        @FastNative
        private static native void native_setValues(long nativeObject, long[] array);

        @FastNative
        private static native void native_getValues(long nativeObject, long[] array);

        @FastNative
        private static native boolean native_combineValues(long nativeObject, long[] array,
                int[] indexMap);
    }

    private static volatile NativeAllocationRegistry sRegistry;
    private static final AtomicReference<LongArrayContainer> sTmpArrayContainer =
            new AtomicReference<>();

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

    @android.ravenwood.annotation.RavenwoodReplace
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
        LongArrayContainer container = sTmpArrayContainer.getAndSet(null);
        if (container == null || container.mLength != values.length) {
            container = new LongArrayContainer(values.length);
        }
        container.setValues(values);
        native_setValues(mNativeObject, state, container.mNativeObject);
        sTmpArrayContainer.set(container);
    }

    /**
     * Sets the new values.  The delta between the previously set values and these values
     * is distributed among the state according to the time the object spent in those states
     * since the previous call to updateValues.
     */
    public void updateValues(long[] values, long timestampMs) {
        LongArrayContainer container = sTmpArrayContainer.getAndSet(null);
        if (container == null || container.mLength != values.length) {
            container = new LongArrayContainer(values.length);
        }
        container.setValues(values);
        updateValues(container, timestampMs);
        sTmpArrayContainer.set(container);
    }

    /**
     * Adds the supplied values to the current accumulated values in the counter.
     */
    public void incrementValues(long[] values, long timestampMs) {
        LongArrayContainer container = sTmpArrayContainer.getAndSet(null);
        if (container == null || container.mLength != values.length) {
            container = new LongArrayContainer(values.length);
        }
        container.setValues(values);
        native_incrementValues(mNativeObject, container.mNativeObject, timestampMs);
        sTmpArrayContainer.set(container);
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
     * Adds the supplied values to the current accumulated values in the counter.
     */
    public void addCounts(LongArrayContainer counts) {
        if (counts.mLength != mLength) {
            throw new IllegalArgumentException(
                    "Invalid array length: " + counts.mLength + ", expected: " + mLength);
        }
        native_addCounts(mNativeObject, counts.mNativeObject);
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
        LongArrayContainer container = sTmpArrayContainer.getAndSet(null);
        if (container == null || container.mLength != counts.length) {
            container = new LongArrayContainer(counts.length);
        }
        getCounts(container, state);
        container.getValues(counts);
        sTmpArrayContainer.set(container);
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
    private static native void native_setValues(long nativeObject, int state,
            long longArrayContainerNativeObject);

    @CriticalNative
    private static native void native_updateValues(long nativeObject,
            long longArrayContainerNativeObject, long timestampMs);

    @CriticalNative
    private static native void native_incrementValues(long nativeObject,
            long longArrayContainerNativeObject, long timestampMs);

    @CriticalNative
    private static native void native_addCounts(long nativeObject,
            long longArrayContainerNativeObject);

    @CriticalNative
    private static native void native_reset(long nativeObject);

    @CriticalNative
    private static native void native_getCounts(long nativeObject,
            long longArrayContainerNativeObject, int state);

    @FastNative
    private static native String native_toString(long nativeObject);

    @FastNative
    private static native void native_writeToParcel(long nativeObject, Parcel dest, int flags);

    @FastNative
    private static native long native_initFromParcel(Parcel parcel);

    @CriticalNative
    private static native int native_getStateCount(long nativeObject);

    @CriticalNative
    private static native int native_getArrayLength(long nativeObject);
}
