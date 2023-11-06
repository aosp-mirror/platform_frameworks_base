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
import static android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.Flags.FLAG_RELAX_PREFETCH_CONNECTIVITY_CONSTRAINT_ONLY_ON_CHARGER;
import static com.android.server.job.JobSchedulerService.FREQUENT_INDEX;
import static com.android.server.job.JobSchedulerService.RARE_INDEX;
import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.controllers.ConnectivityController.CcConfig.KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY;
import static com.android.server.job.controllers.ConnectivityController.TRANSPORT_AFFINITY_AVOID;
import static com.android.server.job.controllers.ConnectivityController.TRANSPORT_AFFINITY_PREFER;
import static com.android.server.job.controllers.ConnectivityController.TRANSPORT_AFFINITY_UNDEFINED;

import static org.junit.Assert.assertEquals;
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
import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.DataUnit;

import com.android.server.AppSchedulingModuleThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.net.NetworkPolicyManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

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
    @Mock
    private PackageManager mPackageManager;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Constants mConstants;
    private DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder;

    private FlexibilityController mFlexibilityController;
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

        // Used in JobStatus.
        LocalServices.removeServiceForTest(JobSchedulerInternal.class);
        LocalServices.addService(JobSchedulerInternal.class, mock(JobSchedulerInternal.class));

        mDeviceConfigPropertiesBuilder =
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);

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
        // Instantiate Flexibility Controller
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(false);
        mFlexibilityController =
                spy(new FlexibilityController(mService, mock(PrefetchController.class)));
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
        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);
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
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(140)
                        .setLinkDownstreamBandwidthKbps(1).build(), mConstants));
        // Slow upstream
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(1)
                        .setLinkDownstreamBandwidthKbps(140).build(), mConstants));
        // Network good enough
        assertTrue(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(140)
                        .setLinkDownstreamBandwidthKbps(140).build(), mConstants));
        // Network slightly too slow given reduced time
        assertFalse(controller.isSatisfied(createJobStatus(job), net,
                createCapabilitiesBuilder().setLinkUpstreamBandwidthKbps(139)
                        .setLinkDownstreamBandwidthKbps(139).build(), mConstants));
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

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);
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

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

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
    public void testMeteredAllowed() throws Exception {
        final JobInfo.Builder jobBuilder = createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1),
                        DataUnit.MEBIBYTES.toBytes(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        final JobStatus job = spy(createJobStatus(jobBuilder));

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

        // Unmetered network is always "metered allowed"
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .build();
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));
        }

        // Temporarily unmetered network is always "metered allowed"
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                    .build();
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));
        }

        // Respond with the default values in NetworkPolicyManager. If those ever change enough
        // to cause these tests to fail, we would likely need to go and update
        // ConnectivityController.
        doAnswer(
                (Answer<Integer>) invocationOnMock
                        -> NetworkPolicyManager.getDefaultProcessNetworkCapabilities(
                        invocationOnMock.getArgument(0)))
                .when(mService).getUidCapabilities(anyInt());

        // Foreground is always allowed for metered network
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .build();

            when(mService.getUidProcState(anyInt()))
                    .thenReturn(ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));

            when(mService.getUidProcState(anyInt()))
                    .thenReturn(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));

            when(mService.getUidProcState(anyInt())).thenReturn(ActivityManager.PROCESS_STATE_TOP);
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));

            when(mService.getUidProcState(anyInt())).thenReturn(JobInfo.BIAS_DEFAULT);
            when(job.getFlags()).thenReturn(JobInfo.FLAG_WILL_BE_FOREGROUND);
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));
        }

        when(mService.getUidProcState(anyInt())).thenReturn(ActivityManager.PROCESS_STATE_UNKNOWN);
        when(job.getFlags()).thenReturn(0);

        // User initiated is always allowed for metered network
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .build();
            when(job.shouldTreatAsUserInitiatedJob()).thenReturn(true);
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));
        }

        // Background non-user-initiated should follow the app's restricted state
        {
            final Network net = mock(Network.class);
            final NetworkCapabilities caps = createCapabilitiesBuilder()
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .build();
            when(job.shouldTreatAsUserInitiatedJob()).thenReturn(false);
            when(mNetPolicyManager.getRestrictBackgroundStatus(anyInt()))
                    .thenReturn(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED);
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));
            // Test cache
            when(mNetPolicyManager.getRestrictBackgroundStatus(anyInt()))
                    .thenReturn(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED);
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));
            // Clear cache
            controller.onAppRemovedLocked(job.getSourcePackageName(), job.getSourceUid());
            assertFalse(controller.isSatisfied(job, net, caps, mConstants));
            // Test cache
            when(mNetPolicyManager.getRestrictBackgroundStatus(anyInt()))
                    .thenReturn(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED);
            assertFalse(controller.isSatisfied(job, net, caps, mConstants));
            // Clear cache
            controller.onAppRemovedLocked(job.getSourcePackageName(), job.getSourceUid());
            assertTrue(controller.isSatisfied(job, net, caps, mConstants));
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

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

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

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

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

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

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

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

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

        // Metered network is only when prefetching, charging*, late, and in opportunistic quota
        // *Charging only when the flag is enabled
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
            mSetFlagsRule.disableFlags(FLAG_RELAX_PREFETCH_CONNECTIVITY_CONSTRAINT_ONLY_ON_CHARGER);
            when(mService.isBatteryCharging()).thenReturn(false);
            when(mService.isBatteryNotLow()).thenReturn(false);

            when(mNetPolicyManagerInternal.getSubscriptionOpportunisticQuota(
                    any(), eq(NetworkPolicyManagerInternal.QUOTA_TYPE_JOBS)))
                    .thenReturn(9876543210L);
            assertTrue(controller.isSatisfied(latePrefetch, net, caps, mConstants));
            // Only relax restrictions when we at least know the estimated download bytes.
            assertFalse(controller.isSatisfied(latePrefetchUnknownDown, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetchUnknownUp, net, caps, mConstants));

            mSetFlagsRule.enableFlags(FLAG_RELAX_PREFETCH_CONNECTIVITY_CONSTRAINT_ONLY_ON_CHARGER);
            when(mNetPolicyManagerInternal.getSubscriptionOpportunisticQuota(
                    any(), eq(NetworkPolicyManagerInternal.QUOTA_TYPE_JOBS)))
                    .thenReturn(9876543210L);
            assertFalse(controller.isSatisfied(latePrefetch, net, caps, mConstants));
            // Only relax restrictions when we at least know the estimated download bytes.
            assertFalse(controller.isSatisfied(latePrefetchUnknownDown, net, caps, mConstants));
            assertFalse(controller.isSatisfied(latePrefetchUnknownUp, net, caps, mConstants));

            when(mService.isBatteryCharging()).thenReturn(true);
            assertFalse(controller.isSatisfied(latePrefetch, net, caps, mConstants));
            // Only relax restrictions when we at least know the estimated download bytes.
            assertFalse(controller.isSatisfied(latePrefetchUnknownDown, net, caps, mConstants));
            assertFalse(controller.isSatisfied(latePrefetchUnknownUp, net, caps, mConstants));

            when(mService.isBatteryNotLow()).thenReturn(true);
            assertTrue(controller.isSatisfied(latePrefetch, net, caps, mConstants));
            // Only relax restrictions when we at least know the estimated download bytes.
            assertFalse(controller.isSatisfied(latePrefetchUnknownDown, net, caps, mConstants));
            assertTrue(controller.isSatisfied(latePrefetchUnknownUp, net, caps, mConstants));
        }
    }

    @Test
    public void testUpdates() throws Exception {
        ConnectivityController.sNetworkTransportAffinities.put(
                NetworkCapabilities.TRANSPORT_CELLULAR, TRANSPORT_AFFINITY_AVOID);
        ConnectivityController.sNetworkTransportAffinities.put(
                NetworkCapabilities.TRANSPORT_WIFI, TRANSPORT_AFFINITY_PREFER);
        ConnectivityController.sNetworkTransportAffinities.put(
                NetworkCapabilities.TRANSPORT_TEST, TRANSPORT_AFFINITY_UNDEFINED);
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

        doReturn(true).when(mFlexibilityController).isEnabled();

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

        final Network meteredNet = mock(Network.class);
        final NetworkCapabilities meteredCaps = createCapabilitiesBuilder().build();
        final Network unmeteredNet = mock(Network.class);
        final NetworkCapabilities unmeteredCaps = createCapabilitiesBuilder()
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .build();
        final NetworkCapabilities meteredWifiCaps = createCapabilitiesBuilder()
                .addTransportType(TRANSPORT_WIFI)
                .build();
        final NetworkCapabilities unmeteredCelullarCaps = createCapabilitiesBuilder()
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .addTransportType(TRANSPORT_CELLULAR)
                .build();

        final JobStatus red = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED), UID_RED);
        final JobStatus blue = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY), UID_BLUE);
        final JobStatus red2 = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetwork(
                        new NetworkRequest.Builder()
                                .addTransportType(TRANSPORT_CELLULAR)
                                .build()),
                        UID_RED);
        final JobStatus blue2 = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(1), 0)
                .setRequiredNetwork(
                        new NetworkRequest.Builder()
                                .addTransportType(TRANSPORT_WIFI)
                                .build()),
                        UID_BLUE);
        assertTrue(red.canApplyTransportAffinities());
        assertTrue(blue.canApplyTransportAffinities());
        assertFalse(red2.canApplyTransportAffinities());
        assertFalse(blue2.canApplyTransportAffinities());

        controller.maybeStartTrackingJobLocked(red, null);
        controller.maybeStartTrackingJobLocked(blue, null);
        controller.maybeStartTrackingJobLocked(red2, null);
        controller.maybeStartTrackingJobLocked(blue2, null);
        final NetworkCallback generalCallback = callbackCaptor.getValue();
        final NetworkCallback redCallback = redCallbackCaptor.getValue();
        final NetworkCallback blueCallback = blueCallbackCaptor.getValue();

        // Pretend we're offline when job is added
        {
            answerNetwork(generalCallback, redCallback, null, null, null);
            answerNetwork(generalCallback, blueCallback, null, null, null);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(red2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(blue2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertFalse(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
        }

        // Metered network
        {
            answerNetwork(generalCallback, redCallback, null, meteredNet, meteredCaps);
            answerNetwork(generalCallback, blueCallback, null, meteredNet, meteredCaps);

            generalCallback.onCapabilitiesChanged(meteredNet, meteredCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(red2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(blue2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            // No transport is specified. Accept the network for transport affinity.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, false);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertTrue(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
            // No transport is specified. Avoid the network for transport affinity.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, true);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertFalse(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
        }

        // Unmetered network background for general; metered network for apps
        {
            answerNetwork(generalCallback, redCallback, meteredNet, meteredNet, meteredCaps);
            answerNetwork(generalCallback, blueCallback, meteredNet, meteredNet, meteredCaps);

            generalCallback.onCapabilitiesChanged(unmeteredNet, unmeteredCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));

            // No transport is specified. Accept the network for transport affinity.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, false);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertTrue(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
            // No transport is specified. Avoid the network for transport affinity.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, true);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertFalse(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
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

        // Metered wifi
        {
            answerNetwork(generalCallback, redCallback, null, meteredNet, meteredWifiCaps);
            answerNetwork(generalCallback, blueCallback, unmeteredNet, meteredNet, meteredWifiCaps);

            generalCallback.onCapabilitiesChanged(meteredNet, meteredWifiCaps);

            assertFalse(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(red2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));

            // Wifi is preferred.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, false);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertTrue(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertTrue(blue2.areTransportAffinitiesSatisfied());
            // Wifi is preferred.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, true);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertTrue(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertTrue(blue2.areTransportAffinitiesSatisfied());
        }

        // Unmetered cellular
        {
            answerNetwork(generalCallback, redCallback, meteredNet,
                    unmeteredNet, unmeteredCelullarCaps);
            answerNetwork(generalCallback, blueCallback, meteredNet,
                    unmeteredNet, unmeteredCelullarCaps);

            generalCallback.onCapabilitiesChanged(unmeteredNet, unmeteredCelullarCaps);

            assertTrue(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(red2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(blue2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));

            // Cellular is avoided.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, false);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertFalse(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
            // Cellular is avoided.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, true);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertFalse(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
        }

        // Undefined affinity
        final NetworkCapabilities unmeteredTestCaps = createCapabilitiesBuilder()
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .addTransportType(TRANSPORT_TEST)
                .build();
        {
            answerNetwork(generalCallback, redCallback, unmeteredNet,
                    unmeteredNet, unmeteredTestCaps);
            answerNetwork(generalCallback, blueCallback, unmeteredNet,
                    unmeteredNet, unmeteredTestCaps);

            generalCallback.onCapabilitiesChanged(unmeteredNet, unmeteredTestCaps);

            assertTrue(red.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertTrue(blue.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(red2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));
            assertFalse(blue2.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY));

            // Undefined is preferred.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, false);
            controller.onConstantsUpdatedLocked();
            assertTrue(red.areTransportAffinitiesSatisfied());
            assertTrue(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
            // Undefined is avoided.
            setDeviceConfigBoolean(controller, KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY, true);
            controller.onConstantsUpdatedLocked();
            assertFalse(red.areTransportAffinitiesSatisfied());
            assertFalse(blue.areTransportAffinitiesSatisfied());
            assertFalse(red2.areTransportAffinitiesSatisfied());
            assertFalse(blue2.areTransportAffinitiesSatisfied());
        }
    }

    @Test
    public void testRequestStandbyExceptionLocked() {
        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);
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
        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);
        final JobStatus red = createJobStatus(createJob().setMinimumLatency(1));

        controller.evaluateStateLocked(red);
        assertFalse(controller.isStandbyExceptionRequestedLocked(UID_RED));
        verify(mNetPolicyManagerInternal, never())
                .setAppIdleWhitelist(eq(UID_RED), anyBoolean());
    }

    @Test
    public void testEvaluateStateLocked_JobWouldBeReady() {
        final ConnectivityController controller = spy(
                new ConnectivityController(mService, mFlexibilityController));
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
        final ConnectivityController controller = spy(
                new ConnectivityController(mService, mFlexibilityController));
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
        final ConnectivityController controller = spy(
                new ConnectivityController(mService, mFlexibilityController));
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
        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);
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

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);
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

    @Test
    public void testCalculateTransferTimeMs() {
        assertEquals(ConnectivityController.UNKNOWN_TIME,
                ConnectivityController.calculateTransferTimeMs(1, 0));
        assertEquals(ConnectivityController.UNKNOWN_TIME,
                ConnectivityController.calculateTransferTimeMs(JobInfo.NETWORK_BYTES_UNKNOWN, 512));
        assertEquals(1, ConnectivityController.calculateTransferTimeMs(1, 8));
        assertEquals(1000, ConnectivityController.calculateTransferTimeMs(1000, 8));
        assertEquals(8, ConnectivityController.calculateTransferTimeMs(1024, 1024));
    }

    @Test
    public void testGetEstimatedTransferTimeMs() {
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        doNothing().when(mConnManager).registerNetworkCallback(any(), callbackCaptor.capture());

        final JobStatus job = createJobStatus(createJob()
                .setEstimatedNetworkBytes(DataUnit.MEBIBYTES.toBytes(10_000),
                        DataUnit.MEBIBYTES.toBytes(1_000))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY));

        final ConnectivityController controller = new ConnectivityController(mService,
                mFlexibilityController);

        final JobStatus jobNoEstimates = createJobStatus(createJob());
        assertEquals(ConnectivityController.UNKNOWN_TIME,
                controller.getEstimatedTransferTimeMs(jobNoEstimates));

        // No network
        job.network = null;
        assertEquals(ConnectivityController.UNKNOWN_TIME,
                controller.getEstimatedTransferTimeMs(job));

        final NetworkCallback generalCallback = callbackCaptor.getValue();

        // No capabilities
        final Network network = mock(Network.class);
        answerNetwork(generalCallback, null, null, network, null);
        job.network = network;
        assertEquals(ConnectivityController.UNKNOWN_TIME,
                controller.getEstimatedTransferTimeMs(job));

        // Capabilities don't have bandwidth values
        NetworkCapabilities caps = createCapabilitiesBuilder().build();
        answerNetwork(generalCallback, null, null, network, caps);
        assertEquals(ConnectivityController.UNKNOWN_TIME,
                controller.getEstimatedTransferTimeMs(job));

        // Capabilities only has downstream bandwidth
        caps = createCapabilitiesBuilder()
                .setLinkDownstreamBandwidthKbps(1024)
                .build();
        answerNetwork(generalCallback, null, null, network, caps);
        assertEquals(81920 * SECOND_IN_MILLIS, controller.getEstimatedTransferTimeMs(job));

        // Capabilities only has upstream bandwidth
        caps = createCapabilitiesBuilder()
                .setLinkUpstreamBandwidthKbps(2 * 1024)
                .build();
        answerNetwork(generalCallback, null, null, network, caps);
        assertEquals(4096 * SECOND_IN_MILLIS, controller.getEstimatedTransferTimeMs(job));

        // Capabilities only both stream bandwidths
        caps = createCapabilitiesBuilder()
                .setLinkDownstreamBandwidthKbps(1024)
                .setLinkUpstreamBandwidthKbps(2 * 1024)
                .build();
        answerNetwork(generalCallback, null, null, network, caps);
        assertEquals((81920 + 4096) * SECOND_IN_MILLIS, controller.getEstimatedTransferTimeMs(job));
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
            if (caps != null) {
                generalCallback.onCapabilitiesChanged(net, caps);
            }
            if (uidCallback != null) {
                uidCallback.onAvailable(net);
                uidCallback.onBlockedStatusChanged(net, ConnectivityManager.BLOCKED_REASON_NONE);
                if (caps != null) {
                    uidCallback.onCapabilitiesChanged(net, caps);
                }
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
        return new JobStatus(job.build(), uid, null, -1, 0, null, null,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis, 0, 0, 0, null, 0, 0);
    }

    private void setDeviceConfigBoolean(ConnectivityController connectivityController,
            String key, boolean val) {
        mDeviceConfigPropertiesBuilder.setBoolean(key, val);
        synchronized (connectivityController.mLock) {
            connectivityController.prepareForUpdatedConstantsLocked();
            mFlexibilityController.prepareForUpdatedConstantsLocked();
            connectivityController.getCcConfig()
                    .processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            mFlexibilityController.getFcConfig()
                    .processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
            connectivityController.onConstantsUpdatedLocked();
            mFlexibilityController.onConstantsUpdatedLocked();
        }
        waitForNonDelayedMessagesProcessed();
    }

    private void waitForNonDelayedMessagesProcessed() {
        AppSchedulingModuleThread.getHandler().runWithScissors(() -> {}, 15_000);
    }
}
