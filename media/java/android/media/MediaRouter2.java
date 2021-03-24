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
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.content.pm.PackageManager;
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
import java.util.Collections;
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
// TODO(b/157873330): Add method names at the beginning of log messages. (e.g. selectRoute)
//       Not only MediaRouter2, but also to service / manager / provider.
// TODO: ensure thread-safe and document it
public final class MediaRouter2 {
    private static final String TAG = "MR2";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Object sSystemRouterLock = new Object();
    private static final Object sRouterLock = new Object();

    // The maximum time for the old routing controller available after transfer.
    private static final int TRANSFER_TIMEOUT_MS = 30_000;
    // The manager request ID representing that no manager is involved.
    private static final long MANAGER_REQUEST_ID_NONE = MediaRoute2ProviderService.REQUEST_ID_NONE;

    @GuardedBy("sSystemRouterLock")
    private static Map<String, MediaRouter2> sSystemMediaRouter2Map = new ArrayMap<>();
    private static MediaRouter2Manager sManager;

    @GuardedBy("sRouterLock")
    private static MediaRouter2 sInstance;

    private final Context mContext;
    private final IMediaRouterService mMediaRouterService;
    private final Object mLock = new Object();

    private final CopyOnWriteArrayList<RouteCallbackRecord> mRouteCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TransferCallbackRecord> mTransferCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ControllerCallbackRecord> mControllerCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ControllerCreationRequest> mControllerCreationRequests =
            new CopyOnWriteArrayList<>();

    // TODO: Specify the fields that are only used (or not used) by system media router.
    private final String mClientPackageName;
    private final ManagerCallback mManagerCallback;

    private final String mPackageName;

    @GuardedBy("mLock")
    final Map<String, MediaRoute2Info> mRoutes = new ArrayMap<>();

    final RoutingController mSystemController;

    @GuardedBy("mLock")
    private RouteDiscoveryPreference mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;

    // TODO: Make MediaRouter2 is always connected to the MediaRouterService.
    @GuardedBy("mLock")
    MediaRouter2Stub mStub;

    @GuardedBy("mLock")
    private final Map<String, RoutingController> mNonSystemRoutingControllers = new ArrayMap<>();

    private final AtomicInteger mNextRequestId = new AtomicInteger(1);

    final Handler mHandler;
    @GuardedBy("mLock")
    private boolean mShouldUpdateRoutes = true;
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

    /**
     * Gets an instance of the system media router which controls the app's media routing.
     * Returns {@code null} if the given package name is invalid.
     * There are several things to note when using the media routers created with this method.
     * <p>
     * First of all, the discovery preference passed to {@link #registerRouteCallback}
     * will have no effect. The callback will be called accordingly with the client app's
     * discovery preference. Therefore, it is recommended to pass
     * {@link RouteDiscoveryPreference#EMPTY} there.
     * <p>
     * Also, do not keep/compare the instances of the {@link RoutingController}, since they are
     * always newly created with the latest session information whenever below methods are called:
     * <ul>
     * <li> {@link #getControllers()} </li>
     * <li> {@link #getController(String)}} </li>
     * <li> {@link TransferCallback#onTransfer(RoutingController, RoutingController)} </li>
     * <li> {@link TransferCallback#onStop(RoutingController)} </li>
     * <li> {@link ControllerCallback#onControllerUpdated(RoutingController)} </li>
     * </ul>
     * Therefore, in order to track the current routing status, keep the controller's ID instead,
     * and use {@link #getController(String)} and {@link #getSystemController()} for
     * getting controllers.
     * <p>
     * Finally, it will have no effect to call {@link #setOnGetControllerHintsListener}.
     *
     * @param clientPackageName the package name of the app to control
     * @hide
     */
    @SystemApi
    @Nullable
    public static MediaRouter2 getInstance(@NonNull Context context,
            @NonNull String clientPackageName) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(clientPackageName, "clientPackageName must not be null");

        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(clientPackageName, 0);
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, "Package " + clientPackageName + " not found. Ignoring.");
            return null;
        }

        synchronized (sSystemRouterLock) {
            MediaRouter2 instance = sSystemMediaRouter2Map.get(clientPackageName);
            if (instance == null) {
                // TODO: Add permission check here using MODIFY_AUDIO_ROUTING.
                if (sManager == null) {
                    sManager = MediaRouter2Manager.getInstance(context.getApplicationContext());
                }
                instance = new MediaRouter2(context, clientPackageName);
                sSystemMediaRouter2Map.put(clientPackageName, instance);
                // TODO: Remove router instance once it is not needed.
                instance.registerManagerCallbackForSystemRouter();
            }
            return instance;
        }
    }

    /**
     * Starts scanning remote routes.
     * Note that calling start/stopScan is applied to all system routers in the same process.
     *
     * @see #stopScan()
     * @hide
     */
    @SystemApi
    public void startScan() {
        if (isSystemRouter()) {
            sManager.startScan();
        }
    }

    /**
     * Stops scanning remote routes to reduce resource consumption.
     * Note that calling start/stopScan is applied to all system routers in the same process.
     *
     * @see #startScan()
     * @hide
     */
    @SystemApi
    public void stopScan() {
        if (isSystemRouter()) {
            sManager.stopScan();
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

        // Only used by system MediaRouter2.
        mClientPackageName = null;
        mManagerCallback = null;
    }

    private MediaRouter2(Context context, String clientPackageName) {
        mContext = context;
        mClientPackageName = clientPackageName;
        mManagerCallback = new ManagerCallback();
        mHandler = new Handler(Looper.getMainLooper());
        mSystemController = new SystemRoutingController(
                ensureClientPackageNameForSystemSession(sManager.getSystemRoutingSession()));
        mDiscoveryPreference = new RouteDiscoveryPreference.Builder(
                sManager.getPreferredFeatures(clientPackageName), true).build();
        updateAllRoutesFromManager();
        mMediaRouterService = null; // TODO: Make this non-null and check permission.

        // Only used by non-system MediaRouter2.
        mPackageName = null;
    }

    /**
     * Returns whether any route in {@code routeList} has a same unique ID with given route.
     *
     * @hide
     */
    static boolean checkRouteListContainsRouteId(@NonNull List<MediaRoute2Info> routeList,
            @NonNull String routeId) {
        for (MediaRoute2Info info : routeList) {
            if (TextUtils.equals(routeId, info.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the client package name of the app which this media router controls.
     * This is only non-null when the router instance is created with the client package name.
     *
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @Nullable
    public String getClientPackageName() {
        return mClientPackageName;
    }

    /**
     * Registers a callback to receive route related events when they change.
     * <p>
     * If the specified callback is already registered, its registration will be updated for the
     * given {@link Executor executor}.
     * <p>
     * This will be no-op for non-system routers.
     * @hide
     */
    @SystemApi
    public void registerRouteCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback) {
        if (!isSystemRouter()) {
            return;
        }
        registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.EMPTY);
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
        if (isSystemRouter()) {
            preference = RouteDiscoveryPreference.EMPTY;
        }

        RouteCallbackRecord record = new RouteCallbackRecord(executor, routeCallback, preference);

        mRouteCallbackRecords.remove(record);
        // It can fail to add the callback record if another registration with the same callback
        // is happening but it's okay because either this or the other registration should be done.
        mRouteCallbackRecords.addIfAbsent(record);

        if (isSystemRouter()) {
            return;
        }

        synchronized (mLock) {
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
                    Log.e(TAG, "registerRouteCallback: Unable to set discovery request.", ex);
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
            Log.w(TAG, "unregisterRouteCallback: Ignoring unknown callback");
            return;
        }

        if (isSystemRouter()) {
            return;
        }

        synchronized (mLock) {
            if (mStub == null) {
                return;
            }
            if (updateDiscoveryPreferenceIfNeededLocked()) {
                try {
                    mMediaRouterService.setDiscoveryRequestWithRouter2(
                            mStub, mDiscoveryPreference);
                } catch (RemoteException ex) {
                    Log.e(TAG, "unregisterRouteCallback: Unable to set discovery request.", ex);
                }
            }
            if (mRouteCallbackRecords.isEmpty() && mNonSystemRoutingControllers.isEmpty()) {
                try {
                    mMediaRouterService.unregisterRouter2(mStub);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to unregister media router.", ex);
                }
                mStub = null;
            }
            mShouldUpdateRoutes = true;
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
     * Gets the list of all discovered routes.
     * This list includes the routes that are not related to the client app.
     * <p>
     * This will return an empty list for non-system media routers.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public List<MediaRoute2Info> getAllRoutes() {
        if (isSystemRouter()) {
            return sManager.getAllRoutes();
        }
        return Collections.emptyList();
    }

    /**
     * Gets the unmodifiable list of {@link MediaRoute2Info routes} currently
     * known to the media router.
     * <p>
     * Please note that the list can be changed before callbacks are invoked.
     * </p>
     * @return the list of routes that contains at least one of the route features in discovery
     * preferences registered by the application
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        synchronized (mLock) {
            if (mShouldUpdateRoutes) {
                mShouldUpdateRoutes = false;

                List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
                for (MediaRoute2Info route : mRoutes.values()) {
                    if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
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
        if (isSystemRouter()) {
            return;
        }
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
     * @see TransferCallback#onTransfer
     * @see TransferCallback#onTransferFailure
     */
    public void transferTo(@NonNull MediaRoute2Info route) {
        if (isSystemRouter()) {
            sManager.selectRoute(mClientPackageName, route);
            return;
        }

        Objects.requireNonNull(route, "route must not be null");
        Log.v(TAG, "Transferring to route: " + route);
        transfer(getCurrentController(), route);
    }

    /**
     * Stops the current media routing. If the {@link #getSystemController() system controller}
     * controls the media routing, this method is a no-op.
     */
    public void stop() {
        if (isSystemRouter()) {
            List<RoutingSessionInfo> sessionInfos = sManager.getRoutingSessions(mClientPackageName);
            RoutingSessionInfo sessionToRelease = sessionInfos.get(sessionInfos.size() - 1);
            sManager.releaseSession(sessionToRelease);
            return;
        }
        getCurrentController().release();
    }

    /**
     * Transfers the media of a routing controller to the given route.
     * @param controller a routing controller controlling media routing.
     * @param route the route you want to transfer the media to.
     * @hide
     */
    @SystemApi
    public void transfer(@NonNull RoutingController controller, @NonNull MediaRoute2Info route) {
        if (isSystemRouter()) {
            sManager.transfer(controller.getRoutingSessionInfo(), route);
            return;
        }

        Objects.requireNonNull(controller, "controller must not be null");
        Objects.requireNonNull(route, "route must not be null");

        boolean routeFound;
        synchronized (mLock) {
            // TODO: Check thread-safety
            routeFound = mRoutes.containsKey(route.getId());
        }
        if (!routeFound) {
            notifyTransferFailure(route);
            return;
        }

        if (controller.getRoutingSessionInfo().getTransferableRoutes().contains(route.getId())) {
            controller.transferToRoute(route);
            return;
        }

        requestCreateController(controller, route, MANAGER_REQUEST_ID_NONE);
    }

    void requestCreateController(@NonNull RoutingController controller,
            @NonNull MediaRoute2Info route, long managerRequestId) {

        final int requestId = mNextRequestId.getAndIncrement();

        ControllerCreationRequest request = new ControllerCreationRequest(requestId,
                managerRequestId, route, controller);
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
        synchronized (mLock) {
            stub = mStub;
        }
        if (stub != null) {
            try {
                mMediaRouterService.requestCreateSessionWithRouter2(
                        stub, requestId, managerRequestId,
                        controller.getRoutingSessionInfo(), route, controllerHints);
            } catch (RemoteException ex) {
                Log.e(TAG, "createControllerForTransfer: "
                        + "Failed to request for creating a controller.", ex);
                mControllerCreationRequests.remove(request);
                if (managerRequestId == MANAGER_REQUEST_ID_NONE) {
                    notifyTransferFailure(route);
                }
            }
        }
    }

    @NonNull
    private RoutingController getCurrentController() {
        List<RoutingController> controllers = getControllers();
        return controllers.get(controllers.size() - 1);
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
     * Gets a {@link RoutingController} whose ID is equal to the given ID.
     * Returns {@code null} if there is no matching controller.
     * @hide
     */
    @SystemApi
    @Nullable
    public RoutingController getController(@NonNull String id) {
        Objects.requireNonNull(id, "id must not be null");
        for (RoutingController controller : getControllers()) {
            if (TextUtils.equals(id, controller.getId())) {
                return controller;
            }
        }
        return null;
    }

    /**
     * Gets the list of currently active {@link RoutingController routing controllers} on which
     * media can be played.
     * <p>
     * Note: The list returned here will never be empty. The first element in the list is
     * always the {@link #getSystemController() system controller}.
     */
    @NonNull
    public List<RoutingController> getControllers() {
        List<RoutingController> result = new ArrayList<>();

        if (isSystemRouter()) {
            // Unlike non-system MediaRouter2, controller instances cannot be kept,
            // since the transfer events initiated from other apps will not come through manager.
            List<RoutingSessionInfo> sessions = sManager.getRoutingSessions(mClientPackageName);
            for (RoutingSessionInfo session : sessions) {
                RoutingController controller;
                if (session.isSystemSession()) {
                    mSystemController.setRoutingSessionInfo(
                            ensureClientPackageNameForSystemSession(session));
                    controller = mSystemController;
                } else {
                    controller = new RoutingController(session);
                }
                result.add(controller);
            }
            return result;
        }

        result.add(0, mSystemController);
        synchronized (mLock) {
            result.addAll(mNonSystemRoutingControllers.values());
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
    @SystemApi
    public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        if (isSystemRouter()) {
            sManager.setRouteVolume(route, volume);
            return;
        }

        MediaRouter2Stub stub;
        synchronized (mLock) {
            stub = mStub;
        }
        if (stub != null) {
            try {
                mMediaRouterService.setRouteVolumeWithRouter2(stub, route, volume);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to set route volume.", ex);
            }
        }
    }

    void syncRoutesOnHandler(List<MediaRoute2Info> currentRoutes,
            RoutingSessionInfo currentSystemSessionInfo) {
        if (currentRoutes == null || currentRoutes.isEmpty() || currentSystemSessionInfo == null) {
            Log.e(TAG, "syncRoutesOnHandler: Received wrong data. currentRoutes=" + currentRoutes
                    + ", currentSystemSessionInfo=" + currentSystemSessionInfo);
            return;
        }

        List<MediaRoute2Info> addedRoutes = new ArrayList<>();
        List<MediaRoute2Info> removedRoutes = new ArrayList<>();
        List<MediaRoute2Info> changedRoutes = new ArrayList<>();

        synchronized (mLock) {
            List<String> currentRoutesIds = currentRoutes.stream().map(MediaRoute2Info::getId)
                    .collect(Collectors.toList());

            for (String routeId : mRoutes.keySet()) {
                if (!currentRoutesIds.contains(routeId)) {
                    // This route is removed while the callback is unregistered.
                    MediaRoute2Info route = mRoutes.get(routeId);
                    if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                        removedRoutes.add(mRoutes.get(routeId));
                    }
                }
            }

            for (MediaRoute2Info route : currentRoutes) {
                if (mRoutes.containsKey(route.getId())) {
                    if (!route.equals(mRoutes.get(route.getId()))) {
                        // This route is changed while the callback is unregistered.
                        if (route.hasAnyFeatures(
                                        mDiscoveryPreference.getPreferredFeatures())) {
                            changedRoutes.add(route);
                        }
                    }
                } else {
                    // This route is added while the callback is unregistered.
                    if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                        addedRoutes.add(route);
                    }
                }
            }

            mRoutes.clear();
            for (MediaRoute2Info route : currentRoutes) {
                mRoutes.put(route.getId(), route);
            }

            mShouldUpdateRoutes = true;
        }

        if (!addedRoutes.isEmpty()) {
            notifyRoutesAdded(addedRoutes);
        }
        if (!removedRoutes.isEmpty()) {
            notifyRoutesRemoved(removedRoutes);
        }
        if (!changedRoutes.isEmpty()) {
            notifyRoutesChanged(changedRoutes);
        }

        RoutingSessionInfo oldInfo = mSystemController.getRoutingSessionInfo();
        mSystemController.setRoutingSessionInfo(currentSystemSessionInfo);
        if (!oldInfo.equals(currentSystemSessionInfo)) {
            notifyControllerUpdated(mSystemController);
        }
    }

    void addRoutesOnHandler(List<MediaRoute2Info> routes) {
        List<MediaRoute2Info> addedRoutes = new ArrayList<>();
        synchronized (mLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
                if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                    addedRoutes.add(route);
                }
            }
            mShouldUpdateRoutes = true;
        }
        if (!addedRoutes.isEmpty()) {
            notifyRoutesAdded(addedRoutes);
        }
    }

    void removeRoutesOnHandler(List<MediaRoute2Info> routes) {
        List<MediaRoute2Info> removedRoutes = new ArrayList<>();
        synchronized (mLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.remove(route.getId());
                if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                    removedRoutes.add(route);
                }
            }
            mShouldUpdateRoutes = true;
        }
        if (!removedRoutes.isEmpty()) {
            notifyRoutesRemoved(removedRoutes);
        }
    }

    void changeRoutesOnHandler(List<MediaRoute2Info> routes) {
        List<MediaRoute2Info> changedRoutes = new ArrayList<>();
        synchronized (mLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getId(), route);
                if (route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                    changedRoutes.add(route);
                }
            }
            mShouldUpdateRoutes = true;
        }
        if (!changedRoutes.isEmpty()) {
            notifyRoutesChanged(changedRoutes);
        }
    }

    /**
     * Creates a controller and calls the {@link TransferCallback#onTransfer}.
     * If the controller creation has failed, then it calls
     * {@link TransferCallback#onTransferFailure}.
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

        if (matchingRequest == null) {
            Log.w(TAG, "createControllerOnHandler: Ignoring an unknown request.");
            return;
        }

        mControllerCreationRequests.remove(matchingRequest);
        MediaRoute2Info requestedRoute = matchingRequest.mRoute;

        // TODO: Notify the reason for failure.
        if (sessionInfo == null) {
            notifyTransferFailure(requestedRoute);
            return;
        } else if (!TextUtils.equals(requestedRoute.getProviderId(),
                sessionInfo.getProviderId())) {
            Log.w(TAG, "The session's provider ID does not match the requested route's. "
                    + "(requested route's providerId=" + requestedRoute.getProviderId()
                    + ", actual providerId=" + sessionInfo.getProviderId()
                    + ")");
            notifyTransferFailure(requestedRoute);
            return;
        }

        RoutingController oldController = matchingRequest.mOldController;
        // When the old controller is released before transferred, treat it as a failure.
        // This could also happen when transfer is requested twice or more.
        if (!oldController.scheduleRelease()) {
            Log.w(TAG, "createControllerOnHandler: "
                    + "Ignoring controller creation for released old controller. "
                    + "oldController=" + oldController);
            if (!sessionInfo.isSystemSession()) {
                new RoutingController(sessionInfo).release();
            }
            notifyTransferFailure(requestedRoute);
            return;
        }

        RoutingController newController;
        if (sessionInfo.isSystemSession()) {
            newController = getSystemController();
            newController.setRoutingSessionInfo(sessionInfo);
        } else {
            newController = new RoutingController(sessionInfo);
            synchronized (mLock) {
                mNonSystemRoutingControllers.put(newController.getId(), newController);
            }
        }

        notifyTransfer(oldController, newController);
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
        synchronized (mLock) {
            matchingController = mNonSystemRoutingControllers.get(sessionInfo.getId());
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

        RoutingController matchingController;
        synchronized (mLock) {
            matchingController = mNonSystemRoutingControllers.get(sessionInfo.getId());
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

        matchingController.releaseInternal(/* shouldReleaseSession= */ false);
    }

    void onRequestCreateControllerByManagerOnHandler(RoutingSessionInfo oldSession,
            MediaRoute2Info route, long managerRequestId) {
        RoutingController controller;
        if (oldSession.isSystemSession()) {
            controller = getSystemController();
        } else {
            synchronized (mLock) {
                controller = mNonSystemRoutingControllers.get(oldSession.getId());
            }
        }
        if (controller == null) {
            return;
        }
        requestCreateController(controller, route, managerRequestId);
    }

    /**
     * Returns whether this router is created with {@link #getInstance(Context, String)}.
     * This kind of router can control the target app's media routing.
     */
    private boolean isSystemRouter() {
        return mClientPackageName != null;
    }

    /**
     * Registers {@link MediaRouter2Manager.Callback} for getting events.
     * Should only used for system media routers.
     */
    private void registerManagerCallbackForSystemRouter() {
        // Using direct executor here, since MediaRouter2Manager also posts to the main handler.
        sManager.registerCallback(Runnable::run, mManagerCallback);
    }

    /**
     * Returns a {@link RoutingSessionInfo} which has the client package name.
     * The client package name is set only when the given sessionInfo doesn't have it.
     * Should only used for system media routers.
     */
    private RoutingSessionInfo ensureClientPackageNameForSystemSession(
            @NonNull RoutingSessionInfo sessionInfo) {
        if (!sessionInfo.isSystemSession()
                || !TextUtils.isEmpty(sessionInfo.getClientPackageName())) {
            return sessionInfo;
        }

        return new RoutingSessionInfo.Builder(sessionInfo)
                .setClientPackageName(mClientPackageName)
                .build();
    }

    private List<MediaRoute2Info> filterRoutes(List<MediaRoute2Info> routes,
            RouteDiscoveryPreference discoveryRequest) {
        return routes.stream()
                .filter(route -> route.hasAnyFeatures(discoveryRequest.getPreferredFeatures()))
                .collect(Collectors.toList());
    }

    private void updateAllRoutesFromManager() {
        synchronized (mLock) {
            mRoutes.clear();
            for (MediaRoute2Info route : sManager.getAllRoutes()) {
                mRoutes.put(route.getId(), route);
            }
            mShouldUpdateRoutes = true;
        }
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

    private void notifyPreferredFeaturesChanged(List<String> features) {
        for (RouteCallbackRecord record: mRouteCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteCallback.onPreferredFeaturesChanged(features));
        }
    }

    private void notifyTransfer(RoutingController oldController, RoutingController newController) {
        for (TransferCallbackRecord record: mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onTransfer(oldController, newController));
        }
    }

    private void notifyTransferFailure(MediaRoute2Info route) {
        for (TransferCallbackRecord record: mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onTransferFailure(route));
        }
    }

    private void notifyStop(RoutingController controller) {
        for (TransferCallbackRecord record: mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onStop(controller));
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

        /**
         * Called when the client app's preferred features are changed.
         * When this is called, it is recommended to {@link #getRoutes()} to get the routes
         * that are currently available to the app.
         *
         * @param preferredFeatures the new preferred features set by the application
         * @hide
         */
        @SystemApi
        public void onPreferredFeaturesChanged(@NonNull List<String> preferredFeatures) {}
    }

    /**
     * Callback for receiving events on media transfer.
     */
    public abstract static class TransferCallback {
        /**
         * Called when a media is transferred between two different routing controllers.
         * This can happen by calling {@link #transferTo(MediaRoute2Info)}.
         * <p> Override this to start playback with {@code newController}. You may want to get
         * the status of the media that is being played with {@code oldController} and resume it
         * continuously with {@code newController}.
         * After this is called, any callbacks with {@code oldController} will not be invoked
         * unless {@code oldController} is the {@link #getSystemController() system controller}.
         * You need to {@link RoutingController#release() release} {@code oldController} before
         * playing the media with {@code newController}.
         *
         * @param oldController the previous controller that controlled routing
         * @param newController the new controller to control routing
         * @see #transferTo(MediaRoute2Info)
         */
        public void onTransfer(@NonNull RoutingController oldController,
                @NonNull RoutingController newController) {}

        /**
         * Called when {@link #transferTo(MediaRoute2Info)} failed.
         *
         * @param requestedRoute the route info which was used for the transfer
         */
        public void onTransferFailure(@NonNull MediaRoute2Info requestedRoute) {}

        /**
         * Called when a media routing stops. It can be stopped by a user or a provider.
         * App should not continue playing media locally when this method is called.
         * The {@code controller} is released before this method is called.
         *
         * @param controller the controller that controlled the stopped media routing
         */
        public void onStop(@NonNull RoutingController controller) { }
    }

    /**
     * A listener interface to send optional app-specific hints when creating a
     * {@link RoutingController}.
     */
    public interface OnGetControllerHintsListener {
        /**
         * Called when the {@link MediaRouter2} or the system is about to request
         * a media route provider service to create a controller with the given route.
         * The {@link Bundle} returned here will be sent to media route provider service as a hint.
         * <p>
         * Since controller creation can be requested by the {@link MediaRouter2} and the system,
         * set the listener as soon as possible after acquiring {@link MediaRouter2} instance.
         * The method will be called on the same thread that calls
         * {@link #transferTo(MediaRoute2Info)} or the main thread if it is requested by the system.
         *
         * @param route the route to create a controller with
         * @return An optional bundle of app-specific arguments to send to the provider,
         *         or {@code null} if none. The contents of this bundle may affect the result of
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
         * Called when a controller is updated. (e.g., when the selected routes of the
         * controller is changed or when the volume of the controller is changed.)
         *
         * @param controller the updated controller. It may be the
         * {@link #getSystemController() system controller}.
         * @see #getSystemController()
         */
        public void onControllerUpdated(@NonNull RoutingController controller) { }
    }

    /**
     * A class to control media routing session in media route provider.
     * For example, selecting/deselecting/transferring to routes of a session can be done through
     * this. Instances are created when
     * {@link TransferCallback#onTransfer(RoutingController, RoutingController)} is called,
     * which is invoked after {@link #transferTo(MediaRoute2Info)} is called.
     */
    public class RoutingController {
        private final Object mControllerLock = new Object();

        private static final int CONTROLLER_STATE_UNKNOWN = 0;
        private static final int CONTROLLER_STATE_ACTIVE = 1;
        private static final int CONTROLLER_STATE_RELEASING = 2;
        private static final int CONTROLLER_STATE_RELEASED = 3;

        @GuardedBy("mControllerLock")
        private RoutingSessionInfo mSessionInfo;

        @GuardedBy("mControllerLock")
        private int mState;

        RoutingController(@NonNull RoutingSessionInfo sessionInfo) {
            mSessionInfo = sessionInfo;
            mState = CONTROLLER_STATE_ACTIVE;
        }

        RoutingController(@NonNull RoutingSessionInfo sessionInfo, int state) {
            mSessionInfo = sessionInfo;
            mState = state;
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
         * Gets the original session ID set by
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
         * Gets the control hints used to control routing session if available.
         * It is set by the media route provider.
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
         * Gets the information about how volume is handled on the session.
         * <p>Please note that you may not control the volume of the session even when
         * you can control the volume of each selected route in the session.
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
         * of selected routes. Use {@link MediaRoute2Info#getVolume()}
         * to get the volume of a route,
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
                return mState == CONTROLLER_STATE_RELEASED;
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
         * <li>It should not be included in {@link #getSelectedRoutes()}</li>
         * <li>It should be included in {@link #getSelectableRoutes()}</li>
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
            if (isReleased()) {
                Log.w(TAG, "selectRoute: Called on released controller. Ignoring.");
                return;
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

            if (isSystemRouter()) {
                sManager.selectRoute(getRoutingSessionInfo(), route);
                return;
            }

            MediaRouter2Stub stub;
            synchronized (mLock) {
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
         * expected to be stopped on the deselected route.
         * <p>
         * The given route must satisfy all of the following conditions:
         * <ul>
         * <li>It should be included in {@link #getSelectedRoutes()}</li>
         * <li>It should be included in {@link #getDeselectableRoutes()}</li>
         * </ul>
         * If the route doesn't meet any of above conditions, it will be ignored.
         *
         * @see #getSelectedRoutes()
         * @see #getDeselectableRoutes()
         * @see ControllerCallback#onControllerUpdated
         */
        public void deselectRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            if (isReleased()) {
                Log.w(TAG, "deselectRoute: called on released controller. Ignoring.");
                return;
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

            if (isSystemRouter()) {
                sManager.deselectRoute(getRoutingSessionInfo(), route);
                return;
            }

            MediaRouter2Stub stub;
            synchronized (mLock) {
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
         * Transfers to a given route for the remote session. The given route must be included
         * in {@link RoutingSessionInfo#getTransferableRoutes()}.
         *
         * @see RoutingSessionInfo#getSelectedRoutes()
         * @see RoutingSessionInfo#getTransferableRoutes()
         * @see ControllerCallback#onControllerUpdated
         */
        void transferToRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");
            synchronized (mControllerLock) {
                if (isReleased()) {
                    Log.w(TAG, "transferToRoute: Called on released controller. Ignoring.");
                    return;
                }

                if (!mSessionInfo.getTransferableRoutes().contains(route.getId())) {
                    Log.w(TAG, "Ignoring transferring to a non-transferable route=" + route);
                    return;
                }
            }

            MediaRouter2Stub stub;
            synchronized (mLock) {
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
                Log.w(TAG, "setVolume: The routing session has fixed volume. Ignoring.");
                return;
            }
            if (volume < 0 || volume > getVolumeMax()) {
                Log.w(TAG, "setVolume: The target volume is out of range. Ignoring");
                return;
            }

            if (isReleased()) {
                Log.w(TAG, "setVolume: Called on released controller. Ignoring.");
                return;
            }

            if (isSystemRouter()) {
                sManager.setSessionVolume(getRoutingSessionInfo(), volume);
                return;
            }

            MediaRouter2Stub stub;
            synchronized (mLock) {
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
         * Releases this controller and the corresponding session.
         * Any operations on this controller after calling this method will be ignored.
         * The devices that are playing media will stop playing it.
         */
        public void release() {
            releaseInternal(/* shouldReleaseSession= */ true);
        }

        /**
         * Schedules release of the controller.
         * @return {@code true} if it's successfully scheduled, {@code false} if it's already
         * scheduled to be released or released.
         */
        boolean scheduleRelease() {
            synchronized (mControllerLock) {
                if (mState != CONTROLLER_STATE_ACTIVE) {
                    return false;
                }
                mState = CONTROLLER_STATE_RELEASING;
            }

            synchronized (mLock) {
                // It could happen if the controller is released by the another thread
                // in between two locks
                if (!mNonSystemRoutingControllers.remove(getId(), this)) {
                    // In that case, onStop isn't called so we return true to call onTransfer.
                    // It's also consistent with that the another thread acquires the lock later.
                    return true;
                }
            }

            mHandler.postDelayed(this::release, TRANSFER_TIMEOUT_MS);

            return true;
        }

        void releaseInternal(boolean shouldReleaseSession) {
            boolean shouldNotifyStop;

            synchronized (mControllerLock) {
                if (mState == CONTROLLER_STATE_RELEASED) {
                    if (DEBUG) {
                        Log.d(TAG, "releaseInternal: Called on released controller. Ignoring.");
                    }
                    return;
                }
                shouldNotifyStop = (mState == CONTROLLER_STATE_ACTIVE);
                mState = CONTROLLER_STATE_RELEASED;
            }

            if (isSystemRouter()) {
                sManager.releaseSession(getRoutingSessionInfo());
                return;
            }

            synchronized (mLock) {
                mNonSystemRoutingControllers.remove(getId(), this);

                if (shouldReleaseSession && mStub != null) {
                    try {
                        mMediaRouterService.releaseSessionWithRouter2(mStub, getId());
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Unable to release session", ex);
                    }
                }

                if (shouldNotifyStop) {
                    mHandler.sendMessage(obtainMessage(MediaRouter2::notifyStop, MediaRouter2.this,
                            RoutingController.this));
                }

                if (mRouteCallbackRecords.isEmpty() && mNonSystemRoutingControllers.isEmpty()
                        && mStub != null) {
                    try {
                        mMediaRouterService.unregisterRouter2(mStub);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "releaseInternal: Unable to unregister media router.", ex);
                    }
                    mStub = null;
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
            if (isSystemRouter()) {
                return getRoutes().stream()
                        .filter(r -> routeIds.contains(r.getId()))
                        .collect(Collectors.toList());
            }

            synchronized (mLock) {
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
        public boolean isReleased() {
            // SystemRoutingController will never be released
            return false;
        }

        @Override
        boolean scheduleRelease() {
            // SystemRoutingController can be always transferred
            return true;
        }

        @Override
        void releaseInternal(boolean shouldReleaseSession) {
            // Do nothing. SystemRoutingController will never be released
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
            return mTransferCallback == ((TransferCallbackRecord) obj).mTransferCallback;
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
        public final long mManagerRequestId;
        public final MediaRoute2Info mRoute;
        public final RoutingController mOldController;

        ControllerCreationRequest(int requestId, long managerRequestId,
                @NonNull MediaRoute2Info route, @NonNull RoutingController oldController) {
            mRequestId = requestId;
            mManagerRequestId = managerRequestId;
            mRoute = Objects.requireNonNull(route, "route must not be null");
            mOldController = Objects.requireNonNull(oldController,
                    "oldController must not be null");
        }
    }

    class MediaRouter2Stub extends IMediaRouter2.Stub {
        @Override
        public void notifyRouterRegistered(List<MediaRoute2Info> currentRoutes,
                RoutingSessionInfo currentSystemSessionInfo) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::syncRoutesOnHandler,
                    MediaRouter2.this, currentRoutes, currentSystemSessionInfo));
        }

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

        @Override
        public void requestCreateSessionByManager(long managerRequestId,
                RoutingSessionInfo oldSession, MediaRoute2Info route) {
            mHandler.sendMessage(obtainMessage(
                    MediaRouter2::onRequestCreateControllerByManagerOnHandler,
                    MediaRouter2.this, oldSession, route, managerRequestId));
        }
    }

    // Note: All methods are run on main thread.
    class ManagerCallback implements MediaRouter2Manager.Callback {

        @Override
        public void onRoutesAdded(@NonNull List<MediaRoute2Info> routes) {
            updateAllRoutesFromManager();

            List<MediaRoute2Info> filteredRoutes;
            synchronized (mLock) {
                filteredRoutes = filterRoutes(routes, mDiscoveryPreference);
            }
            if (filteredRoutes.isEmpty()) {
                return;
            }
            for (RouteCallbackRecord record: mRouteCallbackRecords) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesAdded(filteredRoutes));
            }
        }

        @Override
        public void onRoutesRemoved(@NonNull List<MediaRoute2Info> routes) {
            updateAllRoutesFromManager();

            List<MediaRoute2Info> filteredRoutes;
            synchronized (mLock) {
                filteredRoutes = filterRoutes(routes, mDiscoveryPreference);
            }
            if (filteredRoutes.isEmpty()) {
                return;
            }
            for (RouteCallbackRecord record: mRouteCallbackRecords) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesRemoved(filteredRoutes));
            }
        }

        @Override
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {
            updateAllRoutesFromManager();

            List<MediaRoute2Info> filteredRoutes;
            synchronized (mLock) {
                filteredRoutes = filterRoutes(routes, mDiscoveryPreference);
            }
            if (filteredRoutes.isEmpty()) {
                return;
            }
            for (RouteCallbackRecord record: mRouteCallbackRecords) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesChanged(filteredRoutes));
            }
        }

        @Override
        public void onTransferred(@NonNull RoutingSessionInfo oldSession,
                @NonNull RoutingSessionInfo newSession) {
            if (!oldSession.isSystemSession()
                    && !TextUtils.equals(mClientPackageName, oldSession.getClientPackageName())) {
                return;
            }

            if (!newSession.isSystemSession()
                    && !TextUtils.equals(mClientPackageName, newSession.getClientPackageName())) {
                return;
            }

            // For successful in-session transfer, onControllerUpdated() handles it.
            if (TextUtils.equals(oldSession.getId(), newSession.getId())) {
                return;
            }


            RoutingController oldController;
            if (oldSession.isSystemSession()) {
                mSystemController.setRoutingSessionInfo(
                        ensureClientPackageNameForSystemSession(oldSession));
                oldController = mSystemController;
            } else {
                oldController = new RoutingController(oldSession);
            }

            RoutingController newController;
            if (newSession.isSystemSession()) {
                mSystemController.setRoutingSessionInfo(
                        ensureClientPackageNameForSystemSession(newSession));
                newController = mSystemController;
            } else {
                newController = new RoutingController(newSession);
            }

            notifyTransfer(oldController, newController);
        }

        @Override
        public void onTransferFailed(@NonNull RoutingSessionInfo session,
                @NonNull MediaRoute2Info route) {
            if (!session.isSystemSession()
                    && !TextUtils.equals(mClientPackageName, session.getClientPackageName())) {
                return;
            }
            notifyTransferFailure(route);
        }

        @Override
        public void onSessionUpdated(@NonNull RoutingSessionInfo session) {
            if (!session.isSystemSession()
                    && !TextUtils.equals(mClientPackageName, session.getClientPackageName())) {
                return;
            }

            RoutingController controller;
            if (session.isSystemSession()) {
                mSystemController.setRoutingSessionInfo(
                        ensureClientPackageNameForSystemSession(session));
                controller = mSystemController;
            } else {
                controller = new RoutingController(session);
            }
            notifyControllerUpdated(controller);
        }

        @Override
        public void onSessionReleased(@NonNull RoutingSessionInfo session) {
            if (session.isSystemSession()) {
                Log.e(TAG, "onSessionReleased: Called on system session. Ignoring.");
                return;
            }

            if (!TextUtils.equals(mClientPackageName, session.getClientPackageName())) {
                return;
            }

            notifyStop(new RoutingController(session, RoutingController.CONTROLLER_STATE_RELEASED));
        }

        @Override
        public void onPreferredFeaturesChanged(@NonNull String packageName,
                @NonNull List<String> preferredFeatures) {
            if (!TextUtils.equals(mClientPackageName, packageName)) {
                return;
            }

            synchronized (mLock) {
                mDiscoveryPreference = new RouteDiscoveryPreference.Builder(
                        preferredFeatures, true).build();
            }

            updateAllRoutesFromManager();
            notifyPreferredFeaturesChanged(preferredFeatures);
        }

        @Override
        public void onRequestFailed(int reason) {
            // Does nothing.
        }
    }
}
