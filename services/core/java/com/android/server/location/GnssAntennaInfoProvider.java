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

package com.android.server.location;

import android.content.Context;
import android.location.GnssAntennaInfo;
import android.location.IGnssAntennaInfoListener;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * An base implementation for GNSS antenna info provider. It abstracts out the responsibility of
 * handling listeners, while still allowing technology specific implementations to be built.
 *
 * @hide
 */
public abstract class GnssAntennaInfoProvider
        extends RemoteListenerHelper<Void, IGnssAntennaInfoListener> {
    private static final String TAG = "GnssAntennaInfoProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final GnssAntennaInfoProviderNative mNative;

    private boolean mIsListeningStarted;

    protected GnssAntennaInfoProvider(Context context, Handler handler) {
        this(context, handler, new GnssAntennaInfoProviderNative());
    }

    @VisibleForTesting
    public GnssAntennaInfoProvider(
            Context context, Handler handler, GnssAntennaInfoProviderNative aNative) {
        super(context, handler, TAG);
        mNative = aNative;
    }

    void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        if (mIsListeningStarted) {
            mNative.startAntennaInfoListening();
        }
    }


    @Override
    public boolean isAvailableInPlatform() {
        return mNative.isAntennaInfoSupported();
    }

    @Override
    protected int registerWithService() {
        boolean started = mNative.startAntennaInfoListening();
        if (started) {
            mIsListeningStarted = true;
            return RemoteListenerHelper.RESULT_SUCCESS;
        }
        return RemoteListenerHelper.RESULT_INTERNAL_ERROR;
    }

    @Override
    protected void unregisterFromService() {
        boolean stopped = mNative.stopAntennaInfoListening();
        if (stopped) {
            mIsListeningStarted = false;
        }
    }

    /** Handle GNSS capabilities update from the GNSS HAL implementation. */
    public void onCapabilitiesUpdated(boolean isAntennaInfoSupported) {
        setSupported(isAntennaInfoSupported);
        updateResult();
    }

    /** Handle GNSS enabled changes.*/
    public void onGpsEnabledChanged() {
        tryUpdateRegistrationWithService();
        updateResult();
    }

    @Override
    protected ListenerOperation<IGnssAntennaInfoListener> getHandlerOperation(int result) {
        int status;
        switch (result) {
            case RESULT_SUCCESS:
                status = GnssAntennaInfo.Callback.STATUS_READY;
                break;
            case RESULT_NOT_AVAILABLE:
            case RESULT_NOT_SUPPORTED:
            case RESULT_INTERNAL_ERROR:
                status = GnssAntennaInfo.Callback.STATUS_NOT_SUPPORTED;
                break;
            case RESULT_GPS_LOCATION_DISABLED:
                status = GnssAntennaInfo.Callback.STATUS_LOCATION_DISABLED;
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
            implements ListenerOperation<IGnssAntennaInfoListener> {
        private final int mStatus;

        StatusChangedOperation(int status) {
            mStatus = status;
        }

        @Override
        public void execute(IGnssAntennaInfoListener listener,
                CallerIdentity callerIdentity) throws RemoteException {
            listener.onStatusChanged(mStatus);
        }
    }

    /** Handle Gnss Antenna Info report. */
    public void onGnssAntennaInfoAvailable(final List<GnssAntennaInfo> gnssAntennaInfos) {
        foreach((IGnssAntennaInfoListener listener, CallerIdentity callerIdentity) -> {
            if (!hasPermission(mContext, callerIdentity)) {
                logPermissionDisabledEventNotReported(
                        TAG, callerIdentity.mPackageName, "GNSS antenna info");
                return;
            }
            listener.onGnssAntennaInfoReceived(gnssAntennaInfos);
        });
    }

    /**
     * Wrapper class for native methods. This is mocked for testing.
     */
    @VisibleForTesting
    public static class GnssAntennaInfoProviderNative {

        public boolean isAntennaInfoSupported() {
            return native_is_antenna_info_supported();
        }

        /** Start antenna info listening. */
        public boolean startAntennaInfoListening() {
            return native_start_antenna_info_listening();
        }

        /** Stop antenna info listening. */
        public boolean stopAntennaInfoListening() {
            return native_stop_antenna_info_listening();
        }
    }

    private static native boolean native_is_antenna_info_supported();

    private static native boolean native_start_antenna_info_listening();

    private static native boolean native_stop_antenna_info_listening();
}
