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
import android.annotation.TestApi;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

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
import java.util.stream.Collectors;

/**
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
  <a href="{@docRoot}reference/androidx/mediarouter/media/package-summary.html">Media Router
 * Library</a> for consistent behavior across all devices.
 *
 * Media Router 2 allows applications to control the routing of media channels
 * and streams from the current device to remote speakers and devices.
 */
// TODO: Add method names at the beginning of log messages. (e.g. updateControllerOnHandler)
//       Not only MediaRouter2, but also to service / manager / provider.
// TODO: ensure thread-safe and document it
public class MediaRouter2 {
    private static final String TAG = "MR2";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Object sRouterLock = new Object();

    @GuardedBy("sRouterLock")
    private static MediaRouter2 sInstance;

    private final Context mContext;
    private final IMediaRouterService mMediaRouterService;

    private final CopyOnWriteArrayList<RouteCallbackRecord> mRouteCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TransferCallbackRecord> mTransferCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ControllerCallbackRecord> mControllerCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ControllerCreationRequest> mControllerCreationRequests =
            new CopyOnWriteArrayList<>();

    private final String mPackageName;
    @GuardedBy("sRouterLock")
    final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    final RoutingController mSystemController;

    @GuardedBy("sRouterLock")
    private RouteDiscoveryPreference mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;

    // TODO: Make MediaRouter2 is always connected to the MediaRouterService.
    @GuardedBy("sRouterLock")
    MediaRouter2Stub mStub;

    @GuardedBy("sRouterLock")
    private Map<String, RoutingController> mRoutingControllers = new ArrayMap<>();

    private AtomicInteger mControllerCreationRequestCnt = new AtomicInteger(1);

    final Handler mHandler;
    @GuardedBy("sRouterLock")
    private boolean mShouldUpdateRoutes;
    private volatile List<MediaRoute2Info> mFilteredRoutes = Collections.emptyList();
    private volatile OnGetControllerHintsListener mOnGetControllerHintsListener;

    /**
     * Gets an instance of the media router associated with the context.
     */
    @NonNull
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
        mHandler = new Handler(Looper.getMainLooper());

        List<MediaRoute2Info> currentSystemRoutes = null;
        RoutingSessionInfo currentSystemSessionInfo = null;
        try {
            currentSystemRoutes = mMediaRouterService.getSystemRoutes();
            currentSystemSessionInfo = mMediaRouterService.getSystemSessionInfo();
        } catch (RemoteException ex) {
            Log.e(TAG, "Unable to get current system's routes / session info", ex);
        }

        if (currentSystemRoutes == null || currentSystemRoutes.isEmpty()) {
            throw new RuntimeException("Null or empty currentSystemRoutes. Something is wrong.");
        }

        if (currentSystemSessionInfo == null) {
            throw new RuntimeException("Null currentSystemSessionInfo. Something is wrong.");
        }

        for (MediaRoute2Info route : currentSystemRoutes) {
            mRoutes.put(route.getId(), route);
        }
        mSystemController = new SystemRoutingController(currentSystemSessionInfo);
    }

    /**
     * Returns whether any route in {@code routeList} has a same unique ID with given route.
     *
     * @hide
     */
    public static boolean checkRouteListContainsRouteId(@NonNull List<MediaRoute2Info> routeList,
            @NonNull String routeId) {
        for (MediaRoute2Info info : routeList) {
            if (TextUtils.equals(routeId, info.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     * <p>
     * If the specified callback is already registered, its registration will be updated for the
     * given {@link Executor executor} and {@link RouteDiscoveryPreference discovery preference}.
     * </p>
     */
    public void registerRouteCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback,
            @NonNull RouteDiscoveryPreference preference) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeCallback, "callback must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        RouteCallbackRecord record = new RouteCallbackRecord(executor, routeCallback, preference);

        mRouteCallbackRecords.remove(record);
        // It can fail to add the callback record if another registration with the same callback
        // is happening but it's okay because either this or the other registration should be done.
        mRouteCallbackRecords.addIfAbsent(record);

        synchronized (sRouterLock) {
            if (mStub == null) {
                MediaRouter2Stub stub = new MediaRouter2Stub();
                try {
                    mMediaRouterService.registerRouter2(stub, mPackageName);
                    mStub = stub;
                } catch (RemoteException ex) {
                    Log.e(TAG, "registerRouteCallback: Unable to register MediaRouter2.", ex);
                }
            }
            if (mStub != null && updateDiscoveryPreferenceIfNeededLocked()) {
                try {
                    mMediaRouterService.setDiscoveryRequestWithRouter2(mStub, mDiscoveryPreference);
                } catch (RemoteException ex) {
                    Log.e(TAG, "registerRouteCallback: Unable to set discovery request.");
                }
            }
        }
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
                new RouteCallbackRecord(null, routeCallback, null))) {
            Log.w(TAG, "Ignoring unknown callback");
            return;
        }

        synchronized (sRouterLock) {
            if (mStub == null) {
                return;
            }
            if (updateDiscoveryPreferenceIfNeededLocked()) {
                try {
                    mMediaRouterService.setDiscoveryRequestWithRouter2(
                            mStub, mDiscoveryPreference);
                } catch (RemoteException ex) {
                    Log.e(TAG, "unregisterRouteCallback: Unable to set discovery request.");
                }
            }
            if (mRouteCallbackRecords.size() == 0) {
                try {
                    mMediaRouterService.unregisterRouter2(mStub);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to unregister media router.", ex);
                }
            }
            mShouldUpdateRoutes = true;
            mStub = null;
        }
    }

    private boolean updateDiscoveryPreferenceIfNeededLocked() {
        RouteDiscoveryPreference newDiscoveryPreference = new RouteDiscoveryPreference.Builder(
                mRouteCallbackRecords.stream().map(record -> record.mPreference).collect(
                        Collectors.toList())).build();
        if (Objects.equals(mDiscoveryPreference, newDiscoveryPreference)) {
            return false;
        }
        mDiscoveryPreference = newDiscoveryPreference;
        mShouldUpdateRoutes = true;
        return true;
    }

    /**
     * Gets the unmodifiable list of {@link MediaRoute2Info routes} currently
     * known to the media router.
     * <p>
     * {@link MediaRoute2Info#isSystemRoute() System routes} such as phone speaker,
     * Bluetooth devices are always included in the list.
     * Please note that the list can be changed before callbacks are invoked.
     * </p>
     *
     * @return the list of routes that contains at least one of the route features in discovery
     * preferences registered by the application
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        synchronized (sRouterLock) {
            if (mShouldUpdateRoutes) {
                mShouldUpdateRoutes = false;

                List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
                for (MediaRoute2Info route : mRoutes.values()) {
                    if (route.isSystemRoute()
                            || route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                        filteredRoutes.add(route);
                    }
                }
                mFilteredRoutes = Collections.unmodifiableList(filteredRoutes);
            }
        }
        return mFilteredRoutes;
    }

    /**
     * Registers a callback to get the result of {@link #transferTo(MediaRoute2Info)}.
     * If you register the same callback twice or more, it will be ignored.
     *
     * @param executor the executor to execute the callback on
     * @param callback the callback to register
     * @see #unregisterTransferCallback
     */
    public void registerTransferCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull TransferCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        TransferCallbackRecord record = new TransferCallbackRecord(executor, callback);
        if (!mTransferCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "registerTransferCallback: Ignoring the same callback");
            return;
        }
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @param callback the callback to unregister
     * @see #registerTransferCallback
     */
    public void unregisterTransferCallback(@NonNull TransferCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mTransferCallbackRecords.remove(new TransferCallbackRecord(null, callback))) {
            Log.w(TAG, "unregisterTransferCallback: Ignoring an unknown callback");
            return;
        }
    }

    /**
     * Registers a {@link ControllerCallback}.
     * If you register the same callback twice or more, it will be ignored.
     * @see #unregisterControllerCallback(ControllerCallback)
     */
    public void registerControllerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull ControllerCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        ControllerCallbackRecord record = new ControllerCallbackRecord(executor, callback);
        if (!mControllerCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "registerControllerCallback: Ignoring the same callback");
            return;
        }
    }

    /**
     * Unregisters a {@link ControllerCallback}. The callback will no longer receive
     * events. If the callback has not been added or been removed already, it is ignored.
     * @see #registerControllerCallback(Executor, ControllerCallback)
     */
    public void unregisterControllerCallback(
            @NonNull ControllerCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mControllerCallbackRecords.remove(new ControllerCallbackRecord(null, callback))) {
            Log.w(TAG, "unregisterControllerCallback: Ignoring an unknown callback");
            return;
        }
    }

    /**
     * Sets an {@link OnGetControllerHintsListener} to send hints when creating a
     * {@link RoutingController}. To send the hints, listener should be set <em>BEFORE</em> calling
     * {@link #transferTo(MediaRoute2Info)}.
     *
     * @param listener A listener to send optional app-specific hints when creating a controller.
     *                 {@code null} for unset.
     */
    public void setOnGetControllerHintsListener(@Nullable OnGetControllerHintsListener listener) {
        mOnGetControllerHintsListener = listener;
    }

    /**
     * Transfers the current media to the given route.
     * If it's necessary a new {@link RoutingController} is created or it is handled within
     * the current routing controller.
     *
     * @param route the route you want to transfer the current media to. Pass {@code null} to
     *              stop routing of the current media.
     *
     * @see TransferCallback#onTransferred
     * @see TransferCallback#onTransferFailed
     */
    public void transferTo(@NonNull MediaRoute2Info route) {
        Objects.requireNonNull(route, "route must not be null");

        List<RoutingController> controllers = getControllers();
        RoutingController controller = controllers.get(controllers.size() - 1);

        transfer(controller, route);
    }

    /**
     * Stops the current media routing. If the {@link #getSystemController() system controller}
     * controls the media routing, this method is a no-op.
     */
    public void stop() {
        List<RoutingController> controllers = getControllers();
        RoutingController controller = controllers.get(controllers.size() - 1);

        controller.release();
    }

    /**
     * Transfers the media of a routing controller to the given route.
     * @param controller a routing controller controlling media routing.
     * @param route the route you want to transfer the media to.
     * @hide
     */
    void transfer(@NonNull RoutingController controller, @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(controller, "controller must not be null");
        Objects.requireNonNull(route, "route must not be null");

        // TODO: Check thread-safety
        if (!mRoutes.containsKey(route.getId())) {
            notifyTransferFailed(route);
            return;
        }
        if (controller.getRoutingSessionInfo().getTransferableRoutes().contains(route.getId())) {
            controller.transferToRoute(route);
            return;
        }

        controller.release();

        final int requestId;
        requestId = mControllerCreationRequestCnt.getAndIncrement();

        ControllerCreationRequest request =
                new ControllerCreationRequest(requestId, controller, route);
        mControllerCreationRequests.add(request);

        OnGetControllerHintsListener listener = mOnGetControllerHintsListener;
        Bundle controllerHints = null;
        if (listener != null) {
            controllerHints = listener.onGetControllerHints(route);
            if (controllerHints != null) {
                controllerHints = new Bundle(controllerHints);
            }
        }

        MediaRouter2Stub stub;
        synchronized (sRouterLock) {
            stub = mStub;
        }
        if (stub != null) {
            try {
                mMediaRouterService.requestCreateSessionWithRouter2(
                        stub, requestId, route, controllerHints);
            } catch (RemoteException ex) {
                Log.e(TAG, "transfer: Unable to request to create controller.", ex);
                mHandler.sendMessage(obtainMessage(MediaRouter2::createControllerOnHandler,
                        MediaRouter2.this, requestId, null));
            }
        }
    }

    /**
     * Gets a {@link RoutingController} which can control the routes provided by system.
     * e.g. Phone speaker, wired headset, Bluetooth, etc.
     * <p>
     * Note: The system controller can't be released. Calling {@link RoutingController#release()}
     * will be ignored.
     * <p>
     * This method always returns the same instance.
     */
    @NonNull
    public RoutingController getSystemController() {
        return mSystemController;
    }

    /**
     * Gets the list of currently non-released {@link RoutingController routing controllers}.
     * <p>
     * Note: The list returned here will never be empty. The first element in the list is
     * always the {@link #getSystemController() system controller}.
     */
    @NonNull
    public List<RoutingController> getControllers() {
        List<RoutingController> result = new ArrayList<>();
        result.add(0, mSystemController);

        Collection<RoutingController> controllers;
        synchronized (sRouterLock) {
            controllers = mRoutingControllers.values();
            if (controllers != null) {
                result.addAll(controllers);
            }
        }
        return result;
    }

    /**
     * Requests a volume change for the route asynchronously.
     * <p>
     * It may have no effect if the route is currently not selected.
     * </p>
     *
     * @param volume The new volume value between 0 and {@link MediaRoute2Info#getVolumeMax}.
     * @hide
     */
    public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        MediaRouter2Stub stub;
        synchronized (sRouterLock) {
            stub = mStub;
        }
        if (stub != null) {
            try {
                mMediaRouterService.setRouteVolumeWithRouter2(stub, route, volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
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
                mRoutes.put(route.getId(), route);
                if (route.isSystemRoute()
                        || route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
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
                mRoutes.remove(route.getId());
                if (route.isSystemRoute()
                        || route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
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
                mRoutes.put(route.getId(), route);
                if (route.isSystemRoute()
                        || route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                    changedRoutes.add(route);
                }
            }
        }
        if (changedRoutes.size() > 0) {
            notifyRoutesChanged(changedRoutes);
        }
    }

    /**
     * Creates a controller and calls the {@link TransferCallback#onTransferred}.
     * If the controller creation has failed, then it calls
     * {@link TransferCallback#onTransferFailed}.
     * <p>
     * Pass {@code null} to sessionInfo for the failure case.
     */
    void createControllerOnHandler(int requestId, @Nullable RoutingSessionInfo sessionInfo) {
        ControllerCreationRequest matchingRequest = null;
        for (ControllerCreationRequest request : mControllerCreationRequests) {
            if (request.mRequestId == requestId) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest != null) {
            mControllerCreationRequests.remove(matchingRequest);

            MediaRoute2Info requestedRoute = matchingRequest.mRoute;

            if (sessionInfo == null) {
                // TODO: We may need to distinguish between failure and rejection.
                //       One way can be introducing 'reason'.
                notifyTransferFailed(requestedRoute);
                return;
            } else if (!sessionInfo.getSelectedRoutes().contains(requestedRoute.getId())) {
                Log.w(TAG, "The session does not contain the requested route. "
                        + "(requestedRouteId=" + requestedRoute.getId()
                        + ", actualRoutes=" + sessionInfo.getSelectedRoutes()
                        + ")");
                notifyTransferFailed(requestedRoute);
                return;
            } else if (!TextUtils.equals(requestedRoute.getProviderId(),
                    sessionInfo.getProviderId())) {
                Log.w(TAG, "The session's provider ID does not match the requested route's. "
                        + "(requested route's providerId=" + requestedRoute.getProviderId()
                        + ", actual providerId=" + sessionInfo.getProviderId()
                        + ")");
                notifyTransferFailed(requestedRoute);
                return;
            }
        }

        if (sessionInfo != null) {
            RoutingController newController = new RoutingController(sessionInfo);
            synchronized (sRouterLock) {
                mRoutingControllers.put(newController.getId(), newController);
            }
            notifyTransferred(matchingRequest != null ? matchingRequest.mController :
                    getSystemController(), newController);
        }
    }

    void updateControllerOnHandler(RoutingSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            Log.w(TAG, "updateControllerOnHandler: Ignoring null sessionInfo.");
            return;
        }

        if (sessionInfo.isSystemSession()) {
            // The session info is sent from SystemMediaRoute2Provider.
            RoutingController systemController = getSystemController();
            systemController.setRoutingSessionInfo(sessionInfo);
            notifyControllerUpdated(systemController);
            return;
        }

        RoutingController matchingController;
        synchronized (sRouterLock) {
            matchingController = mRoutingControllers.get(sessionInfo.getId());
        }

        if (matchingController == null) {
            Log.w(TAG, "updateControllerOnHandler: Matching controller not found. uniqueSessionId="
                    + sessionInfo.getId());
            return;
        }

        RoutingSessionInfo oldInfo = matchingController.getRoutingSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(TAG, "updateControllerOnHandler: Provider IDs are not matched. old="
                    + oldInfo.getProviderId() + ", new=" + sessionInfo.getProviderId());
            return;
        }

        matchingController.setRoutingSessionInfo(sessionInfo);
        notifyControllerUpdated(matchingController);
    }

    void releaseControllerOnHandler(RoutingSessionInfo sessionInfo) {
        if (sessionInfo == null) {
            Log.w(TAG, "releaseControllerOnHandler: Ignoring null sessionInfo.");
            return;
        }

        final String uniqueSessionId = sessionInfo.getId();
        RoutingController matchingController;
        synchronized (sRouterLock) {
            matchingController = mRoutingControllers.get(uniqueSessionId);
        }

        if (matchingController == null) {
            if (DEBUG) {
                Log.d(TAG, "releaseControllerOnHandler: Matching controller not found. "
                        + "uniqueSessionId=" + sessionInfo.getId());
            }
            return;
        }

        RoutingSessionInfo oldInfo = matchingController.getRoutingSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(TAG, "releaseControllerOnHandler: Provider IDs are not matched. old="
                    + oldInfo.getProviderId() + ", new=" + sessionInfo.getProviderId());
            return;
        }

        boolean removed;
        synchronized (sRouterLock) {
            removed = mRoutingControllers.remove(uniqueSessionId, matchingController);
        }

        if (removed) {
            matchingController.release();
            notifyStopped(matchingController);
        }
    }

    private List<MediaRoute2Info> filterRoutes(List<MediaRoute2Info> routes,
            RouteDiscoveryPreference discoveryRequest) {
        return routes.stream()
                .filter(route -> route.isSystemRoute()
                        || route.hasAnyFeatures(discoveryRequest.getPreferredFeatures()))
                .collect(Collectors.toList());
    }

    private void notifyRoutesAdded(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes = filterRoutes(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesAdded(filteredRoutes));
            }
        }
    }

    private void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes = filterRoutes(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesRemoved(filteredRoutes));
            }
        }
    }

    private void notifyRoutesChanged(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes = filterRoutes(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesChanged(filteredRoutes));
            }
        }
    }

    private void notifyTransferred(RoutingController oldController,
            RoutingController newController) {
        for (TransferCallbackRecord record: mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onTransferred(oldController, newController));
        }
    }

    private void notifyTransferFailed(MediaRoute2Info route) {
        for (TransferCallbackRecord record: mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onTransferFailed(route));
        }
    }

    private void notifyStopped(RoutingController controller) {
        for (TransferCallbackRecord record: mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onStopped(controller));
        }
    }

    private void notifyControllerUpdated(RoutingController controller) {
        for (ControllerCallbackRecord record: mControllerCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onControllerUpdated(controller));
        }
    }

    /**
     * Callback for receiving events about media route discovery.
     */
    public abstract static class RouteCallback {
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
         * @param routes the list of routes that have been changed. It's never empty.
         */
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}
    }

    /**
     * Callback for receiving events on media transfer.
     */
    public abstract static class TransferCallback {
        /**
         * Called when a media is transferred between two different routing controllers.
         * This can happen by calling {@link #transferTo(MediaRoute2Info)} or
         * {@link RoutingController#release()}.
         *
         * @param oldController the previous controller that controlled routing
         * @param newController the new controller to control routing
         * @see #transferTo(MediaRoute2Info)
         */
        public void onTransferred(@NonNull RoutingController oldController,
                @NonNull RoutingController newController) {}

        /**
         * Called when {@link #transferTo(MediaRoute2Info)} failed.
         *
         * @param requestedRoute the route info which was used for the transfer
         */
        public void onTransferFailed(@NonNull MediaRoute2Info requestedRoute) {}

        /**
         * Called when a media routing stops. It can be stopped by a user or a provider.
         *
         * @param controller the controller that controlled the stopped media routing.
         */
        public void onStopped(@NonNull RoutingController controller) { }
    }

    /**
     * A listener interface to send an optional app-specific hints when creating the
     * {@link RoutingController}.
     */
    public interface OnGetControllerHintsListener {
        /**
         * Called when the {@link MediaRouter2} is about to request
         * the media route provider service to create a controller with the given route.
         * The {@link Bundle} returned here will be sent to media route provider service as a hint.
         * <p>
         * To send hints when creating the controller, set the listener before calling
         * {@link #transferTo(MediaRoute2Info)}. The method will be called
         * on the same thread which calls {@link #transferTo(MediaRoute2Info)}.
         *
         * @param route The route to create controller with
         * @return An optional bundle of app-specific arguments to send to the provider,
         *         or null if none. The contents of this bundle may affect the result of
         *         controller creation.
         * @see MediaRoute2ProviderService#onCreateSession(long, String, String, Bundle)
         */
        @Nullable
        Bundle onGetControllerHints(@NonNull MediaRoute2Info route);
    }

    /**
     * Callback for receiving {@link RoutingController} updates.
     */
    public abstract static class ControllerCallback {
        /**
         * Called when a controller is updated. (e.g., the selected routes of the
         * controller is changed or the volume of the controller is changed.)
         *
         * @param controller the updated controller. Can be the system controller.
         * @see #getSystemController()
         */
        public void onControllerUpdated(@NonNull RoutingController controller) { }
    }

    /**
     * A class to control media routing session in media route provider.
     * For example, selecting/deselecting/transferring routes to a session can be done through this
     * class. Instances are created by {@link #transferTo(MediaRoute2Info)}.
     */
    public class RoutingController {
        private final Object mControllerLock = new Object();

        @GuardedBy("mControllerLock")
        private RoutingSessionInfo mSessionInfo;

        @GuardedBy("mControllerLock")
        private volatile boolean mIsReleased;

        RoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            mSessionInfo = sessionInfo;
        }

        /**
         * @return the ID of the controller. It is globally unique.
         */
        @NonNull
        public String getId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getId();
            }
        }

        /**
         * Gets the original session id set by
         * {@link RoutingSessionInfo.Builder#Builder(String, String)}.
         *
         * @hide
         */
        @NonNull
        @TestApi
        public String getOriginalId() {
            synchronized (mControllerLock) {
                return mSessionInfo.getOriginalId();
            }
        }

        /**
         * @return the control hints used to control routing session if available.
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
            List<String> selectedRouteIds;
            synchronized (mControllerLock) {
                selectedRouteIds = mSessionInfo.getSelectedRoutes();
            }
            return getRoutesWithIds(selectedRouteIds);
        }

        /**
         * @return the unmodifiable list of selectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getSelectableRoutes() {
            List<String> selectableRouteIds;
            synchronized (mControllerLock) {
                selectableRouteIds = mSessionInfo.getSelectableRoutes();
            }
            return getRoutesWithIds(selectableRouteIds);
        }

        /**
         * @return the unmodifiable list of deselectable routes for the session.
         */
        @NonNull
        public List<MediaRoute2Info> getDeselectableRoutes() {
            List<String> deselectableRouteIds;
            synchronized (mControllerLock) {
                deselectableRouteIds = mSessionInfo.getDeselectableRoutes();
            }
            return getRoutesWithIds(deselectableRouteIds);
        }

        /**
         * Gets information about how volume is handled on the session.
         *
         * @return {@link MediaRoute2Info#PLAYBACK_VOLUME_FIXED} or
         * {@link MediaRoute2Info#PLAYBACK_VOLUME_VARIABLE}
         */
        @MediaRoute2Info.PlaybackVolume
        public int getVolumeHandling() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolumeHandling();
            }
        }

        /**
         * Gets the maximum volume of the session.
         */
        public int getVolumeMax() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolumeMax();
            }
        }

        /**
         * Gets the current volume of the session.
         * <p>
         * When it's available, it represents the volume of routing session, which is a group
         * of selected routes. To get the volume of a route,
         * use {@link MediaRoute2Info#getVolume()}.
         * </p>
         * @see MediaRoute2Info#getVolume()
         */
        public int getVolume() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolume();
            }
        }

        /**
         * Returns true if this controller is released, false otherwise.
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
         * Selects a route for the remote session. After a route is selected, the media is expected
         * to be played to the all the selected routes. This is different from {@link
         * MediaRouter2#transferTo(MediaRoute2Info)} transferring to a route},
         * where the media is expected to 'move' from one route to another.
         * <p>
         * The given route must satisfy all of the following conditions:
         * <ul>
         * <li>ID should not be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getSelectableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #deselectRoute(MediaRoute2Info)
         * @see #getSelectedRoutes()
         * @see #getSelectableRoutes()
         * @see ControllerCallback#onControllerUpdated
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
            if (checkRouteListContainsRouteId(selectedRoutes, route.getId())) {
                Log.w(TAG, "Ignoring selecting a route that is already selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> selectableRoutes = getSelectableRoutes();
            if (!checkRouteListContainsRouteId(selectableRoutes, route.getId())) {
                Log.w(TAG, "Ignoring selecting a non-selectable route=" + route);
                return;
            }

            MediaRouter2Stub stub;
            synchronized (sRouterLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.selectRouteWithRouter2(stub, getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to select route for session.", ex);
                }
            }
        }

        /**
         * Deselects a route from the remote session. After a route is deselected, the media is
         * expected to be stopped on the deselected routes.
         * <p>
         * The given route must satisfy all of the following conditions:
         * <ul>
         * <li>ID should be included in {@link #getSelectedRoutes()}</li>
         * <li>ID should be included in {@link #getDeselectableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getDeselectableRoutes()
         * @see ControllerCallback#onControllerUpdated
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
            if (!checkRouteListContainsRouteId(selectedRoutes, route.getId())) {
                Log.w(TAG, "Ignoring deselecting a route that is not selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> deselectableRoutes = getDeselectableRoutes();
            if (!checkRouteListContainsRouteId(deselectableRoutes, route.getId())) {
                Log.w(TAG, "Ignoring deselecting a non-deselectable route=" + route);
                return;
            }

            MediaRouter2Stub stub;
            synchronized (sRouterLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.deselectRouteWithRouter2(stub, getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to deselect route from session.", ex);
                }
            }
        }

        /**
         * Transfers to a given route for the remote session. The given route must satisfy
         * all of the following conditions:
         * <ul>
         * <li>ID should not be included in {@link RoutingSessionInfo#getSelectedRoutes()}</li>
         * <li>ID should be included in {@link RoutingSessionInfo#getTransferableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see RoutingSessionInfo#getSelectedRoutes()
         * @see RoutingSessionInfo#getTransferableRoutes()
         * @see ControllerCallback#onControllerUpdated
         */
        void transferToRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "transferToRoute() called on released controller. Ignoring.");
                    return;
                }

                if (mSessionInfo.getSelectedRoutes().contains(route.getId())) {
                    Log.w(TAG, "Ignoring transferring to a route that is already added. "
                            + "route=" + route);
                    return;
                }

                if (!mSessionInfo.getTransferableRoutes().contains(route.getId())) {
                    Log.w(TAG, "Ignoring transferring to a non-transferrable route=" + route);
                    return;
                }
            }

            MediaRouter2Stub stub;
            synchronized (sRouterLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.transferToRouteWithRouter2(stub, getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to transfer to route for session.", ex);
                }
            }
        }

        /**
         * Requests a volume change for the remote session asynchronously.
         *
         * @param volume The new volume value between 0 and {@link RoutingController#getVolumeMax}
         *               (inclusive).
         * @see #getVolume()
         */
        public void setVolume(int volume) {
            if (getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) {
                Log.w(TAG, "setVolume: the routing session has fixed volume. Ignoring.");
                return;
            }
            if (volume < 0 || volume > getVolumeMax()) {
                Log.w(TAG, "setVolume: the target volume is out of range. Ignoring");
                return;
            }

            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "setVolume is called on released controller. Ignoring.");
                    return;
                }
            }
            MediaRouter2Stub stub;
            synchronized (sRouterLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.setSessionVolumeWithRouter2(stub, getId(), volume);
                } catch (RemoteException ex) {
                    Log.e(TAG, "setVolume: Failed to deliver request.", ex);
                }
            }
        }

        /**
         * Release this controller and corresponding session.
         * Any operations on this controller after calling this method will be ignored.
         * The devices that are playing media will stop playing it.
         */
        // TODO: Add tests using {@link MediaRouter2Manager#getActiveSessions()}.
        public void release() {
            synchronized (mControllerLock) {
                if (mIsReleased) {
                    Log.w(TAG, "release() called on released controller. Ignoring.");
                    return;
                }
                mIsReleased = true;
            }

            MediaRouter2Stub stub;
            boolean removed;
            synchronized (sRouterLock) {
                removed = mRoutingControllers.remove(getId(), this);
                stub = mStub;
            }

            if (removed) {
                mHandler.post(() -> notifyStopped(RoutingController.this));
            }

            if (stub != null) {
                try {
                    mMediaRouterService.releaseSessionWithRouter2(stub, getId());
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to notify of controller release", ex);
                }
            }
        }

        @Override
        public String toString() {
            // To prevent logging spam, we only print the ID of each route.
            List<String> selectedRoutes = getSelectedRoutes().stream()
                    .map(MediaRoute2Info::getId).collect(Collectors.toList());
            List<String> selectableRoutes = getSelectableRoutes().stream()
                    .map(MediaRoute2Info::getId).collect(Collectors.toList());
            List<String> deselectableRoutes = getDeselectableRoutes().stream()
                    .map(MediaRoute2Info::getId).collect(Collectors.toList());

            StringBuilder result = new StringBuilder()
                    .append("RoutingController{ ")
                    .append("id=").append(getId())
                    .append(", selectedRoutes={")
                    .append(selectedRoutes)
                    .append("}")
                    .append(", selectableRoutes={")
                    .append(selectableRoutes)
                    .append("}")
                    .append(", deselectableRoutes={")
                    .append(deselectableRoutes)
                    .append("}")
                    .append(" }");
            return result.toString();
        }

        @NonNull
        RoutingSessionInfo getRoutingSessionInfo() {
            synchronized (mControllerLock) {
                return mSessionInfo;
            }
        }

        void setRoutingSessionInfo(@NonNull RoutingSessionInfo info) {
            synchronized (mControllerLock) {
                mSessionInfo = info;
            }
        }

        private List<MediaRoute2Info> getRoutesWithIds(List<String> routeIds) {
            synchronized (sRouterLock) {
                return routeIds.stream().map(mRoutes::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        }
    }

    class SystemRoutingController extends RoutingController {
        SystemRoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            super(sessionInfo);
        }

        @Override
        public void release() {
            // Do nothing. SystemRoutingController will never be released
        }

        @Override
        public boolean isReleased() {
            // SystemRoutingController will never be released
            return false;
        }
    }

    static final class RouteCallbackRecord {
        public final Executor mExecutor;
        public final RouteCallback mRouteCallback;
        public final RouteDiscoveryPreference mPreference;

        RouteCallbackRecord(@Nullable Executor executor, @NonNull RouteCallback routeCallback,
                @Nullable RouteDiscoveryPreference preference) {
            mRouteCallback = routeCallback;
            mExecutor = executor;
            mPreference = preference;
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

    static final class TransferCallbackRecord {
        public final Executor mExecutor;
        public final TransferCallback mTransferCallback;

        TransferCallbackRecord(@NonNull Executor executor,
                @NonNull TransferCallback transferCallback) {
            mTransferCallback = transferCallback;
            mExecutor = executor;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TransferCallbackRecord)) {
                return false;
            }
            return mTransferCallback
                    == ((TransferCallbackRecord) obj).mTransferCallback;
        }

        @Override
        public int hashCode() {
            return mTransferCallback.hashCode();
        }
    }

    static final class ControllerCallbackRecord {
        public final Executor mExecutor;
        public final ControllerCallback mCallback;

        ControllerCallbackRecord(@Nullable Executor executor,
                @NonNull ControllerCallback callback) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ControllerCallbackRecord)) {
                return false;
            }
            return mCallback == ((ControllerCallbackRecord) obj).mCallback;
        }

        @Override
        public int hashCode() {
            return mCallback.hashCode();
        }
    }

    static final class ControllerCreationRequest {
        public final int mRequestId;
        public final RoutingController mController;
        public final MediaRoute2Info mRoute;

        ControllerCreationRequest(int requestId, @NonNull RoutingController controller,
                @NonNull MediaRoute2Info route) {
            mRequestId = requestId;
            mController = controller;
            mRoute = route;
        }
    }

    class MediaRouter2Stub extends IMediaRouter2.Stub {
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
        public void notifySessionCreated(int requestId, @Nullable RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::createControllerOnHandler,
                    MediaRouter2.this, requestId, sessionInfo));
        }

        @Override
        public void notifySessionInfoChanged(@Nullable RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::updateControllerOnHandler,
                    MediaRouter2.this, sessionInfo));
        }

        @Override
        public void notifySessionReleased(RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::releaseControllerOnHandler,
                    MediaRouter2.this, sessionInfo));
        }
    }
}
