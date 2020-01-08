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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A new Media Router
 * @hide
 *
 * TODO: Add method names at the beginning of log messages. (e.g. changeSessionInfoOnHandler)
 *       Not only MediaRouter2, but also to service / manager / provider.
 */
public class MediaRouter2 {

    /** @hide */
    @Retention(SOURCE)
    @IntDef(value = {
            SELECT_REASON_UNKNOWN,
            SELECT_REASON_USER_SELECTED,
            SELECT_REASON_FALLBACK,
            SELECT_REASON_SYSTEM_SELECTED})
    public @interface SelectReason {}

    /**
     * Passed to {@link Callback#onRouteSelected(MediaRoute2Info, int, Bundle)} when the reason
     * the route was selected is unknown.
     */
    public static final int SELECT_REASON_UNKNOWN = 0;

    /**
     * Passed to {@link Callback#onRouteSelected(MediaRoute2Info, int, Bundle)} when the route
     * is selected in response to a user's request. For example, when a user has selected
     * a different device to play media to.
     */
    public static final int SELECT_REASON_USER_SELECTED = 1;

    /**
     * Passed to {@link Callback#onRouteSelected(MediaRoute2Info, int, Bundle)} when the route
     * is selected as a fallback route. For example, when Wi-Fi is disconnected, the device speaker
     * may be selected as a fallback route.
     */
    public static final int SELECT_REASON_FALLBACK = 2;

    /**
     * This is passed from {@link com.android.server.media.MediaRouterService} when the route
     * is selected in response to a request from other apps (e.g. System UI).
     * @hide
     */
    public static final int SELECT_REASON_SYSTEM_SELECTED = 3;

    private static final String TAG = "MR2";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Object sRouterLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2 sInstance;

    private final Context mContext;
    private final IMediaRouterService mMediaRouterService;

    private final CopyOnWriteArrayList<RouteCallbackRecord> mRouteCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<SessionCallbackRecord> mSessionCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<SessionCreationRequest> mSessionCreationRequests =
            new CopyOnWriteArrayList<>();

    private final String mPackageName;
    @GuardedBy("sLock")
    final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    @GuardedBy("sLock")
    private List<String> mControlCategories = Collections.emptyList();

    // TODO: Make MediaRouter2 is always connected to the MediaRouterService.
    @GuardedBy("sLock")
    Client2 mClient;

    @GuardedBy("sLock")
    private Map<String, RouteSessionController> mSessionControllers = new ArrayMap<>();

    private AtomicInteger mSessionCreationRequestCnt = new AtomicInteger(1);

    final Handler mHandler;
    @GuardedBy("sLock")
    private boolean mShouldUpdateRoutes;
    private volatile List<MediaRoute2Info> mFilteredRoutes = Collections.emptyList();

    /**
     * Gets an instance of the media router associated with the context.
     */
    public static MediaRouter2 getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (sRouterLock) {
            if (sInstance == null) {
                sInstance = new MediaRouter2(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private MediaRouter2(Context appContext) {
        mContext = appContext;
        mMediaRouterService = IMediaRouterService.Stub.asInterface(
                ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));
        mPackageName = mContext.getPackageName();
        //TODO: read control categories from the manifest
        mHandler = new Handler(Looper.getMainLooper());

        List<MediaRoute2Info> currentSystemRoutes = null;
        try {
            currentSystemRoutes = mMediaRouterService.getSystemRoutes();
        } catch (RemoteException ex) {
            Log.e(TAG, "Unable to get current currentSystemRoutes", ex);
        }

        if (currentSystemRoutes == null || currentSystemRoutes.isEmpty()) {
            throw new RuntimeException("Null or empty currentSystemRoutes. Something is wrong.");
        }

        for (MediaRoute2Info route : currentSystemRoutes) {
            mRoutes.put(route.getId(), route);
        }
    }

    /**
     * Returns whether any route in {@code routeList} has a same unique ID with given route.
     *
     * @hide
     */
    public static boolean checkRouteListContainsRouteId(@NonNull List<MediaRoute2Info> routeList,
            @NonNull String uniqueRouteId) {
        for (MediaRoute2Info info : routeList) {
            if (TextUtils.equals(uniqueRouteId, info.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     */
    public void registerRouteCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback) {
        registerRouteCallback(executor, routeCallback, 0);
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     * <p>
     * If you register the same callback twice or more, it will be ignored.
     * </p>
     */
    public void registerRouteCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback, int flags) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeCallback, "callback must not be null");

        RouteCallbackRecord record = new RouteCallbackRecord(executor, routeCallback, flags);
        if (!mRouteCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "Ignoring the same callback");
            return;
        }

        synchronized (sRouterLock) {
            if (mClient == null) {
                Client2 client = new Client2();
                try {
                    mMediaRouterService.registerClient2(client, mPackageName);
                    mMediaRouterService.setControlCategories(client, mControlCategories);
                    mClient = client;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to register media router.", ex);
                }
            }
        }

        //TODO: Update discovery request here.
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @param routeCallback the callback to unregister
     * @see #registerRouteCallback
     */
    public void unregisterRouteCallback(@NonNull RouteCallback routeCallback) {
        Objects.requireNonNull(routeCallback, "callback must not be null");

        if (!mRouteCallbackRecords.remove(
                new RouteCallbackRecord(null, routeCallback, 0))) {
            Log.w(TAG, "Ignoring unknown callback");
            return;
        }

        synchronized (sRouterLock) {
            if (mRouteCallbackRecords.size() == 0 && mClient != null) {
                try {
                    mMediaRouterService.unregisterClient2(mClient);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to unregister media router.", ex);
                }
                //TODO: Clean up mRoutes. (onHandler?)
                mClient = null;
            }
        }
    }

    //TODO(b/139033746): Rename "Control Category" when it's finalized.
    /**
     * Sets the control categories of the application.
     * Routes that support at least one of the given control categories are handled
     * by the media router.
     */
    public void setControlCategories(@NonNull Collection<String> controlCategories) {
        Objects.requireNonNull(controlCategories, "control categories must not be null");

        List<String> newControlCategories = new ArrayList<>(controlCategories);

        synchronized (sRouterLock) {
            mShouldUpdateRoutes = true;

            // invoke callbacks due to control categories change
            handleControlCategoriesChangedLocked(newControlCategories);
            if (mClient != null) {
                try {
                    mMediaRouterService.setControlCategories(mClient, mControlCategories);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to set control categories.", ex);
                }
            }
        }
    }

    /**
     * Gets the unmodifiable list of {@link MediaRoute2Info routes} currently
     * known to the media router.
     * Please note that the list can be changed before callbacks are invoked.
     *
     * @return the list of routes that support at least one of the control categories set by
     * the application
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        synchronized (sRouterLock) {
            if (mShouldUpdateRoutes) {
                mShouldUpdateRoutes = false;

                List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
                for (MediaRoute2Info route : mRoutes.values()) {
                    if (route.supportsControlCategories(mControlCategories)) {
                        filteredRoutes.add(route);
                    }
                }
                mFilteredRoutes = Collections.unmodifiableList(filteredRoutes);
            }
        }
        return mFilteredRoutes;
    }

    /**
     * Registers a callback to get updates on creations and changes of route sessions.
     * If you register the same callback twice or more, it will be ignored.
     *
     * @param executor the executor to execute the callback on
     * @param callback the callback to register
     * @see #unregisterSessionCallback
     */
    @NonNull
    public void registerSessionCallback(@CallbackExecutor Executor executor,
            @NonNull SessionCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        SessionCallbackRecord record = new SessionCallbackRecord(executor, callback);
        if (!mSessionCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "Ignoring the same session callback");
            return;
        }
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @param callback the callback to unregister
     * @see #registerSessionCallback
     */
    @NonNull
    public void unregisterSessionCallback(@NonNull SessionCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mSessionCallbackRecords.remove(new SessionCallbackRecord(null, callback))) {
            Log.w(TAG, "Ignoring unknown session callback");
            return;
        }
    }

    /**
     * Requests the media route provider service to create a session with the given route.
     *
     * @param route the route you want to create a session with.
     * @param controlCategory the control category of the session. Should not be empty
     *
     * @see SessionCallback#onSessionCreated
     * @see SessionCallback#onSessionCreationFailed
     */
    @NonNull
    public void requestCreateSession(@NonNull MediaRoute2Info route,
            @NonNull String controlCategory) {
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(controlCategory)) {
            throw new IllegalArgumentException("controlCategory must not be empty");
        }
        // TODO: Check the given route exists
        // TODO: Check the route supports the given controlCategory

        final int requestId;
        requestId = mSessionCreationRequestCnt.getAndIncrement();

        SessionCreationRequest request = new SessionCreationRequest(
                requestId, route, controlCategory);
        mSessionCreationRequests.add(request);

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.requestCreateSession(
                        client, route, controlCategory, requestId);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to request to create session.", ex);
                mHandler.sendMessage(obtainMessage(MediaRouter2::createControllerOnHandler,
                        MediaRouter2.this, null, requestId));
            }
        }
    }

    /**
     * Sends a media control request to be performed asynchronously by the route's destination.
     *
     * @param route the route that will receive the control request
     * @param request the media control request
     */
    //TODO: Discuss what to use for request (e.g., Intent? Request class?)
    //TODO: Provide a way to obtain the result
    public void sendControlRequest(@NonNull MediaRoute2Info route, @NonNull Intent request) {
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.sendControlRequest(client, route, request);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    /**
     * Requests a volume change for the route asynchronously.
     * <p>
     * It may have no effect if the route is currently not selected.
     * </p>
     *
     * @param volume The new volume value between 0 and {@link MediaRoute2Info#getVolumeMax}.
     */
    public void requestSetVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.requestSetVolume2(client, route, volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    /**
     * Requests an incremental volume update  for the route asynchronously.
     * <p>
     * It may have no effect if the route is currently not selected.
     * </p>
     *
     * @param delta The delta to add to the current volume.
     */
    public void requestUpdateVolume(@NonNull MediaRoute2Info route, int delta) {
        Objects.requireNonNull(route, "route must not be null");

        Client2 client;
        synchronized (sRouterLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.requestUpdateVolume2(client, route, delta);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    private void handleControlCategoriesChangedLocked(List<String> newControlCategories) {
        List<MediaRoute2Info> addedRoutes = new ArrayList<>();
        List<MediaRoute2Info> removedRoutes = new ArrayList<>();

        List<String> prevControlCategories = mControlCategories;
        mControlCategories = newControlCategories;

        for (MediaRoute2Info route : mRoutes.values()) {
            boolean preSupported = route.supportsControlCategories(prevControlCategories);
            boolean postSupported = route.supportsControlCategories(newControlCategories);
            if (preSupported == postSupported) {
                continue;
            }
            if (preSupported) {
                removedRoutes.add(route);
            } else {
                addedRoutes.add(route);
            }
        }

        if (removedRoutes.size() > 0) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::notifyRoutesRemoved,
                    MediaRouter2.this, removedRoutes));
        }
        if (addedRoutes.size() > 0) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::notifyRoutesAdded,
                    MediaRouter2.this, addedRoutes));
        }
    }

    void addRoutesOnHandler(List<MediaRoute2Info> routes) {
        // TODO: When onRoutesAdded is first called,
        //  1) clear mRoutes before adding the routes
        //  2) Call onRouteSelected(system_route, reason_fallback) if previously selected route
        //     does not exist anymore. => We may need 'boolean MediaRoute2Info#isSystemRoute()'.
        List<MediaRoute2Info> addedRoutes = new ArrayList<>();
        synchronized (sRouterLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getUniqueId(), route);
                if (route.supportsControlCategories(mControlCategories)) {
                    addedRoutes.add(route);
                }
            }
            mShouldUpdateRoutes = true;
        }
        if (addedRoutes.size() > 0) {
            notifyRoutesAdded(addedRoutes);
        }
    }

    void removeRoutesOnHandler(List<MediaRoute2Info> routes) {
        List<MediaRoute2Info> removedRoutes = new ArrayList<>();
        synchronized (sRouterLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.remove(route.getUniqueId());
                if (route.supportsControlCategories(mControlCategories)) {
                    removedRoutes.add(route);
                }
            }
            mShouldUpdateRoutes = true;
        }
        if (removedRoutes.size() > 0) {
            notifyRoutesRemoved(removedRoutes);
        }
    }

    void changeRoutesOnHandler(List<MediaRoute2Info> routes) {
        List<MediaRoute2Info> changedRoutes = new ArrayList<>();
        synchronized (sRouterLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getUniqueId(), route);
                if (route.supportsControlCategories(mControlCategories)) {
                    changedRoutes.add(route);
                }
            }
        }
        if (changedRoutes.size() > 0) {
            notifyRoutesChanged(changedRoutes);
        }
    }

    /**
     * Creates a controller and calls the {@link SessionCallback#onSessionCreated}.
     * If session creation has failed, then it calls
     * {@link SessionCallback#onSessionCreationFailed}.
     * <p>
     * Pass {@code null} to sessionInfo for the failure case.
     */
    void createControllerOnHandler(@Nullable RouteSessionInfo sessionInfo, int requestId) {
        SessionCreationRequest matchingRequest = null;
        for (SessionCreationRequest request : mSessionCreationRequests) {
            if (request.mRequestId == requestId) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest != null) {
            mSessionCreationRequests.remove(matchingRequest);

            MediaRoute2Info requestedRoute = matchingRequest.mRoute;
            String requestedControlCategory = matchingRequest.mControlCategory;

            if (sessionInfo == null) {
                // TODO: We may need to distinguish between failure and rejection.
                //       One way can be introducing 'reason'.
                notifySessionCreationFailed(requestedRoute, requestedControlCategory);
                return;
            } else if (!TextUtils.equals(requestedControlCategory,
                    sessionInfo.getControlCategory())) {
                Log.w(TAG, "The session has different control category from what we requested. "
                        + "(requested=" + requestedControlCategory
                        + ", actual=" + sessionInfo.getControlCategory()
                        + ")");
                notifySessionCreationFailed(requestedRoute, requestedControlCategory);
                return;
            } else if (!sessionInfo.getSelectedRoutes().contains(requestedRoute.getId())) {
                Log.w(TAG, "The session does not contain the requested route. "
                        + "(requestedRouteId=" + requestedRoute.getId()
                        + ", actualRoutes=" + sessionInfo.getSelectedRoutes()
                        + ")");
                notifySessionCreationFailed(requestedRoute, requestedControlCategory);
                return;
            } else if (!TextUtils.equals(requestedRoute.getProviderId(),
                    sessionInfo.getProviderId())) {
                Log.w(TAG, "The session's provider ID does not match the requested route's. "
                        + "(requested route's providerId=" + requestedRoute.getProviderId()
                        + ", actual providerId=" + sessionInfo.getProviderId()
                        + ")");
                notifySessionCreationFailed(requestedRoute, requestedControlCategory);
                return;
            }
        }

        if (sessionInfo != null) {
            RouteSessionController controller = new RouteSessionController(sessionInfo);
            synchronized (sRouterLock) {
                mSessionControllers.put(controller.getUniqueSessionId(), controller);
            }
            notifySessionCreated(controller);
        }
    }

    void changeSessionInfoOnHandler(RouteSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            Log.w(TAG, "changeSessionInfoOnHandler: Ignoring null sessionInfo.");
            return;
        }

        RouteSessionController matchingController;
        synchronized (sRouterLock) {
            matchingController = mSessionControllers.get(sessionInfo.getUniqueSessionId());
        }

        if (matchingController == null) {
            Log.w(TAG, "changeSessionInfoOnHandler: Matching controller not found. uniqueSessionId="
                    + sessionInfo.getUniqueSessionId());
            return;
        }

        RouteSessionInfo oldInfo = matchingController.getRouteSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(TAG, "changeSessionInfoOnHandler: Provider IDs are not matched. old="
                    + oldInfo.getProviderId() + ", new=" + sessionInfo.getProviderId());
            return;
        }

        matchingController.setRouteSessionInfo(sessionInfo);
        notifySessionInfoChanged(matchingController, oldInfo, sessionInfo);
    }

    void releaseControllerOnHandler(RouteSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            Log.w(TAG, "releaseControllerOnHandler: Ignoring null sessionInfo.");
            return;
        }

        final String uniqueSessionId = sessionInfo.getUniqueSessionId();
        RouteSessionController matchingController;
        synchronized (sRouterLock) {
            matchingController = mSessionControllers.get(uniqueSessionId);
        }

        if (matchingController == null) {
            if (DEBUG) {
                Log.d(TAG, "releaseControllerOnHandler: Matching controller not found. "
                        + "uniqueSessionId=" + sessionInfo.getUniqueSessionId());
            }
            return;
        }

        RouteSessionInfo oldInfo = matchingController.getRouteSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(TAG, "releaseControllerOnHandler: Provider IDs are not matched. old="
                    + oldInfo.getProviderId() + ", new=" + sessionInfo.getProviderId());
            return;
        }

        synchronized (sRouterLock) {
            mSessionControllers.remove(uniqueSessionId, matchingController);
        }
        matchingController.release();
        notifyControllerReleased(matchingController);
    }

    private void notifyRoutesAdded(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteCallback.onRoutesAdded(routes));
        }
    }

    private void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteCallback.onRoutesRemoved(routes));
        }
    }

    private void notifyRoutesChanged(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteCallback.onRoutesChanged(routes));
        }
    }

    private void notifySessionCreated(RouteSessionController controller) {
        for (SessionCallbackRecord record: mSessionCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mSessionCallback.onSessionCreated(controller));
        }
    }

    private void notifySessionCreationFailed(MediaRoute2Info route, String controlCategory) {
        for (SessionCallbackRecord record: mSessionCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mSessionCallback.onSessionCreationFailed(route, controlCategory));
        }
    }

    private void notifySessionInfoChanged(RouteSessionController controller,
            RouteSessionInfo oldInfo, RouteSessionInfo newInfo) {
        for (SessionCallbackRecord record: mSessionCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mSessionCallback.onSessionInfoChanged(
                            controller, oldInfo, newInfo));
        }
    }

    private void notifyControllerReleased(RouteSessionController controller) {
        for (SessionCallbackRecord record: mSessionCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mSessionCallback.onSessionReleased(controller));
        }
    }

    /**
     * Callback for receiving events about media route discovery.
     */
    public static class RouteCallback {
        /**
         * Called when routes are added. Whenever you registers a callback, this will
         * be invoked with known routes.
         *
         * @param routes the list of routes that have been added. It's never empty.
         */
        public void onRoutesAdded(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are removed.
         *
         * @param routes the list of routes that have been removed. It's never empty.
         */
        public void onRoutesRemoved(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are changed. For example, it is called when the route's name
         * or volume have been changed.
         *
         * TODO: Write here what the developers should do when this method is called.
         * How they can find the exact point how a route is changed?
         * It can be a volume, name, client package name, ....
         *
         * @param routes the list of routes that have been changed. It's never empty.
         */
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}
    }

    /**
     * Callback for receiving a result of session creation and session updates.
     */
    public static class SessionCallback {
        /**
         * Called when the route session is created by the route provider.
         *
         * @param controller the controller to control the created session
         */
        public void onSessionCreated(@NonNull RouteSessionController controller) {}

        /**
         * Called when the session creation request failed.
         *
         * @param requestedRoute the route info which was used for the request
         * @param requestedControlCategory the control category which was used for the request
         */
        public void onSessionCreationFailed(@NonNull MediaRoute2Info requestedRoute,
                @NonNull String requestedControlCategory) {}

        /**
         * Called when the session info has changed.
         *
         * @param oldInfo the session info before the session changed.
         * @prarm newInfo the changed session info
         *
         * TODO: (Discussion) Do we really need newInfo? The controller has the newInfo.
         *       However. there can be timing issue if there is no newInfo.
         */
        public void onSessionInfoChanged(@NonNull RouteSessionController controller,
                @NonNull RouteSessionInfo oldInfo,
                @NonNull RouteSessionInfo newInfo) {}

        /**
         * Called when the session is released by {@link MediaRoute2ProviderService}.
         * Before this method is called, the controller would be released by the system,
         * which means the {@link RouteSessionController#isReleased()} will always return true
         * for the {@code controller} here.
         * <p>
         * Note: Calling {@link RouteSessionController#release()} will <em>NOT</em> trigger
         * this method to be called.
         *
         * TODO: Add tests for checking whether this method is called.
         * TODO: When service process dies, this should be called.
         *
         * @see RouteSessionController#isReleased()
         */
        public void onSessionReleased(@NonNull RouteSessionController controller) {}
    }

    /**
     * A class to control media route session in media route provider.
     * For example, selecting/deselcting/transferring routes to session can be done through this
     * class. Instances are created by {@link MediaRouter2}.
     *
     * TODO: Need to add toString()
     */
    public final class RouteSessionController {
        private final Object mControllerLock = new Object();

        @GuardedBy("mLock")
        private RouteSessionInfo mSessionInfo;

        @GuardedBy("mLock")
        private volatile boolean mIsReleased;

        RouteSessionController(@NonNull RouteSessionInfo sessionInfo) {
            mSessionInfo = sessionInfo;
        }

        /**
         * @return the ID of the session
         */
        public int getSessionId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getSessionId();
            }
        }

        /**
         * @return the unique ID of the session
         * @hide
         */
        @NonNull
        public String getUniqueSessionId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getUniqueSessionId();
            }
        }

        /**
         * @return the category of routes that the session includes.
         */
        @NonNull
        public String getControlCategory() {
            synchronized (mControllerLock) {
                return mSessionInfo.getControlCategory();
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
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getSelectedRoutes());
            }
        }

        /**
         * @return the unmodifiable list of selectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getSelectableRoutes() {
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getSelectableRoutes());
            }
        }

        /**
         * @return the unmodifiable list of deselectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getDeselectableRoutes() {
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getDeselectableRoutes());
            }
        }

        /**
         * @return the unmodifiable list of transferrable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getTransferrableRoutes() {
            synchronized (mControllerLock) {
                return getRoutesWithIdsLocked(mSessionInfo.getTransferrableRoutes());
            }
        }

        /**
         * Returns true if the session is released, false otherwise.
         * If it is released, then all other getters from this instance may return invalid values.
         * Also, any operations to this instance will be ignored once released.
         *
         * @see #release
         */
        public boolean isReleased() {
            synchronized (mControllerLock) {
                return mIsReleased;
            }
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
         * @see SessionCallback#onSessionInfoChanged
         */
        public void selectRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "selectRoute() called on released controller. Ignoring.");
                    return;
                }
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (checkRouteListContainsRouteId(selectedRoutes, route.getUniqueId())) {
                Log.w(TAG, "Ignoring selecting a route that is already selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> selectableRoutes = getSelectableRoutes();
            if (!checkRouteListContainsRouteId(selectableRoutes, route.getUniqueId())) {
                Log.w(TAG, "Ignoring selecting a non-selectable route=" + route);
                return;
            }

            Client2 client;
            synchronized (sRouterLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.selectRoute(client, getUniqueSessionId(), route);
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
         * @see SessionCallback#onSessionInfoChanged
         */
        public void deselectRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "deselectRoute() called on released controller. Ignoring.");
                    return;
                }
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (!checkRouteListContainsRouteId(selectedRoutes, route.getUniqueId())) {
                Log.w(TAG, "Ignoring deselecting a route that is not selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> deselectableRoutes = getDeselectableRoutes();
            if (!checkRouteListContainsRouteId(deselectableRoutes, route.getUniqueId())) {
                Log.w(TAG, "Ignoring deselecting a non-deselectable route=" + route);
                return;
            }

            Client2 client;
            synchronized (sRouterLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.deselectRoute(client, getUniqueSessionId(), route);
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
         * <li>ID should be included in {@link #getTransferrableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getTransferrableRoutes()
         * @see SessionCallback#onSessionInfoChanged
         */
        public void transferToRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "transferToRoute() called on released controller. Ignoring.");
                    return;
                }
            }

            List<MediaRoute2Info> selectedRoutes = getSelectedRoutes();
            if (checkRouteListContainsRouteId(selectedRoutes, route.getUniqueId())) {
                Log.w(TAG, "Ignoring transferring to a route that is already added. route="
                        + route);
                return;
            }

            List<MediaRoute2Info> transferrableRoutes = getTransferrableRoutes();
            if (!checkRouteListContainsRouteId(transferrableRoutes, route.getUniqueId())) {
                Log.w(TAG, "Ignoring transferring to a non-transferrable route=" + route);
                return;
            }

            Client2 client;
            synchronized (sRouterLock) {
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.transferToRoute(client, getUniqueSessionId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to transfer to route for session.", ex);
                }
            }
        }

        /**
         * Release this controller and corresponding session.
         * Any operations on this controller after calling this method will be ignored.
         * The devices that are playing media will stop playing it.
         *
         * TODO: Add tests using {@link MediaRouter2Manager#getActiveSessions()}.
         */
        public void release() {
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "release() called on released controller. Ignoring.");
                    return;
                }
                mIsReleased = true;
            }

            Client2 client;
            synchronized (sRouterLock) {
                mSessionControllers.remove(getUniqueSessionId(), this);
                client = mClient;
            }
            if (client != null) {
                try {
                    mMediaRouterService.releaseSession(client, getUniqueSessionId());
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to notify of controller release", ex);
                }
            }
        }

        @NonNull
        RouteSessionInfo getRouteSessionInfo() {
            synchronized (mControllerLock) {
                return mSessionInfo;
            }
        }

        void setRouteSessionInfo(@NonNull RouteSessionInfo info) {
            synchronized (mControllerLock) {
                mSessionInfo = info;
            }
        }

        // TODO: This method uses two locks (mLock outside, sLock inside).
        //       Check if there is any possiblity of deadlock.
        private List<MediaRoute2Info> getRoutesWithIdsLocked(List<String> routeIds) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            synchronized (sRouterLock) {
                for (String routeId : routeIds) {
                    MediaRoute2Info route = mRoutes.get(
                            MediaRoute2Info.toUniqueId(mSessionInfo.mProviderId, routeId));
                    if (route != null) {
                        routes.add(route);
                    }
                }
            }
            return Collections.unmodifiableList(routes);
        }
    }

    final class RouteCallbackRecord {
        public final Executor mExecutor;
        public final RouteCallback mRouteCallback;
        public final int mFlags;

        RouteCallbackRecord(@Nullable Executor executor, @NonNull RouteCallback routeCallback,
                int flags) {
            mRouteCallback = routeCallback;
            mExecutor = executor;
            mFlags = flags;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RouteCallbackRecord)) {
                return false;
            }
            return mRouteCallback == ((RouteCallbackRecord) obj).mRouteCallback;
        }

        @Override
        public int hashCode() {
            return mRouteCallback.hashCode();
        }
    }

    final class SessionCallbackRecord {
        public final Executor mExecutor;
        public final SessionCallback mSessionCallback;

        SessionCallbackRecord(@NonNull Executor executor,
                @NonNull SessionCallback sessionCallback) {
            mSessionCallback = sessionCallback;
            mExecutor = executor;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SessionCallbackRecord)) {
                return false;
            }
            return mSessionCallback == ((SessionCallbackRecord) obj).mSessionCallback;
        }

        @Override
        public int hashCode() {
            return mSessionCallback.hashCode();
        }
    }

    final class SessionCreationRequest {
        public final MediaRoute2Info mRoute;
        public final String mControlCategory;
        public final int mRequestId;

        SessionCreationRequest(int requestId, @NonNull MediaRoute2Info route,
                @NonNull String controlCategory) {
            mRoute = route;
            mControlCategory = controlCategory;
            mRequestId = requestId;
        }
    }

    class Client2 extends IMediaRouter2Client.Stub {
        @Override
        public void notifyRestoreRoute() throws RemoteException {}

        @Override
        public void notifyRoutesAdded(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::addRoutesOnHandler,
                    MediaRouter2.this, routes));
        }

        @Override
        public void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::removeRoutesOnHandler,
                    MediaRouter2.this, routes));
        }

        @Override
        public void notifyRoutesChanged(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::changeRoutesOnHandler,
                    MediaRouter2.this, routes));
        }

        @Override
        public void notifySessionCreated(@Nullable RouteSessionInfo sessionInfo, int requestId) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::createControllerOnHandler,
                    MediaRouter2.this, sessionInfo, requestId));
        }

        @Override
        public void notifySessionInfoChanged(@Nullable RouteSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::changeSessionInfoOnHandler,
                    MediaRouter2.this, sessionInfo));
        }

        @Override
        public void notifySessionReleased(RouteSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::releaseControllerOnHandler,
                    MediaRouter2.this, sessionInfo));
        }
    }
}
