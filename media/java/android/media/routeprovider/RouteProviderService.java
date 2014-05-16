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

import android.app.Service;
import android.content.Intent;
import android.media.routeprovider.IRouteProvider;
import android.media.routeprovider.IRouteProviderCallback;
import android.media.session.RouteEvent;
import android.media.session.RouteInfo;
import android.media.session.RouteOptions;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for defining a route provider service.
 * <p>
 * A route provider offers media routes which represent destinations to which
 * applications may connect, control, and send content. This provides a means
 * for Android applications to interact with a variety of media streaming
 * devices such as speakers or television sets.
 * <p>
 * The system will bind to your provider when an active app is interested in
 * routes that may be discovered through your provider. After binding, the
 * system will send updates on which routes to discover through
 * {@link #updateDiscoveryRequests(List)}. The system will call
 * {@link #getMatchingRoutes(List)} with a subset of filters when a route is
 * needed for a specific app.
 * <p>
 * TODO add documentation for how the sytem knows an app is interested. Maybe
 * interface declarations in the manifest.
 * <p>
 * The system will only start a provider when an app may discover routes through
 * it. If your service needs to run at other times you are responsible for
 * managing its lifecycle.
 * <p>
 * Declare your route provider service in your application manifest like this:
 * <p>
 *
 * <pre>
 *   &lt;service android:name=".MyRouteProviderService"
 *           android:label="@string/my_route_provider_service">
 *       &lt;intent-filter>
 *           &lt;action android:name="com.android.media.session.MediaRouteProvider" />
 *       &lt;/intent-filter>
 *   &lt;/service>
 * </pre>
 * @hide
 */
public abstract class RouteProviderService extends Service {
    private static final String TAG = "RouteProvider";
    /**
     * A service that implements a RouteProvider must declare that it handles
     * this action in its AndroidManifest.
     */
    public static final String SERVICE_INTERFACE =
            "com.android.media.session.MediaRouteProvider";

    /**
     * @hide
     */
    public static final String KEY_ROUTES = "routes";
    /**
     * @hide
     */
    public static final String KEY_CONNECTION = "connection";
    /**
     * @hide
     */
    public static final int RESULT_FAILURE = -1;
    /**
     * @hide
     */
    public static final int RESULT_SUCCESS = 0;

    // The system's callback once it has bound to the service
    private IRouteProviderCallback mCb;

    /**
     * If your service overrides onBind it must return super.onBind() in
     * response to the {@link #SERVICE_INTERFACE} action.
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && RouteProviderService.SERVICE_INTERFACE.equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    /**
     * Disconnect the specified RouteConnection. The system will stop sending
     * commands to this connection.
     *
     * @param connection The connection to disconnect.
     * @hide
     */
    public final void disconnect(RouteConnection connection) {
        if (mCb != null) {
            try {
                mCb.onConnectionTerminated(connection.getBinder());
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error in disconnect.", e);
            }
        }
    }

    /**
     * @hide
     */
    public final void sendRouteEvent(RouteEvent event) {
        if (mCb != null) {
            try {
                mCb.onRouteEvent(event);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Unable to send MediaRouteEvent to system", e);
            }
        }
    }

    /**
     * Override to handle updates to the routes that are of interest. Each
     * {@link RouteRequest} will specify if it is an active or passive request.
     * Route discovery may perform more aggressive discovery on behalf of active
     * requests but should use low power discovery methods otherwise.
     * <p>
     * A single app may have more than one request. Your provider is responsible
     * for deciding the set of features that are important for discovery given
     * the set of requests. If your provider only has one method of discovery it
     * may simply verify that one or more requests are valid before starting
     * discovery.
     *
     * @param requests The route requests that are currently relevant.
     */
    public void updateDiscoveryRequests(List<RouteRequest> requests) {
    }

    /**
     * Return a list of matching routes for the given set of requests. Returning
     * null or an empty list indicates there are no matches. A route is
     * considered matching if it supports one or more of the
     * {@link RouteOptions} specified. Each returned {@link RouteInfo}
     * should include all the requested connections that it supports.
     *
     * @param options The set of requests for routes
     * @return The routes that this caller may connect to using one or more of
     *         the route options.
     */
    public abstract List<RouteInfo> getMatchingRoutes(List<RouteRequest> options);

    /**
     * Handle a request to connect to a specific route with a specific request.
     * The {@link RouteConnection} must be fully defined before being returned,
     * though the actual connection to the route may be performed in the
     * background.
     *
     * @param route The route to connect to
     * @param request The connection request parameters
     * @return A MediaRouteConnection representing the connection to the route
     */
    public abstract RouteConnection connect(RouteInfo route, RouteRequest request);

    private IRouteProvider.Stub mBinder = new IRouteProvider.Stub() {

        @Override
        public void registerCallback(IRouteProviderCallback cb) throws RemoteException {
            mCb = cb;
        }

        @Override
        public void unregisterCallback(IRouteProviderCallback cb) throws RemoteException {
            mCb = null;
        }

        @Override
        public void updateDiscoveryRequests(List<RouteRequest> requests)
                throws RemoteException {
            RouteProviderService.this.updateDiscoveryRequests(requests);
        }

        @Override
        public void getAvailableRoutes(List<RouteRequest> requests, ResultReceiver cb)
                throws RemoteException {
            List<RouteInfo> routes = RouteProviderService.this.getMatchingRoutes(requests);
            ArrayList<RouteInfo> routesArray;
            if (routes instanceof ArrayList) {
                routesArray = (ArrayList<RouteInfo>) routes;
            } else {
                routesArray = new ArrayList<RouteInfo>(routes);
            }
            Bundle resultData = new Bundle();
            resultData.putParcelableArrayList(KEY_ROUTES, routesArray);
            cb.send(routes == null ? RESULT_FAILURE : RESULT_SUCCESS, resultData);
        }

        @Override
        public void connect(RouteInfo route, RouteRequest request, ResultReceiver cb)
                throws RemoteException {
            RouteConnection connection = RouteProviderService.this.connect(route, request);
            Bundle resultData = new Bundle();
            if (connection != null) {
                connection.publish();
                resultData.putBinder(KEY_CONNECTION, connection.getBinder());
            }

            cb.send(connection == null ? RESULT_FAILURE : RESULT_SUCCESS, resultData);
        }
    };
}
