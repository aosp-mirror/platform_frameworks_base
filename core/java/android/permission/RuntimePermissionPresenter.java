/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.permission;

import static android.permission.RuntimePermissionPresenterService.SERVICE_INTERFACE;

import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;
import com.android.internal.infra.AbstractRemoteService;

import java.util.Collections;
import java.util.List;

/**
 * This class provides information about runtime permissions for a specific
 * app or all apps. This information is dedicated for presentation purposes
 * and does not necessarily reflect the individual permissions requested/
 * granted to an app as the platform may be grouping permissions to improve
 * presentation and help the user make an informed choice. For example, all
 * runtime permissions in the same permission group may be presented as a
 * single permission in the UI.
 *
 * @hide
 */
public final class RuntimePermissionPresenter {
    private static final String TAG = "RuntimePermPresenter";

    /**
     * The key for retrieving the result from the returned bundle.
     *
     * @hide
     */
    public static final String KEY_RESULT =
            "android.permission.RuntimePermissionPresenter.key.result";

    /**
     * Listener for delivering the result of {@link #getAppPermissions}.
     */
    public interface OnGetAppPermissionResultCallback {
        /**
         * The result for {@link #getAppPermissions(String, OnGetAppPermissionResultCallback,
         * Handler)}.
         *
         * @param permissions The permissions list.
         */
        void onGetAppPermissions(@NonNull List<RuntimePermissionPresentationInfo> permissions);
    }

    /**
     * Listener for delivering the result of {@link #countPermissionApps}.
     */
    public interface OnCountPermissionAppsResultCallback {
        /**
         * The result for {@link #countPermissionApps(List, boolean, boolean,
         * OnCountPermissionAppsResultCallback, Handler)}.
         *
         * @param numApps The number of apps that have one of the permissions
         */
        void onCountPermissionApps(int numApps);
    }

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static RuntimePermissionPresenter sInstance;

    private final RemoteService mRemoteService;

    /**
     * Gets the singleton runtime permission presenter.
     *
     * @param context Context for accessing resources.
     * @return The singleton instance.
     */
    public static RuntimePermissionPresenter getInstance(@NonNull Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new RuntimePermissionPresenter(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private RuntimePermissionPresenter(@NonNull Context context) {
        Intent intent = new Intent(SERVICE_INTERFACE);
        intent.setPackage(context.getPackageManager().getPermissionControllerPackageName());
        ResolveInfo serviceInfo = context.getPackageManager().resolveService(intent, 0);

        mRemoteService = new RemoteService(context,
                serviceInfo.getComponentInfo().getComponentName());
    }

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     * @param callback Callback to receive the result.
     * @param handler Handler on which to invoke the callback.
     */
    public void getAppPermissions(@NonNull String packageName,
            @NonNull OnGetAppPermissionResultCallback callback, @Nullable Handler handler) {
        checkNotNull(packageName);
        checkNotNull(callback);

        mRemoteService.scheduleRequest(new PendingGetAppPermissionRequest(mRemoteService,
                packageName, callback, handler == null ? mRemoteService.getHandler() : handler));
    }

    /**
     * Revoke the permission {@code permissionName} for app {@code packageName}
     *
     * @param packageName The package for which to revoke
     * @param permissionName The permission to revoke
     */
    public void revokeRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName) {
        checkNotNull(packageName);
        checkNotNull(permissionName);

        mRemoteService.scheduleAsyncRequest(new PendingRevokeAppPermissionRequest(packageName,
                permissionName));
    }

    /**
     * Count how many apps have one of a set of permissions.
     *
     * @param permissionNames The permissions the app might have
     * @param countOnlyGranted Count an app only if the permission is granted to the app
     * @param countSystem Also count system apps
     * @param callback Callback to receive the result
     * @param handler Handler on which to invoke the callback
     */
    public void countPermissionApps(@NonNull List<String> permissionNames,
            boolean countOnlyGranted, boolean countSystem,
            @NonNull OnCountPermissionAppsResultCallback callback, @Nullable Handler handler) {
        checkCollectionElementsNotNull(permissionNames, "permissionNames");
        checkNotNull(callback);

        mRemoteService.scheduleRequest(new PendingCountPermissionAppsRequest(mRemoteService,
                permissionNames, countOnlyGranted, countSystem, callback,
                handler == null ? mRemoteService.getHandler() : handler));
    }

    /**
     * A connection to the remote service
     */
    static final class RemoteService extends
            AbstractMultiplePendingRequestsRemoteService<RemoteService,
                    IRuntimePermissionPresenter>  {
        private static final long UNBIND_TIMEOUT_MILLIS = 10000;
        private static final long MESSAGE_TIMEOUT_MILLIS = 30000;

        /**
         * Create a connection to the remote service
         *
         * @param context A context to use
         * @param componentName The component of the service to connect to
         */
        RemoteService(@NonNull Context context, @NonNull ComponentName componentName) {
            super(context, SERVICE_INTERFACE, componentName, UserHandle.myUserId(),
                    service -> Log.e(TAG, "RuntimePermPresenterService " + service + " died"),
                    false, false, 1);
        }

        /**
         * @return The default handler used by this service.
         */
        Handler getHandler() {
            return mHandler;
        }

        @Override
        protected @NonNull IRuntimePermissionPresenter getServiceInterface(
                @NonNull IBinder binder) {
            return IRuntimePermissionPresenter.Stub.asInterface(binder);
        }

        @Override
        protected long getTimeoutIdleBindMillis() {
            return UNBIND_TIMEOUT_MILLIS;
        }

        @Override
        protected long getRemoteRequestMillis() {
            return MESSAGE_TIMEOUT_MILLIS;
        }

        @Override
        public void scheduleRequest(@NonNull PendingRequest<RemoteService,
                IRuntimePermissionPresenter> pendingRequest) {
            super.scheduleRequest(pendingRequest);
        }

        @Override
        public void scheduleAsyncRequest(
                @NonNull AsyncRequest<IRuntimePermissionPresenter> request) {
            super.scheduleAsyncRequest(request);
        }
    }

    /**
     * Request for {@link #getAppPermissions}
     */
    private static final class PendingGetAppPermissionRequest extends
            AbstractRemoteService.PendingRequest<RemoteService, IRuntimePermissionPresenter> {
        private final @NonNull String mPackageName;
        private final @NonNull OnGetAppPermissionResultCallback mCallback;

        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingGetAppPermissionRequest(@NonNull RemoteService service,
                @NonNull String packageName, @NonNull OnGetAppPermissionResultCallback callback,
                @NonNull Handler handler) {
            super(service);

            mPackageName = packageName;
            mCallback = callback;

            mRemoteCallback = new RemoteCallback(result -> {
                final List<RuntimePermissionPresentationInfo> reportedPermissions;
                List<RuntimePermissionPresentationInfo> permissions = null;
                if (result != null) {
                    permissions = result.getParcelableArrayList(KEY_RESULT);
                }
                if (permissions == null) {
                    permissions = Collections.emptyList();
                }
                reportedPermissions = permissions;

                callback.onGetAppPermissions(reportedPermissions);

                finish();
            }, handler);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            mCallback.onGetAppPermissions(Collections.emptyList());
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().getAppPermissions(mPackageName, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error getting app permission", e);
            }
        }
    }

    /**
     * Request for {@link #revokeRuntimePermission}
     */
    private static final class PendingRevokeAppPermissionRequest
            implements AbstractRemoteService.AsyncRequest<IRuntimePermissionPresenter> {
        private final @NonNull String mPackageName;
        private final @NonNull String mPermissionName;

        private PendingRevokeAppPermissionRequest(@NonNull String packageName,
                @NonNull String permissionName) {
            mPackageName = packageName;
            mPermissionName = permissionName;
        }

        @Override
        public void run(IRuntimePermissionPresenter remoteInterface) {
            try {
                remoteInterface.revokeRuntimePermission(mPackageName, mPermissionName);
            } catch (RemoteException e) {
                Log.e(TAG, "Error revoking app permission", e);
            }
        }
    }

    /**
     * Request for {@link #countPermissionApps}
     */
    private static final class PendingCountPermissionAppsRequest extends
            AbstractRemoteService.PendingRequest<RemoteService, IRuntimePermissionPresenter> {
        private final @NonNull List<String> mPermissionNames;
        private final @NonNull OnCountPermissionAppsResultCallback mCallback;
        private final boolean mCountOnlyGranted;
        private final boolean mCountSystem;

        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingCountPermissionAppsRequest(@NonNull RemoteService service,
                @NonNull List<String> permissionNames, boolean countOnlyGranted,
                boolean countSystem, @NonNull OnCountPermissionAppsResultCallback callback,
                @NonNull Handler handler) {
            super(service);

            mPermissionNames = permissionNames;
            mCountOnlyGranted = countOnlyGranted;
            mCountSystem = countSystem;
            mCallback = callback;

            mRemoteCallback = new RemoteCallback(result -> {
                final int numApps;
                if (result != null) {
                    numApps = result.getInt(KEY_RESULT);
                } else {
                    numApps = 0;
                }

                callback.onCountPermissionApps(numApps);

                finish();
            }, handler);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            mCallback.onCountPermissionApps(0);
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().countPermissionApps(mPermissionNames,
                        mCountOnlyGranted, mCountSystem, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error counting permission apps", e);
            }
        }
    }
}
