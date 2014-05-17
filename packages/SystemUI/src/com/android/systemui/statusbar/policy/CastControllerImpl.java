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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;

import java.util.ArrayList;

/** Platform implementation of the cast controller. **/
public class CastControllerImpl implements CastController {

    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final MediaRouter mMediaRouter;

    private boolean mEnabled;
    private boolean mConnecting;
    private String mConnectedRouteName;

    public CastControllerImpl(Context context) {
        mMediaRouter = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
    }

    @Override
    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
        fireStateChanged(callback);
    }

    @Override
    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    @Override
    public void setDiscovering(boolean request) {
        if (request) {
            mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    mMediaCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
        } else {
            mMediaRouter.removeCallback(mMediaCallback);
        }
    }

    @Override
    public void setCurrentUserId(int currentUserId) {
        mMediaRouter.rebindAsUser(currentUserId);
    }

    private void updateRemoteDisplays() {
        final MediaRouter.RouteInfo connectedRoute = mMediaRouter.getSelectedRoute(
                MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean enabled = connectedRoute != null
                && connectedRoute.matchesTypes(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean connecting;
        if (enabled) {
            connecting = connectedRoute.isConnecting();
        } else {
            connecting = false;
            enabled = mMediaRouter.isRouteAvailable(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
        }

        String connectedRouteName = null;
        if (connectedRoute != null) {
            connectedRouteName = connectedRoute.getName().toString();
        }
        synchronized(mCallbacks) {
            mEnabled = enabled;
            mConnecting = connecting;
            mConnectedRouteName = connectedRouteName;
        }
        fireStateChanged();
    }

    private void fireStateChanged() {
        for (Callback callback : mCallbacks) {
            fireStateChanged(callback);
        }
    }

    private void fireStateChanged(Callback callback) {
        synchronized(mCallbacks) {
            callback.onStateChanged(mEnabled, mConnecting, mConnectedRouteName);
        }
    }

    private final MediaRouter.SimpleCallback mMediaCallback = new MediaRouter.SimpleCallback() {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
    };
}
