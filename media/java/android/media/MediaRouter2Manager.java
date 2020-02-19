/*
 * Copyright 2019 The Android Open Source Project
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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @hide
 */
public class MediaRouter2Manager {
    private static final String TAG = "MR2Manager";
    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2Manager sInstance;

    private final MediaSessionManager mMediaSessionManager;

    final String mPackageName;

    private Context mContext;
    @GuardedBy("sLock")
    private Client mClient;
    private final IMediaRouterService mMediaRouterService;
    final Handler mHandler;
    final CopyOnWriteArrayList<CallbackRecord> mCallbackRecords = new CopyOnWriteArrayList<>();

    private final Object mRoutesLock = new Object();
    @GuardedBy("mRoutesLock")
    private final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();
    @NonNull
    final ConcurrentMap<String, List<String>> mPreferredFeaturesMap = new ConcurrentHashMap<>();

    private AtomicInteger mNextRequestId = new AtomicInteger(1);

    /**
     * Gets an instance of media router manager that controls media route of other applications.
     *
     * @return The media router manager instance for the context.
     */
    public static MediaRouter2Manager getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new MediaRouter2Manager(context);
            }
            return sInstance;
        }
    }

    private MediaRouter2Manager(Context context) {
        mContext = context.getApplicationContext();
        mMediaRouterService = IMediaRouterService.Stub.asInterface(
                ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));
        mMediaSessionManager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        mPackageName = mContext.getPackageName();
        mHandler = new Handler(context.getMainLooper());
    }

    /**
     * Registers a callback to listen route info.
     *
     * @param executor the executor that runs the callback
     * @param callback the callback to add
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        CallbackRecord callbackRecord = new CallbackRecord(executor, callback);
        if (!mCallbackRecords.addIfAbsent(callbackRecord)) {
            Log.w(TAG, "Ignoring to add the same callback twice.");
            return;
        }

        synchronized (sLock) {
            if (mClient == null) {
                Client client = new Client();
                try {
                    mMediaRouterService.registerManager(client, mPackageName);
                    mClient = client;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to register media router manager.", ex);
                }
            }
        }
    }

    /**
     * Unregisters the specified callback.
     *
     * @param callback the callback to unregister
     */
    public void unregisterCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mCallbackRecords.remove(new CallbackRecord(null, callback))) {
            Log.w(TAG, "unregisterCallback: Ignore unknown callback. " + callback);
            return;
        }

        synchronized (sLock) {
            if (mCallbackRecords.size() == 0 && mClient != null) {
                try {
                    mMediaRouterService.unregisterManager(mClient);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to unregister media router manager", ex);
                }
                //TODO: clear mRoutes?
                mClient = null;
                mPreferredFeaturesMap.clear();
            }
        }
    }

    /**
     * Gets a {@link android.media.session.MediaController} associated with the
     * given routing session.
     * If there is no matching media session, {@code null} is returned.
     */
    @Nullable
    public MediaController getMediaControllerForRoutingSession(
            @NonNull RoutingSessionInfo sessionInfo) {
        for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
            String volumeControlId = controller.getPlaybackInfo().getVolumeControlId();
            if (TextUtils.equals(sessionInfo.getId(), volumeControlId)) {
                return controller;
            }
        }
        return null;
    }

    //TODO: Use cache not to create array. For now, it's unclear when to purge the cache.
    //Do this when we finalize how to set control categories.
    /**
     * Gets available routes for an application.
     *
     * @param packageName the package name of the application
     */
    @NonNull
    public List<MediaRoute2Info> getAvailableRoutes(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<MediaRoute2Info> routes = new ArrayList<>();

        List<String> preferredFeatures = mPreferredFeaturesMap.get(packageName);
        if (preferredFeatures == null) {
            preferredFeatures = Collections.emptyList();
        }
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : mRoutes.values()) {
                if (route.isSystemRoute() || route.hasAnyFeatures(preferredFeatures)) {
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    /**
     * Gets routing sessions of an application with the given package name.
     * The first element of the returned list is the system routing controller.
     *
     * @see MediaRouter2#getSystemController()
     */
    @NonNull
    public List<RoutingSessionInfo> getRoutingSessions(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = new ArrayList<>();

        for (RoutingSessionInfo sessionInfo : getActiveSessions()) {
            if (sessionInfo.isSystemSession()
                    || TextUtils.equals(sessionInfo.getClientPackageName(), packageName)) {
                sessions.add(sessionInfo);
            }
        }
        return sessions;
    }

    /**
     * Gets the list of all active routing sessions.
     * The first element of the list is the system routing session containing
     * phone speakers, wired headset, Bluetooth devices.
     * The system routing session is shared by apps such that controlling it will affect
     * all apps.
     */
    @NonNull
    public List<RoutingSessionInfo> getActiveSessions() {
        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                return mMediaRouterService.getActiveSessions(client);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to get sessions. Service probably died.", ex);
            }
        }
        return Collections.emptyList();
    }

    /**
     * Gets the list of all discovered routes
     */
    @NonNull
    public List<MediaRoute2Info> getAllRoutes() {
        List<MediaRoute2Info> routes = new ArrayList<>();
        synchronized (mRoutesLock) {
            routes.addAll(mRoutes.values());
        }
        return routes;
    }

    /**
     * Selects media route for the specified package name.
     *
     * If the given route is {@link RoutingController#getTransferableRoutes() a transferable
     * route} of a routing session of the application, the session will be transferred to
     * the route. If not, a new routing session will be created.
     *
     * @param packageName the package name of the application that should change it's media route
     * @param route the route to be selected.
     */
    public void selectRoute(@NonNull String packageName, @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(route, "route must not be null");

        boolean transferred = false;
        //TODO: instead of release all controllers, add an API to specify controllers that
        // should be released (or is the system controller).
        for (RoutingSessionInfo sessionInfo : getRoutingSessions(packageName)) {
            if (!transferred && sessionInfo.getTransferableRoutes().contains(route.getId())) {
                new RoutingController(sessionInfo).transferToRoute(route);
                transferred = true;
            } else if (!sessionInfo.isSystemSession()) {
                new RoutingController(sessionInfo).release();
            }
        }

        if (transferred) {
            return;
        }

        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.requestCreateSessionWithManager(
                        client, packageName, route, requestId);
                //TODO: release the previous session?
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select media route", ex);
            }
        }
    }

    /**
     * Requests a volume change for a route asynchronously.
     */
    //TODO: remove this.
    public void requestSetVolume(MediaRoute2Info route, int volume) {
        setRouteVolume(route, volume);
    }

    /**
     * Requests a volume change for a route asynchronously.
     * <p>
     * It may have no effect if the route is currently not selected.
     * </p>
     *
     * @param volume The new volume value between 0 and {@link MediaRoute2Info#getVolumeMax}
     *               (inclusive).
     */
    public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        if (route.getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
            Log.w(TAG, "setRouteVolume: the route has fixed volume. Ignoring.");
            return;
        }
        if (volume < 0 || volume > route.getVolumeMax()) {
            Log.w(TAG, "setRouteVolume: the target volume is out of range. Ignoring");
            return;
        }

        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.setRouteVolumeWithManager(client, route, volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    /**
     * Requests a volume change for a routing session asynchronously.
     *
     * @param volume The new volume value between 0 and {@link RoutingSessionInfo#getVolumeMax}
     *               (inclusive).
     */
    public void setSessionVolume(@NonNull RoutingSessionInfo sessionInfo, int volume) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        if (sessionInfo.getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
            Log.w(TAG, "setSessionVolume: the route has fixed volume. Ignoring.");
            return;
        }
        if (volume < 0 || volume > sessionInfo.getVolumeMax()) {
            Log.w(TAG, "setSessionVolume: the target volume is out of range. Ignoring");
            return;
        }

        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.setSessionVolumeWithManager(
                        client, sessionInfo.getId(), volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    void addRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
            }
        }
        if (routes.size() > 0) {
            notifyRoutesAdded(routes);
        }
    }

    void removeRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.remove(route.getId());
            }
        }
        if (routes.size() > 0) {
            notifyRoutesRemoved(routes);
        }
    }

    void changeRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
            }
        }
        if (routes.size() > 0) {
            notifyRoutesChanged(routes);
        }
    }

    private void notifyRoutesAdded(List<MediaRoute2Info> routes) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesAdded(routes));
        }
    }

    private void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesRemoved(routes));
        }
    }

    private void notifyRoutesChanged(List<MediaRoute2Info> routes) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesChanged(routes));
        }
    }

    void notifySessionCreated(RoutingSessionInfo sessionInfo) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onSessionCreated(
                    new RoutingController(sessionInfo)));
        }
    }

    void notifySessionInfosChanged() {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onSessionsUpdated());
        }
    }

    void updatePreferredFeatures(String packageName, List<String> preferredFeatures) {
        List<String> prevFeatures = mPreferredFeaturesMap.put(packageName, preferredFeatures);
        if ((prevFeatures == null && preferredFeatures.size() == 0)
                || Objects.equals(preferredFeatures, prevFeatures)) {
            return;
        }
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback
                    .onControlCategoriesChanged(packageName, preferredFeatures));
        }
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback
                    .onPreferredFeaturesChanged(packageName, preferredFeatures));
        }
    }

    /**
     * @hide
     */
    public RoutingController getControllerForSession(@NonNull RoutingSessionInfo sessionInfo) {
        return new RoutingController(sessionInfo);
    }

    /**
     * A class to control media routing session in media route provider.
     * With routing controller, an application can select a route into the session or deselect
     * a route in the session.
     */
    public final class RoutingController {
        private final Object mControllerLock = new Object();
        @GuardedBy("mControllerLock")
        private RoutingSessionInfo mSessionInfo;

        RoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            mSessionInfo = sessionInfo;
        }

        /**
         * Gets the ID of the session
         */
        @NonNull
        public String getSessionId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getId();
            }
        }

        /**
         * Gets the client package name of the session
         */
        @NonNull
        public String getClientPackageName() {
            synchronized (mControllerLock) {
                return mSessionInfo.getClientPackageName();
            }
        }

        /**
         * @return the control hints used to control route session if available.
         */
        @Nullable
        public Bundle getControlHints() {
            synchronized (mControllerLock) {
                return mSessionInfo.getControlHints();
            }
        }

        /**
         * @return the unmodifiable list of currently selected routes
         */
        @NonNull
        public List<MediaRoute2Info> getSelectedRoutes() {
            List<String> routeIds;
            synchronized (mControllerLock) {
                routeIds = mSessionInfo.getSelectedRoutes();
            }
            return getRoutesWithIds(routeIds);
        }

        /**
         * @return the unmodifiable list of selectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getSelectableRoutes() {
            List<String> routeIds;
            synchronized (mControllerLock) {
                routeIds = mSessionInfo.getSelectableRoutes();
            }
            return getRoutesWithIds(routeIds);
        }

        /**
         * @return the unmodifiable list of deselectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getDeselectableRoutes() {
            List<String> routeIds;
            synchronized (mControllerLock) {
                routeIds = mSessionInfo.getDeselectableRoutes();
            }
            return getRoutesWithIds(routeIds);
        }

        /**
         * @return the unmodifiable list of transferable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getTransferableRoutes() {
            List<String> routeIds;
            synchronized (mControllerLock) {
                routeIds = mSessionInfo.getTransferableRoutes();
            }
            return getRoutesWithIds(routeIds);
        }

        /**
         * Selects a route for the remote session. The given route must satisfy all of the
         * following conditions:
         * <ul>
         * <li>ID should not be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getSelectableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getSelectableRoutes()
         */
        public void selectRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");

            RoutingSessionInfo sessionInfo;
            synchronized (mControllerLock) {
                sessionInfo = mSessionInfo;
            }
            if (sessionInfo.getSelectedRoutes().contains(route.getId())) {
                Log.w(TAG, "Ignoring selecting a route that is already selected. route=" + route);
                return;
            }

            if (!sessionInfo.getSelectableRoutes().contains(route.getId())) {
                Log.w(TAG, "Ignoring selecting a non-selectable route=" + route);
                return;
            }

            Client client;
            synchronized (sLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.selectRouteWithManager(mClient, getSessionId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to select route for session.", ex);
                }
            }
        }

        /**
         * Deselects a route from the remote session. The given route must satisfy all of the
         * following conditions:
         * <ul>
         * <li>ID should be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getDeselectableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getDeselectableRoutes()
         */
        public void deselectRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            RoutingSessionInfo sessionInfo;
            synchronized (mControllerLock) {
                sessionInfo = mSessionInfo;
            }

            if (!sessionInfo.getSelectedRoutes().contains(route.getId())) {
                Log.w(TAG, "Ignoring deselecting a route that is not selected. route=" + route);
                return;
            }

            if (!sessionInfo.getDeselectableRoutes().contains(route.getId())) {
                Log.w(TAG, "Ignoring deselecting a non-deselectable route=" + route);
                return;
            }

            Client client;
            synchronized (sLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.deselectRouteWithManager(mClient, getSessionId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to remove route from session.", ex);
                }
            }
        }

        /**
         * Transfers to a given route for the remote session. The given route must satisfy
         * all of the following conditions:
         * <ul>
         * <li>ID should not be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getTransferableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getTransferableRoutes()
         */
        public void transferToRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            RoutingSessionInfo sessionInfo;
            synchronized (mControllerLock) {
                sessionInfo = mSessionInfo;
            }

            if (sessionInfo.getSelectedRoutes().contains(route.getId())) {
                Log.w(TAG, "Ignoring transferring to a route that is already added. route="
                        + route);
                return;
            }

            if (!sessionInfo.getTransferableRoutes().contains(route.getId())) {
                Log.w(TAG, "Ignoring transferring to a non-transferable route=" + route);
                return;
            }

            Client client;
            synchronized (sLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.transferToRouteWithManager(mClient, getSessionId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to transfer to route for session.", ex);
                }
            }
        }

        /**
         * Release this session.
         * Any operation on this session after calling this method will be ignored.
         */
        public void release() {
            Client client;
            synchronized (sLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.releaseSessionWithManager(mClient, getSessionId());
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to notify of controller release", ex);
                }
            }
        }

        /**
         * Gets the session info of the session
         * @hide
         */
        @NonNull
        public RoutingSessionInfo getSessionInfo() {
            synchronized (mControllerLock) {
                return mSessionInfo;
            }
        }

        private List<MediaRoute2Info> getRoutesWithIds(List<String> routeIds) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            synchronized (mRoutesLock) {
                for (String routeId : routeIds) {
                    MediaRoute2Info route = mRoutes.get(routeId);
                    if (route != null) {
                        routes.add(route);
                    }
                }
            }
            return Collections.unmodifiableList(routes);
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public static class Callback {

        /**
         * Called when routes are added.
         * @param routes the list of routes that have been added. It's never empty.
         */
        public void onRoutesAdded(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are removed.
         * @param routes the list of routes that have been removed. It's never empty.
         */
        public void onRoutesRemoved(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are changed.
         * @param routes the list of routes that have been changed. It's never empty.
         */
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when a routing session is created.
         *
         * @param controller the controller to control the created session
         */
        public void onSessionCreated(@NonNull RoutingController controller) {}

        /**
         * Called when at least one session info is changed.
         * Call {@link #getActiveSessions()} to get current active session info.
         */
        public void onSessionsUpdated() {}

        //TODO: remove this
        /**
         * Called when the preferred route features of an app is changed.
         *
         * @param packageName the package name of the application
         * @param preferredFeatures the list of preferred route features set by an application.
         */
        public void onControlCategoriesChanged(@NonNull String packageName,
                @NonNull List<String> preferredFeatures) {}

        /**
         * Called when the preferred route features of an app is changed.
         *
         * @param packageName the package name of the application
         * @param preferredFeatures the list of preferred route features set by an application.
         */
        public void onPreferredFeaturesChanged(@NonNull String packageName,
                @NonNull List<String> preferredFeatures) {}

    }

    final class CallbackRecord {
        public final Executor mExecutor;
        public final Callback mCallback;

        CallbackRecord(Executor executor, Callback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CallbackRecord)) {
                return false;
            }
            return mCallback ==  ((CallbackRecord) obj).mCallback;
        }

        @Override
        public int hashCode() {
            return mCallback.hashCode();
        }
    }

    class Client extends IMediaRouter2Manager.Stub {
        @Override
        public void notifySessionCreated(RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifySessionCreated,
                    MediaRouter2Manager.this, sessionInfo));
        }

        @Override
        public void notifySessionsUpdated() {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifySessionInfosChanged,
                    MediaRouter2Manager.this));
            // do nothing
        }

        @Override
        public void notifyPreferredFeaturesChanged(String packageName, List<String> features) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::updatePreferredFeatures,
                    MediaRouter2Manager.this, packageName, features));
        }

        @Override
        public void notifyRoutesAdded(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::addRoutesOnHandler,
                    MediaRouter2Manager.this, routes));
        }

        @Override
        public void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::removeRoutesOnHandler,
                    MediaRouter2Manager.this, routes));
        }

        @Override
        public void notifyRoutesChanged(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::changeRoutesOnHandler,
                    MediaRouter2Manager.this, routes));
        }
    }
}
