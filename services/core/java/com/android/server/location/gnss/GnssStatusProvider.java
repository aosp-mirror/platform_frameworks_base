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

import android.location.GnssStatus;
import android.location.IGnssStatusListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.os.RemoteException;
import android.stats.location.LocationStatsEnums;
import android.util.Log;

import com.android.server.location.AppForegroundHelper;
import com.android.server.location.AppOpsHelper;
import com.android.server.location.LocationUsageLogger;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;

/**
 * Implementation of a handler for {@link IGnssStatusListener}.
 */
public class GnssStatusProvider extends GnssListenerManager<Void, IGnssStatusListener, Void> {

    private final LocationUsageLogger mLogger;

    public GnssStatusProvider(UserInfoHelper userInfoHelper, SettingsHelper settingsHelper,
            AppOpsHelper appOpsHelper, AppForegroundHelper appForegroundHelper,
            LocationUsageLogger logger) {
        super(userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper);
        mLogger = logger;
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssStatusListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerService(Void ignored) {
        if (GnssManagerService.D) {
            Log.d(GnssManagerService.TAG, "starting gnss status");
        }
        return true;
    }

    @Override
    protected void unregisterService() {
        if (GnssManagerService.D) {
            Log.d(GnssManagerService.TAG, "stopping gnss status");
        }
    }

    @Override
    protected void onRegistrationAdded(IBinder key, GnssRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_REGISTER_GNSS_STATUS_CALLBACK,
                registration.getIdentity().packageName,
                /* LocationRequest= */ null,
                /* hasListener= */ true,
                /* hasIntent= */ false,
                /* geofence= */ null,
                registration.isForeground());
    }

    @Override
    protected void onRegistrationRemoved(IBinder key, GnssRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REGISTER_GNSS_STATUS_CALLBACK,
                registration.getIdentity().packageName,
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
            deliverToListeners((listener) -> {
                try {
                    listener.onGnssStarted();
                } catch (RemoteException e) {
                    // ignore - the listener will get cleaned up later anyways
                }
            });
        } else {
            deliverToListeners((listener) -> {
                try {
                    listener.onGnssStopped();
                } catch (RemoteException e) {
                    // ignore - the listener will get cleaned up later anyways
                }
            });
        }
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onFirstFix(int ttff) {
        deliverToListeners((listener) -> {
            try {
                listener.onFirstFix(ttff);
            } catch (RemoteException e) {
                // ignore - the listener will get cleaned up later anyways
            }
        });
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onSvStatusChanged(GnssStatus gnssStatus) {
        deliverToListeners((listener) -> {
            try {
                listener.onSvStatusChanged(gnssStatus);
            } catch (RemoteException e) {
                // ignore - the listener will get cleaned up later anyways
            }
        }, registration -> mAppOpsHelper.noteLocationAccess(registration.getIdentity()));
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onNmeaReceived(long timestamp, String nmea) {
        deliverToListeners((listener) -> {
            try {
                listener.onNmeaReceived(timestamp, nmea);
            } catch (RemoteException e) {
                // ignore - the listener will get cleaned up later anyways
            }
        }, registration -> mAppOpsHelper.noteLocationAccess(registration.getIdentity()));
    }
}
