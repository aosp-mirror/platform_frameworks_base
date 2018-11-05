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

package com.android.server;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * Base class representing a remote service.
 *
 * <p>It abstracts away the binding and unbinding from the remote implementation, so clients can
 * call its methods without worrying about when and how to bind/unbind/timeout.
 *
 * <p>All state of this class is modified on a handler thread.
 *
 * <p>See {@code com.android.server.autofill.RemoteFillService} for a concrete
 * (no pun intended) example of how to use it.
 *
 * @hide
 */
//TODO(b/117779333): improve javadoc above instead of using Autofill as an example
public abstract class AbstractRemoteService implements DeathRecipient {

    private static final int MSG_UNBIND = 1;

    protected static final int LAST_PRIVATE_MSG = MSG_UNBIND;

    // TODO(b/117779333): convert all booleans into an integer / flags
    public final boolean mVerbose;

    protected final String mTag = getClass().getSimpleName();
    protected final Handler mHandler;
    protected final ComponentName mComponentName;

    protected PendingRequest<? extends AbstractRemoteService> mPendingRequest;

    private final Context mContext;
    private final Intent mIntent;
    private final VultureCallback mVultureCallback;
    private final int mUserId;
    private final ServiceConnection mServiceConnection = new RemoteServiceConnection();
    private final boolean mBindInstantServiceAllowed;
    private IInterface mServiceInterface;

    private boolean mBinding;
    private boolean mDestroyed;
    private boolean mServiceDied;
    private boolean mCompleted;

    /**
     * Callback called when the service dies.
     */
    public interface VultureCallback {
        /**
         * Called when the service dies.
         *
         * @param service service that died!
         */
        void onServiceDied(AbstractRemoteService service);
    }

    public AbstractRemoteService(@NonNull Context context, @NonNull String serviceInterface,
            @NonNull ComponentName componentName, int userId, @NonNull VultureCallback callback,
            boolean bindInstantServiceAllowed, boolean verbose) {
        mContext = context;
        mVultureCallback = callback;
        mVerbose = verbose;
        mComponentName = componentName;
        mIntent = new Intent(serviceInterface).setComponent(mComponentName);
        mUserId = userId;
        mHandler = new Handler(FgThread.getHandler().getLooper());
        mBindInstantServiceAllowed = bindInstantServiceAllowed;
    }

    /**
     * Destroys this service.
     */
    public final void destroy() {
        mHandler.sendMessage(obtainMessage(AbstractRemoteService::handleDestroy, this));
    }

    /**
     * Checks whether this service is destroyed.
     */
    public final boolean isDestroyed() {
        return mDestroyed;
    }

    /**
     * Callback called when the system connected / disconnected to the service.
     *
     * @param state {@code true} when connected, {@code false} when disconnected.
     */
    protected void onConnectedStateChanged(boolean state) {
    }

    /**
     * Gets the base Binder interface from the service.
     */
    @NonNull
    protected abstract IInterface getServiceInterface(@NonNull IBinder service);

    /**
     * Defines How long after the last interaction with the service we would unbind.
     */
    protected abstract long getTimeoutIdleBindMillis();

    /**
     * Defines how long after we make a remote request to a fill service we timeout.
     */
    protected abstract long getRemoteRequestMillis();

    private void handleDestroy() {
        if (checkIfDestroyed()) return;
        if (mPendingRequest != null) {
            mPendingRequest.cancel();
            mPendingRequest = null;
        }
        ensureUnbound();
        mDestroyed = true;
    }

    @Override // from DeathRecipient
    public void binderDied() {
        mHandler.sendMessage(obtainMessage(AbstractRemoteService::handleBinderDied, this));
    }

    private void handleBinderDied() {
        if (checkIfDestroyed()) return;
        if (mServiceInterface != null) {
            mServiceInterface.asBinder().unlinkToDeath(this, 0);
        }
        mServiceInterface = null;
        mServiceDied = true;
        mVultureCallback.onServiceDied(this);
    }

    // Note: we are dumping without a lock held so this is a bit racy but
    // adding a lock to a class that offloads to a handler thread would
    // mean adding a lock adding overhead to normal runtime operation.
    /**
     * Dump it!
     */
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        String tab = "  ";
        pw.append(prefix).append("service:").println();
        pw.append(prefix).append(tab).append("userId=")
                .append(String.valueOf(mUserId)).println();
        pw.append(prefix).append(tab).append("componentName=")
                .append(mComponentName.flattenToString()).println();
        pw.append(prefix).append(tab).append("destroyed=")
                .append(String.valueOf(mDestroyed)).println();
        pw.append(prefix).append(tab).append("bound=")
                .append(String.valueOf(isBound())).println();
        pw.append(prefix).append(tab).append("hasPendingRequest=")
                .append(String.valueOf(mPendingRequest != null)).println();
        pw.append(prefix).append("mBindInstantServiceAllowed=").println(mBindInstantServiceAllowed);
        pw.append(prefix).append("idleTimeout=")
            .append(Long.toString(getTimeoutIdleBindMillis() / 1000)).append("s").println();
        pw.append(prefix).append("requestTimeout=")
            .append(Long.toString(getRemoteRequestMillis() / 1000)).append("s").println();
        pw.println();
    }

    protected void scheduleRequest(PendingRequest<? extends AbstractRemoteService> pendingRequest) {
        mHandler.sendMessage(obtainMessage(
                AbstractRemoteService::handlePendingRequest, this, pendingRequest));
    }

    protected void cancelScheduledUnbind() {
        mHandler.removeMessages(MSG_UNBIND);
    }

    protected void scheduleUnbind() {
        cancelScheduledUnbind();
        mHandler.sendMessageDelayed(obtainMessage(AbstractRemoteService::handleUnbind, this)
                .setWhat(MSG_UNBIND), getTimeoutIdleBindMillis());
    }

    private void handleUnbind() {
        if (checkIfDestroyed()) return;

        ensureUnbound();
    }

    private void handlePendingRequest(
            PendingRequest<? extends AbstractRemoteService> pendingRequest) {
        if (checkIfDestroyed() || mCompleted) return;

        if (!isBound()) {
            if (mPendingRequest != null) {
                mPendingRequest.cancel();
            }
            mPendingRequest = pendingRequest;
            ensureBound();
        } else {
            if (mVerbose) Slog.v(mTag, "handlePendingRequest(): " + pendingRequest);
            pendingRequest.run();
            if (pendingRequest.isFinal()) {
                mCompleted = true;
            }
        }
    }

    private boolean isBound() {
        return mServiceInterface != null;
    }

    private void ensureBound() {
        if (isBound() || mBinding) return;

        if (mVerbose) Slog.v(mTag, "ensureBound()");
        mBinding = true;

        int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
        if (mBindInstantServiceAllowed) {
            flags |= Context.BIND_ALLOW_INSTANT;
        }

        final boolean willBind = mContext.bindServiceAsUser(mIntent, mServiceConnection, flags,
                new UserHandle(mUserId));

        if (!willBind) {
            Slog.w(mTag, "could not bind to " + mIntent + " using flags " + flags);
            mBinding = false;

            if (!mServiceDied) {
                handleBinderDied();
            }
        }
    }

    private void ensureUnbound() {
        if (!isBound() && !mBinding) return;

        if (mVerbose) Slog.v(mTag, "ensureUnbound()");
        mBinding = false;
        if (isBound()) {
            onConnectedStateChanged(false);
            if (mServiceInterface != null) {
                mServiceInterface.asBinder().unlinkToDeath(this, 0);
                mServiceInterface = null;
            }
        }
        mContext.unbindService(mServiceConnection);
    }

    private class RemoteServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mDestroyed || !mBinding) {
                // This is abnormal. Unbinding the connection has been requested already.
                Slog.wtf(mTag, "onServiceConnected() was dispatched after unbindService.");
                return;
            }
            mBinding = false;
            mServiceInterface = getServiceInterface(service);
            try {
                service.linkToDeath(AbstractRemoteService.this, 0);
            } catch (RemoteException re) {
                handleBinderDied();
                return;
            }
            onConnectedStateChanged(true);

            if (mPendingRequest != null) {
                final PendingRequest<? extends AbstractRemoteService> pendingRequest =
                        mPendingRequest;
                mPendingRequest = null;
                handlePendingRequest(pendingRequest);
            }

            mServiceDied = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinding = true;
            mServiceInterface = null;
        }
    }

    private boolean checkIfDestroyed() {
        if (mDestroyed) {
            if (mVerbose) {
                Slog.v(mTag, "Not handling operation as service for " + mComponentName
                        + " is already destroyed");
            }
        }
        return mDestroyed;
    }

    protected boolean handleResponseCallbackCommon(
            PendingRequest<? extends AbstractRemoteService> pendingRequest) {
        if (isDestroyed()) return false;

        if (mPendingRequest == pendingRequest) {
            mPendingRequest = null;
        }
        if (mPendingRequest == null) {
            scheduleUnbind();
        }
        return true;
    }

    /**
     * Base class for the requests serviced by the remote service.
     *
     * @param <S> the remote service class
     */
    public abstract static class PendingRequest<S extends AbstractRemoteService>
            implements Runnable {
        protected final String mTag = getClass().getSimpleName();
        protected final Object mLock = new Object();

        private final WeakReference<S> mWeakService;
        private final Runnable mTimeoutTrigger;
        private final Handler mServiceHandler;

        @GuardedBy("mLock")
        private boolean mCancelled;

        @GuardedBy("mLock")
        private boolean mCompleted;

        protected PendingRequest(S service) {
            mWeakService = new WeakReference<>(service);
            mServiceHandler = service.mHandler;
            mTimeoutTrigger = () -> {
                synchronized (mLock) {
                    if (mCancelled) {
                        return;
                    }
                    mCompleted = true;
                }

                final S remoteService = mWeakService.get();
                if (remoteService != null) {
                    Slog.w(mTag, "timed out after " + service.getRemoteRequestMillis() + " ms");
                    onTimeout(remoteService);
                } else {
                    Slog.w(mTag, "timed out (no service)");
                }
            };
            mServiceHandler.postAtTime(mTimeoutTrigger,
                    SystemClock.uptimeMillis() + service.getRemoteRequestMillis());
        }

        /**
         * Gets a reference to the remote service.
         */
        protected final S getService() {
            return mWeakService.get();
        }

        /**
         * Subclasses must call this method when the remote service finishes, i.e., when the service
         * finishes processing a request.
         *
         * @return {@code false} in the service is already finished, {@code true} otherwise.
         */
        protected final boolean finish() {
            synchronized (mLock) {
                if (mCompleted || mCancelled) {
                    return false;
                }
                mCompleted = true;
            }
            mServiceHandler.removeCallbacks(mTimeoutTrigger);
            return true;
        }

        /**
         * Checks whether this request was cancelled.
         */
        @GuardedBy("mLock")
        protected final boolean isCancelledLocked() {
            return mCancelled;
        }

        /**
         * Cancels the service.
         *
         * @return {@code false} if service is already canceled, {@code true} otherwise.
         */
        public boolean cancel() {
            synchronized (mLock) {
                if (mCancelled || mCompleted) {
                    return false;
                }
                mCancelled = true;
            }

            mServiceHandler.removeCallbacks(mTimeoutTrigger);
            return true;
        }

        /**
         * Called by the self-destruct timeout when the remote service didn't reply to the
         * request on time.
         */
        protected abstract void onTimeout(S remoteService);

        /**
         * Checks whether this request leads to a final state where no other requests can be made.
         */
        protected boolean isFinal() {
            return false;
        }
    }
}
