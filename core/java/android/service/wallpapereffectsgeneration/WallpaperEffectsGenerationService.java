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

package android.service.wallpapereffectsgeneration;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.wallpapereffectsgeneration.CinematicEffectRequest;
import android.app.wallpapereffectsgeneration.CinematicEffectResponse;
import android.app.wallpapereffectsgeneration.IWallpaperEffectsGenerationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Slog;

/**
 * A service for handling wallpaper effects generation tasks. It must implement
 * (onGenerateCinematicEffect} method to generate response and call returnCinematicEffectResponse
 * to send the response.
 *
 * <p>To extend this service, you must declare the service in your manifest file with the
 * {@link android.Manifest.permission#BIND_WALLPAPER_EFFECTS_GENERATION} permission and includes
 * an intent filter with the {@link #SERVICE_INTERFACE} action. For example: </p>
 * <pre>
 *     <application>
 *         <service android:name=".CtsWallpaperEffectsGenerationService"
 *             android:exported="true"
 *             android:label="CtsWallpaperEffectsGenerationService"
 *             android:permission="android.permission.BIND_WALLPAPER_EFFECTS_GENERATION_SERVICE">
 *             <intent-filter>
 *                 <action android:name="android.service.wallpapereffectsgeneration.WallpaperEffectsGenerationService"
 />
 *             </intent-filter>
 *         </service>
 *         <uses-library android:name="android.test.runner"/>
 *     </application>
 * </pre>
 *
 * @hide
 */
@SystemApi
public abstract class WallpaperEffectsGenerationService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>The service must also require the
     * {@link android.permission#MANAGE_WALLPAPER_EFFECTS_GENERATION}
     * permission.
     *
     */
    public static final String SERVICE_INTERFACE =
            "android.service.wallpapereffectsgeneration.WallpaperEffectsGenerationService";
    private static final boolean DEBUG = false;
    private static final String TAG = "WallpaperEffectsGenerationService";
    private Handler mHandler;
    private IWallpaperEffectsGenerationManager mService;

    private final IWallpaperEffectsGenerationService  mInterface =
            new IWallpaperEffectsGenerationService.Stub() {
                @Override
                public void onGenerateCinematicEffect(CinematicEffectRequest request) {
                    mHandler.sendMessage(
                            obtainMessage(
                                    WallpaperEffectsGenerationService::onGenerateCinematicEffect,
                                    WallpaperEffectsGenerationService.this, request));
                }
            };

    /**
     * Called when the OS receives a request for generating cinematic effect. On receiving the
     * request, it extract cinematic information from the input and call
     * {@link #returnCinematicEffectResponse} with the textured mesh
     * and metadata wrapped in CinematicEffectResponse.
     *
     * @param request the cinematic effect request passed from the client.
     */
    @MainThread
    public abstract void onGenerateCinematicEffect(@NonNull CinematicEffectRequest request);

    /**
     * Returns the cinematic effect response. Must be called when cinematic effect
     * response is generated and ready to be sent back. Otherwise the response won't be
     * returned.
     *
     * @param response the cinematic effect response returned from service provider.
     */
    public final void returnCinematicEffectResponse(@NonNull CinematicEffectResponse response) {
        try {
            mService.returnCinematicEffectResponse(response);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) {
            Log.d(TAG, "onCreate WallpaperEffectsGenerationService");
        }
        mHandler = new Handler(Looper.getMainLooper(), null, true);
        IBinder b = ServiceManager.getService(Context.WALLPAPER_EFFECTS_GENERATION_SERVICE);
        mService = IWallpaperEffectsGenerationManager.Stub.asInterface(b);
    }

    @NonNull
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onBind WallpaperEffectsGenerationService");
        }
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Slog.w(TAG,
                "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }
}
