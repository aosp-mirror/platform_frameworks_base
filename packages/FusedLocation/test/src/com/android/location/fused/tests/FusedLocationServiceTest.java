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

package com.android.location.fused.tests;

import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ILocationProviderManager;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.location.fused.FusedLocationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class FusedLocationServiceTest {

    private static final String TAG = "FusedLocationServiceTest";

    private static final long TIMEOUT_MS = 5000;

    private Context mContext;
    private Random mRandom;
    private LocationManager mLocationManager;

    private ILocationProvider mProvider;
    private LocationProviderManagerCapture mManager;

    @Before
    public void setUp() throws Exception {
        long seed = System.currentTimeMillis();
        Log.i(TAG, "location seed: " + seed);

        mContext = InstrumentationRegistry.getTargetContext();
        mRandom = new Random(seed);
        mLocationManager = mContext.getSystemService(LocationManager.class);

        setMockLocation(true);

        mManager = new LocationProviderManagerCapture();
        mProvider = ILocationProvider.Stub.asInterface(
                new FusedLocationProvider(mContext).getBinder());
        mProvider.setLocationProviderManager(mManager);

        mLocationManager.addTestProvider(NETWORK_PROVIDER,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                Criteria.POWER_MEDIUM,
                Criteria.ACCURACY_FINE);
        mLocationManager.setTestProviderEnabled(NETWORK_PROVIDER, true);
        mLocationManager.addTestProvider(GPS_PROVIDER,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                Criteria.POWER_MEDIUM,
                Criteria.ACCURACY_FINE);
        mLocationManager.setTestProviderEnabled(GPS_PROVIDER, true);
    }

    @After
    public void tearDown() throws Exception {
        for (String provider : mLocationManager.getAllProviders()) {
            mLocationManager.removeTestProvider(provider);
        }

        setMockLocation(false);
    }

    @Test
    public void testNetworkRequest() throws Exception {
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(FUSED_PROVIDER, 1000,
                0, false);

        mProvider.setRequest(
                        new ProviderRequest.Builder()
                                .setInterval(1000)
                                .setLocationRequests(Collections.singletonList(request))
                                .build(),
                new WorkSource());

        Location location = createLocation(NETWORK_PROVIDER, mRandom);
        mLocationManager.setTestProviderLocation(NETWORK_PROVIDER, location);

        assertThat(mManager.getNextLocation(TIMEOUT_MS)).isEqualTo(location);
    }

    @Test
    public void testGpsRequest() throws Exception {
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(FUSED_PROVIDER, 1000,
                0, false).setQuality(LocationRequest.POWER_HIGH);

        mProvider.setRequest(
                new ProviderRequest.Builder()
                        .setInterval(1000)
                        .setLocationRequests(Collections.singletonList(request))
                        .build(),
                new WorkSource());

        Location location = createLocation(GPS_PROVIDER, mRandom);
        mLocationManager.setTestProviderLocation(GPS_PROVIDER, location);

        assertThat(mManager.getNextLocation(TIMEOUT_MS)).isEqualTo(location);
    }

    @Test
    public void testBypassRequest() throws Exception {
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(FUSED_PROVIDER, 1000,
                0, false).setQuality(LocationRequest.POWER_HIGH).setLocationSettingsIgnored(true);

        mProvider.setRequest(
                new ProviderRequest.Builder()
                        .setInterval(1000)
                        .setLocationSettingsIgnored(true)
                        .setLocationRequests(Collections.singletonList(request))
                        .build(),
                new WorkSource());

        boolean containsNetworkBypass = false;
        for (LocationRequest iRequest : mLocationManager.getTestProviderCurrentRequests(
                NETWORK_PROVIDER)) {
            if (iRequest.isLocationSettingsIgnored()) {
                containsNetworkBypass = true;
                break;
            }
        }

        boolean containsGpsBypass = false;
        for (LocationRequest iRequest : mLocationManager.getTestProviderCurrentRequests(
                GPS_PROVIDER)) {
            if (iRequest.isLocationSettingsIgnored()) {
                containsGpsBypass = true;
                break;
            }
        }

        assertThat(containsNetworkBypass).isTrue();
        assertThat(containsGpsBypass).isTrue();
    }

    private static class LocationProviderManagerCapture extends ILocationProviderManager.Stub {

        private final LinkedBlockingQueue<Location> mLocations;

        private LocationProviderManagerCapture() {
            mLocations = new LinkedBlockingQueue<>();
        }

        @Override
        public void onSetAdditionalProviderPackages(List<String> packageNames) {

        }

        @Override
        public void onSetAllowed(boolean allowed) {

        }

        @Override
        public void onSetProperties(ProviderProperties properties) {

        }

        @Override
        public void onReportLocation(Location location) {
            mLocations.add(location);
        }

        public Location getNextLocation(long timeoutMs) throws InterruptedException {
            return mLocations.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private static final double MIN_LATITUDE = -90D;
    private static final double MAX_LATITUDE = 90D;
    private static final double MIN_LONGITUDE = -180D;
    private static final double MAX_LONGITUDE = 180D;

    private static final float MIN_ACCURACY = 1;
    private static final float MAX_ACCURACY = 100;

    private static Location createLocation(String provider, Random random) {
        return createLocation(provider,
                MIN_LATITUDE + random.nextDouble() * (MAX_LATITUDE - MIN_LATITUDE),
                MIN_LONGITUDE + random.nextDouble() * (MAX_LONGITUDE - MIN_LONGITUDE),
                MIN_ACCURACY + random.nextFloat() * (MAX_ACCURACY - MIN_ACCURACY));
    }

    private static Location createLocation(String provider, double latitude, double longitude,
            float accuracy) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(accuracy);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }

    private static void setMockLocation(boolean allowed) throws IOException {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("appops set "
                        + InstrumentationRegistry.getTargetContext().getPackageName()
                        + " android:mock_location " + (allowed ? "allow" : "deny"));
        try (FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int count;
            try {
                while ((count = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, count);
                }
                fis.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Log.e(TAG, new String(os.toByteArray()));
        }
    }
}
