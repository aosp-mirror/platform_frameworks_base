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

package android.os;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.LongArrayMultiStateCounter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class LongArrayMultiStateCounterPerfTest {

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * A complete line-for-line reimplementation of
     * {@link com.android.internal.os.LongArrayMultiStateCounter}, only in Java instead of
     * native.
     */
    private static class TestLongArrayMultiStateCounter {
        private final int mStateCount;
        private final int mArrayLength;
        private int mCurrentState;
        private long mLastStateChangeTimestampMs = -1;
        private long mLastUpdateTimestampMs = -1;

        private static class State {
            private long mTimeInStateSinceUpdate;
            private long[] mCounter;
        }

        private final State[] mStates;
        private final long[] mLastTimeInFreq;
        private final long[] mDelta;

        TestLongArrayMultiStateCounter(int stateCount, int arrayLength) {
            mStateCount = stateCount;
            mArrayLength = arrayLength;
            mStates = new State[stateCount];
            for (int i = 0; i < mStateCount; i++) {
                mStates[i] = new State();
                mStates[i].mCounter = new long[mArrayLength];
            }
            mLastTimeInFreq = new long[mArrayLength];
            mDelta = new long[mArrayLength];
        }

        public void setState(int state, long timestampMs) {
            if (mLastStateChangeTimestampMs > 0) {
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

        public void updateValue(long[] timeInFreq, long timestampMs) {
            setState(mCurrentState, timestampMs);

            if (mLastUpdateTimestampMs >= 0) {
                if (timestampMs > mLastUpdateTimestampMs) {
                    if (delta(mLastTimeInFreq, timeInFreq, mDelta)) {
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
            System.arraycopy(timeInFreq, 0, mLastTimeInFreq, 0, mArrayLength);
            mLastUpdateTimestampMs = timestampMs;
        }

        private boolean delta(long[] timeInFreq1, long[] timeInFreq2, long[] delta) {
            if (delta.length != mArrayLength) {
                throw new RuntimeException();
            }

            boolean is_delta_valid = true;
            for (int i = 0; i < mStateCount; i++) {
                if (timeInFreq2[i] >= timeInFreq1[i]) {
                    delta[i] = timeInFreq2[i] - timeInFreq1[i];
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

    @Test
    public void javaImplementation() {
        TestLongArrayMultiStateCounter counter =
                new TestLongArrayMultiStateCounter(2, 4);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        long time = 1000;
        long[] timeInFreq = {100, 200, 300, 400};
        while (state.keepRunning()) {
            counter.setState(1, time);
            counter.setState(0, time + 1000);
            counter.updateValue(timeInFreq, time + 2000);
            time += 10000;
        }
    }

    @Test
    public void nativeImplementation() {
        LongArrayMultiStateCounter counter = new LongArrayMultiStateCounter(2, 4);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        long time = 1000;
        LongArrayMultiStateCounter.LongArrayContainer timeInFreq =
                new LongArrayMultiStateCounter.LongArrayContainer(4);
        timeInFreq.setValues(new long[]{100, 200, 300, 400});
        while (state.keepRunning()) {
            counter.setState(1, time);
            counter.setState(0, time + 1000);
            counter.updateValues(timeInFreq, time + 2000);
            time += 10000;
        }
    }
}
