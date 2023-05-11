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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.location.GnssMeasurementRequest;
import android.location.IGnssMeasurementsListener;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.location.gnss.hal.FakeGnssHal;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.FakeUserInfoHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.TestInjector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Objects;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GnssMeasurementsProviderTest {
    private static final int CURRENT_USER = FakeUserInfoHelper.DEFAULT_USERID;
    private static final CallerIdentity IDENTITY = CallerIdentity.forTest(CURRENT_USER, 1000,
            "mypackage", "attribution", "listener");
    private static final GnssConfiguration.HalInterfaceVersion HIDL_V2_1 =
            new GnssConfiguration.HalInterfaceVersion(
                    2, 1);
    private static final GnssConfiguration.HalInterfaceVersion AIDL_V3 =
            new GnssConfiguration.HalInterfaceVersion(
                    GnssConfiguration.HalInterfaceVersion.AIDL_INTERFACE, 3);
    private static final GnssMeasurementRequest ACTIVE_REQUEST =
            new GnssMeasurementRequest.Builder().build();
    private static final GnssMeasurementRequest PASSIVE_REQUEST =
            new GnssMeasurementRequest.Builder().setIntervalMillis(
                    GnssMeasurementRequest.PASSIVE_INTERVAL).build();
    private @Mock Context mContext;
    private @Mock LocationManagerInternal mInternal;
    private @Mock GnssConfiguration mMockConfiguration;
    private @Mock IGnssMeasurementsListener mListener1;
    private @Mock IGnssMeasurementsListener mListener2;
    private @Mock IBinder mBinder1;
    private @Mock IBinder mBinder2;

    private GnssNative mGnssNative;

    private GnssMeasurementsProvider mTestProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(mBinder1).when(mListener1).asBinder();
        doReturn(mBinder2).when(mListener2).asBinder();
        doReturn(true).when(mInternal).isProviderEnabledForUser(eq(LocationManager.GPS_PROVIDER),
                anyInt());
        LocalServices.addService(LocationManagerInternal.class, mInternal);
        FakeGnssHal fakeGnssHal = new FakeGnssHal();
        GnssNative.setGnssHalForTest(fakeGnssHal);
        Injector injector = new TestInjector(mContext);
        mGnssNative = spy(Objects.requireNonNull(
                GnssNative.create(injector, mMockConfiguration)));
        mTestProvider = new GnssMeasurementsProvider(injector, mGnssNative);
        mGnssNative.register();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(LocationManagerInternal.class);
    }

    @Test
    public void testAddListener_active() {
        // add the active request
        mTestProvider.addListener(ACTIVE_REQUEST, IDENTITY, mListener1);
        verify(mGnssNative, times(1)).startMeasurementCollection(
                eq(ACTIVE_REQUEST.isFullTracking()),
                eq(ACTIVE_REQUEST.isCorrelationVectorOutputsEnabled()),
                eq(ACTIVE_REQUEST.getIntervalMillis()));

        // remove the active request
        mTestProvider.removeListener(mListener1);
        verify(mGnssNative, times(1)).stopMeasurementCollection();
    }

    @Test
    public void testAddListener_passive() {
        // add the passive request
        mTestProvider.addListener(PASSIVE_REQUEST, IDENTITY, mListener1);
        verify(mGnssNative, never()).startMeasurementCollection(anyBoolean(), anyBoolean(),
                anyInt());

        // remove the passive request
        mTestProvider.removeListener(mListener1);
        verify(mGnssNative, times(1)).stopMeasurementCollection();
    }

    @Test
    public void testReregister_aidlV3Plus() {
        doReturn(AIDL_V3).when(mMockConfiguration).getHalInterfaceVersion();

        // add the passive request
        mTestProvider.addListener(PASSIVE_REQUEST, IDENTITY, mListener1);
        verify(mGnssNative, never()).startMeasurementCollection(anyBoolean(), anyBoolean(),
                anyInt());

        // add the active request, reregister with the active request
        mTestProvider.addListener(ACTIVE_REQUEST, IDENTITY, mListener2);
        verify(mGnssNative, never()).stopMeasurementCollection();
        verify(mGnssNative, times(1)).startMeasurementCollection(
                eq(ACTIVE_REQUEST.isFullTracking()),
                eq(ACTIVE_REQUEST.isCorrelationVectorOutputsEnabled()),
                eq(ACTIVE_REQUEST.getIntervalMillis()));

        // remove the active request, reregister with the passive request
        mTestProvider.removeListener(mListener2);
        verify(mGnssNative, times(1)).stopMeasurementCollection();

        // remove the passive request
        mTestProvider.removeListener(mListener1);
        verify(mGnssNative, times(2)).stopMeasurementCollection();
    }

    @Test
    public void testReregister_preAidlV3() {
        doReturn(HIDL_V2_1).when(mMockConfiguration).getHalInterfaceVersion();

        // add the passive request
        mTestProvider.addListener(PASSIVE_REQUEST, IDENTITY, mListener1);
        verify(mGnssNative, never()).startMeasurementCollection(anyBoolean(), anyBoolean(),
                anyInt());

        // add the active request, reregister with the active request
        mTestProvider.addListener(ACTIVE_REQUEST, IDENTITY, mListener2);
        verify(mGnssNative, times(1)).stopMeasurementCollection();
        verify(mGnssNative, times(1)).startMeasurementCollection(
                eq(ACTIVE_REQUEST.isFullTracking()),
                eq(ACTIVE_REQUEST.isCorrelationVectorOutputsEnabled()),
                eq(ACTIVE_REQUEST.getIntervalMillis()));

        // remove the active request, reregister with the passive request
        mTestProvider.removeListener(mListener2);
        verify(mGnssNative, times(2)).stopMeasurementCollection();

        // remove the passive request
        mTestProvider.removeListener(mListener1);
        verify(mGnssNative, times(3)).stopMeasurementCollection();
    }
}
