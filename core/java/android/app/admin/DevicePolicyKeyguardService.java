/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app.admin;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControlViewHost;

/**
 * Client interface for providing the SystemUI with secondary lockscreen information.
 *
 * <p>An implementation must be provided by the default configured supervision app that is set as
 * Profile Owner or Device Owner when {@link DevicePolicyManager#setSecondaryLockscreenEnabled} is
 * set to true and the service must be declared in the manifest as handling the action
 * {@link DevicePolicyManager#ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE}, otherwise the keyguard
 * will fail to bind to the service and continue to unlock.
 *
 * @see DevicePolicyManager#setSecondaryLockscreenEnabled
 * @hide
 */
@SystemApi
public class DevicePolicyKeyguardService extends Service {
    private static final String TAG = "DevicePolicyKeyguardService";
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private IKeyguardCallback mCallback;

    private final IKeyguardClient mClient = new IKeyguardClient.Stub() {
        @MainThread
        @Override
        public void onCreateKeyguardSurface(@Nullable IBinder hostInputToken,
                @NonNull IKeyguardCallback callback) {
            mCallback = callback;
            mHandler.post(() -> {
                SurfaceControlViewHost.SurfacePackage surfacePackage =
                        DevicePolicyKeyguardService.this.onCreateKeyguardSurface(hostInputToken);

                try {
                    mCallback.onRemoteContentReady(surfacePackage);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to return created SurfacePackage", e);
                }
            });
        }
    };

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        return mClient.asBinder();
    }

    /**
     * Called by keyguard once the host surface for the secondary lockscreen is created and ready to
     * display remote content.
     *
     * <p>Implementations are expected to create a Surface hierarchy with view elements for the
     * admin's desired secondary lockscreen UI, and optionally, interactive elements
     * that will allow the user to dismiss the secondary lockscreen, subject to the implementation's
     * requirements. The view hierarchy is expected to be embedded via the
     * {@link SurfaceControlViewHost} APIs, and returned as a SurfacePackage via
     * {@link SurfaceControlViewHost#getSurfacePackage}for the keyguard to reparent into its
     * prepared SurfaceView.
     *
     * @param hostInputToken Token of the SurfaceView which will hosting the embedded hierarchy,
     *                       primarily required by {@link SurfaceControlViewHost} for ANR reporting.
     *                       It will be provided by the keyguard via
     *                       {@link android.view.SurfaceView#getHostToken}.
     * @return the {@link SurfaceControlViewHost.SurfacePackage} for the Surface the
     *      secondary lockscreen content is attached to.
     */
    @Nullable
    public SurfaceControlViewHost.SurfacePackage onCreateKeyguardSurface(
            @NonNull IBinder hostInputToken) {
        return null;
    }

    /**
     * Signals to keyguard that the secondary lock screen is ready to be dismissed.
     */
    @Nullable
    public void dismiss() {
        if (mCallback == null) {
            Log.w(TAG, "KeyguardCallback was unexpectedly null");
            return;
        }
        try {
            mCallback.onDismiss();
        } catch (RemoteException e) {
            Log.e(TAG, "onDismiss failed", e);
        }
    }
}
