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
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.DataUnit;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
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
import java.util.Set;

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
    public void testUsable() throws Exception {
        final Network net = mock(Network.class);
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        final JobInfo.Builder jobWithMinChunk = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setMinimumNetworkChunkBytes(DataUnit.KIBIBYTES.toBytes(100))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);

        when(mService.isBatteryCharging()).thenReturn(false);
        final ConnectivityController controller = new ConnectivityController(mService);
        when(mService.getMaxJobExecutionTimeMs(any())).thenReturn(10 * 60_000L);
        controller.onBatteryStateChangedLocked();

        // Slow network is too slow
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1).build(), mConstants));
        assertFalse(controller.isSatisfied(createJobStatus(jobWithMinChunk), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1).build(), mConstants));
        // Slow downstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1).build(), mConstants));
        assertFalse(controller.isSatisfied(createJobStatus(jobWithMinChunk), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1).build(), mConstants));
        // Slow upstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1024).build(), mConstants));
        assertFalse(controller.isSatisfied(createJobStatus(jobWithMinChunk), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1024).build(), mConstants));
        // Medium network is fine for min chunk
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(5)
                        .setLinkDownstreamBandwidthKbps(5).build(), mConstants));
        assertTrue(controller.isSatisfied(createJobStatus(jobWithMinChunk), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(5)
                        .setLinkDownstreamBandwidthKbps(5).build(), mConstants));
        // Medium downstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(5).build(), mConstants));
        assertTrue(controller.isSatisfied(createJobStatus(jobWithMinChunk), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(5).build(), mConstants));
        // Medium upstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(5)
                        .setLinkDownstreamBandwidthKbps(1024).build(), mConstants));
        assertTrue(controller.isSatisfied(createJobStatus(jobWithMinChunk), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(5)
                        .setLinkDownstreamBandwidthKbps(1024).build(), mConstants));
        // Fast network looks great
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1024).build(), mConstants));
        assertTrue(controller.isSatisfied(createJobStatus(jobWithMinChunk), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1024).build(), mConstants));
        // Slow network still good given time
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(130)
                        .setLinkDownstreamBandwidthKbps(130).build(), mConstants));
        // Slow network is too slow, but device is charging and network is unmetered.
        when(mService.isBatteryCharging()).thenReturn(true);
        controller.onBatteryStateChangedLocked();
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().addCapability(NET_CAPABILITY_NOT_METERED)
                        .setLinkUpstreamBandwidthKbps(1).setLinkDownstreamBandwidthKbps(1).build(),
                mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        controller.onBatteryStateChangedLocked();
        when(mService.getMaxJobExecutionTimeMs(any())).thenReturn(60_000L);

        // Slow network is too slow
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(1).build(), mConstants));
        // Slow downstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(137)
                        .setLinkDownstreamBandwidthKbps(1).build(), mConstants));
        // Slow upstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(137).build(), mConstants));
        // Network good enough
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(137)
                        .setLinkDownstreamBandwidthKbps(137).build(), mConstants));
        // Network slightly too slow given reduced time
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(130)
                        .setLinkDownstreamBandwidthKbps(130).build(), mConstants));
        // Slow network is too slow, but device is charging and network is unmetered.
        when(mService.isBatteryCharging()).thenReturn(true);
        controller.onBatteryStateChangedLocked();
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().addCapability(NET_CAPABILITY_NOT_METERED)
                        .setLinkUpstreamBandwidthKbps(1).setLinkDownstreamBandwidthKbps(1).build(),
                mConstants));
    }

    @Test
    public void testInsane() throws Exception {
        final Network net = mock(Network.class);
        final JobInfo.Builder job = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);

        final ConnectivityController controller = new ConnectivityController(mService);
        when(mService.getMaxJobExecutionTimeMs(any())).thenReturn(10 * 60_000L);

        // Suspended networks aren't usable.
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().removeCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        .setLinkUpstreamBandwidthKbps(1024).setLinkDownstreamBandwidthKbps(1024)
                        .build(),
                mConstants));

        // Not suspended networks are usable.
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1024)
                        .setLinkDownstreamBandwidthKbps(1024).build(), mConstants));
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
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED).build();
            assertTrue(controller.isSatisfied(early, net, caps, mConstants));
            assertTrue(controller.isSatisfied(late, net, caps, mConstants));
        }

        // Congested network is more selective
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder().build();
            assertFalse(controller.isSatisfied(early, net, caps, mConstants));
            assertTrue(controller.isSatisfied(late, net, caps, mConstants));
        }
    }

    @Test
    public void testStrongEnough_Cellular() {
        mConstants.CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS = 0;

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final TelephonyManager telephonyManager = mock(TelephonyManager.class);
        when(mContext.getSystemService(TelephonyManager.class))
                .thenReturn(telephonyManager);
        when(telephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(telephonyManager);
        final ArgumentCaptor<TelephonyCallback> signalStrengthsCaptor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        doNothing().when(telephonyManager)
                .registerTelephonyCallback(any(), signalStrengthsCaptor.capture());
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerNetworkCallback(any(), callbackCaptor.capture());
        final Network net = mock(Network.class);
        final NetworkCapabilities caps = createCapabilitiesBuilder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                .setSubscriptionIds(Set.of(7357))
                .build();
        final JobInfo.Builder baseJobBuilder = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        final JobStatus jobExp = createJobStatus(baseJobBuilder.setExpedited(true));
        final JobStatus jobHigh = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_HIGH));
        final JobStatus jobDefEarly = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_DEFAULT),
                now - 1000, now + 100_000);
        final JobStatus jobDefLate = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_DEFAULT),
                now - 100_000, now + 1000);
        final JobStatus jobLow = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_LOW));
        final JobStatus jobMin = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_MIN));
        final JobStatus jobMinRunner = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_MIN));

        final ConnectivityController controller = new ConnectivityController(mService);

        final NetworkCallback generalCallback = callbackCaptor.getValue();

        when(mService.getMaxJobExecutionTimeMs(any())).thenReturn(10 * 60_000L);

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        answerNetwork(generalCallback, null, null, net, caps);

        final TelephonyCallback.SignalStrengthsListener signalStrengthsListener =
                (TelephonyCallback.SignalStrengthsListener) signalStrengthsCaptor.getValue();

        controller.maybeStartTrackingJobLocked(jobMinRunner, null);
        controller.prepareForExecutionLocked(jobMinRunner);

        final SignalStrength signalStrength = mock(SignalStrength.class);

        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(true);
        when(mService.isBatteryNotLow()).thenReturn(false);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(true);
        when(mService.isBatteryNotLow()).thenReturn(true);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_MODERATE);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(true);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertFalse(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(true);
        when(mService.isBatteryNotLow()).thenReturn(true);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_GREAT);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));
    }

    @Test
    public void testStrongEnough_Cellular_CheckDisabled() {
        mConstants.CONN_USE_CELL_SIGNAL_STRENGTH = false;
        mConstants.CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS = 0;

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final TelephonyManager telephonyManager = mock(TelephonyManager.class);
        when(mContext.getSystemService(TelephonyManager.class))
                .thenReturn(telephonyManager);
        when(telephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(telephonyManager);
        final ArgumentCaptor<TelephonyCallback> signalStrengthsCaptor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        doNothing().when(telephonyManager)
                .registerTelephonyCallback(any(), signalStrengthsCaptor.capture());
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerNetworkCallback(any(), callbackCaptor.capture());
        final Network net = mock(Network.class);
        final NetworkCapabilities caps = createCapabilitiesBuilder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                .setSubscriptionIds(Set.of(7357))
                .build();
        final JobInfo.Builder baseJobBuilder = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        final JobStatus jobExp = createJobStatus(baseJobBuilder.setExpedited(true));
        final JobStatus jobHigh = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_HIGH));
        final JobStatus jobDefEarly = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_DEFAULT),
                now - 1000, now + 100_000);
        final JobStatus jobDefLate = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_DEFAULT),
                now - 100_000, now + 1000);
        final JobStatus jobLow = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_LOW));
        final JobStatus jobMin = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_MIN));
        final JobStatus jobMinRunner = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_MIN));

        final ConnectivityController controller = new ConnectivityController(mService);

        final NetworkCallback generalCallback = callbackCaptor.getValue();

        when(mService.getMaxJobExecutionTimeMs(any())).thenReturn(10 * 60_000L);

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        answerNetwork(generalCallback, null, null, net, caps);

        final TelephonyCallback.SignalStrengthsListener signalStrengthsListener =
                (TelephonyCallback.SignalStrengthsListener) signalStrengthsCaptor.getValue();

        controller.maybeStartTrackingJobLocked(jobMinRunner, null);
        controller.prepareForExecutionLocked(jobMinRunner);

        final SignalStrength signalStrength = mock(SignalStrength.class);

        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_POOR);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(true);
        when(mService.isBatteryNotLow()).thenReturn(false);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(true);
        when(mService.isBatteryNotLow()).thenReturn(true);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_MODERATE);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(true);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(true);
        when(mService.isBatteryNotLow()).thenReturn(true);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_GOOD);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_GREAT);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));
    }

    @Test
    public void testStrongEnough_Cellular_VPN() {
        mConstants.CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS = 0;

        final long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        final TelephonyManager telephonyManager = mock(TelephonyManager.class);
        when(mContext.getSystemService(TelephonyManager.class))
                .thenReturn(telephonyManager);
        when(telephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(telephonyManager);
        final ArgumentCaptor<TelephonyCallback> signalStrengthsCaptor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        doNothing().when(telephonyManager)
                .registerTelephonyCallback(any(), signalStrengthsCaptor.capture());
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerNetworkCallback(any(), callbackCaptor.capture());
        final Network net = mock(Network.class);
        final NetworkCapabilities caps = createCapabilitiesBuilder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addTransportType(TRANSPORT_VPN)
                .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                .setSubscriptionIds(Set.of(7357))
                .build();
        final JobInfo.Builder baseJobBuilder = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        final JobStatus jobExp = createJobStatus(baseJobBuilder.setExpedited(true));
        final JobStatus jobHigh = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_HIGH));
        final JobStatus jobDefEarly = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_DEFAULT),
                now - 1000, now + 100_000);
        final JobStatus jobDefLate = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_DEFAULT),
                now - 100_000, now + 1000);
        final JobStatus jobLow = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_LOW));
        final JobStatus jobMin = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_MIN));
        final JobStatus jobMinRunner = createJobStatus(
                baseJobBuilder.setExpedited(false).setPriority(JobInfo.PRIORITY_MIN));

        final ConnectivityController controller = new ConnectivityController(mService);

        final NetworkCallback generalCallback = callbackCaptor.getValue();

        when(mService.getMaxJobExecutionTimeMs(any())).thenReturn(10 * 60_000L);

        when(mService.isBatteryCharging()).thenReturn(false);
        when(mService.isBatteryNotLow()).thenReturn(false);
        answerNetwork(generalCallback, null, null, net, caps);

        final TelephonyCallback.SignalStrengthsListener signalStrengthsListener =
                (TelephonyCallback.SignalStrengthsListener) signalStrengthsCaptor.getValue();

        controller.maybeStartTrackingJobLocked(jobMinRunner, null);
        controller.prepareForExecutionLocked(jobMinRunner);

        final SignalStrength signalStrength = mock(SignalStrength.class);

        when(signalStrength.getLevel()).thenReturn(
                CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN);
        signalStrengthsListener.onSignalStrengthsChanged(signalStrength);

        // We don't restrict data via VPN over cellular.
        assertTrue(controller.isSatisfied(jobExp, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobHigh, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefEarly, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobDefLate, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobLow, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMin, net, caps, mConstants));
        assertTrue(controller.isSatisfied(jobMinRunner, net, caps, mConstants));
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

        job.setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN, DataUnit.MEBIBYTES.toBytes(1));
        final JobStatus latePrefetchUnknownDown = createJobStatus(job, now - 2000, now + 1000);
        job.setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), JobInfo.NETWORK_BYTES_UNKNOWN);
        final JobStatus latePrefetchUnknownUp = createJobStatus(job, now - 2000, now + 1000);

        final ConnectivityController controller = new ConnectivityController(mService);

        when(mNetPolicyManagerInternal.getSubscriptionOpportunisticQuota(
                any(), eq(NetworkPolicyManagerInternal.QUOTA_TYPE_JOBS)))
                .thenReturn(0L);

        // Unmetered network is whenever
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .build();
            assertTrue(controller.isSatisfied(early, net, caps, mConstants));
            assertTrue(controller.isSatisfied(late, net, caps, mConstants));
            assertTrue(controller.isSatisfied(earlyPrefetch, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetch, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetchUnknownDown, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetchUnknownUp, net, caps, mConstants));
        }

        // Metered network is only when prefetching, late, and in opportunistic quota
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .build();
            assertFalse(controller.isSatisfied(early, net, caps, mConstants));
            assertFalse(controller.isSatisfied(late, net, caps, mConstants));
            assertFalse(controller.isSatisfied(earlyPrefetch, net, caps, mConstants));
            assertFalse(controller.isSatisfied(latePrefetch, net, caps, mConstants));
            assertFalse(controller.isSatisfied(latePrefetchUnknownDown, net, caps, mConstants));
            assertFalse(controller.isSatisfied(latePrefetchUnknownUp, net, caps, mConstants));

            when(mNetPolicyManagerInternal.getSubscriptionOpportunisticQuota(
                    any(), eq(NetworkPolicyManagerInternal.QUOTA_TYPE_JOBS)))
                    .thenReturn(9876543210L);
            assertTrue(controller.isSatisfied(latePrefetch, net, caps, mConstants));
            // Only relax restrictions when we at least know the estimated download bytes.
            assertFalse(controller.isSatisfied(latePrefetchUnknownDown, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetchUnknownUp, net, caps, mConstants));
        }
    }

    @Test
    public void testUpdates() throws Exception {
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerNetworkCallback(any(), callbackCaptor.capture());
        final ArgumentCaptor<NetworkCallback> redCallbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerDefaultNetworkCallbackForUid(
                eq(UID_RED), redCallbackCaptor.capture(), any());
        final ArgumentCaptor<NetworkCallback> blueCallbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerDefaultNetworkCallbackForUid(
                eq(UID_BLUE), blueCallbackCaptor.capture(), any());

        final ConnectivityController controller = new ConnectivityController(mService);

        final Network meteredNet = mock(Network.class);
        final NetworkCapabilities meteredCaps = createCapabilitiesBuilder().build();
        final Network unmeteredNet = mock(Network.class);
        final NetworkCapabilities unmeteredCaps = createCapabilitiesBuilder()
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .build();

        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);
        controller.maybeStartTrackingJobLocked(red, null);
        controller.maybeStartTrackingJobLocked(blue, null);
        final NetworkCallback generalCallback = callbackCaptor.getValue();
        final NetworkCallback redCallback = redCallbackCaptor.getValue();
        final NetworkCallback blueCallback = blueCallbackCaptor.getValue();

        // Pretend we're offline when job is added
        {
            answerNetwork(generalCallback, redCallback, null, null, null);
            answerNetwork(generalCallback, blueCallback, null, null, null);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Metered network
        {
            answerNetwork(generalCallback, redCallback, null, meteredNet, meteredCaps);
            answerNetwork(generalCallback, blueCallback, null, meteredNet, meteredCaps);

            generalCallback.onCapabilitiesChanged(meteredNet, meteredCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Unmetered network background
        {
            answerNetwork(generalCallback, redCallback, meteredNet, meteredNet, meteredCaps);
            answerNetwork(generalCallback, blueCallback, meteredNet, meteredNet, meteredCaps);

            generalCallback.onCapabilitiesChanged(unmeteredNet, unmeteredCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Lost metered network
        {
            answerNetwork(generalCallback, redCallback, meteredNet, unmeteredNet, unmeteredCaps);
            answerNetwork(generalCallback, blueCallback, meteredNet, unmeteredNet, unmeteredCaps);

            generalCallback.onLost(meteredNet);

            assertTrue(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
        }

        // Specific UID was blocked
        {
            answerNetwork(generalCallback, redCallback, unmeteredNet, null, null);
            answerNetwork(generalCallback, blueCallback, unmeteredNet, unmeteredNet, unmeteredCaps);

            generalCallback.onCapabilitiesChanged(unmeteredNet, unmeteredCaps);

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
        // Allowlisting doesn't need to be requested again.
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
        doReturn(true).when(controller)
                .wouldBeReadyWithConstraintLocked(any(), eq(JobStatus.CONSTRAINT_CONNECTIVITY));
        doReturn(true).when(controller).isNetworkAvailable(any());
        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);
        controller.maybeStartTrackingJobLocked(red, null);
        controller.maybeStartTrackingJobLocked(blue, null);

        InOrder inOrder = inOrder(mNetPolicyManagerInternal);

        controller.evaluateStateLocked(red);
        inOrder.verify(mNetPolicyManagerInternal, times(1))
                .setAppIdleWhitelist(eq(UID_RED), eq(true));
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_BLUE), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_BLUE));
        // Allowlisting doesn't need to be requested again.
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
        doReturn(false).when(controller)
                .wouldBeReadyWithConstraintLocked(any(), eq(JobStatus.CONSTRAINT_CONNECTIVITY));
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

        // Test that a currently allowlisted uid is now removed.
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
        doReturn(true).when(controller)
                .wouldBeReadyWithConstraintLocked(any(), eq(JobStatus.CONSTRAINT_CONNECTIVITY));
        doReturn(true).when(controller).isNetworkAvailable(any());
        controller.reevaluateStateLocked(UID_RED);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());

        // One job is still ready. Exception should not be revoked.
        doReturn(true).when(controller).wouldBeReadyWithConstraintLocked(
                eq(redOne), eq(JobStatus.CONSTRAINT_CONNECTIVITY));
        doReturn(false).when(controller).wouldBeReadyWithConstraintLocked(
                eq(redTwo), eq(JobStatus.CONSTRAINT_CONNECTIVITY));
        controller.reevaluateStateLocked(UID_RED);
        inOrder.verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
        assertTrue(controller.isStandbyExceptionRequestedLocked(UID_RED));

        // Both jobs are not ready. Exception should be revoked.
        doReturn(false).when(controller)
                .wouldBeReadyWithConstraintLocked(any(), eq(JobStatus.CONSTRAINT_CONNECTIVITY));
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
        final ArgumentCaptor<NetworkCallback> callback =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerNetworkCallback(any(), callback.capture());
        final ArgumentCaptor<NetworkCallback> redCallback =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerDefaultNetworkCallbackForUid(
                eq(UID_RED), redCallback.capture(), any());
        final ArgumentCaptor<NetworkCallback> blueCallback =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerDefaultNetworkCallbackForUid(
                eq(UID_BLUE), blueCallback.capture(), any());

        final JobStatus networked = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_CELLULAR), UID_RED);
        final JobStatus unnetworked = createJobStatus(createJob(), UID_BLUE);
        networked.setStandbyBucket(FREQUENT_INDEX);
        unnetworked.setStandbyBucket(FREQUENT_INDEX);

        final Network cellularNet = mock(Network.class);
        final NetworkCapabilities cellularCaps =
                createCapabilitiesBuilder().addTransportType(TRANSPORT_CELLULAR).build();

        final ConnectivityController controller = new ConnectivityController(mService);
        controller.maybeStartTrackingJobLocked(networked, null);
        controller.maybeStartTrackingJobLocked(unnetworked, null);
        answerNetwork(callback.getValue(), redCallback.getValue(), null, cellularNet, cellularCaps);

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

    private void answerNetwork(@NonNull NetworkCallback generalCallback,
            @Nullable NetworkCallback uidCallback, @Nullable Network lastNetwork,
            @Nullable Network net, @Nullable NetworkCapabilities caps) {
        if (net == null) {
            generalCallback.onLost(lastNetwork);
            if (uidCallback != null) {
                uidCallback.onLost(lastNetwork);
            }
        } else {
            generalCallback.onAvailable(net);
            generalCallback.onCapabilitiesChanged(net, caps);
            if (uidCallback != null) {
                uidCallback.onAvailable(net);
                uidCallback.onBlockedStatusChanged(net, ConnectivityManager.BLOCKED_REASON_NONE);
                uidCallback.onCapabilitiesChanged(net, caps);
            }
        }
    }

    private static NetworkCapabilities.Builder createCapabilitiesBuilder() {
        return new NetworkCapabilities.Builder().addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
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
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis, 0, 0, null, 0, 0);
    }
}
