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
import static com.android.media.flags.Flags.FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES;
import static com.android.media.flags.Flags.FLAG_ENABLE_CROSS_USER_ROUTING_IN_MEDIA_ROUTER2;
import static com.android.media.flags.Flags.FLAG_ENABLE_RLP_CALLBACKS_IN_MEDIA_ROUTER2;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * This API is not generally intended for third party application developers. Use the
 * <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/mediarouter/media/package-summary.html">Media Router
 * Library</a> for consistent behavior across all devices.
 *
 * <p>MediaRouter2 allows applications to control the routing of media channels and streams from
 * the current device to remote speakers and devices.
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

    private record PackageNameUserHandlePair(String packageName, UserHandle user) {}

    @GuardedBy("sSystemRouterLock")
    private static final Map<PackageNameUserHandlePair, MediaRouter2> sAppToProxyRouterMap =
            new ArrayMap<>();

    @GuardedBy("sRouterLock")
    private static MediaRouter2 sInstance;

    private final Context mContext;
    private final IMediaRouterService mMediaRouterService;
    private final Object mLock = new Object();
    private final MediaRouter2Impl mImpl;

    private final CopyOnWriteArrayList<RouteCallbackRecord> mRouteCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RouteListingPreferenceCallbackRecord>
            mListingPreferenceCallbackRecords = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TransferCallbackRecord> mTransferCallbackRecords =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ControllerCallbackRecord> mControllerCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ControllerCreationRequest> mControllerCreationRequests =
            new CopyOnWriteArrayList<>();

    /**
     * Stores the latest copy of all routes received from the system server, without any filtering,
     * sorting, or deduplication.
     *
     * <p>Uses {@link MediaRoute2Info#getId()} to set each entry's key.
     */
    @GuardedBy("mLock")
    private final Map<String, MediaRoute2Info> mRoutes = new ArrayMap<>();

    private final RoutingController mSystemController;

    @GuardedBy("mLock")
    private final Map<String, RoutingController> mNonSystemRoutingControllers = new ArrayMap<>();

    private final AtomicInteger mNextRequestId = new AtomicInteger(1);
    private final Handler mHandler;

    @GuardedBy("mLock")
    private RouteDiscoveryPreference mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;

    // TODO: Make MediaRouter2 is always connected to the MediaRouterService.
    @GuardedBy("mLock")
    private MediaRouter2Stub mStub;

    @GuardedBy("mLock")
    @Nullable
    private RouteListingPreference mRouteListingPreference;

    /**
     * Stores an auxiliary copy of {@link #mFilteredRoutes} at the time of the last route callback
     * dispatch. This is only used to determine what callback a route should be assigned to (added,
     * removed, changed) in {@link #dispatchFilteredRoutesUpdatedOnHandler(List)}.
     */
    private volatile ArrayMap<String, MediaRoute2Info> mPreviousRoutes = new ArrayMap<>();

    /**
     * Stores the latest copy of exposed routes after filtering, sorting, and deduplication. Can be
     * accessed through {@link #getRoutes()}.
     *
     * <p>This list is a copy of {@link #mRoutes} which has undergone filtering, sorting, and
     * deduplication using criteria in {@link #mDiscoveryPreference}.
     *
     * @see #filterRoutesWithCompositePreferenceLocked(List)
     */
    private volatile List<MediaRoute2Info> mFilteredRoutes = Collections.emptyList();
    private volatile OnGetControllerHintsListener mOnGetControllerHintsListener;

    /** Gets an instance of the media router associated with the context. */
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
     * Returns a proxy MediaRouter2 instance that allows you to control the routing of an app
     * specified by {@code clientPackageName}. Returns {@code null} if the specified package name
     * does not exist.
     *
     * <p>Proxy MediaRouter2 instances operate differently than regular MediaRouter2 instances:
     *
     * <ul>
     *   <li>
     *       <p>{@link #registerRouteCallback} ignores any {@link RouteDiscoveryPreference discovery
     *       preference} passed by a proxy router. Use {@link RouteDiscoveryPreference#EMPTY} when
     *       setting a route callback.
     *   <li>
     *       <p>Methods returning non-system {@link RoutingController controllers} always return
     *       new instances with the latest data. Do not attempt to compare or store them. Instead,
     *       use {@link #getController(String)} or {@link #getControllers()} to query the most
     *       up-to-date state.
     *   <li>
     *       <p>Calls to {@link #setOnGetControllerHintsListener} are ignored.
     * </ul>
     *
     * @param clientPackageName the package name of the app to control
     * @throws SecurityException if the caller doesn't have {@link
     *     Manifest.permission#MEDIA_CONTENT_CONTROL MEDIA_CONTENT_CONTROL} permission.
     * @hide
     */
    // TODO (b/311711420): Deprecate once #getInstance(Context, Looper, String, UserHandle)
    //  reaches public SDK.
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    @Nullable
    public static MediaRouter2 getInstance(
            @NonNull Context context, @NonNull String clientPackageName) {
        // Capturing the IAE here to not break nullability.
        try {
            return findOrCreateProxyInstanceForCallingUser(
                    context, Looper.getMainLooper(), clientPackageName, context.getUser());
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Package " + clientPackageName + " not found. Ignoring.");
            return null;
        }
    }

    /**
     * Returns a proxy MediaRouter2 instance that allows you to control the routing of an app
     * specified by {@code clientPackageName} and {@code user}.
     *
     * <p>You can specify any {@link Looper} of choice on which internal state updates will run.
     *
     * <p>Proxy MediaRouter2 instances operate differently than regular MediaRouter2 instances:
     *
     * <ul>
     *   <li>
     *       <p>{@link #registerRouteCallback} ignores any {@link RouteDiscoveryPreference discovery
     *       preference} passed by a proxy router. Use a {@link RouteDiscoveryPreference} with empty
     *       {@link RouteDiscoveryPreference.Builder#setPreferredFeatures(List) preferred features}
     *       when setting a route callback.
     *   <li>
     *       <p>Methods returning non-system {@link RoutingController controllers} always return
     *       new instances with the latest data. Do not attempt to compare or store them. Instead,
     *       use {@link #getController(String)} or {@link #getControllers()} to query the most
     *       up-to-date state.
     *   <li>
     *       <p>Calls to {@link #setOnGetControllerHintsListener} are ignored.
     * </ul>
     *
     * @param context The {@link Context} of the caller.
     * @param looper The {@link Looper} on which to process internal state changes.
     * @param clientPackageName The package name of the app you want to control the routing of.
     * @param user The {@link UserHandle} of the user running the app for which to get the proxy
     *     router instance. Must match {@link Process#myUserHandle()} if the caller doesn't hold
     *     {@code Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     * @throws SecurityException if {@code user} does not match {@link Process#myUserHandle()} and
     *     the caller does not hold {@code Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     * @throws IllegalArgumentException if {@code clientPackageName} does not exist in {@code user}.
     */
    @FlaggedApi(FLAG_ENABLE_CROSS_USER_ROUTING_IN_MEDIA_ROUTER2)
    @RequiresPermission(
            anyOf = {
                Manifest.permission.MEDIA_CONTENT_CONTROL,
                Manifest.permission.MEDIA_ROUTING_CONTROL
            })
    @NonNull
    public static MediaRouter2 getInstance(
            @NonNull Context context,
            @NonNull Looper looper,
            @NonNull String clientPackageName,
            @NonNull UserHandle user) {
        return findOrCreateProxyInstanceForCallingUser(context, looper, clientPackageName, user);
    }

    /**
     * Returns the per-process singleton proxy router instance for the {@code clientPackageName} and
     * {@code user} if it exists, or otherwise it creates the appropriate instance.
     *
     * <p>If no instance has been created previously, the method will create an instance via {@link
     * #MediaRouter2(Context, Looper, String, UserHandle)}.
     */
    @NonNull
    private static MediaRouter2 findOrCreateProxyInstanceForCallingUser(
            Context context, Looper looper, String clientPackageName, UserHandle user) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(looper, "looper must not be null");
        Objects.requireNonNull(user, "user must not be null");

        if (TextUtils.isEmpty(clientPackageName)) {
            throw new IllegalArgumentException("clientPackageName must not be null or empty");
        }

        PackageNameUserHandlePair key = new PackageNameUserHandlePair(clientPackageName, user);

        synchronized (sSystemRouterLock) {
            MediaRouter2 instance = sAppToProxyRouterMap.get(key);
            if (instance == null) {
                instance = new MediaRouter2(context, looper, clientPackageName, user);
                sAppToProxyRouterMap.put(key, instance);
            }
            return instance;
        }
    }

    /**
     * Starts scanning remote routes.
     *
     * <p>Route discovery can happen even when the {@link #startScan()} is not called. This is
     * because the scanning could be started before by other apps. Therefore, calling this method
     * after calling {@link #stopScan()} does not necessarily mean that the routes found before are
     * removed and added again.
     *
     * <p>Use {@link RouteCallback} to get the route related events.
     *
     * <p>Note that calling start/stopScan is applied to all system routers in the same process.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @see #stopScan()
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void startScan() {
        mImpl.startScan();
    }

    /**
     * Stops scanning remote routes to reduce resource consumption.
     *
     * <p>Route discovery can be continued even after this method is called. This is because the
     * scanning is only turned off when all the apps stop scanning. Therefore, calling this method
     * does not necessarily mean the routes are removed. Also, for the same reason it does not mean
     * that {@link RouteCallback#onRoutesAdded(List)} is not called afterwards.
     *
     * <p>Use {@link RouteCallback} to get the route related events.
     *
     * <p>Note that calling start/stopScan is applied to all system routers in the same process.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @see #startScan()
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void stopScan() {
        mImpl.stopScan();
    }

    private MediaRouter2(Context appContext) {
        mContext = appContext;
        mMediaRouterService =
                IMediaRouterService.Stub.asInterface(
                        ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));
        mImpl = new LocalMediaRouter2Impl(mContext.getPackageName());
        mHandler = new Handler(Looper.getMainLooper());

        loadSystemRoutes();

        RoutingSessionInfo currentSystemSessionInfo = mImpl.getSystemSessionInfo();
        if (currentSystemSessionInfo == null) {
            throw new RuntimeException("Null currentSystemSessionInfo. Something is wrong.");
        }

        mSystemController = new SystemRoutingController(currentSystemSessionInfo);
    }

    private MediaRouter2(
            Context context, Looper looper, String clientPackageName, UserHandle user) {
        mContext = context;
        mHandler = new Handler(looper);
        mMediaRouterService =
                IMediaRouterService.Stub.asInterface(
                        ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));

        loadSystemRoutes();

        mSystemController =
                new SystemRoutingController(
                        ProxyMediaRouter2Impl.getSystemSessionInfoImpl(
                                mMediaRouterService, clientPackageName));
        mImpl = new ProxyMediaRouter2Impl(context, clientPackageName, user);
    }

    @GuardedBy("mLock")
    private void loadSystemRoutes() {
        List<MediaRoute2Info> currentSystemRoutes = null;
        try {
            currentSystemRoutes = mMediaRouterService.getSystemRoutes();
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

        if (currentSystemRoutes == null || currentSystemRoutes.isEmpty()) {
            throw new RuntimeException("Null or empty currentSystemRoutes. Something is wrong.");
        }

        for (MediaRoute2Info route : currentSystemRoutes) {
            mRoutes.put(route.getId(), route);
        }
    }

    /**
     * Gets the client package name of the app which this media router controls.
     *
     * <p>This will return null for non-system media routers.
     *
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @Nullable
    public String getClientPackageName() {
        return mImpl.getClientPackageName();
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     *
     * <p>If the specified callback is already registered, its registration will be updated for the
     * given {@link Executor executor} and {@link RouteDiscoveryPreference discovery preference}.
     */
    public void registerRouteCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RouteCallback routeCallback,
            @NonNull RouteDiscoveryPreference preference) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeCallback, "callback must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        RouteCallbackRecord record =
                mImpl.createRouteCallbackRecord(executor, routeCallback, preference);

        mRouteCallbackRecords.remove(record);
        // It can fail to add the callback record if another registration with the same callback
        // is happening but it's okay because either this or the other registration should be done.
        mRouteCallbackRecords.addIfAbsent(record);

        mImpl.registerRouteCallback();
    }

    /**
     * Unregisters the given callback. The callback will no longer receive events. If the callback
     * has not been added or been removed already, it is ignored.
     *
     * @param routeCallback the callback to unregister
     * @see #registerRouteCallback
     */
    public void unregisterRouteCallback(@NonNull RouteCallback routeCallback) {
        Objects.requireNonNull(routeCallback, "callback must not be null");

        if (!mRouteCallbackRecords.remove(new RouteCallbackRecord(null, routeCallback, null))) {
            Log.w(TAG, "unregisterRouteCallback: Ignoring unknown callback");
            return;
        }

        mImpl.unregisterRouteCallback();
    }

    /**
     * Registers the given callback to be invoked when the {@link RouteListingPreference} of the
     * target router changes.
     *
     * <p>Calls using a previously registered callback will overwrite the previous executor.
     *
     * @see #setRouteListingPreference(RouteListingPreference)
     */
    @FlaggedApi(FLAG_ENABLE_RLP_CALLBACKS_IN_MEDIA_ROUTER2)
    public void registerRouteListingPreferenceUpdatedCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<RouteListingPreference> routeListingPreferenceCallback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(routeListingPreferenceCallback, "callback must not be null");

        RouteListingPreferenceCallbackRecord record =
                new RouteListingPreferenceCallbackRecord(executor, routeListingPreferenceCallback);

        mListingPreferenceCallbackRecords.remove(record);
        mListingPreferenceCallbackRecords.add(record);
    }

    /**
     * Unregisters the given callback to not receive {@link RouteListingPreference} change events.
     *
     * @see #registerRouteListingPreferenceUpdatedCallback(Executor, Consumer)
     */
    @FlaggedApi(FLAG_ENABLE_RLP_CALLBACKS_IN_MEDIA_ROUTER2)
    public void unregisterRouteListingPreferenceUpdatedCallback(
            @NonNull Consumer<RouteListingPreference> callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mListingPreferenceCallbackRecords.remove(
                new RouteListingPreferenceCallbackRecord(/* executor */ null, callback))) {
            Log.w(
                    TAG,
                    "unregisterRouteListingPreferenceUpdatedCallback: Ignoring an unknown"
                        + " callback");
        }
    }

    /**
     * Shows the system output switcher dialog.
     *
     * <p>Should only be called when the context of MediaRouter2 is in the foreground and visible on
     * the screen.
     *
     * <p>The appearance and precise behaviour of the system output switcher dialog may vary across
     * different devices, OS versions, and form factors, but the basic functionality stays the same.
     *
     * <p>See <a
     * href="https://developer.android.com/guide/topics/media/media-routing#output-switcher">Output
     * Switcher documentation</a> for more details.
     *
     * @return {@code true} if the output switcher dialog is being shown, or {@code false} if the
     * call is ignored because the app is in the background.
     */
    public boolean showSystemOutputSwitcher() {
        return mImpl.showSystemOutputSwitcher();
    }

    /**
     * Sets the {@link RouteListingPreference} of the app associated to this media router.
     *
     * <p>Use this method to inform the system UI of the routes that you would like to list for
     * media routing, via the Output Switcher.
     *
     * <p>You should call this method before {@link #registerRouteCallback registering any route
     * callbacks} and immediately after receiving any {@link RouteCallback#onRoutesUpdated route
     * updates} in order to keep the system UI in a consistent state. You can also call this method
     * at any other point to update the listing preference dynamically.
     *
     * <p>Any calls to this method from a privileged router will throw an {@link
     * UnsupportedOperationException}.
     *
     * <p>Notes:
     *
     * <ol>
     *   <li>You should not include the ids of two or more routes with a match in their {@link
     *       MediaRoute2Info#getDeduplicationIds() deduplication ids}. If you do, the system will
     *       deduplicate them using its own criteria.
     *   <li>You can use this method to rank routes in the output switcher, placing the more
     *       important routes first. The system might override the proposed ranking.
     *   <li>You can use this method to avoid listing routes using dynamic criteria. For example,
     *       you can limit access to a specific type of device according to runtime criteria.
     * </ol>
     *
     * @param routeListingPreference The {@link RouteListingPreference} for the system to use for
     *     route listing. When null, the system uses its default listing criteria.
     */
    public void setRouteListingPreference(@Nullable RouteListingPreference routeListingPreference) {
        mImpl.setRouteListingPreference(routeListingPreference);
    }

    /**
     * Returns the current {@link RouteListingPreference} of the target router.
     *
     * <p>If this instance was created using {@code #getInstance(Context, String)}, then it returns
     * the last {@link RouteListingPreference} set by the process this router was created for.
     *
     * @see #setRouteListingPreference(RouteListingPreference)
     */
    @FlaggedApi(FLAG_ENABLE_RLP_CALLBACKS_IN_MEDIA_ROUTER2)
    @Nullable
    public RouteListingPreference getRouteListingPreference() {
        synchronized (mLock) {
            return mRouteListingPreference;
        }
    }

    @GuardedBy("mLock")
    private boolean updateDiscoveryPreferenceIfNeededLocked() {
        RouteDiscoveryPreference newDiscoveryPreference = new RouteDiscoveryPreference.Builder(
                mRouteCallbackRecords.stream().map(record -> record.mPreference).collect(
                        Collectors.toList())).build();

        if (Objects.equals(mDiscoveryPreference, newDiscoveryPreference)) {
            return false;
        }
        mDiscoveryPreference = newDiscoveryPreference;
        updateFilteredRoutesLocked();
        return true;
    }

    /**
     * Gets the list of all discovered routes. This list includes the routes that are not related to
     * the client app.
     *
     * <p>This will return an empty list for non-system media routers.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public List<MediaRoute2Info> getAllRoutes() {
        return mImpl.getAllRoutes();
    }

    /**
     * Gets the unmodifiable list of {@link MediaRoute2Info routes} currently known to the media
     * router.
     *
     * <p>Please note that the list can be changed before callbacks are invoked.
     *
     * @return the list of routes that contains at least one of the route features in discovery
     *     preferences registered by the application
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        synchronized (mLock) {
            return mFilteredRoutes;
        }
    }

    /**
     * Registers a callback to get the result of {@link #transferTo(MediaRoute2Info)}.
     * If you register the same callback twice or more, it will be ignored.
     *
     * @param executor the executor to execute the callback on
     * @param callback the callback to register
     * @see #unregisterTransferCallback
     */
    public void registerTransferCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull TransferCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        TransferCallbackRecord record = new TransferCallbackRecord(executor, callback);
        if (!mTransferCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "registerTransferCallback: Ignoring the same callback");
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
        }
    }

    /**
     * Registers a {@link ControllerCallback}. If you register the same callback twice or more, it
     * will be ignored.
     *
     * @see #unregisterControllerCallback(ControllerCallback)
     */
    public void registerControllerCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull ControllerCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        ControllerCallbackRecord record = new ControllerCallbackRecord(executor, callback);
        if (!mControllerCallbackRecords.addIfAbsent(record)) {
            Log.w(TAG, "registerControllerCallback: Ignoring the same callback");
        }
    }

    /**
     * Unregisters a {@link ControllerCallback}. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     *
     * @see #registerControllerCallback(Executor, ControllerCallback)
     */
    public void unregisterControllerCallback(@NonNull ControllerCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mControllerCallbackRecords.remove(new ControllerCallbackRecord(null, callback))) {
            Log.w(TAG, "unregisterControllerCallback: Ignoring an unknown callback");
        }
    }

    /**
     * Sets an {@link OnGetControllerHintsListener} to send hints when creating a
     * {@link RoutingController}. To send the hints, listener should be set <em>BEFORE</em> calling
     * {@link #transferTo(MediaRoute2Info)}.
     *
     * @param listener A listener to send optional app-specific hints when creating a controller.
     *     {@code null} for unset.
     */
    public void setOnGetControllerHintsListener(@Nullable OnGetControllerHintsListener listener) {
        mImpl.setOnGetControllerHintsListener(listener);
    }

    /**
     * Transfers the current media to the given route. If it's necessary a new
     * {@link RoutingController} is created or it is handled within the current routing controller.
     *
     * @param route the route you want to transfer the current media to. Pass {@code null} to
     *              stop routing of the current media.
     * @see TransferCallback#onTransfer
     * @see TransferCallback#onTransferFailure
     */
    public void transferTo(@NonNull MediaRoute2Info route) {
        mImpl.transferTo(route);
    }

    /**
     * Stops the current media routing. If the {@link #getSystemController() system controller}
     * controls the media routing, this method is a no-op.
     */
    public void stop() {
        mImpl.stop();
    }

    /**
     * Transfers the media of a routing controller to the given route.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @param controller a routing controller controlling media routing.
     * @param route the route you want to transfer the media to.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void transfer(@NonNull RoutingController controller, @NonNull MediaRoute2Info route) {
        mImpl.transfer(
                controller.getRoutingSessionInfo(),
                route,
                Process.myUserHandle(),
                mContext.getPackageName());
    }

    /**
     * Transfers the media of a routing controller to the given route.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @param controller a routing controller controlling media routing.
     * @param route the route you want to transfer the media to.
     * @param transferInitiatorUserHandle the user handle of the app that initiated the transfer
     *     request.
     * @param transferInitiatorPackageName the package name of the app that initiated the transfer.
     *     This value is used with the user handle to populate {@link
     *     RoutingController#wasTransferRequestedBySelf()}.
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
    public void transfer(
            @NonNull RoutingController controller,
            @NonNull MediaRoute2Info route,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        mImpl.transfer(
                controller.getRoutingSessionInfo(),
                route,
                transferInitiatorUserHandle,
                transferInitiatorPackageName);
    }

    void requestCreateController(
            @NonNull RoutingController controller,
            @NonNull MediaRoute2Info route,
            long managerRequestId,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {

        final int requestId = mNextRequestId.getAndIncrement();

        ControllerCreationRequest request =
                new ControllerCreationRequest(requestId, managerRequestId, route, controller);
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
                        stub,
                        requestId,
                        managerRequestId,
                        controller.getRoutingSessionInfo(),
                        route,
                        controllerHints,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName);
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
     *
     * <p>Note: The system controller can't be released. Calling {@link RoutingController#release()}
     * will be ignored.
     *
     * <p>This method always returns the same instance.
     */
    @NonNull
    public RoutingController getSystemController() {
        return mSystemController;
    }

    /**
     * Gets a {@link RoutingController} whose ID is equal to the given ID.
     * Returns {@code null} if there is no matching controller.
     */
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
     *
     * <p>Note: The list returned here will never be empty. The first element in the list is
     * always the {@link #getSystemController() system controller}.
     */
    @NonNull
    public List<RoutingController> getControllers() {
        return mImpl.getControllers();
    }

    /**
     * Requests a volume change for the route asynchronously.
     * It may have no effect if the route is currently not selected.
     *
     * <p>This will be no-op for non-system media routers.
     *
     * @param volume The new volume value between 0 and {@link MediaRoute2Info#getVolumeMax}.
     * @see #getInstance(Context, String)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        Objects.requireNonNull(route, "route must not be null");

        mImpl.setRouteVolume(route, volume);
    }

    void syncRoutesOnHandler(
            List<MediaRoute2Info> currentRoutes, RoutingSessionInfo currentSystemSessionInfo) {
        if (currentRoutes == null || currentRoutes.isEmpty() || currentSystemSessionInfo == null) {
            Log.e(TAG, "syncRoutesOnHandler: Received wrong data. currentRoutes=" + currentRoutes
                    + ", currentSystemSessionInfo=" + currentSystemSessionInfo);
            return;
        }

        updateRoutesOnHandler(currentRoutes);

        RoutingSessionInfo oldInfo = mSystemController.getRoutingSessionInfo();
        mSystemController.setRoutingSessionInfo(currentSystemSessionInfo);
        if (!oldInfo.equals(currentSystemSessionInfo)) {
            notifyControllerUpdated(mSystemController);
        }
    }

    void dispatchFilteredRoutesUpdatedOnHandler(List<MediaRoute2Info> newRoutes) {
        List<MediaRoute2Info> addedRoutes = new ArrayList<>();
        List<MediaRoute2Info> removedRoutes = new ArrayList<>();
        List<MediaRoute2Info> changedRoutes = new ArrayList<>();

        Set<String> newRouteIds =
                newRoutes.stream().map(MediaRoute2Info::getId).collect(Collectors.toSet());

        for (MediaRoute2Info route : newRoutes) {
            MediaRoute2Info prevRoute = mPreviousRoutes.get(route.getId());
            if (prevRoute == null) {
                addedRoutes.add(route);
            } else if (!prevRoute.equals(route)) {
                changedRoutes.add(route);
            }
        }

        for (int i = 0; i < mPreviousRoutes.size(); i++) {
            if (!newRouteIds.contains(mPreviousRoutes.keyAt(i))) {
                removedRoutes.add(mPreviousRoutes.valueAt(i));
            }
        }

        // update previous routes
        for (MediaRoute2Info route : removedRoutes) {
            mPreviousRoutes.remove(route.getId());
        }
        for (MediaRoute2Info route : addedRoutes) {
            mPreviousRoutes.put(route.getId(), route);
        }
        for (MediaRoute2Info route : changedRoutes) {
            mPreviousRoutes.put(route.getId(), route);
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

        // Note: We don't notify clients of changes in route ordering.
        if (!addedRoutes.isEmpty() || !removedRoutes.isEmpty() || !changedRoutes.isEmpty()) {
            notifyRoutesUpdated(newRoutes);
        }
    }

    void updateRoutesOnHandler(List<MediaRoute2Info> newRoutes) {
        synchronized (mLock) {
            mRoutes.clear();
            for (MediaRoute2Info route : newRoutes) {
                mRoutes.put(route.getId(), route);
            }
            updateFilteredRoutesLocked();
        }
    }

    /** Updates filtered routes and dispatch callbacks */
    @GuardedBy("mLock")
    void updateFilteredRoutesLocked() {
        mFilteredRoutes =
                Collections.unmodifiableList(
                        filterRoutesWithCompositePreferenceLocked(List.copyOf(mRoutes.values())));
        mHandler.sendMessage(
                obtainMessage(
                        MediaRouter2::dispatchFilteredRoutesUpdatedOnHandler,
                        this,
                        mFilteredRoutes));
    }

    /**
     * Creates a controller and calls the {@link TransferCallback#onTransfer}. If the controller
     * creation has failed, then it calls {@link TransferCallback#onTransferFailure}.
     *
     * <p>Pass {@code null} to sessionInfo for the failure case.
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
        } else if (!TextUtils.equals(requestedRoute.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(
                    TAG,
                    "The session's provider ID does not match the requested route's. "
                            + "(requested route's providerId="
                            + requestedRoute.getProviderId()
                            + ", actual providerId="
                            + sessionInfo.getProviderId()
                            + ")");
            notifyTransferFailure(requestedRoute);
            return;
        }

        RoutingController oldController = matchingRequest.mOldController;
        // When the old controller is released before transferred, treat it as a failure.
        // This could also happen when transfer is requested twice or more.
        if (!oldController.scheduleRelease()) {
            Log.w(
                    TAG,
                    "createControllerOnHandler: "
                            + "Ignoring controller creation for released old controller. "
                            + "oldController="
                            + oldController);
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
            Log.w(
                    TAG,
                    "updateControllerOnHandler: Matching controller not found. uniqueSessionId="
                            + sessionInfo.getId());
            return;
        }

        RoutingSessionInfo oldInfo = matchingController.getRoutingSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(
                    TAG,
                    "updateControllerOnHandler: Provider IDs are not matched. old="
                            + oldInfo.getProviderId()
                            + ", new="
                            + sessionInfo.getProviderId());
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
                Log.d(
                        TAG,
                        "releaseControllerOnHandler: Matching controller not found. "
                                + "uniqueSessionId="
                                + sessionInfo.getId());
            }
            return;
        }

        RoutingSessionInfo oldInfo = matchingController.getRoutingSessionInfo();
        if (!TextUtils.equals(oldInfo.getProviderId(), sessionInfo.getProviderId())) {
            Log.w(
                    TAG,
                    "releaseControllerOnHandler: Provider IDs are not matched. old="
                            + oldInfo.getProviderId()
                            + ", new="
                            + sessionInfo.getProviderId());
            return;
        }

        matchingController.releaseInternal(/* shouldReleaseSession= */ false);
    }

    void onRequestCreateControllerByManagerOnHandler(
            RoutingSessionInfo oldSession,
            MediaRoute2Info route,
            long managerRequestId,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        Log.i(
                TAG,
                TextUtils.formatSimple(
                        "requestCreateSessionByManager | requestId: %d, oldSession: %s, route: %s",
                        managerRequestId, oldSession, route));
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
        requestCreateController(controller, route, managerRequestId, transferInitiatorUserHandle,
                transferInitiatorPackageName);
    }

    private List<MediaRoute2Info> getSortedRoutes(
            List<MediaRoute2Info> routes, List<String> packageOrder) {
        if (packageOrder.isEmpty()) {
            return routes;
        }
        Map<String, Integer> packagePriority = new ArrayMap<>();
        int count = packageOrder.size();
        for (int i = 0; i < count; i++) {
            // the last package will have 1 as the priority
            packagePriority.put(packageOrder.get(i), count - i);
        }
        ArrayList<MediaRoute2Info> sortedRoutes = new ArrayList<>(routes);
        // take the negative for descending order
        sortedRoutes.sort(
                Comparator.comparingInt(r -> -packagePriority.getOrDefault(r.getPackageName(), 0)));
        return sortedRoutes;
    }

    @GuardedBy("mLock")
    private List<MediaRoute2Info> filterRoutesWithCompositePreferenceLocked(
            List<MediaRoute2Info> routes) {

        Set<String> deduplicationIdSet = new ArraySet<>();

        List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
        for (MediaRoute2Info route :
                getSortedRoutes(routes, mDiscoveryPreference.getDeduplicationPackageOrder())) {
            if (!route.hasAnyFeatures(mDiscoveryPreference.getPreferredFeatures())) {
                continue;
            }
            if (!mDiscoveryPreference.getAllowedPackages().isEmpty()
                    && (route.getPackageName() == null
                            || !mDiscoveryPreference
                                    .getAllowedPackages()
                                    .contains(route.getPackageName()))) {
                continue;
            }
            if (mDiscoveryPreference.shouldRemoveDuplicates()) {
                if (!Collections.disjoint(deduplicationIdSet, route.getDeduplicationIds())) {
                    continue;
                }
                deduplicationIdSet.addAll(route.getDeduplicationIds());
            }
            filteredRoutes.add(route);
        }
        return filteredRoutes;
    }

    @NonNull
    private List<MediaRoute2Info> getRoutesWithIds(@NonNull List<String> routeIds) {
        synchronized (mLock) {
            return routeIds.stream()
                    .map(mRoutes::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private void notifyRoutesAdded(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(() -> record.mRouteCallback.onRoutesAdded(filteredRoutes));
            }
        }
    }

    private void notifyRoutesRemoved(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesRemoved(filteredRoutes));
            }
        }
    }

    private void notifyRoutesChanged(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            if (!filteredRoutes.isEmpty()) {
                record.mExecutor.execute(
                        () -> record.mRouteCallback.onRoutesChanged(filteredRoutes));
            }
        }
    }

    private void notifyRoutesUpdated(List<MediaRoute2Info> routes) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            List<MediaRoute2Info> filteredRoutes =
                    mImpl.filterRoutesWithIndividualPreference(routes, record.mPreference);
            record.mExecutor.execute(() -> record.mRouteCallback.onRoutesUpdated(filteredRoutes));
        }
    }

    private void notifyPreferredFeaturesChanged(List<String> features) {
        for (RouteCallbackRecord record : mRouteCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteCallback.onPreferredFeaturesChanged(features));
        }
    }

    private void notifyRouteListingPreferenceUpdated(@Nullable RouteListingPreference preference) {
        for (RouteListingPreferenceCallbackRecord record : mListingPreferenceCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mRouteListingPreferenceCallback.accept(preference));
        }
    }

    private void notifyTransfer(RoutingController oldController, RoutingController newController) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mTransferCallback.onTransfer(oldController, newController));
        }
    }

    private void notifyTransferFailure(MediaRoute2Info route) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(() -> record.mTransferCallback.onTransferFailure(route));
        }
    }

    private void notifyRequestFailed(int reason) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(() -> record.mTransferCallback.onRequestFailed(reason));
        }
    }

    private void notifyStop(RoutingController controller) {
        for (TransferCallbackRecord record : mTransferCallbackRecords) {
            record.mExecutor.execute(() -> record.mTransferCallback.onStop(controller));
        }
    }

    private void notifyControllerUpdated(RoutingController controller) {
        for (ControllerCallbackRecord record : mControllerCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onControllerUpdated(controller));
        }
    }

    /** Callback for receiving events about media route discovery. */
    public abstract static class RouteCallback {
        /**
         * Called when routes are added. Whenever you register a callback, this will be invoked with
         * known routes.
         *
         * @param routes the list of routes that have been added. It's never empty.
         * @deprecated Use {@link #onRoutesUpdated(List)} instead.
         */
        @Deprecated
        public void onRoutesAdded(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when routes are removed.
         *
         * @param routes the list of routes that have been removed. It's never empty.
         * @deprecated Use {@link #onRoutesUpdated(List)} instead.
         */
        @Deprecated
        public void onRoutesRemoved(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when the properties of one or more existing routes are changed. For example, it is
         * called when a route's name or volume have changed.
         *
         * @param routes the list of routes that have been changed. It's never empty.
         * @deprecated Use {@link #onRoutesUpdated(List)} instead.
         */
        @Deprecated
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when the route list is updated, which can happen when routes are added, removed,
         * or modified. It will also be called when a route callback is registered.
         *
         * @param routes the updated list of routes filtered by the callback's individual discovery
         *     preferences.
         */
        public void onRoutesUpdated(@NonNull List<MediaRoute2Info> routes) {}

        /**
         * Called when the client app's preferred features are changed. When this is called, it is
         * recommended to {@link #getRoutes()} to get the routes that are currently available to the
         * app.
         *
         * @param preferredFeatures the new preferred features set by the application
         * @hide
         */
        @SystemApi
        public void onPreferredFeaturesChanged(@NonNull List<String> preferredFeatures) {}
    }

    /** Callback for receiving events on media transfer. */
    public abstract static class TransferCallback {
        /**
         * Called when a media is transferred between two different routing controllers. This can
         * happen by calling {@link #transferTo(MediaRoute2Info)}.
         *
         * <p>Override this to start playback with {@code newController}. You may want to get the
         * status of the media that is being played with {@code oldController} and resume it
         * continuously with {@code newController}. After this is called, any callbacks with {@code
         * oldController} will not be invoked unless {@code oldController} is the {@link
         * #getSystemController() system controller}. You need to {@link RoutingController#release()
         * release} {@code oldController} before playing the media with {@code newController}.
         *
         * @param oldController the previous controller that controlled routing
         * @param newController the new controller to control routing
         * @see #transferTo(MediaRoute2Info)
         */
        public void onTransfer(
                @NonNull RoutingController oldController,
                @NonNull RoutingController newController) {}

        /**
         * Called when {@link #transferTo(MediaRoute2Info)} failed.
         *
         * @param requestedRoute the route info which was used for the transfer
         */
        public void onTransferFailure(@NonNull MediaRoute2Info requestedRoute) {}

        /**
         * Called when a media routing stops. It can be stopped by a user or a provider. App should
         * not continue playing media locally when this method is called. The {@code controller} is
         * released before this method is called.
         *
         * @param controller the controller that controlled the stopped media routing
         */
        public void onStop(@NonNull RoutingController controller) {}

        /**
         * Called when a routing request fails.
         *
         * @param reason Reason for failure as per {@link
         *     android.media.MediaRoute2ProviderService.Reason}
         * @hide
         */
        public void onRequestFailed(int reason) {}
    }

    /**
     * A listener interface to send optional app-specific hints when creating a {@link
     * RoutingController}.
     */
    public interface OnGetControllerHintsListener {
        /**
         * Called when the {@link MediaRouter2} or the system is about to request a media route
         * provider service to create a controller with the given route. The {@link Bundle} returned
         * here will be sent to media route provider service as a hint.
         *
         * <p>Since controller creation can be requested by the {@link MediaRouter2} and the system,
         * set the listener as soon as possible after acquiring {@link MediaRouter2} instance. The
         * method will be called on the same thread that calls {@link #transferTo(MediaRoute2Info)}
         * or the main thread if it is requested by the system.
         *
         * @param route the route to create a controller with
         * @return An optional bundle of app-specific arguments to send to the provider, or {@code
         *     null} if none. The contents of this bundle may affect the result of controller
         *     creation.
         * @see MediaRoute2ProviderService#onCreateSession(long, String, String, Bundle)
         */
        @Nullable
        Bundle onGetControllerHints(@NonNull MediaRoute2Info route);
    }

    /** Callback for receiving {@link RoutingController} updates. */
    public abstract static class ControllerCallback {
        /**
         * Called when a controller is updated. (e.g., when the selected routes of the controller is
         * changed or when the volume of the controller is changed.)
         *
         * @param controller the updated controller. It may be the {@link #getSystemController()
         *     system controller}.
         * @see #getSystemController()
         */
        public void onControllerUpdated(@NonNull RoutingController controller) {}
    }

    /**
     * A class to control media routing session in media route provider. For example,
     * selecting/deselecting/transferring to routes of a session can be done through this. Instances
     * are created when {@link TransferCallback#onTransfer(RoutingController, RoutingController)} is
     * called, which is invoked after {@link #transferTo(MediaRoute2Info)} is called.
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
         * Gets the original session ID set by {@link RoutingSessionInfo.Builder#Builder(String,
         * String)}.
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
         * Gets the control hints used to control routing session if available. It is set by the
         * media route provider.
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
         * Returns the unmodifiable list of transferable routes for the session.
         *
         * @hide
         */
        @NonNull
        public List<MediaRoute2Info> getTransferableRoutes() {
            List<String> transferableRoutes;
            synchronized (mControllerLock) {
                transferableRoutes = mSessionInfo.getTransferableRoutes();
            }
            return getRoutesWithIds(transferableRoutes);
        }

        /**
         * Returns whether the transfer was requested by the calling app (as determined by comparing
         * {@link UserHandle} and package name).
         */
        @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
        public boolean wasTransferRequestedBySelf() {
            RoutingSessionInfo sessionInfo = getRoutingSessionInfo();

            UserHandle transferInitiatorUserHandle = sessionInfo.getTransferInitiatorUserHandle();
            String transferInitiatorPackageName = sessionInfo.getTransferInitiatorPackageName();

            return Objects.equals(Process.myUserHandle(), transferInitiatorUserHandle)
                    && Objects.equals(mContext.getPackageName(), transferInitiatorPackageName);
        }

        /**
         * Returns the current {@link RoutingSessionInfo} associated to this controller.
         */
        @NonNull
        public RoutingSessionInfo getRoutingSessionInfo() {
            synchronized (mControllerLock) {
                return mSessionInfo;
            }
        }

        /**
         * Gets the information about how volume is handled on the session.
         *
         * <p>Please note that you may not control the volume of the session even when you can
         * control the volume of each selected route in the session.
         *
         * @return {@link MediaRoute2Info#PLAYBACK_VOLUME_FIXED} or {@link
         *     MediaRoute2Info#PLAYBACK_VOLUME_VARIABLE}
         */
        @MediaRoute2Info.PlaybackVolume
        public int getVolumeHandling() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolumeHandling();
            }
        }

        /** Gets the maximum volume of the session. */
        public int getVolumeMax() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolumeMax();
            }
        }

        /**
         * Gets the current volume of the session.
         *
         * <p>When it's available, it represents the volume of routing session, which is a group of
         * selected routes. Use {@link MediaRoute2Info#getVolume()} to get the volume of a route,
         *
         * @see MediaRoute2Info#getVolume()
         */
        public int getVolume() {
            synchronized (mControllerLock) {
                return mSessionInfo.getVolume();
            }
        }

        /**
         * Returns true if this controller is released, false otherwise. If it is released, then all
         * other getters from this instance may return invalid values. Also, any operations to this
         * instance will be ignored once released.
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
         * MediaRouter2#transferTo(MediaRoute2Info) transferring to a route}, where the media is
         * expected to 'move' from one route to another.
         *
         * <p>The given route must satisfy all of the following conditions:
         *
         * <ul>
         *   <li>It should not be included in {@link #getSelectedRoutes()}
         *   <li>It should be included in {@link #getSelectableRoutes()}
         * </ul>
         *
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
            if (containsRouteInfoWithId(selectedRoutes, route.getId())) {
                Log.w(TAG, "Ignoring selecting a route that is already selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> selectableRoutes = getSelectableRoutes();
            if (!containsRouteInfoWithId(selectableRoutes, route.getId())) {
                Log.w(TAG, "Ignoring selecting a non-selectable route=" + route);
                return;
            }

            mImpl.selectRoute(route, getRoutingSessionInfo());
        }

        /**
         * Deselects a route from the remote session. After a route is deselected, the media is
         * expected to be stopped on the deselected route.
         *
         * <p>The given route must satisfy all of the following conditions:
         *
         * <ul>
         *   <li>It should be included in {@link #getSelectedRoutes()}
         *   <li>It should be included in {@link #getDeselectableRoutes()}
         * </ul>
         *
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
            if (!containsRouteInfoWithId(selectedRoutes, route.getId())) {
                Log.w(TAG, "Ignoring deselecting a route that is not selected. route=" + route);
                return;
            }

            List<MediaRoute2Info> deselectableRoutes = getDeselectableRoutes();
            if (!containsRouteInfoWithId(deselectableRoutes, route.getId())) {
                Log.w(TAG, "Ignoring deselecting a non-deselectable route=" + route);
                return;
            }

            mImpl.deselectRoute(route, getRoutingSessionInfo());
        }

        /**
         * Transfers to a given route for the remote session. The given route must be included in
         * {@link RoutingSessionInfo#getTransferableRoutes()}.
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
         *     (inclusive).
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

            mImpl.setSessionVolume(volume, getRoutingSessionInfo());
        }

        /**
         * Releases this controller and the corresponding session. Any operations on this controller
         * after calling this method will be ignored. The devices that are playing media will stop
         * playing it.
         */
        public void release() {
            releaseInternal(/* shouldReleaseSession= */ true);
        }

        /**
         * Schedules release of the controller.
         *
         * @return {@code true} if it's successfully scheduled, {@code false} if it's already
         *     scheduled to be released or released.
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

            mImpl.releaseSession(shouldReleaseSession, shouldNotifyStop, this);
        }

        @Override
        public String toString() {
            // To prevent logging spam, we only print the ID of each route.
            List<String> selectedRoutes =
                    getSelectedRoutes().stream()
                            .map(MediaRoute2Info::getId)
                            .collect(Collectors.toList());
            List<String> selectableRoutes =
                    getSelectableRoutes().stream()
                            .map(MediaRoute2Info::getId)
                            .collect(Collectors.toList());
            List<String> deselectableRoutes =
                    getDeselectableRoutes().stream()
                            .map(MediaRoute2Info::getId)
                            .collect(Collectors.toList());

            StringBuilder result =
                    new StringBuilder()
                            .append("RoutingController{ ")
                            .append("id=")
                            .append(getId())
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

        void setRoutingSessionInfo(@NonNull RoutingSessionInfo info) {
            synchronized (mControllerLock) {
                mSessionInfo = info;
            }
        }

        /** Returns whether any route in {@code routeList} has a same unique ID with given route. */
        private static boolean containsRouteInfoWithId(
                @NonNull List<MediaRoute2Info> routeList, @NonNull String routeId) {
            for (MediaRoute2Info info : routeList) {
                if (TextUtils.equals(routeId, info.getId())) {
                    return true;
                }
            }
            return false;
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

        RouteCallbackRecord(
                @Nullable Executor executor,
                @NonNull RouteCallback routeCallback,
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

    private static final class RouteListingPreferenceCallbackRecord {
        public final Executor mExecutor;
        public final Consumer<RouteListingPreference> mRouteListingPreferenceCallback;

        /* package */ RouteListingPreferenceCallbackRecord(
                @NonNull Executor executor,
                @NonNull Consumer<RouteListingPreference> routeListingPreferenceCallback) {
            mExecutor = executor;
            mRouteListingPreferenceCallback = routeListingPreferenceCallback;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RouteListingPreferenceCallbackRecord)) {
                return false;
            }
            return mRouteListingPreferenceCallback
                    == ((RouteListingPreferenceCallbackRecord) obj).mRouteListingPreferenceCallback;
        }

        @Override
        public int hashCode() {
            return mRouteListingPreferenceCallback.hashCode();
        }
    }

    static final class TransferCallbackRecord {
        public final Executor mExecutor;
        public final TransferCallback mTransferCallback;

        TransferCallbackRecord(
                @NonNull Executor executor, @NonNull TransferCallback transferCallback) {
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

        ControllerCallbackRecord(
                @Nullable Executor executor, @NonNull ControllerCallback callback) {
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

        ControllerCreationRequest(
                int requestId,
                long managerRequestId,
                @NonNull MediaRoute2Info route,
                @NonNull RoutingController oldController) {
            mRequestId = requestId;
            mManagerRequestId = managerRequestId;
            mRoute = Objects.requireNonNull(route, "route must not be null");
            mOldController =
                    Objects.requireNonNull(oldController, "oldController must not be null");
        }
    }

    class MediaRouter2Stub extends IMediaRouter2.Stub {
        @Override
        public void notifyRouterRegistered(
                List<MediaRoute2Info> currentRoutes, RoutingSessionInfo currentSystemSessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::syncRoutesOnHandler,
                            MediaRouter2.this,
                            currentRoutes,
                            currentSystemSessionInfo));
        }

        @Override
        public void notifyRoutesUpdated(List<MediaRoute2Info> routes) {
            mHandler.sendMessage(
                    obtainMessage(MediaRouter2::updateRoutesOnHandler, MediaRouter2.this, routes));
        }

        @Override
        public void notifySessionCreated(int requestId, @Nullable RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::createControllerOnHandler,
                            MediaRouter2.this,
                            requestId,
                            sessionInfo));
        }

        @Override
        public void notifySessionInfoChanged(@Nullable RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::updateControllerOnHandler,
                            MediaRouter2.this,
                            sessionInfo));
        }

        @Override
        public void notifySessionReleased(RoutingSessionInfo sessionInfo) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::releaseControllerOnHandler,
                            MediaRouter2.this,
                            sessionInfo));
        }

        @Override
        public void requestCreateSessionByManager(
                long managerRequestId,
                RoutingSessionInfo oldSession,
                MediaRoute2Info route,
                UserHandle transferInitiatorUserHandle,
                String transferInitiatorPackageName) {
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRouter2::onRequestCreateControllerByManagerOnHandler,
                            MediaRouter2.this,
                            oldSession,
                            route,
                            managerRequestId,
                            transferInitiatorUserHandle,
                            transferInitiatorPackageName));
        }
    }

    /**
     * Provides a common interface for separating {@link LocalMediaRouter2Impl local} and {@link
     * ProxyMediaRouter2Impl proxy} {@link MediaRouter2} instances.
     */
    private interface MediaRouter2Impl {
        void startScan();

        void stopScan();

        String getClientPackageName();

        String getPackageName();

        RoutingSessionInfo getSystemSessionInfo();

        RouteCallbackRecord createRouteCallbackRecord(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull RouteCallback routeCallback,
                @NonNull RouteDiscoveryPreference preference);

        void registerRouteCallback();

        void unregisterRouteCallback();

        void setRouteListingPreference(@Nullable RouteListingPreference preference);

        boolean showSystemOutputSwitcher();

        List<MediaRoute2Info> getAllRoutes();

        void setOnGetControllerHintsListener(OnGetControllerHintsListener listener);

        void transferTo(MediaRoute2Info route);

        void stop();

        void transfer(
                @NonNull RoutingSessionInfo sessionInfo,
                @NonNull MediaRoute2Info route,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName);

        List<RoutingController> getControllers();

        void setRouteVolume(MediaRoute2Info route, int volume);

        List<MediaRoute2Info> filterRoutesWithIndividualPreference(
                List<MediaRoute2Info> routes, RouteDiscoveryPreference discoveryPreference);

        // RoutingController methods.
        void setSessionVolume(int volume, RoutingSessionInfo sessionInfo);

        void selectRoute(MediaRoute2Info route, RoutingSessionInfo sessionInfo);

        void deselectRoute(MediaRoute2Info route, RoutingSessionInfo sessionInfo);

        void releaseSession(
                boolean shouldReleaseSession,
                boolean shouldNotifyStop,
                RoutingController controller);

    }

    /**
     * Implements logic specific to proxy {@link MediaRouter2} instances.
     *
     * <p>A proxy {@link MediaRouter2} instance controls the routing of a different package and can
     * be obtained by calling {@link #getInstance(Context, String)}. This requires {@link
     * Manifest.permission#MEDIA_CONTENT_CONTROL MEDIA_CONTENT_CONTROL} permission.
     *
     * <p>Proxy routers behave differently than local routers. See {@link #getInstance(Context,
     * String)} for more details.
     */
    private class ProxyMediaRouter2Impl implements MediaRouter2Impl {
        // Fields originating from MediaRouter2Manager.
        private final IMediaRouter2Manager.Stub mClient;
        private final CopyOnWriteArrayList<MediaRouter2Manager.TransferRequest>
                mTransferRequests = new CopyOnWriteArrayList<>();
        private final AtomicInteger mScanRequestCount = new AtomicInteger(/* initialValue= */ 0);

        // Fields originating from MediaRouter2.
        @NonNull private final String mClientPackageName;
        @NonNull private final UserHandle mClientUser;
        private final AtomicBoolean mIsScanning = new AtomicBoolean(/* initialValue= */ false);

        ProxyMediaRouter2Impl(
                @NonNull Context context,
                @NonNull String clientPackageName,
                @NonNull UserHandle user) {
            mClientUser = user;
            mClientPackageName = clientPackageName;
            mClient = new Client();

            try {
                mMediaRouterService.registerProxyRouter(
                        mClient,
                        context.getApplicationContext().getPackageName(),
                        clientPackageName,
                        user);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }

            mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;
        }

        @Override
        public void startScan() {
            if (!mIsScanning.getAndSet(true)) {
                if (mScanRequestCount.getAndIncrement() == 0) {
                    try {
                        mMediaRouterService.startScan(mClient);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                }
            }
        }

        @Override
        public void stopScan() {
            if (mIsScanning.getAndSet(false)) {
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
                        mMediaRouterService.stopScan(mClient);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                }
            }
        }

        @Override
        public String getClientPackageName() {
            return mClientPackageName;
        }

        /**
         * Returns {@code null}. This refers to the package name of the caller app, which is only
         * relevant for local routers.
         */
        @Override
        public String getPackageName() {
            return null;
        }

        @Override
        public RoutingSessionInfo getSystemSessionInfo() {
            return getSystemSessionInfoImpl(mMediaRouterService, mClientPackageName);
        }

        /**
         * {@link RouteDiscoveryPreference Discovery preferences} are ignored for proxy routers, as
         * their callbacks should receive events related to the media app's preferences. This is
         * equivalent to setting {@link RouteDiscoveryPreference#EMPTY empty preferences}.
         */
        @Override
        public RouteCallbackRecord createRouteCallbackRecord(
                Executor executor,
                RouteCallback routeCallback,
                RouteDiscoveryPreference preference) {
            return new RouteCallbackRecord(executor, routeCallback, RouteDiscoveryPreference.EMPTY);
        }

        /**
         * No-op. Only local routers communicate directly with {@link
         * com.android.server.media.MediaRouter2ServiceImpl MediaRouter2ServiceImpl} and modify
         * {@link RouteDiscoveryPreference}. Proxy routers receive callbacks from {@link
         * MediaRouter2Manager}.
         */
        @Override
        public void registerRouteCallback() {
            // Do nothing.
        }

        /** No-op. See {@link ProxyMediaRouter2Impl#registerRouteCallback()}. */
        @Override
        public void unregisterRouteCallback() {
            // Do nothing.
        }

        @Override
        public void setRouteListingPreference(@Nullable RouteListingPreference preference) {
            throw new UnsupportedOperationException(
                    "RouteListingPreference cannot be set by a privileged MediaRouter2 instance.");
        }

        @Override
        public boolean showSystemOutputSwitcher() {
            throw new UnsupportedOperationException(
                    "Cannot show system output switcher from a privileged router.");
        }

        /** Gets the list of all discovered routes. */
        @Override
        public List<MediaRoute2Info> getAllRoutes() {
            synchronized (mLock) {
                return new ArrayList<>(mRoutes.values());
            }
        }

        /** No-op. Controller hints can only be provided by the media app through a local router. */
        @Override
        public void setOnGetControllerHintsListener(OnGetControllerHintsListener listener) {
            // Do nothing.
        }

        /**
         * Transfers the current {@link RoutingSessionInfo routing session} associated with the
         * router's {@link #mClientPackageName client package name} to a specified {@link
         * MediaRoute2Info route}.
         *
         * <p>This method is equivalent to {@link #transfer(RoutingSessionInfo, MediaRoute2Info)},
         * except that the {@link RoutingSessionInfo routing session} is resolved based on the
         * router's {@link #mClientPackageName client package name}.
         *
         * @param route The route to transfer to.
         */
        @Override
        public void transferTo(MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");

            List<RoutingSessionInfo> sessionInfos = getRoutingSessions();
            RoutingSessionInfo targetSession = sessionInfos.get(sessionInfos.size() - 1);
            transfer(targetSession, route, Process.myUserHandle(), mContext.getPackageName());
        }

        @Override
        public void stop() {
            List<RoutingSessionInfo> sessionInfos = getRoutingSessions();
            RoutingSessionInfo sessionToRelease = sessionInfos.get(sessionInfos.size() - 1);
            releaseSession(sessionToRelease);
        }

        /**
         * Transfers a {@link RoutingSessionInfo routing session} to a {@link MediaRoute2Info
         * route}.
         *
         * <p>{@link #onTransferred} is called on success or {@link #onTransferFailed} is called if
         * the request fails.
         *
         * <p>This method will default for in-session transfer if the {@link MediaRoute2Info route}
         * is a {@link RoutingSessionInfo#getTransferableRoutes() transferable route}. Otherwise, it
         * will attempt an out-of-session transfer.
         *
         * @param sessionInfo The {@link RoutingSessionInfo routing session} to transfer.
         * @param route The {@link MediaRoute2Info route} to transfer to.
         * @param transferInitiatorUserHandle The user handle of the app that initiated the
         *     transfer.
         * @param transferInitiatorPackageName The package name if of the app that initiated the
         *     transfer.
         * @see #transferToRoute(RoutingSessionInfo, MediaRoute2Info, UserHandle, String)
         * @see #requestCreateSession(RoutingSessionInfo, MediaRoute2Info)
         */
        @Override
        @SuppressWarnings("AndroidFrameworkRequiresPermission")
        public void transfer(
                @NonNull RoutingSessionInfo sessionInfo,
                @NonNull MediaRoute2Info route,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
            Objects.requireNonNull(route, "route must not be null");
            Objects.requireNonNull(transferInitiatorUserHandle);
            Objects.requireNonNull(transferInitiatorPackageName);

            Log.v(
                    TAG,
                    "Transferring routing session. session= " + sessionInfo + ", route=" + route);

            boolean isUnknownRoute;
            synchronized (mLock) {
                isUnknownRoute = !mRoutes.containsKey(route.getId());
            }

            if (isUnknownRoute) {
                Log.w(TAG, "transfer: Ignoring an unknown route id=" + route.getId());
                this.onTransferFailed(sessionInfo, route);
                return;
            }

            if (sessionInfo.getTransferableRoutes().contains(route.getId())) {
                transferToRoute(
                        sessionInfo,
                        route,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName);
            } else {
                requestCreateSession(sessionInfo, route, transferInitiatorUserHandle,
                        transferInitiatorPackageName);
            }
        }

        /**
         * Requests an in-session transfer of a {@link RoutingSessionInfo routing session} to a
         * {@link MediaRoute2Info route}.
         *
         * <p>The provided {@link MediaRoute2Info route} must be listed in the {@link
         * RoutingSessionInfo routing session's} {@link RoutingSessionInfo#getTransferableRoutes()
         * transferable routes list}. Otherwise, the request will fail.
         *
         * <p>Use {@link #requestCreateSession(RoutingSessionInfo, MediaRoute2Info)} to request an
         * out-of-session transfer.
         *
         * @param session The {@link RoutingSessionInfo routing session} to transfer.
         * @param route The {@link MediaRoute2Info route} to transfer to. Must be one of the {@link
         *     RoutingSessionInfo routing session's} {@link
         *     RoutingSessionInfo#getTransferableRoutes() transferable routes}.
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

        /**
         * Requests an out-of-session transfer of a {@link RoutingSessionInfo routing session} to a
         * {@link MediaRoute2Info route}.
         *
         * <p>This request creates a new {@link RoutingSessionInfo routing session} regardless of
         * whether the {@link MediaRoute2Info route} is one of the {@link RoutingSessionInfo current
         * session's} {@link RoutingSessionInfo#getTransferableRoutes() transferable routes}.
         *
         * <p>Use {@link #transferToRoute(RoutingSessionInfo, MediaRoute2Info)} to request an
         * in-session transfer.
         *
         * @param oldSession The {@link RoutingSessionInfo routing session} to transfer.
         * @param route The {@link MediaRoute2Info route} to transfer to.
         */
        private void requestCreateSession(
                @NonNull RoutingSessionInfo oldSession,
                @NonNull MediaRoute2Info route,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName) {
            if (TextUtils.isEmpty(oldSession.getClientPackageName())) {
                Log.w(TAG, "requestCreateSession: Can't create a session without package name.");
                this.onTransferFailed(oldSession, route);
                return;
            }

            int requestId = createTransferRequest(oldSession, route);

            try {
                mMediaRouterService.requestCreateSessionWithManager(
                        mClient,
                        requestId,
                        oldSession,
                        route,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        @Override
        public List<RoutingController> getControllers() {
            List<RoutingController> result = new ArrayList<>();

            /* Unlike local MediaRouter2 instances, controller instances cannot be kept because
            transfer events initiated from other apps will not come through manager.*/
            List<RoutingSessionInfo> sessions = getRoutingSessions();
            for (RoutingSessionInfo session : sessions) {
                RoutingController controller;
                if (session.isSystemSession()) {
                    mSystemController.setRoutingSessionInfo(session);
                    controller = mSystemController;
                } else {
                    controller = new RoutingController(session);
                }
                result.add(controller);
            }
            return result;
        }

        /**
         * Requests a volume change for a {@link MediaRoute2Info route}.
         *
         * <p>It may have no effect if the {@link MediaRoute2Info route} is not currently selected.
         *
         * @param volume The desired volume value between 0 and {@link
         *     MediaRoute2Info#getVolumeMax()} (inclusive).
         */
        @Override
        public void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
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
         * Requests a volume change for a {@link RoutingSessionInfo routing session}.
         *
         * @param volume The desired volume value between 0 and {@link
         *     RoutingSessionInfo#getVolumeMax()} (inclusive).
         */
        @Override
        public void setSessionVolume(int volume, RoutingSessionInfo sessionInfo) {
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

        /**
         * Returns an exact copy of the routes. Individual {@link RouteDiscoveryPreference
         * preferences} do not apply to proxy routers.
         */
        @Override
        public List<MediaRoute2Info> filterRoutesWithIndividualPreference(
                List<MediaRoute2Info> routes, RouteDiscoveryPreference discoveryPreference) {
            // Individual discovery preferences do not apply for the system router.
            return new ArrayList<>(routes);
        }

        /**
         * Adds a {@linkplain MediaRoute2Info route} to the routing session's {@linkplain
         * RoutingSessionInfo#getSelectedRoutes() selected route list}.
         *
         * <p>Upon success, {@link #onSessionUpdated(RoutingSessionInfo)} is invoked. Failed
         * requests are silently ignored.
         *
         * <p>The {@linkplain RoutingSessionInfo#getSelectedRoutes() selected routes list} of a
         * routing session contains the group of devices playing media for that {@linkplain
         * RoutingSessionInfo session}.
         *
         * <p>The given route must not be already selected and must be listed in the session's
         * {@linkplain RoutingSessionInfo#getSelectableRoutes() selectable routes}. Otherwise, the
         * request will be ignored.
         *
         * <p>This method should not be confused with {@link #transfer(RoutingSessionInfo,
         * MediaRoute2Info)}.
         *
         * @see RoutingSessionInfo#getSelectedRoutes()
         * @see RoutingSessionInfo#getSelectableRoutes()
         */
        @Override
        public void selectRoute(MediaRoute2Info route, RoutingSessionInfo sessionInfo) {
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
         * Removes a route from a session's {@linkplain RoutingSessionInfo#getSelectedRoutes()
         * selected routes list}. Calls {@link #onSessionUpdated(RoutingSessionInfo)} on success.
         *
         * <p>The given route must be selected and must be listed in the session's {@linkplain
         * RoutingSessionInfo#getDeselectableRoutes() deselectable route list}. Otherwise, the
         * request will be ignored.
         *
         * @see RoutingSessionInfo#getSelectedRoutes()
         * @see RoutingSessionInfo#getDeselectableRoutes()
         */
        @Override
        public void deselectRoute(MediaRoute2Info route, RoutingSessionInfo sessionInfo) {
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

        @Override
        public void releaseSession(
                boolean shouldReleaseSession,
                boolean shouldNotifyStop,
                RoutingController controller) {
            releaseSession(controller.getRoutingSessionInfo());
        }

        /**
         * Retrieves the system session info for the given package.
         *
         * <p>The returned routing session is guaranteed to have a non-null {@link
         * RoutingSessionInfo#getClientPackageName() client package name}.
         *
         * <p>Extracted into a static method to allow calling this from the constructor.
         */
        /* package */ static RoutingSessionInfo getSystemSessionInfoImpl(
                @NonNull IMediaRouterService service, @NonNull String clientPackageName) {
            try {
                return service.getSystemSessionInfoForPackage(clientPackageName);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Sets the routing session's {@linkplain RoutingSessionInfo#getClientPackageName() client
         * package name} to {@link #mClientPackageName} if empty and returns the session.
         *
         * <p>This method must only be used for {@linkplain RoutingSessionInfo#isSystemSession()
         * system routing sessions}.
         */
        private RoutingSessionInfo ensureClientPackageNameForSystemSession(
                RoutingSessionInfo sessionInfo) {
            if (!sessionInfo.isSystemSession()
                    || !TextUtils.isEmpty(sessionInfo.getClientPackageName())) {
                return sessionInfo;
            }

            return new RoutingSessionInfo.Builder(sessionInfo)
                    .setClientPackageName(mClientPackageName)
                    .build();
        }

        /**
         * Requests the release of a {@linkplain RoutingSessionInfo routing session}. Calls {@link
         * #onSessionReleasedOnHandler(RoutingSessionInfo)} on success.
         *
         * <p>Once released, a routing session ignores incoming requests.
         */
        private void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

            try {
                int requestId = mNextRequestId.getAndIncrement();
                mMediaRouterService.releaseSessionWithManager(
                        mClient, requestId, sessionInfo.getId());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        private int createTransferRequest(
                @NonNull RoutingSessionInfo session, @NonNull MediaRoute2Info route) {
            int requestId = mNextRequestId.getAndIncrement();
            MediaRouter2Manager.TransferRequest transferRequest =
                    new MediaRouter2Manager.TransferRequest(requestId, session, route);
            mTransferRequests.add(transferRequest);

            Message timeoutMessage =
                    obtainMessage(
                            ProxyMediaRouter2Impl::handleTransferTimeout, this, transferRequest);
            mHandler.sendMessageDelayed(timeoutMessage, TRANSFER_TIMEOUT_MS);
            return requestId;
        }

        private void handleTransferTimeout(MediaRouter2Manager.TransferRequest request) {
            boolean removed = mTransferRequests.remove(request);
            if (removed) {
                this.onTransferFailed(request.mOldSessionInfo, request.mTargetRoute);
            }
        }

        /**
         * Returns the {@linkplain RoutingSessionInfo routing sessions} associated with {@link
         * #mClientPackageName}. The first element of the returned list is the {@linkplain
         * #getSystemSessionInfo() system routing session}.
         *
         * @see #getSystemSessionInfo()
         */
        @NonNull
        private List<RoutingSessionInfo> getRoutingSessions() {
            List<RoutingSessionInfo> sessions = new ArrayList<>();
            sessions.add(getSystemSessionInfo());

            List<RoutingSessionInfo> remoteSessions;
            try {
                remoteSessions = mMediaRouterService.getRemoteSessions(mClient);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }

            for (RoutingSessionInfo sessionInfo : remoteSessions) {
                if (TextUtils.equals(sessionInfo.getClientPackageName(), mClientPackageName)) {
                    sessions.add(sessionInfo);
                }
            }
            return sessions;
        }

        private void onTransferred(
                @NonNull RoutingSessionInfo oldSession, @NonNull RoutingSessionInfo newSession) {
            if (!oldSession.isSystemSession()
                    && !TextUtils.equals(
                            getClientPackageName(), oldSession.getClientPackageName())) {
                return;
            }

            if (!newSession.isSystemSession()
                    && !TextUtils.equals(
                            getClientPackageName(), newSession.getClientPackageName())) {
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

        private void onTransferFailed(
                @NonNull RoutingSessionInfo session, @NonNull MediaRoute2Info route) {
            if (!session.isSystemSession()
                    && !TextUtils.equals(getClientPackageName(), session.getClientPackageName())) {
                return;
            }
            notifyTransferFailure(route);
        }

        private void onSessionUpdated(@NonNull RoutingSessionInfo session) {
            if (!session.isSystemSession()
                    && !TextUtils.equals(getClientPackageName(), session.getClientPackageName())) {
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

        private void onSessionCreatedOnHandler(
                int requestId, @NonNull RoutingSessionInfo sessionInfo) {
            MediaRouter2Manager.TransferRequest matchingRequest = null;
            for (MediaRouter2Manager.TransferRequest request : mTransferRequests) {
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

            if (!sessionInfo.getSelectedRoutes().contains(requestedRoute.getId())) {
                Log.w(
                        TAG,
                        "The session does not contain the requested route. "
                                + "(requestedRouteId="
                                + requestedRoute.getId()
                                + ", actualRoutes="
                                + sessionInfo.getSelectedRoutes()
                                + ")");
                this.onTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            } else if (!TextUtils.equals(
                    requestedRoute.getProviderId(), sessionInfo.getProviderId())) {
                Log.w(
                        TAG,
                        "The session's provider ID does not match the requested route's. "
                                + "(requested route's providerId="
                                + requestedRoute.getProviderId()
                                + ", actual providerId="
                                + sessionInfo.getProviderId()
                                + ")");
                this.onTransferFailed(matchingRequest.mOldSessionInfo, requestedRoute);
            } else {
                this.onTransferred(matchingRequest.mOldSessionInfo, sessionInfo);
            }
        }

        private void onSessionUpdatedOnHandler(@NonNull RoutingSessionInfo sessionInfo) {
            for (MediaRouter2Manager.TransferRequest request : mTransferRequests) {
                String sessionId = request.mOldSessionInfo.getId();
                if (!TextUtils.equals(sessionId, sessionInfo.getId())) {
                    continue;
                }
                if (sessionInfo.getSelectedRoutes().contains(request.mTargetRoute.getId())) {
                    mTransferRequests.remove(request);
                    this.onTransferred(request.mOldSessionInfo, sessionInfo);
                    break;
                }
            }
            this.onSessionUpdated(sessionInfo);
        }

        private void onSessionReleasedOnHandler(@NonNull RoutingSessionInfo session) {
            if (session.isSystemSession()) {
                Log.e(TAG, "onSessionReleasedOnHandler: Called on system session. Ignoring.");
                return;
            }

            if (!TextUtils.equals(getClientPackageName(), session.getClientPackageName())) {
                return;
            }

            notifyStop(new RoutingController(session, RoutingController.CONTROLLER_STATE_RELEASED));
        }

        private void onDiscoveryPreferenceChangedOnHandler(
                @NonNull String packageName, @Nullable RouteDiscoveryPreference preference) {
            if (!TextUtils.equals(getClientPackageName(), packageName)) {
                return;
            }

            if (preference == null) {
                return;
            }
            synchronized (mLock) {
                if (Objects.equals(preference, mDiscoveryPreference)) {
                    return;
                }
                mDiscoveryPreference = preference;
                updateFilteredRoutesLocked();
            }
            notifyPreferredFeaturesChanged(preference.getPreferredFeatures());
        }

        private void onRouteListingPreferenceChangedOnHandler(
                @NonNull String packageName,
                @Nullable RouteListingPreference routeListingPreference) {
            if (!TextUtils.equals(getClientPackageName(), packageName)) {
                return;
            }

            synchronized (mLock) {
                if (Objects.equals(mRouteListingPreference, routeListingPreference)) {
                    return;
                }

                mRouteListingPreference = routeListingPreference;
            }

            notifyRouteListingPreferenceUpdated(routeListingPreference);
        }

        private void onRequestFailedOnHandler(int requestId, int reason) {
            MediaRouter2Manager.TransferRequest matchingRequest = null;
            for (MediaRouter2Manager.TransferRequest request : mTransferRequests) {
                if (request.mRequestId == requestId) {
                    matchingRequest = request;
                    break;
                }
            }

            if (matchingRequest != null) {
                mTransferRequests.remove(matchingRequest);
                onTransferFailed(matchingRequest.mOldSessionInfo, matchingRequest.mTargetRoute);
            } else {
                notifyRequestFailed(reason);
            }
        }

        private class Client extends IMediaRouter2Manager.Stub {

            @Override
            public void notifySessionCreated(int requestId, RoutingSessionInfo routingSessionInfo) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onSessionCreatedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                requestId,
                                routingSessionInfo));
            }

            @Override
            public void notifySessionUpdated(RoutingSessionInfo routingSessionInfo) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onSessionUpdatedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                routingSessionInfo));
            }

            @Override
            public void notifySessionReleased(RoutingSessionInfo routingSessionInfo) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onSessionReleasedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                routingSessionInfo));
            }

            @Override
            public void notifyDiscoveryPreferenceChanged(
                    String packageName, RouteDiscoveryPreference routeDiscoveryPreference) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onDiscoveryPreferenceChangedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                packageName,
                                routeDiscoveryPreference));
            }

            @Override
            public void notifyRouteListingPreferenceChange(
                    String packageName, RouteListingPreference routeListingPreference) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onRouteListingPreferenceChangedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                packageName,
                                routeListingPreference));
            }

            @Override
            public void notifyRoutesUpdated(List<MediaRoute2Info> routes) {
                mHandler.sendMessage(
                        obtainMessage(
                                MediaRouter2::updateRoutesOnHandler, MediaRouter2.this, routes));
            }

            @Override
            public void notifyRequestFailed(int requestId, int reason) {
                mHandler.sendMessage(
                        obtainMessage(
                                ProxyMediaRouter2Impl::onRequestFailedOnHandler,
                                ProxyMediaRouter2Impl.this,
                                requestId,
                                reason));
            }
        }
    }

    /**
     * Implements logic specific to local {@link MediaRouter2} instances.
     *
     * <p>Local routers allow an app to control its own routing without any special permissions.
     * Apps can obtain an instance by calling {@link #getInstance(Context)}.
     */
    private class LocalMediaRouter2Impl implements MediaRouter2Impl {
        private final String mPackageName;

        LocalMediaRouter2Impl(@NonNull String packageName) {
            mPackageName = packageName;
        }

        /**
         * No-op. Local routers cannot explicitly control route scanning.
         *
         * <p>Local routers can control scanning indirectly through {@link
         * #registerRouteCallback(Executor, RouteCallback, RouteDiscoveryPreference)}.
         */
        @Override
        public void startScan() {
            // Do nothing.
        }

        /**
         * No-op. Local routers cannot explicitly control route scanning.
         *
         * <p>Local routers can control scanning indirectly through {@link
         * #registerRouteCallback(Executor, RouteCallback, RouteDiscoveryPreference)}.
         */
        @Override
        public void stopScan() {
            // Do nothing.
        }

        /**
         * Returns {@code null}. The client package name is only associated to proxy {@link
         * MediaRouter2} instances.
         */
        @Override
        public String getClientPackageName() {
            return null;
        }

        @Override
        public String getPackageName() {
            return mPackageName;
        }

        @Override
        public RoutingSessionInfo getSystemSessionInfo() {
            RoutingSessionInfo currentSystemSessionInfo = null;
            try {
                currentSystemSessionInfo = mMediaRouterService.getSystemSessionInfo();
            } catch (RemoteException ex) {
                ex.rethrowFromSystemServer();
            }
            return currentSystemSessionInfo;
        }

        @Override
        public RouteCallbackRecord createRouteCallbackRecord(
                Executor executor,
                RouteCallback routeCallback,
                RouteDiscoveryPreference preference) {
            return new RouteCallbackRecord(executor, routeCallback, preference);
        }

        @Override
        public void registerRouteCallback() {
            synchronized (mLock) {
                try {
                    if (mStub == null) {
                        MediaRouter2Stub stub = new MediaRouter2Stub();
                        mMediaRouterService.registerRouter2(stub, mPackageName);
                        mStub = stub;
                    }

                    if (updateDiscoveryPreferenceIfNeededLocked()) {
                        mMediaRouterService.setDiscoveryRequestWithRouter2(
                                mStub, mDiscoveryPreference);
                    }
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public void unregisterRouteCallback() {
            synchronized (mLock) {
                if (mStub == null) {
                    return;
                }

                try {
                    if (updateDiscoveryPreferenceIfNeededLocked()) {
                        mMediaRouterService.setDiscoveryRequestWithRouter2(
                                mStub, mDiscoveryPreference);
                    }

                    if (mRouteCallbackRecords.isEmpty() && mNonSystemRoutingControllers.isEmpty()) {
                        mMediaRouterService.unregisterRouter2(mStub);
                        mStub = null;
                    }
                } catch (RemoteException ex) {
                    Log.e(TAG, "unregisterRouteCallback: Unable to set discovery request.", ex);
                }
            }
        }

        @Override
        public void setRouteListingPreference(@Nullable RouteListingPreference preference) {
            synchronized (mLock) {
                if (Objects.equals(mRouteListingPreference, preference)) {
                    // Nothing changed. We return early to save a call to the system server.
                    return;
                }
                mRouteListingPreference = preference;
                try {
                    if (mStub == null) {
                        MediaRouter2Stub stub = new MediaRouter2Stub();
                        mMediaRouterService.registerRouter2(stub, mImpl.getPackageName());
                        mStub = stub;
                    }
                    mMediaRouterService.setRouteListingPreference(mStub, mRouteListingPreference);
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
                notifyRouteListingPreferenceUpdated(preference);
            }
        }

        @Override
        public boolean showSystemOutputSwitcher() {
            synchronized (mLock) {
                try {
                    return mMediaRouterService.showMediaOutputSwitcher(mImpl.getPackageName());
                } catch (RemoteException ex) {
                    ex.rethrowFromSystemServer();
                }
            }
            return false;
        }

        /**
         * Returns {@link Collections#emptyList()}. Local routes can only access routes related to
         * their {@link RouteDiscoveryPreference} through {@link #getRoutes()}.
         */
        @Override
        public List<MediaRoute2Info> getAllRoutes() {
            return Collections.emptyList();
        }

        @Override
        public void setOnGetControllerHintsListener(OnGetControllerHintsListener listener) {
            mOnGetControllerHintsListener = listener;
        }

        @Override
        public void transferTo(MediaRoute2Info route) {
            Log.v(TAG, "Transferring to route: " + route);

            boolean routeFound;
            synchronized (mLock) {
                // TODO: Check thread-safety
                routeFound = mRoutes.containsKey(route.getId());
            }
            if (!routeFound) {
                notifyTransferFailure(route);
                return;
            }

            RoutingController controller = getCurrentController();
            if (controller
                    .getRoutingSessionInfo()
                    .getTransferableRoutes()
                    .contains(route.getId())) {
                controller.transferToRoute(route);
                return;
            }

            requestCreateController(
                    controller,
                    route,
                    MANAGER_REQUEST_ID_NONE,
                    Process.myUserHandle(),
                    mContext.getPackageName());
        }

        @Override
        public void stop() {
            getCurrentController().release();
        }

        /**
         * No-op. Local routers cannot request transfers of specific {@link RoutingSessionInfo}.
         * This operation is only available to proxy routers.
         *
         * <p>Local routers can only transfer the current {@link RoutingSessionInfo} using {@link
         * #transferTo(MediaRoute2Info)}.
         */
        @Override
        public void transfer(
                @NonNull RoutingSessionInfo sessionInfo,
                @NonNull MediaRoute2Info route,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName) {
            // Do nothing.
        }

        @Override
        public List<RoutingController> getControllers() {
            List<RoutingController> result = new ArrayList<>();

            result.add(0, mSystemController);
            synchronized (mLock) {
                result.addAll(mNonSystemRoutingControllers.values());
            }
            return result;
        }

        /** No-op. Local routers cannot modify the volume of specific routes. */
        @Override
        public void setRouteVolume(MediaRoute2Info route, int volume) {
            // Do nothing.
            // If this API needs to be public, use IMediaRouterService#setRouteVolumeWithRouter2()
        }

        @Override
        public void setSessionVolume(int volume, RoutingSessionInfo sessionInfo) {
            MediaRouter2Stub stub;
            synchronized (mLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.setSessionVolumeWithRouter2(
                            stub, sessionInfo.getId(), volume);
                } catch (RemoteException ex) {
                    Log.e(TAG, "setVolume: Failed to deliver request.", ex);
                }
            }
        }

        @Override
        public List<MediaRoute2Info> filterRoutesWithIndividualPreference(
                List<MediaRoute2Info> routes, RouteDiscoveryPreference discoveryPreference) {
            List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
            for (MediaRoute2Info route : routes) {
                if (!route.hasAnyFeatures(discoveryPreference.getPreferredFeatures())) {
                    continue;
                }
                if (!discoveryPreference.getAllowedPackages().isEmpty()
                        && (route.getPackageName() == null
                                || !discoveryPreference
                                        .getAllowedPackages()
                                        .contains(route.getPackageName()))) {
                    continue;
                }
                filteredRoutes.add(route);
            }
            return filteredRoutes;
        }

        @Override
        public void selectRoute(MediaRoute2Info route, RoutingSessionInfo sessionInfo) {
            MediaRouter2Stub stub;
            synchronized (mLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.selectRouteWithRouter2(stub, sessionInfo.getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to select route for session.", ex);
                }
            }
        }

        @Override
        public void deselectRoute(MediaRoute2Info route, RoutingSessionInfo sessionInfo) {
            MediaRouter2Stub stub;
            synchronized (mLock) {
                stub = mStub;
            }
            if (stub != null) {
                try {
                    mMediaRouterService.deselectRouteWithRouter2(stub, sessionInfo.getId(), route);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to deselect route from session.", ex);
                }
            }
        }

        @Override
        public void releaseSession(
                boolean shouldReleaseSession,
                boolean shouldNotifyStop,
                RoutingController controller) {
            synchronized (mLock) {
                mNonSystemRoutingControllers.remove(controller.getId(), controller);

                if (shouldReleaseSession && mStub != null) {
                    try {
                        mMediaRouterService.releaseSessionWithRouter2(mStub, controller.getId());
                    } catch (RemoteException ex) {
                        ex.rethrowFromSystemServer();
                    }
                }

                if (shouldNotifyStop) {
                    mHandler.sendMessage(
                            obtainMessage(MediaRouter2::notifyStop, MediaRouter2.this, controller));
                }

                if (mRouteCallbackRecords.isEmpty()
                        && mNonSystemRoutingControllers.isEmpty()
                        && mStub != null) {
                    try {
                        mMediaRouterService.unregisterRouter2(mStub);
                    } catch (RemoteException ex) {
                        ex.rethrowFromSystemServer();
                    }
                    mStub = null;
                }
            }
        }

    }
}
