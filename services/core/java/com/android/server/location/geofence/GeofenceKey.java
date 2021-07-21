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

package com.android.server.location.geofence;

import android.app.PendingIntent;
import android.location.Geofence;

import com.android.server.location.listeners.PendingIntentListenerRegistration;

import java.util.Objects;

// geofencing unfortunately allows multiple geofences under the same pending intent, even though
// this makes no real sense. therefore we manufacture an artificial key to use (pendingintent +
// geofence) instead of (pendingintent).
final class GeofenceKey  implements PendingIntentListenerRegistration.PendingIntentKey {

    private final PendingIntent mPendingIntent;
    private final Geofence mGeofence;

    GeofenceKey(PendingIntent pendingIntent, Geofence geofence) {
        mPendingIntent = Objects.requireNonNull(pendingIntent);
        mGeofence = Objects.requireNonNull(geofence);
    }

    @Override
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GeofenceKey)) {
            return false;
        }
        GeofenceKey that = (GeofenceKey) o;
        return mPendingIntent.equals(that.mPendingIntent) && mGeofence.equals(that.mGeofence);
    }

    @Override
    public int hashCode() {
        return mPendingIntent.hashCode();
    }
}
