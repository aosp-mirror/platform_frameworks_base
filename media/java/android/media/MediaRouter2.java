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

    private final CopyOnWriteArrayList<CallbackRecord> mCallbackRecords =
            new CopyOnWriteArrayList<>();

    private final String mPackageName;
    @GuardedBy("sLock")
    private final Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    @GuardedBy("sLock")
    private List<String> mControlCategories = Collections.emptyList();

    private MediaRoute2Info mSelectedRoute;
    @GuardedBy("sLock")
    private MediaRoute2Info mSelectingRoute;
    @GuardedBy("sLock")
    private Client2 mClient;

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
        // The first route is the currently selected system route.
        // For example, if there are two system routes (BT and device speaker),
        // BT will be the first route in the list.
        mSelectedRoute = currentSystemRoutes.get(0);
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        registerCallback(executor, callback, 0);
    }

    /**
     * Registers a callback to discover routes and to receive events when they change.
     * <p>
     * If you register the same callback twice or more, it will be ignored.
     * </p>
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback, int flags) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        CallbackRecord record = new CallbackRecord(callback, executor, flags);
        if (!mCallbackRecords.addIfAbsent(record)) {
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
     * @param callback the callback to unregister
     * @see #registerCallback
     */
    public void unregisterCallback(@NonNull Callback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mCallbackRecords.remove(new CallbackRecord(callback, null, 0))) {
            Log.w(TAG, "Ignoring unknown callback");
            return;
        }

        synchronized (sLock) {
            if (mCallbackRecords.size() == 0 && mClient != null) {
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
                    if (route.supportsControlCategory(mControlCategories)) {
                        filteredRoutes.add(route);
                    }
                }
                mFilteredRoutes = Collections.unmodifiableList(filteredRoutes);
            }
        }
        return mFilteredRoutes;
    }

    /**
     * Gets the currently selected route.
     *
     * @return the selected route
     */
    @NonNull
    public MediaRoute2Info getSelectedRoute() {
        return mSelectedRoute;
    }

    /**
     * Request to select the specified route. When the route is selected,
     * {@link Callback#onRouteSelected(MediaRoute2Info, int, Bundle)} will be called.
     *
     * @param route the route to select
     */
    public void requestSelectRoute(@NonNull MediaRoute2Info route) {
        Objects.requireNonNull(route, "route must not be null");

        Client2 client;
        synchronized (sLock) {
            if (mSelectingRoute == route) {
                Log.w(TAG, "The route selection request is already sent.");
                return;
            }
            mSelectingRoute = route;
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.requestSelectRoute2(client, route);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to request to select route.", ex);
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
            boolean preSupported = route.supportsControlCategory(prevControlCategories);
            boolean postSupported = route.supportsControlCategory(newControlCategories);
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
                if (route.supportsControlCategory(mControlCategories)) {
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
                if (route.supportsControlCategory(mControlCategories)) {
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
                if (route.supportsControlCategory(mControlCategories)) {
                    changedRoutes.add(route);
                }
            }
        }
        if (changedRoutes.size() > 0) {
            notifyRoutesChanged(changedRoutes);
        }
    }

    void selectRouteOnHandler(MediaRoute2Info route, int reason, Bundle controlHints) {
        synchronized (sLock) {
            if (reason == SELECT_REASON_USER_SELECTED) {
                if (mSelectingRoute == null
                        || !TextUtils.equals(mSelectingRoute.getUniqueId(), route.getUniqueId())) {
                    Log.w(TAG, "Ignoring invalid or outdated notifyRouteSelected call. "
                            + "selectingRoute=" + mSelectingRoute + " route=" + route);
                    return;
                }
            }
            mSelectingRoute = null;
        }
        if (reason == SELECT_REASON_SYSTEM_SELECTED) {
            reason = SELECT_REASON_USER_SELECTED;
        }
        mSelectedRoute = route;
        notifyRouteSelected(route, reason, controlHints);
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

    private void notifyRouteSelected(MediaRoute2Info route, int reason, Bundle controlHints) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRouteSelected(route, reason, controlHints));
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public static class Callback {
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

        /**
         * Called when a route is selected. Exactly one route can be selected at a time.
         *
         * @param route the selected route.
         * @param reason the reason why the route is selected.
         * @param controlHints An optional bundle of provider-specific arguments which may be
         *                     used to control the selected route. Can be empty.
         * @see #SELECT_REASON_UNKNOWN
         * @see #SELECT_REASON_USER_SELECTED
         * @see #SELECT_REASON_FALLBACK
         * @see #getSelectedRoute()
         */
        public void onRouteSelected(@NonNull MediaRoute2Info route, @SelectReason int reason,
                @NonNull Bundle controlHints) {}
    }

    final class CallbackRecord {
        public final Callback mCallback;
        public Executor mExecutor;
        public int mFlags;

        CallbackRecord(@NonNull Callback callback, @Nullable Executor executor, int flags) {
            mCallback = callback;
            mExecutor = executor;
            mFlags = flags;
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
        public void notifyRouteSelected(MediaRoute2Info route, int reason,
                Bundle controlHints) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::selectRouteOnHandler,
                    MediaRouter2.this, route, reason, controlHints));
        }
    }
}
