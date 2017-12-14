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

import android.location.IGnssStatusListener;
import android.os.Handler;
import android.os.RemoteException;

/**
 * Implementation of a handler for {@link IGnssStatusListener}.
 */
abstract class GnssStatusListenerHelper extends RemoteListenerHelper<IGnssStatusListener> {
    protected GnssStatusListenerHelper(Handler handler) {
        super(handler, "GnssStatusListenerHelper");
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
        Operation operation;
        if (isNavigating) {
            operation = new Operation() {
                @Override
                public void execute(IGnssStatusListener listener) throws RemoteException {
                    listener.onGnssStarted();
                }
            };
        } else {
            operation = new Operation() {
                @Override
                public void execute(IGnssStatusListener listener) throws RemoteException {
                    listener.onGnssStopped();
                }
            };
        }
        foreach(operation);
    }

    public void onFirstFix(final int timeToFirstFix) {
        Operation operation = new Operation() {
            @Override
            public void execute(IGnssStatusListener listener) throws RemoteException {
                listener.onFirstFix(timeToFirstFix);
            }
        };
        foreach(operation);
    }

    public void onSvStatusChanged(
            final int svCount,
            final int[] prnWithFlags,
            final float[] cn0s,
            final float[] elevations,
            final float[] azimuths,
            final float[] carrierFreqs) {
        Operation operation = new Operation() {
            @Override
            public void execute(IGnssStatusListener listener) throws RemoteException {
                listener.onSvStatusChanged(
                        svCount,
                        prnWithFlags,
                        cn0s,
                        elevations,
                        azimuths,
                        carrierFreqs);
            }
        };
        foreach(operation);
    }

    public void onNmeaReceived(final long timestamp, final String nmea) {
        Operation operation = new Operation() {
            @Override
            public void execute(IGnssStatusListener listener) throws RemoteException {
                listener.onNmeaReceived(timestamp, nmea);
            }
        };
        foreach(operation);
    }

    private interface Operation extends ListenerOperation<IGnssStatusListener> {}
}
