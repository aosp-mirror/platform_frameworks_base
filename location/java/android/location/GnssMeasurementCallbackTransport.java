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

import com.android.internal.util.Preconditions;

/**
 * A handler class to manage transport callbacks for {@link GnssMeasurementsEvent.Callback}.
 *
 * @hide
 */
class GnssMeasurementCallbackTransport
        extends LocalListenerHelper<GnssMeasurementsEvent.Callback> {
    private static final String TAG = "GnssMeasCbTransport";
    private final ILocationManager mLocationManager;

    private final IGnssMeasurementsListener mListenerTransport = new ListenerTransport();

    public GnssMeasurementCallbackTransport(Context context, ILocationManager locationManager) {
        super(context, TAG);
        mLocationManager = locationManager;
    }

    @Override
    protected boolean registerWithServer() throws RemoteException {
        return mLocationManager.addGnssMeasurementsListener(
                mListenerTransport,
                getContext().getPackageName());
    }

    @Override
    protected void unregisterFromServer() throws RemoteException {
        mLocationManager.removeGnssMeasurementsListener(mListenerTransport);
    }

    /**
     * Injects GNSS measurement corrections into the GNSS chipset.
     *
     * @param measurementCorrections a {@link GnssMeasurementCorrections} object with the GNSS
     *     measurement corrections to be injected into the GNSS chipset.
     */
    protected void injectGnssMeasurementCorrections(
            GnssMeasurementCorrections measurementCorrections) throws RemoteException {
        Preconditions.checkNotNull(measurementCorrections);
        mLocationManager.injectGnssMeasurementCorrections(
                measurementCorrections, getContext().getPackageName());
    }

    protected long getGnssCapabilities() throws RemoteException {
        return mLocationManager.getGnssCapabilities(getContext().getPackageName());
    }

    private class ListenerTransport extends IGnssMeasurementsListener.Stub {
        @Override
        public void onGnssMeasurementsReceived(final GnssMeasurementsEvent event) {
            ListenerOperation<GnssMeasurementsEvent.Callback> operation =
                    new ListenerOperation<GnssMeasurementsEvent.Callback>() {
                        @Override
                        public void execute(GnssMeasurementsEvent.Callback callback)
                                throws RemoteException {
                            callback.onGnssMeasurementsReceived(event);
                        }
                    };
            foreach(operation);
        }

        @Override
        public void onStatusChanged(final int status) {
            ListenerOperation<GnssMeasurementsEvent.Callback> operation =
                    new ListenerOperation<GnssMeasurementsEvent.Callback>() {
                @Override
                public void execute(GnssMeasurementsEvent.Callback callback)
                        throws RemoteException {
                    callback.onStatusChanged(status);
                }
            };
            foreach(operation);
        }
    }
}
