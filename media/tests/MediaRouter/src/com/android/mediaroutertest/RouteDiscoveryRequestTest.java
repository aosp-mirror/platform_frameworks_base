/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.mediaroutertest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.media.RouteDiscoveryRequest;
import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RouteDiscoveryRequestTest {
    @Before
    public void setUp() throws Exception { }

    @After
    public void tearDown() throws Exception { }

    @Test
    public void testEquality() {
        List<String> testTypes = new ArrayList<>();
        testTypes.add("TEST_TYPE_1");
        testTypes.add("TEST_TYPE_2");
        RouteDiscoveryRequest request = new RouteDiscoveryRequest.Builder(testTypes, true)
                .build();

        RouteDiscoveryRequest requestRebuilt = new RouteDiscoveryRequest.Builder(request)
                .build();

        assertEquals(request, requestRebuilt);

        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(request, 0);
        parcel.setDataPosition(0);
        RouteDiscoveryRequest requestFromParcel = parcel.readParcelable(null);

        assertEquals(request, requestFromParcel);
    }

    @Test
    public void testInequality() {
        List<String> testTypes = new ArrayList<>();
        testTypes.add("TEST_TYPE_1");
        testTypes.add("TEST_TYPE_2");

        List<String> testTypes2 = new ArrayList<>();
        testTypes.add("TEST_TYPE_3");

        RouteDiscoveryRequest request = new RouteDiscoveryRequest.Builder(testTypes, true)
                .build();

        RouteDiscoveryRequest requestTypes = new RouteDiscoveryRequest.Builder(request)
                .setRouteTypes(testTypes2)
                .build();
        assertNotEquals(request, requestTypes);

        RouteDiscoveryRequest requestActiveScan = new RouteDiscoveryRequest.Builder(request)
                .setActiveScan(false)
                .build();
        assertNotEquals(request, requestActiveScan);
    }
}
