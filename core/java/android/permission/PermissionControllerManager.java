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

import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
import static android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
import static android.permission.PermissionControllerService.SERVICE_INTERFACE;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkArgumentNonnegative;
import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkFlagsArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkStringNotEmpty;

import static java.lang.Math.min;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.admin.DevicePolicyManager.PermissionGrantState;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interface for communicating with the permission controller.
 *
 * @hide
 */
@TestApi
@SystemApi
@SystemService(Context.PERMISSION_CONTROLLER_SERVICE)
public final class PermissionControllerManager {
    private static final String TAG = PermissionControllerManager.class.getSimpleName();

    private static final Object sLock = new Object();

    /**
     * Global remote services (per user) used by all {@link PermissionControllerManager managers}
     */
    @GuardedBy("sLock")
    private static SparseArray<RemoteService> sRemoteServices = new SparseArray<>(1);

    /**
     * The key for retrieving the result from the returned bundle.
     *
     * @hide
     */
    public static final String KEY_RESULT =
            "android.permission.PermissionControllerManager.key.result";

    /** @hide */
    @IntDef(prefix = { "REASON_" }, value = {
            REASON_MALWARE,
            REASON_INSTALLER_POLICY_VIOLATION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {}

    /** The permissions are revoked because the apps holding the permissions are malware */
    public static final int REASON_MALWARE = 1;

    /**
     * The permissions are revoked because the apps holding the permissions violate a policy of the
     * app that installed it.
     *
     * <p>If this reason is used only permissions of apps that are installed by the caller of the
     * API can be revoked.
     */
    public static final int REASON_INSTALLER_POLICY_VIOLATION = 2;

    /** @hide */
    @IntDef(prefix = { "COUNT_" }, value = {
            COUNT_ONLY_WHEN_GRANTED,
            COUNT_WHEN_SYSTEM,
    }, flag = true)
    @Retention(RetentionPolicy.SOURCE)
    public @interface CountPermissionAppsFlag {}

    /** Count an app only if the permission is granted to the app. */
    public static final int COUNT_ONLY_WHEN_GRANTED = 1;

    /** Count and app even if it is a system app. */
    public static final int COUNT_WHEN_SYSTEM = 2;

    /**
     * Callback for delivering the result of {@link #revokeRuntimePermissions}.
     */
    public abstract static class OnRevokeRuntimePermissionsCallback {
        /**
         * The result for {@link #revokeRuntimePermissions}.
         *
         * @param revoked The actually revoked permissions as
         *                {@code Map<packageName, List<permission>>}
         */
        public abstract void onRevokeRuntimePermissions(@NonNull Map<String, List<String>> revoked);
    }

    /**
     * Callback for delivering the result of {@link #getRuntimePermissionBackup}.
     *
     * @hide
     */
    public interface OnGetRuntimePermissionBackupCallback {
        /**
         * The result for {@link #getRuntimePermissionBackup}.
         *
         * @param backup The backup file
         */
        void onGetRuntimePermissionsBackup(@NonNull byte[] backup);
    }

    /**
     * Callback for delivering the result of {@link #getAppPermissions}.
     *
     * @hide
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
     * Callback for delivering the result of {@link #countPermissionApps}.
     *
     * @hide
     */
    public interface OnCountPermissionAppsResultCallback {
        /**
         * The result for {@link #countPermissionApps(List, int,
         * OnCountPermissionAppsResultCallback, Handler)}.
         *
         * @param numApps The number of apps that have one of the permissions
         */
        void onCountPermissionApps(int numApps);
    }

    /**
     * Callback for delivering the result of {@link #getPermissionUsages}.
     *
     * @hide
     */
    public interface OnPermissionUsageResultCallback {
        /**
         * The result for {@link #getPermissionUsages}.
         *
         * @param users The users list.
         */
        void onPermissionUsageResult(@NonNull List<RuntimePermissionUsageInfo> users);
    }

    private final @NonNull Context mContext;
    private final @NonNull RemoteService mRemoteService;

    /**
     * Create a new {@link PermissionControllerManager}.
     *
     * @param context to create the manager for
     *
     * @hide
     */
    public PermissionControllerManager(@NonNull Context context) {
        synchronized (sLock) {
            RemoteService remoteService = sRemoteServices.get(context.getUserId(), null);
            if (remoteService == null) {
                Intent intent = new Intent(SERVICE_INTERFACE);
                intent.setPackage(context.getPackageManager().getPermissionControllerPackageName());
                ResolveInfo serviceInfo = context.getPackageManager().resolveService(intent, 0);

                remoteService = new RemoteService(context.getApplicationContext(),
                        serviceInfo.getComponentInfo().getComponentName(), context.getUser());
                sRemoteServices.put(context.getUserId(), remoteService);
            }

            mRemoteService = remoteService;
        }

        mContext = context;
    }

    /**
     * Revoke a set of runtime permissions for various apps.
     *
     * @param request The permissions to revoke as {@code Map<packageName, List<permission>>}
     * @param doDryRun Compute the permissions that would be revoked, but not actually revoke them
     * @param reason Why the permission should be revoked
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     */
    @RequiresPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
    public void revokeRuntimePermissions(@NonNull Map<String, List<String>> request,
            boolean doDryRun, @Reason int reason, @NonNull @CallbackExecutor Executor executor,
            @NonNull OnRevokeRuntimePermissionsCallback callback) {
        // Check input to fail immediately instead of inside the async request
        checkNotNull(executor);
        checkNotNull(callback);
        checkNotNull(request);
        for (Map.Entry<String, List<String>> appRequest : request.entrySet()) {
            checkNotNull(appRequest.getKey());
            checkCollectionElementsNotNull(appRequest.getValue(), "permissions");
        }

        // Check required permission to fail immediately instead of inside the oneway binder call
        if (mContext.checkSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
                    + " required");
        }

        mRemoteService.scheduleRequest(new PendingRevokeRuntimePermissionRequest(mRemoteService,
                request, doDryRun, reason, mContext.getPackageName(), executor, callback));
    }

    /**
     * Set the runtime permission state from a device admin.
     *
     * @param callerPackageName The package name of the admin requesting the change
     * @param packageName Package the permission belongs to
     * @param permission Permission to change
     * @param grantState State to set the permission into
     * @param executor Executor to run the {@code callback} on
     * @param callback The callback
     *
     * @hide
     */
    @RequiresPermission(allOf = {Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY},
            conditional = true)
    public void setRuntimePermissionGrantStateByDeviceAdmin(@NonNull String callerPackageName,
            @NonNull String packageName, @NonNull String permission,
            @PermissionGrantState int grantState, @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        checkStringNotEmpty(callerPackageName);
        checkStringNotEmpty(packageName);
        checkStringNotEmpty(permission);
        checkArgument(grantState == PERMISSION_GRANT_STATE_GRANTED
                || grantState == PERMISSION_GRANT_STATE_DENIED
                || grantState == PERMISSION_GRANT_STATE_DEFAULT);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.scheduleRequest(new PendingSetRuntimePermissionGrantStateByDeviceAdmin(
                mRemoteService, callerPackageName, packageName, permission, grantState, executor,
                callback));
    }

    /**
     * Create a backup of the runtime permissions.
     *
     * @param user The user to be backed up
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    public void getRuntimePermissionBackup(@NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnGetRuntimePermissionBackupCallback callback) {
        checkNotNull(user);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.scheduleRequest(new PendingGetRuntimePermissionBackup(mRemoteService,
                user, executor, callback));
    }

    /**
     * Restore a backup of the runtime permissions.
     *
     * @param backup the backup to restore. The backup is sent asynchronously, hence it should not
     *               be modified after calling this method.
     * @param user The user to be restore
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    public void restoreRuntimePermissionBackup(@NonNull byte[] backup, @NonNull UserHandle user) {
        checkNotNull(backup);
        checkNotNull(user);

        mRemoteService.scheduleAsyncRequest(
                new PendingRestoreRuntimePermissionBackup(mRemoteService, backup, user));
    }

    /**
     * Restore a backup of the runtime permissions that has been delayed.
     *
     * @param packageName The package that is ready to have it's permissions restored.
     * @param user The user to restore
     * @param executor Executor to execute the callback on
     * @param callback Is called with {@code true} iff there is still more delayed backup left
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    public void restoreDelayedRuntimePermissionBackup(@NonNull String packageName,
            @NonNull UserHandle user,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        checkNotNull(packageName);
        checkNotNull(user);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.scheduleRequest(
                new PendingRestoreDelayedRuntimePermissionBackup(mRemoteService, packageName,
                        user, executor, callback));
    }

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     * @param callback Callback to receive the result.
     * @param handler Handler on which to invoke the callback.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
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
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
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
     * @param flags Modify which apps to count. By default all non-system apps that request a
     *              permission are counted
     * @param callback Callback to receive the result
     * @param handler Handler on which to invoke the callback
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    public void countPermissionApps(@NonNull List<String> permissionNames,
            @CountPermissionAppsFlag int flags,
            @NonNull OnCountPermissionAppsResultCallback callback, @Nullable Handler handler) {
        checkCollectionElementsNotNull(permissionNames, "permissionNames");
        checkFlagsArgument(flags, COUNT_WHEN_SYSTEM | COUNT_ONLY_WHEN_GRANTED);
        checkNotNull(callback);

        mRemoteService.scheduleRequest(new PendingCountPermissionAppsRequest(mRemoteService,
                permissionNames, flags, callback,
                handler == null ? mRemoteService.getHandler() : handler));
    }

    /**
     * Count how many apps have used permissions.
     *
     * @param countSystem Also count system apps
     * @param numMillis The number of milliseconds in the past to check for uses
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.GET_RUNTIME_PERMISSIONS)
    public void getPermissionUsages(boolean countSystem, long numMillis,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPermissionUsageResultCallback callback) {
        checkArgumentNonnegative(numMillis);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.scheduleRequest(new PendingGetPermissionUsagesRequest(mRemoteService,
                countSystem, numMillis, executor, callback));
    }

    /**
     * Check whether an application is qualified for a role.
     *
     * @param roleName name of the role to check for
     * @param packageName package name of the application to check for
     * @param executor Executor on which to invoke the callback
     * @param callback Callback to receive the result
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_ROLE_HOLDERS)
    public void isApplicationQualifiedForRole(@NonNull String roleName, @NonNull String packageName,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
        checkStringNotEmpty(roleName);
        checkStringNotEmpty(packageName);
        checkNotNull(executor);
        checkNotNull(callback);

        mRemoteService.scheduleRequest(new PendingIsApplicationQualifiedForRoleRequest(
                mRemoteService, roleName, packageName, executor, callback));
    }

    /**
     * A connection to the remote service
     */
    static final class RemoteService extends
            AbstractMultiplePendingRequestsRemoteService<RemoteService, IPermissionController> {
        private static final long UNBIND_TIMEOUT_MILLIS = 10000;
        private static final long MESSAGE_TIMEOUT_MILLIS = 30000;

        /**
         * Create a connection to the remote service
         *
         * @param context A context to use
         * @param componentName The component of the service to connect to
         * @param user User the remote service should be connected as
         */
        RemoteService(@NonNull Context context, @NonNull ComponentName componentName,
                @NonNull UserHandle user) {
            super(context, SERVICE_INTERFACE, componentName, user.getIdentifier(),
                    service -> Log.e(TAG, "RemoteService " + service + " died"), false, false, 1);
        }

        /**
         * @return The default handler used by this service.
         */
        Handler getHandler() {
            return mHandler;
        }

        @Override
        protected @NonNull IPermissionController getServiceInterface(@NonNull IBinder binder) {
            return IPermissionController.Stub.asInterface(binder);
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
                IPermissionController> pendingRequest) {
            super.scheduleRequest(pendingRequest);
        }

        @Override
        public void scheduleAsyncRequest(@NonNull AsyncRequest<IPermissionController> request) {
            super.scheduleAsyncRequest(request);
        }
    }

    /**
     * Task to read a large amount of data from a remote service.
     */
    private static class FileReaderTask<Callback extends Consumer<byte[]>>
            extends AsyncTask<Void, Void, byte[]> {
        private ParcelFileDescriptor mLocalPipe;
        private ParcelFileDescriptor mRemotePipe;

        private final @NonNull Callback mCallback;

        FileReaderTask(@NonNull Callback callback) {
            mCallback = callback;
        }

        @Override
        protected void onPreExecute() {
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException e) {
                Log.e(TAG, "Could not create pipe needed to get runtime permission backup", e);
                return;
            }

            mLocalPipe = pipe[0];
            mRemotePipe = pipe[1];
        }

        /**
         * Get the file descriptor the remote service should write the data to.
         *
         * <p>Needs to be closed <u>locally</u> before the FileReader can finish.
         *
         * @return The file the data should be written to
         */
        ParcelFileDescriptor getRemotePipe() {
            return mRemotePipe;
        }

        @Override
        protected byte[] doInBackground(Void... ignored) {
            ByteArrayOutputStream combinedBuffer = new ByteArrayOutputStream();

            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(mLocalPipe)) {
                byte[] buffer = new byte[16 * 1024];

                while (!isCancelled()) {
                    int numRead = in.read(buffer);
                    if (numRead == -1) {
                        break;
                    }

                    combinedBuffer.write(buffer, 0, numRead);
                }
            } catch (IOException | NullPointerException e) {
                Log.e(TAG, "Error reading runtime permission backup", e);
                combinedBuffer.reset();
            }

            return combinedBuffer.toByteArray();
        }

        /**
         * Interrupt the reading of the data.
         *
         * <p>Needs to be called when canceling this task as it might be hung.
         */
        void interruptRead() {
            IoUtils.closeQuietly(mLocalPipe);
        }

        @Override
        protected void onCancelled() {
            onPostExecute(new byte[]{});
        }

        @Override
        protected void onPostExecute(byte[] backup) {
            IoUtils.closeQuietly(mLocalPipe);
            mCallback.accept(backup);
        }
    }

    /**
     * Task to send a large amount of data to a remote service.
     */
    private static class FileWriterTask extends AsyncTask<byte[], Void, Void> {
        private static final int CHUNK_SIZE = 4 * 1024;

        private ParcelFileDescriptor mLocalPipe;
        private ParcelFileDescriptor mRemotePipe;

        @Override
        protected void onPreExecute() {
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException e) {
                Log.e(TAG, "Could not create pipe needed to send runtime permission backup",
                        e);
                return;
            }

            mRemotePipe = pipe[0];
            mLocalPipe = pipe[1];
        }

        /**
         * Get the file descriptor the remote service should read the data from.
         *
         * @return The file the data should be read from
         */
        ParcelFileDescriptor getRemotePipe() {
            return mRemotePipe;
        }

        /**
         * Send the data to the remove service.
         *
         * @param in The data to send
         *
         * @return ignored
         */
        @Override
        protected Void doInBackground(byte[]... in) {
            byte[] buffer = in[0];
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(mLocalPipe)) {
                for (int offset = 0; offset < buffer.length; offset += CHUNK_SIZE) {
                    out.write(buffer, offset, min(CHUNK_SIZE, buffer.length - offset));
                }
            } catch (IOException | NullPointerException e) {
                Log.e(TAG, "Error sending runtime permission backup", e);
            }

            return null;
        }

        /**
         * Interrupt the send of the data.
         *
         * <p>Needs to be called when canceling this task as it might be hung.
         */
        void interruptWrite() {
            IoUtils.closeQuietly(mLocalPipe);
        }

        @Override
        protected void onCancelled() {
            onPostExecute(null);
        }

        @Override
        protected void onPostExecute(Void ignored) {
            IoUtils.closeQuietly(mLocalPipe);
        }
    }

    /**
     * Request for {@link #revokeRuntimePermissions}
     */
    private static final class PendingRevokeRuntimePermissionRequest extends
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController> {
        private final @NonNull Map<String, List<String>> mRequest;
        private final boolean mDoDryRun;
        private final int mReason;
        private final @NonNull String mCallingPackage;
        private final @NonNull Executor mExecutor;
        private final @NonNull OnRevokeRuntimePermissionsCallback mCallback;

        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingRevokeRuntimePermissionRequest(@NonNull RemoteService service,
                @NonNull Map<String, List<String>> request, boolean doDryRun,
                @Reason int reason, @NonNull String callingPackage,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnRevokeRuntimePermissionsCallback callback) {
            super(service);

            mRequest = request;
            mDoDryRun = doDryRun;
            mReason = reason;
            mCallingPackage = callingPackage;
            mExecutor = executor;
            mCallback = callback;

            mRemoteCallback = new RemoteCallback(result -> executor.execute(() -> {
                long token = Binder.clearCallingIdentity();
                try {
                    Map<String, List<String>> revoked = new ArrayMap<>();
                    try {
                        Bundle bundleizedRevoked = result.getBundle(KEY_RESULT);

                        for (String packageName : bundleizedRevoked.keySet()) {
                            Preconditions.checkNotNull(packageName);

                            ArrayList<String> permissions =
                                    bundleizedRevoked.getStringArrayList(packageName);
                            Preconditions.checkCollectionElementsNotNull(permissions,
                                    "permissions");

                            revoked.put(packageName, permissions);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Could not read result when revoking runtime permissions", e);
                    }

                    callback.onRevokeRuntimePermissions(revoked);
                } finally {
                    Binder.restoreCallingIdentity(token);

                    finish();
                }
            }), null);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.onRevokeRuntimePermissions(Collections.emptyMap()));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void run() {
            Bundle bundledizedRequest = new Bundle();
            for (Map.Entry<String, List<String>> appRequest : mRequest.entrySet()) {
                bundledizedRequest.putStringArrayList(appRequest.getKey(),
                        new ArrayList<>(appRequest.getValue()));
            }

            try {
                getService().getServiceInterface().revokeRuntimePermissions(bundledizedRequest,
                        mDoDryRun, mReason, mCallingPackage, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error revoking runtime permission", e);
            }
        }
    }

    /**
     * Request for {@link #getRuntimePermissionBackup}
     */
    private static final class PendingGetRuntimePermissionBackup extends
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController>
            implements Consumer<byte[]> {
        private final @NonNull FileReaderTask<PendingGetRuntimePermissionBackup> mBackupReader;
        private final @NonNull Executor mExecutor;
        private final @NonNull OnGetRuntimePermissionBackupCallback mCallback;
        private final @NonNull UserHandle mUser;

        private PendingGetRuntimePermissionBackup(@NonNull RemoteService service,
                @NonNull UserHandle user, @NonNull @CallbackExecutor Executor executor,
                @NonNull OnGetRuntimePermissionBackupCallback callback) {
            super(service);

            mUser = user;
            mExecutor = executor;
            mCallback = callback;

            mBackupReader = new FileReaderTask<>(this);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            mBackupReader.cancel(true);
            mBackupReader.interruptRead();
        }

        @Override
        public void run() {
            mBackupReader.execute();

            ParcelFileDescriptor remotePipe = mBackupReader.getRemotePipe();
            try {
                getService().getServiceInterface().getRuntimePermissionBackup(mUser, remotePipe);
            } catch (RemoteException e) {
                Log.e(TAG, "Error getting runtime permission backup", e);
            } finally {
                // Remote pipe end is duped by binder call. Local copy is not needed anymore
                IoUtils.closeQuietly(remotePipe);
            }
        }

        /**
         * Called when the {@link #mBackupReader} finished reading the file.
         *
         * @param backup The data read
         */
        @Override
        public void accept(byte[] backup) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onGetRuntimePermissionsBackup(backup));
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            finish();
        }
    }

    /**
     * Request for {@link #getRuntimePermissionBackup}
     */
    private static final class PendingSetRuntimePermissionGrantStateByDeviceAdmin extends
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController> {
        private final @NonNull String mCallerPackageName;
        private final @NonNull String mPackageName;
        private final @NonNull String mPermission;
        private final @PermissionGrantState int mGrantState;

        private final @NonNull Executor mExecutor;
        private final @NonNull Consumer<Boolean> mCallback;
        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingSetRuntimePermissionGrantStateByDeviceAdmin(@NonNull RemoteService service,
                @NonNull String callerPackageName, @NonNull String packageName,
                @NonNull String permission, @PermissionGrantState int grantState,
                @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
            super(service);

            mCallerPackageName = callerPackageName;
            mPackageName = packageName;
            mPermission = permission;
            mGrantState = grantState;
            mExecutor = executor;
            mCallback = callback;

            mRemoteCallback = new RemoteCallback(result -> executor.execute(() -> {
                long token = Binder.clearCallingIdentity();
                try {
                    callback.accept(result.getBoolean(KEY_RESULT, false));
                } finally {
                    Binder.restoreCallingIdentity(token);

                    finish();
                }
            }), null);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.accept(false));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().setRuntimePermissionGrantStateByDeviceAdmin(
                        mCallerPackageName, mPackageName, mPermission, mGrantState, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error setting permissions state for device admin " + mPackageName,
                        e);
            }
        }
    }

    /**
     * Request for {@link #restoreRuntimePermissionBackup}
     */
    private static final class PendingRestoreRuntimePermissionBackup implements
            AbstractRemoteService.AsyncRequest<IPermissionController> {
        private final @NonNull FileWriterTask mBackupSender;
        private final @NonNull byte[] mBackup;
        private final @NonNull UserHandle mUser;

        private PendingRestoreRuntimePermissionBackup(@NonNull RemoteService service,
                @NonNull byte[] backup, @NonNull UserHandle user) {
            mBackup = backup;
            mUser = user;

            mBackupSender = new FileWriterTask();
        }

        @Override
        public void run(@NonNull IPermissionController service) {
            mBackupSender.execute(mBackup);

            ParcelFileDescriptor remotePipe = mBackupSender.getRemotePipe();
            try {
                service.restoreRuntimePermissionBackup(mUser, remotePipe);
            } catch (RemoteException e) {
                Log.e(TAG, "Error sending runtime permission backup", e);
                mBackupSender.cancel(false);
                mBackupSender.interruptWrite();
            } finally {
                // Remote pipe end is duped by binder call. Local copy is not needed anymore
                IoUtils.closeQuietly(remotePipe);
            }
        }
    }

    /**
     * Request for {@link #restoreDelayedRuntimePermissionBackup(String, UserHandle, Executor,
     * Consumer<Boolean>)}
     */
    private static final class PendingRestoreDelayedRuntimePermissionBackup extends
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController> {
        private final @NonNull String mPackageName;
        private final @NonNull UserHandle mUser;
        private final @NonNull Executor mExecutor;
        private final @NonNull Consumer<Boolean> mCallback;

        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingRestoreDelayedRuntimePermissionBackup(@NonNull RemoteService service,
                @NonNull String packageName, @NonNull UserHandle user, @NonNull Executor executor,
                @NonNull Consumer<Boolean> callback) {
            super(service);

            mPackageName = packageName;
            mUser = user;
            mExecutor = executor;
            mCallback = callback;

            mRemoteCallback = new RemoteCallback(result -> executor.execute(() -> {
                long token = Binder.clearCallingIdentity();
                try {
                    callback.accept(result.getBoolean(KEY_RESULT, false));
                } finally {
                    Binder.restoreCallingIdentity(token);

                    finish();
                }
            }), null);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(
                        () -> mCallback.accept(true));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().restoreDelayedRuntimePermissionBackup(
                        mPackageName, mUser, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error restoring delayed permissions for " + mPackageName, e);
            }
        }
    }

    /**
     * Request for {@link #getAppPermissions}
     */
    private static final class PendingGetAppPermissionRequest extends
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController> {
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
            implements AbstractRemoteService.AsyncRequest<IPermissionController> {
        private final @NonNull String mPackageName;
        private final @NonNull String mPermissionName;

        private PendingRevokeAppPermissionRequest(@NonNull String packageName,
                @NonNull String permissionName) {
            mPackageName = packageName;
            mPermissionName = permissionName;
        }

        @Override
        public void run(IPermissionController remoteInterface) {
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
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController> {
        private final @NonNull List<String> mPermissionNames;
        private final @NonNull OnCountPermissionAppsResultCallback mCallback;
        private final @CountPermissionAppsFlag int mFlags;

        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingCountPermissionAppsRequest(@NonNull RemoteService service,
                @NonNull List<String> permissionNames, @CountPermissionAppsFlag int flags,
                @NonNull OnCountPermissionAppsResultCallback callback, @NonNull Handler handler) {
            super(service);

            mPermissionNames = permissionNames;
            mFlags = flags;
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
                        mFlags, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error counting permission apps", e);
            }
        }
    }

    /**
     * Request for {@link #getPermissionUsages}
     */
    private static final class PendingGetPermissionUsagesRequest extends
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController> {
        private final @NonNull OnPermissionUsageResultCallback mCallback;
        private final boolean mCountSystem;
        private final long mNumMillis;

        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingGetPermissionUsagesRequest(@NonNull RemoteService service,
                boolean countSystem, long numMillis, @NonNull @CallbackExecutor Executor executor,
                @NonNull OnPermissionUsageResultCallback callback) {
            super(service);

            mCountSystem = countSystem;
            mNumMillis = numMillis;
            mCallback = callback;

            mRemoteCallback = new RemoteCallback(result -> executor.execute(() -> {
                long token = Binder.clearCallingIdentity();
                try {
                    final List<RuntimePermissionUsageInfo> reportedUsers;
                    List<RuntimePermissionUsageInfo> users = null;
                    if (result != null) {
                        users = result.getParcelableArrayList(KEY_RESULT);
                    } else {
                        users = Collections.emptyList();
                    }
                    reportedUsers = users;

                    callback.onPermissionUsageResult(reportedUsers);
                } finally {
                    Binder.restoreCallingIdentity(token);

                    finish();
                }
            }), null);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            mCallback.onPermissionUsageResult(Collections.emptyList());
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().getPermissionUsages(mCountSystem, mNumMillis,
                        mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error counting permission users", e);
            }
        }
    }

    /**
     * Request for {@link #isApplicationQualifiedForRole}.
     */
    private static final class PendingIsApplicationQualifiedForRoleRequest extends
            AbstractRemoteService.PendingRequest<RemoteService, IPermissionController> {

        private final @NonNull String mRoleName;
        private final @NonNull String mPackageName;
        private final @NonNull Consumer<Boolean> mCallback;

        private final @NonNull RemoteCallback mRemoteCallback;

        private PendingIsApplicationQualifiedForRoleRequest(@NonNull RemoteService service,
                @NonNull String roleName, @NonNull String packageName,
                @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Boolean> callback) {
            super(service);

            mRoleName = roleName;
            mPackageName = packageName;
            mCallback = callback;

            mRemoteCallback = new RemoteCallback(result -> executor.execute(() -> {
                long token = Binder.clearCallingIdentity();
                try {
                    boolean qualified;
                    if (result != null) {
                        qualified = result.getBoolean(KEY_RESULT);
                    } else {
                        qualified = false;
                    }
                    callback.accept(qualified);
                } finally {
                    Binder.restoreCallingIdentity(token);
                    finish();
                }
            }), null);
        }

        @Override
        protected void onTimeout(RemoteService remoteService) {
            mCallback.accept(false);
        }

        @Override
        public void run() {
            try {
                getService().getServiceInterface().isApplicationQualifiedForRole(mRoleName,
                        mPackageName, mRemoteCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Error checking whether application qualifies for role", e);
            }
        }
    }
}
