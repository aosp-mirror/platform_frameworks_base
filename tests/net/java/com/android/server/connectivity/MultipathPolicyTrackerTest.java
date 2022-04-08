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

package com.android.server.connectivity;

import static android.content.Intent.ACTION_CONFIGURATION_CHANGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.provider.Settings.Global.NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES;

import static com.android.server.net.NetworkPolicyManagerInternal.QUOTA_TYPE_MULTIPATH;
import static com.android.server.net.NetworkPolicyManagerService.OPPORTUNISTIC_QUOTA_UNKNOWN;

import static junit.framework.TestCase.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkTemplate;
import android.net.StringNetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.util.DataUnit;
import android.util.RecurrenceRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.NetworkStatsManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MultipathPolicyTrackerTest {
    private static final Network TEST_NETWORK = new Network(123);
    private static final int POLICY_SNOOZED = -100;

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private Handler mHandler;
    @Mock private MultipathPolicyTracker.Dependencies mDeps;
    @Mock private Clock mClock;
    @Mock private ConnectivityManager mCM;
    @Mock private NetworkPolicyManager mNPM;
    @Mock private NetworkStatsManager mStatsManager;
    @Mock private NetworkPolicyManagerInternal mNPMI;
    @Mock private NetworkStatsManagerInternal mNetworkStatsManagerInternal;
    @Mock private TelephonyManager mTelephonyManager;
    private MockContentResolver mContentResolver;

    private ArgumentCaptor<BroadcastReceiver> mConfigChangeReceiverCaptor;

    private MultipathPolicyTracker mTracker;

    private Clock mPreviousRecurrenceRuleClock;
    private boolean mRecurrenceRuleClockMocked;

    private <T> void mockService(String serviceName, Class<T> serviceClass, T service) {
        when(mContext.getSystemServiceName(serviceClass)).thenReturn(serviceName);
        when(mContext.getSystemService(serviceName)).thenReturn(service);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mPreviousRecurrenceRuleClock = RecurrenceRule.sClock;
        RecurrenceRule.sClock = mClock;
        mRecurrenceRuleClockMocked = true;

        mConfigChangeReceiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver.class);

        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
        when(mContext.registerReceiverAsUser(mConfigChangeReceiverCaptor.capture(),
                any(), argThat(f -> f.hasAction(ACTION_CONFIGURATION_CHANGED)), any(), any()))
                .thenReturn(null);

        when(mDeps.getClock()).thenReturn(mClock);

        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);

        mContentResolver = Mockito.spy(new MockContentResolver(mContext));
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        Settings.Global.clearProviderForTest();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        mockService(Context.CONNECTIVITY_SERVICE, ConnectivityManager.class, mCM);
        mockService(Context.NETWORK_POLICY_SERVICE, NetworkPolicyManager.class, mNPM);
        mockService(Context.NETWORK_STATS_SERVICE, NetworkStatsManager.class, mStatsManager);
        mockService(Context.TELEPHONY_SERVICE, TelephonyManager.class, mTelephonyManager);

        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
        LocalServices.addService(NetworkPolicyManagerInternal.class, mNPMI);

        LocalServices.removeServiceForTest(NetworkStatsManagerInternal.class);
        LocalServices.addService(NetworkStatsManagerInternal.class, mNetworkStatsManagerInternal);

        mTracker = new MultipathPolicyTracker(mContext, mHandler, mDeps);
    }

    @After
    public void tearDown() {
        // Avoid setting static clock to null (which should normally not be the case)
        // if MockitoAnnotations.initMocks threw an exception
        if (mRecurrenceRuleClockMocked) {
            RecurrenceRule.sClock = mPreviousRecurrenceRuleClock;
        }
        mRecurrenceRuleClockMocked = false;
    }

    private void setDefaultQuotaGlobalSetting(long setting) {
        Settings.Global.putInt(mContentResolver, NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES,
                (int) setting);
    }

    private void testGetMultipathPreference(
            long usedBytesToday, long subscriptionQuota, long policyWarning, long policyLimit,
            long defaultGlobalSetting, long defaultResSetting, boolean roaming) {

        // TODO: tests should not use ZoneId.systemDefault() once code handles TZ correctly.
        final ZonedDateTime now = ZonedDateTime.ofInstant(
                Instant.parse("2017-04-02T10:11:12Z"), ZoneId.systemDefault());
        final ZonedDateTime startOfDay = now.truncatedTo(ChronoUnit.DAYS);
        when(mClock.millis()).thenReturn(now.toInstant().toEpochMilli());
        when(mClock.instant()).thenReturn(now.toInstant());
        when(mClock.getZone()).thenReturn(ZoneId.systemDefault());

        // Setup plan quota
        when(mNPMI.getSubscriptionOpportunisticQuota(TEST_NETWORK, QUOTA_TYPE_MULTIPATH))
                .thenReturn(subscriptionQuota);

        // Setup user policy warning / limit
        if (policyWarning != WARNING_DISABLED || policyLimit != LIMIT_DISABLED) {
            final Instant recurrenceStart = Instant.parse("2017-04-01T00:00:00Z");
            final RecurrenceRule recurrenceRule = new RecurrenceRule(
                    ZonedDateTime.ofInstant(
                            recurrenceStart,
                            ZoneId.systemDefault()),
                    null /* end */,
                    Period.ofMonths(1));
            final boolean snoozeWarning = policyWarning == POLICY_SNOOZED;
            final boolean snoozeLimit = policyLimit == POLICY_SNOOZED;
            when(mNPM.getNetworkPolicies()).thenReturn(new NetworkPolicy[] {
                    new NetworkPolicy(
                            NetworkTemplate.buildTemplateMobileWildcard(),
                            recurrenceRule,
                            snoozeWarning ? 0 : policyWarning,
                            snoozeLimit ? 0 : policyLimit,
                            snoozeWarning ? recurrenceStart.toEpochMilli() + 1 : SNOOZE_NEVER,
                            snoozeLimit ? recurrenceStart.toEpochMilli() + 1 : SNOOZE_NEVER,
                            SNOOZE_NEVER,
                            true /* metered */,
                            false /* inferred */)
            });
        } else {
            when(mNPM.getNetworkPolicies()).thenReturn(new NetworkPolicy[0]);
        }

        // Setup default quota in settings and resources
        if (defaultGlobalSetting > 0) {
            setDefaultQuotaGlobalSetting(defaultGlobalSetting);
        }
        when(mResources.getInteger(R.integer.config_networkDefaultDailyMultipathQuotaBytes))
                .thenReturn((int) defaultResSetting);

        when(mNetworkStatsManagerInternal.getNetworkTotalBytes(
                any(),
                eq(startOfDay.toInstant().toEpochMilli()),
                eq(now.toInstant().toEpochMilli()))).thenReturn(usedBytesToday);

        ArgumentCaptor<ConnectivityManager.NetworkCallback> networkCallback =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        mTracker.start();
        verify(mCM).registerNetworkCallback(any(), networkCallback.capture(), any());

        // Simulate callback after capability changes
        NetworkCapabilities capabilities = new NetworkCapabilities()
                .addCapability(NET_CAPABILITY_INTERNET)
                .addTransportType(TRANSPORT_CELLULAR)
                .setNetworkSpecifier(new StringNetworkSpecifier("234"));
        if (!roaming) {
            capabilities.addCapability(NET_CAPABILITY_NOT_ROAMING);
        }
        networkCallback.getValue().onCapabilitiesChanged(
                TEST_NETWORK,
                capabilities);

        // make sure it also works with the new introduced  TelephonyNetworkSpecifier
        capabilities = new NetworkCapabilities()
                .addCapability(NET_CAPABILITY_INTERNET)
                .addTransportType(TRANSPORT_CELLULAR)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier.Builder()
                        .setSubscriptionId(234).build());
        if (!roaming) {
            capabilities.addCapability(NET_CAPABILITY_NOT_ROAMING);
        }
        networkCallback.getValue().onCapabilitiesChanged(
                TEST_NETWORK,
                capabilities);
    }

    @Test
    public void testGetMultipathPreference_SubscriptionQuota() {
        testGetMultipathPreference(
                DataUnit.MEGABYTES.toBytes(2) /* usedBytesToday */,
                DataUnit.MEGABYTES.toBytes(14) /* subscriptionQuota */,
                DataUnit.MEGABYTES.toBytes(100) /* policyWarning */,
                LIMIT_DISABLED,
                DataUnit.MEGABYTES.toBytes(12) /* defaultGlobalSetting */,
                2_500_000 /* defaultResSetting */,
                false /* roaming */);

        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(12)), any(), any());
    }

    @Test
    public void testGetMultipathPreference_UserWarningQuota() {
        testGetMultipathPreference(
                DataUnit.MEGABYTES.toBytes(7) /* usedBytesToday */,
                OPPORTUNISTIC_QUOTA_UNKNOWN,
                // 29 days from Apr. 2nd to May 1st
                DataUnit.MEGABYTES.toBytes(15 * 29 * 20) /* policyWarning */,
                LIMIT_DISABLED,
                DataUnit.MEGABYTES.toBytes(12) /* defaultGlobalSetting */,
                2_500_000 /* defaultResSetting */,
                false /* roaming */);

        // Daily budget should be 15MB (5% of daily quota), 7MB used today: callback set for 8MB
        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(8)), any(), any());
    }

    @Test
    public void testGetMultipathPreference_SnoozedWarningQuota() {
        testGetMultipathPreference(
                DataUnit.MEGABYTES.toBytes(7) /* usedBytesToday */,
                OPPORTUNISTIC_QUOTA_UNKNOWN,
                // 29 days from Apr. 2nd to May 1st
                POLICY_SNOOZED /* policyWarning */,
                DataUnit.MEGABYTES.toBytes(15 * 29 * 20) /* policyLimit */,
                DataUnit.MEGABYTES.toBytes(12) /* defaultGlobalSetting */,
                2_500_000 /* defaultResSetting */,
                false /* roaming */);

        // Daily budget should be 15MB (5% of daily quota), 7MB used today: callback set for 8MB
        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(8)), any(), any());
    }

    @Test
    public void testGetMultipathPreference_SnoozedBothQuota() {
        testGetMultipathPreference(
                DataUnit.MEGABYTES.toBytes(7) /* usedBytesToday */,
                OPPORTUNISTIC_QUOTA_UNKNOWN,
                // 29 days from Apr. 2nd to May 1st
                POLICY_SNOOZED /* policyWarning */,
                POLICY_SNOOZED /* policyLimit */,
                DataUnit.MEGABYTES.toBytes(12) /* defaultGlobalSetting */,
                2_500_000 /* defaultResSetting */,
                false /* roaming */);

        // Default global setting should be used: 12 - 7 = 5
        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(5)), any(), any());
    }

    @Test
    public void testGetMultipathPreference_SettingChanged() {
        testGetMultipathPreference(
                DataUnit.MEGABYTES.toBytes(2) /* usedBytesToday */,
                OPPORTUNISTIC_QUOTA_UNKNOWN,
                WARNING_DISABLED,
                LIMIT_DISABLED,
                -1 /* defaultGlobalSetting */,
                DataUnit.MEGABYTES.toBytes(10) /* defaultResSetting */,
                false /* roaming */);

        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(8)), any(), any());

        // Update setting
        setDefaultQuotaGlobalSetting(DataUnit.MEGABYTES.toBytes(14));
        mTracker.mSettingsObserver.onChange(
                false, Settings.Global.getUriFor(NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES));

        // Callback must have been re-registered with new setting
        verify(mStatsManager, times(1)).unregisterUsageCallback(any());
        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(12)), any(), any());
    }

    @Test
    public void testGetMultipathPreference_ResourceChanged() {
        testGetMultipathPreference(
                DataUnit.MEGABYTES.toBytes(2) /* usedBytesToday */,
                OPPORTUNISTIC_QUOTA_UNKNOWN,
                WARNING_DISABLED,
                LIMIT_DISABLED,
                -1 /* defaultGlobalSetting */,
                DataUnit.MEGABYTES.toBytes(14) /* defaultResSetting */,
                false /* roaming */);

        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(12)), any(), any());

        when(mResources.getInteger(R.integer.config_networkDefaultDailyMultipathQuotaBytes))
                .thenReturn((int) DataUnit.MEGABYTES.toBytes(16));

        final BroadcastReceiver configChangeReceiver = mConfigChangeReceiverCaptor.getValue();
        assertNotNull(configChangeReceiver);
        configChangeReceiver.onReceive(mContext, new Intent());

        // Uses the new setting (16 - 2 = 14MB)
        verify(mStatsManager, times(1)).registerUsageCallback(
                any(), anyInt(), eq(DataUnit.MEGABYTES.toBytes(14)), any(), any());
    }
}
