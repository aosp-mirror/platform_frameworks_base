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

import android.bluetooth.BluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * MediaRouter allows applications to control the routing of media channels
 * and streams from the current device to external speakers and destination devices.
 *
 * <p>Media routes should only be modified on your application's main thread.</p>
 */
public class MediaRouter {
    private static final String TAG = "MediaRouter";

    private Context mAppContext;
    private AudioManager mAudioManager;
    private Handler mHandler;
    private final ArrayList<CallbackInfo> mCallbacks = new ArrayList<CallbackInfo>();

    private final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
    private final ArrayList<RouteCategory> mCategories = new ArrayList<RouteCategory>();

    private final RouteCategory mSystemCategory;
    private RouteInfo mDefaultAudio;
    private RouteInfo mBluetoothA2dpRoute;

    private RouteInfo mSelectedRoute;

    // These get removed when an activity dies
    final ArrayList<BroadcastReceiver> mRegisteredReceivers = new ArrayList<BroadcastReceiver>();

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

    /**
     * Return a MediaRouter for the application that the specified Context belongs to.
     * The behavior or availability of media routing may depend on
     * various parameters of the context.
     *
     * @param context Context for the desired router
     * @return Router for the supplied Context
     */
    public static MediaRouter forApplication(Context context) {
        final Context appContext = context.getApplicationContext();
        if (!sRouters.containsKey(appContext)) {
            final MediaRouter r = new MediaRouter(appContext);
            sRouters.put(appContext, r);
            return r;
        } else {
            return sRouters.get(appContext);
        }
    }

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

    private MediaRouter(Context context) {
        mAppContext = context;
        mHandler = new Handler(mAppContext.getMainLooper());

        mAudioManager = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
        mSystemCategory = new RouteCategory(mAppContext.getText(
                com.android.internal.R.string.default_audio_route_category_name),
                ROUTE_TYPE_LIVE_AUDIO, false);

        registerReceivers();

        createDefaultRoutes();
    }

    private void registerReceivers() {
        final BroadcastReceiver volumeReceiver = new VolumeChangedBroadcastReceiver();
        mAppContext.registerReceiver(volumeReceiver,
                new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION));
        mRegisteredReceivers.add(volumeReceiver);

        final IntentFilter speakerFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        speakerFilter.addAction(Intent.ACTION_ANALOG_AUDIO_DOCK_PLUG);
        speakerFilter.addAction(Intent.ACTION_DIGITAL_AUDIO_DOCK_PLUG);
        speakerFilter.addAction(Intent.ACTION_HDMI_AUDIO_PLUG);
        final BroadcastReceiver plugReceiver = new HeadphoneChangedBroadcastReceiver();
        mAppContext.registerReceiver(plugReceiver, speakerFilter);
        mRegisteredReceivers.add(plugReceiver);
    }

    void unregisterReceivers() {
        final int count = mRegisteredReceivers.size();
        for (int i = 0; i < count; i++) {
            final BroadcastReceiver r = mRegisteredReceivers.get(i);
            mAppContext.unregisterReceiver(r);
        }
        mRegisteredReceivers.clear();
    }

    private void createDefaultRoutes() {
        mDefaultAudio = new RouteInfo(mSystemCategory);
        mDefaultAudio.mName = mAppContext.getText(
                com.android.internal.R.string.default_audio_route_name);
        mDefaultAudio.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO;
        addRoute(mDefaultAudio);
    }

    void onHeadphonesPlugged(boolean headphonesPresent, String headphonesName) {
        mDefaultAudio.mName = headphonesPresent ? headphonesName : mAppContext.getText(
                com.android.internal.R.string.default_audio_route_name);
        dispatchRouteChanged(mDefaultAudio);
    }

    /**
     * Set volume for the specified route types.
     *
     * @param types Volume will be set for these route types
     * @param volume Volume to set in the range 0.f (inaudible) to 1.f (full volume).
     */
    public void setRouteVolume(int types, float volume) {
        if ((types & ROUTE_TYPE_LIVE_AUDIO) != 0) {
            final int index = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
        }
        if ((types & ROUTE_TYPE_USER) != 0) {
            dispatchVolumeChanged(ROUTE_TYPE_USER, volume);
        }
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
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo info = mCallbacks.get(i);
            if (info.cb == cb) {
                info.type &= types;
                return;
            }
        }
        mCallbacks.add(new CallbackInfo(cb, types));
    }

    /**
     * Remove the specified callback. It will no longer receive events about media routing.
     *
     * @param cb Callback to remove
     */
    public void removeCallback(Callback cb) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            if (mCallbacks.get(i).cb == cb) {
                mCallbacks.remove(i);
                return;
            }
        }
        Log.w(TAG, "removeCallback(" + cb + "): callback not registered");
    }

    public void selectRoute(int types, RouteInfo route) {
        if (mSelectedRoute == route) return;

        if (mSelectedRoute != null) {
            // TODO filter types properly
            dispatchRouteUnselected(types & mSelectedRoute.getSupportedTypes(), mSelectedRoute);
        }
        mSelectedRoute = route;
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

    void addRoute(RouteInfo info) {
        final RouteCategory cat = info.getCategory();
        if (!mCategories.contains(cat)) {
            mCategories.add(cat);
        }
        if (info.getCategory().isGroupable() && !(info instanceof RouteGroup)) {
            // Enforce that any added route in a groupable category must be in a group.
            final RouteGroup group = new RouteGroup(info.getCategory());
            group.addRoute(info);
            info = group;
        }
        final boolean onlyRoute = mRoutes.isEmpty();
        mRoutes.add(info);
        dispatchRouteAdded(info);
        if (onlyRoute) {
            selectRoute(info.getSupportedTypes(), info);
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

    void removeRoute(RouteInfo info) {
        if (mRoutes.remove(info)) {
            final RouteCategory removingCat = info.getCategory();
            final int count = mRoutes.size();
            boolean found = false;
            for (int i = 0; i < count; i++) {
                final RouteCategory cat = mRoutes.get(i).getCategory();
                if (removingCat == cat) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                mCategories.remove(removingCat);
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
        return mCategories.size();
    }

    /**
     * Return the {@link MediaRouter.RouteCategory category} at the given index.
     * Valid indices are in the range [0-getCategoryCount).
     *
     * @param index which category to return
     * @return the category at index
     */
    public RouteCategory getCategoryAt(int index) {
        return mCategories.get(index);
    }

    /**
     * Return the number of {@link MediaRouter.RouteInfo routes} currently known
     * to this MediaRouter.
     *
     * @return the number of routes tracked by this router
     */
    public int getRouteCount() {
        return mRoutes.size();
    }

    /**
     * Return the route at the specified index.
     *
     * @param index index of the route to return
     * @return the route at index
     */
    public RouteInfo getRouteAt(int index) {
        return mRoutes.get(index);
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

    void updateRoute(final RouteInfo info) {
        dispatchRouteChanged(info);
    }

    void dispatchRouteSelected(int type, RouteInfo info) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = mCallbacks.get(i);
            if ((cbi.type & type) != 0) {
                cbi.cb.onRouteSelected(type, info);
            }
        }
    }

    void dispatchRouteUnselected(int type, RouteInfo info) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = mCallbacks.get(i);
            if ((cbi.type & type) != 0) {
                cbi.cb.onRouteUnselected(type, info);
            }
        }
    }

    void dispatchRouteChanged(RouteInfo info) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = mCallbacks.get(i);
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteChanged(info);
            }
        }
    }

    void dispatchRouteAdded(RouteInfo info) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = mCallbacks.get(i);
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteAdded(info.mSupportedTypes, info);
            }
        }
    }

    void dispatchRouteRemoved(RouteInfo info) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = mCallbacks.get(i);
            if ((cbi.type & info.mSupportedTypes) != 0) {
                cbi.cb.onRouteRemoved(info.mSupportedTypes, info);
            }
        }
    }

    void dispatchVolumeChanged(int type, float volume) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo cbi = mCallbacks.get(i);
            if ((cbi.type & type) != 0) {
                cbi.cb.onVolumeChanged(type, volume);
            }
        }
    }

    void onA2dpDeviceConnected() {
        final RouteInfo info = new RouteInfo(mSystemCategory);
        info.mName = "Bluetooth"; // TODO Fetch the real name of the device
        mBluetoothA2dpRoute = info;
        addRoute(mBluetoothA2dpRoute);
    }

    void onA2dpDeviceDisconnected() {
        removeRoute(mBluetoothA2dpRoute);
        mBluetoothA2dpRoute = null;
    }

    /**
     * Information about a media route.
     */
    public class RouteInfo {
        CharSequence mName;
        private CharSequence mStatus;
        int mSupportedTypes;
        RouteGroup mGroup;
        final RouteCategory mCategory;

        RouteInfo(RouteCategory category) {
            mCategory = category;
            category.mRoutes.add(this);
        }

        /**
         * @return The user-friendly name of a media route. This is the string presented
         * to users who may select this as the active route.
         */
        public CharSequence getName() {
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
            String supportedTypes = typesToString(mSupportedTypes);
            return "RouteInfo{ name=" + mName + ", status=" + mStatus +
                    " category=" + mCategory +
                    " supportedTypes=" + supportedTypes + "}";
        }
    }

    /**
     * Information about a route that the application may define and modify.
     *
     * @see MediaRouter.RouteInfo
     */
    public class UserRouteInfo extends RouteInfo {

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
         * Set the current user-visible status for this route.
         * @param status Status to display to the user to describe what the endpoint
         * of this route is currently doing
         */
        public void setStatus(CharSequence status) {
            setStatusInt(status);
        }
    }

    /**
     * Information about a route that consists of multiple other routes in a group.
     */
    public class RouteGroup extends RouteInfo {
        final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        private boolean mUpdateName;

        RouteGroup(RouteCategory category) {
            super(category);
            mGroup = this;
        }

        public CharSequence getName() {
            if (mUpdateName) updateName();
            return super.getName();
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
            mRoutes.add(route);
            mUpdateName = true;
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
            mUpdateName = true;
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
            mUpdateName = true;
            routeUpdated();
        }

        /**
         * Remove the route at the specified index from this group.
         *
         * @param index index of the route to remove
         */
        public void removeRoute(int index) {
            mRoutes.remove(index);
            mUpdateName = true;
            routeUpdated();
        }

        void memberNameChanged(RouteInfo info, CharSequence name) {
            mUpdateName = true;
            routeUpdated();
        }

        void memberStatusChanged(RouteInfo info, CharSequence status) {
            setStatusInt(status);
        }

        void updateName() {
            final StringBuilder sb = new StringBuilder();
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                final RouteInfo info = mRoutes.get(i);
                if (i > 0) sb.append(", ");
                sb.append(info.mName);
            }
            mName = sb.toString();
            mUpdateName = false;
        }
    }

    /**
     * Definition of a category of routes. All routes belong to a category.
     */
    public class RouteCategory {
        final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        CharSequence mName;
        int mTypes;
        final boolean mGroupable;

        RouteCategory(CharSequence name, int types, boolean groupable) {
            mName = name;
            mTypes = types;
            mGroupable = groupable;
        }

        /**
         * @return the name of this route category
         */
        public CharSequence getName() {
            return mName;
        }

        /**
         * @return the number of routes in this category
         */
        public int getRouteCount() {
            return mRoutes.size();
        }

        /**
         * Return a route from this category
         *
         * @param index Index from [0-getRouteCount)
         * @return the route at the given index
         */
        public RouteInfo getRouteAt(int index) {
            return mRoutes.get(index);
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
         * via calls to {@link #getRouteAt(int)} will be {@link MediaRouter.RouteGroup}s.
         *
         * @return true if this category supports
         */
        public boolean isGroupable() {
            return mGroupable;
        }

        public String toString() {
            return "RouteCategory{ name=" + mName + " types=" + typesToString(mTypes) +
                    " groupable=" + mGroupable + " routes=" + mRoutes.size() + " }";
        }
    }

    static class CallbackInfo {
        public int type;
        public Callback cb;

        public CallbackInfo(Callback cb, int type) {
            this.cb = cb;
            this.type = type;
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
    public interface Callback {
        /**
         * Called when the supplied route becomes selected as the active route
         * for the given route type.
         *
         * @param type Type flag set indicating the routes that have been selected
         * @param info Route that has been selected for the given route types
         */
        public void onRouteSelected(int type, RouteInfo info);

        /**
         * Called when the supplied route becomes unselected as the active route
         * for the given route type.
         *
         * @param type Type flag set indicating the routes that have been unselected
         * @param info Route that has been unselected for the given route types
         */
        public void onRouteUnselected(int type, RouteInfo info);

        /**
         * Called when the volume is changed for the specified route types.
         *
         * @param type Type flags indicating which volume type was changed
         * @param volume New volume value in the range 0 (inaudible) to 1 (full)
         */
        public void onVolumeChanged(int type, float volume);

        /**
         * Called when a route for the specified type was added.
         *
         * @param type Type flags indicating which types the added route supports
         * @param info Route that has become available for use
         */
        public void onRouteAdded(int type, RouteInfo info);

        /**
         * Called when a route for the specified type was removed.
         *
         * @param type Type flags indicating which types the removed route supported
         * @param info Route that has been removed from availability
         */
        public void onRouteRemoved(int type, RouteInfo info);

        /**
         * Called when an aspect of the indicated route has changed.
         *
         * <p>This will not indicate that the types supported by this route have
         * changed, only that cosmetic info such as name or status have been updated.</p>
         *
         * @param info The route that was changed
         */
        public void onRouteChanged(RouteInfo info);
    }

    /**
     * Stub implementation of the {@link MediaRouter.Callback} interface.
     * Each interface method is defined as a no-op. Override just the ones
     * you need.
     */
    public static class SimpleCallback implements Callback {

        @Override
        public void onRouteSelected(int type, RouteInfo info) {

        }

        @Override
        public void onRouteUnselected(int type, RouteInfo info) {

        }

        @Override
        public void onVolumeChanged(int type, float volume) {

        }

        @Override
        public void onRouteAdded(int type, RouteInfo info) {

        }

        @Override
        public void onRouteRemoved(int type, RouteInfo info) {

        }

        @Override
        public void onRouteChanged(RouteInfo info) {

        }

    }

    class VolumeChangedBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AudioManager.VOLUME_CHANGED_ACTION.equals(action) &&
                    AudioManager.STREAM_MUSIC == intent.getIntExtra(
                            AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)) {
                final int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                final int volExtra = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
                final float volume = (float) volExtra / maxVol;
                dispatchVolumeChanged(ROUTE_TYPE_LIVE_AUDIO, volume);
            }
        }
    }

    class BtChangedBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1);
                if (state == BluetoothA2dp.STATE_CONNECTED) {
                    onA2dpDeviceConnected();
                } else if (state == BluetoothA2dp.STATE_DISCONNECTING ||
                        state == BluetoothA2dp.STATE_DISCONNECTED) {
                    onA2dpDeviceDisconnected();
                }
            }
        }
    }

    class HeadphoneChangedBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                final boolean plugged = intent.getIntExtra("state", 0) != 0;
                final String name = mAppContext.getString(
                        com.android.internal.R.string.default_audio_route_name_headphones);
                onHeadphonesPlugged(plugged, name);
            } else if (Intent.ACTION_ANALOG_AUDIO_DOCK_PLUG.equals(action) ||
                    Intent.ACTION_DIGITAL_AUDIO_DOCK_PLUG.equals(action)) {
                final boolean plugged = intent.getIntExtra("state", 0) != 0;
                final String name = mAppContext.getString(
                        com.android.internal.R.string.default_audio_route_name_dock_speakers);
                onHeadphonesPlugged(plugged, name);
            } else if (Intent.ACTION_HDMI_AUDIO_PLUG.equals(action)) {
                final boolean plugged = intent.getIntExtra("state", 0) != 0;
                final String name = mAppContext.getString(
                        com.android.internal.R.string.default_audio_route_name_hdmi);
                onHeadphonesPlugged(plugged, name);
            }
        }
    }
}
