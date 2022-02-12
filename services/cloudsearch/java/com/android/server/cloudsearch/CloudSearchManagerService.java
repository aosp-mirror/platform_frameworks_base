/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.cloudsearch;

import static android.Manifest.permission.MANAGE_CLOUDSEARCH;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.content.Context.CLOUDSEARCH_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.cloudsearch.ICloudSearchManager;
import android.app.cloudsearch.ICloudSearchManagerCallback;
import android.app.cloudsearch.SearchRequest;
import android.app.cloudsearch.SearchResponse;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.util.function.Consumer;

/**
 * A service used to return cloudsearch targets given a query.
 */
public class CloudSearchManagerService extends
        AbstractMasterSystemService<CloudSearchManagerService, CloudSearchPerUserService> {

    private static final String TAG = CloudSearchManagerService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int MAX_TEMP_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes

    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;

    private final Context mContext;

    public CloudSearchManagerService(Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                        R.string.config_defaultCloudSearchService), null,
                PACKAGE_UPDATE_POLICY_NO_REFRESH | PACKAGE_RESTART_POLICY_NO_REFRESH);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mContext = context;
    }

    @Override
    protected CloudSearchPerUserService newServiceLocked(int resolvedUserId, boolean disabled) {
        return new CloudSearchPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        publishBinderService(CLOUDSEARCH_SERVICE, new CloudSearchManagerStub());
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_CLOUDSEARCH, TAG);
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        final CloudSearchPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatedLocked();
        }
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        final CloudSearchPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageRestartedLocked();
        }
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    private class CloudSearchManagerStub extends ICloudSearchManager.Stub {

        @Override
        public void search(@NonNull SearchRequest searchRequest,
                @NonNull ICloudSearchManagerCallback callBack) {
            searchRequest.setSource(
                    mContext.getPackageManager().getNameForUid(Binder.getCallingUid()));
            runForUserLocked("search", searchRequest.getRequestId(), (service) ->
                    service.onSearchLocked(searchRequest, callBack));
        }

        @Override
        public void returnResults(IBinder token, String requestId, SearchResponse response) {
            runForUserLocked("returnResults", requestId, (service) ->
                    service.onReturnResultsLocked(token, requestId, response));
        }

        public void destroy(@NonNull SearchRequest searchRequest) {
            runForUserLocked("destroyCloudSearchSession", searchRequest.getRequestId(),
                    (service) -> service.onDestroyLocked(searchRequest.getRequestId()));
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new CloudSearchManagerServiceShellCommand(CloudSearchManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        private void runForUserLocked(@NonNull final String func,
                @NonNull final String  requestId,
                @NonNull final Consumer<CloudSearchPerUserService> c) {
            ActivityManagerInternal am = LocalServices.getService(ActivityManagerInternal.class);
            final int userId = am.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                    Binder.getCallingUserHandle().getIdentifier(), false, ALLOW_NON_FULL,
                    null, null);

            if (DEBUG) {
                Slog.d(TAG, "runForUserLocked:" + func + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
            }
            Context ctx = getContext();
            if (!(ctx.checkCallingPermission(MANAGE_CLOUDSEARCH) == PERMISSION_GRANTED
                    || mServiceNameResolver.isTemporary(userId)
                    || mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid()))) {

                String msg = "Permission Denial: Cannot call " + func + " from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            final long origId = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    final CloudSearchPerUserService service = getServiceForUserLocked(userId);
                    c.accept(service);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }
}
