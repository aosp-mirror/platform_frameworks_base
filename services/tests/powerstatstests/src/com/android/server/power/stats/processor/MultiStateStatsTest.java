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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.os.BatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MultiStateStatsTest {
    public static final int DIMENSION_COUNT = 2;

    @Test
    public void compositeStateIndex_allEnabled() {
        MultiStateStats.Factory factory = makeFactory(true, true, true);
        assertThatCpuPerformanceStatsFactory(factory)
                .hasSerialStateCount(BatteryConsumer.PROCESS_STATE_COUNT * 4)
                .haveDifferentSerialStates(
                        state(false, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, false, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(true, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, false, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_BACKGROUND));
    }

    @Test
    public void compositeStateIndex_procStateTrackingDisabled() {
        MultiStateStats.Factory factory = makeFactory(true, false, true);
        assertThatCpuPerformanceStatsFactory(factory)
                .hasSerialStateCount(4)
                .haveDifferentSerialStates(
                        state(false, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_FOREGROUND))
                .haveSameSerialStates(
                        state(false, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, false, BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .haveSameSerialStates(
                        state(false, true, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .haveSameSerialStates(
                        state(true, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, false, BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .haveSameSerialStates(
                        state(true, true, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_BACKGROUND));
    }

    @Test
    public void compositeStateIndex_screenTrackingDisabled() {
        MultiStateStats.Factory factory = makeFactory(true, true, false);
        assertThatCpuPerformanceStatsFactory(factory)
                .hasSerialStateCount(BatteryConsumer.PROCESS_STATE_COUNT * 2)
                .haveDifferentSerialStates(
                        state(false, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(true, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .haveSameSerialStates(
                        state(false, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_FOREGROUND))
                .haveSameSerialStates(
                        state(true, false, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_BACKGROUND));
    }

    @Test
    public void compositeStateIndex_allDisabled() {
        MultiStateStats.Factory factory = makeFactory(false, false, false);
        assertThatCpuPerformanceStatsFactory(factory)
                .hasSerialStateCount(1)
                .haveSameSerialStates(
                        state(false, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, false, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(false, true, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(true, false, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, false, BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        state(true, true, BatteryConsumer.PROCESS_STATE_BACKGROUND));
    }

    @Test
    public void tooManyStates() {
        // 4 bits needed to represent
        String[] labels = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j"};
        // 4 * 10 = 40 bits needed to represent the composite state
        MultiStateStats.States[] states = new MultiStateStats.States[10];
        for (int i = 0; i < states.length; i++) {
            states[i] = new MultiStateStats.States("foo", true, labels);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new MultiStateStats.Factory(DIMENSION_COUNT, states));
        assertThat(e.getMessage()).contains("40");
    }

    @Test
    public void multiStateStats_aggregation() {
        MultiStateStats.Factory factory = makeFactory(true, true, false);
        MultiStateStats multiStateStats = factory.create();
        multiStateStats.setState(0 /* batteryState */, 1 /* on */, 1000);
        multiStateStats.setState(1 /* procState */, BatteryConsumer.PROCESS_STATE_FOREGROUND, 1000);
        multiStateStats.setState(2 /* screenState */, 0 /* off */, 1000);

        multiStateStats.increment(new long[]{100, 200}, 1000);

        multiStateStats.setState(0 /* batteryState */, 0 /* off */, 2000);
        multiStateStats.setState(2 /* screenState */, 1 /* on */, 2000); // untracked

        multiStateStats.increment(new long[]{300, 500}, 3000);

        multiStateStats.setState(1 /* procState */, BatteryConsumer.PROCESS_STATE_BACKGROUND, 4000);

        multiStateStats.increment(new long[]{200, 200}, 5000);

        long[] stats = new long[DIMENSION_COUNT];
        multiStateStats.getStats(stats, new int[]{0, BatteryConsumer.PROCESS_STATE_FOREGROUND, 0});
        // (400 - 100) * 0.5 + (600 - 400) * 0.5
        assertThat(stats).isEqualTo(new long[]{250, 350});

        multiStateStats.getStats(stats, new int[]{1, BatteryConsumer.PROCESS_STATE_FOREGROUND, 0});
        // (400 - 100) * 0.5 + (600 - 400) * 0
        assertThat(stats).isEqualTo(new long[]{150, 250});

        // Note that screen state does not affect the result, as it is untracked
        multiStateStats.getStats(stats, new int[]{0, BatteryConsumer.PROCESS_STATE_BACKGROUND, 1});
        // (400 - 100) * 0 + (600 - 400) * 0.5
        assertThat(stats).isEqualTo(new long[]{100, 100});

        multiStateStats.getStats(stats, new int[]{1, BatteryConsumer.PROCESS_STATE_BACKGROUND, 0});
        // Never been in this composite state
        assertThat(stats).isEqualTo(new long[]{0, 0});
    }

    @Test
    public void test_toString() {
        MultiStateStats.Factory factory = makeFactory(true, true, false);
        MultiStateStats multiStateStats = factory.create();
        multiStateStats.setState(0 /* batteryState */, 0 /* off */, 1000);
        multiStateStats.setState(1 /* procState */, BatteryConsumer.PROCESS_STATE_FOREGROUND, 1000);
        multiStateStats.setState(2 /* screenState */, 0 /* off */, 1000);
        multiStateStats.setState(0 /* batteryState */, 1 /* on */, 2000);
        multiStateStats.setState(1 /* procState */, BatteryConsumer.PROCESS_STATE_BACKGROUND, 3000);
        multiStateStats.increment(new long[]{100, 200}, 5000);

        assertThat(multiStateStats.toString()).isEqualTo(
                "(plugged-in fg) [25, 50]\n"
                + "(on-battery fg) [25, 50]\n"
                + "(on-battery bg) [50, 100]"
        );
    }

    private static MultiStateStats.Factory makeFactory(boolean trackBatteryState,
            boolean trackProcState, boolean trackScreenState) {
        return new MultiStateStats.Factory(DIMENSION_COUNT,
                new MultiStateStats.States("bs", trackBatteryState, "plugged-in", "on-battery"),
                new MultiStateStats.States("ps", trackProcState,
                        BatteryConsumer.processStateToString(
                                BatteryConsumer.PROCESS_STATE_UNSPECIFIED),
                        BatteryConsumer.processStateToString(
                                BatteryConsumer.PROCESS_STATE_FOREGROUND),
                        BatteryConsumer.processStateToString(
                                BatteryConsumer.PROCESS_STATE_BACKGROUND),
                        BatteryConsumer.processStateToString(
                                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE),
                        BatteryConsumer.processStateToString(
                                BatteryConsumer.PROCESS_STATE_CACHED)),
                new MultiStateStats.States("scr", trackScreenState, "screen-off", "plugged-in"));
    }

    private FactorySubject assertThatCpuPerformanceStatsFactory(MultiStateStats.Factory factory) {
        FactorySubject subject = new FactorySubject();
        subject.mFactory = factory;
        return subject;
    }

    private static class FactorySubject {
        private MultiStateStats.Factory mFactory;

        FactorySubject hasSerialStateCount(int stateCount) {
            assertThat(mFactory.getSerialStateCount()).isEqualTo(stateCount);
            return this;
        }

        public FactorySubject haveDifferentSerialStates(State... states) {
            int[] serialStates = getSerialStates(states);
            assertWithMessage("Expected all to be different: " + Arrays.toString(serialStates))
                    .that(Arrays.stream(serialStates).distinct().toArray())
                    .hasLength(states.length);
            return this;
        }

        public FactorySubject haveSameSerialStates(State... states) {
            int[] serialStates = getSerialStates(states);
            assertWithMessage("Expected all to be the same: " + Arrays.toString(serialStates))
                    .that(Arrays.stream(serialStates).distinct().toArray())
                    .hasLength(1);
            return this;
        }

        private int[] getSerialStates(State[] states) {
            int[] serialStates = new int[states.length];
            for (int i = 0; i < states.length; i++) {
                serialStates[i] = mFactory.getSerialState(
                        new int[]{
                                states[i].batteryState ? 0 : 1,
                                states[i].procstate,
                                states[i].screenState ? 0 : 1
                        });
            }
            return serialStates;
        }
    }

    private State state(boolean batteryState, boolean screenState, int procstate) {
        State state = new State();
        state.batteryState = batteryState;
        state.screenState = screenState;
        state.procstate = procstate;
        return state;
    }

    private static class State {
        public boolean batteryState;
        public boolean screenState;
        public int procstate;
    }
}
