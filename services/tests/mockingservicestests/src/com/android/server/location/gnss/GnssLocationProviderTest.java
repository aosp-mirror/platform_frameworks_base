/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.location.gnss;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.location.GnssCapabilities;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.flags.Flags;
import android.location.provider.ProviderRequest;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.LocalServices;
import com.android.server.location.gnss.hal.FakeGnssHal;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.TestInjector;
import com.android.server.timedetector.TimeDetectorInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Presubmit
@androidx.test.filters.SmallTest
@RunWith(AndroidJUnit4.class)
public class GnssLocationProviderTest {

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .setStrictness(Strictness.WARN)
            .mockStatic(Settings.Global.class)
            .build();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private @Mock Context mContext;
    private @Mock LocationManagerInternal mLocationManagerInternal;
    private @Mock LocationManager mLocationManager;
    private @Mock TimeDetectorInternal mTimeDetectorInternal;
    private @Mock GnssConfiguration mMockConfiguration;
    private @Mock GnssMetrics mGnssMetrics;
    private @Mock PowerManager mPowerManager;
    private @Mock TelephonyManager mTelephonyManager;
    private @Mock AppOpsManager mAppOpsManager;
    private @Mock AlarmManager mAlarmManager;
    private @Mock PowerManager.WakeLock mWakeLock;
    private @Mock ContentResolver mContentResolver;
    private @Mock UserManager mUserManager;
    private @Mock UserHandle mUserHandle;
    private Set<UserHandle> mUserHandleSet = new HashSet<>();

    private GnssNative mGnssNative;

    private GnssLocationProvider mTestProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn("mypackage").when(mContext).getPackageName();
        doReturn("attribution").when(mContext).getAttributionTag();
        doReturn(mPowerManager).when(mContext).getSystemService(PowerManager.class);
        doReturn(mPowerManager).when(mContext).getSystemService("power");
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
        doReturn(mTelephonyManager).when(mContext).getSystemService(Context.TELEPHONY_SERVICE);
        doReturn(mAlarmManager).when(mContext).getSystemService(Context.ALARM_SERVICE);
        doReturn(mLocationManager).when(mContext).getSystemService(LocationManager.class);
        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        mUserHandleSet.add(mUserHandle);
        doReturn(true).when(mLocationManager).isLocationEnabledForUser(eq(mUserHandle));
        doReturn(mUserHandleSet).when(mUserManager).getVisibleUsers();
        doReturn(mContentResolver).when(mContext).getContentResolver();
        doReturn(mWakeLock).when(mPowerManager).newWakeLock(anyInt(), anyString());
        LocalServices.addService(LocationManagerInternal.class, mLocationManagerInternal);
        LocalServices.addService(TimeDetectorInternal.class, mTimeDetectorInternal);
        FakeGnssHal fakeGnssHal = new FakeGnssHal();
        GnssNative.setGnssHalForTest(fakeGnssHal);
        Injector injector = new TestInjector(mContext);
        mGnssNative = spy(Objects.requireNonNull(
                GnssNative.create(injector, mMockConfiguration)));
        doReturn(true).when(mGnssNative).init();
        GnssCapabilities gnssCapabilities = new GnssCapabilities.Builder().setHasScheduling(
                true).build();
        doReturn(gnssCapabilities).when(mGnssNative).getCapabilities();

        mTestProvider = new GnssLocationProvider(mContext, mGnssNative, mGnssMetrics);
        mGnssNative.register();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(LocationManagerInternal.class);
        LocalServices.removeServiceForTest(TimeDetectorInternal.class);
    }

    @Test
    public void testStartNavigating() {
        ProviderRequest providerRequest = new ProviderRequest.Builder().setIntervalMillis(
                0).build();

        mTestProvider.onSetRequest(providerRequest);
        verify(mGnssNative).start();
    }

    @Test
    public void testUpdateRequirements_sameRequest() {
        mSetFlagsRule.enableFlags(Flags.FLAG_GNSS_CALL_STOP_BEFORE_SET_POSITION_MODE);
        ProviderRequest providerRequest = new ProviderRequest.Builder().setIntervalMillis(
                0).build();

        mTestProvider.onSetRequest(providerRequest);
        verify(mGnssNative).start();

        // set the same request
        mTestProvider.onSetRequest(providerRequest);
        verify(mGnssNative, never()).stop();
        verify(mGnssNative, times(1)).start();
    }

    @Test
    public void testUpdateRequirements_differentRequest() {
        mSetFlagsRule.enableFlags(Flags.FLAG_GNSS_CALL_STOP_BEFORE_SET_POSITION_MODE);
        ProviderRequest providerRequest = new ProviderRequest.Builder().setIntervalMillis(
                0).build();

        mTestProvider.onSetRequest(providerRequest);
        verify(mGnssNative).start();

        // set a different request
        providerRequest = new ProviderRequest.Builder().setIntervalMillis(2000).build();
        mTestProvider.onSetRequest(providerRequest);
        verify(mGnssNative).stop();
        verify(mGnssNative, times(2)).start();
    }
}
