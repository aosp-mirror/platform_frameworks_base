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

import android.content.Context;
import android.location.GnssMeasurementsEvent;
import android.location.GnssRequest;
import android.location.IGnssMeasurementsListener;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.CallerIdentity;
import com.android.server.location.RemoteListenerHelper;

/**
 * An base implementation for GPS measurements provider. It abstracts out the responsibility of
 * handling listeners, while still allowing technology specific implementations to be built.
 *
 * @hide
 */
public abstract class GnssMeasurementsProvider
        extends RemoteListenerHelper<GnssRequest, IGnssMeasurementsListener> {
    private static final String TAG = "GnssMeasProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final GnssMeasurementProviderNative mNative;

    private boolean mStartedCollection;
    private boolean mStartedFullTracking;

    protected GnssMeasurementsProvider(Context context, Handler handler) {
        this(context, handler, new GnssMeasurementProviderNative());
    }

    @VisibleForTesting
    public GnssMeasurementsProvider(
            Context context, Handler handler, GnssMeasurementProviderNative aNative) {
        super(context, handler, TAG);
        mNative = aNative;
    }

    void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        if (mStartedCollection) {
            mNative.startMeasurementCollection(mStartedFullTracking);
        }
    }

    @Override
    public boolean isAvailableInPlatform() {
        return mNative.isMeasurementSupported();
    }

    private boolean getMergedFullTracking() {
        int devOptions = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        int enableFullTracking = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ENABLE_GNSS_RAW_MEAS_FULL_TRACKING, 0);
        boolean enableFullTrackingBySetting = (devOptions == 1 /* Developer Mode enabled */)
                && (enableFullTracking == 1 /* Raw Measurements Full Tracking enabled */);
        if (enableFullTrackingBySetting) {
            return true;
        }

        synchronized (mListenerMap) {
            for (IdentifiedListener identifiedListener : mListenerMap.values()) {
                GnssRequest request = identifiedListener.getRequest();
                if (request != null && request.isFullTracking()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected int registerWithService() {
        boolean enableFullTracking = getMergedFullTracking();
        boolean result = mNative.startMeasurementCollection(enableFullTracking);
        if (result) {
            mStartedCollection = true;
            mStartedFullTracking = enableFullTracking;
            return RemoteListenerHelper.RESULT_SUCCESS;
        } else {
            return RemoteListenerHelper.RESULT_INTERNAL_ERROR;
        }
    }

    @Override
    protected void unregisterFromService() {
        boolean stopped = mNative.stopMeasurementCollection();
        if (stopped) {
            mStartedCollection = false;
        }
    }

    public void onMeasurementsAvailable(final GnssMeasurementsEvent event) {
        foreach((IGnssMeasurementsListener listener, CallerIdentity callerIdentity) -> {
            if (!hasPermission(mContext, callerIdentity)) {
                logPermissionDisabledEventNotReported(
                        TAG, callerIdentity.packageName, "GNSS measurements");
                return;
            }
            listener.onGnssMeasurementsReceived(event);
        });
    }

    /** Handle GNSS capabilities update from the GNSS HAL implementation. */
    public void onCapabilitiesUpdated(boolean isGnssMeasurementsSupported) {
        setSupported(isGnssMeasurementsSupported);
        updateResult();
    }

    public void onGpsEnabledChanged() {
        tryUpdateRegistrationWithService();
        updateResult();
    }

    @Override
    protected ListenerOperation<IGnssMeasurementsListener> getHandlerOperation(int result) {
        int status;
        switch (result) {
            case RESULT_SUCCESS:
                status = GnssMeasurementsEvent.Callback.STATUS_READY;
                break;
            case RESULT_NOT_AVAILABLE:
            case RESULT_NOT_SUPPORTED:
            case RESULT_INTERNAL_ERROR:
                status = GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED;
                break;
            case RESULT_NOT_ALLOWED:
                status = GnssMeasurementsEvent.Callback.STATUS_NOT_ALLOWED;
                break;
            case RESULT_GPS_LOCATION_DISABLED:
                status = GnssMeasurementsEvent.Callback.STATUS_LOCATION_DISABLED;
                break;
            case RESULT_UNKNOWN:
                return null;
            default:
                Log.v(TAG, "Unhandled addListener result: " + result);
                return null;
        }
        return new StatusChangedOperation(status);
    }

    private static class StatusChangedOperation
            implements ListenerOperation<IGnssMeasurementsListener> {
        private final int mStatus;

        public StatusChangedOperation(int status) {
            mStatus = status;
        }

        @Override
        public void execute(IGnssMeasurementsListener listener,
                CallerIdentity callerIdentity) throws RemoteException {
            listener.onStatusChanged(mStatus);
        }
    }

    @VisibleForTesting
    public static class GnssMeasurementProviderNative {
        public boolean isMeasurementSupported() {
            return native_is_measurement_supported();
        }

        public boolean startMeasurementCollection(boolean enableFullTracking) {
            return native_start_measurement_collection(enableFullTracking);
        }

        public boolean stopMeasurementCollection() {
            return native_stop_measurement_collection();
        }
    }

    private static native boolean native_is_measurement_supported();

    private static native boolean native_start_measurement_collection(boolean enableFullTracking);

    private static native boolean native_stop_measurement_collection();
}
