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

import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;

import static com.android.server.location.gnss.GnssManagerService.D;
import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.IGnssMeasurementsListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.stats.location.LocationStatsEnums;
import android.util.Log;

import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.SettingsHelper;

import java.util.Collection;

/**
 * An base implementation for GNSS measurements provider. It abstracts out the responsibility of
 * handling listeners, while still allowing technology specific implementations to be built.
 *
 * @hide
 */
public final class GnssMeasurementsProvider extends
        GnssListenerMultiplexer<GnssMeasurementRequest, IGnssMeasurementsListener,
                GnssMeasurementRequest> implements
        SettingsHelper.GlobalSettingChangedListener, GnssNative.BaseCallbacks,
        GnssNative.MeasurementCallbacks {

    private class GnssMeasurementListenerRegistration extends GnssListenerRegistration {

        protected GnssMeasurementListenerRegistration(
                @Nullable GnssMeasurementRequest request,
                CallerIdentity callerIdentity,
                IGnssMeasurementsListener listener) {
            super(request, callerIdentity, listener);
        }

        @Override
        protected void onGnssListenerRegister() {
            executeOperation(listener -> listener.onStatusChanged(
                    GnssMeasurementsEvent.Callback.STATUS_READY));
        }

        @Nullable
        @Override
        protected void onActive() {
            mAppOpsHelper.startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION, getIdentity());
        }

        @Nullable
        @Override
        protected void onInactive() {
            mAppOpsHelper.finishOp(OP_MONITOR_HIGH_POWER_LOCATION, getIdentity());
        }
    }

    private final AppOpsHelper mAppOpsHelper;
    private final LocationUsageLogger mLogger;
    private final GnssNative mGnssNative;

    public GnssMeasurementsProvider(Injector injector, GnssNative gnssNative) {
        super(injector);
        mAppOpsHelper = injector.getAppOpsHelper();
        mLogger = injector.getLocationUsageLogger();
        mGnssNative = gnssNative;

        mGnssNative.addBaseCallbacks(this);
        mGnssNative.addMeasurementCallbacks(this);
    }

    @Override
    public boolean isSupported() {
        return mGnssNative.isMeasurementSupported();
    }

    @Override
    public void addListener(GnssMeasurementRequest request, CallerIdentity identity,
            IGnssMeasurementsListener listener) {
        super.addListener(request, identity, listener);
    }

    @Override
    protected GnssListenerRegistration createRegistration(GnssMeasurementRequest request,
            CallerIdentity callerIdentity, IGnssMeasurementsListener listener) {
        return new GnssMeasurementListenerRegistration(request, callerIdentity, listener);
    }

    @Override
    protected boolean registerWithService(GnssMeasurementRequest request,
            Collection<GnssListenerRegistration> registrations) {
        if (mGnssNative.startMeasurementCollection(request.isFullTracking(),
                request.isCorrelationVectorOutputsEnabled(),
                request.getIntervalMillis())) {
            if (D) {
                Log.d(TAG, "starting gnss measurements (" + request + ")");
            }
            return true;
        } else {
            Log.e(TAG, "error starting gnss measurements");
            return false;
        }
    }

    @Override
    protected void unregisterWithService() {
        if (mGnssNative.stopMeasurementCollection()) {
            if (D) {
                Log.d(TAG, "stopping gnss measurements");
            }
        } else {
            Log.e(TAG, "error stopping gnss measurements");
        }
    }

    @Override
    protected void onActive() {
        mSettingsHelper.addOnGnssMeasurementsFullTrackingEnabledChangedListener(this);
    }

    @Override
    protected void onInactive() {
        mSettingsHelper.removeOnGnssMeasurementsFullTrackingEnabledChangedListener(this);
    }

    @Override
    public void onSettingChanged() {
        // GNSS Measurements Full Tracking dev setting changed
        updateService();
    }

    @Override
    protected GnssMeasurementRequest mergeRegistrations(
            Collection<GnssListenerRegistration> registrations) {
        boolean fullTracking = false;
        boolean enableCorrVecOutputs = false;
        int intervalMillis = Integer.MAX_VALUE;

        if (mSettingsHelper.isGnssMeasurementsFullTrackingEnabled()) {
            fullTracking = true;
        }

        for (GnssListenerRegistration registration : registrations) {
            GnssMeasurementRequest request = registration.getRequest();
            if (request.isFullTracking()) {
                fullTracking = true;
            }
            if (request.isCorrelationVectorOutputsEnabled()) {
                enableCorrVecOutputs = true;
            }
            intervalMillis = Math.min(intervalMillis, request.getIntervalMillis());
        }

        return new GnssMeasurementRequest.Builder()
                    .setFullTracking(fullTracking)
                    .setCorrelationVectorOutputsEnabled(enableCorrVecOutputs)
                    .setIntervalMillis(intervalMillis)
                    .build();
    }

    @Override
    protected void onRegistrationAdded(IBinder key, GnssListenerRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_ADD_GNSS_MEASUREMENTS_LISTENER,
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
                LocationStatsEnums.API_ADD_GNSS_MEASUREMENTS_LISTENER,
                registration.getIdentity().getPackageName(),
                registration.getIdentity().getAttributionTag(),
                null,
                null,
                true,
                false,
                null, registration.isForeground());
    }

    @Override
    public void onHalRestarted() {
        resetService();
    }

    @Override
    public void onReportMeasurements(GnssMeasurementsEvent event) {
        deliverToListeners(registration -> {
            if (mAppOpsHelper.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION,
                    registration.getIdentity())) {
                return listener -> listener.onGnssMeasurementsReceived(event);
            } else {
                return null;
            }
        });
    }
}
