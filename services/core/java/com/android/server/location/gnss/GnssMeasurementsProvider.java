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

import android.location.GnssMeasurementsEvent;
import android.location.GnssRequest;
import android.location.IGnssMeasurementsListener;
import android.location.util.identity.CallerIdentity;
import android.os.IBinder;
import android.os.RemoteException;
import android.stats.location.LocationStatsEnums;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.AppOpsHelper;
import com.android.server.location.LocationUsageLogger;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;

import java.util.List;
import java.util.Objects;

/**
 * An base implementation for GNSS measurements provider. It abstracts out the responsibility of
 * handling listeners, while still allowing technology specific implementations to be built.
 *
 * @hide
 */
public class GnssMeasurementsProvider extends
        GnssListenerManager<GnssRequest, IGnssMeasurementsListener, Boolean> implements
        SettingsHelper.GlobalSettingChangedListener {

    private final LocationUsageLogger mLogger;
    private final GnssMeasurementProviderNative mNative;

    public GnssMeasurementsProvider(UserInfoHelper userInfoHelper,
            SettingsHelper settingsHelper, AppOpsHelper appOpsHelper,
            AppForegroundHelper appForegroundHelper, LocationUsageLogger logger) {
        this(userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper, logger,
                new GnssMeasurementProviderNative());
    }

    @VisibleForTesting
    public GnssMeasurementsProvider(UserInfoHelper userInfoHelper,
            SettingsHelper settingsHelper, AppOpsHelper appOpsHelper,
            AppForegroundHelper appForegroundHelper, LocationUsageLogger logger,
            GnssMeasurementProviderNative aNative) {
        super(userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper);
        mLogger = logger;
        mNative = aNative;
    }

    @Override
    public void addListener(GnssRequest request, CallerIdentity identity,
            IGnssMeasurementsListener listener) {
        super.addListener(request, identity, listener);
    }

    @Override
    protected boolean registerService(Boolean fullTrackingRequest) {
        if (mNative.isMeasurementSupported()) {
            if (mNative.startMeasurementCollection(fullTrackingRequest)) {
                if (GnssManagerService.D) {
                    Log.d(GnssManagerService.TAG,
                            "starting gnss measurements (" + fullTrackingRequest + ")");
                }
                return true;
            }

            Log.e(GnssManagerService.TAG, "error starting gnss measurements");
        }
        return false;
    }

    @Override
    protected void unregisterService() {
        if (mNative.isMeasurementSupported()) {
            if (mNative.stopMeasurementCollection()) {
                if (GnssManagerService.D) {
                    Log.d(GnssManagerService.TAG, "stopping gnss measurements");
                }
            } else {
                Log.e(GnssManagerService.TAG, "error stopping gnss measurements");
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
    protected Boolean mergeRequests(List<GnssRegistration> registrations) {
        if (mSettingsHelper.isGnssMeasurementsFullTrackingEnabled()) {
            return true;
        }

        for (GnssRegistration registration : registrations) {
            if (Objects.requireNonNull(registration.getRequest()).isFullTracking()) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void onRegistrationAdded(IBinder key, GnssRegistration registration) {
        mLogger.logLocationApiUsage(
                LocationStatsEnums.USAGE_STARTED,
                LocationStatsEnums.API_ADD_GNSS_MEASUREMENTS_LISTENER,
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
                LocationStatsEnums.API_ADD_GNSS_MEASUREMENTS_LISTENER,
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
    public void onMeasurementsAvailable(GnssMeasurementsEvent event) {
        deliverToListeners(listener -> {
            try {
                listener.onGnssMeasurementsReceived(event);
            } catch (RemoteException e) {
                // ignore - the listener will get cleaned up later anyways
            }
        }, registration -> mAppOpsHelper.noteLocationAccess(registration.getIdentity()));
    }

    @Override
    protected boolean isServiceSupported() {
        return mNative.isMeasurementSupported();
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
