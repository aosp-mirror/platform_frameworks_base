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
import java.util.stream.Collectors;

/**
 * A class that monitors and controls media routing of other apps.
 * @hide
 */
public final class MediaRouter2Manager {
    private static final String TAG = "MR2Manager";
    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2Manager sInstance;

    private final MediaSessionManager mMediaSessionManager;

    final String mPackageName;

    private final Context mContext;
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

    private final AtomicInteger mNextRequestId = new AtomicInteger(1);
    private final CopyOnWriteArrayList<TransferRequest> mTransferRequests =
            new CopyOnWriteArrayList<>();

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
            if (mCallbackRecords.size() == 0) {
                if (mClient != null) {
                    try {
                        mMediaRouterService.unregisterManager(mClient);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Unable to unregister media router manager", ex);
                    }
                    mClient = null;
                }
                mRoutes.clear();
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
     * Gets the system routing session associated with no specific application.
     */
    @NonNull
    public RoutingSessionInfo getSystemRoutingSession() {
        for (RoutingSessionInfo sessionInfo : getActiveSessions()) {
            if (sessionInfo.isSystemSession()) {
                return sessionInfo;
            }
        }
        throw new IllegalStateException("No system routing session");
    }

    /**
     * Gets routing sessions of an application with the given package name.
     * The first element of the returned list is the system routing session.
     *
     * @param packageName the package name of the application that is routing.
     * @see #getSystemRoutingSession()
     */
    @NonNull
    public List<RoutingSessionInfo> getRoutingSessions(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = new ArrayList<>();

        for (RoutingSessionInfo sessionInfo : getActiveSessions()) {
            if (sessionInfo.isSystemSession()) {
                sessions.add(new RoutingSessionInfo.Builder(sessionInfo)
                        .setClientPackageName(packageName)
                        .build());
            } else if (TextUtils.equals(sessionInfo.getClientPackageName(), packageName)) {
                sessions.add(sessionInfo);
            }
        }
        return sessions;
    }

    /**
     * Gets the list of all active routing sessions.
     * <p>
     * The first element of the list is the system routing session containing
     * phone speakers, wired headset, Bluetooth devices.
     * The system routing session is shared by apps such that controlling it will affect
     * all apps.
     * If you want to transfer media of an application, use {@link #getRoutingSessions(String)}.
     *
     * @see #getRoutingSessions(String)
     * @see #getSystemRoutingSession()
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
     */
    public void selectRoute(@NonNull String packageName, @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(route, "route must not be null");

        List<RoutingSessionInfo> sessionInfos = getRoutingSessions(packageName);
        RoutingSessionInfo targetSession = sessionInfos.get(sessionInfos.size() - 1);
        transfer(targetSession, route);
    }

    /**
     * Transfers a routing session to a media route.
     * <p>{@link Callback#onTransferred} or {@link Callback#onTransferFailed} will be called
     * depending on the result.
     *
     * @param sessionInfo the routing session info to transfer
     * @param route the route transfer to
     *
     * @see Callback#onTransferred(RoutingSessionInfo, RoutingSessionInfo)
     * @see Callback#onTransferFailed(RoutingSessionInfo, MediaRoute2Info)
     */
    public void transfer(@NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");

        //TODO: Ignore unknown route.
        if (sessionInfo.getTransferableRoutes().contains(route.getId())) {
            //TODO: callbacks must be called after this.
            transferToRoute(sessionInfo, route);
            return;
        }

        if (TextUtils.isEmpty(sessionInfo.getClientPackageName())) {
            Log.w(TAG, "transfer: Ignoring transfer without package name.");
            notifyTransferFailed(sessionInfo, route);
            return;
        }

        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                //TODO: Ensure that every request is eventually removed.
                mTransferRequests.add(new TransferRequest(requestId, sessionInfo, route));

                mMediaRouterService.requestCreateSessionWithManager(
                        client, requestId, sessionInfo.getClientPackageName(), route);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select media route", ex);
            }
            releaseSession(sessionInfo);
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
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.setRouteVolumeWithManager(client, requestId, route, volume);
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
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.setSessionVolumeWithManager(
                        client, requestId, sessionInfo.getId(), volume);
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

    void createSessionOnHandler(int requestId, RoutingSessionInfo sessionInfo) {
        TransferRequest matchingRequest = null;
        for (TransferRequest request : mTransferRequests) {
            if (request.mRequestId == requestId) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest == null) {
            return;
        }

        mTransferRequests.remove(matchingRequest);

        MediaRoute2Info requestedRoute = matchingRequest.mTargetRoute;

        if (sessionInfo == null) {
            notifyTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            return;
        } else if (!sessionInfo.getSelectedRoutes().contains(requestedRoute.getId())) {
            Log.w(TAG, "The session does not contain the requested route. "
                    + "(requestedRouteId=" + requestedRoute.getId()
                    + ", actualRoutes=" + sessionInfo.getSelectedRoutes()
                    + ")");
            notifyTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            return;
        } else if (!TextUtils.equals(requestedRoute.getProviderId(),
                sessionInfo.getProviderId())) {
            Log.w(TAG, "The session's provider ID does not match the requested route's. "
                    + "(requested route's providerId=" + requestedRoute.getProviderId()
                    + ", actual providerId=" + sessionInfo.getProviderId()
                    + ")");
            notifyTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            return;
        }
        notifyTransferred(matchingRequest.mOldSessionInfo, sessionInfo);
    }

    void handleFailureOnHandler(int requestId, int reason) {
        TransferRequest matchingRequest = null;
        for (TransferRequest request : mTransferRequests) {
            if (request.mRequestId == requestId) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest != null) {
            mTransferRequests.remove(matchingRequest);
            notifyTransferFailed(matchingRequest.mOldSessionInfo, matchingRequest.mTargetRoute);
            return;
        }
        notifyRequestFailed(reason);
    }

    void handleSessionsUpdated(RoutingSessionInfo sessionInfo) {
        for (TransferRequest request : mTransferRequests) {
            String sessionId = request.mOldSessionInfo.getId();
            if (!TextUtils.equals(sessionId, sessionInfo.getId())) {
                continue;
            }
            if (sessionInfo.getSelectedRoutes().contains(request.mTargetRoute.getId())) {
                notifyTransferred(request.mOldSessionInfo, sessionInfo);
                mTransferRequests.remove(request);
                break;
            }
        }
        notifySessionUpdated(sessionInfo);
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

    void notifySessionUpdated(RoutingSessionInfo sessionInfo) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onSessionUpdated(sessionInfo));
        }
    }

    void notifyRequestFailed(int reason) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onRequestFailed(reason));
        }
    }

    void notifyTransferred(RoutingSessionInfo oldSession, RoutingSessionInfo newSession) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onTransferred(oldSession, newSession));
        }
    }

    void notifyTransferFailed(RoutingSessionInfo sessionInfo, MediaRoute2Info route) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onTransferFailed(sessionInfo, route));
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
     * Gets the unmodifiable list of selected routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getSelectedRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<String> routeIds = sessionInfo.getSelectedRoutes();
        return getRoutesWithIds(routeIds);
    }

    /**
     * Gets the unmodifiable list of selectable routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getSelectableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<String> routeIds = sessionInfo.getSelectableRoutes();
        return getRoutesWithIds(routeIds);
    }

    /**
     * Gets the unmodifiable list of deselectable routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getDeselectableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<String> routeIds = sessionInfo.getDeselectableRoutes();
        return getRoutesWithIds(routeIds);
    }

    /**
     * Selects a route for the remote session. After a route is selected, the media is expected
     * to be played to the all the selected routes. This is different from {@link
     * #transfer(RoutingSessionInfo, MediaRoute2Info)} transferring to a route},
     * where the media is expected to 'move' from one route to another.
     * <p>
     * The given route must satisfy all of the following conditions:
     * <ul>
     * <li>it should not be included in {@link #getSelectedRoutes(RoutingSessionInfo)}</li>
     * <li>it should be included in {@link #getSelectableRoutes(RoutingSessionInfo)}</li>
     * </ul>
     * If the route doesn't meet any of above conditions, it will be ignored.
     *
     * @see #getSelectedRoutes(RoutingSessionInfo)
     * @see #getSelectableRoutes(RoutingSessionInfo)
     * @see Callback#onSessionUpdated(RoutingSessionInfo)
     */
    public void selectRoute(@NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");

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
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.selectRouteWithManager(
                        mClient, requestId, sessionInfo.getId(), route);
            } catch (RemoteException ex) {
                Log.e(TAG, "selectRoute: Failed to send a request.", ex);
            }
        }
    }

    /**
     * Deselects a route from the remote session. After a route is deselected, the media is
     * expected to be stopped on the deselected routes.
     * <p>
     * The given route must satisfy all of the following conditions:
     * <ul>
     * <li>it should be included in {@link #getSelectedRoutes(RoutingSessionInfo)}</li>
     * <li>it should be included in {@link #getDeselectableRoutes(RoutingSessionInfo)}</li>
     * </ul>
     * If the route doesn't meet any of above conditions, it will be ignored.
     *
     * @see #getSelectedRoutes(RoutingSessionInfo)
     * @see #getDeselectableRoutes(RoutingSessionInfo)
     * @see Callback#onSessionUpdated(RoutingSessionInfo)
     */
    public void deselectRoute(@NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");

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
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.deselectRouteWithManager(
                        mClient, requestId, sessionInfo.getId(), route);
            } catch (RemoteException ex) {
                Log.e(TAG, "deselectRoute: Failed to send a request.", ex);
            }
        }
    }

    /**
     * Transfers to a given route for the remote session.
     *
     * @hide
     */
    void transferToRoute(@NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");

        if (sessionInfo.getSelectedRoutes().contains(route.getId())) {
            Log.w(TAG, "Ignoring transferring to a route that is already added. route="
                    + route);
            return;
        }

        if (!sessionInfo.getTransferableRoutes().contains(route.getId())) {
            Log.w(TAG, "Ignoring transferring to a non-transferable route=" + route);
            return;
        }

        int requestId = mNextRequestId.getAndIncrement();
        mTransferRequests.add(new TransferRequest(requestId, sessionInfo, route));

        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.transferToRouteWithManager(
                        mClient, requestId, sessionInfo.getId(), route);
            } catch (RemoteException ex) {
                Log.e(TAG, "transferToRoute: Failed to send a request.", ex);
            }
        }
    }

    /**
     * Requests releasing a session.
     * <p>
     * If a session is released, any operation on the session will be ignored.
     * {@link Callback#onTransferred(RoutingSessionInfo, RoutingSessionInfo)} with {@code null}
     * session will be called when the session is released.
     * </p>
     *
     * @see Callback#onTransferred(RoutingSessionInfo, RoutingSessionInfo)
     */
    public void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.releaseSessionWithManager(
                        mClient, requestId, sessionInfo.getId());
            } catch (RemoteException ex) {
                Log.e(TAG, "releaseSession: Failed to send a request", ex);
            }
        }
    }

    private List<MediaRoute2Info> getRoutesWithIds(List<String> routeIds) {
        synchronized (sLock) {
            return routeIds.stream().map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    //TODO: Remove this.
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
         * Releases the session
         */
        public void release() {
            synchronized (mControllerLock) {
                releaseSession(mSessionInfo);
            }
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
            return MediaRouter2Manager.this.getSelectedRoutes(mSessionInfo);
        }

        /**
         * @return the unmodifiable list of selectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getSelectableRoutes() {
            return MediaRouter2Manager.this.getSelectableRoutes(mSessionInfo);
        }

        /**
         * @return the unmodifiable list of deselectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getDeselectableRoutes() {
            return MediaRouter2Manager.this.getDeselectableRoutes(mSessionInfo);
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
            MediaRouter2Manager.this.selectRoute(mSessionInfo, route);
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
            MediaRouter2Manager.this.deselectRoute(mSessionInfo, route);
        }

        /**
         * Transfers session to the given rotue.
         */
        public void transferToRoute(@NonNull MediaRoute2Info route) {
            MediaRouter2Manager.this.transferToRoute(mSessionInfo, route);
        }

        /**
         * Gets the session info of the session
         *
         * @hide
         */
        @NonNull
        public RoutingSessionInfo getSessionInfo() {
            synchronized (mControllerLock) {
                return mSessionInfo;
            }
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
         * Called when a session is changed.
         * @param sessionInfo the updated session
         */
        public void onSessionUpdated(@NonNull RoutingSessionInfo sessionInfo) {}

        /**
         * Called when media is transferred.
         *
         * @param oldSession the previous session
         * @param newSession the new session or {@code null} if the session is released.
         */
        public void onTransferred(@NonNull RoutingSessionInfo oldSession,
                @Nullable RoutingSessionInfo newSession) { }

        /**
         * Called when {@link #transfer(RoutingSessionInfo, MediaRoute2Info)} fails.
         */
        public void onTransferFailed(@NonNull RoutingSessionInfo session,
                @NonNull MediaRoute2Info route) { }

        //TODO: Remove this.
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

        /**
         * Called when a previous request has failed.
         *
         * @param reason the reason that the request has failed. Can be one of followings:
         *               {@link MediaRoute2ProviderService#REASON_UNKNOWN_ERROR},
         *               {@link MediaRoute2ProviderService#REASON_REJECTED},
         *               {@link MediaRoute2ProviderService#REASON_NETWORK_ERROR},
         *               {@link MediaRoute2ProviderService#REASON_ROUTE_NOT_AVAILABLE},
         *               {@link MediaRoute2ProviderService#REASON_INVALID_COMMAND},
         */
        public void onRequestFailed(int reason) {}
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

    static final class TransferRequest {
        public final int mRequestId;
        public final RoutingSessionInfo mOldSessionInfo;
        public final MediaRoute2Info mTargetRoute;

        TransferRequest(int requestId, @NonNull RoutingSessionInfo oldSessionInfo,
                @NonNull MediaRoute2Info targetRoute) {
            mRequestId = requestId;
            mOldSessionInfo = oldSessionInfo;
            mTargetRoute = targetRoute;
        }
    }

    class Client extends IMediaRouter2Manager.Stub {
        @Override
        public void notifySessionCreated(int requestId, RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::createSessionOnHandler,
                    MediaRouter2Manager.this, requestId, sessionInfo));
        }

        @Override
        public void notifySessionUpdated(RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::handleSessionsUpdated,
                    MediaRouter2Manager.this, sessionInfo));
        }

        @Override
        public void notifyRequestFailed(int requestId, int reason) {
            // Note: requestId is not used.
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::handleFailureOnHandler,
                    MediaRouter2Manager.this, requestId, reason));
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
