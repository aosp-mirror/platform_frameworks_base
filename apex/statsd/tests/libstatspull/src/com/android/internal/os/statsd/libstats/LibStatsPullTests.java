/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.os.statsd.libstats;

import static com.google.common.truth.Truth.assertThat;

import android.app.StatsManager;
import android.content.Context;
import android.util.Log;
import android.util.StatsLog;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.FieldFilter;
import com.android.internal.os.StatsdConfigProto.GaugeMetric;
import com.android.internal.os.StatsdConfigProto.PullAtomPackages;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.TimeUnit;
import com.android.internal.os.statsd.protos.TestAtoms;
import com.android.os.AtomsProto.Atom;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Test puller registration.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class LibStatsPullTests {
    private static final String LOG_TAG = LibStatsPullTests.class.getSimpleName();
    private static final int SHORT_SLEEP_MILLIS = 250;
    private static final int LONG_SLEEP_MILLIS = 1_000;
    private Context mContext;
    private static final int PULL_ATOM_TAG = 150030;
    private static final int APP_BREADCRUMB_LABEL = 3;
    private static int sPullReturnValue;
    private static long sConfigId;
    private static long sPullLatencyMillis;
    private static long sPullTimeoutMillis;
    private static long sCoolDownMillis;
    private static int sAtomsPerPull;

    static {
        System.loadLibrary("statspull_testhelper");
    }

    /**
     * Setup the tests. Initialize shared data.
     */
    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getTargetContext();
        assertThat(InstrumentationRegistry.getInstrumentation()).isNotNull();
        sPullReturnValue = StatsManager.PULL_SUCCESS;
        sPullLatencyMillis = 0;
        sPullTimeoutMillis = 10_000L;
        sCoolDownMillis = 1_000L;
        sAtomsPerPull = 1;
    }

    /**
     * Teardown the tests.
     */
    @After
    public void tearDown() throws Exception {
        clearStatsPuller(PULL_ATOM_TAG);
        StatsManager statsManager = (StatsManager) mContext.getSystemService(
                Context.STATS_MANAGER);
        statsManager.removeConfig(sConfigId);
    }

    /**
     * Tests adding a puller callback and that pulls complete successfully.
     */
    @Test
    public void testPullAtomCallbackRegistration() throws Exception {
        StatsManager statsManager = (StatsManager) mContext.getSystemService(
                Context.STATS_MANAGER);
        // Upload a config that captures that pulled atom.
        createAndAddConfigToStatsd(statsManager);

        // Add the puller.
        setStatsPuller(PULL_ATOM_TAG, sPullTimeoutMillis, sCoolDownMillis, sPullReturnValue,
                sPullLatencyMillis, sAtomsPerPull);
        Thread.sleep(SHORT_SLEEP_MILLIS);
        StatsLog.logStart(APP_BREADCRUMB_LABEL);
        // Let the current bucket finish.
        Thread.sleep(LONG_SLEEP_MILLIS);
        List<Atom> data = StatsConfigUtils.getGaugeMetricDataList(statsManager, sConfigId);
        clearStatsPuller(PULL_ATOM_TAG);
        assertThat(data.size()).isEqualTo(1);
        TestAtoms.PullCallbackAtomWrapper atomWrapper = null;
        try {
            atomWrapper = TestAtoms.PullCallbackAtomWrapper.parser()
                    .parseFrom(data.get(0).toByteArray());
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to parse primitive atoms");
        }
        assertThat(atomWrapper).isNotNull();
        assertThat(atomWrapper.hasPullCallbackAtom()).isTrue();
        TestAtoms.PullCallbackAtom atom =
                atomWrapper.getPullCallbackAtom();
        assertThat(atom.getLongVal()).isEqualTo(1);
    }

    /**
     * Tests that a failed pull is skipped.
     */
    @Test
    public void testPullAtomCallbackFailure() throws Exception {
        StatsManager statsManager = (StatsManager) mContext.getSystemService(
                Context.STATS_MANAGER);
        createAndAddConfigToStatsd(statsManager);
        sPullReturnValue = StatsManager.PULL_SKIP;
        // Add the puller.
        setStatsPuller(PULL_ATOM_TAG, sPullTimeoutMillis, sCoolDownMillis, sPullReturnValue,
                sPullLatencyMillis, sAtomsPerPull);
        Thread.sleep(SHORT_SLEEP_MILLIS);
        StatsLog.logStart(APP_BREADCRUMB_LABEL);
        // Let the current bucket finish.
        Thread.sleep(LONG_SLEEP_MILLIS);
        List<Atom> data = StatsConfigUtils.getGaugeMetricDataList(statsManager, sConfigId);
        clearStatsPuller(PULL_ATOM_TAG);
        assertThat(data.size()).isEqualTo(0);
    }

    /**
     * Tests that a pull that times out is skipped.
     */
    @Test
    public void testPullAtomCallbackTimeout() throws Exception {
        StatsManager statsManager = (StatsManager) mContext.getSystemService(
                Context.STATS_MANAGER);
        createAndAddConfigToStatsd(statsManager);
        // The puller will sleep for 1.5 sec.
        sPullLatencyMillis = 1_500;
        // 1 second timeout
        sPullTimeoutMillis = 1_000;

        // Add the puller.
        setStatsPuller(PULL_ATOM_TAG, sPullTimeoutMillis, sCoolDownMillis, sPullReturnValue,
                sPullLatencyMillis, sAtomsPerPull);
        Thread.sleep(SHORT_SLEEP_MILLIS);
        StatsLog.logStart(APP_BREADCRUMB_LABEL);
        // Let the current bucket finish and the pull timeout.
        Thread.sleep(sPullLatencyMillis * 2);
        List<Atom> data = StatsConfigUtils.getGaugeMetricDataList(statsManager, sConfigId);
        clearStatsPuller(PULL_ATOM_TAG);
        assertThat(data.size()).isEqualTo(0);
    }

    /**
     * Tests that 2 pulls in quick succession use the cache instead of pulling again.
     */
    @Test
    public void testPullAtomCallbackCache() throws Exception {
        StatsManager statsManager = (StatsManager) mContext.getSystemService(
                Context.STATS_MANAGER);
        createAndAddConfigToStatsd(statsManager);

        // Set the cooldown to 10 seconds
        sCoolDownMillis = 10_000L;
        // Add the puller.
        setStatsPuller(PULL_ATOM_TAG, sPullTimeoutMillis, sCoolDownMillis, sPullReturnValue,
                sPullLatencyMillis, sAtomsPerPull);

        Thread.sleep(SHORT_SLEEP_MILLIS);
        StatsLog.logStart(APP_BREADCRUMB_LABEL);
        // Pull from cache.
        StatsLog.logStart(APP_BREADCRUMB_LABEL);
        Thread.sleep(LONG_SLEEP_MILLIS);
        List<Atom> data = StatsConfigUtils.getGaugeMetricDataList(statsManager, sConfigId);
        clearStatsPuller(PULL_ATOM_TAG);
        assertThat(data.size()).isEqualTo(2);
        for (int i = 0; i < data.size(); i++) {
            TestAtoms.PullCallbackAtomWrapper atomWrapper = null;
            try {
                atomWrapper = TestAtoms.PullCallbackAtomWrapper.parser()
                        .parseFrom(data.get(i).toByteArray());
            } catch (Exception e) {
                Log.e(LOG_TAG, "Failed to parse primitive atoms");
            }
            assertThat(atomWrapper).isNotNull();
            assertThat(atomWrapper.hasPullCallbackAtom()).isTrue();
            TestAtoms.PullCallbackAtom atom =
                    atomWrapper.getPullCallbackAtom();
            assertThat(atom.getLongVal()).isEqualTo(1);
        }
    }

    /**
     * Tests that a pull that returns 1000 stats events works properly.
     */
    @Test
    public void testPullAtomCallbackStress() throws Exception {
        StatsManager statsManager = (StatsManager) mContext.getSystemService(
                Context.STATS_MANAGER);
        // Upload a config that captures that pulled atom.
        createAndAddConfigToStatsd(statsManager);
        sAtomsPerPull = 1000;
        // Add the puller.
        setStatsPuller(PULL_ATOM_TAG, sPullTimeoutMillis, sCoolDownMillis, sPullReturnValue,
                sPullLatencyMillis, sAtomsPerPull);

        Thread.sleep(SHORT_SLEEP_MILLIS);
        StatsLog.logStart(APP_BREADCRUMB_LABEL);
        // Let the current bucket finish.
        Thread.sleep(LONG_SLEEP_MILLIS);
        List<Atom> data = StatsConfigUtils.getGaugeMetricDataList(statsManager, sConfigId);
        clearStatsPuller(PULL_ATOM_TAG);
        assertThat(data.size()).isEqualTo(sAtomsPerPull);

        for (int i = 0; i < data.size(); i++) {
            TestAtoms.PullCallbackAtomWrapper atomWrapper = null;
            try {
                atomWrapper = TestAtoms.PullCallbackAtomWrapper.parser()
                        .parseFrom(data.get(i).toByteArray());
            } catch (Exception e) {
                Log.e(LOG_TAG, "Failed to parse primitive atoms");
            }
            assertThat(atomWrapper).isNotNull();
            assertThat(atomWrapper.hasPullCallbackAtom()).isTrue();
            TestAtoms.PullCallbackAtom atom =
                    atomWrapper.getPullCallbackAtom();
            assertThat(atom.getLongVal()).isEqualTo(1);
        }
    }

    private void createAndAddConfigToStatsd(StatsManager statsManager) throws Exception {
        sConfigId = System.currentTimeMillis();
        long triggerMatcherId = sConfigId + 10;
        long pullerMatcherId = sConfigId + 11;
        long metricId = sConfigId + 100;
        StatsdConfig config = StatsConfigUtils.getSimpleTestConfig(sConfigId)
                .addAtomMatcher(
                        StatsConfigUtils.getAppBreadcrumbMatcher(triggerMatcherId,
                                APP_BREADCRUMB_LABEL))
                .addAtomMatcher(AtomMatcher.newBuilder()
                        .setId(pullerMatcherId)
                        .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder()
                                .setAtomId(PULL_ATOM_TAG))
                )
                .addGaugeMetric(GaugeMetric.newBuilder()
                        .setId(metricId)
                        .setWhat(pullerMatcherId)
                        .setTriggerEvent(triggerMatcherId)
                        .setGaugeFieldsFilter(FieldFilter.newBuilder().setIncludeAll(true))
                        .setBucket(TimeUnit.CTS)
                        .setSamplingType(GaugeMetric.SamplingType.FIRST_N_SAMPLES)
                        .setMaxNumGaugeAtomsPerBucket(1000)
                )
                .addPullAtomPackages(PullAtomPackages.newBuilder()
                        .setAtomId(PULL_ATOM_TAG)
                        .addPackages(LibStatsPullTests.class.getPackage().getName()))
                .build();
        statsManager.addConfig(sConfigId, config.toByteArray());
        assertThat(StatsConfigUtils.verifyValidConfigExists(statsManager, sConfigId)).isTrue();
    }

    private native void setStatsPuller(int atomTag, long timeoutMillis, long coolDownMillis,
            int pullReturnVal, long latencyMillis, int atomPerPull);

    private native void clearStatsPuller(int atomTag);
}

