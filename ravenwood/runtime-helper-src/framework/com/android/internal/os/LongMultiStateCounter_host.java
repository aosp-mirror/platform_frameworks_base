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

package com.android.internal.os;

import android.os.BadParcelableException;
import android.os.Parcel;

import java.util.HashMap;

/**
 * Native implementation substitutions for the LongMultiStateCounter class.
 */
public class LongMultiStateCounter_host {

    /**
     * A reimplementation of {@link com.android.internal.os.LongMultiStateCounter}, only in
     * Java instead of native.  The majority of the code (in C++) can be found in
     * /frameworks/native/libs/battery/MultiStateCounter.h
     */
    private static class LongMultiStateCounterRavenwood {
        private final int mStateCount;
        private int mCurrentState;
        private long mLastStateChangeTimestampMs = -1;
        private long mLastUpdateTimestampMs = -1;
        private boolean mEnabled = true;

        private static class State {
            private long mTimeInStateSinceUpdate;
            private long mCounter;
        }

        private final State[] mStates;
        private long mValue;

        LongMultiStateCounterRavenwood(int stateCount) {
            mStateCount = stateCount;
            mStates = new State[stateCount];
            for (int i = 0; i < mStateCount; i++) {
                mStates[i] = new State();
            }
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

        public long updateValue(long value, long timestampMs) {
            long returnValue = 0;
            if (mEnabled || mLastUpdateTimestampMs < mLastStateChangeTimestampMs) {
                if (timestampMs < mLastStateChangeTimestampMs) {
                    timestampMs = mLastStateChangeTimestampMs;
                }

                setState(mCurrentState, timestampMs);

                if (mLastUpdateTimestampMs >= 0) {
                    if (timestampMs > mLastUpdateTimestampMs) {
                        long delta = value - mValue;
                        if (delta >= 0) {
                            returnValue = delta;
                            long timeSinceUpdate = timestampMs - mLastUpdateTimestampMs;
                            for (int i = 0; i < mStateCount; i++) {
                                long timeInState = mStates[i].mTimeInStateSinceUpdate;
                                if (timeInState > 0) {
                                    mStates[i].mCounter += delta * timeInState / timeSinceUpdate;
                                    mStates[i].mTimeInStateSinceUpdate = 0;
                                }
                            }
                        } else {
                            for (int i = 0; i < mStateCount; i++) {
                                mStates[i].mTimeInStateSinceUpdate = 0;
                            }
                        }
                    } else if (timestampMs < mLastUpdateTimestampMs) {
                        for (int i = 0; i < mStateCount; i++) {
                            mStates[i].mTimeInStateSinceUpdate = 0;
                        }
                    }
                }
            }
            mValue = value;
            mLastUpdateTimestampMs = timestampMs;
            return returnValue;
        }

        public void incrementValue(long count, long timestampMs) {
            updateValue(mValue + count, timestampMs);
        }

        public long getValue(int state) {
            return mStates[state].mCounter;
        }

        public void reset() {
            mLastStateChangeTimestampMs = -1;
            mLastUpdateTimestampMs = -1;
            for (int i = 0; i < mStateCount; i++) {
                mStates[i].mTimeInStateSinceUpdate = 0;
                mStates[i].mCounter = 0;
            }
        }

        public void writeToParcel(Parcel parcel) {
            parcel.writeInt(mStateCount);
            for (int i = 0; i < mStateCount; i++) {
                parcel.writeLong(mStates[i].mCounter);
            }
        }

        public void initFromParcel(Parcel parcel) {
            try {
                for (int i = 0; i < mStateCount; i++) {
                    mStates[i].mCounter = parcel.readLong();
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
                sb.append(state).append(": ").append(mStates[state].mCounter);
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
    }

    private static final HashMap<Long, LongMultiStateCounterRavenwood> sInstances =
            new HashMap<>();
    private static long sNextId = 1;

    public static long native_init(int stateCount) {
        LongMultiStateCounterRavenwood instance = new LongMultiStateCounterRavenwood(stateCount);
        long instanceId = sNextId++;
        sInstances.put(instanceId, instance);
        return instanceId;
    }

    private static LongMultiStateCounterRavenwood getInstance(long instanceId) {
        return sInstances.get(instanceId);
    }

    public static void native_setEnabled(long instanceId, boolean enabled,
            long timestampMs) {
        getInstance(instanceId).setEnabled(enabled, timestampMs);
    }

    public static int native_getStateCount(long instanceId) {
        return getInstance(instanceId).mStateCount;
    }

    public static long native_updateValue(long instanceId, long value, long timestampMs) {
        return getInstance(instanceId).updateValue(value, timestampMs);
    }

    public static void native_setState(long instanceId, int state, long timestampMs) {
        getInstance(instanceId).setState(state, timestampMs);
    }

    public static void native_incrementValue(long instanceId, long count, long timestampMs) {
        getInstance(instanceId).incrementValue(count, timestampMs);
    }

    public static long native_getCount(long instanceId, int state) {
        return getInstance(instanceId).getValue(state);
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
        // LongMultiStateCounter.cpp uses AParcel, which throws on out-of-data.
        if (parcel.dataPosition() >= parcel.dataSize()) {
            throw new RuntimeException("Bad parcel");
        }
        long instanceId = native_init(stateCount);
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
