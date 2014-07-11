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

import com.android.internal.util.Preconditions;

import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/**
 * A handler class to manage transport listeners for {@link GpsMeasurementsEvent.Listener},
 * and post the events in a handler.
 *
 * @hide
 */
class GpsMeasurementListenerTransport {
    private static final String TAG = "GpsMeasurementListenerTransport";

    private final Context mContext;
    private final ILocationManager mLocationManager;

    private final IGpsMeasurementsListener mListenerTransport = new ListenerTransport();
    private final HashSet<GpsMeasurementsEvent.Listener> mListeners =
            new HashSet<GpsMeasurementsEvent.Listener>();

    public GpsMeasurementListenerTransport(Context context, ILocationManager locationManager) {
        mContext = context;
        mLocationManager = locationManager;
    }

    public boolean add(@NonNull GpsMeasurementsEvent.Listener listener) {
        Preconditions.checkNotNull(listener);

        synchronized (mListeners) {
            // we need to register with the service first, because we need to find out if the
            // service will actually support the request before we attempt anything
            if (mListeners.isEmpty()) {
                boolean registeredWithServer;
                try {
                    registeredWithServer = mLocationManager.addGpsMeasurementsListener(
                            mListenerTransport,
                            mContext.getPackageName());
                } catch (RemoteException e) {
                    Log.e(TAG, "Error handling first listener.", e);
                    return false;
                }

                if (!registeredWithServer) {
                    Log.e(TAG, "Unable to register listener transport.");
                    return false;
                }
            }

            if (mListeners.contains(listener)) {
                return true;
            }

            mListeners.add(listener);
        }

        return true;
    }

    public void remove(@NonNull GpsMeasurementsEvent.Listener listener) {
        Preconditions.checkNotNull(listener);

        synchronized (mListeners) {
            boolean removed = mListeners.remove(listener);

            boolean isLastListener = removed && mListeners.isEmpty();
            if (isLastListener) {
                try {
                    mLocationManager.removeGpsMeasurementsListener(mListenerTransport);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error handling last listener.", e);
                }
            }
        }
    }

    private class ListenerTransport extends IGpsMeasurementsListener.Stub {
        @Override
        public void onGpsMeasurementsReceived(final GpsMeasurementsEvent eventArgs) {
            Collection<GpsMeasurementsEvent.Listener> listeners;
            synchronized (mListeners) {
                listeners = new ArrayList<GpsMeasurementsEvent.Listener>(mListeners);
            }

            for (final GpsMeasurementsEvent.Listener listener : listeners) {
                listener.onGpsMeasurementsReceived(eventArgs);
            }
        }
    }
}
