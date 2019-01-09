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

/**
 * UpdateEngine handles calls to the update engine which takes care of A/B OTA
 * updates. It wraps up the update engine Binder APIs and exposes them as
 * SystemApis, which will be called by the system app responsible for OTAs.
 * On a Google device, this will be GmsCore.
 *
 * The minimal flow is:
 * <ol>
 * <li>Create a new UpdateEngine instance.
 * <li>Call {@link #bind}, optionally providing callbacks.
 * <li>Call {@link #applyPayload}.
 * </ol>
 *
 * In addition, methods are provided to {@link #cancel} or
 * {@link #suspend}/{@link #resume} application of an update.
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
        public static final int PAYLOAD_TIMESTAMP_ERROR = 51;
        public static final int UPDATED_BUT_NOT_ACTIVE = 52;
    }

    /**
     * Update status code from the update engine. Values must agree with the
     * ones in system/update_engine/client_library/include/update_engine/update_status.h.
     */
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
    private IUpdateEngineCallback mUpdateEngineCallback = null;
    private final Object mUpdateEngineCallbackLock = new Object();

    /**
     * Creates a new instance.
     */
    public UpdateEngine() {
        mUpdateEngine = IUpdateEngine.Stub.asInterface(
                ServiceManager.getService(UPDATE_ENGINE_SERVICE));
    }

    /**
     * Prepares this instance for use. The callback will be notified on any
     * status change, and when the update completes. A handler can be supplied
     * to control which thread runs the callback, or null.
     */
    public boolean bind(final UpdateEngineCallback callback, final Handler handler) {
        synchronized (mUpdateEngineCallbackLock) {
            mUpdateEngineCallback = new IUpdateEngineCallback.Stub() {
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
                return mUpdateEngine.bind(mUpdateEngineCallback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Equivalent to {@code bind(callback, null)}.
     */
    public boolean bind(final UpdateEngineCallback callback) {
        return bind(callback, null);
    }

    /**
     * Applies the payload found at the given {@code url}. For non-streaming
     * updates, the URL can be a local file using the {@code file://} scheme.
     *
     * <p>The {@code offset} and {@code size} parameters specify the location
     * of the payload within the file represented by the URL. This is useful
     * if the downloadable package at the URL contains more than just the
     * update_engine payload (such as extra metadata). This is true for
     * Google's OTA system, where the URL points to a zip file in which the
     * payload is stored uncompressed within the zip file alongside other
     * data.
     *
     * <p>The {@code headerKeyValuePairs} parameter is used to pass metadata
     * to update_engine. In Google's implementation, this is stored as
     * {@code payload_properties.txt} in the zip file. It's generated by the
     * script {@code system/update_engine/scripts/brillo_update_payload}.
     * The complete list of keys and their documentation is in
     * {@code system/update_engine/common/constants.cc}, but an example
     * might be:
     * <pre>
     * String[] pairs = {
     *   "FILE_HASH=lURPCIkIAjtMOyB/EjQcl8zDzqtD6Ta3tJef6G/+z2k=",
     *   "FILE_SIZE=871903868",
     *   "METADATA_HASH=tBvj43QOB0Jn++JojcpVdbRLz0qdAuL+uTkSy7hokaw=",
     *   "METADATA_SIZE=70604"
     * };
     * </pre>
     */
    public void applyPayload(String url, long offset, long size, String[] headerKeyValuePairs) {
        try {
            mUpdateEngine.applyPayload(url, offset, size, headerKeyValuePairs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Permanently cancels an in-progress update.
     *
     * <p>See {@link #resetStatus} to undo a finshed update (only available
     * before the updated system has been rebooted).
     *
     * <p>See {@link #suspend} for a way to temporarily stop an in-progress
     * update with the ability to resume it later.
     */
    public void cancel() {
        try {
            mUpdateEngine.cancel();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Suspends an in-progress update. This can be undone by calling
     * {@link #resume}.
     */
    public void suspend() {
        try {
            mUpdateEngine.suspend();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resumes a suspended update.
     */
    public void resume() {
        try {
            mUpdateEngine.resume();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the bootable flag on the non-current partition and all internal
     * update_engine state. This can be used after an unwanted payload has been
     * successfully applied and the device has not yet been rebooted to signal
     * that we no longer want to boot into that updated system. After this call
     * completes, update_engine will no longer report
     * {@code UPDATED_NEED_REBOOT}, so your callback can remove any outstanding
     * notification that rebooting into the new system is possible.
     */
    public void resetStatus() {
        try {
            mUpdateEngine.resetStatus();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unbinds the last bound callback function.
     */
    public boolean unbind() {
        synchronized (mUpdateEngineCallbackLock) {
            if (mUpdateEngineCallback == null) {
                return true;
            }
            try {
                boolean result = mUpdateEngine.unbind(mUpdateEngineCallback);
                mUpdateEngineCallback = null;
                return result;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Verifies that a payload associated with the given payload metadata
     * {@code payloadMetadataFilename} can be safely applied to ths device.
     * Returns {@code true} if the update can successfully be applied and
     * returns {@code false} otherwise.
     *
     * @param payloadMetadataFilename the location of the metadata without the
     * {@code file://} prefix.
     */
    public boolean verifyPayloadMetadata(String payloadMetadataFilename) {
        try {
            return mUpdateEngine.verifyPayloadApplicable(payloadMetadataFilename);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
