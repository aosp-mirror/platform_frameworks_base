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

package com.android.server.smartspace;

import static android.Manifest.permission.MANAGE_SMARTSPACE;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.content.Context.SMARTSPACE_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.smartspace.ISmartspaceCallback;
import android.app.smartspace.ISmartspaceManager;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.util.function.Consumer;

/**
 * A service used to return smartspace targets given a query.
 */
public class SmartspaceManagerService extends
        AbstractMasterSystemService<SmartspaceManagerService, SmartspacePerUserService> {

    private static final String TAG = SmartspaceManagerService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int MAX_TEMP_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes

    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public SmartspaceManagerService(Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                        com.android.internal.R.string.config_defaultSmartspaceService), null,
                PACKAGE_UPDATE_POLICY_NO_REFRESH | PACKAGE_RESTART_POLICY_NO_REFRESH);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override
    protected SmartspacePerUserService newServiceLocked(int resolvedUserId, boolean disabled) {
        return new SmartspacePerUserService(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        publishBinderService(SMARTSPACE_SERVICE, new SmartspaceManagerStub());
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_SMARTSPACE, TAG);
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        final SmartspacePerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatedLocked();
        }
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        final SmartspacePerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageRestartedLocked();
        }
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    private class SmartspaceManagerStub extends ISmartspaceManager.Stub {

        @Override
        public void createSmartspaceSession(@NonNull SmartspaceConfig smartspaceConfig,
                @NonNull SmartspaceSessionId sessionId, @NonNull IBinder token) {
            runForUserLocked("createSmartspaceSession", sessionId, (service) ->
                    service.onCreateSmartspaceSessionLocked(smartspaceConfig, sessionId, token));
        }

        @Override
        public void notifySmartspaceEvent(SmartspaceSessionId sessionId,
                SmartspaceTargetEvent event) {
            runForUserLocked("notifySmartspaceEvent", sessionId,
                    (service) -> service.notifySmartspaceEventLocked(sessionId, event));
        }

        @Override
        public void requestSmartspaceUpdate(SmartspaceSessionId sessionId) {
            runForUserLocked("requestSmartspaceUpdate", sessionId,
                    (service) -> service.requestSmartspaceUpdateLocked(sessionId));
        }

        @Override
        public void registerSmartspaceUpdates(@NonNull SmartspaceSessionId sessionId,
                @NonNull ISmartspaceCallback callback) {
            runForUserLocked("registerSmartspaceUpdates", sessionId,
                    (service) -> service.registerSmartspaceUpdatesLocked(sessionId, callback));
        }

        @Override
        public void unregisterSmartspaceUpdates(SmartspaceSessionId sessionId,
                ISmartspaceCallback callback) {
            runForUserLocked("unregisterSmartspaceUpdates", sessionId,
                    (service) -> service.unregisterSmartspaceUpdatesLocked(sessionId, callback));
        }

        @Override
        public void destroySmartspaceSession(@NonNull SmartspaceSessionId sessionId) {
            runForUserLocked("destroySmartspaceSession", sessionId,
                    (service) -> service.onDestroyLocked(sessionId));
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new SmartspaceManagerServiceShellCommand(SmartspaceManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        private void runForUserLocked(@NonNull final String func,
                @NonNull final SmartspaceSessionId sessionId,
                @NonNull final Consumer<SmartspacePerUserService> c) {
            ActivityManagerInternal am = LocalServices.getService(ActivityManagerInternal.class);
            final int userId = am.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                    sessionId.getUserHandle().getIdentifier(), false, ALLOW_NON_FULL, null, null);

            if (DEBUG) {
                Slog.d(TAG, "runForUserLocked:" + func + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
            }
            Context ctx = getContext();
            if (!(ctx.checkCallingPermission(MANAGE_SMARTSPACE) == PERMISSION_GRANTED
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
                    final SmartspacePerUserService service = getServiceForUserLocked(userId);
                    c.accept(service);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }
}
