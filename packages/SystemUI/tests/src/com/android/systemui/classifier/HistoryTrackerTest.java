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

package com.android.systemui.classifier;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class HistoryTrackerTest extends SysuiTestCase {

    private FakeSystemClock mSystemClock = new FakeSystemClock();

    private HistoryTracker mHistoryTracker;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mHistoryTracker = new HistoryTracker(mSystemClock);
    }

    @Test
    public void testNoDataNoPenalty() {
        assertThat(mHistoryTracker.falseBelief()).isEqualTo(0.5);
        assertThat(mHistoryTracker.falseConfidence()).isEqualTo(0);
    }

    @Test
    public void testOneResultFullConfidence() {
        addResult(true, 1);
        assertThat(mHistoryTracker.falseBelief()).isWithin(0.001).of(1);
        assertThat(mHistoryTracker.falseConfidence()).isEqualTo(1);
    }

    @Test
    public void testMultipleResultsSameTimestamp() {
        addResult(true, 1);
        addResult(false, 1);

        assertThat(mHistoryTracker.falseBelief()).isWithin(0.001).of(0.5);
        assertThat(mHistoryTracker.falseConfidence()).isWithin(0.001).of(0.5);
    }

    @Test
    public void testMultipleConfidences() {
        addResult(true, 1);
        addResult(true, 0);

        assertThat(mHistoryTracker.falseBelief()).isWithin(0.001).of(1);
        assertThat(mHistoryTracker.falseConfidence()).isWithin(0.001).of(.75);
    }

    @Test
    public void testDecay() {
        addResult(true, 1);

        assertThat(mHistoryTracker.falseBelief()).isWithin(0.001).of(1);
        assertThat(mHistoryTracker.falseConfidence()).isEqualTo(1);

        mSystemClock.advanceTime(9999);

        assertThat(mHistoryTracker.falseBelief()).isWithin(0.005).of(0.55);
        assertThat(mHistoryTracker.falseConfidence()).isEqualTo(1);
    }

    @Test
    public void testMultipleResultsDifferentTimestamp() {
        addResult(true, 1);
        mSystemClock.advanceTime(1000);
        addResult(false, .5);

        assertThat(mHistoryTracker.falseBelief()).isWithin(0.01).of(0.74);
        assertThat(mHistoryTracker.falseConfidence()).isWithin(0.001).of(0.625);
    }

    @Test
    public void testCompleteDecay() {
        addResult(true, 1);

        assertThat(mHistoryTracker.falseBelief()).isWithin(0.001).of(1);
        assertThat(mHistoryTracker.falseConfidence()).isEqualTo(1);

        mSystemClock.advanceTime(9999);

        assertThat(mHistoryTracker.falseBelief()).isGreaterThan(0);
        assertThat(mHistoryTracker.falseConfidence()).isEqualTo(1);

        mSystemClock.advanceTime(1);

        assertThat(mHistoryTracker.falseBelief()).isEqualTo(0.5);
        assertThat(mHistoryTracker.falseConfidence()).isEqualTo(0);
    }

    private void addResult(boolean falsed, double confidence) {
        mHistoryTracker.addResults(Collections.singletonList(
                falsed
                        ? FalsingClassifier.Result.falsed(
                                confidence, getClass().getSimpleName(), "test")
                        : FalsingClassifier.Result.passed(confidence)),
                mSystemClock.uptimeMillis());
    }
}
