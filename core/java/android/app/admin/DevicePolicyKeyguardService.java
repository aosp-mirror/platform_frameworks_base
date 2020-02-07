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

import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControlViewHost;

/**
 * Client interface for providing the SystemUI with secondary lockscreen information.
 *
 * <p>An implementation must be provided by the device admin app when
 * {@link DevicePolicyManager#setSecondaryLockscreenEnabled} is set to true and the service must be
 * declared in the manifest as handling the action
 * {@link DevicePolicyManager#ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE}, otherwise the keyguard
 * will fail to bind to the service and continue to unlock.
 *
 * @see DevicePolicyManager#setSecondaryLockscreenEnabled
 */
public class DevicePolicyKeyguardService extends Service {
    private static final String TAG = "DevicePolicyKeyguardService";
    private IKeyguardCallback mCallback;

    private final IKeyguardClient mClient = new IKeyguardClient.Stub() {
        @Override
        public void onSurfaceReady(@Nullable IBinder hostInputToken, IKeyguardCallback callback) {
            mCallback = callback;
            SurfaceControlViewHost.SurfacePackage surfacePackage =
                    DevicePolicyKeyguardService.this.onSurfaceReady(hostInputToken);

            if (mCallback != null) {
                try {
                    mCallback.onRemoteContentReady(surfacePackage);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to return created SurfacePackage", e);
                }
            }
        }
    };

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        return mClient.asBinder();
    }

    /**
     * Called by keyguard once the host surface for the secondary lockscreen is ready to display
     * remote content.
     * @return the {@link SurfaceControlViewHost.SurfacePackage} for the Surface the
     *      secondary lockscreen content is attached to.
     */
    @Nullable
    public SurfaceControlViewHost.SurfacePackage onSurfaceReady(@Nullable IBinder hostInputToken) {
        return null;
    }

    /**
     * Signals to keyguard that the secondary lock screen is ready to be dismissed.
     */
    @Nullable
    public void dismiss() {
        try {
            mCallback.onDismiss();
        } catch (RemoteException e) {
            Log.e(TAG, "onDismiss failed", e);
        }
    }
}
