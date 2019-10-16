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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    final List<CallbackRecord> mCallbacks = new CopyOnWriteArrayList<>();

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    @NonNull
    List<MediaRoute2ProviderInfo> mProviders = Collections.emptyList();
    @NonNull
    List<MediaRoute2Info> mRoutes = Collections.emptyList();
    @NonNull
    ConcurrentMap<String, List<String>> mControlCategoryMap = new ConcurrentHashMap<>();

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

        CallbackRecord callbackRecord;
        synchronized (mCallbacks) {
            if (findCallbackRecordIndexLocked(callback) >= 0) {
                Log.w(TAG, "Ignoring to add the same callback twice.");
                return;
            }
            callbackRecord = new CallbackRecord(executor, callback);
            mCallbacks.add(callbackRecord);
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
            } else {
                callbackRecord.notifyRoutes();
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

        synchronized (mCallbacks) {
            final int index = findCallbackRecordIndexLocked(callback);
            if (index < 0) {
                Log.w(TAG, "Ignore removing unknown callback. " + callback);
                return;
            }
            mCallbacks.remove(index);
            synchronized (sLock) {
                if (mCallbacks.size() == 0 && mClient != null) {
                    try {
                        mMediaRouterService.unregisterManager(mClient);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Unable to unregister media router manager", ex);
                    }
                    mClient.notifyProviderInfosUpdated(Collections.emptyList());
                    mClient = null;
                }
            }
        }
    }

    @GuardedBy("mCallbacks")
    private int findCallbackRecordIndexLocked(Callback callback) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            if (mCallbacks.get(i).mCallback == callback) {
                return i;
            }
        }
        return -1;
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
        for (MediaRoute2Info route : mRoutes) {
            if (route.supportsControlCategory(controlCategories)) {
                routes.add(route);
            }
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

    int findProviderIndex(MediaRoute2ProviderInfo provider) {
        final int count = mProviders.size();
        for (int i = 0; i < count; i++) {
            if (TextUtils.equals(mProviders.get(i).getUniqueId(), provider.getUniqueId())) {
                return i;
            }
        }
        return -1;
    }

    void updateProvider(@NonNull MediaRoute2ProviderInfo provider) {
        if (provider == null || !provider.isValid()) {
            Log.w(TAG, "Ignoring invalid provider : " + provider);
            return;
        }

        final Collection<MediaRoute2Info> routes = provider.getRoutes();

        final int index = findProviderIndex(provider);
        if (index >= 0) {
            final MediaRoute2ProviderInfo prevProvider = mProviders.get(index);
            final Set<String> updatedRouteIds = new HashSet<>();
            for (MediaRoute2Info routeInfo : routes) {
                if (!routeInfo.isValid()) {
                    Log.w(TAG, "Ignoring invalid route : " + routeInfo);
                    continue;
                }
                final MediaRoute2Info prevRoute = prevProvider.getRoute(routeInfo.getId());
                if (prevRoute == null) {
                    notifyRouteAdded(routeInfo);
                } else {
                    if (!Objects.equals(prevRoute, routeInfo)) {
                        notifyRouteChanged(routeInfo);
                    }
                    updatedRouteIds.add(routeInfo.getId());
                }
            }
            final Collection<MediaRoute2Info> prevRoutes = prevProvider.getRoutes();

            for (MediaRoute2Info prevRoute : prevRoutes) {
                if (!updatedRouteIds.contains(prevRoute.getId())) {
                    notifyRouteRemoved(prevRoute);
                }
            }
        } else {
            for (MediaRoute2Info routeInfo: routes) {
                notifyRouteAdded(routeInfo);
            }
        }
    }

    void notifyRouteAdded(MediaRoute2Info routeInfo) {
        for (CallbackRecord record : mCallbacks) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRouteAdded(routeInfo));
        }
    }

    void notifyRouteChanged(MediaRoute2Info routeInfo) {
        for (CallbackRecord record : mCallbacks) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRouteChanged(routeInfo));
        }
    }

    void notifyRouteRemoved(MediaRoute2Info routeInfo) {
        for (CallbackRecord record : mCallbacks) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRouteRemoved(routeInfo));
        }
    }

    void notifyRouteListChanged() {
        for (CallbackRecord record: mCallbacks) {
            record.mExecutor.execute(
                    () -> record.mCallback.onRoutesChanged(mRoutes));
        }
    }

    void notifyProviderInfosUpdated(List<MediaRoute2ProviderInfo> providers) {
        if (providers == null) {
            Log.w(TAG, "Providers info is null.");
            return;
        }

        ArrayList<MediaRoute2Info> routes = new ArrayList<>();

        for (MediaRoute2ProviderInfo provider : providers) {
            updateProvider(provider);
            //TODO: Should we do this in updateProvider()?
            routes.addAll(provider.getRoutes());
        }
        //TODO: Call notifyRouteRemoved for the routes of the removed providers.

        //TODO: Filter invalid providers and invalid routes.
        mProviders = providers;
        mRoutes = routes;

        //TODO: Call this when only the list is modified.
        notifyRouteListChanged();
    }

    void notifyRouteSelected(String packageName, MediaRoute2Info route) {
        for (CallbackRecord record : mCallbacks) {
            record.mExecutor.execute(() -> record.mCallback.onRouteSelected(packageName, route));
        }
    }

    void updateControlCategories(String packageName, List<String> categories) {
        mControlCategoryMap.put(packageName, categories);
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public static class Callback {
        /**
         * Called when a route is added.
         */
        public void onRouteAdded(@NonNull MediaRoute2Info routeInfo) {}

        /**
         * Called when a route is changed.
         */
        public void onRouteChanged(@NonNull MediaRoute2Info routeInfo) {}

        /**
         * Called when a route is removed.
         */
        public void onRouteRemoved(@NonNull MediaRoute2Info routeInfo) {}

        /**
         * Called when a route is selected for an application.
         *
         * @param packageName the package name of the application
         * @param route the selected route of the application.
         *              It is null if the application has no selected route.
         */
        public void onRouteSelected(@NonNull String packageName, @Nullable MediaRoute2Info route) {}

        /**
         * Called when the list of routes is changed.
         * A client may refresh available routes for each application.
         */
        public void onRoutesChanged(@NonNull List<MediaRoute2Info> routes) {}
    }

    final class CallbackRecord {
        public final Executor mExecutor;
        public final Callback mCallback;

        CallbackRecord(Executor executor, Callback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        void notifyRoutes() {
            mExecutor.execute(() -> mCallback.onRoutesChanged(mRoutes));
            for (MediaRoute2Info routeInfo : mRoutes) {
                mExecutor.execute(
                        () -> mCallback.onRouteAdded(routeInfo));
            }
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
        public void notifyProviderInfosUpdated(List<MediaRoute2ProviderInfo> info) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifyProviderInfosUpdated,
                    MediaRouter2Manager.this, info));
        }
    }
}
