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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.wallpapereffectsgeneration.CinematicEffectRequest;
import android.app.wallpapereffectsgeneration.CinematicEffectResponse;
import android.app.wallpapereffectsgeneration.ICinematicEffectListener;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

/**
 * Per-user instance of {@link WallpaperEffectsGenerationManagerService}.
 */
public class WallpaperEffectsGenerationPerUserService extends
        AbstractPerUserSystemService<WallpaperEffectsGenerationPerUserService,
                WallpaperEffectsGenerationManagerService> implements
        RemoteWallpaperEffectsGenerationService.RemoteWallpaperEffectsGenerationServiceCallback {

    private static final String TAG =
            WallpaperEffectsGenerationPerUserService.class.getSimpleName();

    @GuardedBy("mLock")
    private CinematicEffectListenerWrapper mCinematicEffectListenerWrapper;

    @Nullable
    @GuardedBy("mLock")
    private RemoteWallpaperEffectsGenerationService mRemoteService;

    protected WallpaperEffectsGenerationPerUserService(
            WallpaperEffectsGenerationManagerService master,
            Object lock, int userId) {
        super(master, lock, userId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {
        ServiceInfo si;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new NameNotFoundException("Could not get service for " + serviceComponent);
        }
        if (!Manifest.permission.BIND_WALLPAPER_EFFECTS_GENERATION_SERVICE.equals(si.permission)) {
            Slog.w(TAG, "WallpaperEffectsGenerationService from '" + si.packageName
                    + "' does not require permission "
                    + Manifest.permission.BIND_WALLPAPER_EFFECTS_GENERATION_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_WALLPAPER_EFFECTS_GENERATION_SERVICE);
        }
        return si;
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        final boolean enabledChanged = super.updateLocked(disabled);
        updateRemoteServiceLocked();
        return enabledChanged;
    }

    /**
     * Notifies the service of a new cinematic effect generation request.
     */
    @GuardedBy("mLock")
    public void onGenerateCinematicEffectLocked(
            @NonNull CinematicEffectRequest cinematicEffectRequest,
            @NonNull ICinematicEffectListener cinematicEffectListener) {
        String newTaskId = cinematicEffectRequest.getTaskId();
        // Previous request is still being processed.
        if (mCinematicEffectListenerWrapper != null) {
            if (mCinematicEffectListenerWrapper.mTaskId.equals(newTaskId)) {
                invokeCinematicListenerAndCleanup(
                        new CinematicEffectResponse.Builder(
                                CinematicEffectResponse.CINEMATIC_EFFECT_STATUS_PENDING, newTaskId)
                                .build()
                );
            } else {
                invokeCinematicListenerAndCleanup(
                        new CinematicEffectResponse.Builder(
                                CinematicEffectResponse.CINEMATIC_EFFECT_STATUS_TOO_MANY_REQUESTS,
                                newTaskId).build()
                );
            }
            return;
        }
        RemoteWallpaperEffectsGenerationService remoteService = ensureRemoteServiceLocked();
        if (remoteService != null) {
            remoteService.executeOnResolvedService(
                    s -> s.onGenerateCinematicEffect(cinematicEffectRequest));
            mCinematicEffectListenerWrapper =
                    new CinematicEffectListenerWrapper(newTaskId, cinematicEffectListener);
        } else {
            if (isDebug()) {
                Slog.d(TAG, "Remote service not found");
            }
            try {
                cinematicEffectListener.onCinematicEffectGenerated(
                        createErrorCinematicEffectResponse(newTaskId));
            } catch (RemoteException e) {
                if (isDebug()) {
                    Slog.d(TAG, "Failed to invoke cinematic effect listener for task [" + newTaskId
                            + "]");
                }
            }
        }
    }

    /**
     * Notifies the service of a generated cinematic effect response.
     */
    @GuardedBy("mLock")
    public void onReturnCinematicEffectResponseLocked(
            @NonNull CinematicEffectResponse cinematicEffectResponse) {
        invokeCinematicListenerAndCleanup(cinematicEffectResponse);
    }

    /**
     * Checks whether the calling uid matches the bind service uid.
     */
    public boolean isCallingUidAllowed(int callingUid) {
        return getServiceUidLocked() ==  callingUid;
    }

    @GuardedBy("mLock")
    private void updateRemoteServiceLocked() {
        if (mRemoteService != null) {
            mRemoteService.destroy();
            mRemoteService = null;
        }
        // End existing response and clean up listener for next request.
        if (mCinematicEffectListenerWrapper != null) {
            invokeCinematicListenerAndCleanup(
                    createErrorCinematicEffectResponse(mCinematicEffectListenerWrapper.mTaskId));
        }
    }

    void onPackageUpdatedLocked() {
        if (isDebug()) {
            Slog.v(TAG, "onPackageUpdatedLocked()");
        }
        destroyAndRebindRemoteService();
    }

    void onPackageRestartedLocked() {
        if (isDebug()) {
            Slog.v(TAG, "onPackageRestartedLocked()");
        }
        destroyAndRebindRemoteService();
    }

    private void destroyAndRebindRemoteService() {
        if (mRemoteService == null) {
            return;
        }

        if (isDebug()) {
            Slog.d(TAG, "Destroying the old remote service.");
        }
        mRemoteService.destroy();
        mRemoteService = null;
        mRemoteService = ensureRemoteServiceLocked();
        if (mRemoteService != null) {
            if (isDebug()) {
                Slog.d(TAG, "Rebinding to the new remote service.");
            }
            mRemoteService.reconnect();
        }
        // Clean up listener for next request.
        if (mCinematicEffectListenerWrapper != null) {
            invokeCinematicListenerAndCleanup(
                    createErrorCinematicEffectResponse(mCinematicEffectListenerWrapper.mTaskId));
        }
    }

    private CinematicEffectResponse createErrorCinematicEffectResponse(String taskId) {
        return new CinematicEffectResponse.Builder(
                CinematicEffectResponse.CINEMATIC_EFFECT_STATUS_ERROR,
                taskId).build();
    }

    @GuardedBy("mLock")
    private void invokeCinematicListenerAndCleanup(
            CinematicEffectResponse cinematicEffectResponse) {
        try {
            if (mCinematicEffectListenerWrapper != null
                    && mCinematicEffectListenerWrapper.mListener != null) {
                mCinematicEffectListenerWrapper.mListener.onCinematicEffectGenerated(
                        cinematicEffectResponse);
            } else {
                if (isDebug()) {
                    Slog.w(TAG, "Cinematic effect listener not found for task["
                            + mCinematicEffectListenerWrapper.mTaskId + "]");
                }
            }
        } catch (RemoteException e) {
            if (isDebug()) {
                Slog.w(TAG, "Error invoking cinematic effect listener for task["
                        + mCinematicEffectListenerWrapper.mTaskId + "]");
            }
        } finally {
            mCinematicEffectListenerWrapper = null;
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteWallpaperEffectsGenerationService ensureRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "ensureRemoteServiceLocked(): not set");
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);

            mRemoteService = new RemoteWallpaperEffectsGenerationService(getContext(),
                    serviceComponent, mUserId, this,
                    mMaster.isBindInstantServiceAllowed(), mMaster.verbose);
        }

        return mRemoteService;
    }

    @Override // from RemoteWallpaperEffectsGenerationService
    public void onServiceDied(RemoteWallpaperEffectsGenerationService service) {
        Slog.w(TAG, "remote wallpaper effects generation service died");
        updateRemoteServiceLocked();
    }

    @Override // from RemoteWallpaperEffectsGenerationService
    public void onConnectedStateChanged(boolean connected) {
        if (!connected) {
            Slog.w(TAG, "remote wallpaper effects generation service disconnected");
            updateRemoteServiceLocked();
        }
    }

    private static final class CinematicEffectListenerWrapper {
        @NonNull
        private final String mTaskId;
        @NonNull
        private final ICinematicEffectListener mListener;

        CinematicEffectListenerWrapper(
                @NonNull final String taskId,
                @NonNull final ICinematicEffectListener listener) {
            mTaskId = taskId;
            mListener = listener;
        }
    }
}
