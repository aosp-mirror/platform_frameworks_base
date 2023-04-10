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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.location.gnss.hal.FakeGnssHal;
import com.android.server.location.gnss.hal.GnssNative;
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
public class GnssAntennaInfoProviderTest {
    private @Mock Context mContext;
    private @Mock LocationManagerInternal mInternal;
    private @Mock GnssConfiguration mMockConfiguration;
    private @Mock IBinder mBinder;
    private GnssNative mGnssNative;

    private GnssAntennaInfoProvider mTestProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(true).when(mInternal).isProviderEnabledForUser(eq(LocationManager.GPS_PROVIDER),
                anyInt());
        LocalServices.addService(LocationManagerInternal.class, mInternal);
        FakeGnssHal fakeGnssHal = new FakeGnssHal();
        GnssNative.setGnssHalForTest(fakeGnssHal);
        Injector injector = new TestInjector(mContext);
        mGnssNative = spy(Objects.requireNonNull(GnssNative.create(injector, mMockConfiguration)));
        mTestProvider = new GnssAntennaInfoProvider(mGnssNative);
        mGnssNative.register();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(LocationManagerInternal.class);
    }

    @Test
    public void testOnHalStarted() {
        verify(mGnssNative, times(1)).startAntennaInfoListening();
    }

    @Test
    public void testOnHalRestarted() {
        mTestProvider.onHalRestarted();
        verify(mGnssNative, times(2)).startAntennaInfoListening();
    }
}
