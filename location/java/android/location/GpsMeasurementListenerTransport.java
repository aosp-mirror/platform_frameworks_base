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

package android.location;

import android.content.Context;
import android.os.RemoteException;

/**
 * A handler class to manage transport listeners for {@link GpsMeasurementsEvent.Listener}.
 *
 * @hide
 */
class GpsMeasurementListenerTransport
        extends LocalListenerHelper<GpsMeasurementsEvent.Listener> {
    private final ILocationManager mLocationManager;

    private final IGpsMeasurementsListener mListenerTransport = new ListenerTransport();

    public GpsMeasurementListenerTransport(Context context, ILocationManager locationManager) {
        super(context, "GpsMeasurementListenerTransport");
        mLocationManager = locationManager;
    }

    @Override
    protected boolean registerWithServer() throws RemoteException {
        return mLocationManager.addGpsMeasurementsListener(
                mListenerTransport,
                getContext().getPackageName());
    }

    @Override
    protected void unregisterFromServer() throws RemoteException {
        mLocationManager.removeGpsMeasurementsListener(mListenerTransport);
    }

    private class ListenerTransport extends IGpsMeasurementsListener.Stub {
        @Override
        public void onGpsMeasurementsReceived(final GpsMeasurementsEvent event) {
            ListenerOperation<GpsMeasurementsEvent.Listener> operation =
                    new ListenerOperation<GpsMeasurementsEvent.Listener>() {
                @Override
                public void execute(GpsMeasurementsEvent.Listener listener) throws RemoteException {
                    listener.onGpsMeasurementsReceived(event);
                }
            };
            foreach(operation);
        }

        @Override
        public void onStatusChanged(final int status) {
            ListenerOperation<GpsMeasurementsEvent.Listener> operation =
                    new ListenerOperation<GpsMeasurementsEvent.Listener>() {
                @Override
                public void execute(GpsMeasurementsEvent.Listener listener) throws RemoteException {
                    listener.onStatusChanged(status);
                }
            };
            foreach(operation);
        }
    }
}
