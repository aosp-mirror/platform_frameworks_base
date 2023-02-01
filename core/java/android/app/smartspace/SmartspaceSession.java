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
package android.app.smartspace;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.smartspace.ISmartspaceCallback.Stub;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Client API to share information about the Smartspace UI state and execute query.
 *
 * <p>
 * Usage: <pre> {@code
 *
 * class MyActivity {
 *    private SmartspaceSession mSmartspaceSession;
 *
 *    void onCreate() {
 *         mSmartspaceSession = mSmartspaceManager.createSmartspaceSession(smartspaceConfig)
 *         mSmartspaceSession.registerSmartspaceUpdates(...)
 *    }
 *
 *    void onStart() {
 *        mSmartspaceSession.requestSmartspaceUpdate()
 *    }
 *
 *    void onTouch(...) OR
 *    void onStateTransitionStarted(...) OR
 *    void onResume(...) OR
 *    void onStop(...) {
 *        mSmartspaceSession.notifyEvent(event);
 *    }
 *
 *    void onDestroy() {
 *        mSmartspaceSession.unregisterPredictionUpdates()
 *        mSmartspaceSession.close();
 *    }
 *
 * }</pre>
 *
 * @hide
 */
@SystemApi
public final class SmartspaceSession implements AutoCloseable {

    private static final String TAG = SmartspaceSession.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final android.app.smartspace.ISmartspaceManager mInterface;
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private final AtomicBoolean mIsClosed = new AtomicBoolean(false);

    private final SmartspaceSessionId mSessionId;
    private final ArrayMap<OnTargetsAvailableListener, CallbackWrapper> mRegisteredCallbacks =
            new ArrayMap<>();
    private final IBinder mToken = new Binder();

    /**
     * Creates a new Smartspace ui client.
     * <p>
     * The caller should call {@link SmartspaceSession#destroy()} to dispose the client once it
     * no longer used.
     *
     * @param context          the {@link Context} of the user of this {@link SmartspaceSession}.
     * @param smartspaceConfig the Smartspace context.
     */
    // b/177858121 Create weak reference child objects to not leak context.
    SmartspaceSession(@NonNull Context context, @NonNull SmartspaceConfig smartspaceConfig) {
        IBinder b = ServiceManager.getService(Context.SMARTSPACE_SERVICE);
        mInterface = android.app.smartspace.ISmartspaceManager.Stub.asInterface(b);
        mSessionId = new SmartspaceSessionId(
                context.getPackageName() + ":" + UUID.randomUUID().toString(), context.getUser());
        try {
            mInterface.createSmartspaceSession(smartspaceConfig, mSessionId, mToken);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to create Smartspace session", e);
            e.rethrowFromSystemServer();
        }

        mCloseGuard.open("SmartspaceSession.close");
    }

    /**
     * Notifies the Smartspace service of a Smartspace target event.
     *
     * @param event The {@link SmartspaceTargetEvent} that represents the Smartspace target event.
     */
    public void notifySmartspaceEvent(@NonNull SmartspaceTargetEvent event) {
        if (mIsClosed.get()) {
            throw new IllegalStateException("This client has already been destroyed.");
        }
        try {
            mInterface.notifySmartspaceEvent(mSessionId, event);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to notify event", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the smartspace service for an update.
     */
    public void requestSmartspaceUpdate() {
        if (mIsClosed.get()) {
            throw new IllegalStateException("This client has already been destroyed.");
        }
        try {
            mInterface.requestSmartspaceUpdate(mSessionId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to request update.", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the smartspace service provide continuous updates of smartspace cards via the
     * provided callback, until the given callback is unregistered.
     *
     * @param listenerExecutor The listener executor to use when firing the listener.
     * @param listener         The listener to be called when updates of Smartspace targets are
     *                         available.
     */
    public void addOnTargetsAvailableListener(@NonNull @CallbackExecutor Executor listenerExecutor,
            @NonNull OnTargetsAvailableListener listener) {
        if (mIsClosed.get()) {
            throw new IllegalStateException("This client has already been destroyed.");
        }

        if (mRegisteredCallbacks.containsKey(listener)) {
            // Skip if this callback is already registered
            return;
        }
        try {
            final CallbackWrapper callbackWrapper = new CallbackWrapper(listenerExecutor,
                    listener::onTargetsAvailable);
            mRegisteredCallbacks.put(listener, callbackWrapper);
            mInterface.registerSmartspaceUpdates(mSessionId, callbackWrapper);
            mInterface.requestSmartspaceUpdate(mSessionId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register for smartspace updates", e);
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Requests the smartspace service to stop providing continuous updates to the provided
     * callback until the callback is re-registered.
     *
     * @param listener The callback to be unregistered.
     * @see {@link SmartspaceSession#addOnTargetsAvailableListener(Executor,
     * OnTargetsAvailableListener)}.
     */
    public void removeOnTargetsAvailableListener(@NonNull OnTargetsAvailableListener listener) {
        if (mIsClosed.get()) {
            throw new IllegalStateException("This client has already been destroyed.");
        }

        if (!mRegisteredCallbacks.containsKey(listener)) {
            // Skip if this callback was never registered
            return;
        }
        try {
            final CallbackWrapper callbackWrapper = mRegisteredCallbacks.remove(listener);
            mInterface.unregisterSmartspaceUpdates(mSessionId, callbackWrapper);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unregister for smartspace updates", e);
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Destroys the client and unregisters the callback. Any method on this class after this call
     * will throw {@link IllegalStateException}.
     */
    private void destroy() {
        if (!mIsClosed.getAndSet(true)) {
            mCloseGuard.close();

            // Do destroy;
            try {
                mInterface.destroySmartspaceSession(mSessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify Smartspace target event", e);
                e.rethrowFromSystemServer();
            }
        } else {
            throw new IllegalStateException("This client has already been destroyed.");
        }
    }

    @Override
    protected void finalize() {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            if (!mIsClosed.get()) {
                destroy();
            }
        } finally {
            try {
                super.finalize();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    @Override
    public void close() {
        try {
            destroy();
            finalize();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    /**
     * Listener to receive smartspace targets from the service.
     */
    public interface OnTargetsAvailableListener {

        /**
         * Called when a new set of smartspace targets are available.
         *
         * @param targets Ranked list of smartspace targets.
         */
        void onTargetsAvailable(@NonNull List<SmartspaceTarget> targets);
    }

    static class CallbackWrapper extends Stub {

        private final Consumer<List<SmartspaceTarget>> mCallback;
        private final Executor mExecutor;

        CallbackWrapper(@NonNull Executor callbackExecutor,
                @NonNull Consumer<List<SmartspaceTarget>> callback) {
            mCallback = callback;
            mExecutor = callbackExecutor;
        }

        @Override
        public void onResult(ParceledListSlice result) {
            final long identity = Binder.clearCallingIdentity();
            try {
                if (DEBUG) {
                    Log.d(TAG, "CallbackWrapper.onResult result=" + result.getList());
                }
                mExecutor.execute(() -> mCallback.accept(result.getList()));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
