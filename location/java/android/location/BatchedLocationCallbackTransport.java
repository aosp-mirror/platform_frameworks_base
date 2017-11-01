/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.util.List;

/**
 * A handler class to manage transport callbacks for {@link BatchedLocationCallback}.
 *
 * @hide
 */
class BatchedLocationCallbackTransport
        extends LocalListenerHelper<BatchedLocationCallback> {
    private final ILocationManager mLocationManager;

    private final IBatchedLocationCallback mCallbackTransport = new CallbackTransport();

    public BatchedLocationCallbackTransport(Context context, ILocationManager locationManager) {
        super(context, "BatchedLocationCallbackTransport");
        mLocationManager = locationManager;
    }

    @Override
    protected boolean registerWithServer() throws RemoteException {
        return mLocationManager.addGnssBatchingCallback(
                mCallbackTransport,
                getContext().getPackageName());
    }

    @Override
    protected void unregisterFromServer() throws RemoteException {
        mLocationManager.removeGnssBatchingCallback();
    }

    private class CallbackTransport extends IBatchedLocationCallback.Stub {
        @Override
        public void onLocationBatch(final List<Location> locations) {
            ListenerOperation<BatchedLocationCallback> operation =
                    new ListenerOperation<BatchedLocationCallback>() {
                @Override
                public void execute(BatchedLocationCallback callback)
                        throws RemoteException {
                    callback.onLocationBatch(locations);
                }
            };
            foreach(operation);
        }
    }
}
