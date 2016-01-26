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
 * A handler class to manage transport callback for {@link GpsNavigationMessageEvent.Callback}.
 *
 * @hide
 */
class GpsNavigationMessageCallbackTransport
        extends LocalListenerHelper<GpsNavigationMessageEvent.Callback> {
    private final ILocationManager mLocationManager;

    private final IGpsNavigationMessageListener mListenerTransport = new ListenerTransport();

    public GpsNavigationMessageCallbackTransport(
            Context context,
            ILocationManager locationManager) {
        super(context, "GpsNavigationMessageCallbackTransport");
        mLocationManager = locationManager;
    }

    @Override
    protected boolean registerWithServer() throws RemoteException {
        return mLocationManager.addGpsNavigationMessageListener(
                mListenerTransport,
                getContext().getPackageName());
    }

    @Override
    protected void unregisterFromServer() throws RemoteException {
        mLocationManager.removeGpsNavigationMessageListener(mListenerTransport);
    }

    private class ListenerTransport extends IGpsNavigationMessageListener.Stub {
        @Override
        public void onGpsNavigationMessageReceived(final GpsNavigationMessageEvent event) {
            ListenerOperation<GpsNavigationMessageEvent.Callback> operation =
                    new ListenerOperation<GpsNavigationMessageEvent.Callback>() {
                @Override
                public void execute(GpsNavigationMessageEvent.Callback callback)
                        throws RemoteException {
                    callback.onGpsNavigationMessageReceived(event);
                }
            };
            foreach(operation);
        }

        @Override
        public void onStatusChanged(final int status) {
            ListenerOperation<GpsNavigationMessageEvent.Callback> operation =
                    new ListenerOperation<GpsNavigationMessageEvent.Callback>() {
                @Override
                public void execute(GpsNavigationMessageEvent.Callback callback)
                        throws RemoteException {
                    callback.onStatusChanged(status);
                }
            };
            foreach(operation);
        }
    }
}
