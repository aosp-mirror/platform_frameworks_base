/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MediaRouter allows applications to control the routing of media channels
 * and streams from the current device to external speakers and destination devices.
 *
 * <p>A MediaRouter is retrieved through {@link Context#getSystemService(String)
 * Context.getSystemService()} of a {@link Context#MEDIA_ROUTER_SERVICE
 * Context.MEDIA_ROUTER_SERVICE}.
 *
 * <p>The media router API is not thread-safe; all interactions with it must be
 * done from the main thread of the process.</p>
 */
public class MediaRouter {
    private static final String TAG = "MediaRouter";

    static class Static implements DisplayManager.DisplayListener {
        final Resources mResources;
        final IAudioService mAudioService;
        final DisplayManager mDisplayService;
        final Handler mHandler;
        final CopyOnWriteArrayList<CallbackInfo> mCallbacks =
                new CopyOnWriteArrayList<CallbackInfo>();

        final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        final ArrayList<RouteCategory> mCategories = new ArrayList<RouteCategory>();

        final RouteCategory mSystemCategory;

        final AudioRoutesInfo mCurAudioRoutesInfo = new AudioRoutesInfo();

        RouteInfo mDefaultAudioVideo;
        RouteInfo mBluetoothA2dpRoute;

        RouteInfo mSelectedRoute;

        WifiDisplayStatus mLastKnownWifiDisplayStatus;

        final IAudioRoutesObserver.Stub mAudioRoutesObserver = new IAudioRoutesObserver.Stub() {
            public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        updateAudioRoutes(newRoutes);
                    }
                });
            }
        };

        Static(Context appContext) {
            mResources = Resources.getSystem();
            mHandler = new Handler(appContext.getMainLooper());

            IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
            mAudioService = IAudioService.Stub.asInterface(b);

            mDisplayService = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);

            mSystemCategory = new RouteCategory(
                    com.android.internal.R.string.default_audio_route_category_name,
                    ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO, false);
            mSystemCategory.mIsSystem = true;
        }

        // Called after sStatic is initialized
        void startMonitoringRoutes(Context appContext) {
            mDefaultAudioVideo = new RouteInfo(mSystemCategory);
            mDefaultAudioVideo.mNameResId = com.android.internal.R.string.default_audio_route_name;
            mDefaultAudioVideo.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO;
            mDefaultAudioVideo.mPresentationDisplay = choosePresentationDisplayForRoute(
                    mDefaultAudioVideo, getAllPresentationDisplays());
            addRouteStatic(mDefaultAudioVideo);

            // This will select the active wifi display route if there is one.
            updateWifiDisplayStatus(mDisplayService.getWifiDisplayStatus());

            appContext.registerReceiver(new WifiDisplayStatusChangedReceiver(),
                    new IntentFilter(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED));
            appContext.registerReceiver(new VolumeChangeReceiver(),
                    new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION));

            mDisplayService.registerDisplayListener(this, mHandler);

            AudioRoutesInfo newAudioRoutes = null;
            try {
                newAudioRoutes = mAudioService.startWatchingRoutes(mAudioRoutesObserver);
            } catch (RemoteException e) {
            }
            if (newAudioRoutes != null) {
                // This will select the active BT route if there is one and the current
                // selected route is the default system route, or if there is no selected
                // route yet.
                updateAudioRoutes(newAudioRoutes);
            }

            // Select the default route if the above didn't sync us up
            // appropriately with relevant system state.
            if (mSelectedRoute == null) {
                selectRouteStatic(mDefaultAudioVideo.getSupportedTypes(), mDefaultAudioVideo);
            }
        }

        void updateAudioRoutes(AudioRoutesInfo newRoutes) {
            if (newRoutes.mMainType != mCurAudioRoutesInfo.mMainType) {
                mCurAudioRoutesInfo.mMainType = newRoutes.mMainType;
                int name;
                if ((newRoutes.mMainType&AudioRoutesInfo.MAIN_HEADPHONES) != 0
                        || (newRoutes.mMainType&AudioRoutesInfo.MAIN_HEADSET) != 0) {
                    name = com.android.internal.R.string.default_audio_route_name_headphones;
                } else if ((newRoutes.mMainType&AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
                    name = com.android.internal.R.string.default_audio_route_name_dock_speakers;
                } else if ((newRoutes.mMainType&AudioRoutesInfo.MAIN_HDMI) != 0) {
                    name = com.android.internal.R.string.default_media_route_name_hdmi;
                } else {
                    name = com.android.internal.R.string.default_audio_route_name;
                }
                sStatic.mDefaultAudioVideo.mNameResId = name;
                dispatchRouteChanged(sStatic.mDefaultAudioVideo);
            }

            final int mainType = mCurAudioRoutesInfo.mMainType;

            boolean a2dpEnabled;
            try {
                a2dpEnabled = mAudioService.isBluetoothA2dpOn();
            } catch (RemoteException e) {
                Log.e(TAG, "Error querying Bluetooth A2DP state", e);
                a2dpEnabled = false;
            }

            if (!TextUtils.equals(newRoutes.mBluetoothName, mCurAudioRoutesInfo.mBluetoothName)) {
                mCurAudioRoutesInfo.mBluetoothName = newRoutes.mBluetoothName;
                if (mCurAudioRoutesInfo.mBluetoothName != null) {
                    if (sStatic.mBluetoothA2dpRoute == null) {
                        final RouteInfo info = new RouteInfo(sStatic.mSystemCategory);
                        info.mName = mCurAudioRoutesInfo.mBluetoothName;
                        info.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO;
                        sStatic.mBluetoothA2dpRoute = info;
                        addRouteStatic(sStatic.mBluetoothA2dpRoute);
                    } else {
                        sStatic.mBluetoothA2dpRoute.mName = mCurAudioRoutesInfo.mBluetoothName;
                        dispatchRouteChanged(sStatic.mBluetoothA2dpRoute);
                    }
                } else if (sStatic.mBluetoothA2dpRoute != null) {
                    removeRoute(sStatic.mBluetoothA2dpRoute);
                    sStatic.mBluetoothA2dpRoute = null;
                }
            }

            if (mBluetoothA2dpRoute != null) {
                if (mainType != AudioRoutesInfo.MAIN_SPEAKER &&
                        mSelectedRoute == mBluetoothA2dpRoute && !a2dpEnabled) {
                    selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO, mDefaultAudioVideo);
                } else if ((mSelectedRoute == mDefaultAudioVideo || mSelectedRoute == null) &&
                        a2dpEnabled) {
                    selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO, mBluetoothA2dpRoute);
                }
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            updatePresentationDisplays(displayId);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updatePresentationDisplays(displayId);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            updatePresentationDisplays(displayId);
        }

        public Display[] getAllPresentationDisplays() {
            return mDisplayService.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        }

        private void updatePresentationDisplays(int changedDisplayId) {
            final Display[] displays = getAllPresentationDisplays();
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                final RouteInfo info = mRoutes.get(i);
                Display display = choosePresentationDisplayForRoute(info, displays);
                if (display != info.mPresentationDisplay
                        || (display != null && display.getDisplayId() == changedDisplayId)) {
                    info.mPresentationDisplay = display;
                    dispatchRoutePresentationDisplayChanged(info);
                }
            }
        }
    }

    static Static sStatic;

    /**
     * Route type flag for live audio.
     *
     * <p>A device that supports live audio routing will allow the media audio stream
     * to be routed to supported destinations. This can include internal speakers or
     * audio jacks on the device itself, A2DP devices, and more.</p>
     *
     * <p>Once initiated this routing is transparent to the application. All audio
     * played on the media stream will be routed to the selected destination.</p>
     */
    public static final int ROUTE_TYPE_LIVE_AUDIO = 0x1;

    /**
     * Route type flag for live video.
     *
     * <p>A device that supports live video routing will allow a mirrored version
     * of the device's primary display or a customized
     * {@link android.app.Presentation Presentation} to be routed to supported destinations.</p>
     *
     * <p>Once initiated, display mirroring is transparent to the application.
     * While remote routing is active the application may use a
     * {@link android.app.Presentation Presentation} to replace the mirrored view
     * on the external display with different content.</p>
     *
     * @see RouteInfo#getPresentationDisplay()
     * @see android.app.Presentation
     */
    public static final int ROUTE_TYPE_LIVE_VIDEO = 0x2;

    /**
     * Route type flag for application-specific usage.
     *
     * <p>Unlike other media route types, user routes are managed by the application.
     * The MediaRouter will manage and dispatch events for user routes, but the application
     * is expected to interpret the meaning of these events and perform the requested
     * routing tasks.</p>
     */
    public static final int ROUTE_TYPE_USER = 0x00800000;

    // Maps application contexts
    static final HashMap<Context, MediaRouter> sRouters = new HashMap<Context, MediaRouter>();

    static String typesToString(int types) {
        final StringBuilder result = new StringBuilder();
        if ((types & ROUTE_TYPE_LIVE_AUDIO) != 0) {
            result.append("ROUTE_TYPE_LIVE_AUDIO ");
        }
        if ((types & ROUTE_TYPE_LIVE_VIDEO) != 0) {
            result.append("ROUTE_TYPE_LIVE_VIDEO ");
        }
        if ((types & ROUTE_TYPE_USER) != 0) {
            result.append("ROUTE_TYPE_USER ");
        }
        return result.toString();
    }

    /** @hide */
    public MediaRouter(Context context) {
        synchronized (Static.class) {
            if (sStatic == null) {
                final Context appContext = context.getApplicationContext();
                sStatic = new Static(appContext);
                sStatic.startMonitoringRoutes(appContext);
            }
        }
    }

    /**
     * @hide for use by framework routing UI
     */
    public RouteInfo getSystemAudioRoute() {
        return sStatic.mDefaultAudioVideo;
    }

    /**
     * @hide for use by framework routing UI
     */
    public RouteCategory getSystemAudioCategory() {
        return sStatic.mSystemCategory;
    }

    /**
     * Return the currently selected route for any of the given types
     *
     * @param type route types
     * @return the selected route
     */
    public RouteInfo getSelectedRoute(int type) {
        if (sStatic.mSelectedRoute != null &&
                (sStatic.mSelectedRoute.mSupportedTypes & type) != 0) {
            // If the selected route supports any of the types supplied, it's still considered
            // 'selected' for that type.
            return sStatic.mSelectedRoute;
        } else if (type == ROUTE_TYPE_USER) {
            // The caller specifically asked for a user route and the currently selected route
            // doesn't qualify.
            return null;
        }
        // If the above didn't match and we're not specifically asking for a user route,
        // consider the default selected.
        return sStatic.mDefaultAudioVideo;
    }

    /**
     * Add a callback to listen to events about specific kinds of media routes.
     * If the specified callback is already registered, its registration will be updated for any
     * additional route types specified.
     *
     * @param types Types of routes this callback is interested in
     * @param cb Callback to add
     */
    public void addCallback(int types, Callback cb) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo info = sStatic.mCallbacks.get(i);
            if (info.cb == cb) {
                info.type |= types;
                return;
            }
        }
        sStatic.mCallbacks.add(new CallbackInfo(cb, types, this));
    }

    /**
     * Remove the specified callback. It will no longer receive events about media routing.
     *
     * @param cb Callback to remove
     */
    public void removeCallback(Callback cb) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            if (sStatic.mCallbacks.get(i).cb == cb) {
                sStatic.mCallbacks.remove(i);
                return;
            }
        }
        Log.w(TAG, "removeCallback(" + cb + "): callback not registered");
    }

    /**
     * Select the specified route to use for output of the given media types.
     *
     * @param types type flags indicating which types this route should be used for.
     *              The route must support at least a subset.
     * @param route Route to select
     */
    public void selectRoute(int types, RouteInfo route) {
        // Applications shouldn't programmatically change anything but user routes.
        types &= ROUTE_TYPE_USER;
        selectRouteStatic(types, route);
    }
    
    /**
     * @hide internal use
     */
    public void selectRouteInt(int types, RouteInfo route) {
        selectRouteStatic(types, route);
    }

    static void selectRouteStatic(int types, RouteInfo route) {
        final RouteInfo oldRoute = sStatic.mSelectedRoute;
        if (oldRoute == route) return;
        if ((route.getSupportedTypes() & types) == 0) {
            Log.w(TAG, "selectRoute ignored; cannot select route with supported types " +
                    typesToString(route.getSupportedTypes()) + " into route types " +
                    typesToString(types));
            return;
        }

        final RouteInfo btRoute = sStatic.mBluetoothA2dpRoute;
        if (btRoute != null && (types & ROUTE_TYPE_LIVE_AUDIO) != 0 &&
                (route == btRoute || route == sStatic.mDefaultAudioVideo)) {
            try {
                sStatic.mAudioService.setBluetoothA2dpOn(route == btRoute);
            } catch (RemoteException e) {
                Log.e(TAG, "Error changing Bluetooth A2DP state", e);
            }
        }

        final WifiDisplay activeDisplay =
                sStatic.mDisplayService.getWifiDisplayStatus().getActiveDisplay();
        final boolean oldRouteHasAddress = oldRoute != null && oldRoute.mDeviceAddress != null;
        final boolean newRouteHasAddress = route != null && route.mDeviceAddress != null;
        if (activeDisplay != null || oldRouteHasAddress || newRouteHasAddress) {
            if (newRouteHasAddress && !matchesDeviceAddress(activeDisplay, route)) {
                sStatic.mDisplayService.connectWifiDisplay(route.mDeviceAddress);
            } else if (activeDisplay != null && !newRouteHasAddress) {
                sStatic.mDisplayService.disconnectWifiDisplay();
            }
        }

        if (oldRoute != null) {
            // TODO filter types properly
            dispatchRouteUnselected(types & oldRoute.getSupportedTypes(), oldRoute);
        }
        sStatic.mSelectedRoute = route;
        if (route != null) {
            // TODO filter types properly
            dispatchRouteSelected(types & route.getSupportedTypes(), route);
        }
    }

    /**
     * Compare the device address of a display and a route.
     * Nulls/no device address will match another null/no address.
     */
    static boolean matchesDeviceAddress(WifiDisplay display, RouteInfo info) {
        final boolean routeHasAddress = info != null && info.mDeviceAddress != null;
        if (display == null && !routeHasAddress) {
            return true;
        }

        if (display != null && routeHasAddress) {
            return display.getDeviceAddress().equals(info.mDeviceAddress);
        }
        return false;
    }

    /**
     * Add an app-specified route for media to the MediaRouter.
     * App-specified route definitions are created using {@link #createUserRoute(RouteCategory)}
     *
     * @param info Definition of the route to add
     * @see #createUserRoute()
     * @see #removeUserRoute(UserRouteInfo)
     */
    public void addUserRoute(UserRouteInfo info) {
        addRouteStatic(info);
    }

    /**
     * @hide Framework use only
     */
    public void addRouteInt(RouteInfo info) {
        addRouteStatic(info);
    }

    static void addRouteStatic(RouteInfo info) {
        final RouteCategory cat = info.getCategory();
        if (!sStatic.mCategories.contains(cat)) {
            sStatic.mCategories.add(cat);
        }
        if (cat.isGroupable() && !(info instanceof RouteGroup)) {
            // Enforce that any added route in a groupable category must be in a group.
            final RouteGroup group = new RouteGroup(info.getCategory());
            group.mSupportedTypes = info.mSupportedTypes;
            sStatic.mRoutes.add(group);
            dispatchRouteAdded(group);
            group.addRoute(info);

            info = group;
        } else {
            sStatic.mRoutes.add(info);
            dispatchRouteAdded(info);
        }
    }

    /**
     * Remove an app-specified route for media from the MediaRouter.
     *
     * @param info Definition of the route to remove
     * @see #addUserRoute(UserRouteInfo)
     */
    public void removeUserRoute(UserRouteInfo info) {
        removeRoute(info);
    }

    /**
     * Remove all app-specified routes from the MediaRouter.
     *
     * @see #removeUserRoute(UserRouteInfo)
     */
    public void clearUserRoutes() {
        for (int i = 0; i < sStatic.mRoutes.size(); i++) {
            final RouteInfo info = sStatic.mRoutes.get(i);
            // TODO Right now, RouteGroups only ever contain user routes.
            // The code below will need to change if this assumption does.
            if (info instanceof UserRouteInfo || info instanceof RouteGroup) {
                removeRouteAt(i);
                i--;
            }
        }
    }

    /**
     * @hide internal use only
     */
    public void removeRouteInt(RouteInfo info) {
        removeRoute(info);
    }

    static void removeRoute(RouteInfo info) {
        if (sStatic.mRoutes.remove(info)) {
            final RouteCategory removingCat = info.getCategory();
            final int count = sStatic.mRoutes.size();
            boolean found = false;
            for (int i = 0; i < count; i++) {
                final RouteCategory cat = sStatic.mRoutes.get(i).getCategory();
                if (removingCat == cat) {
                    found = true;
                    break;
                }
            }
            if (info == sStatic.mSelectedRoute) {
                // Removing the currently selected route? Select the default before we remove it.
                // TODO: Be smarter about the route types here; this selects for all valid.
                selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_USER, sStatic.mDefaultAudioVideo);
            }
            if (!found) {
                sStatic.mCategories.remove(removingCat);
            }
            dispatchRouteRemoved(info);
        }
    }

    void removeRouteAt(int routeIndex) {
        if (routeIndex >= 0 && routeIndex < sStatic.mRoutes.size()) {
            final RouteInfo info = sStatic.mRoutes.remove(routeIndex);
            final RouteCategory removingCat = info.getCategory();
            final int count = sStatic.mRoutes.size();
            boolean found = false;
            for (int i = 0; i < count; i++) {
                final RouteCategory cat = sStatic.mRoutes.get(i).getCategory();
                if (removingCat == cat) {
                    found = true;
                    break;
                }
            }
            if (info == sStatic.mSelectedRoute) {
                // Removing the currently selected route? Select the default before we remove it.
                // TODO: Be smarter about the route types here; this selects for all valid.
                selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO | ROUTE_TYPE_USER,
                        sStatic.mDefaultAudioVideo);
            }
            if (!found) {
                sStatic.mCategories.remove(removingCat);
            }
            dispatchRouteRemoved(info);
        }
    }

    /**
     * Return the number of {@link MediaRouter.RouteCategory categories} currently
     * represented by routes known to this MediaRouter.
     *
     * @return the number of unique categories represented by this MediaRouter's known routes
     */
    public int getCategoryCount() {
        return sStatic.mCategories.size();
    }

    /**
     * Return the {@link MediaRouter.RouteCategory category} at the given index.
     * Valid indices are in the range [0-getCategoryCount).
     *
     * @param index which category to return
     * @return the category at index
     */
    public RouteCategory getCategoryAt(int index) {
        return sStatic.mCategories.get(index);
    }

    /**
     * Return the number of {@link MediaRouter.RouteInfo routes} currently known
     * to this MediaRouter.
     *
     * @return the number of routes tracked by this router
     */
    public int getRouteCount() {
        return sStatic.mRoutes.size();
    }

    /**
     * Return the route at the specified index.
     *
     * @param index index of the route to return
     * @return the route at index
     */
    public RouteInfo getRouteAt(int index) {
        return sStatic.mRoutes.get(index);
    }

    static int getRouteCountStatic() {
        return sStatic.mRoutes.size();
    }

    static RouteInfo getRouteAtStatic(int index) {
        return sStatic.mRoutes.get(index);
    }

    /**
     * Create a new user route that may be modified and registered for use by the application.
     *
     * @param category The category the new route will belong to
     * @return A new UserRouteInfo for use by the application
     *
     * @see #addUserRoute(UserRouteInfo)
     * @see #removeUserRoute(UserRouteInfo)
     * @see #createRouteCategory(CharSequence)
     */
    public UserRouteInfo createUserRoute(RouteCategory category) {
        return new UserRouteInfo(category);
    }

    /**
     * Create a new route category. Each route must belong to a category.
     *
     * @param name Name of the new category
     * @param isGroupable true if routes in this category may be grouped with one another
     * @return the new RouteCategory
     */
    public RouteCategory createRouteCategory(CharSequence name, boolean isGroupable) {
        return new RouteCategory(name, ROUTE_TYPE_USER, isGroupable);
    }
    
    /**
     * Create a new route category. Each route must belong to a category.
     *
     * @param nameResId Resource ID of the name of the new category
     * @param isGroupable true if routes in this category may be grouped with one another
     * @return the new RouteCategory
     */
    public RouteCategory createRouteCategory(int nameResId, boolean isGroupable) {
        return new RouteCategory(nameResId, ROUTE_TYPE_USER, isGroupable);
    }

    static void updateRoute(final RouteInfo info) {
        dispatchRouteChanged(info);
    }

    static void dispatchRouteSelected(int type, RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & type) != 0) {
                cbi.cb.onRouteSelected(cbi.router, type, info);
            }
        }
    }

    static void dispatchRouteUnselected(int type, RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & type) != 0) {
                cbi.cb.onRouteUnselected(cbi.router, type, info);
            }
        }
    }

    static void dispatchRouteChanged(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteChanged(cbi.router, info);
            }
        }
    }

    static void dispatchRouteAdded(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteAdded(cbi.router, info);
            }
        }
    }

    static void dispatchRouteRemoved(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteRemoved(cbi.router, info);
            }
        }
    }

    static void dispatchRouteGrouped(RouteInfo info, RouteGroup group, int index) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & group.mSupportedTypes) != 0) {
                cbi.cb.onRouteGrouped(cbi.router, info, group, index);
            }
        }
    }

    static void dispatchRouteUngrouped(RouteInfo info, RouteGroup group) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & group.mSupportedTypes) != 0) {
                cbi.cb.onRouteUngrouped(cbi.router, info, group);
            }
        }
    }

    static void dispatchRouteVolumeChanged(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteVolumeChanged(cbi.router, info);
            }
        }
    }

    static void dispatchRoutePresentationDisplayChanged(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRoutePresentationDisplayChanged(cbi.router, info);
            }
        }
    }

    static void systemVolumeChanged(int newValue) {
        final RouteInfo selectedRoute = sStatic.mSelectedRoute;
        if (selectedRoute == null) return;

        if (selectedRoute == sStatic.mBluetoothA2dpRoute ||
                selectedRoute == sStatic.mDefaultAudioVideo) {
            dispatchRouteVolumeChanged(selectedRoute);
        } else if (sStatic.mBluetoothA2dpRoute != null) {
            try {
                dispatchRouteVolumeChanged(sStatic.mAudioService.isBluetoothA2dpOn() ?
                        sStatic.mBluetoothA2dpRoute : sStatic.mDefaultAudioVideo);
            } catch (RemoteException e) {
                Log.e(TAG, "Error checking Bluetooth A2DP state to report volume change", e);
            }
        } else {
            dispatchRouteVolumeChanged(sStatic.mDefaultAudioVideo);
        }
    }

    static void updateWifiDisplayStatus(WifiDisplayStatus newStatus) {
        final WifiDisplayStatus oldStatus = sStatic.mLastKnownWifiDisplayStatus;

        // TODO Naive implementation. Make this smarter later.
        boolean wantScan = false;
        boolean blockScan = false;
        WifiDisplay[] oldDisplays = oldStatus != null ?
                oldStatus.getRememberedDisplays() : new WifiDisplay[0];
        WifiDisplay[] newDisplays = newStatus.getRememberedDisplays();
        WifiDisplay[] availableDisplays = newStatus.getAvailableDisplays();
        WifiDisplay activeDisplay = newStatus.getActiveDisplay();

        for (int i = 0; i < newDisplays.length; i++) {
            final WifiDisplay d = newDisplays[i];
            final WifiDisplay oldRemembered = findMatchingDisplay(d, oldDisplays);
            if (oldRemembered == null) {
                addRouteStatic(makeWifiDisplayRoute(d,
                        findMatchingDisplay(d, availableDisplays) != null));
                wantScan = true;
            } else {
                final boolean available = findMatchingDisplay(d, availableDisplays) != null;
                final RouteInfo route = findWifiDisplayRoute(d);
                updateWifiDisplayRoute(route, d, available, newStatus);
            }
            if (d.equals(activeDisplay)) {
                final RouteInfo activeRoute = findWifiDisplayRoute(d);
                if (activeRoute != null) {
                    selectRouteStatic(activeRoute.getSupportedTypes(), activeRoute);

                    // Don't scan if we're already connected to a wifi display,
                    // the scanning process can cause a hiccup with some configurations.
                    blockScan = true;
                }
            }
        }
        for (int i = 0; i < oldDisplays.length; i++) {
            final WifiDisplay d = oldDisplays[i];
            final WifiDisplay newDisplay = findMatchingDisplay(d, newDisplays);
            if (newDisplay == null) {
                removeRoute(findWifiDisplayRoute(d));
            }
        }

        if (wantScan && !blockScan) {
            sStatic.mDisplayService.scanWifiDisplays();
        }

        sStatic.mLastKnownWifiDisplayStatus = newStatus;
    }

    static RouteInfo makeWifiDisplayRoute(WifiDisplay display, boolean available) {
        final RouteInfo newRoute = new RouteInfo(sStatic.mSystemCategory);
        newRoute.mDeviceAddress = display.getDeviceAddress();
        newRoute.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO;
        newRoute.mVolumeHandling = RouteInfo.PLAYBACK_VOLUME_FIXED;
        newRoute.mPlaybackType = RouteInfo.PLAYBACK_TYPE_REMOTE;

        newRoute.setStatusCode(available ?
                RouteInfo.STATUS_AVAILABLE : RouteInfo.STATUS_CONNECTING);
        newRoute.mEnabled = available;

        newRoute.mName = display.getFriendlyDisplayName();

        newRoute.mPresentationDisplay = choosePresentationDisplayForRoute(newRoute,
                sStatic.getAllPresentationDisplays());
        return newRoute;
    }

    private static void updateWifiDisplayRoute(RouteInfo route, WifiDisplay display,
            boolean available, WifiDisplayStatus wifiDisplayStatus) {
        final boolean isScanning =
                wifiDisplayStatus.getScanState() == WifiDisplayStatus.SCAN_STATE_SCANNING;

        boolean changed = false;
        int newStatus = RouteInfo.STATUS_NONE;

        if (available) {
            newStatus = isScanning ? RouteInfo.STATUS_SCANNING : RouteInfo.STATUS_AVAILABLE;
        } else {
            newStatus = RouteInfo.STATUS_NOT_AVAILABLE;
        }

        if (display.equals(wifiDisplayStatus.getActiveDisplay())) {
            final int activeState = wifiDisplayStatus.getActiveDisplayState();
            switch (activeState) {
                case WifiDisplayStatus.DISPLAY_STATE_CONNECTED:
                    newStatus = RouteInfo.STATUS_NONE;
                    break;
                case WifiDisplayStatus.DISPLAY_STATE_CONNECTING:
                    newStatus = RouteInfo.STATUS_CONNECTING;
                    break;
                case WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED:
                    Log.e(TAG, "Active display is not connected!");
                    break;
            }
        }

        final String newName = display.getFriendlyDisplayName();
        if (!route.getName().equals(newName)) {
            route.mName = newName;
            changed = true;
        }

        changed |= route.mEnabled != available;
        route.mEnabled = available;

        changed |= route.setStatusCode(newStatus);

        if (changed) {
            dispatchRouteChanged(route);
        }

        if (!available && route == sStatic.mSelectedRoute) {
            // Oops, no longer available. Reselect the default.
            final RouteInfo defaultRoute = sStatic.mDefaultAudioVideo;
            selectRouteStatic(defaultRoute.getSupportedTypes(), defaultRoute);
        }
    }

    private static WifiDisplay findMatchingDisplay(WifiDisplay d, WifiDisplay[] displays) {
        for (int i = 0; i < displays.length; i++) {
            final WifiDisplay other = displays[i];
            if (d.hasSameAddress(other)) {
                return other;
            }
        }
        return null;
    }

    private static RouteInfo findWifiDisplayRoute(WifiDisplay d) {
        final int count = sStatic.mRoutes.size();
        for (int i = 0; i < count; i++) {
            final RouteInfo info = sStatic.mRoutes.get(i);
            if (d.getDeviceAddress().equals(info.mDeviceAddress)) {
                return info;
            }
        }
        return null;
    }

    private static Display choosePresentationDisplayForRoute(RouteInfo route, Display[] displays) {
        if ((route.mSupportedTypes & ROUTE_TYPE_LIVE_VIDEO) != 0) {
            if (route.mDeviceAddress != null) {
                // Find the indicated Wifi display by its address.
                for (Display display : displays) {
                    if (display.getType() == Display.TYPE_WIFI
                            && route.mDeviceAddress.equals(display.getAddress())) {
                        return display;
                    }
                }
                return null;
            }

            if (route == sStatic.mDefaultAudioVideo && displays.length > 0) {
                // Choose the first presentation display from the list.
                return displays[0];
            }
        }
        return null;
    }

    /**
     * Information about a media route.
     */
    public static class RouteInfo {
        CharSequence mName;
        int mNameResId;
        private CharSequence mStatus;
        int mSupportedTypes;
        RouteGroup mGroup;
        final RouteCategory mCategory;
        Drawable mIcon;
        // playback information
        int mPlaybackType = PLAYBACK_TYPE_LOCAL;
        int mVolumeMax = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME;
        int mVolume = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME;
        int mVolumeHandling = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME_HANDLING;
        int mPlaybackStream = AudioManager.STREAM_MUSIC;
        VolumeCallbackInfo mVcb;
        Display mPresentationDisplay;

        String mDeviceAddress;
        boolean mEnabled = true;

        // A predetermined connection status that can override mStatus
        private int mStatusCode;

        /** @hide */ public static final int STATUS_NONE = 0;
        /** @hide */ public static final int STATUS_SCANNING = 1;
        /** @hide */ public static final int STATUS_CONNECTING = 2;
        /** @hide */ public static final int STATUS_AVAILABLE = 3;
        /** @hide */ public static final int STATUS_NOT_AVAILABLE = 4;

        private Object mTag;

        /**
         * The default playback type, "local", indicating the presentation of the media is happening
         * on the same device (e.g. a phone, a tablet) as where it is controlled from.
         * @see #setPlaybackType(int)
         */
        public final static int PLAYBACK_TYPE_LOCAL = 0;
        /**
         * A playback type indicating the presentation of the media is happening on
         * a different device (i.e. the remote device) than where it is controlled from.
         * @see #setPlaybackType(int)
         */
        public final static int PLAYBACK_TYPE_REMOTE = 1;
        /**
         * Playback information indicating the playback volume is fixed, i.e. it cannot be
         * controlled from this object. An example of fixed playback volume is a remote player,
         * playing over HDMI where the user prefers to control the volume on the HDMI sink, rather
         * than attenuate at the source.
         * @see #setVolumeHandling(int)
         */
        public final static int PLAYBACK_VOLUME_FIXED = 0;
        /**
         * Playback information indicating the playback volume is variable and can be controlled
         * from this object.
         */
        public final static int PLAYBACK_VOLUME_VARIABLE = 1;

        RouteInfo(RouteCategory category) {
            mCategory = category;
        }

        /**
         * @return The user-friendly name of a media route. This is the string presented
         * to users who may select this as the active route.
         */
        public CharSequence getName() {
            return getName(sStatic.mResources);
        }
        
        /**
         * Return the properly localized/resource selected name of this route.
         * 
         * @param context Context used to resolve the correct configuration to load
         * @return The user-friendly name of the media route. This is the string presented
         * to users who may select this as the active route.
         */
        public CharSequence getName(Context context) {
            return getName(context.getResources());
        }
        
        CharSequence getName(Resources res) {
            if (mNameResId != 0) {
                return mName = res.getText(mNameResId);
            }
            return mName;
        }

        /**
         * @return The user-friendly status for a media route. This may include a description
         * of the currently playing media, if available.
         */
        public CharSequence getStatus() {
            return mStatus;
        }

        /**
         * Set this route's status by predetermined status code. If the caller
         * should dispatch a route changed event this call will return true;
         */
        boolean setStatusCode(int statusCode) {
            if (statusCode != mStatusCode) {
                mStatusCode = statusCode;
                int resId = 0;
                switch (statusCode) {
                    case STATUS_SCANNING:
                        resId = com.android.internal.R.string.media_route_status_scanning;
                        break;
                    case STATUS_CONNECTING:
                        resId = com.android.internal.R.string.media_route_status_connecting;
                        break;
                    case STATUS_AVAILABLE:
                        resId = com.android.internal.R.string.media_route_status_available;
                        break;
                    case STATUS_NOT_AVAILABLE:
                        resId = com.android.internal.R.string.media_route_status_not_available;
                        break;
                }
                mStatus = resId != 0 ? sStatic.mResources.getText(resId) : null;
                return true;
            }
            return false;
        }

        /**
         * @hide
         */
        public int getStatusCode() {
            return mStatusCode;
        }

        /**
         * @return A media type flag set describing which types this route supports.
         */
        public int getSupportedTypes() {
            return mSupportedTypes;
        }

        /**
         * @return The group that this route belongs to.
         */
        public RouteGroup getGroup() {
            return mGroup;
        }

        /**
         * @return the category this route belongs to.
         */
        public RouteCategory getCategory() {
            return mCategory;
        }

        /**
         * Get the icon representing this route.
         * This icon will be used in picker UIs if available.
         *
         * @return the icon representing this route or null if no icon is available
         */
        public Drawable getIconDrawable() {
            return mIcon;
        }

        /**
         * Set an application-specific tag object for this route.
         * The application may use this to store arbitrary data associated with the
         * route for internal tracking.
         *
         * <p>Note that the lifespan of a route may be well past the lifespan of
         * an Activity or other Context; take care that objects you store here
         * will not keep more data in memory alive than you intend.</p>
         *
         * @param tag Arbitrary, app-specific data for this route to hold for later use
         */
        public void setTag(Object tag) {
            mTag = tag;
            routeUpdated();
        }

        /**
         * @return The tag object previously set by the application
         * @see #setTag(Object)
         */
        public Object getTag() {
            return mTag;
        }

        /**
         * @return the type of playback associated with this route
         * @see UserRouteInfo#setPlaybackType(int)
         */
        public int getPlaybackType() {
            return mPlaybackType;
        }

        /**
         * @return the stream over which the playback associated with this route is performed
         * @see UserRouteInfo#setPlaybackStream(int)
         */
        public int getPlaybackStream() {
            return mPlaybackStream;
        }

        /**
         * Return the current volume for this route. Depending on the route, this may only
         * be valid if the route is currently selected.
         *
         * @return the volume at which the playback associated with this route is performed
         * @see UserRouteInfo#setVolume(int)
         */
        public int getVolume() {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                int vol = 0;
                try {
                    vol = sStatic.mAudioService.getStreamVolume(mPlaybackStream);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error getting local stream volume", e);
                }
                return vol;
            } else {
                return mVolume;
            }
        }

        /**
         * Request a volume change for this route.
         * @param volume value between 0 and getVolumeMax
         */
        public void requestSetVolume(int volume) {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                try {
                    sStatic.mAudioService.setStreamVolume(mPlaybackStream, volume, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error setting local stream volume", e);
                }
            } else {
                Log.e(TAG, getClass().getSimpleName() + ".requestSetVolume(): " +
                        "Non-local volume playback on system route? " +
                        "Could not request volume change.");
            }
        }

        /**
         * Request an incremental volume update for this route.
         * @param direction Delta to apply to the current volume
         */
        public void requestUpdateVolume(int direction) {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                try {
                    final int volume =
                            Math.max(0, Math.min(getVolume() + direction, getVolumeMax()));
                    sStatic.mAudioService.setStreamVolume(mPlaybackStream, volume, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error setting local stream volume", e);
                }
            } else {
                Log.e(TAG, getClass().getSimpleName() + ".requestChangeVolume(): " +
                        "Non-local volume playback on system route? " +
                        "Could not request volume change.");
            }
        }

        /**
         * @return the maximum volume at which the playback associated with this route is performed
         * @see UserRouteInfo#setVolumeMax(int)
         */
        public int getVolumeMax() {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                int volMax = 0;
                try {
                    volMax = sStatic.mAudioService.getStreamMaxVolume(mPlaybackStream);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error getting local stream volume", e);
                }
                return volMax;
            } else {
                return mVolumeMax;
            }
        }

        /**
         * @return how volume is handling on the route
         * @see UserRouteInfo#setVolumeHandling(int)
         */
        public int getVolumeHandling() {
            return mVolumeHandling;
        }

        /**
         * Gets the {@link Display} that should be used by the application to show
         * a {@link android.app.Presentation} on an external display when this route is selected.
         * Depending on the route, this may only be valid if the route is currently
         * selected.
         * <p>
         * The preferred presentation display may change independently of the route
         * being selected or unselected.  For example, the presentation display
         * of the default system route may change when an external HDMI display is connected
         * or disconnected even though the route itself has not changed.
         * </p><p>
         * This method may return null if there is no external display associated with
         * the route or if the display is not ready to show UI yet.
         * </p><p>
         * The application should listen for changes to the presentation display
         * using the {@link Callback#onRoutePresentationDisplayChanged} callback and
         * show or dismiss its {@link android.app.Presentation} accordingly when the display
         * becomes available or is removed.
         * </p><p>
         * This method only makes sense for {@link #ROUTE_TYPE_LIVE_VIDEO live video} routes.
         * </p>
         *
         * @return The preferred presentation display to use when this route is
         * selected or null if none.
         *
         * @see #ROUTE_TYPE_LIVE_VIDEO
         * @see android.app.Presentation
         */
        public Display getPresentationDisplay() {
            return mPresentationDisplay;
        }

        /**
         * @return true if this route is enabled and may be selected
         */
        public boolean isEnabled() {
            return mEnabled;
        }

        void setStatusInt(CharSequence status) {
            if (!status.equals(mStatus)) {
                mStatus = status;
                if (mGroup != null) {
                    mGroup.memberStatusChanged(this, status);
                }
                routeUpdated();
            }
        }

        final IRemoteVolumeObserver.Stub mRemoteVolObserver = new IRemoteVolumeObserver.Stub() {
            public void dispatchRemoteVolumeUpdate(final int direction, final int value) {
                sStatic.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mVcb != null) {
                            if (direction != 0) {
                                mVcb.vcb.onVolumeUpdateRequest(mVcb.route, direction);
                            } else {
                                mVcb.vcb.onVolumeSetRequest(mVcb.route, value);
                            }
                        }
                    }
                });
            }
        };

        void routeUpdated() {
            updateRoute(this);
        }

        @Override
        public String toString() {
            String supportedTypes = typesToString(getSupportedTypes());
            return getClass().getSimpleName() + "{ name=" + getName() +
                    ", status=" + getStatus() +
                    ", category=" + getCategory() +
                    ", supportedTypes=" + supportedTypes +
                    ", presentationDisplay=" + mPresentationDisplay + "}";
        }
    }

    /**
     * Information about a route that the application may define and modify.
     * A user route defaults to {@link RouteInfo#PLAYBACK_TYPE_REMOTE} and
     * {@link RouteInfo#PLAYBACK_VOLUME_FIXED}.
     *
     * @see MediaRouter.RouteInfo
     */
    public static class UserRouteInfo extends RouteInfo {
        RemoteControlClient mRcc;

        UserRouteInfo(RouteCategory category) {
            super(category);
            mSupportedTypes = ROUTE_TYPE_USER;
            mPlaybackType = PLAYBACK_TYPE_REMOTE;
            mVolumeHandling = PLAYBACK_VOLUME_FIXED;
        }

        /**
         * Set the user-visible name of this route.
         * @param name Name to display to the user to describe this route
         */
        public void setName(CharSequence name) {
            mName = name;
            routeUpdated();
        }
        
        /**
         * Set the user-visible name of this route.
         * @param resId Resource ID of the name to display to the user to describe this route
         */
        public void setName(int resId) {
            mNameResId = resId;
            mName = null;
            routeUpdated();
        }

        /**
         * Set the current user-visible status for this route.
         * @param status Status to display to the user to describe what the endpoint
         * of this route is currently doing
         */
        public void setStatus(CharSequence status) {
            setStatusInt(status);
        }

        /**
         * Set the RemoteControlClient responsible for reporting playback info for this
         * user route.
         *
         * <p>If this route manages remote playback, the data exposed by this
         * RemoteControlClient will be used to reflect and update information
         * such as route volume info in related UIs.</p>
         *
         * <p>The RemoteControlClient must have been previously registered with
         * {@link AudioManager#registerRemoteControlClient(RemoteControlClient)}.</p>
         *
         * @param rcc RemoteControlClient associated with this route
         */
        public void setRemoteControlClient(RemoteControlClient rcc) {
            mRcc = rcc;
            updatePlaybackInfoOnRcc();
        }

        /**
         * Retrieve the RemoteControlClient associated with this route, if one has been set.
         *
         * @return the RemoteControlClient associated with this route
         * @see #setRemoteControlClient(RemoteControlClient)
         */
        public RemoteControlClient getRemoteControlClient() {
            return mRcc;
        }

        /**
         * Set an icon that will be used to represent this route.
         * The system may use this icon in picker UIs or similar.
         *
         * @param icon icon drawable to use to represent this route
         */
        public void setIconDrawable(Drawable icon) {
            mIcon = icon;
        }

        /**
         * Set an icon that will be used to represent this route.
         * The system may use this icon in picker UIs or similar.
         *
         * @param resId Resource ID of an icon drawable to use to represent this route
         */
        public void setIconResource(int resId) {
            setIconDrawable(sStatic.mResources.getDrawable(resId));
        }

        /**
         * Set a callback to be notified of volume update requests
         * @param vcb
         */
        public void setVolumeCallback(VolumeCallback vcb) {
            mVcb = new VolumeCallbackInfo(vcb, this);
        }

        /**
         * Defines whether playback associated with this route is "local"
         *    ({@link RouteInfo#PLAYBACK_TYPE_LOCAL}) or "remote"
         *    ({@link RouteInfo#PLAYBACK_TYPE_REMOTE}).
         * @param type
         */
        public void setPlaybackType(int type) {
            if (mPlaybackType != type) {
                mPlaybackType = type;
                setPlaybackInfoOnRcc(RemoteControlClient.PLAYBACKINFO_PLAYBACK_TYPE, type);
            }
        }

        /**
         * Defines whether volume for the playback associated with this route is fixed
         * ({@link RouteInfo#PLAYBACK_VOLUME_FIXED}) or can modified
         * ({@link RouteInfo#PLAYBACK_VOLUME_VARIABLE}).
         * @param volumeHandling
         */
        public void setVolumeHandling(int volumeHandling) {
            if (mVolumeHandling != volumeHandling) {
                mVolumeHandling = volumeHandling;
                setPlaybackInfoOnRcc(
                        RemoteControlClient.PLAYBACKINFO_VOLUME_HANDLING, volumeHandling);
            }
        }

        /**
         * Defines at what volume the playback associated with this route is performed (for user
         * feedback purposes). This information is only used when the playback is not local.
         * @param volume
         */
        public void setVolume(int volume) {
            volume = Math.max(0, Math.min(volume, getVolumeMax()));
            if (mVolume != volume) {
                mVolume = volume;
                setPlaybackInfoOnRcc(RemoteControlClient.PLAYBACKINFO_VOLUME, volume);
                dispatchRouteVolumeChanged(this);
                if (mGroup != null) {
                    mGroup.memberVolumeChanged(this);
                }
            }
        }

        @Override
        public void requestSetVolume(int volume) {
            if (mVolumeHandling == PLAYBACK_VOLUME_VARIABLE) {
                if (mVcb == null) {
                    Log.e(TAG, "Cannot requestSetVolume on user route - no volume callback set");
                    return;
                }
                mVcb.vcb.onVolumeSetRequest(this, volume);
            }
        }

        @Override
        public void requestUpdateVolume(int direction) {
            if (mVolumeHandling == PLAYBACK_VOLUME_VARIABLE) {
                if (mVcb == null) {
                    Log.e(TAG, "Cannot requestChangeVolume on user route - no volumec callback set");
                    return;
                }
                mVcb.vcb.onVolumeUpdateRequest(this, direction);
            }
        }

        /**
         * Defines the maximum volume at which the playback associated with this route is performed
         * (for user feedback purposes). This information is only used when the playback is not
         * local.
         * @param volumeMax
         */
        public void setVolumeMax(int volumeMax) {
            if (mVolumeMax != volumeMax) {
                mVolumeMax = volumeMax;
                setPlaybackInfoOnRcc(RemoteControlClient.PLAYBACKINFO_VOLUME_MAX, volumeMax);
            }
        }

        /**
         * Defines over what stream type the media is presented.
         * @param stream
         */
        public void setPlaybackStream(int stream) {
            if (mPlaybackStream != stream) {
                mPlaybackStream = stream;
                setPlaybackInfoOnRcc(RemoteControlClient.PLAYBACKINFO_USES_STREAM, stream);
            }
        }

        private void updatePlaybackInfoOnRcc() {
            if ((mRcc != null) && (mRcc.getRcseId() != RemoteControlClient.RCSE_ID_UNREGISTERED)) {
                mRcc.setPlaybackInformation(
                        RemoteControlClient.PLAYBACKINFO_VOLUME_MAX, mVolumeMax);
                mRcc.setPlaybackInformation(
                        RemoteControlClient.PLAYBACKINFO_VOLUME, mVolume);
                mRcc.setPlaybackInformation(
                        RemoteControlClient.PLAYBACKINFO_VOLUME_HANDLING, mVolumeHandling);
                mRcc.setPlaybackInformation(
                        RemoteControlClient.PLAYBACKINFO_USES_STREAM, mPlaybackStream);
                mRcc.setPlaybackInformation(
                        RemoteControlClient.PLAYBACKINFO_PLAYBACK_TYPE, mPlaybackType);
                // let AudioService know whom to call when remote volume needs to be updated
                try {
                    sStatic.mAudioService.registerRemoteVolumeObserverForRcc(
                            mRcc.getRcseId() /* rccId */, mRemoteVolObserver /* rvo */);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error registering remote volume observer", e);
                }
            }
        }

        private void setPlaybackInfoOnRcc(int what, int value) {
            if (mRcc != null) {
                mRcc.setPlaybackInformation(what, value);
            }
        }
    }

    /**
     * Information about a route that consists of multiple other routes in a group.
     */
    public static class RouteGroup extends RouteInfo {
        final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        private boolean mUpdateName;

        RouteGroup(RouteCategory category) {
            super(category);
            mGroup = this;
            mVolumeHandling = PLAYBACK_VOLUME_FIXED;
        }

        CharSequence getName(Resources res) {
            if (mUpdateName) updateName();
            return super.getName(res);
        }

        /**
         * Add a route to this group. The route must not currently belong to another group.
         *
         * @param route route to add to this group
         */
        public void addRoute(RouteInfo route) {
            if (route.getGroup() != null) {
                throw new IllegalStateException("Route " + route + " is already part of a group.");
            }
            if (route.getCategory() != mCategory) {
                throw new IllegalArgumentException(
                        "Route cannot be added to a group with a different category. " +
                            "(Route category=" + route.getCategory() +
                            " group category=" + mCategory + ")");
            }
            final int at = mRoutes.size();
            mRoutes.add(route);
            route.mGroup = this;
            mUpdateName = true;
            updateVolume();
            routeUpdated();
            dispatchRouteGrouped(route, this, at);
        }

        /**
         * Add a route to this group before the specified index.
         *
         * @param route route to add
         * @param insertAt insert the new route before this index
         */
        public void addRoute(RouteInfo route, int insertAt) {
            if (route.getGroup() != null) {
                throw new IllegalStateException("Route " + route + " is already part of a group.");
            }
            if (route.getCategory() != mCategory) {
                throw new IllegalArgumentException(
                        "Route cannot be added to a group with a different category. " +
                            "(Route category=" + route.getCategory() +
                            " group category=" + mCategory + ")");
            }
            mRoutes.add(insertAt, route);
            route.mGroup = this;
            mUpdateName = true;
            updateVolume();
            routeUpdated();
            dispatchRouteGrouped(route, this, insertAt);
        }

        /**
         * Remove a route from this group.
         *
         * @param route route to remove
         */
        public void removeRoute(RouteInfo route) {
            if (route.getGroup() != this) {
                throw new IllegalArgumentException("Route " + route +
                        " is not a member of this group.");
            }
            mRoutes.remove(route);
            route.mGroup = null;
            mUpdateName = true;
            updateVolume();
            dispatchRouteUngrouped(route, this);
            routeUpdated();
        }

        /**
         * Remove the route at the specified index from this group.
         *
         * @param index index of the route to remove
         */
        public void removeRoute(int index) {
            RouteInfo route = mRoutes.remove(index);
            route.mGroup = null;
            mUpdateName = true;
            updateVolume();
            dispatchRouteUngrouped(route, this);
            routeUpdated();
        }

        /**
         * @return The number of routes in this group
         */
        public int getRouteCount() {
            return mRoutes.size();
        }

        /**
         * Return the route in this group at the specified index
         *
         * @param index Index to fetch
         * @return The route at index
         */
        public RouteInfo getRouteAt(int index) {
            return mRoutes.get(index);
        }

        /**
         * Set an icon that will be used to represent this group.
         * The system may use this icon in picker UIs or similar.
         *
         * @param icon icon drawable to use to represent this group
         */
        public void setIconDrawable(Drawable icon) {
            mIcon = icon;
        }

        /**
         * Set an icon that will be used to represent this group.
         * The system may use this icon in picker UIs or similar.
         *
         * @param resId Resource ID of an icon drawable to use to represent this group
         */
        public void setIconResource(int resId) {
            setIconDrawable(sStatic.mResources.getDrawable(resId));
        }

        @Override
        public void requestSetVolume(int volume) {
            final int maxVol = getVolumeMax();
            if (maxVol == 0) {
                return;
            }

            final float scaledVolume = (float) volume / maxVol;
            final int routeCount = getRouteCount();
            for (int i = 0; i < routeCount; i++) {
                final RouteInfo route = getRouteAt(i);
                final int routeVol = (int) (scaledVolume * route.getVolumeMax());
                route.requestSetVolume(routeVol);
            }
            if (volume != mVolume) {
                mVolume = volume;
                dispatchRouteVolumeChanged(this);
            }
        }

        @Override
        public void requestUpdateVolume(int direction) {
            final int maxVol = getVolumeMax();
            if (maxVol == 0) {
                return;
            }

            final int routeCount = getRouteCount();
            int volume = 0;
            for (int i = 0; i < routeCount; i++) {
                final RouteInfo route = getRouteAt(i);
                route.requestUpdateVolume(direction);
                final int routeVol = route.getVolume();
                if (routeVol > volume) {
                    volume = routeVol;
                }
            }
            if (volume != mVolume) {
                mVolume = volume;
                dispatchRouteVolumeChanged(this);
            }
        }

        void memberNameChanged(RouteInfo info, CharSequence name) {
            mUpdateName = true;
            routeUpdated();
        }

        void memberStatusChanged(RouteInfo info, CharSequence status) {
            setStatusInt(status);
        }

        void memberVolumeChanged(RouteInfo info) {
            updateVolume();
        }

        void updateVolume() {
            // A group always represents the highest component volume value.
            final int routeCount = getRouteCount();
            int volume = 0;
            for (int i = 0; i < routeCount; i++) {
                final int routeVol = getRouteAt(i).getVolume();
                if (routeVol > volume) {
                    volume = routeVol;
                }
            }
            if (volume != mVolume) {
                mVolume = volume;
                dispatchRouteVolumeChanged(this);
            }
        }

        @Override
        void routeUpdated() {
            int types = 0;
            final int count = mRoutes.size();
            if (count == 0) {
                // Don't keep empty groups in the router.
                MediaRouter.removeRoute(this);
                return;
            }

            int maxVolume = 0;
            boolean isLocal = true;
            boolean isFixedVolume = true;
            for (int i = 0; i < count; i++) {
                final RouteInfo route = mRoutes.get(i);
                types |= route.mSupportedTypes;
                final int routeMaxVolume = route.getVolumeMax();
                if (routeMaxVolume > maxVolume) {
                    maxVolume = routeMaxVolume;
                }
                isLocal &= route.getPlaybackType() == PLAYBACK_TYPE_LOCAL;
                isFixedVolume &= route.getVolumeHandling() == PLAYBACK_VOLUME_FIXED;
            }
            mPlaybackType = isLocal ? PLAYBACK_TYPE_LOCAL : PLAYBACK_TYPE_REMOTE;
            mVolumeHandling = isFixedVolume ? PLAYBACK_VOLUME_FIXED : PLAYBACK_VOLUME_VARIABLE;
            mSupportedTypes = types;
            mVolumeMax = maxVolume;
            mIcon = count == 1 ? mRoutes.get(0).getIconDrawable() : null;
            super.routeUpdated();
        }

        void updateName() {
            final StringBuilder sb = new StringBuilder();
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                final RouteInfo info = mRoutes.get(i);
                // TODO: There's probably a much more correct way to localize this.
                if (i > 0) sb.append(", ");
                sb.append(info.mName);
            }
            mName = sb.toString();
            mUpdateName = false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(super.toString());
            sb.append('[');
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(", ");
                sb.append(mRoutes.get(i));
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /**
     * Definition of a category of routes. All routes belong to a category.
     */
    public static class RouteCategory {
        CharSequence mName;
        int mNameResId;
        int mTypes;
        final boolean mGroupable;
        boolean mIsSystem;

        RouteCategory(CharSequence name, int types, boolean groupable) {
            mName = name;
            mTypes = types;
            mGroupable = groupable;
        }

        RouteCategory(int nameResId, int types, boolean groupable) {
            mNameResId = nameResId;
            mTypes = types;
            mGroupable = groupable;
        }

        /**
         * @return the name of this route category
         */
        public CharSequence getName() {
            return getName(sStatic.mResources);
        }
        
        /**
         * Return the properly localized/configuration dependent name of this RouteCategory.
         * 
         * @param context Context to resolve name resources
         * @return the name of this route category
         */
        public CharSequence getName(Context context) {
            return getName(context.getResources());
        }
        
        CharSequence getName(Resources res) {
            if (mNameResId != 0) {
                return res.getText(mNameResId);
            }
            return mName;
        }

        /**
         * Return the current list of routes in this category that have been added
         * to the MediaRouter.
         *
         * <p>This list will not include routes that are nested within RouteGroups.
         * A RouteGroup is treated as a single route within its category.</p>
         *
         * @param out a List to fill with the routes in this category. If this parameter is
         *            non-null, it will be cleared, filled with the current routes with this
         *            category, and returned. If this parameter is null, a new List will be
         *            allocated to report the category's current routes.
         * @return A list with the routes in this category that have been added to the MediaRouter.
         */
        public List<RouteInfo> getRoutes(List<RouteInfo> out) {
            if (out == null) {
                out = new ArrayList<RouteInfo>();
            } else {
                out.clear();
            }

            final int count = getRouteCountStatic();
            for (int i = 0; i < count; i++) {
                final RouteInfo route = getRouteAtStatic(i);
                if (route.mCategory == this) {
                    out.add(route);
                }
            }
            return out;
        }

        /**
         * @return Flag set describing the route types supported by this category
         */
        public int getSupportedTypes() {
            return mTypes;
        }

        /**
         * Return whether or not this category supports grouping.
         *
         * <p>If this method returns true, all routes obtained from this category
         * via calls to {@link #getRouteAt(int)} will be {@link MediaRouter.RouteGroup}s.</p>
         *
         * @return true if this category supports
         */
        public boolean isGroupable() {
            return mGroupable;
        }

        /**
         * @return true if this is the category reserved for system routes.
         * @hide
         */
        public boolean isSystem() {
            return mIsSystem;
        }

        public String toString() {
            return "RouteCategory{ name=" + mName + " types=" + typesToString(mTypes) +
                    " groupable=" + mGroupable + " }";
        }
    }

    static class CallbackInfo {
        public int type;
        public final Callback cb;
        public final MediaRouter router;

        public CallbackInfo(Callback cb, int type, MediaRouter router) {
            this.cb = cb;
            this.type = type;
            this.router = router;
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     * All methods of this interface will be called from the application's main thread.
     *
     * <p>A Callback will only receive events relevant to routes that the callback
     * was registered for.</p>
     *
     * @see MediaRouter#addCallback(int, Callback)
     * @see MediaRouter#removeCallback(Callback)
     */
    public static abstract class Callback {
        /**
         * Called when the supplied route becomes selected as the active route
         * for the given route type.
         *
         * @param router the MediaRouter reporting the event
         * @param type Type flag set indicating the routes that have been selected
         * @param info Route that has been selected for the given route types
         */
        public abstract void onRouteSelected(MediaRouter router, int type, RouteInfo info);

        /**
         * Called when the supplied route becomes unselected as the active route
         * for the given route type.
         *
         * @param router the MediaRouter reporting the event
         * @param type Type flag set indicating the routes that have been unselected
         * @param info Route that has been unselected for the given route types
         */
        public abstract void onRouteUnselected(MediaRouter router, int type, RouteInfo info);

        /**
         * Called when a route for the specified type was added.
         *
         * @param router the MediaRouter reporting the event
         * @param info Route that has become available for use
         */
        public abstract void onRouteAdded(MediaRouter router, RouteInfo info);

        /**
         * Called when a route for the specified type was removed.
         *
         * @param router the MediaRouter reporting the event
         * @param info Route that has been removed from availability
         */
        public abstract void onRouteRemoved(MediaRouter router, RouteInfo info);

        /**
         * Called when an aspect of the indicated route has changed.
         *
         * <p>This will not indicate that the types supported by this route have
         * changed, only that cosmetic info such as name or status have been updated.</p>
         *
         * @param router the MediaRouter reporting the event
         * @param info The route that was changed
         */
        public abstract void onRouteChanged(MediaRouter router, RouteInfo info);

        /**
         * Called when a route is added to a group.
         *
         * @param router the MediaRouter reporting the event
         * @param info The route that was added
         * @param group The group the route was added to
         * @param index The route index within group that info was added at
         */
        public abstract void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group,
                int index);

        /**
         * Called when a route is removed from a group.
         *
         * @param router the MediaRouter reporting the event
         * @param info The route that was removed
         * @param group The group the route was removed from
         */
        public abstract void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group);

        /**
         * Called when a route's volume changes.
         *
         * @param router the MediaRouter reporting the event
         * @param info The route with altered volume
         */
        public abstract void onRouteVolumeChanged(MediaRouter router, RouteInfo info);

        /**
         * Called when a route's presentation display changes.
         * <p>
         * This method is called whenever the route's presentation display becomes
         * available, is removes or has changes to some of its properties (such as its size).
         * </p>
         *
         * @param router the MediaRouter reporting the event
         * @param info The route whose presentation display changed
         *
         * @see RouteInfo#getPresentationDisplay()
         */
        public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo info) {
        }
    }

    /**
     * Stub implementation of {@link MediaRouter.Callback}.
     * Each abstract method is defined as a no-op. Override just the ones
     * you need.
     */
    public static class SimpleCallback extends Callback {

        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group,
                int index) {
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
        }

        @Override
        public void onRouteVolumeChanged(MediaRouter router, RouteInfo info) {
        }
    }

    static class VolumeCallbackInfo {
        public final VolumeCallback vcb;
        public final RouteInfo route;

        public VolumeCallbackInfo(VolumeCallback vcb, RouteInfo route) {
            this.vcb = vcb;
            this.route = route;
        }
    }

    /**
     * Interface for receiving events about volume changes.
     * All methods of this interface will be called from the application's main thread.
     *
     * <p>A VolumeCallback will only receive events relevant to routes that the callback
     * was registered for.</p>
     *
     * @see UserRouteInfo#setVolumeCallback(VolumeCallback)
     */
    public static abstract class VolumeCallback {
        /**
         * Called when the volume for the route should be increased or decreased.
         * @param info the route affected by this event
         * @param direction an integer indicating whether the volume is to be increased
         *     (positive value) or decreased (negative value).
         *     For bundled changes, the absolute value indicates the number of changes
         *     in the same direction, e.g. +3 corresponds to three "volume up" changes.
         */
        public abstract void onVolumeUpdateRequest(RouteInfo info, int direction);
        /**
         * Called when the volume for the route should be set to the given value
         * @param info the route affected by this event
         * @param volume an integer indicating the new volume value that should be used, always
         *     between 0 and the value set by {@link UserRouteInfo#setVolumeMax(int)}.
         */
        public abstract void onVolumeSetRequest(RouteInfo info, int volume);
    }

    static class VolumeChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                final int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                        -1);
                if (streamType != AudioManager.STREAM_MUSIC) {
                    return;
                }

                final int newVolume = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
                final int oldVolume = intent.getIntExtra(
                        AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 0);
                if (newVolume != oldVolume) {
                    systemVolumeChanged(newVolume);
                }
            }
        }
    }

    static class WifiDisplayStatusChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED)) {
                updateWifiDisplayStatus((WifiDisplayStatus) intent.getParcelableExtra(
                        DisplayManager.EXTRA_WIFI_DISPLAY_STATUS));
            }
        }
    }
}
