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

import android.location.IGpsStatusListener;
import android.os.RemoteException;

/**
 * Implementation of a handler for {@link IGpsStatusListener}.
 */
abstract class GpsStatusListenerHelper extends RemoteListenerHelper<IGpsStatusListener> {
    public GpsStatusListenerHelper() {
        super("GpsStatusListenerHelper");
    }

    public void onFirstFix(final int timeToFirstFix) {
        Operation operation = new Operation() {
            @Override
            public void execute(IGpsStatusListener listener) throws RemoteException {
                listener.onFirstFix(timeToFirstFix);
            }
        };

        foreach(operation);
    }

    public void onStatusChanged(final boolean isNavigating) {
        Operation operation = new Operation() {
            @Override
            public void execute(IGpsStatusListener listener) throws RemoteException {
                if (isNavigating) {
                    listener.onGpsStarted();
                } else {
                    listener.onGpsStopped();
                }
            }
        };

        foreach(operation);
    }

    public void onSvStatusChanged(
            final int svCount,
            final int[] prns,
            final float[] snrs,
            final float[] elevations,
            final float[] azimuths,
            final int ephemerisMask,
            final int almanacMask,
            final int usedInFixMask) {
        Operation operation = new Operation() {
            @Override
            public void execute(IGpsStatusListener listener) throws RemoteException {
                listener.onSvStatusChanged(
                        svCount,
                        prns,
                        snrs,
                        elevations,
                        azimuths,
                        ephemerisMask,
                        almanacMask,
                        usedInFixMask);
            }
        };

        foreach(operation);
    }

    public void onNmeaReceived(final long timestamp, final String nmea) {
        Operation operation = new Operation() {
            @Override
            public void execute(IGpsStatusListener listener) throws RemoteException {
                listener.onNmeaReceived(timestamp, nmea);
            }
        };

        foreach(operation);
    }

    private abstract class Operation implements ListenerOperation<IGpsStatusListener> { }
}
