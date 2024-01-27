/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.provider;

import static com.android.server.location.LocationUtils.createLocation;
import static com.android.server.location.LocationUtils.createLocationResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.location.Location;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.location.provider.ProviderRequest;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.location.injector.TestInjector;
import com.android.server.location.test.FakeProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Random;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StationaryThrottlingLocationProviderTest {

    private static final String TAG = "StationaryThrottlingLocationProviderTest";

    private Random mRandom;
    private TestInjector mInjector;
    private FakeProvider mDelegateProvider;

    private @Mock Context mContext;
    private @Mock AbstractLocationProvider.Listener mListener;
    private @Mock FakeProvider.FakeProviderInterface mDelegate;

    private StationaryThrottlingLocationProvider mProvider;

    @Before
    public void setUp() {
        initMocks(this);

        long seed = System.currentTimeMillis();
        Log.i(TAG, "location random seed: " + seed);

        mRandom = new Random(seed);

        mInjector = new TestInjector(mContext);
        mDelegateProvider = new FakeProvider(mDelegate);

        mProvider = new StationaryThrottlingLocationProvider("test_provider", mInjector,
                mDelegateProvider);
        mProvider.getController().setListener(mListener);
        mProvider.getController().start();
    }

    @After
    public void tearDown() {
        mProvider.getController().setRequest(ProviderRequest.EMPTY_REQUEST);
        mProvider.getController().stop();
    }

    @Test
    public void testThrottle_lowInterval() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(0).build();

        mProvider.getController().setRequest(request);
        mDelegateProvider.reportLocation(createLocationResult("test_provider", mRandom));
        verify(mListener, times(1)).onReportLocation(any(LocationResult.class));

        mInjector.getDeviceStationaryHelper().setStationary(true);
        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mListener, after(1500).times(2)).onReportLocation(any(LocationResult.class));
    }

    @Test
    public void testThrottle_stationaryExit() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(50).build();

        mProvider.getController().setRequest(request);
        verify(mDelegate).onSetRequest(request);

        mDelegateProvider.reportLocation(createLocationResult("test_provider", mRandom));
        verify(mListener, times(1)).onReportLocation(any(LocationResult.class));

        mInjector.getDeviceStationaryHelper().setStationary(true);
        verify(mDelegate, never()).onSetRequest(ProviderRequest.EMPTY_REQUEST);

        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mDelegate).onSetRequest(ProviderRequest.EMPTY_REQUEST);
        verify(mListener, timeout(1100).times(2)).onReportLocation(any(LocationResult.class));

        mInjector.getDeviceStationaryHelper().setStationary(false);
        verify(mDelegate, times(2)).onSetRequest(request);
        verify(mListener, after(1000).times(2)).onReportLocation(any(LocationResult.class));
    }

    @Test
    public void testThrottle_idleExit() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(1000).build();

        mProvider.getController().setRequest(request);
        verify(mDelegate).onSetRequest(request);

        mDelegateProvider.reportLocation(createLocationResult("test_provider", mRandom));
        verify(mListener, times(1)).onReportLocation(any(LocationResult.class));

        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mDelegate, never()).onSetRequest(ProviderRequest.EMPTY_REQUEST);

        mInjector.getDeviceStationaryHelper().setStationary(true);
        verify(mDelegate).onSetRequest(ProviderRequest.EMPTY_REQUEST);
        verify(mListener, timeout(1100).times(2)).onReportLocation(any(LocationResult.class));

        mInjector.getDeviceIdleHelper().setIdle(false);
        verify(mDelegate, times(2)).onSetRequest(request);
        verify(mListener, after(1000).times(2)).onReportLocation(any(LocationResult.class));
    }

    @Test
    public void testThrottle_NoInitialLocation() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(1000).build();

        mProvider.getController().setRequest(request);
        verify(mDelegate).onSetRequest(request);

        mInjector.getDeviceStationaryHelper().setStationary(true);
        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mDelegate, never()).onSetRequest(ProviderRequest.EMPTY_REQUEST);

        mDelegateProvider.reportLocation(createLocationResult("test_provider", mRandom));
        verify(mListener, times(1)).onReportLocation(any(LocationResult.class));
        verify(mDelegate, times(1)).onSetRequest(ProviderRequest.EMPTY_REQUEST);
        verify(mListener, timeout(1100).times(2)).onReportLocation(any(LocationResult.class));

        mInjector.getDeviceStationaryHelper().setStationary(false);
        verify(mDelegate, times(2)).onSetRequest(request);
        verify(mListener, after(1000).times(2)).onReportLocation(any(LocationResult.class));
    }

    @Test
    public void testNoThrottle_noLocation() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(50).build();

        mProvider.getController().setRequest(request);
        verify(mDelegate).onSetRequest(request);
        verify(mListener, never()).onReportLocation(any(LocationResult.class));

        mInjector.getDeviceStationaryHelper().setStationary(true);
        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mDelegate, never()).onSetRequest(ProviderRequest.EMPTY_REQUEST);
        verify(mListener, after(75).times(0)).onReportLocation(any(LocationResult.class));
    }

    @Test
    public void testNoThrottle_oldLocation() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(50).build();

        mProvider.getController().setRequest(request);
        verify(mDelegate).onSetRequest(request);

        Location l = createLocation("test_provider", mRandom);
        l.setElapsedRealtimeNanos(0);

        LocationResult loc = LocationResult.wrap(l);
        mDelegateProvider.reportLocation(loc);
        verify(mListener, times(1)).onReportLocation(loc);

        mInjector.getDeviceStationaryHelper().setStationary(true);
        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mDelegate, never()).onSetRequest(ProviderRequest.EMPTY_REQUEST);
        verify(mListener, after(75).times(1)).onReportLocation(any(LocationResult.class));
    }

    @Test
    public void testNoThrottle_locationSettingsIgnored() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(
                50).setLocationSettingsIgnored(true).build();

        mProvider.getController().setRequest(request);
        verify(mDelegate).onSetRequest(request);

        LocationResult loc = createLocationResult("test_provider", mRandom);
        mDelegateProvider.reportLocation(loc);
        verify(mListener, times(1)).onReportLocation(loc);

        mInjector.getDeviceStationaryHelper().setStationary(true);
        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mDelegate, never()).onSetRequest(ProviderRequest.EMPTY_REQUEST);
        verify(mListener, after(75).times(1)).onReportLocation(any(LocationResult.class));
    }

    @Test
    public void testNoThrottle_highAccuracy() {
        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(
                50).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();

        mProvider.getController().setRequest(request);
        verify(mDelegate).onSetRequest(request);

        LocationResult loc = createLocationResult("test_provider", mRandom);
        mDelegateProvider.reportLocation(loc);
        verify(mListener, times(1)).onReportLocation(loc);

        mInjector.getDeviceStationaryHelper().setStationary(true);
        mInjector.getDeviceIdleHelper().setIdle(true);
        verify(mDelegate, never()).onSetRequest(ProviderRequest.EMPTY_REQUEST);
        verify(mListener, after(75).times(1)).onReportLocation(any(LocationResult.class));
    }
}
