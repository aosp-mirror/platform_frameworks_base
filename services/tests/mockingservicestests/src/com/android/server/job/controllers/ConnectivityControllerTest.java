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
 * limitations under the License.
 */

package com.android.server.job.controllers;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkPolicyManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DataUnit;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.JobServiceContext;
import com.android.server.net.NetworkPolicyManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Clock;
import java.time.ZoneOffset;

@RunWith(MockitoJUnitRunner.class)
public class ConnectivityControllerTest {

    @Mock
    private Context mContext;
    @Mock
    private ConnectivityManager mConnManager;
    @Mock
    private NetworkPolicyManager mNetPolicyManager;
    @Mock
    private NetworkPolicyManagerInternal mNetPolicyManagerInternal;
    @Mock
    private JobSchedulerService mService;

    private Constants mConstants;

    private static final int UID_RED = 10001;
    private static final int UID_BLUE = 10002;

    @Before
    public void setUp() throws Exception {
        // Assume all packages are current SDK
        final PackageManagerInternal pm = mock(PackageManagerInternal.class);
        when(pm.getPackageTargetSdkVersion(anyString()))
                .thenReturn(Build.VERSION_CODES.CUR_DEVELOPMENT);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, pm);

        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
        LocalServices.addService(NetworkPolicyManagerInternal.class, mNetPolicyManagerInternal);

        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());

        // Freeze the clocks at this moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sUptimeMillisClock =
                Clock.fixed(SystemClock.uptimeClock().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);

        // Assume default constants for now
        mConstants = new Constants();

        // Get our mocks ready
        when(mContext.getSystemServiceName(ConnectivityManager.class))
                .thenReturn(Context.CONNECTIVITY_SERVICE);
        when(mContext.getSystemService(ConnectivityManager.class))
                .thenReturn(mConnManager);
        when(mContext.getSystemServiceName(NetworkPolicyManager.class))
                .thenReturn(Context.NETWORK_POLICY_SERVICE);
        when(mContext.getSystemService(NetworkPolicyManager.class))
                .thenReturn(mNetPolicyManager);
        when(mService.getTestableContext()).thenReturn(mContext);
        when(mService.getLock()).thenReturn(mService);
        when(mService.getConstants()).thenReturn(mConstants);
    }

    @Test
    public void testInsane() throws Exception {
        final Network net = new Network(101);
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);

        final ConnectivityController controller = new ConnectivityController(mService);
        when(mService.getMaxJobExecutionTimeMs(any()))
                .thenReturn(JobServiceContext.EXECUTING_TIMESLICE_MILLIS);

        // Slow network is too slow
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1), mConstants));
        // Slow downstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1), mConstants));
        // Slow upstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1024), mConstants));
        // Fast network looks great
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1024), mConstants));
        // Slow network still good given time
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(130)
                        .setLinkDownstreamBandwidthKbps(130), mConstants));

        when(mService.getMaxJobExecutionTimeMs(any())).thenReturn(60_000L);

        // Slow network is too slow
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1), mConstants));
        // Slow downstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(137)
                        .setLinkDownstreamBandwidthKbps(1), mConstants));
        // Slow upstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(137), mConstants));
        // Network good enough
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(137)
                        .setLinkDownstreamBandwidthKbps(137), mConstants));
        // Network slightly too slow given reduced time
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilities().setLinkUpstreamBandwidthKbps(130)
                        .setLinkDownstreamBandwidthKbps(130), mConstants));
    }

    @Test
    public void testCongestion() throws Exception {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        final JobStatus early = createJobStatus(job, now - 1000, now + 2000);
        final JobStatus late = createJobStatus(job, now - 2000, now + 1000);

        final ConnectivityController controller = new ConnectivityController(mService);

        // Uncongested network is whenever
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED);
            assertTrue(controller.isSatisfied(early, net, caps, mConstants));
            assertTrue(controller.isSatisfied(late, net, caps, mConstants));
        }

        // Congested network is more selective
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities();
            assertFalse(controller.isSatisfied(early, net, caps, mConstants));
            assertTrue(controller.isSatisfied(late, net, caps, mConstants));
        }
    }

    @Test
    public void testRelaxed() throws Exception {
        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
        final JobStatus early = createJobStatus(job, now - 1000, now + 2000);
        final JobStatus late = createJobStatus(job, now - 2000, now + 1000);

        job.setPrefetch(true);
        final JobStatus earlyPrefetch = createJobStatus(job, now - 1000, now + 2000);
        final JobStatus latePrefetch = createJobStatus(job, now - 2000, now + 1000);

        final ConnectivityController controller = new ConnectivityController(mService);

        // Unmetered network is whenever
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED);
            assertTrue(controller.isSatisfied(early, net, caps, mConstants));
            assertTrue(controller.isSatisfied(late, net, caps, mConstants));
            assertTrue(controller.isSatisfied(earlyPrefetch, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetch, net, caps, mConstants));
        }

        // Metered network is only when prefetching and late
        {
            final Network net = new Network(101);
            final NetworkCapabilities caps = createCapabilities()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED);
            assertFalse(controller.isSatisfied(early, net, caps, mConstants));
            assertFalse(controller.isSatisfied(late, net, caps, mConstants));
            assertFalse(controller.isSatisfied(earlyPrefetch, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetch, net, caps, mConstants));
        }
    }

    @Test
    public void testUpdates() throws Exception {
        final ArgumentCaptor<NetworkCallback> callback = ArgumentCaptor
                .forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerNetworkCallback(any(), callback.capture());

        final ConnectivityController controller = new ConnectivityController(mService);

        final Network meteredNet = new Network(101);
        final NetworkCapabilities meteredCaps = createCapabilities();
        final Network unmeteredNet = new Network(202);
        final NetworkCapabilities unmeteredCaps = createCapabilities()
                .addCapability(NET_CAPABILITY_NOT_METERED);

        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);

        // Pretend we're offline when job is added
        {
            reset(mConnManager);
            answerNetwork(UID_RED, null, null);
            answerNetwork(UID_BLUE, null, null);

            controller.maybeStartTrackingJobLocked(red, null);
            controller.maybeStartTrackingJobLocked(blue, null);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Metered network
        {
            reset(mConnManager);
            answerNetwork(UID_RED, meteredNet, meteredCaps);
            answerNetwork(UID_BLUE, meteredNet, meteredCaps);

            callback.getValue().onCapabilitiesChanged(meteredNet, meteredCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Unmetered network background
        {
            reset(mConnManager);
            answerNetwork(UID_RED, meteredNet, meteredCaps);
            answerNetwork(UID_BLUE, meteredNet, meteredCaps);

            callback.getValue().onCapabilitiesChanged(unmeteredNet, unmeteredCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Lost metered network
        {
            reset(mConnManager);
            answerNetwork(UID_RED, unmeteredNet, unmeteredCaps);
            answerNetwork(UID_BLUE, unmeteredNet, unmeteredCaps);

            callback.getValue().onLost(meteredNet);

            assertTrue(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Specific UID was blocked
        {
            reset(mConnManager);
            answerNetwork(UID_RED, null, null);
            answerNetwork(UID_BLUE, unmeteredNet, unmeteredCaps);

            callback.getValue().onCapabilitiesChanged(unmeteredNet, unmeteredCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }
    }

    @Test
    public void testRequestStandbyExceptionLocked() {
        final ConnectivityController controller = new ConnectivityController(mService);
        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);

        InOrder inOrder = inOrder(mNetPolicyManagerInternal);

        controller.requestStandbyExceptionLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_RED), eq(true));
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_BLUE), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
        // Whitelisting doesn't need to be requested again.
        controller.requestStandbyExceptionLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_BLUE), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));

        controller.requestStandbyExceptionLocked(blue);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_BLUE), eq(true));
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
    }

    @Test
    public void testWouldBeReadyWithConnectivityLocked() {
        final ConnectivityController controller = spy(new ConnectivityController(mService));
        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);

        doReturn(false).when(controller).isNetworkAvailable(any());
        assertFalse(controller.wouldBeReadyWithConnectivityLocked(red));

        doReturn(true).when(controller).isNetworkAvailable(any());
        doReturn(false).when(controller).wouldBeReadyWithConstraintLocked(any(),
                eq(JobStatus.CONSTRAINT_CONNECTIVITY));
        assertFalse(controller.wouldBeReadyWithConnectivityLocked(red));

        doReturn(true).when(controller).isNetworkAvailable(any());
        doReturn(true).when(controller).wouldBeReadyWithConstraintLocked(any(),
                eq(JobStatus.CONSTRAINT_CONNECTIVITY));
        assertTrue(controller.wouldBeReadyWithConnectivityLocked(red));
    }

    @Test
    public void testEvaluateStateLocked_JobWithoutConnectivity() {
        final ConnectivityController controller = new ConnectivityController(mService);
        final JobStatus red = createJobStatus(createJob().setMinimumLatency(1));

        controller.evaluateStateLocked(red);
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_RED));
        verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
    }

    @Test
    public void testEvaluateStateLocked_JobWouldBeReady() {
        final ConnectivityController controller = spy(new ConnectivityController(mService));
        doReturn(true).when(controller).wouldBeReadyWithConnectivityLocked(any());
        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);

        InOrder inOrder = inOrder(mNetPolicyManagerInternal);

        controller.evaluateStateLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_RED), eq(true));
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_BLUE), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
        // Whitelisting doesn't need to be requested again.
        controller.evaluateStateLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_BLUE), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));

        controller.evaluateStateLocked(blue);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_BLUE), eq(true));
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
    }

    @Test
    public void testEvaluateStateLocked_JobWouldNotBeReady() {
        final ConnectivityController controller = spy(new ConnectivityController(mService));
        doReturn(false).when(controller).wouldBeReadyWithConnectivityLocked(any());
        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);

        InOrder inOrder = inOrder(mNetPolicyManagerInternal);

        controller.evaluateStateLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_BLUE), anyBoolean());
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));

        // Test that a currently whitelisted uid is now removed.
        controller.requestStandbyExceptionLocked(blue);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_BLUE), eq(true));
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
        controller.evaluateStateLocked(blue);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_BLUE), eq(false));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
    }

    @Test
    public void testReevaluateStateLocked() {
        final ConnectivityController controller = spy(new ConnectivityController(mService));
        final JobStatus redOne = createJobStatus(createJob(1)
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus redTwo = createJobStatus(createJob(2)
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);
        controller.maybeStartTrackingJobLocked(redOne, null);
        controller.maybeStartTrackingJobLocked(redTwo, null);
        controller.maybeStartTrackingJobLocked(blue, null);

        InOrder inOrder = inOrder(mNetPolicyManagerInternal);
        controller.requestStandbyExceptionLocked(redOne);
        controller.requestStandbyExceptionLocked(redTwo);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_RED), eq(true));
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));

        // Make sure nothing happens if an exception hasn't been requested.
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
        controller.reevaluateStateLocked(UID_BLUE);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_BLUE), anyBoolean());

        // Make sure a job that isn't being tracked doesn't cause issues.
        assertFalse(controller.isStandbyExceptionRequestedLocked(12345));
        controller.reevaluateStateLocked(12345);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(12345), anyBoolean());

        // Both jobs would still be ready. Exception should not be revoked.
        doReturn(true).when(controller).wouldBeReadyWithConnectivityLocked(any());
        controller.reevaluateStateLocked(UID_RED);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());

        // One job is still ready. Exception should not be revoked.
        doReturn(true).when(controller).wouldBeReadyWithConnectivityLocked(eq(redOne));
        doReturn(false).when(controller).wouldBeReadyWithConnectivityLocked(eq(redTwo));
        controller.reevaluateStateLocked(UID_RED);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));

        // Both jobs are not ready. Exception should be revoked.
        doReturn(false).when(controller).wouldBeReadyWithConnectivityLocked(any());
        controller.reevaluateStateLocked(UID_RED);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_RED), eq(false));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_RED));
    }

    @Test
    public void testMaybeRevokeStandbyExceptionLocked() {
        final ConnectivityController controller = new ConnectivityController(mService);
        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);

        InOrder inOrder = inOrder(mNetPolicyManagerInternal);
        controller.requestStandbyExceptionLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_RED), eq(true));

        // Try revoking for blue instead of red. Red should still have an exception requested.
        controller.maybeRevokeStandbyExceptionLocked(blue);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(anyInt(), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));

        // Now revoke for red.
        controller.maybeRevokeStandbyExceptionLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_RED), eq(false));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_RED));
    }

    @Test
    public void testRestrictedJobTracking() {
        final JobStatus networked = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_CELLULAR), UID_RED);
        final JobStatus unnetworked = createJobStatus(createJob(), UID_BLUE);
        networked.setStandbyBucket(FREQUENT_INDEX);
        unnetworked.setStandbyBucket(FREQUENT_INDEX);

        final Network cellularNet = new Network(101);
        final NetworkCapabilities cellularCaps =
                createCapabilities().addTransportType(TRANSPORT_CELLULAR);
        reset(mConnManager);
        answerNetwork(UID_RED, cellularNet, cellularCaps);
        answerNetwork(UID_BLUE, cellularNet, cellularCaps);

        final ConnectivityController controller = new ConnectivityController(mService);
        controller.maybeStartTrackingJobLocked(networked, null);
        controller.maybeStartTrackingJobLocked(unnetworked, null);

        assertTrue(networked.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        assertFalse(unnetworked.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));

        networked.setStandbyBucket(RESTRICTED_INDEX);
        unnetworked.setStandbyBucket(RESTRICTED_INDEX);
        controller.startTrackingRestrictedJobLocked(networked);
        controller.startTrackingRestrictedJobLocked(unnetworked);
        assertFalse(networked.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        // Unnetworked shouldn't be affected by ConnectivityController since it doesn't have a
        // connectivity constraint.
        assertFalse(unnetworked.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));

        networked.setStandbyBucket(RARE_INDEX);
        unnetworked.setStandbyBucket(RARE_INDEX);
        controller.stopTrackingRestrictedJobLocked(networked);
        controller.stopTrackingRestrictedJobLocked(unnetworked);
        assertTrue(networked.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        // Unnetworked shouldn't be affected by ConnectivityController since it doesn't have a
        // connectivity constraint.
        assertFalse(unnetworked.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
    }

    private void answerNetwork(int uid, Network net, NetworkCapabilities caps) {
        when(mConnManager.getActiveNetworkForUid(eq(uid))).thenReturn(net);
        when(mConnManager.getNetworkCapabilities(eq(net))).thenReturn(caps);
        if (net != null) {
            final NetworkInfo ni = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, null, null);
            ni.setDetailedState(DetailedState.CONNECTED, null, null);
            when(mConnManager.getNetworkInfoForUid(eq(net), eq(uid), anyBoolean())).thenReturn(ni);
        }
    }

    private static NetworkCapabilities createCapabilities() {
        return new NetworkCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_VALIDATED);
    }

    private static JobInfo.Builder createJob() {
        return createJob(101);
    }

    private static JobInfo.Builder createJob(int jobId) {
        return new JobInfo.Builder(jobId, new ComponentName("foo", "bar"));
    }

    private static JobStatus createJobStatus(JobInfo.Builder job) {
        return createJobStatus(job, android.os.Process.NOBODY_UID, 0, Long.MAX_VALUE);
    }

    private static JobStatus createJobStatus(JobInfo.Builder job, int uid) {
        return createJobStatus(job, uid, 0, Long.MAX_VALUE);
    }

    private static JobStatus createJobStatus(JobInfo.Builder job,
            long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis) {
        return createJobStatus(job, android.os.Process.NOBODY_UID,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis);
    }

    private static JobStatus createJobStatus(JobInfo.Builder job, int uid,
            long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis) {
        return new JobStatus(job.build(), uid, null, -1, 0, null,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis, 0, 0, null, 0);
    }
}
