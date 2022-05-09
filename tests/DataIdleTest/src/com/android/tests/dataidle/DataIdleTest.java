/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tests.dataidle;

import static android.net.NetworkStats.METERED_YES;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.NetworkTemplate;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.util.Set;

/**
 * A test that dumps data usage to instrumentation out, used for measuring data usage for idle
 * devices.
 */
public class DataIdleTest extends InstrumentationTestCase {

    private TelephonyManager mTelephonyManager;
    private NetworkStatsManager mStatsManager;

    private static final String LOG_TAG = "DataIdleTest";
    private final static int INSTRUMENTATION_IN_PROGRESS = 2;

    protected void setUp() throws Exception {
        super.setUp();
        Context c = getInstrumentation().getTargetContext();
        mStatsManager = c.getSystemService(NetworkStatsManager.class);
        mTelephonyManager = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Test that dumps all the data usage metrics for wifi to instrumentation out.
     */
    public void testWifiIdle() {
        final NetworkTemplate template = new NetworkTemplate
                .Builder(NetworkTemplate.MATCH_WIFI)
                .build();
        fetchStats(template);
    }

    /**
     * Test that dumps all the data usage metrics for all mobile to instrumentation out.
     */
    public void testMobile() {
        final String subscriberId = mTelephonyManager.getSubscriberId();
        NetworkTemplate template = new NetworkTemplate
                .Builder(NetworkTemplate.MATCH_MOBILE)
                .setMeteredness(METERED_YES)
                .setSubscriberIds(Set.of(subscriberId)).build();
        fetchStats(template);
    }

    /**
     * Helper method that fetches all the network stats available and reports it
     * to instrumentation out.
     * @param template {@link NetworkTemplate} to match.
     */
    private void fetchStats(NetworkTemplate template) {
        try {
            mStatsManager.forceUpdate();
            final NetworkStats.Bucket bucket =
                    mStatsManager.querySummaryForDevice(template, Long.MIN_VALUE, Long.MAX_VALUE);
            reportStats(bucket);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Failed to fetch network stats.");
        }
    }

    /**
     * Print network data usage stats to instrumentation out
     * @param bucket {@link NetworkStats} to print
     */
    void reportStats(NetworkStats.Bucket bucket) {
        Bundle result = new Bundle();
        result.putLong("Total rx Bytes", bucket.getRxBytes());
        result.putLong("Total tx Bytes", bucket.getTxBytes());
        result.putLong("Total rx Packets", bucket.getRxPackets());
        result.putLong("Total tx Packets", bucket.getTxPackets());
        getInstrumentation().sendStatus(INSTRUMENTATION_IN_PROGRESS, result);

    }
}
