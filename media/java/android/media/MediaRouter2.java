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
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
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
    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2 sInstance;

    private final Context mContext;
    private final IMediaRouterService mMediaRouterService;

    private final CopyOnWriteArrayList<RouteCallbackRecord> mRouteCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<SessionCreationRequest> mSessionCreationRequests =
            new CopyOnWriteArrayList<>();

    private final String mPackageName;
    @GuardedBy("sLock")
    private final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    @GuardedBy("sLock")
    private List<String> mControlCategories = Collections.emptyList();

    // TODO: Make MediaRouter2 is always connected to the MediaRouterService.
    @GuardedBy("sLock")
    private Client2 mClient;

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
        synchronized (sLock) {
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
     * Registers a callback to discover routes and to receive events when they change.
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback) {
        registerCallback(executor, routeCallback, 0);
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     * <p>
     * If you register the same callback twice or more, it will be ignored.
     * </p>
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback, int flags) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeCallback, "callback must not be null");

        RouteCallbackRecord record = new RouteCallbackRecord(executor, routeCallback, flags);
        if (!mRouteCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "Ignoring the same callback");
            return;
        }

        synchronized (sLock) {
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
     * @see #registerCallback
     */
    public void unregisterCallback(@NonNull RouteCallback routeCallback) {
        Objects.requireNonNull(routeCallback, "callback must not be null");

        if (!mRouteCallbackRecords.remove(
                new RouteCallbackRecord(null, routeCallback, 0))) {
            Log.w(TAG, "Ignoring unknown callback");
            return;
        }

        synchronized (sLock) {
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

        synchronized (sLock) {
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
        synchronized (sLock) {
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
     * Requests the media route provider service to create a session with the given route.
     *
     * @param route the route you want to create a session with.
     * @param controlCategory the control category of the session. Should not be empty
     * @param executor the executor to get the result of the session creation
     * @param callback the callback to get the result of the session creation
     *
     * @see SessionCallback#onSessionCreated(RouteSessionController, Bundle)
     * @see SessionCallback#onSessionCreationFailed()
     *
     * TODO: Separate callback registeration from creating session request.
     */
    @NonNull
    public void requestCreateSession(@NonNull MediaRoute2Info route,
            @NonNull String controlCategory,
            @CallbackExecutor Executor executor, @NonNull SessionCallback callback) {
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(controlCategory)) {
            throw new IllegalArgumentException("controlCategory must not be empty");
        }
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        // TODO: Check the given route exists
        // TODO: Check the route supports the given controlCategory

        final int requestId;
        // TODO: This does not ensure the uniqueness of the request ID.
        //       Find the way to ensure it. (e.g. have mapping inside MediaRouterService)
        requestId = Process.myPid() * 10000 + mSessionCreationRequestCnt.getAndIncrement();

        SessionCreationRequest request = new SessionCreationRequest(
                requestId, route, controlCategory, executor, callback);
        mSessionCreationRequests.add(request);

        Client2 client;
        synchronized (sLock) {
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
        synchronized (sLock) {
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
        synchronized (sLock) {
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
        synchronized (sLock) {
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
        synchronized (sLock) {
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
        synchronized (sLock) {
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
        synchronized (sLock) {
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
     * {@link SessionCallback#onSessionCreationFailed()}.
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

        if (matchingRequest == null) {
            Log.w(TAG, "Ignoring session creation result for unknown request."
                    + " requestId=" + requestId + ", sessionInfo=" + sessionInfo);
            return;
        }

        mSessionCreationRequests.remove(matchingRequest);

        final Executor executor = matchingRequest.mExecutor;
        final SessionCallback callback = matchingRequest.mSessionCallback;

        if (sessionInfo == null) {
            // TODO: We may need to distinguish between failure and rejection.
            //       One way can be introducing 'reason'.
            executor.execute(callback::onSessionCreationFailed);
        } else {
            // TODO: RouteSessionController should be created with full info (e.g. routes)
            //       from RouteSessionInfo.
            RouteSessionController controller = new RouteSessionController(sessionInfo);
            executor.execute(() -> callback.onSessionCreated(controller));
        }
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
        public void onSessionCreated(RouteSessionController controller) {}

        /**
         * Called when the session creation request failed.
         */
        public void onSessionCreationFailed() {}

        /**
         * Called when the session info has changed.
         */
        void onSessionInfoChanged(RouteSessionController controller, RouteSessionInfo newInfo,
                RouteSessionInfo oldInfo) {}

        /**
         * Called when the session is released. Session can be released by the controller using
         * {@link RouteSessionController#release(boolean)}, or by the
         * {@link MediaRoute2ProviderService} itself. One can do clean-ups here.
         *
         * TODO: When Provider#notifySessionDestroyed is introduced, add @see for the method.
         */
        void onSessionReleased(RouteSessionController controller, int reason, boolean shouldStop) {}
    }

    /**
     * A class to control media route session in media route provider.
     * For example, adding/removing/transferring routes to session can be done through this class.
     * Instances are created by {@link MediaRouter2}.
     *
     * @hide
     */
    public final class RouteSessionController {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private RouteSessionInfo mSessionInfo;

        @GuardedBy("mLock")
        private volatile boolean mIsReleased;

        RouteSessionController(@NonNull RouteSessionInfo sessionInfo) {
            mSessionInfo = sessionInfo;
        }

        /**
         * @return the ID of this controller
         */
        public int getSessionId() {
            synchronized (mLock) {
                return mSessionInfo.getSessionId();
            }
        }

        /**
         * @return the category of routes that the session includes.
         */
        @NonNull
        public String getControlCategory() {
            synchronized (mLock) {
                return mSessionInfo.getControlCategory();
            }
        }

        /**
         * @return the control hints used to control route session if available.
         */
        @Nullable
        public Bundle getControlHints() {
            synchronized (mLock) {
                return mSessionInfo.getControlHints();
            }
        }

        /**
         * @return the unmodifiable list of IDs of currently selected routes
         */
        @NonNull
        public List<String> getSelectedRoutes() {
            synchronized (mLock) {
                return Collections.unmodifiableList(mSessionInfo.getSelectedRoutes());
            }
        }

        /**
         * @return the unmodifiable list of IDs of deselectable routes for the session.
         */
        @NonNull
        public List<String> getDeselectableRoutes() {
            synchronized (mLock) {
                return Collections.unmodifiableList(mSessionInfo.getDeselectableRoutes());
            }
        }

        /**
         * @return the unmodifiable list of IDs of groupable routes for the session.
         */
        @NonNull
        public List<String> getGroupableRoutes() {
            synchronized (mLock) {
                return Collections.unmodifiableList(mSessionInfo.getGroupableRoutes());
            }
        }

        /**
         * @return the unmodifiable list of IDs of transferrable routes for the session.
         */
        @NonNull
        public List<String> getTransferrableRoutes() {
            synchronized (mLock) {
                return Collections.unmodifiableList(mSessionInfo.getTransferrableRoutes());
            }
        }

        /**
         * Returns true if the session is released, false otherwise.
         * If it is released, then all other getters from this instance may return invalid values.
         * Also, any operations to this instance will be ignored once released.
         *
         * @see #release
         * @see SessionCallback#onSessionReleased
         */
        public boolean isReleased() {
            synchronized (mLock) {
                return mIsReleased;
            }
        }

        /**
         * Add routes to the remote session. Route add requests that are currently in
         * {@link #getSelectedRoutes()} will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see SessionCallback#onSessionInfoChanged
         */
        public void addRoute(MediaRoute2Info route) {
            // TODO: Implement this when the actual connection logic is implemented.
        }

        /**
         * Remove routes from this session. Media may be stopped on those devices.
         * Route removal requests that are not currently in {@link #getSelectedRoutes()} will be
         * ignored.
         *
         * @see #getSelectedRoutes()
         * @see SessionCallback#onSessionInfoChanged
         */
        public void removeRoute(MediaRoute2Info route) {
            // TODO: Implement this when the actual connection logic is implemented.
        }

        /**
         * Release this session.
         * Any operation on this session after calling this method will be ignored.
         *
         * @param stopMedia Should the media that is playing on the device be stopped after this
         *                  session is released.
         * @see SessionCallback#onSessionReleased
         */
        public void release(boolean stopMedia) {
            synchronized (mLock) {
                if (mIsReleased) {
                    return;
                }
                mIsReleased = true;
            }
            // TODO: Use stopMedia variable when the actual connection logic is implemented.
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

    final class SessionCreationRequest {
        public final MediaRoute2Info mRoute;
        public final String mControlCategory;
        public final Executor mExecutor;
        public final SessionCallback mSessionCallback;
        public final int mRequestId;

        SessionCreationRequest(int requestId, @NonNull MediaRoute2Info route,
                @NonNull String controlCategory,
                @Nullable Executor executor,
                @NonNull SessionCallback sessionCallback) {
            mRoute = route;
            mControlCategory = controlCategory;
            mExecutor = executor;
            mSessionCallback = sessionCallback;
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
    }
}
