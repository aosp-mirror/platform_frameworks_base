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

package com.android.server.appprediction;

import static android.Manifest.permission.MANAGE_APP_PREDICTIONS;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;
import static android.content.Context.APP_PREDICTION_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.app.prediction.IPredictionManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.util.function.Consumer;

/**
 * A service used to predict app and shortcut usage.
 *
 * <p>The data collected by this service can be analyzed and combined with other sources to provide
 * predictions in different areas of the system such as Launcher and Share sheet.
 */
public class AppPredictionManagerService extends
        AbstractMasterSystemService<AppPredictionManagerService, AppPredictionPerUserService> {

    private static final String TAG = AppPredictionManagerService.class.getSimpleName();

    private static final int MAX_TEMP_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes

    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public AppPredictionManagerService(Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                com.android.internal.R.string.config_defaultAppPredictionService), null,
                PACKAGE_UPDATE_POLICY_NO_REFRESH | PACKAGE_RESTART_POLICY_NO_REFRESH);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override
    protected AppPredictionPerUserService newServiceLocked(int resolvedUserId, boolean disabled) {
        return new AppPredictionPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        publishBinderService(APP_PREDICTION_SERVICE, new PredictionManagerServiceStub());
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_APP_PREDICTIONS, TAG);
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        final AppPredictionPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatedLocked();
        }
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        final AppPredictionPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageRestartedLocked();
        }
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    private class PredictionManagerServiceStub extends IPredictionManager.Stub {

        @Override
        public void createPredictionSession(@NonNull AppPredictionContext context,
                @NonNull AppPredictionSessionId sessionId) {
            runForUserLocked("createPredictionSession",
                    (service) -> service.onCreatePredictionSessionLocked(context, sessionId));
        }

        @Override
        public void notifyAppTargetEvent(@NonNull AppPredictionSessionId sessionId,
                @NonNull AppTargetEvent event) {
            runForUserLocked("notifyAppTargetEvent",
                    (service) -> service.notifyAppTargetEventLocked(sessionId, event));
        }

        @Override
        public void notifyLaunchLocationShown(@NonNull AppPredictionSessionId sessionId,
                @NonNull String launchLocation, @NonNull ParceledListSlice targetIds) {
            runForUserLocked("notifyLaunchLocationShown", (service) ->
                    service.notifyLaunchLocationShownLocked(sessionId, launchLocation, targetIds));
        }

        @Override
        public void sortAppTargets(@NonNull AppPredictionSessionId sessionId,
                @NonNull ParceledListSlice targets,
                IPredictionCallback callback) {
            runForUserLocked("sortAppTargets",
                    (service) -> service.sortAppTargetsLocked(sessionId, targets, callback));
        }

        @Override
        public void registerPredictionUpdates(@NonNull AppPredictionSessionId sessionId,
                @NonNull IPredictionCallback callback) {
            runForUserLocked("registerPredictionUpdates",
                    (service) -> service.registerPredictionUpdatesLocked(sessionId, callback));
        }

        public void unregisterPredictionUpdates(@NonNull AppPredictionSessionId sessionId,
                @NonNull IPredictionCallback callback) {
            runForUserLocked("unregisterPredictionUpdates",
                    (service) -> service.unregisterPredictionUpdatesLocked(sessionId, callback));
        }

        @Override
        public void requestPredictionUpdate(@NonNull AppPredictionSessionId sessionId) {
            runForUserLocked("requestPredictionUpdate",
                    (service) -> service.requestPredictionUpdateLocked(sessionId));
        }

        @Override
        public void onDestroyPredictionSession(@NonNull AppPredictionSessionId sessionId) {
            runForUserLocked("onDestroyPredictionSession",
                    (service) -> service.onDestroyPredictionSessionLocked(sessionId));
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new AppPredictionManagerServiceShellCommand(AppPredictionManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        private void runForUserLocked(@NonNull String func,
                @NonNull Consumer<AppPredictionPerUserService> c) {
            final int userId = UserHandle.getCallingUserId();

            Context ctx = getContext();
            if (!(ctx.checkCallingPermission(PACKAGE_USAGE_STATS) == PERMISSION_GRANTED
                    || mServiceNameResolver.isTemporary(userId)
                    || mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid()))) {

                String msg = "Permission Denial: " + func + " from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " expected caller to hold PACKAGE_USAGE_STATS permission";
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            long origId = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    final AppPredictionPerUserService service = getServiceForUserLocked(userId);
                    c.accept(service);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }
}
