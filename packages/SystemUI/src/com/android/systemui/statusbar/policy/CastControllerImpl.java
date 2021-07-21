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

import static android.media.MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.Utils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;


/** Platform implementation of the cast controller. **/
@SysUISingleton
public class CastControllerImpl implements CastController {
    private static final String TAG = "CastController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    @GuardedBy("mCallbacks")
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final MediaRouter mMediaRouter;
    private final ArrayMap<String, RouteInfo> mRoutes = new ArrayMap<>();
    private final Object mDiscoveringLock = new Object();
    private final MediaProjectionManager mProjectionManager;
    private final Object mProjectionLock = new Object();

    private boolean mDiscovering;
    private boolean mCallbackRegistered;
    private MediaProjectionInfo mProjection;

    @Inject
    public CastControllerImpl(Context context, DumpManager dumpManager) {
        mContext = context;
        mMediaRouter = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mMediaRouter.setRouterGroupId(MediaRouter.MIRRORING_GROUP_ID);
        mProjectionManager = (MediaProjectionManager)
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mProjection = mProjectionManager.getActiveProjectionInfo();
        mProjectionManager.addCallback(mProjectionCallback, new Handler());
        dumpManager.registerDumpable(TAG, this);
        if (DEBUG) Log.d(TAG, "new CastController()");
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CastController state:");
        pw.print("  mDiscovering="); pw.println(mDiscovering);
        pw.print("  mCallbackRegistered="); pw.println(mCallbackRegistered);
        pw.print("  mCallbacks.size="); synchronized (mCallbacks) {pw.println(mCallbacks.size());}
        pw.print("  mRoutes.size="); pw.println(mRoutes.size());
        for (int i = 0; i < mRoutes.size(); i++) {
            final RouteInfo route = mRoutes.valueAt(i);
            pw.print("    "); pw.println(routeToString(route));
        }
        pw.print("  mProjection="); pw.println(mProjection);
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
        fireOnCastDevicesChanged(callback);
        synchronized (mDiscoveringLock) {
            handleDiscoveryChangeLocked();
        }
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
        synchronized (mDiscoveringLock) {
            handleDiscoveryChangeLocked();
        }
    }

    @Override
    public void setDiscovering(boolean request) {
        synchronized (mDiscoveringLock) {
            if (mDiscovering == request) return;
            mDiscovering = request;
            if (DEBUG) Log.d(TAG, "setDiscovering: " + request);
            handleDiscoveryChangeLocked();
        }
    }

    private void handleDiscoveryChangeLocked() {
        if (mCallbackRegistered) {
            mMediaRouter.removeCallback(mMediaCallback);
            mCallbackRegistered = false;
        }
        if (mDiscovering) {
            mMediaRouter.addCallback(ROUTE_TYPE_REMOTE_DISPLAY, mMediaCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
            mCallbackRegistered = true;
        } else {
            boolean hasCallbacks = false;
            synchronized (mCallbacks) {
                hasCallbacks = mCallbacks.isEmpty();
            }
            if (!hasCallbacks) {
                mMediaRouter.addCallback(ROUTE_TYPE_REMOTE_DISPLAY, mMediaCallback,
                        MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
                mCallbackRegistered = true;
            }
        }
    }

    @Override
    public void setCurrentUserId(int currentUserId) {
        mMediaRouter.rebindAsUser(currentUserId);
    }

    @Override
    public List<CastDevice> getCastDevices() {
        final ArrayList<CastDevice> devices = new ArrayList<>();
        synchronized(mRoutes) {
            for (RouteInfo route : mRoutes.values()) {
                final CastDevice device = new CastDevice();
                device.id = route.getTag().toString();
                final CharSequence name = route.getName(mContext);
                device.name = name != null ? name.toString() : null;
                final CharSequence description = route.getDescription();
                device.description = description != null ? description.toString() : null;

                int statusCode = route.getStatusCode();
                if (statusCode == RouteInfo.STATUS_CONNECTING) {
                    device.state = CastDevice.STATE_CONNECTING;
                } else if (route.isSelected() || statusCode == RouteInfo.STATUS_CONNECTED) {
                    device.state = CastDevice.STATE_CONNECTED;
                } else {
                    device.state = CastDevice.STATE_DISCONNECTED;
                }

                device.tag = route;
                devices.add(device);
            }
        }

        synchronized (mProjectionLock) {
            if (mProjection != null) {
                final CastDevice device = new CastDevice();
                device.id = mProjection.getPackageName();
                device.name = getAppName(mProjection.getPackageName());
                device.description = mContext.getString(R.string.quick_settings_casting);
                device.state = CastDevice.STATE_CONNECTED;
                device.tag = mProjection;
                devices.add(device);
            }
        }

        return devices;
    }

    @Override
    public void startCasting(CastDevice device) {
        if (device == null || device.tag == null) return;
        final RouteInfo route = (RouteInfo) device.tag;
        if (DEBUG) Log.d(TAG, "startCasting: " + routeToString(route));
        mMediaRouter.selectRoute(ROUTE_TYPE_REMOTE_DISPLAY, route);
    }

    @Override
    public void stopCasting(CastDevice device) {
        final boolean isProjection = device.tag instanceof MediaProjectionInfo;
        if (DEBUG) Log.d(TAG, "stopCasting isProjection=" + isProjection);
        if (isProjection) {
            final MediaProjectionInfo projection = (MediaProjectionInfo) device.tag;
            if (Objects.equals(mProjectionManager.getActiveProjectionInfo(), projection)) {
                mProjectionManager.stopActiveProjection();
            } else {
                Log.w(TAG, "Projection is no longer active: " + projection);
            }
        } else {
            mMediaRouter.getFallbackRoute().select();
        }
    }

    private void setProjection(MediaProjectionInfo projection, boolean started) {
        boolean changed = false;
        final MediaProjectionInfo oldProjection = mProjection;
        synchronized (mProjectionLock) {
            final boolean isCurrent = Objects.equals(projection, mProjection);
            if (started && !isCurrent) {
                mProjection = projection;
                changed = true;
            } else if (!started && isCurrent) {
                mProjection = null;
                changed = true;
            }
        }
        if (changed) {
            if (DEBUG) Log.d(TAG, "setProjection: " + oldProjection + " -> " + mProjection);
            fireOnCastDevicesChanged();
        }
    }

    private String getAppName(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        if (Utils.isHeadlessRemoteDisplayProvider(pm, packageName)) {
            return "";
        }

        try {
            final ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            if (appInfo != null) {
                final CharSequence label = appInfo.loadLabel(pm);
                if (!TextUtils.isEmpty(label)) {
                    return label.toString();
                }
            }
            Log.w(TAG, "No label found for package: " + packageName);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Error getting appName for package: " + packageName, e);
        }
        return packageName;
    }

    private void updateRemoteDisplays() {
        synchronized(mRoutes) {
            mRoutes.clear();
            final int n = mMediaRouter.getRouteCount();
            for (int i = 0; i < n; i++) {
                final RouteInfo route = mMediaRouter.getRouteAt(i);
                if (!route.isEnabled()) continue;
                if (!route.matchesTypes(ROUTE_TYPE_REMOTE_DISPLAY)) continue;
                ensureTagExists(route);
                mRoutes.put(route.getTag().toString(), route);
            }
            final RouteInfo selected = mMediaRouter.getSelectedRoute(ROUTE_TYPE_REMOTE_DISPLAY);
            if (selected != null && !selected.isDefault()) {
                ensureTagExists(selected);
                mRoutes.put(selected.getTag().toString(), selected);
            }
        }
        fireOnCastDevicesChanged();
    }

    private void ensureTagExists(RouteInfo route) {
        if (route.getTag() == null) {
            route.setTag(UUID.randomUUID().toString());
        }
    }

    @VisibleForTesting
    void fireOnCastDevicesChanged() {
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                fireOnCastDevicesChanged(callback);
            }

        }
    }


    private void fireOnCastDevicesChanged(Callback callback) {
        callback.onCastDevicesChanged();
    }

    private static String routeToString(RouteInfo route) {
        if (route == null) return null;
        final StringBuilder sb = new StringBuilder().append(route.getName()).append('/')
                .append(route.getDescription()).append('@').append(route.getDeviceAddress())
                .append(",status=").append(route.getStatus());
        if (route.isDefault()) sb.append(",default");
        if (route.isEnabled()) sb.append(",enabled");
        if (route.isConnecting()) sb.append(",connecting");
        if (route.isSelected()) sb.append(",selected");
        return sb.append(",id=").append(route.getTag()).toString();
    }

    private final MediaRouter.SimpleCallback mMediaCallback = new MediaRouter.SimpleCallback() {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
            if (DEBUG) Log.d(TAG, "onRouteAdded: " + routeToString(route));
            updateRemoteDisplays();
        }
        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
            if (DEBUG) Log.d(TAG, "onRouteChanged: " + routeToString(route));
            updateRemoteDisplays();
        }
        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
            if (DEBUG) Log.d(TAG, "onRouteRemoved: " + routeToString(route));
            updateRemoteDisplays();
        }
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo route) {
            if (DEBUG) Log.d(TAG, "onRouteSelected(" + type + "): " + routeToString(route));
            updateRemoteDisplays();
        }
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo route) {
            if (DEBUG) Log.d(TAG, "onRouteUnselected(" + type + "): " + routeToString(route));
            updateRemoteDisplays();
        }
    };

    private final MediaProjectionManager.Callback mProjectionCallback
            = new MediaProjectionManager.Callback() {
        @Override
        public void onStart(MediaProjectionInfo info) {
            setProjection(info, true);
        }

        @Override
        public void onStop(MediaProjectionInfo info) {
            setProjection(info, false);
        }
    };
}
