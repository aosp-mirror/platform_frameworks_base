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

package com.android.server.location.gnss;

import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.gnss.GnssManagerService.D;
import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.location.GnssStatus;
import android.location.IGnssStatusListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.stats.location.LocationStatsEnums;
import android.util.Log;

import com.android.server.location.util.Injector;
import com.android.server.location.util.LocationUsageLogger;

/**
 * Implementation of a handler for {@link IGnssStatusListener}.
 */
public class GnssStatusProvider extends GnssListenerMultiplexer<Void, IGnssStatusListener, Void> {

    private final LocationUsageLogger mLogger;

    public GnssStatusProvider(Injector injector) {
        super(injector);
        mLogger = injector.getLocationUsageLogger();
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssStatusListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerWithService(Void ignored) {
        if (D) {
            Log.d(TAG, "starting gnss status");
        }
        return true;
    }

    @Override
    protected void unregisterWithService() {
        if (D) {
            Log.d(TAG, "stopping gnss status");
        }
    }

    @Override
    protected void onRegistrationAdded(IBinder key, GnssListenerRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_REGISTER_GNSS_STATUS_CALLBACK,
                registration.getIdentity().getPackageName(),
                /* LocationRequest= */ null,
                /* hasListener= */ true,
                /* hasIntent= */ false,
                /* geofence= */ null,
                registration.isForeground());
    }

    @Override
    protected void onRegistrationRemoved(IBinder key, GnssListenerRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REGISTER_GNSS_STATUS_CALLBACK,
                registration.getIdentity().getPackageName(),
                /* LocationRequest= */ null,
                /* hasListener= */ true,
                /* hasIntent= */ false,
                /* geofence= */ null,
                registration.isForeground());
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onStatusChanged(boolean isNavigating) {
        if (isNavigating) {
            deliverToListeners(IGnssStatusListener::onGnssStarted);
        } else {
            deliverToListeners(IGnssStatusListener::onGnssStopped);
        }
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onFirstFix(int ttff) {
        deliverToListeners(listener -> {
            listener.onFirstFix(ttff);
        });
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onSvStatusChanged(GnssStatus gnssStatus) {
        deliverToListeners(registration -> {
            if (mAppOpsHelper.noteLocationAccess(registration.getIdentity(), PERMISSION_FINE)) {
                return listener -> listener.onSvStatusChanged(gnssStatus);
            } else {
                return null;
            }
        });
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onNmeaReceived(long timestamp, String nmea) {
        deliverToListeners(registration -> {
            if (mAppOpsHelper.noteLocationAccess(registration.getIdentity(), PERMISSION_FINE)) {
                return listener -> listener.onNmeaReceived(timestamp, nmea);
            } else {
                return null;
            }
        });
    }
}
