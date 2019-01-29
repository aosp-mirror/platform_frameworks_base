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

package com.android.server.role;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.role.IRoleManagerCallback;
import android.app.role.RoleManagerCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.rolecontrollerservice.IRoleControllerService;
import android.rolecontrollerservice.RoleControllerService;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * Handles connection with {@link RoleControllerService}.
 */
public class RemoteRoleControllerService {

    static final boolean DEBUG = false;
    private static final String LOG_TAG = RemoteRoleControllerService.class.getSimpleName();

    @NonNull
    private final Connection mConnection;

    public RemoteRoleControllerService(@UserIdInt int userId, @NonNull Context context) {
        mConnection = new Connection(userId, context);
    }

    /**
     * Add a specific application to the holders of a role. If the role is exclusive, the previous
     * holder will be replaced.
     *
     * @see RoleControllerService#onAddRoleHolder(String, String, RoleManagerCallback)
     */
    public void onAddRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @NonNull IRoleManagerCallback callback) {
        mConnection.enqueueCall(new Connection.Call((service, callbackDelegate) ->
                service.onAddRoleHolder(roleName, packageName, callbackDelegate), callback));
    }

    /**
     * Remove a specific application from the holders of a role.
     *
     * @see RoleControllerService#onRemoveRoleHolder(String, String, RoleManagerCallback)
     */
    public void onRemoveRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @NonNull IRoleManagerCallback callback) {
        mConnection.enqueueCall(new Connection.Call((service, callbackDelegate) ->
                service.onRemoveRoleHolder(roleName, packageName, callbackDelegate), callback));
    }

    /**
     * Remove all holders of a role.
     *
     * @see RoleControllerService#onClearRoleHolders(String, RoleManagerCallback)
     */
    public void onClearRoleHolders(@NonNull String roleName,
            @NonNull IRoleManagerCallback callback) {
        mConnection.enqueueCall(new Connection.Call((service, callbackDelegate) ->
                service.onClearRoleHolders(roleName, callbackDelegate), callback));
    }

    /**
     * Performs granting of default roles and permissions and appops
     *
     * @see RoleControllerService#onGrantDefaultRoles(RoleManagerCallback)
     */
    public void onGrantDefaultRoles(@NonNull IRoleManagerCallback callback) {
        mConnection.enqueueCall(new Connection.Call(IRoleControllerService::onGrantDefaultRoles,
                callback));
    }

    private static final class Connection implements ServiceConnection {

        private static final long UNBIND_DELAY_MILLIS = 15 * 1000;

        @UserIdInt
        private final int mUserId;

        @NonNull
        private final Context mContext;

        @NonNull
        private final Handler mHandler = FgThread.getHandler();

        private boolean mBound;

        @Nullable
        private IRoleControllerService mService;

        @NonNull
        private final Queue<Call> mPendingCalls = new ArrayDeque<>();

        @NonNull
        private final Runnable mUnbindRunnable = this::unbind;

        Connection(@UserIdInt int userId, @NonNull Context context) {
            mUserId = userId;
            mContext = context;
        }

        @Override
        @WorkerThread
        public void onServiceConnected(@NonNull ComponentName name, @NonNull IBinder service) {
            mService = IRoleControllerService.Stub.asInterface(service);
            executePendingCalls();
        }

        @WorkerThread
        private void executePendingCalls() {
            while (!mPendingCalls.isEmpty()) {
                Call call = mPendingCalls.poll();
                call.execute(mService);
            }
            scheduleUnbind();
        }

        @Override
        @WorkerThread
        public void onServiceDisconnected(@NonNull ComponentName name) {
            mService = null;
        }

        @Override
        @WorkerThread
        public void onBindingDied(@NonNull ComponentName name) {
            unbind();
        }

        public void enqueueCall(@NonNull Call call) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "Enqueue " + call);
            }
            mHandler.executeOrSendMessage(PooledLambda.obtainMessage(Connection::executeCall, this,
                    call));
        }

        @WorkerThread
        private void executeCall(@NonNull Call call) {
            ensureBound();
            if (mService == null) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Delaying until service connected: " + call);
                }
                mPendingCalls.offer(call);
                return;
            }
            call.execute(mService);
            scheduleUnbind();
        }

        @WorkerThread
        private void ensureBound() {
            mHandler.removeCallbacks(mUnbindRunnable);
            if (!mBound) {
                Intent intent = new Intent(RoleControllerService.SERVICE_INTERFACE);
                intent.setPackage(mContext.getPackageManager()
                        .getPermissionControllerPackageName());
                // Use direct handler to ensure onServiceConnected callback happens in the same
                // call frame, as required by onGrantDefaultRoles
                //
                // Note that as a result, onServiceConnected may happen not on main thread!
                mBound = mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE,
                        mHandler, UserHandle.of(mUserId));
            }
        }

        private void scheduleUnbind() {
            mHandler.removeCallbacks(mUnbindRunnable);
            mHandler.postDelayed(mUnbindRunnable, UNBIND_DELAY_MILLIS);
        }

        @WorkerThread
        private void unbind() {
            if (mBound) {
                mService = null;
                mContext.unbindService(this);
                mBound = false;
            }
        }

        public static class Call {

            private static final int TIMEOUT_MILLIS = 15 * 1000;

            @NonNull
            private final CallExecutor mCallExecutor;

            @NonNull
            private final IRoleManagerCallback mCallback;

            @NonNull
            private final Handler mHandler = FgThread.getHandler();

            @NonNull
            private final Runnable mTimeoutRunnable = this::notifyTimeout;

            private boolean mCallbackNotified;

            @Nullable
            private final String mDebugName;

            private Call(@NonNull CallExecutor callExecutor,
                    @NonNull IRoleManagerCallback callback) {
                mCallExecutor = callExecutor;
                mCallback = callback;
                mDebugName = DEBUG
                        ? Arrays.stream(Thread.currentThread().getStackTrace())
                                .filter(s -> s.getClassName().equals(
                                        RemoteRoleControllerService.class.getName()))
                                .findFirst()
                                .get()
                                .getMethodName()
                        : null;
            }

            @WorkerThread
            public void execute(IRoleControllerService service) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Executing " + this);
                }
                try {
                    mHandler.postDelayed(mTimeoutRunnable, TIMEOUT_MILLIS);
                    mCallExecutor.execute(service, new CallbackDelegate());
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling RoleControllerService", e);
                    notifyCallback(false);
                }
            }

            @WorkerThread
            private void notifyTimeout() {
                Slog.e(LOG_TAG, "Call timed out, calling onFailure()");
                notifyCallback(false);
            }

            @WorkerThread
            private void notifyCallback(boolean success) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "notifyCallback(" + this
                            + ", success = " + success + ")");
                }
                if (mCallbackNotified) {
                    return;
                }
                mCallbackNotified = true;
                mHandler.removeCallbacks(mTimeoutRunnable);
                try {
                    if (success) {
                        mCallback.onSuccess();
                    } else {
                        mCallback.onFailure();
                    }
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling " + (success ? "onSuccess()" : "onFailure()")
                            + " callback", e);
                }
            }

            @Override
            public String toString() {
                return DEBUG ? mDebugName : "Call with callback: " + mCallback;
            }

            @FunctionalInterface
            public interface CallExecutor {

                @WorkerThread
                void execute(IRoleControllerService service, IRoleManagerCallback callbackDelegate)
                        throws RemoteException;
            }

            private class CallbackDelegate extends IRoleManagerCallback.Stub {

                @Override
                public void onSuccess() throws RemoteException {
                    mHandler.sendMessage(PooledLambda.obtainMessage(Call::notifyCallback, Call.this,
                            true));
                }

                @Override
                public void onFailure() throws RemoteException {
                    mHandler.sendMessage(PooledLambda.obtainMessage(Call::notifyCallback, Call.this,
                            false));
                }
            }
        }
    }
}
