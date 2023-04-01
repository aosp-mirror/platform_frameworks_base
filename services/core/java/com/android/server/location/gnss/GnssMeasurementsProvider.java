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

import static com.android.server.location.eventlog.LocationEventLog.EVENT_LOG;
import static com.android.server.location.gnss.GnssManagerService.D;
import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.IGnssMeasurementsListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.stats.location.LocationStatsEnums;
import android.util.Log;

import com.android.server.location.gnss.GnssConfiguration.HalInterfaceVersion;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.SettingsHelper;

import java.util.Collection;

/**
 * GNSS measurements HAL module and listener multiplexer.
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
        protected void onRegister() {
            super.onRegister();
            EVENT_LOG.logGnssMeasurementClientRegistered(getIdentity(), getRequest());
            executeOperation(listener -> listener.onStatusChanged(
                    GnssMeasurementsEvent.Callback.STATUS_READY));
        }

        @Override
        protected void onUnregister() {
            EVENT_LOG.logGnssMeasurementClientUnregistered(getIdentity());
            super.onUnregister();
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
        if (request.getIntervalMillis() == GnssMeasurementRequest.PASSIVE_INTERVAL) {
            return true;
        }
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
    protected boolean reregisterWithService(GnssMeasurementRequest old,
            GnssMeasurementRequest request,
            @NonNull Collection<GnssListenerRegistration> registrations) {
        if (request.getIntervalMillis() == GnssMeasurementRequest.PASSIVE_INTERVAL) {
            unregisterWithService();
            return true;
        }
        HalInterfaceVersion halInterfaceVersion =
                mGnssNative.getConfiguration().getHalInterfaceVersion();
        boolean aidlV3Plus = halInterfaceVersion.mMajor == HalInterfaceVersion.AIDL_INTERFACE
                && halInterfaceVersion.mMinor >= 3;
        if (!aidlV3Plus) {
            // The HAL doc does not specify if consecutive start() calls will be allowed.
            // Some vendors may ignore the 2nd start() call if stop() is not called.
            // Thus, here we always call stop() before calling start() to avoid being ignored.
            // AIDL v3+ is free from this issue.
            unregisterWithService();
        }
        return registerWithService(request, registrations);
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
        int intervalMillis = GnssMeasurementRequest.PASSIVE_INTERVAL;

        if (mSettingsHelper.isGnssMeasurementsFullTrackingEnabled()) {
            fullTracking = true;
        }

        for (GnssListenerRegistration registration : registrations) {
            GnssMeasurementRequest request = registration.getRequest();
            // passive requests do not contribute to the merged request
            if (request.getIntervalMillis() == GnssMeasurementRequest.PASSIVE_INTERVAL) {
                continue;
            }
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
                EVENT_LOG.logGnssMeasurementsDelivered(event.getMeasurements().size(),
                        registration.getIdentity());
                return listener -> listener.onGnssMeasurementsReceived(event);
            } else {
                return null;
            }
        });
    }
}
