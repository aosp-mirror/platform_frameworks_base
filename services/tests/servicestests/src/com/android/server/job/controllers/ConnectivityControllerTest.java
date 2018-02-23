/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job.controllers;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.support.test.runner.AndroidJUnit4;
import android.util.DataUnit;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
public class ConnectivityControllerTest {
    private Constants mConstants;

    @Before
    public void setUp() throws Exception {
        // Assume all packages are current SDK
        final PackageManagerInternal pm = mock(PackageManagerInternal.class);
        when(pm.getPackageTargetSdkVersion(anyString()))
                .thenReturn(Build.VERSION_CODES.CUR_DEVELOPMENT);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, pm);

        // Freeze the clocks at this moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sUptimeMillisClock =
                Clock.fixed(SystemClock.uptimeMillisClock().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);

        // Assume default constants for now
        mConstants = new Constants();
    }

    @Test
    public void testInsane() throws Exception {
        final Network net = new Network(101);
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);

        // Slow network is too slow
        assertFalse(ConnectivityController.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1), mConstants));
        // Fast network looks great
        assertTrue(ConnectivityController.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1024), mConstants));
    }

    @Test
    public void testCongestion() throws Exception {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        final JobStatus early = createJobStatus(job, now - 1000, now + 2000);
        final JobStatus late = createJobStatus(job, now - 2000, now + 1000);

        // Uncongested network is whenever
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED);
            assertTrue(ConnectivityController.isSatisfied(early, net, caps, mConstants));
            assertTrue(ConnectivityController.isSatisfied(late, net, caps, mConstants));
        }

        // Congested network is more selective
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities();
            assertFalse(ConnectivityController.isSatisfied(early, net, caps, mConstants));
            assertTrue(ConnectivityController.isSatisfied(late, net, caps, mConstants));
        }
    }

    @Test
    public void testRelaxed() throws Exception {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
        final JobStatus early = createJobStatus(job, now - 1000, now + 2000);
        final JobStatus late = createJobStatus(job, now - 2000, now + 1000);

        job.setIsPrefetch(true);
        final JobStatus earlyPrefetch = createJobStatus(job, now - 1000, now + 2000);
        final JobStatus latePrefetch = createJobStatus(job, now - 2000, now + 1000);

        // Unmetered network is whenever
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED);
            assertTrue(ConnectivityController.isSatisfied(early, net, caps, mConstants));
            assertTrue(ConnectivityController.isSatisfied(late, net, caps, mConstants));
            assertTrue(ConnectivityController.isSatisfied(earlyPrefetch, net, caps, mConstants));
            assertTrue(ConnectivityController.isSatisfied(latePrefetch, net, caps, mConstants));
        }

        // Metered network is only when prefetching and late
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED);
            assertFalse(ConnectivityController.isSatisfied(early, net, caps, mConstants));
            assertFalse(ConnectivityController.isSatisfied(late, net, caps, mConstants));
            assertFalse(ConnectivityController.isSatisfied(earlyPrefetch, net, caps, mConstants));
            assertTrue(ConnectivityController.isSatisfied(latePrefetch, net, caps, mConstants));
        }
    }

    private static NetworkCapabilities createCapabilities() {
        return new NetworkCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_VALIDATED);
    }

    private static JobInfo.Builder createJob() {
        return new JobInfo.Builder(101, new ComponentName("foo", "bar"));
    }

    private static JobStatus createJobStatus(JobInfo.Builder job) {
        return createJobStatus(job, 0, Long.MAX_VALUE);
    }

    private static JobStatus createJobStatus(JobInfo.Builder job, long earliestRunTimeElapsedMillis,
            long latestRunTimeElapsedMillis) {
        return new JobStatus(job.build(), 0, null, -1, 0, 0, null, earliestRunTimeElapsedMillis,
                latestRunTimeElapsedMillis, 0, 0, null, 0);
    }
}
