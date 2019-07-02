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
import android.location.IGnssStatusListener;
import android.os.Handler;
import android.util.Log;

/**
 * Implementation of a handler for {@link IGnssStatusListener}.
 */
public abstract class GnssStatusListenerHelper extends RemoteListenerHelper<IGnssStatusListener> {
    private static final String TAG = "GnssStatusListenerHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    protected GnssStatusListenerHelper(Context context, Handler handler) {
        super(context, handler, TAG);
        setSupported(GnssLocationProvider.isSupported());
    }

    @Override
    protected int registerWithService() {
        return RemoteListenerHelper.RESULT_SUCCESS;
    }

    @Override
    protected void unregisterFromService() {}

    @Override
    protected ListenerOperation<IGnssStatusListener> getHandlerOperation(int result) {
        return null;
    }

    public void onStatusChanged(boolean isNavigating) {
        if (isNavigating) {
            foreach((IGnssStatusListener listener, CallerIdentity callerIdentity) -> {
                listener.onGnssStarted();
            });
        } else {
            foreach((IGnssStatusListener listener, CallerIdentity callerIdentity) -> {
                listener.onGnssStopped();
            });
        }
    }

    public void onFirstFix(final int timeToFirstFix) {
        foreach((IGnssStatusListener listener, CallerIdentity callerIdentity) -> {
                    listener.onFirstFix(timeToFirstFix);
                }
        );
    }

    public void onSvStatusChanged(
            final int svCount,
            final int[] prnWithFlags,
            final float[] cn0s,
            final float[] elevations,
            final float[] azimuths,
            final float[] carrierFreqs) {
        foreach((IGnssStatusListener listener, CallerIdentity callerIdentity) -> {
            if (!hasPermission(mContext, callerIdentity)) {
                logPermissionDisabledEventNotReported(TAG, callerIdentity.mPackageName,
                        "GNSS status");
                return;
            }
            listener.onSvStatusChanged(svCount, prnWithFlags, cn0s, elevations, azimuths,
                    carrierFreqs);
        });
    }

    public void onNmeaReceived(final long timestamp, final String nmea) {
        foreach((IGnssStatusListener listener, CallerIdentity callerIdentity) -> {
            if (!hasPermission(mContext, callerIdentity)) {
                logPermissionDisabledEventNotReported(TAG, callerIdentity.mPackageName, "NMEA");
                return;
            }
            listener.onNmeaReceived(timestamp, nmea);
        });
    }
}
