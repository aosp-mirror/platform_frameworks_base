/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os;

import android.annotation.SystemApi;
import android.os.IUpdateEngine;
import android.os.IUpdateEngineCallback;
import android.os.RemoteException;

import android.util.Log;

/**
 * UpdateEngine handles calls to the update engine which takes care of A/B OTA
 * updates. It wraps up the update engine Binder APIs and exposes them as
 * SystemApis, which will be called by system apps like GmsCore.
 *
 * The APIs defined in this class and UpdateEngineCallback class must be in
 * sync with the ones in
 * system/update_engine/binder_bindings/android/os/IUpdateEngine.aidl and
 * system/update_engine/binder_bindings/android/os/IUpdateEngineCallback.aidl.
 *
 * {@hide}
 */
@SystemApi
public class UpdateEngine {
    private static final String TAG = "UpdateEngine";

    private static final String UPDATE_ENGINE_SERVICE = "android.os.UpdateEngineService";

    /**
     * Error code from the update engine. Values must agree with the ones in
     * system/update_engine/common/error_code.h.
     */
    @SystemApi
    public static final class ErrorCodeConstants {
        public static final int SUCCESS = 0;
        public static final int ERROR = 1;
        public static final int FILESYSTEM_COPIER_ERROR = 4;
        public static final int POST_INSTALL_RUNNER_ERROR = 5;
        public static final int PAYLOAD_MISMATCHED_TYPE_ERROR = 6;
        public static final int INSTALL_DEVICE_OPEN_ERROR = 7;
        public static final int KERNEL_DEVICE_OPEN_ERROR = 8;
        public static final int DOWNLOAD_TRANSFER_ERROR = 9;
        public static final int PAYLOAD_HASH_MISMATCH_ERROR = 10;
        public static final int PAYLOAD_SIZE_MISMATCH_ERROR = 11;
        public static final int DOWNLOAD_PAYLOAD_VERIFICATION_ERROR = 12;
    }

    /**
     * Update status code from the update engine. Values must agree with the
     * ones in system/update_engine/client_library/include/update_engine/update_status.h.
     */
    @SystemApi
    public static final class UpdateStatusConstants {
        public static final int IDLE = 0;
        public static final int CHECKING_FOR_UPDATE = 1;
        public static final int UPDATE_AVAILABLE = 2;
        public static final int DOWNLOADING = 3;
        public static final int VERIFYING = 4;
        public static final int FINALIZING = 5;
        public static final int UPDATED_NEED_REBOOT = 6;
        public static final int REPORTING_ERROR_EVENT = 7;
        public static final int ATTEMPTING_ROLLBACK = 8;
        public static final int DISABLED = 9;
    }

    private IUpdateEngine mUpdateEngine;

    @SystemApi
    public UpdateEngine() {
        mUpdateEngine = IUpdateEngine.Stub.asInterface(
                ServiceManager.getService(UPDATE_ENGINE_SERVICE));
    }

    @SystemApi
    public boolean bind(final UpdateEngineCallback callback, final Handler handler) {
        IUpdateEngineCallback updateEngineCallback = new IUpdateEngineCallback.Stub() {
            @Override
            public void onStatusUpdate(final int status, final float percent) {
                if (handler != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onStatusUpdate(status, percent);
                        }
                    });
                } else {
                    callback.onStatusUpdate(status, percent);
                }
            }

            @Override
            public void onPayloadApplicationComplete(final int errorCode) {
                if (handler != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onPayloadApplicationComplete(errorCode);
                        }
                    });
                } else {
                    callback.onPayloadApplicationComplete(errorCode);
                }
            }
        };

        try {
            return mUpdateEngine.bind(updateEngineCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @SystemApi
    public boolean bind(final UpdateEngineCallback callback) {
        return bind(callback, null);
    }

    @SystemApi
    public void applyPayload(String url, long offset, long size, String[] headerKeyValuePairs) {
        try {
            mUpdateEngine.applyPayload(url, offset, size, headerKeyValuePairs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @SystemApi
    public void cancel() {
        try {
            mUpdateEngine.cancel();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @SystemApi
    public void suspend() {
        try {
            mUpdateEngine.suspend();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @SystemApi
    public void resume() {
        try {
            mUpdateEngine.resume();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @SystemApi
    public void resetStatus() {
        try {
            mUpdateEngine.resetStatus();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
