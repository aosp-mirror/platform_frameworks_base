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

import android.content.Context;
import android.net.INetworkStatsService;
import android.net.NetworkStats.Entry;
import android.net.NetworkTemplate;
import android.net.NetworkStats;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Log;

/**
 * A test that dumps data usage to instrumentation out, used for measuring data usage for idle
 * devices.
 */
public class DataIdleTest extends InstrumentationTestCase {

    private TelephonyManager mTelephonyManager;
    private INetworkStatsService mStatsService;

    private static final String LOG_TAG = "DataIdleTest";
    private final static int INSTRUMENTATION_IN_PROGRESS = 2;

    protected void setUp() throws Exception {
        super.setUp();
        Context c = getInstrumentation().getTargetContext();
        mStatsService = INetworkStatsService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        mTelephonyManager = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Test that dumps all the data usage metrics for wifi to instrumentation out.
     */
    public void testWifiIdle() {
        NetworkTemplate template = NetworkTemplate.buildTemplateWifi();
        fetchStats(template);
    }

    /**
     * Test that dumps all the data usage metrics for all mobile to instrumentation out.
     */
    public void testMobile() {
        String subscriberId = mTelephonyManager.getSubscriberId();
        NetworkTemplate template = NetworkTemplate.buildTemplateMobileAll(subscriberId);
        fetchStats(template);
    }

    /**
     * Helper method that fetches all the network stats available and reports it
     * to instrumentation out.
     * @param template {link {@link NetworkTemplate} to match.
     */
    private void fetchStats(NetworkTemplate template) {
        try {
            mStatsService.forceUpdate();
            NetworkStats stats = mStatsService.getSummaryForAllUid(template, Long.MIN_VALUE,
                    Long.MAX_VALUE, false);
            reportStats(stats);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Failed to fetch network stats.");
        }
    }

    /**
     * Print network data usage stats to instrumentation out
     * @param stats {@link NetworkorStats} to print
     */
    void reportStats(NetworkStats stats) {
        Bundle result = new Bundle();
        long rxBytes = 0;
        long txBytes = 0;
        long rxPackets = 0;
        long txPackets = 0;
        for (int i = 0; i < stats.size(); ++i) {
            // Label will be iface_uid_tag_set
            Entry  statsEntry = stats.getValues(i, null);
            // Debugging use.
            /*
            String labelTemplate = String.format("%s_%d_%d_%d", statsEntry.iface, statsEntry.uid,
                    statsEntry.tag, statsEntry.set) + "_%s";
            result.putLong(String.format(labelTemplate, "rxBytes"), statsEntry.rxBytes);
            result.putLong(String.format(labelTemplate, "txBytes"), statsEntry.txBytes);
            */
            rxPackets += statsEntry.rxPackets;
            rxBytes += statsEntry.rxBytes;
            txPackets += statsEntry.txPackets;
            txBytes += statsEntry.txBytes;
        }
        result.putLong("Total rx Bytes", rxBytes);
        result.putLong("Total tx Bytes", txBytes);
        result.putLong("Total rx Packets", rxPackets);
        result.putLong("Total tx Packets", txPackets);
        getInstrumentation().sendStatus(INSTRUMENTATION_IN_PROGRESS, result);

    }
}
