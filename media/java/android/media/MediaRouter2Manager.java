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
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public class MediaRouter2Manager {
    private static final String TAG = "MediaRouter2Manager";
    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static MediaRouter2Manager sInstance;

    final String mPackageName;

    private Context mContext;
    private Client mClient;
    private final IMediaRouterService mMediaRouterService;
    final Handler mHandler;

    @GuardedBy("sLock")
    final ArrayList<CallbackRecord> mCallbacks = new ArrayList<>();

    /**
     * Gets an instance of media router manager that controls media route of other apps.
     * @param context
     * @return
     */
    public static MediaRouter2Manager getInstance(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
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
     * @param executor The executor that runs the callback.
     * @param callback The callback to add.
     */
    public void addCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {

        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        synchronized (sLock) {
            final int index = findCallbackRecord(callback);
            if (index >= 0) {
                Log.w(TAG, "Ignore adding the same callback twice.");
                return;
            }
            if (mCallbacks.size() == 0) {
                Client client = new Client();
                try {
                    mMediaRouterService.registerManagerAsUser(client, mPackageName,
                            UserHandle.myUserId());
                    mClient = client;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to register media router manager.", ex);
                }
            }
            mCallbacks.add(new CallbackRecord(executor, callback));
        }
    }

    /**
     * Removes the specified callback.
     *
     * @param callback The callback to remove.
     */
    public void removeCallback(@NonNull Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        synchronized (sLock) {
            final int index = findCallbackRecord(callback);
            if (index < 0) {
                Log.w(TAG, "Ignore removing unknown callback. " + callback);
                return;
            }
            mCallbacks.remove(index);
            if (mCallbacks.size() == 0 && mClient != null) {
                try {
                    mMediaRouterService.unregisterManager(mClient);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to unregister media router manager", ex);
                }
                mClient = null;
            }
        }
    }

    private int findCallbackRecord(Callback callback) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            if (mCallbacks.get(i).mCallback == callback) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Selects media route for the specified application uid.
     *
     * @param uid The uid of the application that should change it's media route.
     * @param routeId The id of the route to select
     */
    public void selectRoute(int uid, String routeId) {
        if (mClient != null) {
            try {
                mMediaRouterService.setRemoteRoute(mClient, uid, routeId, /* explicit= */true);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select media route", ex);
            }
        }
    }

    /**
     * Unselects media route for the specified application uid.
     *
     * @param uid The uid of the application that should stop routing.
     */
    public void unselectRoute(int uid) {
        if (mClient != null) {
            try {
                mMediaRouterService.setRemoteRoute(mClient, uid, null, /* explicit= */ true);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select media route", ex);
            }
        }
    }

    void notifyRouteSelected(int uid, String routeId) {
        for (CallbackRecord record : mCallbacks) {
            record.mExecutor.execute(() -> record.mCallback.onRouteSelected(uid, routeId));
        }
    }

    void notifyControlCategoriesChanged(int uid, List<String> categories) {
        for (CallbackRecord record : mCallbacks) {
            record.mExecutor.execute(
                    () -> record.mCallback.onControlCategoriesChanged(uid, categories));
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public abstract static class Callback {
        /**
         * Called when a route is selected for some application uid.
         * @param uid
         * @param routeId
         */
        public abstract void onRouteSelected(int uid, String routeId);

        /**
         * Called when the control categories of an application is changed.
         * @param uid the uid of the app that changed control categories
         * @param categories the changed categories
         */
        public abstract void onControlCategoriesChanged(int uid, List<String> categories);
    }

    final class CallbackRecord {
        public final Executor mExecutor;
        public final Callback mCallback;

        CallbackRecord(Executor executor, Callback callback) {
            mExecutor = executor;
            mCallback = callback;
        }
    }

    class Client extends IMediaRouter2ManagerClient.Stub {
        @Override
        public void onRouteSelected(int uid, String routeId) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifyRouteSelected,
                    MediaRouter2Manager.this, uid, routeId));
        }

        @Override
        public void onControlCategoriesChanged(int uid, List<String> categories) {
            mHandler.sendMessage(obtainMessage(MediaRouter2Manager::notifyControlCategoriesChanged,
                    MediaRouter2Manager.this, uid, categories));
        }
    }
}
