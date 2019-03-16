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
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.app.prediction.IPredictionCallback;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.appprediction.IPredictionService.Stub;
import android.util.ArrayMap;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * TODO(b/111701043): Add java docs
 *
 * @hide
 */
@SystemApi
@TestApi
public abstract class AppPredictionService extends Service {

    private static final String TAG = "AppPredictionService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * TODO(b/111701043): Add any docs about permissions the service must hold
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
        public void notifyLocationShown(AppPredictionSessionId sessionId, String launchLocation,
                ParceledListSlice targetIds) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::onLocationShown, AppPredictionService.this,
                            sessionId, launchLocation, targetIds.getList()));
        }

        @Override
        public void sortAppTargets(AppPredictionSessionId sessionId, ParceledListSlice targets,
                IPredictionCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(AppPredictionService::onSortAppTargets,
                            AppPredictionService.this, sessionId, targets.getList(), null,
                            new CallbackWrapper(callback)));
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
        // TODO(b/111701043): Verify that the action is valid
        return mInterface.asBinder();
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
    public abstract void onLocationShown(@NonNull AppPredictionSessionId sessionId,
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
     * TODO(b/111701043): Implement CancellationSignal so caller can cancel a long running request
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
            callbacks.add(new CallbackWrapper(callback));
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
        if (wrapper != null) {
            callbacks.remove(wrapper);
            if (callbacks.isEmpty()) {
                onStopPredictionUpdates();
            }
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
     * TODO(b/111701043): Add java docs
     *
     * @see #updatePredictions(AppPredictionSessionId, List)
     */
    @MainThread
    public abstract void onRequestPredictionUpdate(@NonNull AppPredictionSessionId sessionId);

    private void doDestroyPredictionSession(@NonNull AppPredictionSessionId sessionId) {
        mSessionCallbacks.remove(sessionId);
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

        CallbackWrapper(IPredictionCallback callback) {
            mCallback = callback;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to link to death: " + e);
            }
        }

        public boolean isCallback(@NonNull IPredictionCallback callback) {
            return mCallback.equals(callback);
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
            mCallback = null;
        }
    }
}
