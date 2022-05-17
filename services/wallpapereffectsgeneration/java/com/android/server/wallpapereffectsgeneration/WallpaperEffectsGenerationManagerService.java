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

package com.android.server.wallpapereffectsgeneration;

import static android.Manifest.permission.MANAGE_WALLPAPER_EFFECTS_GENERATION;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.content.Context.WALLPAPER_EFFECTS_GENERATION_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.wallpapereffectsgeneration.CinematicEffectRequest;
import android.app.wallpapereffectsgeneration.CinematicEffectResponse;
import android.app.wallpapereffectsgeneration.ICinematicEffectListener;
import android.app.wallpapereffectsgeneration.IWallpaperEffectsGenerationManager;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
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
 * A service used to return wallpaper effect given a request.
 */
public class WallpaperEffectsGenerationManagerService extends
        AbstractMasterSystemService<WallpaperEffectsGenerationManagerService,
                WallpaperEffectsGenerationPerUserService> {
    private static final String TAG =
            WallpaperEffectsGenerationManagerService.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final int MAX_TEMP_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes
    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public WallpaperEffectsGenerationManagerService(Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(context,
                        com.android.internal.R.string.config_defaultWallpaperEffectsGenerationService),
                null,
                PACKAGE_UPDATE_POLICY_NO_REFRESH | PACKAGE_RESTART_POLICY_NO_REFRESH);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override
    protected WallpaperEffectsGenerationPerUserService newServiceLocked(int resolvedUserId,
            boolean disabled) {
        return new WallpaperEffectsGenerationPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        publishBinderService(WALLPAPER_EFFECTS_GENERATION_SERVICE,
                new WallpaperEffectsGenerationManagerStub());
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_WALLPAPER_EFFECTS_GENERATION, TAG);
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        final WallpaperEffectsGenerationPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatedLocked();
        }
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        final WallpaperEffectsGenerationPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageRestartedLocked();
        }
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    private class WallpaperEffectsGenerationManagerStub
            extends IWallpaperEffectsGenerationManager.Stub {
        @Override
        public void generateCinematicEffect(@NonNull CinematicEffectRequest request,
                @NonNull ICinematicEffectListener listener) {
            if (!runForUser("generateCinematicEffect", true, (service) ->
                    service.onGenerateCinematicEffectLocked(request, listener))) {
                try {
                    listener.onCinematicEffectGenerated(
                            new CinematicEffectResponse.Builder(
                                    CinematicEffectResponse.CINEMATIC_EFFECT_STATUS_ERROR,
                                    request.getTaskId()).build());
                } catch (RemoteException e) {
                    if (DEBUG) {
                        Slog.d(TAG, "fail to invoke cinematic effect listener for task["
                                + request.getTaskId() + "]");
                    }
                }
            }
        }

        @Override
        public void returnCinematicEffectResponse(@NonNull CinematicEffectResponse response) {
            runForUser("returnCinematicResponse", false, (service) ->
                    service.onReturnCinematicEffectResponseLocked(response));
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new WallpaperEffectsGenerationManagerServiceShellCommand(
                    WallpaperEffectsGenerationManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        /**
         * Execute the operation for the user.
         *
         * @param func The name of function for logging purpose.
         * @param checkManageWallpaperEffectsPermission whether to check if caller has
         *    MANAGE_WALLPAPER_EFFECTS_GENERATION.
         *    If false, check the uid of caller matching bind service.
         * @param c WallpaperEffectsGenerationPerUserService operation.
         * @return whether WallpaperEffectsGenerationPerUserService is found.
         */
        private boolean runForUser(@NonNull final String func,
                @NonNull final boolean checkManageWallpaperEffectsPermission,
                @NonNull final Consumer<WallpaperEffectsGenerationPerUserService> c) {
            ActivityManagerInternal am = LocalServices.getService(ActivityManagerInternal.class);
            final int userId = am.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                    Binder.getCallingUserHandle().getIdentifier(), false, ALLOW_NON_FULL,
                    null, null);
            if (DEBUG) {
                Slog.d(TAG, "runForUser:" + func + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
            }
            if (checkManageWallpaperEffectsPermission) {
                // MANAGE_WALLPAPER_EFFECTS_GENERATION is required for all functions except for
                // "returnCinematicResponse", whose calling permission checked in
                // WallpaperEffectsGenerationPerUserService against remote binding.
                Context ctx = getContext();
                if (!(ctx.checkCallingPermission(MANAGE_WALLPAPER_EFFECTS_GENERATION)
                        == PERMISSION_GRANTED
                        || mServiceNameResolver.isTemporary(userId)
                        || mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid()))) {
                    String msg = "Permission Denial: Cannot call " + func + " from pid="
                            + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
            final int origCallingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            boolean accepted = false;
            try {
                synchronized (mLock) {
                    final WallpaperEffectsGenerationPerUserService service =
                            getServiceForUserLocked(userId);
                    if (service != null) {
                        // Check uid of caller matches bind service implementation if
                        // MANAGE_WALLPAPER_EFFECTS_GENERATION is skipped. This is useful
                        // for service implementation to return response.
                        if (!checkManageWallpaperEffectsPermission
                                && !service.isCallingUidAllowed(origCallingUid)) {
                            String msg = "Permission Denial: cannot call " + func + ", uid["
                                    + origCallingUid + "] doesn't match service implementation";
                            Slog.w(TAG, msg);
                            throw new SecurityException(msg);
                        }
                        accepted = true;
                        c.accept(service);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }

            return accepted;
        }
    }
}
