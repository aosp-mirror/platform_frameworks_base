/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.role;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;
import com.android.internal.infra.AbstractRemoteService;

/**
 * Interface for communicating with the role controller.
 *
 * @hide
 */
@SystemService(Context.ROLE_CONTROLLER_SERVICE)
public class RoleControllerManager {

    private static final String LOG_TAG = RoleControllerManager.class.getSimpleName();

    private static final Object sRemoteServicesLock = new Object();
    /**
     * Global remote services (per user) used by all {@link RoleControllerManager managers}.
     */
    @GuardedBy("sRemoteServicesLock")
    private static final SparseArray<RemoteService> sRemoteServices = new SparseArray<>();

    @NonNull
    private final RemoteService mRemoteService;

    public RoleControllerManager(@NonNull Context context, @NonNull Handler handler) {
        synchronized (sRemoteServicesLock) {
            int userId = context.getUserId();
            RemoteService remoteService = sRemoteServices.get(userId);
            if (remoteService == null) {
                Intent intent = new Intent(RoleControllerService.SERVICE_INTERFACE);
                PackageManager packageManager = context.getPackageManager();
                intent.setPackage(packageManager.getPermissionControllerPackageName());
                ResolveInfo resolveInfo = packageManager.resolveService(intent, 0);

                remoteService = new RemoteService(context.getApplicationContext(),
                        resolveInfo.getComponentInfo().getComponentName(), handler, userId);
                sRemoteServices.put(userId, remoteService);
            }
            mRemoteService = remoteService;
        }
    }

    public RoleControllerManager(@NonNull Context context) {
        this(context, context.getMainThreadHandler());
    }

    /**
     * @see RoleControllerService#onGrantDefaultRoles(RoleManagerCallback)
     */
    public void onGrantDefaultRoles(@NonNull IRoleManagerCallback callback) {
        mRemoteService.scheduleRequest(new OnGrantDefaultRolesRequest(mRemoteService, callback));
    }

    /**
     * @see RoleControllerService#onAddRoleHolder(String, String, int, RoleManagerCallback)
     */
    public void onAddRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull IRoleManagerCallback callback) {
        mRemoteService.scheduleRequest(new OnAddRoleHolderRequest(mRemoteService, roleName,
                packageName, flags, callback));
    }

    /**
     * @see RoleControllerService#onRemoveRoleHolder(String, String, int, RoleManagerCallback)
     */
    public void onRemoveRoleHolder(@NonNull String roleName, @NonNull String packageName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull IRoleManagerCallback callback) {
        mRemoteService.scheduleRequest(new OnRemoveRoleHolderRequest(mRemoteService, roleName,
                packageName, flags, callback));
    }

    /**
     * @see RoleControllerService#onClearRoleHolders(String, int, RoleManagerCallback)
     */
    public void onClearRoleHolders(@NonNull String roleName,
            @RoleManager.ManageHoldersFlags int flags, @NonNull IRoleManagerCallback callback) {
        mRemoteService.scheduleRequest(new OnClearRoleHoldersRequest(mRemoteService, roleName,
                flags, callback));
    }

    /**
     * @see RoleControllerService#onSmsKillSwitchToggled(boolean)
     */
    public void onSmsKillSwitchToggled(boolean enabled) {
        mRemoteService.scheduleAsyncRequest(new OnSmsKillSwitchToggledRequest(enabled));
    }

    /**
     * Connection to the remote service.
     */
    private static final class RemoteService extends AbstractMultiplePendingRequestsRemoteService<
            RemoteService, IRoleController> {

        private static final long UNBIND_DELAY_MILLIS = 15 * 1000;
        private static final long REQUEST_TIMEOUT_MILLIS = 15 * 1000;

        /**
         * Create a connection to the remote service
         *
         * @param context the context to use
         * @param componentName the component of the service to connect to
         * @param handler the handler for binding service and callbacks
         * @param userId the user whom remote service should be connected as
         */
        RemoteService(@NonNull Context context, @NonNull ComponentName componentName,
                @NonNull Handler handler, @UserIdInt int userId) {
            super(context, RoleControllerService.SERVICE_INTERFACE, componentName, userId,
                    service -> Log.e(LOG_TAG, "RemoteService " + service + " died"), handler, false,
                    false, 1);
        }

        /**
         * @return The default handler used by this service.
         */
        @NonNull
        public Handler getHandler() {
            return mHandler;
        }

        @Override
        protected @NonNull IRoleController getServiceInterface(@NonNull IBinder binder) {
            return IRoleController.Stub.asInterface(binder);
        }

        @Override
        protected long getTimeoutIdleBindMillis() {
            return UNBIND_DELAY_MILLIS;
        }

        @Override
        protected long getRemoteRequestMillis() {
            return REQUEST_TIMEOUT_MILLIS;
        }

        @Override
        public void scheduleRequest(
                @NonNull BasePendingRequest<RemoteService, IRoleController> pendingRequest) {
            super.scheduleRequest(pendingRequest);
        }

        @Override
        public void scheduleAsyncRequest(@NonNull AsyncRequest<IRoleController> request) {
            super.scheduleAsyncRequest(request);
        }
    }

    /**
     * Request for {@link #onGrantDefaultRoles(IRoleManagerCallback)}.
     */
    private static final class OnGrantDefaultRolesRequest
            extends AbstractRemoteService.PendingRequest<RemoteService, IRoleController> {

        @NonNull
        private final IRoleManagerCallback mCallback;

        @NonNull
        private final IRoleManagerCallback mRemoteCallback;

        private OnGrantDefaultRolesRequest(@NonNull RemoteService service,
                @NonNull IRoleManagerCallback callback) {
            super(service);

            mCallback = callback;

            mRemoteCallback = new IRoleManagerCallback.Stub() {
                @Override
                public void onSuccess() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
                @Override
                public void onFailure() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
            };
        }

        @Override
        protected void onTimeout(@NonNull RemoteService remoteService) {
            try {
                mCallback.onFailure();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onFailure() on callback", e);
            }
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().onGrantDefaultRoles(mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onGrantDefaultRoles()", e);
            }
        }
    }

    /**
     * Request for {@link #onAddRoleHolder(String, String, int, IRoleManagerCallback)}.
     */
    private static final class OnAddRoleHolderRequest
            extends AbstractRemoteService.PendingRequest<RemoteService, IRoleController> {

        @NonNull
        private final String mRoleName;
        @NonNull
        private final String mPackageName;
        @RoleManager.ManageHoldersFlags
        private final int mFlags;
        @NonNull
        private final IRoleManagerCallback mCallback;

        @NonNull
        private final IRoleManagerCallback mRemoteCallback;

        private OnAddRoleHolderRequest(@NonNull RemoteService service, @NonNull String roleName,
                @NonNull String packageName, @RoleManager.ManageHoldersFlags int flags,
                @NonNull IRoleManagerCallback callback) {
            super(service);

            mRoleName = roleName;
            mPackageName = packageName;
            mFlags = flags;
            mCallback = callback;

            mRemoteCallback = new IRoleManagerCallback.Stub() {
                @Override
                public void onSuccess() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
                @Override
                public void onFailure() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
            };
        }

        @Override
        protected void onTimeout(@NonNull RemoteService remoteService) {
            try {
                mCallback.onFailure();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onFailure() on callback", e);
            }
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().onAddRoleHolder(mRoleName, mPackageName, mFlags,
                        mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onAddRoleHolder()", e);
            }
        }
    }

    /**
     * Request for {@link #onRemoveRoleHolder(String, String, int, IRoleManagerCallback)}.
     */
    private static final class OnRemoveRoleHolderRequest
            extends AbstractRemoteService.PendingRequest<RemoteService, IRoleController> {

        @NonNull
        private final String mRoleName;
        @NonNull
        private final String mPackageName;
        @RoleManager.ManageHoldersFlags
        private final int mFlags;
        @NonNull
        private final IRoleManagerCallback mCallback;

        @NonNull
        private final IRoleManagerCallback mRemoteCallback;

        private OnRemoveRoleHolderRequest(@NonNull RemoteService service, @NonNull String roleName,
                @NonNull String packageName, @RoleManager.ManageHoldersFlags int flags,
                @NonNull IRoleManagerCallback callback) {
            super(service);

            mRoleName = roleName;
            mPackageName = packageName;
            mFlags = flags;
            mCallback = callback;

            mRemoteCallback = new IRoleManagerCallback.Stub() {
                @Override
                public void onSuccess() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
                @Override
                public void onFailure() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
            };
        }

        @Override
        protected void onTimeout(@NonNull RemoteService remoteService) {
            try {
                mCallback.onFailure();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onFailure() on callback", e);
            }
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().onRemoveRoleHolder(mRoleName, mPackageName,
                        mFlags, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onRemoveRoleHolder()", e);
            }
        }
    }

    /**
     * Request for {@link #onClearRoleHolders(String, int, IRoleManagerCallback)}.
     */
    private static final class OnClearRoleHoldersRequest
            extends AbstractRemoteService.PendingRequest<RemoteService, IRoleController> {

        @NonNull
        private final String mRoleName;
        @RoleManager.ManageHoldersFlags
        private final int mFlags;
        @NonNull
        private final IRoleManagerCallback mCallback;

        @NonNull
        private final IRoleManagerCallback mRemoteCallback;

        private OnClearRoleHoldersRequest(@NonNull RemoteService service, @NonNull String roleName,
                @RoleManager.ManageHoldersFlags int flags, @NonNull IRoleManagerCallback callback) {
            super(service);

            mRoleName = roleName;
            mFlags = flags;
            mCallback = callback;

            mRemoteCallback = new IRoleManagerCallback.Stub() {
                @Override
                public void onSuccess() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
                @Override
                public void onFailure() throws RemoteException {
                    long token = Binder.clearCallingIdentity();
                    try {
                        mCallback.onSuccess();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                        finish();
                    }
                }
            };
        }

        @Override
        protected void onTimeout(@NonNull RemoteService remoteService) {
            try {
                mCallback.onFailure();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onFailure() on callback", e);
            }
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().onClearRoleHolders(mRoleName, mFlags,
                        mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onClearRoleHolders()", e);
            }
        }
    }

    /**
     * Request for {@link #onSmsKillSwitchToggled(boolean)}
     */
    private static final class OnSmsKillSwitchToggledRequest
            implements AbstractRemoteService.AsyncRequest<IRoleController> {

        private final boolean mEnabled;

        private OnSmsKillSwitchToggledRequest(boolean enabled) {
            mEnabled = enabled;
        }

        @Override
        public void run(@NonNull IRoleController service) {
            try {
                service.onSmsKillSwitchToggled(mEnabled);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error calling onSmsKillSwitchToggled()", e);
            }
        }
    }
}
