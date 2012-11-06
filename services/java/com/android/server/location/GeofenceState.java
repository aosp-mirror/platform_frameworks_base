/*
 * Copyright (C) 2012 The Android Open Source Project
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


package com.android.server.location;

import android.app.PendingIntent;
import android.location.Geofence;
import android.location.Location;

/**
 * Represents state associated with a geofence
 */
public class GeofenceState {
    public final static int FLAG_ENTER = 0x01;
    public final static int FLAG_EXIT = 0x02;

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_INSIDE = 1;
    private static final int STATE_OUTSIDE = 2;

    public final Geofence mFence;
    private final Location mLocation;
    public final long mExpireAt;
    public final String mPackageName;
    public final PendingIntent mIntent;

    int mState;  // current state
    double mDistanceToCenter;  // current distance to center of fence

    public GeofenceState(Geofence fence, long expireAt,
            String packageName, PendingIntent intent) {
        mState = STATE_UNKNOWN;
        mDistanceToCenter = Double.MAX_VALUE;

        mFence = fence;
        mExpireAt = expireAt;
        mPackageName = packageName;
        mIntent = intent;

        mLocation = new Location("");
        mLocation.setLatitude(fence.getLatitude());
        mLocation.setLongitude(fence.getLongitude());
    }

    /**
     * Process a new location.
     * @return FLAG_ENTER or FLAG_EXIT if the fence was crossed, 0 otherwise
     */
    public int processLocation(Location location) {
        mDistanceToCenter = mLocation.distanceTo(location);

        int prevState = mState;
        //TODO: inside/outside detection could be made more rigorous
        boolean inside = mDistanceToCenter <= Math.max(mFence.getRadius(), location.getAccuracy());
        if (inside) {
            mState = STATE_INSIDE;
            if (prevState != STATE_INSIDE) {
                return FLAG_ENTER; // return enter if previously exited or unknown
            }
        } else {
            mState = STATE_OUTSIDE;
            if (prevState == STATE_INSIDE) {
                return FLAG_EXIT; // return exit only if previously entered
            }
        }
        return 0;
    }

    /**
     * Gets the distance from the current location to the fence's boundary.
     * @return The distance or {@link Double#MAX_VALUE} if unknown.
     */
    public double getDistanceToBoundary() {
        if (Double.compare(mDistanceToCenter, Double.MAX_VALUE) == 0) {
            return Double.MAX_VALUE;
        } else {
            return Math.abs(mFence.getRadius() - mDistanceToCenter);
        }
    }

    @Override
    public String toString() {
        String state;
        switch (mState) {
            case STATE_INSIDE:
                state = "IN";
                break;
            case STATE_OUTSIDE:
                state = "OUT";
                break;
            default:
                state = "?";
        }
        return String.format("%s d=%.0f %s", mFence.toString(), mDistanceToCenter, state);
    }
}
