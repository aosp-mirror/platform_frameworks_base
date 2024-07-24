/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.appprediction;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.FlaggedApi;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.app.prediction.IPredictionCallback;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.service.appprediction.IPredictionService.Stub;
import android.service.appprediction.flags.Flags;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A service used to predict app and shortcut usage.
 *
 * @hide
 */
@SystemApi
public abstract class AppPredictionService extends Service {

    private static final String TAG = "AppPredictionService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>The service must also require the {@link android.permission#MANAGE_APP_PREDICTIONS}
     * permission.
     *
     * @hide
     */
    public static final String SERVICE_INTERFACE =
            "android.service.appprediction.AppPredictionService";

    private final ArrayMap<AppPredictionSessionId, ArrayList<CallbackWrapper>> mSessionCallbacks =
            new ArrayMap<>();
    private Handler mHandler;

    private final IPredictionService mInterface = new Stub() {

        @Override
        public void onCreatePredictionSession(AppPredictionContext context,
                AppPredictionSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::doCreatePredictionSession,
                            AppPredictionService.this, context, sessionId));
        }

        @Override
        public void notifyAppTargetEvent(AppPredictionSessionId sessionId, AppTargetEvent event) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::onAppTargetEvent,
                            AppPredictionService.this, sessionId, event));
        }

        @Override
        public void notifyLaunchLocationShown(AppPredictionSessionId sessionId,
                String launchLocation, ParceledListSlice targetIds) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::onLaunchLocationShown,
                            AppPredictionService.this, sessionId, launchLocation,
                            targetIds.getList()));
        }

        @Override
        public void sortAppTargets(AppPredictionSessionId sessionId, ParceledListSlice targets,
                IPredictionCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::onSortAppTargets,
                            AppPredictionService.this, sessionId, targets.getList(), null,
                            new CallbackWrapper(callback, null)));
        }

        @Override
        public void registerPredictionUpdates(AppPredictionSessionId sessionId,
                IPredictionCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::doRegisterPredictionUpdates,
                            AppPredictionService.this, sessionId, callback));
        }

        @Override
        public void unregisterPredictionUpdates(AppPredictionSessionId sessionId,
                IPredictionCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::doUnregisterPredictionUpdates,
                            AppPredictionService.this, sessionId, callback));
        }

        @Override
        public void requestPredictionUpdate(AppPredictionSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::doRequestPredictionUpdate,
                            AppPredictionService.this, sessionId));
        }

        @Override
        public void onDestroyPredictionSession(AppPredictionSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::doDestroyPredictionSession,
                            AppPredictionService.this, sessionId));
        }

        @FlaggedApi(Flags.FLAG_SERVICE_FEATURES_API)
        @Override
        public void requestServiceFeatures(AppPredictionSessionId sessionId,
                IRemoteCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::onRequestServiceFeatures,
                            AppPredictionService.this, sessionId,
                            new RemoteCallbackWrapper(callback, null)));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Called by a client app to indicate a target launch
     */
    @MainThread
    public abstract void onAppTargetEvent(@NonNull AppPredictionSessionId sessionId,
            @NonNull AppTargetEvent event);

    /**
     * Called by a client app to indication a particular location has been shown to the user.
     */
    @MainThread
    public abstract void onLaunchLocationShown(@NonNull AppPredictionSessionId sessionId,
            @NonNull String launchLocation, @NonNull List<AppTargetId> targetIds);

    private void doCreatePredictionSession(@NonNull AppPredictionContext context,
            @NonNull AppPredictionSessionId sessionId) {
        mSessionCallbacks.put(sessionId, new ArrayList<>());
        onCreatePredictionSession(context, sessionId);
    }

    /**
     * Creates a new interaction session.
     *
     * @param context interaction context
     * @param sessionId the session's Id
     */
    public void onCreatePredictionSession(@NonNull AppPredictionContext context,
            @NonNull AppPredictionSessionId sessionId) {}

    /**
     * Called by the client app to request sorting of targets based on prediction rank.
     */
    @MainThread
    public abstract void onSortAppTargets(@NonNull AppPredictionSessionId sessionId,
            @NonNull List<AppTarget> targets, @NonNull CancellationSignal cancellationSignal,
            @NonNull Consumer<List<AppTarget>> callback);

    private void doRegisterPredictionUpdates(@NonNull AppPredictionSessionId sessionId,
            @NonNull IPredictionCallback callback) {
        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks == null) {
            Slog.e(TAG, "Failed to register for updates for unknown session: " + sessionId);
            return;
        }

        final CallbackWrapper wrapper = findCallbackWrapper(callbacks, callback);
        if (wrapper == null) {
            callbacks.add(new CallbackWrapper(callback,
                    callbackWrapper ->
                        mHandler.post(() -> removeCallbackWrapper(callbacks, callbackWrapper))));
            if (callbacks.size() == 1) {
                onStartPredictionUpdates();
            }
        }
    }

    /**
     * Called when any continuous prediction callback is registered.
     */
    @MainThread
    public void onStartPredictionUpdates() {}

    private void doUnregisterPredictionUpdates(@NonNull AppPredictionSessionId sessionId,
            @NonNull IPredictionCallback callback) {
        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks == null) {
            Slog.e(TAG, "Failed to unregister for updates for unknown session: " + sessionId);
            return;
        }

        final CallbackWrapper wrapper = findCallbackWrapper(callbacks, callback);
        removeCallbackWrapper(callbacks, wrapper);
    }

    private void removeCallbackWrapper(@Nullable ArrayList<CallbackWrapper> callbacks,
            @Nullable CallbackWrapper wrapper) {
        if (callbacks == null || wrapper == null) {
            return;
        }
        callbacks.remove(wrapper);
        wrapper.destroy();
        if (callbacks.isEmpty()) {
            onStopPredictionUpdates();
        }
    }

    /**
     * Called when there are no longer any continuous prediction callbacks registered.
     */
    @MainThread
    public void onStopPredictionUpdates() {}

    private void doRequestPredictionUpdate(@NonNull AppPredictionSessionId sessionId) {
        // Just an optimization, if there are no callbacks, then don't bother notifying the service
        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks != null && !callbacks.isEmpty()) {
            onRequestPredictionUpdate(sessionId);
        }
    }

    /**
     * Called by the client app to request target predictions. This method is only called if there
     * are one or more prediction callbacks registered.
     *
     * @see #updatePredictions(AppPredictionSessionId, List)
     */
    @MainThread
    public abstract void onRequestPredictionUpdate(@NonNull AppPredictionSessionId sessionId);

    private void doDestroyPredictionSession(@NonNull AppPredictionSessionId sessionId) {
        final ArrayList<CallbackWrapper> callbacks = mSessionCallbacks.remove(sessionId);
        if (callbacks != null) callbacks.forEach(CallbackWrapper::destroy);
        onDestroyPredictionSession(sessionId);
    }

    /**
     * Destroys the interaction session.
     *
     * @param sessionId the id of the session to destroy
     */
    @MainThread
    public void onDestroyPredictionSession(@NonNull AppPredictionSessionId sessionId) {}

    /**
     * Called by the client app to request {@link AppPredictionService} features info.
     *
     * @param sessionId the session's Id. It is @NonNull.
     * @param callback the callback to return the Bundle which includes service features info. It
     *                is @NonNull.
     */
    @FlaggedApi(Flags.FLAG_SERVICE_FEATURES_API)
    @MainThread
    public void onRequestServiceFeatures(@NonNull AppPredictionSessionId sessionId,
            @NonNull Consumer<Bundle> callback) {}

    /**
     * Used by the prediction factory to send back results the client app. The can be called
     * in response to {@link #onRequestPredictionUpdate(AppPredictionSessionId)} or proactively as
     * a result of changes in predictions.
     */
    public final void updatePredictions(@NonNull AppPredictionSessionId sessionId,
            @NonNull List<AppTarget> targets) {
        List<CallbackWrapper> callbacks = mSessionCallbacks.get(sessionId);
        if (callbacks != null) {
            for (CallbackWrapper callback : callbacks) {
                callback.accept(targets);
            }
        }
    }

    /**
     * Finds the callback wrapper for the given callback.
     */
    private CallbackWrapper findCallbackWrapper(ArrayList<CallbackWrapper> callbacks,
            IPredictionCallback callback) {
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            if (callbacks.get(i).isCallback(callback)) {
                return callbacks.get(i);
            }
        }
        return null;
    }

    private static final class CallbackWrapper implements Consumer<List<AppTarget>>,
            IBinder.DeathRecipient {

        private IPredictionCallback mCallback;
        private final Consumer<CallbackWrapper> mOnBinderDied;

        CallbackWrapper(IPredictionCallback callback,
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

        public boolean isCallback(@NonNull IPredictionCallback callback) {
            if (mCallback == null) {
                Slog.e(TAG, "Callback is null, likely the binder has died.");
                return false;
            }
            return mCallback.asBinder().equals(callback.asBinder());
        }

        public void destroy() {
            if (mCallback != null && mOnBinderDied != null) {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }
        }

        @Override
        public void accept(List<AppTarget> ts) {
            try {
                if (mCallback != null) {
                    mCallback.onResult(new ParceledListSlice(ts));
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending result:" + e);
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

    private static final class RemoteCallbackWrapper implements Consumer<Bundle>,
            IBinder.DeathRecipient {

        private IRemoteCallback mCallback;
        private final Consumer<RemoteCallbackWrapper> mOnBinderDied;

        RemoteCallbackWrapper(IRemoteCallback callback,
                @Nullable Consumer<RemoteCallbackWrapper> onBinderDied) {
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

        public void destroy() {
            if (mCallback != null && mOnBinderDied != null) {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }
        }

        @Override
        public void accept(Bundle bundle) {
            try {
                if (mCallback != null) {
                    mCallback.sendResult(bundle);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending result:" + e);
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
