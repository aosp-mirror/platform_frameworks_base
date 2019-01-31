/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.location;

import android.content.Context;
import android.location.GnssNavigationMessage;
import android.location.IGnssNavigationMessageListener;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * An base implementation for GPS navigation messages provider.
 * It abstracts out the responsibility of handling listeners, while still allowing technology
 * specific implementations to be built.
 *
 * @hide
 */
public abstract class GnssNavigationMessageProvider
        extends RemoteListenerHelper<IGnssNavigationMessageListener> {
    private static final String TAG = "GnssNavigationMessageProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final GnssNavigationMessageProviderNative mNative;
    private boolean mCollectionStarted;

    protected GnssNavigationMessageProvider(Context context, Handler handler) {
        this(context, handler, new GnssNavigationMessageProviderNative());
    }

    @VisibleForTesting
    GnssNavigationMessageProvider(Context context, Handler handler,
            GnssNavigationMessageProviderNative aNative) {
        super(context, handler, TAG);
        mNative = aNative;
    }

    // TODO(b/37460011): Use this with death recovery logic.
    void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        if (mCollectionStarted) {
            mNative.startNavigationMessageCollection();
        }
    }

    @Override
    protected boolean isAvailableInPlatform() {
        return mNative.isNavigationMessageSupported();
    }

    @Override
    protected int registerWithService() {
        boolean result = mNative.startNavigationMessageCollection();
        if (result) {
            mCollectionStarted = true;
            return RemoteListenerHelper.RESULT_SUCCESS;
        } else {
            return RemoteListenerHelper.RESULT_INTERNAL_ERROR;
        }
    }

    @Override
    protected void unregisterFromService() {
        boolean stopped = mNative.stopNavigationMessageCollection();
        if (stopped) {
            mCollectionStarted = false;
        }
    }

    public void onNavigationMessageAvailable(final GnssNavigationMessage event) {
        foreach((IGnssNavigationMessageListener listener, CallerIdentity callerIdentity) -> {
                    listener.onGnssNavigationMessageReceived(event);
                }
        );
    }

    public void onCapabilitiesUpdated(boolean isGnssNavigationMessageSupported) {
        setSupported(isGnssNavigationMessageSupported);
        updateResult();
    }

    public void onGpsEnabledChanged() {
        tryUpdateRegistrationWithService();
        updateResult();
    }

    @Override
    protected ListenerOperation<IGnssNavigationMessageListener> getHandlerOperation(int result) {
        int status;
        switch (result) {
            case RESULT_SUCCESS:
                status = GnssNavigationMessage.Callback.STATUS_READY;
                break;
            case RESULT_NOT_AVAILABLE:
            case RESULT_NOT_SUPPORTED:
            case RESULT_INTERNAL_ERROR:
                status = GnssNavigationMessage.Callback.STATUS_NOT_SUPPORTED;
                break;
            case RESULT_GPS_LOCATION_DISABLED:
                status = GnssNavigationMessage.Callback.STATUS_LOCATION_DISABLED;
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
            implements ListenerOperation<IGnssNavigationMessageListener> {
        private final int mStatus;

        public StatusChangedOperation(int status) {
            mStatus = status;
        }

        @Override
        public void execute(IGnssNavigationMessageListener listener,
                CallerIdentity callerIdentity) throws RemoteException {
            listener.onStatusChanged(mStatus);
        }
    }

    @VisibleForTesting
    static class GnssNavigationMessageProviderNative {
        public boolean isNavigationMessageSupported() {
            return native_is_navigation_message_supported();
        }

        public boolean startNavigationMessageCollection() {
            return native_start_navigation_message_collection();
        }

        public boolean stopNavigationMessageCollection() {
            return native_stop_navigation_message_collection();
        }
    }

    private static native boolean native_is_navigation_message_supported();

    private static native boolean native_start_navigation_message_collection();

    private static native boolean native_stop_navigation_message_collection();
}
