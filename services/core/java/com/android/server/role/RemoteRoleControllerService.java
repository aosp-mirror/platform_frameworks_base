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

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.role.IRoleManagerCallback;
import android.app.role.RoleManagerCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.rolecontrollerservice.IRoleControllerService;
import android.rolecontrollerservice.RoleControllerService;
import android.util.Slog;

import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Handles connection with {@link RoleControllerService}.
 */
public class RemoteRoleControllerService {

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
        mConnection.enqueueCall(new Connection.Call(service -> service.onAddRoleHolder(roleName,
                packageName, callback), callback::onFailure));
    }

    /**
     * Remove a specific application from the holders of a role.
     *
     * @see RoleControllerService#onRemoveRoleHolder(String, String, RoleManagerCallback)
     */
    public void onRemoveRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @NonNull IRoleManagerCallback callback) {
        mConnection.enqueueCall(new Connection.Call(service -> service.onRemoveRoleHolder(roleName,
                packageName, callback), callback::onFailure));
    }

    /**
     * Remove all holders of a role.
     *
     * @see RoleControllerService#onClearRoleHolders(String, RoleManagerCallback)
     */
    public void onClearRoleHolders(@NonNull String roleName,
            @NonNull IRoleManagerCallback callback) {
        mConnection.enqueueCall(new Connection.Call(service -> service.onClearRoleHolders(roleName,
                callback), callback::onFailure));
    }

    private static final class Connection implements ServiceConnection {

        private static final long UNBIND_DELAY_MILLIS = 10000;

        @UserIdInt
        private final int mUserId;

        @NonNull
        private final Context mContext;

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

        @MainThread
        @Override
        public void onServiceConnected(@NonNull ComponentName name, @NonNull IBinder service) {
            mService = IRoleControllerService.Stub.asInterface(service);
            executePendingCalls();
        }

        @MainThread
        private void executePendingCalls() {
            while (!mPendingCalls.isEmpty()) {
                Call call = mPendingCalls.poll();
                call.execute(mService);
            }
            scheduleUnbind();
        }

        @MainThread
        @Override
        public void onServiceDisconnected(@NonNull ComponentName name) {
            mService = null;
        }

        @MainThread
        @Override
        public void onBindingDied(@NonNull ComponentName name) {
            unbind();
        }

        public void enqueueCall(@NonNull Call call) {
            Handler.getMain().post(PooledLambda.obtainRunnable(this::executeCall, call));
        }

        @MainThread
        private void executeCall(@NonNull Call call) {
            ensureBound();
            if (mService == null) {
                mPendingCalls.offer(call);
                return;
            }
            call.execute(mService);
            scheduleUnbind();
        }

        @MainThread
        private void ensureBound() {
            Handler.getMain().removeCallbacks(mUnbindRunnable);
            if (!mBound) {
                Intent intent = new Intent(RoleControllerService.SERVICE_INTERFACE);
                intent.setPackage(mContext.getPackageManager()
                        .getPermissionControllerPackageName());
                mBound = mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE,
                        UserHandle.of(mUserId));
            }
        }

        private void scheduleUnbind() {
            Handler mainHandler = Handler.getMain();
            mainHandler.removeCallbacks(mUnbindRunnable);
            mainHandler.postDelayed(mUnbindRunnable, UNBIND_DELAY_MILLIS);
        }

        @MainThread
        private void unbind() {
            if (mBound) {
                mService = null;
                mContext.unbindService(this);
                mBound = false;
            }
        }

        public static class Call {

            @NonNull
            private final FunctionalUtils.ThrowingConsumer<IRoleControllerService> mOnExecute;

            @NonNull
            private final FunctionalUtils.ThrowingRunnable mOnFailure;

            Call(@NonNull FunctionalUtils.ThrowingConsumer<IRoleControllerService> onExecute,
                    @NonNull FunctionalUtils.ThrowingRunnable onFailure) {
                mOnExecute = onExecute;
                mOnFailure = onFailure;
            }

            @MainThread
            public void execute(IRoleControllerService service) {
                try {
                    mOnExecute.acceptOrThrow(service);
                } catch (Exception e) {
                    Slog.e(LOG_TAG, "Error calling role controller service", e);
                    try {
                        mOnFailure.runOrThrow();
                    } catch (Exception e2) {
                        Slog.e(LOG_TAG, "Error reporting failure from role controller service", e2);
                    }
                }
            }
        }
    }
}
