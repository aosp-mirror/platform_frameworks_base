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
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;


/**
 * A new Media Router
 * @hide
 */
public class MediaRouter2 {
    private static final String TAG = "MediaRouter";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2 sInstance;

    private Context mContext;
    private final IMediaRouterService mMediaRouterService;

    private CopyOnWriteArrayList<CallbackRecord> mCallbackRecords = new CopyOnWriteArrayList<>();
    @GuardedBy("sLock")
    private List<String> mControlCategories = Collections.emptyList();
    @GuardedBy("sLock")
    private Client mClient;

    private final String mPackageName;
    final Handler mHandler;

    List<MediaRoute2ProviderInfo> mProviders = Collections.emptyList();
    volatile List<MediaRoute2Info> mRoutes = Collections.emptyList();

    MediaRoute2Info mSelectedRoute;

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
     * If you register the same callback twice or more, the previous arguments will be overwritten
     * with the new arguments.
     * </p>
     */
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback, int flags) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        CallbackRecord record;
        // This is required to prevent adding the same callback twice.
        synchronized (mCallbackRecords) {
            final int index = findCallbackRecordIndexLocked(callback);
            if (index < 0) {
                record = new CallbackRecord(callback);
                mCallbackRecords.add(record);
            } else {
                record = mCallbackRecords.get(index);
            }
            record.mExecutor = executor;
            record.mFlags = flags;
        }

        synchronized (sLock) {
            if (mClient == null) {
                Client client = new Client();
                try {
                    mMediaRouterService.registerClient2(client, mPackageName);
                    mMediaRouterService.setControlCategories2(client, mControlCategories);
                    mClient = client;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to register media router.", ex);
                }
            }
        }
        record.notifyRoutes();

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

        synchronized (mCallbackRecords) {
            final int index = findCallbackRecordIndexLocked(callback);
            if (index < 0) {
                Log.w(TAG, "Ignoring to remove unknown callback. " + callback);
                return;
            }
            mCallbackRecords.remove(index);
            synchronized (sLock) {
                if (mCallbackRecords.size() == 0 && mClient != null) {
                    try {
                        mMediaRouterService.unregisterClient2(mClient);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Unable to unregister media router.", ex);
                    }
                    mClient = null;
                }
            }
        }
    }

    //TODO(b/139033746): Rename "Control Category" when it's finalized.
    /**
     * Sets the control categories of the application.
     * Routes that support at least one of the given control categories only exists and are handled
     * by the media router.
     */
    public void setControlCategories(@NonNull Collection<String> controlCategories) {
        Objects.requireNonNull(controlCategories, "control categories must not be null");

        Client client;
        List<String> newControlCategories = new ArrayList<>(controlCategories);
        synchronized (sLock) {
            mControlCategories = newControlCategories;
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.setControlCategories2(client, newControlCategories);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to set control categories.", ex);
            }
        }
        mHandler.sendMessage(obtainMessage(MediaRouter2::refreshAndNotifyRoutes, this));
    }

    /**
     * Gets the list of {@link MediaRoute2Info routes} currently known to the media router.
     *
     * @return the list of routes that support at least one of the control categories set by
     * the application
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        return mRoutes;
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
     * Selects the specified route.
     *
     * @param route the route to select
     */
    //TODO: add a parameter for category (e.g. mirroring/casting)
    public void selectRoute(@NonNull MediaRoute2Info route) {
        Objects.requireNonNull(route, "route must not be null");

        Client client;
        synchronized (sLock) {
            mSelectedRoute = route;
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.selectRoute2(client, route);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select route.", ex);
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

        Client client;
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

        Client client;
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

        Client client;
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

    @GuardedBy("mCallbackRecords")
    private int findCallbackRecordIndexLocked(Callback callback) {
        final int count = mCallbackRecords.size();
        for (int i = 0; i < count; i++) {
            CallbackRecord callbackRecord = mCallbackRecords.get(i);
            if (callbackRecord.mCallback == callback) {
                return i;
            }
        }
        return -1;
    }

    void onProviderInfosUpdated(List<MediaRoute2ProviderInfo> providers) {
        if (providers == null) {
            Log.w(TAG, "Providers info is null.");
            return;
        }

        mProviders = providers;
        refreshAndNotifyRoutes();
    }

    void refreshAndNotifyRoutes() {
        ArrayList<MediaRoute2Info> routes = new ArrayList<>();

        List<String> controlCategories;
        synchronized (sLock) {
            controlCategories = mControlCategories;
        }

        for (MediaRoute2ProviderInfo provider : mProviders) {
            updateProvider(provider, controlCategories, routes);
        }

        //TODO: Can orders be changed?
        if (!Objects.equals(mRoutes, routes)) {
            mRoutes = Collections.unmodifiableList(routes);
            notifyRouteListChanged(mRoutes);
        }
    }

    void updateProvider(MediaRoute2ProviderInfo provider, List<String> controlCategories,
            List<MediaRoute2Info> outRoutes) {
        if (provider == null || !provider.isValid()) {
            Log.w(TAG, "Ignoring invalid provider : " + provider);
            return;
        }

        final Collection<MediaRoute2Info> routes = provider.getRoutes();
        for (MediaRoute2Info route : routes) {
            if (!route.isValid()) {
                Log.w(TAG, "Ignoring invalid route : " + route);
                continue;
            }
            if (!route.supportsControlCategory(controlCategories)) {
                continue;
            }
            MediaRoute2Info preRoute = findRouteById(route.getId());
            if (!route.equals(preRoute)) {
                notifyRouteChanged(route);
            }
            outRoutes.add(route);
        }
    }

    MediaRoute2Info findRouteById(String id) {
        for (MediaRoute2Info route : mRoutes) {
            if (route.getId().equals(id)) return route;
        }
        return null;
    }

    void notifyRouteListChanged(List<MediaRoute2Info> routes) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesChanged(routes));
        }
    }

    void notifyRouteChanged(MediaRoute2Info route) {
        for (CallbackRecord record: mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRouteChanged(route));
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public static class Callback {
        //TODO: clean up these callbacks
        /**
         * Called when a route is added.
         */
        public void onRouteAdded(MediaRoute2Info routeInfo) {}

        /**
         * Called when a route is changed.
         */
        public void onRouteChanged(MediaRoute2Info routeInfo) {}

        /**
         * Called when a route is removed.
         */
        public void onRouteRemoved(MediaRoute2Info routeInfo) {}

        /**
         * Called when the list of routes is changed.
         */
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}
    }

    final class CallbackRecord {
        public final Callback mCallback;
        public Executor mExecutor;
        public int mFlags;

        CallbackRecord(@NonNull Callback callback) {
            mCallback = callback;
        }

        void notifyRoutes() {
            final List<MediaRoute2Info> routes = mRoutes;
            // notify only when bound to media router service.
            //TODO: Correct the condition when control category, default rotue, .. are finalized.
            if (routes.size() > 0) {
                mExecutor.execute(() -> mCallback.onRoutesChanged(routes));
            }
        }
    }

    class Client extends IMediaRouter2Client.Stub {
        @Override
        public void notifyRestoreRoute() throws RemoteException {}

        @Override
        public void notifyProviderInfosUpdated(List<MediaRoute2ProviderInfo> info) {
            mHandler.sendMessage(obtainMessage(MediaRouter2::onProviderInfosUpdated,
                    MediaRouter2.this, info));
        }
    }
}
