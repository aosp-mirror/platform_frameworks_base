/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.camera2.extension;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.AppOpsManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.camera2.CameraExtensionCharacteristics.Extension;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.camera.flags.Flags;

interface CameraUsageTracker {
    void startCameraOperation();
    void finishCameraOperation();
}

/**
 * Base service class that extension service implementations must extend.
 *
 * @hide
 */
@SystemApi
public abstract class CameraExtensionService extends Service {
    private static final String TAG = "CameraExtensionService";
    private CameraUsageTracker mCameraUsageTracker;
    private static Object mLock = new Object();

    private final class CameraTracker implements CameraUsageTracker {

        private final AppOpsManager mAppOpsService = getApplicationContext().getSystemService(
                AppOpsManager.class);
        private final String mPackageName = getPackageName();
        private final String mAttributionTag = getAttributionTag();
        private int mUid = getApplicationInfo().uid;

        @Override
        public void startCameraOperation() {
            if (mAppOpsService != null) {
                mAppOpsService.startOp(AppOpsManager.OPSTR_CAMERA, mUid, mPackageName,
                        mAttributionTag, "Camera extensions");
            }
        }

        @Override
        public void finishCameraOperation() {
            if (mAppOpsService != null) {
                mAppOpsService.finishOp(AppOpsManager.OPSTR_CAMERA, mUid, mPackageName,
                        mAttributionTag);
            }
        }
    }
    @GuardedBy("mLock")
    private static IInitializeSessionCallback mInitializeCb = null;

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            synchronized (mLock) {
                mInitializeCb = null;
            }
            if (mCameraUsageTracker != null) {
                mCameraUsageTracker.finishCameraOperation();
            }
        }
    };

    protected CameraExtensionService() { }

    @Override
    @NonNull
    public final IBinder onBind(@Nullable Intent intent) {
        if (mCameraUsageTracker == null) {
            mCameraUsageTracker = new CameraTracker();
        }
        return new CameraExtensionServiceImpl();
    }

    private class CameraExtensionServiceImpl extends ICameraExtensionsProxyService.Stub {
        @Override
        public boolean registerClient(IBinder token) throws RemoteException {
            return CameraExtensionService.this.onRegisterClient(token);
        }

        @Override
        public void unregisterClient(IBinder token) throws RemoteException {
            CameraExtensionService.this.onUnregisterClient(token);
        }

        @Override
        public boolean advancedExtensionsSupported() throws RemoteException {
            return true;
        }

        @Override
        public void initializeSession(IInitializeSessionCallback cb) {
            boolean ret = false;
            synchronized (mLock) {
                if (mInitializeCb == null) {
                    mInitializeCb = cb;
                    try {
                        mInitializeCb.asBinder().linkToDeath(mDeathRecipient, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failure to register binder death notifier!");
                    }
                    ret = true;
                }
            }

            try {
                if (ret) {
                    cb.onSuccess();
                } else {
                    cb.onFailure();
                }
            } catch (RemoteException e) {

                Log.e(TAG, "Client doesn't respond!");
            }
        }

        @Override
        public void releaseSession() {
            synchronized (mLock) {
                if (mInitializeCb != null) {
                    mInitializeCb.asBinder().unlinkToDeath(mDeathRecipient, 0);
                    mInitializeCb = null;
                }
            }
        }

        @Override
        public IPreviewExtenderImpl initializePreviewExtension(@Extension int extensionType)
                throws RemoteException {
            // Basic Extension API is not supported
            return null;
        }

        @Override
        public IImageCaptureExtenderImpl initializeImageExtension(@Extension int extensionType)
                throws RemoteException {
            // Basic Extension API is not supported
            return null;
        }

        @Override
        public IAdvancedExtenderImpl initializeAdvancedExtension(@Extension int extensionType)
                throws RemoteException {
            AdvancedExtender extender =  CameraExtensionService.this.onInitializeAdvancedExtension(
                    extensionType);
            extender.setCameraUsageTracker(mCameraUsageTracker);
            return extender.getAdvancedExtenderBinder();
        }
    }

    /**
     * Register an extension client. The client must call this method
     * after successfully binding to the service.
     *
     * @param token              Binder token that can be used for adding
     *                           death notifier in case the client exits
     *                           unexpectedly.
     * @return true if the registration is successful, false otherwise
     */
    public abstract boolean onRegisterClient(@NonNull IBinder token);

    /**
     * Unregister an extension client.
     *
     * @param token              Binder token
     */
    public abstract void onUnregisterClient(@NonNull IBinder token);

    /**
     * Initialize and return an advanced extension.
     *
     * @param extensionType {@link android.hardware.camera2.CameraExtensionCharacteristics}
     *                      extension type
     * @return Valid advanced extender of the requested type
     */
    @NonNull
    public abstract AdvancedExtender onInitializeAdvancedExtension(@Extension int extensionType);
}
