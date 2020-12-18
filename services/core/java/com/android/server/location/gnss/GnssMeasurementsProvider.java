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

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.IGnssMeasurementsListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.stats.location.LocationStatsEnums;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationAttributionHelper;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.SettingsHelper;

import java.util.Collection;
import java.util.Objects;

/**
 * An base implementation for GNSS measurements provider. It abstracts out the responsibility of
 * handling listeners, while still allowing technology specific implementations to be built.
 *
 * @hide
 */
public final class GnssMeasurementsProvider extends
        GnssListenerMultiplexer<GnssMeasurementRequest, IGnssMeasurementsListener, Boolean>
        implements SettingsHelper.GlobalSettingChangedListener {

    private class GnssMeasurementListenerRegistration extends GnssListenerRegistration {

        private static final String GNSS_MEASUREMENTS_BUCKET = "gnss_measurement";

        protected GnssMeasurementListenerRegistration(
                @Nullable GnssMeasurementRequest request,
                CallerIdentity callerIdentity,
                IGnssMeasurementsListener iGnssMeasurementsListener) {
            super(request, callerIdentity, iGnssMeasurementsListener);
        }

        @Nullable
        @Override
        protected void onActive() {
            mLocationAttributionHelper.reportHighPowerLocationStart(
                    getIdentity(), GNSS_MEASUREMENTS_BUCKET, getKey());
        }

        @Nullable
        @Override
        protected void onInactive() {
            mLocationAttributionHelper.reportHighPowerLocationStop(
                    getIdentity(), GNSS_MEASUREMENTS_BUCKET, getKey());
        }
    }

    private final AppOpsHelper mAppOpsHelper;
    private final LocationAttributionHelper mLocationAttributionHelper;
    private final LocationUsageLogger mLogger;
    private final GnssMeasurementProviderNative mNative;

    public GnssMeasurementsProvider(Injector injector) {
        this(injector, new GnssMeasurementProviderNative());
    }

    @VisibleForTesting
    public GnssMeasurementsProvider(Injector injector, GnssMeasurementProviderNative aNative) {
        super(injector);
        mAppOpsHelper = injector.getAppOpsHelper();
        mLocationAttributionHelper = injector.getLocationAttributionHelper();
        mLogger = injector.getLocationUsageLogger();
        mNative = aNative;
    }

    @Override
    protected boolean isServiceSupported() {
        return mNative.isMeasurementSupported();
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
    protected boolean registerWithService(Boolean fullTrackingRequest,
            Collection<GnssListenerRegistration> registrations) {
        Preconditions.checkState(mNative.isMeasurementSupported());

        if (mNative.startMeasurementCollection(fullTrackingRequest)) {
            if (D) {
                Log.d(TAG, "starting gnss measurements (" + fullTrackingRequest + ")");
            }
            return true;
        } else {

            Log.e(TAG, "error starting gnss measurements");
            return false;
        }
    }

    @Override
    protected void unregisterWithService() {
        if (mNative.isMeasurementSupported()) {
            if (mNative.stopMeasurementCollection()) {
                if (D) {
                    Log.d(TAG, "stopping gnss measurements");
                }
            } else {
                Log.e(TAG, "error stopping gnss measurements");
            }
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
    protected Boolean mergeRegistrations(Collection<GnssListenerRegistration> registrations) {
        if (mSettingsHelper.isGnssMeasurementsFullTrackingEnabled()) {
            return true;
        }

        for (GnssListenerRegistration registration : registrations) {
            if (Objects.requireNonNull(registration.getRequest()).isFullTracking()) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void onRegistrationAdded(IBinder key, GnssListenerRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_ADD_GNSS_MEASUREMENTS_LISTENER,
                registration.getIdentity().getPackageName(),
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
                null,
                null,
                true,
                false,
                null, registration.isForeground());
    }

    /**
     * Called by GnssLocationProvider.
     */
    public void onMeasurementsAvailable(GnssMeasurementsEvent event) {
        deliverToListeners(registration -> {
            if (mAppOpsHelper.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION,
                    registration.getIdentity())) {
                return listener -> listener.onGnssMeasurementsReceived(event);
            } else {
                return null;
            }
        });
    }

    @VisibleForTesting
    static class GnssMeasurementProviderNative {
        boolean isMeasurementSupported() {
            return native_is_measurement_supported();
        }

        boolean startMeasurementCollection(boolean enableFullTracking) {
            return native_start_measurement_collection(enableFullTracking);
        }

        boolean stopMeasurementCollection() {
            return native_stop_measurement_collection();
        }
    }

    static native boolean native_is_measurement_supported();

    static native boolean native_start_measurement_collection(boolean enableFullTracking);

    static native boolean native_stop_measurement_collection();
}
