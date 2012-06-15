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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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

    static class Static {
        final Resources mResources;
        final IAudioService mAudioService;
        final Handler mHandler;
        final ArrayList<CallbackInfo> mCallbacks = new ArrayList<CallbackInfo>();

        final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        final ArrayList<RouteCategory> mCategories = new ArrayList<RouteCategory>();

        final RouteCategory mSystemCategory;

        final AudioRoutesInfo mCurRoutesInfo = new AudioRoutesInfo();

        RouteInfo mDefaultAudio;
        RouteInfo mBluetoothA2dpRoute;

        RouteInfo mSelectedRoute;

        final IAudioRoutesObserver.Stub mRoutesObserver = new IAudioRoutesObserver.Stub() {
            public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        updateRoutes(newRoutes);
                    }
                });
            }
        };

        Static(Context appContext) {
            mResources = Resources.getSystem();
            mHandler = new Handler(appContext.getMainLooper());

            IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
            mAudioService = IAudioService.Stub.asInterface(b);

            // XXX this doesn't deal with locale changes!
            mSystemCategory = new RouteCategory(mResources.getText(
                    com.android.internal.R.string.default_audio_route_category_name),
                    ROUTE_TYPE_LIVE_AUDIO, false);
        }

        // Called after sStatic is initialized
        void startMonitoringRoutes() {
            mDefaultAudio = new RouteInfo(mSystemCategory);
            mDefaultAudio.mNameResId = com.android.internal.R.string.default_audio_route_name;
            mDefaultAudio.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO;
            addRoute(mDefaultAudio);

            AudioRoutesInfo newRoutes = null;
            try {
                newRoutes = mAudioService.startWatchingRoutes(mRoutesObserver);
            } catch (RemoteException e) {
            }
            if (newRoutes != null) {
                updateRoutes(newRoutes);
            }
        }

        void updateRoutes(AudioRoutesInfo newRoutes) {
            if (newRoutes.mMainType != mCurRoutesInfo.mMainType) {
                mCurRoutesInfo.mMainType = newRoutes.mMainType;
                int name;
                if ((newRoutes.mMainType&AudioRoutesInfo.MAIN_HEADPHONES) != 0
                        || (newRoutes.mMainType&AudioRoutesInfo.MAIN_HEADSET) != 0) {
                    name = com.android.internal.R.string.default_audio_route_name_headphones;
                } else if ((newRoutes.mMainType&AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
                    name = com.android.internal.R.string.default_audio_route_name_dock_speakers;
                } else if ((newRoutes.mMainType&AudioRoutesInfo.MAIN_HDMI) != 0) {
                    name = com.android.internal.R.string.default_audio_route_name_hdmi;
                } else {
                    name = com.android.internal.R.string.default_audio_route_name;
                }
                sStatic.mDefaultAudio.mNameResId = name;
                dispatchRouteChanged(sStatic.mDefaultAudio);
            }
            if (!TextUtils.equals(newRoutes.mBluetoothName, mCurRoutesInfo.mBluetoothName)) {
                mCurRoutesInfo.mBluetoothName = newRoutes.mBluetoothName;
                if (mCurRoutesInfo.mBluetoothName != null) {
                    if (sStatic.mBluetoothA2dpRoute == null) {
                        final RouteInfo info = new RouteInfo(sStatic.mSystemCategory);
                        info.mName = mCurRoutesInfo.mBluetoothName;
                        info.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO;
                        sStatic.mBluetoothA2dpRoute = info;
                        addRoute(sStatic.mBluetoothA2dpRoute);
                    } else {
                        sStatic.mBluetoothA2dpRoute.mName = mCurRoutesInfo.mBluetoothName;
                        dispatchRouteChanged(sStatic.mBluetoothA2dpRoute);
                    }
                } else if (sStatic.mBluetoothA2dpRoute != null) {
                    removeRoute(sStatic.mBluetoothA2dpRoute);
                    sStatic.mBluetoothA2dpRoute = null;
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
        if ((types & ROUTE_TYPE_USER) != 0) {
            result.append("ROUTE_TYPE_USER ");
        }
        return result.toString();
    }

    /** @hide */
    public MediaRouter(Context context) {
        synchronized (Static.class) {
            if (sStatic == null) {
                sStatic = new Static(context.getApplicationContext());
                sStatic.startMonitoringRoutes();
            }
        }
    }

    /**
     * @hide for use by framework routing UI
     */
    public RouteInfo getSystemAudioRoute() {
        return sStatic.mDefaultAudio;
    }

    /**
     * Return the currently selected route for the given types
     *
     * @param type route types
     * @return the selected route
     */
    public RouteInfo getSelectedRoute(int type) {
        return sStatic.mSelectedRoute;
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
                info.type &= types;
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
        if (sStatic.mSelectedRoute == route) return;
        if ((route.getSupportedTypes() & types) == 0) {
            Log.w(TAG, "selectRoute ignored; cannot select route with supported types " +
                    typesToString(route.getSupportedTypes()) + " into route types " +
                    typesToString(types));
        }

        if (sStatic.mSelectedRoute != null) {
            // TODO filter types properly
            dispatchRouteUnselected(types & sStatic.mSelectedRoute.getSupportedTypes(),
                    sStatic.mSelectedRoute);
        }
        sStatic.mSelectedRoute = route;
        if (route != null) {
            // TODO filter types properly
            dispatchRouteSelected(types & route.getSupportedTypes(), route);
        }
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
        addRoute(info);
    }

    /**
     * @hide Framework use only
     */
    public void addRouteInt(RouteInfo info) {
        addRoute(info);
    }

    static void addRoute(RouteInfo info) {
        final RouteCategory cat = info.getCategory();
        if (!sStatic.mCategories.contains(cat)) {
            sStatic.mCategories.add(cat);
        }
        final boolean onlyRoute = sStatic.mRoutes.isEmpty();
        if (cat.isGroupable() && !(info instanceof RouteGroup)) {
            // Enforce that any added route in a groupable category must be in a group.
            final RouteGroup group = new RouteGroup(info.getCategory());
            sStatic.mRoutes.add(group);
            dispatchRouteAdded(group);
            group.addRoute(info);

            info = group;
        } else {
            sStatic.mRoutes.add(info);
            dispatchRouteAdded(info);
        }

        if (onlyRoute) {
            selectRouteStatic(info.getSupportedTypes(), info);
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
                selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_USER, sStatic.mDefaultAudio);
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
                selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_USER, sStatic.mDefaultAudio);
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
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = sStatic.mCallbacks.get(i);
            if ((cbi.type & type) != 0) {
                cbi.cb.onRouteSelected(cbi.router, type, info);
            }
        }
    }

    static void dispatchRouteUnselected(int type, RouteInfo info) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = sStatic.mCallbacks.get(i);
            if ((cbi.type & type) != 0) {
                cbi.cb.onRouteUnselected(cbi.router, type, info);
            }
        }
    }

    static void dispatchRouteChanged(RouteInfo info) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = sStatic.mCallbacks.get(i);
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteChanged(cbi.router, info);
            }
        }
    }

    static void dispatchRouteAdded(RouteInfo info) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = sStatic.mCallbacks.get(i);
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteAdded(cbi.router, info);
            }
        }
    }

    static void dispatchRouteRemoved(RouteInfo info) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = sStatic.mCallbacks.get(i);
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteRemoved(cbi.router, info);
            }
        }
    }

    static void dispatchRouteGrouped(RouteInfo info, RouteGroup group, int index) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = sStatic.mCallbacks.get(i);
            if ((cbi.type & group.mSupportedTypes) != 0) {
                cbi.cb.onRouteGrouped(cbi.router, info, group, index);
            }
        }
    }

    static void dispatchRouteUngrouped(RouteInfo info, RouteGroup group) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = sStatic.mCallbacks.get(i);
            if ((cbi.type & group.mSupportedTypes) != 0) {
                cbi.cb.onRouteUngrouped(cbi.router, info, group);
            }
        }
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

        private Object mTag;

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
        }

        /**
         * @return The tag object previously set by the application
         * @see #setTag(Object)
         */
        public Object getTag() {
            return mTag;
        }

        void setStatusInt(CharSequence status) {
            if (!status.equals(mStatus)) {
                mStatus = status;
                routeUpdated();
                if (mGroup != null) {
                    mGroup.memberStatusChanged(this, status);
                }
                routeUpdated();
            }
        }

        void routeUpdated() {
            updateRoute(this);
        }

        @Override
        public String toString() {
            String supportedTypes = typesToString(getSupportedTypes());
            return getClass().getSimpleName() + "{ name=" + getName() + ", status=" + getStatus() +
                    " category=" + getCategory() +
                    " supportedTypes=" + supportedTypes + "}";
        }
    }

    /**
     * Information about a route that the application may define and modify.
     *
     * @see MediaRouter.RouteInfo
     */
    public static class UserRouteInfo extends RouteInfo {
        RemoteControlClient mRcc;

        UserRouteInfo(RouteCategory category) {
            super(category);
            mSupportedTypes = ROUTE_TYPE_USER;
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
         * @param rcc RemoteControlClient associated with this route
         */
        public void setRemoteControlClient(RemoteControlClient rcc) {
            mRcc = rcc;
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
            dispatchRouteGrouped(route, this, at);
            routeUpdated();
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
            dispatchRouteGrouped(route, this, insertAt);
            routeUpdated();
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

        void memberNameChanged(RouteInfo info, CharSequence name) {
            mUpdateName = true;
            routeUpdated();
        }

        void memberStatusChanged(RouteInfo info, CharSequence status) {
            setStatusInt(status);
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

            for (int i = 0; i < count; i++) {
                types |= mRoutes.get(i).mSupportedTypes;
            }
            mSupportedTypes = types;
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

    }
}
