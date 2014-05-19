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
package android.media.routeprovider;

import android.media.routeprovider.IRouteConnection;
import android.media.session.RouteCommand;
import android.media.session.RouteEvent;
import android.media.session.RouteInfo;
import android.media.session.RouteInterface;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an ongoing connection between an application and a media route
 * offered by a media route provider.
 * <p>
 * The media route provider should add interfaces to the connection before
 * returning it to the system in order to receive commands from clients on those
 * interfaces. Use {@link #addRouteInterface(String)} to add an interface and
 * {@link #getRouteInterface(String)} to retrieve the interface's handle anytime
 * after it has been added.
 * @hide
 */
public final class RouteConnection {
    private static final String TAG = "RouteConnection";
    private final ConnectionStub mBinder;
    private final ArrayList<String> mIfaceNames = new ArrayList<String>();
    private final ArrayMap<String, RouteInterfaceHandler> mIfaces
            = new ArrayMap<String, RouteInterfaceHandler>();
    private final RouteProviderService mProvider;
    private final RouteInfo mRoute;

    private boolean mPublished;

    /**
     * Create a new connection for the given Provider and Route.
     *
     * @param provider The provider this route is associated with.
     * @param route The route this is a connection to.
     */
    public RouteConnection(RouteProviderService provider, RouteInfo route) {
        if (provider == null) {
            throw new IllegalArgumentException("provider may not be null.");
        }
        if (route == null) {
            throw new IllegalArgumentException("route may not be null.");
        }
        mBinder = new ConnectionStub(this);
        mProvider = provider;
        mRoute = route;
    }

    /**
     * Add an interface to this route connection. All interfaces must be added
     * to the connection before the connection is returned to the system.
     *
     * @param ifaceName The name of the interface to add
     * @return The route interface that was registered
     */
    public RouteInterfaceHandler addRouteInterface(String ifaceName) {
        if (TextUtils.isEmpty(ifaceName)) {
            throw new IllegalArgumentException("The interface's name may not be empty");
        }
        if (mPublished) {
            throw new IllegalStateException(
                    "Connection has already been published to the system.");
        }
        RouteInterfaceHandler iface = mIfaces.get(ifaceName);
        if (iface == null) {
            iface = new RouteInterfaceHandler(this, ifaceName);
            mIfaceNames.add(ifaceName);
            mIfaces.put(ifaceName, iface);
        } else {
            Log.w(TAG, "Attempted to add an interface that already exists");
        }
        return iface;
    }

    /**
     * Get the interface instance for the specified interface name. If the
     * interface was not added to this connection null will be returned.
     *
     * @param ifaceName The name of the interface to get.
     * @return The route interface with that name or null.
     */
    public RouteInterfaceHandler getRouteInterface(String ifaceName) {
        return mIfaces.get(ifaceName);
    }

    /**
     * Close the connection and inform the system that it may no longer be used.
     */
    public void shutDown() {
        mProvider.disconnect(this);
    }

    /**
     * @hide
     */
    public void sendEvent(String iface, String event, Bundle extras) {
        RouteEvent e = new RouteEvent(mBinder, iface, event, extras);
        mProvider.sendRouteEvent(e);
    }

    /**
     * @hide
     */
    IRouteConnection.Stub getBinder() {
        return mBinder;
    }

    /**
     * @hide
     */
    void publish() {
        mPublished = true;
    }

    private static class ConnectionStub extends IRouteConnection.Stub {
        private final WeakReference<RouteConnection> mConnection;

        public ConnectionStub(RouteConnection connection) {
            mConnection = new WeakReference<RouteConnection>(connection);
        }

        @Override
        public void onCommand(RouteCommand command, ResultReceiver cb) {
            RouteConnection connection = mConnection.get();
            if (connection != null) {
                RouteInterfaceHandler iface = connection.mIfaces.get(command.getIface());
                if (iface != null) {
                    iface.onCommand(command.getEvent(), command.getExtras(), cb);
                } else if (cb != null) {
                    cb.send(RouteInterface.RESULT_INTERFACE_NOT_SUPPORTED, null);
                }
            }
        }

        @Override
        public void disconnect() {
            // TODO
        }
    }
}
