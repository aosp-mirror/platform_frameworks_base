/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.service.smartspace;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.smartspace.ISmartspaceCallback;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.smartspace.ISmartspaceService.Stub;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A service used to share the lifecycle of smartspace UI (open, close, interaction)
 * and also to return smartspace result on a query.
 *
 * @hide
 */
@SystemApi
public abstract class SmartspaceService extends Service {

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>The service must also require the {@link android.permission#MANAGE_SMARTSPACE}
     * permission.
     *
     * @hide
     */
    public static final String SERVICE_INTERFACE =
            "android.service.smartspace.SmartspaceService";
    private static final boolean DEBUG = false;
    private static final String TAG = "SmartspaceService";
    private final ArrayMap<SmartspaceSessionId, ArrayList<CallbackWrapper>> mSessionCallbacks =
            new ArrayMap<>();
    private Handler mHandler;

    private final android.service.smartspace.ISmartspaceService mInterface = new Stub() {

        @Override
        public void onCreateSmartspaceSession(SmartspaceConfig smartspaceConfig,
                SmartspaceSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(SmartspaceService::doCreateSmartspaceSession,
                            SmartspaceService.this, smartspaceConfig, sessionId));
        }

        @Override
        public void notifySmartspaceEvent(SmartspaceSessionId sessionId,
                SmartspaceTargetEvent event) {
            mHandler.sendMessage(
                    obtainMessage(SmartspaceService::notifySmartspaceEvent,
                            SmartspaceService.this, sessionId, event));
        }

        @Override
        public void requestSmartspaceUpdate(SmartspaceSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(SmartspaceService::doRequestPredictionUpdate,
                            SmartspaceService.this, sessionId));
        }

        @Override
        public void registerSmartspaceUpdates(SmartspaceSessionId sessionId,
                ISmartspaceCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(SmartspaceService::doRegisterSmartspaceUpdates,
                            SmartspaceService.this, sessionId, callback));
        }

        @Override
        public void unregisterSmartspaceUpdates(SmartspaceSessionId sessionId,
                ISmartspaceCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(SmartspaceService::doUnregisterSmartspaceUpdates,
                            SmartspaceService.this, sessionId, callback));
        }

        @Override
        public void onDestroySmartspaceSession(SmartspaceSessionId sessionId) {

            mHandler.sendMessage(
                    obtainMessage(SmartspaceService::doDestroy,
                            SmartspaceService.this, sessionId));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) {
            Log.d(TAG, "onCreate mSessionCallbacks: " + mSessionCallbacks);
        }
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onBind mSessionCallbacks: " + mSessionCallbacks);
        }
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Slog.w(TAG, "Tried to bind to wrong intent (should be "
                + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    private void doCreateSmartspaceSession(@NonNull SmartspaceConfig config,
            @NonNull SmartspaceSessionId sessionId) {
        if (DEBUG) {
            Log.d(TAG, "doCreateSmartspaceSession mSessionCallbacks: " + mSessionCallbacks);
        }
        mSessionCallbacks.put(sessionId, new ArrayList<>());
        onCreateSmartspaceSession(config, sessionId);
    }

    /**
     * Gets called when the client calls <code> SmartspaceManager#createSmartspaceSession </code>.
     */
    public abstract void onCreateSmartspaceSession(@NonNull SmartspaceConfig config,
            @NonNull SmartspaceSessionId sessionId);

    /**
     * Gets called when the client calls <code> SmartspaceSession#notifySmartspaceEvent </code>.
     */
    @MainThread
    public abstract void notifySmartspaceEvent(@NonNull SmartspaceSessionId sessionId,
            @NonNull SmartspaceTargetEvent event);

    /**
     * Gets called when the client calls <code> SmartspaceSession#requestSmartspaceUpdate </code>.
     */
    @MainThread
    public abstract void onRequestSmartspaceUpdate(@NonNull SmartspaceSessionId sessionId);

    private void doRegisterSmartspaceUpdates(@NonNull SmartspaceSessionId sessionId,
            @NonNull ISmartspaceCallback callback) {
        if (DEBUG) {
            Log.d(TAG, "doRegisterSmartspaceUpdates mSessionCallbacks: " + mSessionCallbacks);
        }
        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks == null) {
            Slog.e(TAG, "Failed to register for updates for unknown session: " + sessionId);
            return;
        }

        final CallbackWrapper wrapper = findCallbackWrapper(callbacks, callback);
        if (wrapper == null) {
            callbacks.add(new CallbackWrapper(callback,
                    callbackWrapper ->
                            mHandler.post(
                                    () -> removeCallbackWrapper(callbacks, callbackWrapper))));
        }
    }

    private void doUnregisterSmartspaceUpdates(@NonNull SmartspaceSessionId sessionId,
            @NonNull ISmartspaceCallback callback) {
        if (DEBUG) {
            Log.d(TAG, "doUnregisterSmartspaceUpdates mSessionCallbacks: " + mSessionCallbacks);
        }
        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks == null) {
            Slog.e(TAG, "Failed to unregister for updates for unknown session: " + sessionId);
            return;
        }

        final CallbackWrapper wrapper = findCallbackWrapper(callbacks, callback);
        removeCallbackWrapper(callbacks, wrapper);
    }

    private void doRequestPredictionUpdate(@NonNull SmartspaceSessionId sessionId) {
        if (DEBUG) {
            Log.d(TAG, "doRequestPredictionUpdate mSessionCallbacks: " + mSessionCallbacks);
        }
        // Just an optimization, if there are no callbacks, then don't bother notifying the service
        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks != null && !callbacks.isEmpty()) {
            onRequestSmartspaceUpdate(sessionId);
        }
    }

    /**
     * Finds the callback wrapper for the given callback.
     */
    private CallbackWrapper findCallbackWrapper(ArrayList<CallbackWrapper> callbacks,
            ISmartspaceCallback callback) {
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            if (callbacks.get(i).isCallback(callback)) {
                return callbacks.get(i);
            }
        }
        return null;
    }

    private void removeCallbackWrapper(@Nullable ArrayList<CallbackWrapper> callbacks,
            @Nullable CallbackWrapper wrapper) {
        if (callbacks == null || wrapper == null) {
            return;
        }
        callbacks.remove(wrapper);
        wrapper.destroy();
    }

    /**
     * Gets called when the client calls <code> SmartspaceManager#destroy() </code>.
     */
    public abstract void onDestroySmartspaceSession(@NonNull SmartspaceSessionId sessionId);

    private void doDestroy(@NonNull SmartspaceSessionId sessionId) {
        if (DEBUG) {
            Log.d(TAG, "doDestroy mSessionCallbacks: " + mSessionCallbacks);
        }
        super.onDestroy();

        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.remove(sessionId);
        if (callbacks != null) callbacks.forEach(CallbackWrapper::destroy);
        onDestroySmartspaceSession(sessionId);
    }

    /**
     * Used by the prediction factory to send back results the client app. The can be called
     * in response to {@link #onRequestSmartspaceUpdate(SmartspaceSessionId)} or proactively as
     * a result of changes in predictions.
     */
    public final void updateSmartspaceTargets(@NonNull SmartspaceSessionId sessionId,
            @NonNull List<SmartspaceTarget> targets) {
        if (DEBUG) {
            Log.d(TAG, "updateSmartspaceTargets mSessionCallbacks: " + mSessionCallbacks);
        }
        List<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks != null) {
            for (CallbackWrapper callback : callbacks) {
                callback.accept(targets);
            }
        }
    }

    /**
     * Destroys a smartspace session.
     */
    @MainThread
    public abstract void onDestroy(@NonNull SmartspaceSessionId sessionId);

    private static final class CallbackWrapper implements Consumer<List<SmartspaceTarget>>,
            IBinder.DeathRecipient {

        private final Consumer<CallbackWrapper> mOnBinderDied;
        private ISmartspaceCallback mCallback;

        CallbackWrapper(ISmartspaceCallback callback,
                @Nullable Consumer<CallbackWrapper> onBinderDied) {
            mCallback = callback;
            mOnBinderDied = onBinderDied;
            if (mOnBinderDied != null) {
                try {
                    mCallback.asBinder().linkToDeath(this, 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to link to death: " + e);
                }
            }
        }

        public boolean isCallback(@NonNull ISmartspaceCallback callback) {
            if (mCallback == null) {
                Slog.e(TAG, "Callback is null, likely the binder has died.");
                return false;
            }
            return mCallback.asBinder().equals(callback.asBinder());
        }

        @Override
        public void accept(List<SmartspaceTarget> smartspaceTargets) {
            try {
                if (mCallback != null) {
                    if (DEBUG) {
                        Slog.d(TAG,
                                "CallbackWrapper.accept smartspaceTargets=" + smartspaceTargets);
                    }
                    mCallback.onResult(new ParceledListSlice(smartspaceTargets));
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending result:" + e);
            }
        }

        public void destroy() {
            if (mCallback != null && mOnBinderDied != null) {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }
        }

        @Override
        public void binderDied() {
            destroy();
            mCallback = null;
            if (mOnBinderDied != null) {
                mOnBinderDied.accept(this);
            }
        }
    }
}
