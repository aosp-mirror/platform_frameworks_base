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

import android.location.GpsNavigationMessageEvent;
import android.location.IGpsNavigationMessageListener;
import android.os.RemoteException;

/**
 * An base implementation for GPS navigation messages provider.
 * It abstracts out the responsibility of handling listeners, while still allowing technology
 * specific implementations to be built.
 *
 * @hide
 */
public abstract class GpsNavigationMessageProvider
        extends RemoteListenerHelper<IGpsNavigationMessageListener> {
    public GpsNavigationMessageProvider() {
        super("GpsNavigationMessageProvider");
    }

    public void onNavigationMessageAvailable(final GpsNavigationMessageEvent event) {
        ListenerOperation<IGpsNavigationMessageListener> operation =
                new ListenerOperation<IGpsNavigationMessageListener>() {
                    @Override
                    public void execute(IGpsNavigationMessageListener listener)
                            throws RemoteException {
                        listener.onGpsNavigationMessageReceived(event);
                    }
                };

        foreach(operation);
    }
}
