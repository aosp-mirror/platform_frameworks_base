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

/**
 * @hide
 */
public class MediaRouter2Manager {
    private static final String TAG = "MR2Manager";
    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2Manager sInstance;

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
    final ConcurrentMap<String, List<String>> mControlCategoryMap = new ConcurrentHashMap<>();

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
            Log.w(TAG, "Ignore removing unknown callback. " + callback);
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
                mControlCategoryMap.clear();
            }
        }
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

        List<String> controlCategories = mControlCategoryMap.get(packageName);
        if (controlCategories == null) {
            return Collections.emptyList();
        }
        List<MediaRoute2Info> routes = new ArrayList<>();
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : mRoutes.values()) {
                if (route.supportsControlCategory(controlCategories)) {
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    /**
     * Gets the list of routes that are actively used by {@link MediaRouter2}.
     */
    @NonNull
    public List<MediaRoute2Info> getActiveRoutes() {
        List<MediaRoute2Info> routes = new ArrayList<>();
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : mRoutes.values()) {
                if (!TextUtils.isEmpty(route.getClientPackageName())) {
                    routes.add(route);
                }
            }
        }
        return routes;
    }

    /**
     * Gets the list of discovered routes
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
     * @param packageName the package name of the application that should change it's media route
     * @param route the route to be selected
     */
    public void selectRoute(@NonNull String packageName, @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(route, "route must not be null");

        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.selectClientRoute2(client, packageName, route);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select media route", ex);
            }
        }
    }

    /**
     * Unselects media route for the specified package name.
     *
     * @param packageName the package name of the application that should stop routing
     */
    public void unselectRoute(@NonNull String packageName) {
        Client client;
        synchronized (sLock) {
            client = mClient;
        }
        if (client != null) {
            try {
                mMediaRouterService.selectClientRoute2(client, packageName, null);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select media route", ex);
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
                mMediaRouterService.requestSetVolume2Manager(client, route, volume);
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
                mMediaRouterService.requestUpdateVolume2Manager(client, route, delta);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    void addRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getUniqueId(), route);
            }
        }
        if (routes.size() > 0) {
            notifyRoutesAdded(routes);
        }
    }

    void removeRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.remove(route.getUniqueId());
            }
        }
        if (routes.size() > 0) {
            notifyRoutesRemoved(routes);
        }
    }

    void changeRoutesOnHandler(List<MediaRoute2Info> routes) {
        synchronized (mRoutesLock) {
            for (MediaRoute2Info route : routes) {
                mRoutes.put(route.getUniqueId(), route);
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

    void notifyRouteSelected(String packageName, MediaRoute2Info route) {
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(() -> record.mCallback.onRouteSelected(packageName, route));
        }
    }

    void updateControlCategories(String packageName, List<String> categories) {
        List<String> prevCategories = mControlCategoryMap.put(packageName, categories);
        if ((prevCategories == null && categories.size() == 0)
                || Objects.equals(categories, prevCategories)) {
            return;
        }
        for (CallbackRecord record : mCallbackRecords) {
            record.mExecutor.execute(
                    () -> record.mCallback.onControlCategoriesChanged(packageName, categories));
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
         * Called when a route is selected for an application.
         *
         * @param packageName the package name of the application
         * @param route the selected route of the application.
         *              It is null if the application has no selected route.
         */
        public void onRouteSelected(@NonNull String packageName, @Nullable MediaRoute2Info route) {}


        /**
         * Called when the control categories of an app is changed.
         *
         * @param packageName the package name of the application
         * @param controlCategories the list of control categories set by an application.
         */
        public void onControlCategoriesChanged(@NonNull String packageName,
                @NonNull List<String> controlCategories) {}
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
        public void notifyRouteSelected(String packageName, MediaRoute2Info route) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifyRouteSelected,
                    MediaRouter2Manager.this, packageName, route));
        }

        @Override
        public void notifyControlCategoriesChanged(String packageName, List<String> categories) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::updateControlCategories,
                    MediaRouter2Manager.this, packageName, categories));
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
