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
 * limitations under the License.
 */
package com.android.server.media;

import android.media.routeprovider.IRouteConnection;
import android.media.session.RouteCommand;
import android.media.session.RouteEvent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;

/**
 * A connection between a Session and a Route.
 */
public class RouteConnectionRecord {
    private static final String TAG = "RouteConnRecord";
    private final IRouteConnection mBinder;
    private Listener mListener;

    public RouteConnectionRecord(IRouteConnection binder) {
        mBinder = binder;
    }

    /**
     * Add a listener to get route events on.
     *
     * @param listener The listener to get events on.
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Check if this connection matches the token given.
     *
     * @param binder The token to check
     * @return True if this is the connection you're looking for, false
     *         otherwise.
     */
    public boolean isConnection(IBinder binder) {
        return binder != null && binder.equals(mBinder.asBinder());
    }

    /**
     * Send an event from this connection.
     *
     * @param event The event to send.
     */
    public void sendEvent(RouteEvent event) {
        if (mListener != null) {
            mListener.onEvent(event);
        }
    }

    /**
     * Send a command to this connection.
     *
     * @param command The command to send.
     * @param cb The receiver to get a result on.
     */
    public void sendCommand(RouteCommand command, ResultReceiver cb) {
        try {
            mBinder.onCommand(command, cb);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in sendCommand", e);
        }
    }

    /**
     * Tell the session that the provider has disconnected it.
     */
    public void disconnect() {
        if (mListener != null) {
            mListener.disconnect();
        }
    }

    /**
     * Listener to receive updates from the provider for this connection.
     */
    public static interface Listener {
        /**
         * Called when an event is sent on this connection.
         *
         * @param event The event that was sent.
         */
        public void onEvent(RouteEvent event);

        /**
         * Called when the provider has disconnected the route.
         */
        public void disconnect();
    }
}