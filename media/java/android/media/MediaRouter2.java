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

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
    private List<CallbackRecord> mCallbackRecords = new ArrayList<>();
    final String mPackageName;

    IMediaRouter2Client mClient;

    /**
     * Gets an instance of the media router associated with the context.
     */
    public static MediaRouter2 getInstance(@NonNull Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new MediaRouter2(context);
            }
            return sInstance;
        }
    }

    private MediaRouter2(Context context) {
        mContext = Objects.requireNonNull(context, "context must not be null");
        mMediaRouterService = IMediaRouterService.Stub.asInterface(
                ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));
        mPackageName = mContext.getPackageName();
    }

    /**
     * Registers a callback to discover routes that match the selector and to receive events
     * when they change.
     */
    @MainThread
    public void addCallback(@NonNull List<String> controlCategories, @NonNull Executor executor,
            @NonNull Callback callback) {
        addCallback(controlCategories, executor, callback, 0);
    }

    /**
     * Registers a callback to discover routes that match the selector and to receive events
     * when they change.
     * <p>
     * If you add the same callback twice or more, the previous arguments will be overwritten
     * with the new arguments.
     * </p>
     */
    @MainThread
    public void addCallback(@NonNull List<String> controlCategories, @NonNull Executor executor,
            @NonNull Callback callback, int flags) {
        checkMainThread();

        Objects.requireNonNull(controlCategories, "control categories must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (mCallbackRecords.size() == 0) {
            Client client = new Client();
            try {
                mMediaRouterService.registerClient2AsUser(client, mPackageName,
                        UserHandle.myUserId());
                //TODO: We should merge control categories of callbacks.
                mMediaRouterService.setControlCategories(client, controlCategories);
                mClient = client;
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to register media router.", ex);
            }
        }

        final int index = findCallbackRecordIndex(callback);
        CallbackRecord record;
        if (index < 0) {
            record = new CallbackRecord(callback);
            mCallbackRecords.add(record);
        } else {
            record = mCallbackRecords.get(index);
        }
        record.mExecutor = executor;
        record.mControlCategories = controlCategories;
        record.mFlags = flags;

        //TODO: Check if we need an update.
    }

    /**
     * Removes the given callback. The callback will no longer receive events.
     * If the callback has not been added or been removed already, it is ignored.
     * @param callback the callback to remove.
     * @see #addCallback
     */
    @MainThread
    public void removeCallback(@NonNull Callback callback) {
        checkMainThread();

        Objects.requireNonNull(callback, "callback must not be null");

        final int index = findCallbackRecordIndex(callback);
        if (index < 0) {
            Log.w(TAG, "Ignoring to remove unknown callback. " + callback);
            return;
        }
        mCallbackRecords.remove(index);
        if (mCallbackRecords.size() == 0 && mClient != null) {
            try {
                mMediaRouterService.unregisterClient2(mClient);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to unregister media router.", ex);
            }
            mClient = null;
        }
    }

    private int findCallbackRecordIndex(Callback callback) {
        final int count = mCallbackRecords.size();
        for (int i = 0; i < count; i++) {
            CallbackRecord callbackRecord = mCallbackRecords.get(i);
            if (callbackRecord.mCallback == callback) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Selects the specified route.
     *
     * @param route The route to select.
     */
    //TODO: add a parameter for category (e.g. mirroring/casting)
    public void selectRoute(@Nullable MediaRoute2Info route) {
        if (mClient != null) {
            try {
                mMediaRouterService.selectRoute2(mClient, route);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to select route.", ex);
            }
        }
    }

    /**
     * Sends a media control request to be performed asynchronously by the route's destination.
     * @param route the route that will receive the control request
     * @param request the media control request
     */
    //TODO: Discuss what to use for request (e.g., Intent? Request class?)
    //TODO: Provide a way to obtain the result
    public void sendControlRequest(@NonNull MediaRoute2Info route, @NonNull Intent request) {
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(request, "request must not be null");

        if (mClient != null) {
            try {
                mMediaRouterService.sendControlRequest(mClient, route, request);
            } catch (RemoteException ex) {
                Log.e(TAG, "Unable to send control request.", ex);
            }
        }
    }

    void checkMainThread() {
        Looper looper = Looper.myLooper();
        if (looper == null || looper != Looper.getMainLooper()) {
            throw new IllegalStateException("the method must be called on the main thread");
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     */
    public static class Callback {
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
    }

    final class CallbackRecord {
        public final Callback mCallback;
        public Executor mExecutor;
        public List<String> mControlCategories;
        public int mFlags;

        CallbackRecord(@NonNull Callback callback) {
            mCallback = Objects.requireNonNull(callback, "callback must not be null");
            mControlCategories = Collections.emptyList();
        }
    }

    class Client extends IMediaRouter2Client.Stub {
        @Override
        public void notifyStateChanged() throws RemoteException {}

        @Override
        public void notifyRestoreRoute() throws RemoteException {}
    }
}
