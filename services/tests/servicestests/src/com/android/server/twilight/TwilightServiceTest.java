package com.android.server.twilight;

/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.AlarmManager;
import android.content.Context;
import android.location.Location;
import android.test.AndroidTestCase;

public class TwilightServiceTest extends AndroidTestCase {

    private TwilightService mTwilightService;
    private Location mInitialLocation;

    @Override
    protected void setUp() throws Exception {
        final Context context = getContext();
        mTwilightService = new TwilightService(context);
        mTwilightService.mAlarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        mInitialLocation = createMockLocation(10.0, 10.0);
        mTwilightService.onLocationChanged(mInitialLocation);
    }

    @Override
    protected void tearDown() throws Exception {
        mTwilightService = null;
        mInitialLocation = null;
    }

    public void testValidLocation_updatedLocation() {
        final TwilightState priorState = mTwilightService.mLastTwilightState;
        final Location validLocation = createMockLocation(35.0, 35.0);
        mTwilightService.onLocationChanged(validLocation);
        assertEquals(mTwilightService.mLastLocation, validLocation);
        assertNotSame(priorState, mTwilightService.mLastTwilightState);
    }

    public void testInvalidLocation_ignoreLocationUpdate() {
        final TwilightState priorState = mTwilightService.mLastTwilightState;
        final Location invalidLocation = createMockLocation(0.0, 0.0);
        mTwilightService.onLocationChanged(invalidLocation);
        assertEquals(mTwilightService.mLastLocation, mInitialLocation);
        assertEquals(priorState, mTwilightService.mLastTwilightState);
    }

    private Location createMockLocation(double latitude, double longitude) {
        // There's no empty constructor, so we initialize with a string and quickly reset it.
        final Location location = new Location("");
        location.reset();
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        return location;
    }

}
