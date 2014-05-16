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
package android.media.session;

import android.text.TextUtils;
import android.util.Log;

import java.util.List;

/**
 * Represents a destination which an application has connected to and may send
 * media content.
 * <p>
 * This allows a session owner to interact with a route it has been connected
 * to. The MediaRoute must be used to get {@link RouteInterface}
 * instances which can be used to communicate over a specific interface on the
 * route.
 * @hide
 */
public final class Route {
    private static final String TAG = "Route";
    private final RouteInfo mInfo;
    private final MediaSession mSession;
    private final RouteOptions mOptions;

    /**
     * @hide
     */
    public Route(RouteInfo info, RouteOptions options, MediaSession session) {
        if (info == null || options == null) {
            throw new IllegalStateException("Route info was not valid!");
        }
        mInfo = info;
        mOptions = options;
        mSession = session;
    }

    /**
     * Get the {@link RouteInfo} for this route.
     *
     * @return The info for this route.
     */
    public RouteInfo getRouteInfo() {
        return mInfo;
    }

    /**
     * Get the {@link RouteOptions} that were used to connect this route.
     *
     * @return The options used to connect to this route.
     */
    public RouteOptions getOptions() {
        return mOptions;
    }

    /**
     * Gets an interface provided by this route. If the interface is not
     * supported by the route, returns null.
     *
     * @see RouteInterface
     * @param iface The name of the interface to create
     * @return A {@link RouteInterface} or null if the interface is
     *         not supported.
     */
    public RouteInterface getInterface(String iface) {
        if (TextUtils.isEmpty(iface)) {
            throw new IllegalArgumentException("iface may not be empty.");
        }
        List<String> ifaces = mOptions.getInterfaceNames();
        if (ifaces != null) {
            for (int i = ifaces.size() - 1; i >= 0; i--) {
                if (iface.equals(ifaces.get(i))) {
                    return new RouteInterface(this, iface, mSession);
                }
            }
        }
        Log.e(TAG, "Interface not supported by route");
        return null;
    }

    /**
     * @hide
     */
    MediaSession getSession() {
        return mSession;
    }
}
