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

import static com.android.server.location.gnss.GnssManagerService.D;
import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.app.AppOpsManager;
import android.location.GnssStatus;
import android.location.IGnssStatusListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.stats.location.LocationStatsEnums;
import android.util.Log;

import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationUsageLogger;

import java.util.Collection;

/**
 * Implementation of a handler for {@link IGnssStatusListener}.
 */
public class GnssStatusProvider extends
        GnssListenerMultiplexer<Void, IGnssStatusListener, Void> implements
        GnssNative.BaseCallbacks, GnssNative.StatusCallbacks, GnssNative.SvStatusCallbacks {

    private final AppOpsHelper mAppOpsHelper;
    private final LocationUsageLogger mLogger;

    private boolean mIsNavigating = false;

    public GnssStatusProvider(Injector injector, GnssNative gnssNative) {
        super(injector);
        mAppOpsHelper = injector.getAppOpsHelper();
        mLogger = injector.getLocationUsageLogger();

        gnssNative.addBaseCallbacks(this);
        gnssNative.addStatusCallbacks(this);
        gnssNative.addSvStatusCallbacks(this);
    }

    @Override
    public void addListener(CallerIdentity identity, IGnssStatusListener listener) {
        super.addListener(identity, listener);
    }

    @Override
    protected boolean registerWithService(Void ignored,
            Collection<GnssListenerRegistration> registrations) {
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
                registration.getIdentity().getAttributionTag(),
                null,
                null,
                true,
                false,
                null, registration.isForeground());
    }

    @Override
    protected void onRegistrationRemoved(IBinder key, GnssListenerRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_ENDED,
                LocationStatsEnums.API_REGISTER_GNSS_STATUS_CALLBACK,
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                null,
                null,
                true,
                false,
                null,
                registration.isForeground());
    }

    @Override
    public void onHalRestarted() {
        resetService();
    }

    @Override
    public void onReportStatus(@GnssNative.StatusCallbacks.GnssStatusValue int gnssStatus) {
        boolean isNavigating;
        switch (gnssStatus) {
            case GNSS_STATUS_SESSION_BEGIN:
                isNavigating = true;
                break;
            case GNSS_STATUS_SESSION_END:
                // fall through
            case GNSS_STATUS_ENGINE_OFF:
                isNavigating = false;
                break;
            default:
                isNavigating = mIsNavigating;
        }

        if (isNavigating != mIsNavigating) {
            mIsNavigating = isNavigating;
            if (isNavigating) {
                deliverToListeners(IGnssStatusListener::onGnssStarted);
            } else {
                deliverToListeners(IGnssStatusListener::onGnssStopped);
            }
        }
    }

    @Override
    public void onReportFirstFix(int ttff) {
        deliverToListeners(listener -> {
            listener.onFirstFix(ttff);
        });
    }

    @Override
    public void onReportSvStatus(GnssStatus gnssStatus) {
        deliverToListeners(registration -> {
            if (mAppOpsHelper.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION,
                    registration.getIdentity())) {
                return listener -> listener.onSvStatusChanged(gnssStatus);
            } else {
                return null;
            }
        });
    }
}
