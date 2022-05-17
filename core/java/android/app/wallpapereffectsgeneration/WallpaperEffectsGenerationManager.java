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

package android.app.wallpapereffectsgeneration;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import java.util.concurrent.Executor;

/**
 * A {@link WallpaperEffectsGenerationManager} is the class that passes wallpaper effects
 * generation requests to wallpaper effect generation service. For example, create a cinematic
 * and render a cinematic live wallpaper with the response.
 *
 * Usage:
 * <pre>{@code
 *      mWallpaperEffectsGenerationManager =
 *          context.getSystemService(WallpaperEffectsGenerationManager.class);
 *      mWallpaperEffectsGenerationManager.
 *          generateCinematicEffect(cinematicEffectRequest, response->{
 *              // proceed cinematic effect response.
 *          });
 * }</pre>
 *
 * @hide
 */
@SystemApi
@SystemService(Context.WALLPAPER_EFFECTS_GENERATION_SERVICE)
public final class WallpaperEffectsGenerationManager {
    /**
     * Interface for the cinematic effect listener.
     */
    public interface CinematicEffectListener {
        /**
         * Async call when the cinematic effect response is generated.
         * Client needs to check the status code of {@link CinematicEffectResponse}
         * to determine if the effect generation is successful.
         *
         * @param response The generated cinematic effect response.
         */
        void onCinematicEffectGenerated(@NonNull CinematicEffectResponse response);
    }

    private final IWallpaperEffectsGenerationManager mService;

    /** @hide */
    public WallpaperEffectsGenerationManager(
            @NonNull IWallpaperEffectsGenerationManager service) {
        mService = service;
    }

    /**
     * Execute a {@link android.app.wallpapereffectsgeneration.CinematicEffectRequest} from
     * the given parameters to the wallpaper effects generation service. After the cinematic
     * effect response is ready, the given listener is invoked by the system with the response.
     * The listener may never receive a callback if unexpected error happened when proceeding
     * request.
     *
     * @param request  request to generate cinematic effect.
     * @param executor where the listener is invoked.
     * @param listener listener invoked when the cinematic effect response is available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_WALLPAPER_EFFECTS_GENERATION)
    public void generateCinematicEffect(@NonNull CinematicEffectRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CinematicEffectListener listener) {
        try {
            mService.generateCinematicEffect(request,
                    new CinematicEffectListenerWrapper(listener, executor));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static final class CinematicEffectListenerWrapper
            extends ICinematicEffectListener.Stub {
        @NonNull
        private final CinematicEffectListener mListener;
        @NonNull
        private final Executor mExecutor;

        CinematicEffectListenerWrapper(@NonNull CinematicEffectListener listener,
                @NonNull Executor executor) {
            mListener = listener;
            mExecutor = executor;
        }

        @Override
        public void onCinematicEffectGenerated(CinematicEffectResponse response) {
            mExecutor.execute(() -> mListener.onCinematicEffectGenerated(response));
        }
    }
}
