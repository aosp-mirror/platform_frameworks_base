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
 * Performs per-state counting of long integers over time.  The tracked "value" is expected
 * to increase monotonously. The counter keeps track of the current state.  When the
 * updateValue method is called, the delta from the previous invocation of this method
 * and the new value is added to the counter corresponding to the current state.  If the
 * state changed in the interim, the delta is distributed proptionally.
 *
 * The class's behavior is illustrated by this example:
 * <pre>
 *   // At 0 ms, the state of the tracked object is 0 and the initial tracked value is 100
 *   counter.setState(0, 0);
 *   counter.updateValue(100, 0);
 *
 *   // At 1000 ms, the state changes to 1
 *   counter.setState(1, 1000);
 *
 *   // At 3000 ms, the tracked value is updated to 130
 *   counter.updateValue(130, 3000);
 *
 *   // The delta (130 - 100 = 30) is distributed between states 0 and 1 according to the time
 *   // spent in those respective states; in this specific case, 1000 and 2000 ms.
 *   long countForState0 == counter.getCount(0);  // 10
 *   long countForState1 == counter.getCount(1);  // 20
 * </pre>
 *
 * The tracked values are expected to increase monotonically.
 *
 * @hide
 */
@RavenwoodKeepWholeClass
@RavenwoodRedirectionClass("LongMultiStateCounter_host")
public final class LongMultiStateCounter implements Parcelable {

    private static NativeAllocationRegistry sRegistry;

    private final int mStateCount;

    // Visible to other objects in this package so that it can be passed to @CriticalNative
    // methods.
    final long mNativeObject;

    public LongMultiStateCounter(int stateCount) {
        Preconditions.checkArgumentPositive(stateCount, "stateCount must be greater than 0");
        mStateCount = stateCount;
        mNativeObject = native_init(stateCount);
        registerNativeAllocation();
    }

    private LongMultiStateCounter(Parcel in) {
        mNativeObject = native_initFromParcel(in);
        registerNativeAllocation();

        mStateCount = native_getStateCount(mNativeObject);
    }

    @RavenwoodReplace
    private void registerNativeAllocation() {
        if (sRegistry == null) {
            synchronized (LongMultiStateCounter.class) {
                if (sRegistry == null) {
                    sRegistry = NativeAllocationRegistry.createMalloced(
                            LongMultiStateCounter.class.getClassLoader(), native_getReleaseFunc());
                }
            }
        }
        sRegistry.registerNativeAllocation(this, mNativeObject);
    }

    private void registerNativeAllocation$ravenwood() {
        // No-op under ravenwood
    }

    public int getStateCount() {
        return mStateCount;
    }

    /**
     * Enables or disables the counter.  When the counter is disabled, it does not
     * accumulate counts supplied by the {@link #updateValue} method.
     */
    public void setEnabled(boolean enabled, long timestampMs) {
        native_setEnabled(mNativeObject, enabled, timestampMs);
    }

    /**
     * Sets the current state to the supplied value.
     *
     * @param state The new state
     * @param timestampMs The time when the state change occurred, e.g.
     *                    SystemClock.elapsedRealtime()
     */
    public void setState(int state, long timestampMs) {
        if (state < 0 || state >= mStateCount) {
            throw new IllegalArgumentException(
                    "State: " + state + ", outside the range: [0-" + (mStateCount - 1) + "]");
        }
        native_setState(mNativeObject, state, timestampMs);
    }

    /**
     * Sets the new values.  The delta between the previously set value and this value
     * is distributed among the state according to the time the object spent in those states
     * since the previous call to updateValue.
     *
     * @return The delta between the previous value and the new value.
     */
    public long updateValue(long value, long timestampMs) {
        return native_updateValue(mNativeObject, value, timestampMs);
    }

    /**
     * Adds the supplied values to the current accumulated values in the counter.
     */
    public void incrementValue(long count, long timestampMs) {
        native_incrementValue(mNativeObject, count, timestampMs);
    }

    /**
     * Adds the supplied values to the current accumulated values in the counter.
     */
    public void addCount(long count) {
        native_addCount(mNativeObject, count);
    }

    /**
     * Resets the accumulated counts to 0.
     */
    public void reset() {
        native_reset(mNativeObject);
    }

    /**
     * Returns the accumulated count for the specified state.
     */
    public long getCount(int state) {
        if (state < 0 || state >= mStateCount) {
            throw new IllegalArgumentException(
                    "State: " + state + ", outside the range: [0-" + mStateCount + "]");
        }
        return native_getCount(mNativeObject, state);
    }

    /**
     * Returns the total accumulated count across all states.
     */
    public long getTotalCount() {
        long total = 0;
        for (int state = 0; state < mStateCount; state++) {
            total += native_getCount(mNativeObject, state);
        }
        return total;
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

    public static final Creator<LongMultiStateCounter> CREATOR =
            new Creator<LongMultiStateCounter>() {
                @Override
                public LongMultiStateCounter createFromParcel(Parcel in) {
                    return new LongMultiStateCounter(in);
                }

                @Override
                public LongMultiStateCounter[] newArray(int size) {
                    return new LongMultiStateCounter[size];
                }
            };


    @CriticalNative
    @RavenwoodRedirect
    private static native long native_init(int stateCount);

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
    private static native long native_updateValue(long nativeObject, long value, long timestampMs);

    @CriticalNative
    @RavenwoodRedirect
    private static native void native_incrementValue(long nativeObject, long increment,
            long timestampMs);

    @CriticalNative
    @RavenwoodRedirect
    private static native void native_addCount(long nativeObject, long count);

    @CriticalNative
    @RavenwoodRedirect
    private static native void native_reset(long nativeObject);

    @CriticalNative
    @RavenwoodRedirect
    private static native long native_getCount(long nativeObject, int state);

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
}
