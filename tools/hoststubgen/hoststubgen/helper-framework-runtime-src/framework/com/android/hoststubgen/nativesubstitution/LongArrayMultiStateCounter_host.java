/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.hoststubgen.nativesubstitution;

import android.os.BadParcelableException;
import android.os.Parcel;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Native implementation substitutions for the LongArrayMultiStateCounter class.
 */
public class LongArrayMultiStateCounter_host {

    /**
     * A reimplementation of {@link com.android.internal.os.LongArrayMultiStateCounter}, only in
     * Java instead of native.  The majority of the code (in C++) can be found in
     * /frameworks/native/libs/battery/MultiStateCounter.h
     */
    private static class LongArrayMultiStateCounterRavenwood {
        private final int mStateCount;
        private final int mArrayLength;
        private int mCurrentState;
        private long mLastStateChangeTimestampMs = -1;
        private long mLastUpdateTimestampMs = -1;
        private boolean mEnabled = true;

        private static class State {
            private long mTimeInStateSinceUpdate;
            private long[] mCounter;
        }

        private final State[] mStates;
        private final long[] mValues;
        private final long[] mDelta;

        LongArrayMultiStateCounterRavenwood(int stateCount, int arrayLength) {
            mStateCount = stateCount;
            mArrayLength = arrayLength;
            mStates = new State[stateCount];
            for (int i = 0; i < mStateCount; i++) {
                mStates[i] = new State();
                mStates[i].mCounter = new long[mArrayLength];
            }
            mValues = new long[mArrayLength];
            mDelta = new long[mArrayLength];
        }

        public void setEnabled(boolean enabled, long timestampMs) {
            if (enabled == mEnabled) {
                return;
            }

            if (!enabled) {
                setState(mCurrentState, timestampMs);
                mEnabled = false;
            } else {
                if (timestampMs < mLastUpdateTimestampMs) {
                    timestampMs = mLastUpdateTimestampMs;
                }

                if (mLastStateChangeTimestampMs >= 0) {
                    mLastStateChangeTimestampMs = timestampMs;
                }
                mEnabled = true;
            }
        }

        public void setState(int state, long timestampMs) {
            if (mEnabled && mLastStateChangeTimestampMs >= 0 && mLastUpdateTimestampMs >= 0) {
                if (timestampMs < mLastUpdateTimestampMs) {
                    timestampMs = mLastUpdateTimestampMs;
                }

                if (timestampMs >= mLastStateChangeTimestampMs) {
                    mStates[mCurrentState].mTimeInStateSinceUpdate +=
                            timestampMs - mLastStateChangeTimestampMs;
                } else {
                    for (int i = 0; i < mStateCount; i++) {
                        mStates[i].mTimeInStateSinceUpdate = 0;
                    }
                }
            }
            mCurrentState = state;
            mLastStateChangeTimestampMs = timestampMs;
        }

        public void setValue(int state, long[] values) {
            System.arraycopy(values, 0, mStates[state].mCounter, 0, mArrayLength);
        }

        public void updateValue(long[] values, long timestampMs) {
            if (mEnabled || mLastUpdateTimestampMs < mLastStateChangeTimestampMs) {
                if (timestampMs < mLastStateChangeTimestampMs) {
                    timestampMs = mLastStateChangeTimestampMs;
                }

                setState(mCurrentState, timestampMs);

                if (mLastUpdateTimestampMs >= 0) {
                    if (timestampMs > mLastUpdateTimestampMs) {
                        if (delta(mValues, values, mDelta)) {
                            long timeSinceUpdate = timestampMs - mLastUpdateTimestampMs;
                            for (int i = 0; i < mStateCount; i++) {
                                long timeInState = mStates[i].mTimeInStateSinceUpdate;
                                if (timeInState > 0) {
                                    add(mStates[i].mCounter, mDelta, timeInState, timeSinceUpdate);
                                    mStates[i].mTimeInStateSinceUpdate = 0;
                                }
                            }
                        } else {
                            throw new RuntimeException();
                        }
                    } else if (timestampMs < mLastUpdateTimestampMs) {
                        throw new RuntimeException();
                    }
                }
            }
            System.arraycopy(values, 0, mValues, 0, mArrayLength);
            mLastUpdateTimestampMs = timestampMs;
        }

        public void incrementValues(long[] delta, long timestampMs) {
            long[] values = Arrays.copyOf(mValues, mValues.length);
            for (int i = 0; i < mArrayLength; i++) {
                values[i] += delta[i];
            }
            updateValue(values, timestampMs);
        }

        public void getValues(long[] values, int state) {
            System.arraycopy(mStates[state].mCounter, 0, values, 0, mArrayLength);
        }

        public void reset() {
            mLastStateChangeTimestampMs = -1;
            mLastUpdateTimestampMs = -1;
            for (int i = 0; i < mStateCount; i++) {
                mStates[i].mTimeInStateSinceUpdate = 0;
                Arrays.fill(mStates[i].mCounter, 0);
            }
        }

        public void writeToParcel(Parcel parcel) {
            parcel.writeInt(mStateCount);
            parcel.writeInt(mArrayLength);
            for (int i = 0; i < mStateCount; i++) {
                parcel.writeLongArray(mStates[i].mCounter);
            }
        }

        public void initFromParcel(Parcel parcel) {
            try {
                for (int i = 0; i < mStateCount; i++) {
                    parcel.readLongArray(mStates[i].mCounter);
                }
            } catch (Exception e) {
                throw new BadParcelableException(e);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int state = 0; state < mStateCount; state++) {
                if (state != 0) {
                    sb.append(", ");
                }
                sb.append(state).append(": {");
                for (int i = 0; i < mStates[state].mCounter.length; i++) {
                    if (i != 0) {
                        sb.append(", ");
                    }
                    sb.append(mStates[state].mCounter[i]);
                }
                sb.append("}");
            }
            sb.append("]");
            if (mLastUpdateTimestampMs >= 0) {
                sb.append(" updated: ").append(mLastUpdateTimestampMs);
            }
            if (mLastStateChangeTimestampMs >= 0) {
                sb.append(" currentState: ").append(mCurrentState);
                if (mLastStateChangeTimestampMs > mLastUpdateTimestampMs) {
                    sb.append(" stateChanged: ").append(mLastStateChangeTimestampMs);
                }
            } else {
                sb.append(" currentState: none");
            }
            return sb.toString();
        }

        private boolean delta(long[] values1, long[] values2, long[] delta) {
            if (delta.length != mArrayLength) {
                throw new RuntimeException();
            }

            boolean is_delta_valid = true;
            for (int i = 0; i < mArrayLength; i++) {
                if (values2[i] >= values1[i]) {
                    delta[i] = values2[i] - values1[i];
                } else {
                    delta[i] = 0;
                    is_delta_valid = false;
                }
            }

            return is_delta_valid;
        }

        private void add(long[] counter, long[] delta, long numerator, long denominator) {
            if (numerator != denominator) {
                for (int i = 0; i < mArrayLength; i++) {
                    counter[i] += delta[i] * numerator / denominator;
                }
            } else {
                for (int i = 0; i < mArrayLength; i++) {
                    counter[i] += delta[i];
                }
            }
        }
    }

    public static class LongArrayContainer_host {
        private static final HashMap<Long, long[]> sInstances = new HashMap<>();
        private static long sNextId = 1;

        public static long native_init(int arrayLength) {
            long[] array = new long[arrayLength];
            long instanceId = sNextId++;
            sInstances.put(instanceId, array);
            return instanceId;
        }

        static long[] getInstance(long instanceId) {
            return sInstances.get(instanceId);
        }

        public static void native_setValues(long instanceId, long[] values) {
            System.arraycopy(values, 0, getInstance(instanceId), 0, values.length);
        }

        public static void native_getValues(long instanceId, long[] values) {
            System.arraycopy(getInstance(instanceId), 0, values, 0, values.length);
        }

        public static boolean native_combineValues(long instanceId, long[] array, int[] indexMap) {
            long[] values = getInstance(instanceId);

            boolean nonZero = false;
            Arrays.fill(array, 0);

            for (int i = 0; i < values.length; i++) {
                int index = indexMap[i];
                if (index < 0 || index >= array.length) {
                    throw new IndexOutOfBoundsException("Index " + index + " is out of bounds: [0, "
                                                        + (array.length - 1) + "]");
                }
                if (values[i] != 0) {
                    array[index] += values[i];
                    nonZero = true;
                }
            }
            return nonZero;
        }
    }

    private static final HashMap<Long, LongArrayMultiStateCounterRavenwood> sInstances =
            new HashMap<>();
    private static long sNextId = 1;

    public static long native_init(int stateCount, int arrayLength) {
        LongArrayMultiStateCounterRavenwood instance = new LongArrayMultiStateCounterRavenwood(
                stateCount, arrayLength);
        long instanceId = sNextId++;
        sInstances.put(instanceId, instance);
        return instanceId;
    }

    private static LongArrayMultiStateCounterRavenwood getInstance(long instanceId) {
        return sInstances.get(instanceId);
    }

    public static void native_setEnabled(long instanceId, boolean enabled,
            long timestampMs) {
        getInstance(instanceId).setEnabled(enabled, timestampMs);
    }

    public static int native_getStateCount(long instanceId) {
        return getInstance(instanceId).mStateCount;
    }

    public static int native_getArrayLength(long instanceId) {
        return getInstance(instanceId).mArrayLength;
    }

    public static void native_setValues(long instanceId, int state, long containerInstanceId) {
        getInstance(instanceId).setValue(state,
                LongArrayContainer_host.getInstance(containerInstanceId));
    }

    public static void native_updateValues(long instanceId, long containerInstanceId,
            long timestampMs) {
        getInstance(instanceId).updateValue(
                LongArrayContainer_host.getInstance(containerInstanceId), timestampMs);
    }

    public static void native_setState(long instanceId, int state, long timestampMs) {
        getInstance(instanceId).setState(state, timestampMs);
    }

    public static void native_incrementValues(long instanceId, long containerInstanceId,
            long timestampMs) {
        getInstance(instanceId).incrementValues(
                LongArrayContainer_host.getInstance(containerInstanceId), timestampMs);
    }

    public static void native_getCounts(long instanceId, long containerInstanceId, int state) {
        getInstance(instanceId).getValues(LongArrayContainer_host.getInstance(containerInstanceId),
                state);
    }

    public static void native_reset(long instanceId) {
        getInstance(instanceId).reset();
    }

    public static void native_writeToParcel(long instanceId, Parcel parcel, int flags) {
        getInstance(instanceId).writeToParcel(parcel);
    }

    public static long native_initFromParcel(Parcel parcel) {
        int stateCount = parcel.readInt();
        if (stateCount < 0 || stateCount > 0xEFFF) {
            throw new BadParcelableException("stateCount out of range");
        }
        // LongArrayMultiStateCounter.cpp uses AParcel, which throws on out-of-data.
        if (parcel.dataPosition() >= parcel.dataSize()) {
            throw new RuntimeException("Bad parcel");
        }
        int arrayLength = parcel.readInt();
        if (parcel.dataPosition() >= parcel.dataSize()) {
            throw new RuntimeException("Bad parcel");
        }
        long instanceId = native_init(stateCount, arrayLength);
        getInstance(instanceId).initFromParcel(parcel);
        if (parcel.dataPosition() > parcel.dataSize()) {
            throw new RuntimeException("Bad parcel");
        }
        return instanceId;
    }

    public static String native_toString(long instanceId) {
        return getInstance(instanceId).toString();
    }
}
