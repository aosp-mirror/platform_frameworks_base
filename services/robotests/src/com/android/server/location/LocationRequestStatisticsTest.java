/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.location;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.android.internal.util.IndentingPrintWriter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit tests for {@link LocationRequestStatistics}.
 */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class LocationRequestStatisticsTest {
    private static final String FEATURE_ID = "featureId";

    /**
     * Check adding and removing requests & strings
     */
    @Test
    public void testRequestSummary() {
        LocationRequestStatistics.RequestSummary summary =
                new LocationRequestStatistics.RequestSummary(
                        "com.example", FEATURE_ID, "gps", 1000);
        StringWriter stringWriter = new StringWriter();
        summary.dump(new IndentingPrintWriter(new PrintWriter(stringWriter), "  "), 1234);
        assertThat(stringWriter.toString()).startsWith("At");

        StringWriter stringWriterRemove = new StringWriter();
        summary = new LocationRequestStatistics.RequestSummary(
                "com.example", "gps", FEATURE_ID,
                LocationRequestStatistics.RequestSummary.REQUEST_ENDED_INTERVAL);
        summary.dump(new IndentingPrintWriter(new PrintWriter(stringWriterRemove), "  "), 2345);
        assertThat(stringWriterRemove.toString()).contains("-");
        assertThat(stringWriterRemove.toString()).contains(FEATURE_ID);
    }

    /**
     * Check summary list size capping
     */
    @Test
    public void testSummaryList() {
        LocationRequestStatistics statistics = new LocationRequestStatistics();
        statistics.history.addRequest("com.example", FEATURE_ID, "gps", 1000);
        assertThat(statistics.history.mList.size()).isEqualTo(1);
        // Try (not) to overflow
        for (int i = 0; i < LocationRequestStatistics.RequestSummaryLimitedHistory.MAX_SIZE; i++) {
            statistics.history.addRequest("com.example", FEATURE_ID, "gps", 1000);
        }
        assertThat(statistics.history.mList.size()).isEqualTo(
                LocationRequestStatistics.RequestSummaryLimitedHistory.MAX_SIZE);
    }
}
