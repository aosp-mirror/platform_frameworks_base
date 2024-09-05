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

package com.android.server.power.stats.processor;

import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.LongArrayMultiStateCounter;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Maintains multidimensional multi-state stats.  States could be something like on-battery (0,1),
 * screen-on (0,1), process state etc.  Dimensions refer to the metrics themselves, e.g.
 * CPU residency, Network packet counts etc.  All metrics must be represented as <code>long</code>
 * values;
 */
class MultiStateStats {
    private static final String TAG = "MultiStateStats";

    private static final String XML_TAG_STATS = "stats";
    public static final int STATE_DOES_NOT_EXIST = -1;

    /**
     * A set of states, e.g. on-battery, screen-on, procstate.  The state values are integers
     * from 0 to States.mLabels.length
     */
    static class States {
        final String mName;
        final boolean mTracked;
        final String[] mLabels;

        States(String name, boolean tracked, String... labels) {
            mName = name;
            mTracked = tracked;
            mLabels = labels;
        }

        public boolean isTracked() {
            return mTracked;
        }

        public String getName() {
            return mName;
        }

        public String[] getLabels() {
            return mLabels;
        }

        /**
         * Finds state by name in the provided array. If not found, returns STATE_DOES_NOT_EXIST.
         */
        public static int findTrackedStateByName(MultiStateStats.States[] states, String name) {
            for (int i = 0; i < states.length; i++) {
                if (states[i].getName().equals(name)) {
                    return i;
                }
            }
            return STATE_DOES_NOT_EXIST;
        }

        /**
         * Iterates over all combinations of tracked states and invokes <code>consumer</code>
         * for each of them.
         */
        public static void forEachTrackedStateCombination(States[] states,
                Consumer<int[]> consumer) {
            forEachTrackedStateCombination(consumer, states, new int[states.length], 0);
        }

        /**
         * Recursive function that does a depth-first traversal of the multi-dimensional
         * state space. Each time the traversal reaches the end of the <code>states</code> array,
         * <code>statesValues</code> contains a unique combination of values for all tracked states.
         * For untracked states, the corresponding values are left as 0.  The end result is
         * that the <code>consumer</code> is invoked for every unique combination of tracked state
         * values.  For example, it may be a sequence of calls like screen-on/power-on,
         * screen-on/power-off, screen-off/power-on, screen-off/power-off.
         */
        private static void forEachTrackedStateCombination(Consumer<int[]> consumer,
                States[] states, int[] statesValues, int stateIndex) {
            if (stateIndex < statesValues.length) {
                if (!states[stateIndex].mTracked) {
                    forEachTrackedStateCombination(consumer, states, statesValues, stateIndex + 1);
                    return;
                }
                for (int i = 0; i < states[stateIndex].mLabels.length; i++) {
                    statesValues[stateIndex] = i;
                    forEachTrackedStateCombination(consumer, states, statesValues, stateIndex + 1);
                }
                return;
            }
            consumer.accept(statesValues);
        }
    }

    /**
     * Factory for MultiStateStats containers. All generated containers retain their connection
     * to the Factory and the corresponding configuration.
     */
    static class Factory {
        private static final int INVALID_SERIAL_STATE = -1;
        final int mDimensionCount;
        final States[] mStates;
        /*
         * The LongArrayMultiStateCounter object that is used for accumulation of per-state
         * stats thinks of "state" as a simple 0-based index. This Factory object's job is to
         * map a combination of individual states (e.g. on-battery, process state etc) to
         * such a simple index.
         *
         * This task is performed in two steps:
         * 1) We define "composite state" as an integer that combines all constituent States
         * into one integer as bit fields. This gives us a convenient mechanism for updating a
         * single constituent State at a time.  We maintain an array of bit field masks
         * corresponding to each constituent State.
         *
         * 2) We map composite states to "serial states", i.e. simple integer indexes, taking
         * into account which constituent states are configured as tracked.  If a state is not
         * tracked, there is no need to maintain separate counts for its values, thus
         * all values of that constituent state can be mapped to the same serial state.
         */
        private final int[] mStateBitFieldMasks;
        private final short[] mStateBitFieldShifts;
        final int[] mCompositeToSerialState;
        final int mSerialStateCount;

        Factory(int dimensionCount, States... states) {
            mDimensionCount = dimensionCount;
            mStates = states;

            int serialStateCount = 1;
            for (States state : mStates) {
                if (state.mTracked) {
                    serialStateCount *= state.mLabels.length;
                }
            }
            mSerialStateCount = serialStateCount;

            mStateBitFieldMasks = new int[mStates.length];
            mStateBitFieldShifts = new short[mStates.length];

            int shift = 0;
            for (int i = 0; i < mStates.length; i++) {
                mStateBitFieldShifts[i] = (short) shift;
                if (mStates[i].mLabels.length < 2) {
                    throw new IllegalArgumentException("Invalid state: " + Arrays.toString(
                            mStates[i].mLabels) + ". Should have at least two values.");
                }
                int max = mStates[i].mLabels.length - 1;
                int bitcount = Integer.SIZE - Integer.numberOfLeadingZeros(max);
                mStateBitFieldMasks[i] = ((1 << bitcount) - 1) << shift;
                shift = shift + bitcount;
            }

            if (shift >= Integer.SIZE - 1) {
                throw new IllegalArgumentException("Too many states: " + shift
                        + " bits are required to represent the composite state, but only "
                        + (Integer.SIZE - 1) + " are available");
            }

            // Create a mask that filters out all non tracked states
            int trackedMask = 0xFFFFFFFF;
            for (int state = 0; state < mStates.length; state++) {
                if (!mStates[state].mTracked) {
                    trackedMask &= ~mStateBitFieldMasks[state];
                }
            }

            mCompositeToSerialState = new int[1 << shift];
            Arrays.fill(mCompositeToSerialState, INVALID_SERIAL_STATE);

            int nextSerialState = 0;
            for (int composite = 0; composite < mCompositeToSerialState.length; composite++) {
                if (!isValidCompositeState(composite)) continue;

                // Values of an untracked State map to different composite states, but must map to
                // the same serial state. Achieve that by computing a "base composite", which
                // is equivalent to the current composite, but has 0 for all untracked States.
                // See if the base composite already has a serial state assigned.  If so, just use
                // the same one for the current composite.
                int baseComposite = composite & trackedMask;
                if (mCompositeToSerialState[baseComposite] != INVALID_SERIAL_STATE) {
                    mCompositeToSerialState[composite] = mCompositeToSerialState[baseComposite];
                } else {
                    mCompositeToSerialState[composite] = nextSerialState++;
                }
            }
        }

        private boolean isValidCompositeState(int composite) {
            for (int stateIndex = 0; stateIndex < mStates.length; stateIndex++) {
                int state = extractStateFromComposite(composite, stateIndex);
                if (state >= mStates[stateIndex].mLabels.length) {
                    return false;
                }
            }
            return true;
        }

        private int extractStateFromComposite(int compositeState, int stateIndex) {
            return (compositeState & mStateBitFieldMasks[stateIndex])
                   >>> mStateBitFieldShifts[stateIndex];
        }

        int setStateInComposite(int baseCompositeState, int stateIndex, int value) {
            return (baseCompositeState & ~mStateBitFieldMasks[stateIndex])
                    | (value << mStateBitFieldShifts[stateIndex]);
        }

        int setStateInComposite(int compositeState, String stateName, String stateLabel) {
            for (int stateIndex = 0; stateIndex < mStates.length; stateIndex++) {
                States stateConfig = mStates[stateIndex];
                if (stateConfig.mName.equals(stateName)) {
                    for (int state = 0; state < stateConfig.mLabels.length; state++) {
                        if (stateConfig.mLabels[state].equals(stateLabel)) {
                            return setStateInComposite(compositeState, stateIndex, state);
                        }
                    }
                    Slog.e(TAG, "Unexpected label '" + stateLabel + "' for state: " + stateName);
                    return -1;
                }
            }
            Slog.e(TAG, "Unsupported state: " + stateName);
            return -1;
        }

        /**
         * Allocates a new stats container using this Factory's configuration.
         */
        MultiStateStats create() {
            return new MultiStateStats(this, mDimensionCount);
        }

        /**
         * Returns the total number of composite states handled by this container. For example,
         * if there are two states: on-battery (0,1) and screen-on (0,1), both tracked, then the
         * serial state count will be 2 * 2 = 4
         */
        @VisibleForTesting
        public int getSerialStateCount() {
            return mSerialStateCount;
        }

        /**
         * Returns the integer index used by this container to represent the supplied composite
         * state.
         */
        @VisibleForTesting
        public int getSerialState(int[] states) {
            Preconditions.checkArgument(states.length == mStates.length);
            int compositeState = 0;
            for (int i = 0; i < states.length; i++) {
                compositeState = setStateInComposite(compositeState, i, states[i]);
            }
            int serialState = mCompositeToSerialState[compositeState];
            if (serialState == INVALID_SERIAL_STATE) {
                throw new IllegalArgumentException("State values out of bounds: "
                                                   + Arrays.toString(states));
            }
            return serialState;
        }

        int getSerialState(int compositeState) {
            return mCompositeToSerialState[compositeState];
        }
    }

    private final Factory mFactory;
    private final LongArrayMultiStateCounter mCounter;
    private int mCompositeState;
    private boolean mTracking;

    MultiStateStats(Factory factory, int dimensionCount) {
        this.mFactory = factory;
        mCounter = new LongArrayMultiStateCounter(factory.mSerialStateCount, dimensionCount);
    }

    int getDimensionCount() {
        return mFactory.mDimensionCount;
    }

    States[] getStates() {
        return mFactory.mStates;
    }

    /**
     * Copies time-in-state and timestamps from the supplied prototype. Does not
     * copy accumulated counts.
     */
    void copyStatesFrom(MultiStateStats otherStats) {
        mCounter.copyStatesFrom(otherStats.mCounter);
    }

    /**
     * Updates the current composite state by changing one of the States supplied to the Factory
     * constructor.
     *
     * @param stateIndex  Corresponds to the index of the States supplied to the Factory constructor
     * @param state       The new value of the state (e.g. 0 or 1 for "on-battery")
     * @param timestampMs The time when the state change occurred
     */
    void setState(int stateIndex, int state, long timestampMs) {
        if (!mTracking) {
            mCounter.updateValues(new long[mCounter.getArrayLength()], timestampMs);
            mTracking = true;
        }
        mCompositeState = mFactory.setStateInComposite(mCompositeState, stateIndex, state);
        mCounter.setState(mFactory.mCompositeToSerialState[mCompositeState], timestampMs);
    }

    /**
     * Adds the delta to the metrics.  The number of values must correspond to the dimension count
     * supplied to the Factory constructor
     */
    void increment(long[] values, long timestampMs) {
        mCounter.incrementValues(values, timestampMs);
        mTracking = true;
    }

    /**
     * Returns accumulated stats for the specified composite state.
     */
    void getStats(long[] outValues, int[] states) {
        mCounter.getCounts(outValues, mFactory.getSerialState(states));
    }

    /**
     * Updates the stats values for the provided combination of states.
     */
    void setStats(int[] states, long[] values) {
        mCounter.setValues(mFactory.getSerialState(states), values);
    }

    /**
     * Resets the counters.
     */
    void reset() {
        mCounter.reset();
        mTracking = false;
    }

    /**
     * Stores contents in an XML doc.
     */
    void writeXml(TypedXmlSerializer serializer) throws IOException {
        long[] tmpArray = new long[mCounter.getArrayLength()];

        try {
            States.forEachTrackedStateCombination(mFactory.mStates,
                    states -> {
                        try {
                            writeXmlForStates(serializer, states, tmpArray);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            } else {
                throw e;
            }
        }
    }

    private void writeXmlForStates(TypedXmlSerializer serializer, int[] states, long[] values)
            throws IOException {
        mCounter.getCounts(values, mFactory.getSerialState(states));
        boolean nonZero = false;
        for (long value : values) {
            if (value != 0) {
                nonZero = true;
                break;
            }
        }
        if (!nonZero) {
            return;
        }

        serializer.startTag(null, XML_TAG_STATS);

        for (int i = 0; i < states.length; i++) {
            if (mFactory.mStates[i].mTracked && states[i] != 0) {
                serializer.attribute(null, mFactory.mStates[i].mName,
                        mFactory.mStates[i].mLabels[states[i]]);
            }
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] != 0) {
                serializer.attributeLong(null, "_" + i, values[i]);
            }
        }
        serializer.endTag(null, XML_TAG_STATS);
    }

    /**
     * Populates the object with contents in an XML doc. The parser is expected to be
     * positioned on the opening tag of the corresponding element.
     */
    boolean readFromXml(TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        String outerTag = parser.getName();
        long[] tmpArray = new long[mCounter.getArrayLength()];
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT
               && !(eventType == XmlPullParser.END_TAG && parser.getName().equals(outerTag))) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.getName().equals(XML_TAG_STATS)) {
                    Arrays.fill(tmpArray, 0);
                    int compositeState = 0;
                    int attributeCount = parser.getAttributeCount();
                    for (int i = 0; i < attributeCount; i++) {
                        String attributeName = parser.getAttributeName(i);
                        if (attributeName.startsWith("_")) {
                            int index;
                            try {
                                index = Integer.parseInt(attributeName.substring(1));
                            } catch (NumberFormatException e) {
                                throw new XmlPullParserException(
                                        "Unexpected index syntax: " + attributeName, parser, e);
                            }
                            if (index < 0 || index >= tmpArray.length) {
                                Slog.e(TAG, "State index out of bounds: " + index
                                            + " length: " + tmpArray.length);
                                return false;
                            }
                            tmpArray[index] = parser.getAttributeLong(i);
                        } else {
                            String attributeValue = parser.getAttributeValue(i);
                            compositeState = mFactory.setStateInComposite(compositeState,
                                    attributeName, attributeValue);
                            if (compositeState == -1) {
                                return false;
                            }
                        }
                    }
                    mCounter.setValues(mFactory.getSerialState(compositeState), tmpArray);
                }
            }
            eventType = parser.next();
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        long[] values = new long[mCounter.getArrayLength()];
        States.forEachTrackedStateCombination(mFactory.mStates, states -> {
            mCounter.getCounts(values, mFactory.getSerialState(states));
            boolean nonZero = false;
            for (long value : values) {
                if (value != 0) {
                    nonZero = true;
                    break;
                }
            }
            if (!nonZero) {
                return;
            }

            if (!sb.isEmpty()) {
                sb.append("\n");
            }

            sb.append("(");
            boolean first = true;
            for (int i = 0; i < states.length; i++) {
                if (mFactory.mStates[i].mTracked) {
                    if (!first) {
                        sb.append(" ");
                    }
                    first = false;
                    sb.append(mFactory.mStates[i].mLabels[states[i]]);
                }
            }
            sb.append(") ");
            sb.append(Arrays.toString(values));
        });
        return sb.toString();
    }
}
