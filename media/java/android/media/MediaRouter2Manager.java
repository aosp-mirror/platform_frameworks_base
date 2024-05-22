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

import static android.media.MediaRouter2.SCANNING_STATE_NOT_SCANNING;
import static android.media.MediaRouter2.SCANNING_STATE_WHILE_INTERACTIVE;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A class that monitors and controls media routing of other apps.
 * {@link android.Manifest.permission#MEDIA_CONTENT_CONTROL} is required to use this class,
 * or {@link SecurityException} will be thrown.
 * @hide
 */
public final class MediaRouter2Manager {
    private static final String TAG = "MR2Manager";
    private static final Object sLock = new Object();
    /**
     * The request ID for requests not asked by this instance.
     * Shouldn't be used for a valid request.
     * @hide
     */
    public static final int REQUEST_ID_NONE = 0;
    /** @hide */
    @VisibleForTesting
    public static final int TRANSFER_TIMEOUT_MS = 30_000;

    @GuardedBy("sLock")
    private static MediaRouter2Manager sInstance;

    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;
    private final Client mClient;
    private final IMediaRouterService mMediaRouterService;
    private final AtomicInteger mScanRequestCount = new AtomicInteger(/* initialValue= */ 0);
    final Handler mHandler;
    final CopyOnWriteArrayList<CallbackRecord> mCallbackRecords = new CopyOnWriteArrayList<>();

    private final Object mRoutesLock = new Object();
    @GuardedBy("mRoutesLock")
    private final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();
    @NonNull
    final ConcurrentMap<String, RouteDiscoveryPreference> mDiscoveryPreferenceMap =
            new ConcurrentHashMap<>();
    // TODO(b/241888071): Merge mDiscoveryPreferenceMap and mPackageToRouteListingPreferenceMap into
    //     a single record object maintained by a single package-to-record map.
    @NonNull
    private final ConcurrentMap<String, RouteListingPreference>
            mPackageToRouteListingPreferenceMap = new ConcurrentHashMap<>();

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
        mHandler = new Handler(context.getMainLooper());
        mClient = new Client();
        try {
            mMediaRouterService.registerManager(mClient, context.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
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
            Log.w(TAG, "Ignoring to register the same callback twice.");
            return;
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
    }

    /**
     * Registers a request to scan for remote routes.
     *
     * <p>Increases the count of active scanning requests. When the count transitions from zero to
     * one, sends a request to the system server to start scanning.
     *
     * <p>Clients must {@link #unregisterScanRequest() unregister their scan requests} when scanning
     * is no longer needed, to avoid unnecessary resource usage.
     */
    public void registerScanRequest() {
        if (mScanRequestCount.getAndIncrement() == 0) {
            try {
                mMediaRouterService.updateScanningState(mClient, SCANNING_STATE_WHILE_INTERACTIVE);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters a scan request made by {@link #registerScanRequest()}.
     *
     * <p>Decreases the count of active scanning requests. When the count transitions from one to
     * zero, sends a request to the system server to stop scanning.
     *
     * @throws IllegalStateException If called while there are no active scan requests.
     */
    public void unregisterScanRequest() {
        if (mScanRequestCount.updateAndGet(
                count -> {
                    if (count == 0) {
                        throw new IllegalStateException(
                                "No active scan requests to unregister.");
                    } else {
                        return --count;
                    }
                })
                == 0) {
            try {
                mMediaRouterService.updateScanningState(mClient, SCANNING_STATE_NOT_SCANNING);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
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
            if (areSessionsMatched(controller, sessionInfo)) {
                return controller;
            }
        }
        return null;
    }

    /**
     * Gets available routes for an application.
     *
     * @param packageName the package name of the application
     */
    @NonNull
    public List<MediaRoute2Info> getAvailableRoutes(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = getRoutingSessions(packageName);
        return getAvailableRoutes(sessions.get(sessions.size() - 1));
    }

    /**
     * Gets routes that can be transferable seamlessly for an application.
     *
     * @param packageName the package name of the application
     */
    @NonNull
    public List<MediaRoute2Info> getTransferableRoutes(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = getRoutingSessions(packageName);
        return getTransferableRoutes(sessions.get(sessions.size() - 1));
    }

    /**
     * Gets available routes for the given routing session.
     * The returned routes can be passed to
     * {@link #transfer(RoutingSessionInfo, MediaRoute2Info)} for transferring the routing session.
     *
     * @param sessionInfo the routing session that would be transferred
     */
    @NonNull
    public List<MediaRoute2Info> getAvailableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        return getFilteredRoutes(sessionInfo, /*includeSelectedRoutes=*/true,
                /*additionalFilter=*/null);
    }

    /**
     * Gets routes that can be transferable seamlessly for the given routing session.
     * The returned routes can be passed to
     * {@link #transfer(RoutingSessionInfo, MediaRoute2Info)} for transferring the routing session.
     * <p>
     * This includes routes that are {@link RoutingSessionInfo#getTransferableRoutes() transferable}
     * by provider itself and routes that are different playback type (e.g. local/remote)
     * from the given routing session.
     *
     * @param sessionInfo the routing session that would be transferred
     */
    @NonNull
    public List<MediaRoute2Info> getTransferableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        return getFilteredRoutes(sessionInfo, /*includeSelectedRoutes=*/false,
                (route) -> sessionInfo.isSystemSession() ^ route.isSystemRoute());
    }

    private List<MediaRoute2Info> getSortedRoutes(RouteDiscoveryPreference preference) {
        if (!preference.shouldRemoveDuplicates()) {
            synchronized (mRoutesLock) {
                return List.copyOf(mRoutes.values());
            }
        }
        Map<String, Integer> packagePriority = new ArrayMap<>();
        int count = preference.getDeduplicationPackageOrder().size();
        for (int i = 0; i < count; i++) {
            // the last package will have 1 as the priority
            packagePriority.put(preference.getDeduplicationPackageOrder().get(i), count - i);
        }
        ArrayList<MediaRoute2Info> routes;
        synchronized (mRoutesLock) {
            routes = new ArrayList<>(mRoutes.values());
        }
        // take the negative for descending order
        routes.sort(Comparator.comparingInt(
                r -> -packagePriority.getOrDefault(r.getPackageName(), 0)));
        return routes;
    }

    private List<MediaRoute2Info> getFilteredRoutes(@NonNull RoutingSessionInfo sessionInfo,
            boolean includeSelectedRoutes,
            @Nullable Predicate<MediaRoute2Info> additionalFilter) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<MediaRoute2Info> routes = new ArrayList<>();

        Set<String> deduplicationIdSet = new ArraySet<>();
        String packageName = sessionInfo.getClientPackageName();
        RouteDiscoveryPreference discoveryPreference =
                mDiscoveryPreferenceMap.getOrDefault(packageName, RouteDiscoveryPreference.EMPTY);

        for (MediaRoute2Info route : getSortedRoutes(discoveryPreference)) {
            if (!route.isVisibleTo(packageName)) {
                continue;
            }
            boolean transferableRoutesContainRoute =
                    sessionInfo.getTransferableRoutes().contains(route.getId());
            boolean selectedRoutesContainRoute =
                    sessionInfo.getSelectedRoutes().contains(route.getId());
            if (transferableRoutesContainRoute
                    || (includeSelectedRoutes && selectedRoutesContainRoute)) {
                routes.add(route);
                continue;
            }
            if (!route.hasAnyFeatures(discoveryPreference.getPreferredFeatures())) {
                continue;
            }
            if (!discoveryPreference.getAllowedPackages().isEmpty()
                    && (route.getPackageName() == null
                    || !discoveryPreference.getAllowedPackages()
                    .contains(route.getPackageName()))) {
                continue;
            }
            if (additionalFilter != null && !additionalFilter.test(route)) {
                continue;
            }
            if (discoveryPreference.shouldRemoveDuplicates()) {
                if (!Collections.disjoint(deduplicationIdSet, route.getDeduplicationIds())) {
                    continue;
                }
                deduplicationIdSet.addAll(route.getDeduplicationIds());
            }
            routes.add(route);
        }
        return routes;
    }

    /**
     * Returns the preferred features of the specified package name.
     */
    @NonNull
    public RouteDiscoveryPreference getDiscoveryPreference(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        return mDiscoveryPreferenceMap.getOrDefault(packageName, RouteDiscoveryPreference.EMPTY);
    }

    /**
     * Returns the {@link RouteListingPreference} of the app with the given {@code packageName}, or
     * null if the app has not set any.
     */
    @Nullable
    public RouteListingPreference getRouteListingPreference(@NonNull String packageName) {
        Preconditions.checkArgument(!TextUtils.isEmpty(packageName));
        return mPackageToRouteListingPreferenceMap.get(packageName);
    }

    /**
     * Gets the system routing session for the given {@code targetPackageName}. Apps can select a
     * route that is not the global route. (e.g. an app can select the device route while BT route
     * is available.)
     *
     * @param targetPackageName the package name of the application.
     */
    @Nullable
    public RoutingSessionInfo getSystemRoutingSession(@Nullable String targetPackageName) {
        try {
            return mMediaRouterService.getSystemSessionInfoForPackage(
                    mContext.getPackageName(), targetPackageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the routing session of a media session.
     * If the session is using {#link PlaybackInfo#PLAYBACK_TYPE_LOCAL local playback},
     * the system routing session is returned.
     * If the session is using {#link PlaybackInfo#PLAYBACK_TYPE_REMOTE remote playback},
     * it returns the corresponding routing session or {@code null} if it's unavailable.
     */
    @Nullable
    public RoutingSessionInfo getRoutingSessionForMediaController(MediaController mediaController) {
        MediaController.PlaybackInfo playbackInfo = mediaController.getPlaybackInfo();
        if (playbackInfo.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            return getSystemRoutingSession(mediaController.getPackageName());
        }
        for (RoutingSessionInfo sessionInfo : getRemoteSessions()) {
            if (areSessionsMatched(mediaController, sessionInfo)) {
                return sessionInfo;
            }
        }
        return null;
    }

    /**
     * Gets routing sessions of an application with the given package name.
     * The first element of the returned list is the system routing session.
     *
     * @param packageName the package name of the application that is routing.
     * @see #getSystemRoutingSession(String)
     */
    @NonNull
    public List<RoutingSessionInfo> getRoutingSessions(@NonNull String packageName) {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RoutingSessionInfo> sessions = new ArrayList<>();
        sessions.add(getSystemRoutingSession(packageName));

        for (RoutingSessionInfo sessionInfo : getRemoteSessions()) {
            if (TextUtils.equals(sessionInfo.getClientPackageName(), packageName)) {
                sessions.add(sessionInfo);
            }
        }
        return sessions;
    }

    /**
     * Gets the list of all routing sessions except the system routing session.
     * <p>
     * If you want to transfer media of an application, use {@link #getRoutingSessions(String)}.
     * If you want to get only the system routing session, use
     * {@link #getSystemRoutingSession(String)}.
     *
     * @see #getRoutingSessions(String)
     * @see #getSystemRoutingSession(String)
     */
    @NonNull
    public List<RoutingSessionInfo> getRemoteSessions() {
        try {
            return mMediaRouterService.getRemoteSessions(mClient);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the list of all discovered routes.
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
     * Transfers a {@link RoutingSessionInfo routing session} belonging to a specified package name
     * to a {@link MediaRoute2Info media route}.
     *
     * <p>Same as {@link #transfer(RoutingSessionInfo, MediaRoute2Info)}, but resolves the routing
     * session based on the provided package name.
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void transfer(
            @NonNull String packageName,
            @NonNull MediaRoute2Info route,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(route, "route must not be null");

        List<RoutingSessionInfo> sessionInfos = getRoutingSessions(packageName);
        RoutingSessionInfo targetSession = sessionInfos.get(sessionInfos.size() - 1);
        transfer(targetSession, route, userHandle, packageName);
    }

    /**
     * Transfers a routing session to a media route.
     *
     * <p>{@link Callback#onTransferred} or {@link Callback#onTransferFailed} will be called
     * depending on the result.
     *
     * @param sessionInfo the routing session info to transfer
     * @param route the route transfer to
     * @param transferInitiatorUserHandle the user handle of an app initiated the transfer
     * @param transferInitiatorPackageName the package name of an app initiated the transfer
     * @see Callback#onTransferred(RoutingSessionInfo, RoutingSessionInfo)
     * @see Callback#onTransferFailed(RoutingSessionInfo, MediaRoute2Info)
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void transfer(
            @NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaRoute2Info route,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(transferInitiatorUserHandle);
        Objects.requireNonNull(transferInitiatorPackageName);

        Log.v(TAG, "Transferring routing session. session= " + sessionInfo + ", route=" + route);

        synchronized (mRoutesLock) {
            if (!mRoutes.containsKey(route.getId())) {
                Log.w(TAG, "transfer: Ignoring an unknown route id=" + route.getId());
                notifyTransferFailed(sessionInfo, route);
                return;
            }
        }

        if (sessionInfo.getTransferableRoutes().contains(route.getId())) {
            transferToRoute(
                    sessionInfo, route, transferInitiatorUserHandle, transferInitiatorPackageName);
        } else {
            requestCreateSession(sessionInfo, route, transferInitiatorUserHandle,
                    transferInitiatorPackageName);
        }
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

        try {
            int requestId = mNextRequestId.getAndIncrement();
            mMediaRouterService.setRouteVolumeWithManager(mClient, requestId, route, volume);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
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

        try {
            int requestId = mNextRequestId.getAndIncrement();
            mMediaRouterService.setSessionVolumeWithManager(
                    mClient, requestId, sessionInfo.getId(), volume);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    void updateRoutesOnHandler(@NonNull List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            mRoutes.clear();
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
            }
        }

        notifyRoutesUpdated();
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

    void handleSessionsUpdatedOnHandler(RoutingSessionInfo sessionInfo) {
        for (TransferRequest request : mTransferRequests) {
            String sessionId = request.mOldSessionInfo.getId();
            if (!TextUtils.equals(sessionId, sessionInfo.getId())) {
                continue;
            }
            if (sessionInfo.getSelectedRoutes().contains(request.mTargetRoute.getId())) {
                mTransferRequests.remove(request);
                notifyTransferred(request.mOldSessionInfo, sessionInfo);
                break;
            }
        }
        notifySessionUpdated(sessionInfo);
    }

    private void notifyRoutesUpdated() {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onRoutesUpdated());
        }
    }

    void notifySessionUpdated(RoutingSessionInfo sessionInfo) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onSessionUpdated(sessionInfo));
        }
    }

    void notifySessionReleased(RoutingSessionInfo session) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onSessionReleased(session));
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

    void updateDiscoveryPreference(String packageName, RouteDiscoveryPreference preference) {
        if (preference == null) {
            mDiscoveryPreferenceMap.remove(packageName);
            return;
        }
        RouteDiscoveryPreference prevPreference =
                mDiscoveryPreferenceMap.put(packageName, preference);
        if (Objects.equals(preference, prevPreference)) {
            return;
        }
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback
                    .onDiscoveryPreferenceChanged(packageName, preference));
        }
    }

    private void updateRouteListingPreference(
            @NonNull String packageName, @Nullable RouteListingPreference routeListingPreference) {
        RouteListingPreference oldRouteListingPreference =
                routeListingPreference == null
                        ? mPackageToRouteListingPreferenceMap.remove(packageName)
                        : mPackageToRouteListingPreferenceMap.put(
                                packageName, routeListingPreference);
        if (Objects.equals(oldRouteListingPreference, routeListingPreference)) {
            return;
        }
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(
                    () ->
                            record.mCallback.onRouteListingPreferenceUpdated(
                                    packageName, routeListingPreference));
        }
    }

    /**
     * Gets the unmodifiable list of selected routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getSelectedRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        synchronized (mRoutesLock) {
            return sessionInfo.getSelectedRoutes().stream().map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gets the unmodifiable list of selectable routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getSelectableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<String> selectedRouteIds = sessionInfo.getSelectedRoutes();

        synchronized (mRoutesLock) {
            return sessionInfo.getSelectableRoutes().stream()
                    .filter(routeId -> !selectedRouteIds.contains(routeId))
                    .map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gets the unmodifiable list of deselectable routes for the session.
     */
    @NonNull
    public List<MediaRoute2Info> getDeselectableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        List<String> selectedRouteIds = sessionInfo.getSelectedRoutes();

        synchronized (mRoutesLock) {
            return sessionInfo.getDeselectableRoutes().stream()
                    .filter(routeId -> selectedRouteIds.contains(routeId))
                    .map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
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

        try {
            int requestId = mNextRequestId.getAndIncrement();
            mMediaRouterService.selectRouteWithManager(
                    mClient, requestId, sessionInfo.getId(), route);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
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

        try {
            int requestId = mNextRequestId.getAndIncrement();
            mMediaRouterService.deselectRouteWithManager(
                    mClient, requestId, sessionInfo.getId(), route);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Requests releasing a session.
     * <p>
     * If a session is released, any operation on the session will be ignored.
     * {@link Callback#onSessionReleased(RoutingSessionInfo)} will be called
     * when the session is released.
     * </p>
     *
     * @see Callback#onTransferred(RoutingSessionInfo, RoutingSessionInfo)
     */
    public void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        try {
            int requestId = mNextRequestId.getAndIncrement();
            mMediaRouterService.releaseSessionWithManager(mClient, requestId, sessionInfo.getId());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Transfers the remote session to the given route.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    private void transferToRoute(
            @NonNull RoutingSessionInfo session,
            @NonNull MediaRoute2Info route,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        int requestId = createTransferRequest(session, route);

        try {
            mMediaRouterService.transferToRouteWithManager(
                    mClient,
                    requestId,
                    session.getId(),
                    route,
                    transferInitiatorUserHandle,
                    transferInitiatorPackageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    private void requestCreateSession(RoutingSessionInfo oldSession, MediaRoute2Info route,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiationPackageName) {
        if (TextUtils.isEmpty(oldSession.getClientPackageName())) {
            Log.w(TAG, "requestCreateSession: Can't create a session without package name.");
            notifyTransferFailed(oldSession, route);
            return;
        }

        int requestId = createTransferRequest(oldSession, route);

        try {
            mMediaRouterService.requestCreateSessionWithManager(
                    mClient, requestId, oldSession, route, transferInitiatorUserHandle,
                    transferInitiationPackageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    private int createTransferRequest(RoutingSessionInfo session, MediaRoute2Info route) {
        int requestId = mNextRequestId.getAndIncrement();
        TransferRequest transferRequest = new TransferRequest(requestId, session, route);
        mTransferRequests.add(transferRequest);

        Message timeoutMessage =
                obtainMessage(MediaRouter2Manager::handleTransferTimeout, this, transferRequest);
        mHandler.sendMessageDelayed(timeoutMessage, TRANSFER_TIMEOUT_MS);
        return requestId;
    }

    private void handleTransferTimeout(TransferRequest request) {
        boolean removed = mTransferRequests.remove(request);
        if (removed) {
            notifyTransferFailed(request.mOldSessionInfo, request.mTargetRoute);
        }
    }


    private boolean areSessionsMatched(MediaController mediaController,
            RoutingSessionInfo sessionInfo) {
        MediaController.PlaybackInfo playbackInfo = mediaController.getPlaybackInfo();
        String volumeControlId = playbackInfo.getVolumeControlId();
        if (volumeControlId == null) {
            return false;
        }

        if (TextUtils.equals(volumeControlId, sessionInfo.getId())) {
            return true;
        }
        // Workaround for provider not being able to know the unique session ID.
        return TextUtils.equals(volumeControlId, sessionInfo.getOriginalId())
                && TextUtils.equals(mediaController.getPackageName(),
                sessionInfo.getOwnerPackageName());
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public interface Callback {

        /**
         * Called when the routes list changes. This includes adding, modifying, or removing
         * individual routes.
         */
        default void onRoutesUpdated() {}

        /**
         * Called when a session is changed.
         * @param session the updated session
         */
        default void onSessionUpdated(@NonNull RoutingSessionInfo session) {}

        /**
         * Called when a session is released.
         * @param session the released session.
         * @see #releaseSession(RoutingSessionInfo)
         */
        default void onSessionReleased(@NonNull RoutingSessionInfo session) {}

        /**
         * Called when media is transferred.
         *
         * @param oldSession the previous session
         * @param newSession the new session
         */
        default void onTransferred(@NonNull RoutingSessionInfo oldSession,
                @NonNull RoutingSessionInfo newSession) { }

        /**
         * Called when {@link #transfer(RoutingSessionInfo, MediaRoute2Info)} fails.
         */
        default void onTransferFailed(@NonNull RoutingSessionInfo session,
                @NonNull MediaRoute2Info route) { }

        /**
         * Called when the preferred route features of an app is changed.
         *
         * @param packageName the package name of the application
         * @param preferredFeatures the list of preferred route features set by an application.
         */
        default void onPreferredFeaturesChanged(@NonNull String packageName,
                @NonNull List<String> preferredFeatures) {}

        /**
         * Called when the preferred route features of an app is changed.
         *
         * @param packageName the package name of the application
         * @param discoveryPreference the new discovery preference set by the application.
         */
        default void onDiscoveryPreferenceChanged(@NonNull String packageName,
                @NonNull RouteDiscoveryPreference discoveryPreference) {
            onPreferredFeaturesChanged(packageName, discoveryPreference.getPreferredFeatures());
        }

        /**
         * Called when the app with the given {@code packageName} updates its {@link
         * MediaRouter2#setRouteListingPreference route listing preference}.
         *
         * @param packageName The package name of the app that changed its listing preference.
         * @param routeListingPreference The new {@link RouteListingPreference} set by the app with
         *     the given {@code packageName}. Maybe null if an app has unset its preference (by
         *     passing null to {@link MediaRouter2#setRouteListingPreference}).
         */
        default void onRouteListingPreferenceUpdated(
                @NonNull String packageName,
                @Nullable RouteListingPreference routeListingPreference) {}

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
        default void onRequestFailed(int reason) {}
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
            return mCallback == ((CallbackRecord) obj).mCallback;
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
        public void notifySessionCreated(int requestId, RoutingSessionInfo session) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::createSessionOnHandler,
                    MediaRouter2Manager.this, requestId, session));
        }

        @Override
        public void notifySessionUpdated(RoutingSessionInfo session) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::handleSessionsUpdatedOnHandler,
                    MediaRouter2Manager.this, session));
        }

        @Override
        public void notifySessionReleased(RoutingSessionInfo session) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifySessionReleased,
                    MediaRouter2Manager.this, session));
        }

        @Override
        public void notifyRequestFailed(int requestId, int reason) {
            // Note: requestId is not used.
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::handleFailureOnHandler,
                    MediaRouter2Manager.this, requestId, reason));
        }

        @Override
        public void notifyDiscoveryPreferenceChanged(String packageName,
                RouteDiscoveryPreference discoveryPreference) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::updateDiscoveryPreference,
                    MediaRouter2Manager.this, packageName, discoveryPreference));
        }

        @Override
        public void notifyRouteListingPreferenceChange(
                String packageName, @Nullable RouteListingPreference routeListingPreference) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2Manager::updateRouteListingPreference,
                            MediaRouter2Manager.this,
                            packageName,
                            routeListingPreference));
        }

        @Override
        public void notifyRoutesUpdated(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2Manager::updateRoutesOnHandler,
                            MediaRouter2Manager.this,
                            routes));
        }

        @Override
        public void invalidateInstance() {
            // Should never happen since MediaRouter2Manager should only be used with
            // MEDIA_CONTENT_CONTROL, which cannot be revoked.
        }
    }
}
