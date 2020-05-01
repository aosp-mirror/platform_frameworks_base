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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.content.res.AssetFileDescriptor;
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
 * {@code system/update_engine/binder_bindings/android/os/IUpdateEngine.aidl}
 * and
 * {@code system/update_engine/binder_bindings/android/os/IUpdateEngineCallback.aidl}.
 *
 * {@hide}
 */
@SystemApi
public class UpdateEngine {
    private static final String TAG = "UpdateEngine";

    private static final String UPDATE_ENGINE_SERVICE = "android.os.UpdateEngineService";

    /**
     * Error codes from update engine upon finishing a call to
     * {@link applyPayload}. Values will be passed via the callback function
     * {@link UpdateEngineCallback#onPayloadApplicationComplete}. Values must
     * agree with the ones in {@code system/update_engine/common/error_code.h}.
     */
    public static final class ErrorCodeConstants {
        /**
         * Error code: a request finished successfully.
         */
        public static final int SUCCESS = 0;
        /**
         * Error code: a request failed due to a generic error.
         */
        public static final int ERROR = 1;
        /**
         * Error code: an update failed to apply due to filesystem copier
         * error.
         */
        public static final int FILESYSTEM_COPIER_ERROR = 4;
        /**
         * Error code: an update failed to apply due to an error in running
         * post-install hooks.
         */
        public static final int POST_INSTALL_RUNNER_ERROR = 5;
        /**
         * Error code: an update failed to apply due to a mismatching payload.
         *
         * <p>For example, the given payload uses a feature that's not
         * supported by the current update engine.
         */
        public static final int PAYLOAD_MISMATCHED_TYPE_ERROR = 6;
        /**
         * Error code: an update failed to apply due to an error in opening
         * devices.
         */
        public static final int INSTALL_DEVICE_OPEN_ERROR = 7;
        /**
         * Error code: an update failed to apply due to an error in opening
         * kernel device.
         */
        public static final int KERNEL_DEVICE_OPEN_ERROR = 8;
        /**
         * Error code: an update failed to apply due to an error in fetching
         * the payload.
         *
         * <p>For example, this could be a result of bad network connection
         * when streaming an update.
         */
        public static final int DOWNLOAD_TRANSFER_ERROR = 9;
        /**
         * Error code: an update failed to apply due to a mismatch in payload
         * hash.
         *
         * <p>Update engine does sanity checks for the given payload and its
         * metadata.
         */
        public static final int PAYLOAD_HASH_MISMATCH_ERROR = 10;

        /**
         * Error code: an update failed to apply due to a mismatch in payload
         * size.
         */
        public static final int PAYLOAD_SIZE_MISMATCH_ERROR = 11;

        /**
         * Error code: an update failed to apply due to failing to verify
         * payload signatures.
         */
        public static final int DOWNLOAD_PAYLOAD_VERIFICATION_ERROR = 12;

        /**
         * Error code: an update failed to apply due to a downgrade in payload
         * timestamp.
         *
         * <p>The timestamp of a build is encoded into the payload, which will
         * be enforced during install to prevent downgrading a device.
         */
        public static final int PAYLOAD_TIMESTAMP_ERROR = 51;

        /**
         * Error code: an update has been applied successfully but the new slot
         * hasn't been set to active.
         *
         * <p>It indicates a successful finish of calling {@link #applyPayload} with
         * {@code SWITCH_SLOT_ON_REBOOT=0}. See {@link #applyPayload}.
         */
        public static final int UPDATED_BUT_NOT_ACTIVE = 52;

        /**
         * Error code: there is not enough space on the device to apply the update. User should
         * be prompted to free up space and re-try the update.
         *
         * <p>See {@link UpdateEngine#allocateSpace}.
         */
        public static final int NOT_ENOUGH_SPACE = 60;

        /**
         * Error code: the device is corrupted and no further updates may be applied.
         *
         * <p>See {@link UpdateEngine#cleanupAppliedPayload}.
         */
        public static final int DEVICE_CORRUPTED = 61;
    }

    /** @hide */
    @IntDef(value = {
            ErrorCodeConstants.SUCCESS,
            ErrorCodeConstants.ERROR,
            ErrorCodeConstants.FILESYSTEM_COPIER_ERROR,
            ErrorCodeConstants.POST_INSTALL_RUNNER_ERROR,
            ErrorCodeConstants.PAYLOAD_MISMATCHED_TYPE_ERROR,
            ErrorCodeConstants.INSTALL_DEVICE_OPEN_ERROR,
            ErrorCodeConstants.KERNEL_DEVICE_OPEN_ERROR,
            ErrorCodeConstants.DOWNLOAD_TRANSFER_ERROR,
            ErrorCodeConstants.PAYLOAD_HASH_MISMATCH_ERROR,
            ErrorCodeConstants.PAYLOAD_SIZE_MISMATCH_ERROR,
            ErrorCodeConstants.DOWNLOAD_PAYLOAD_VERIFICATION_ERROR,
            ErrorCodeConstants.PAYLOAD_TIMESTAMP_ERROR,
            ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE,
            ErrorCodeConstants.NOT_ENOUGH_SPACE,
            ErrorCodeConstants.DEVICE_CORRUPTED,
    })
    public @interface ErrorCode {}

    /**
     * Status codes for update engine. Values must agree with the ones in
     * {@code system/update_engine/client_library/include/update_engine/update_status.h}.
     */
    public static final class UpdateStatusConstants {
        /**
         * Update status code: update engine is in idle state.
         */
        public static final int IDLE = 0;

        /**
         * Update status code: update engine is checking for update.
         */
        public static final int CHECKING_FOR_UPDATE = 1;

        /**
         * Update status code: an update is available.
         */
        public static final int UPDATE_AVAILABLE = 2;

        /**
         * Update status code: update engine is downloading an update.
         */
        public static final int DOWNLOADING = 3;

        /**
         * Update status code: update engine is verifying an update.
         */
        public static final int VERIFYING = 4;

        /**
         * Update status code: update engine is finalizing an update.
         */
        public static final int FINALIZING = 5;

        /**
         * Update status code: an update has been applied and is pending for
         * reboot.
         */
        public static final int UPDATED_NEED_REBOOT = 6;

        /**
         * Update status code: update engine is reporting an error event.
         */
        public static final int REPORTING_ERROR_EVENT = 7;

        /**
         * Update status code: update engine is attempting to rollback an
         * update.
         */
        public static final int ATTEMPTING_ROLLBACK = 8;

        /**
         * Update status code: update engine is in disabled state.
         */
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
     *
     * <p>The callback functions registered via {@code #bind} will be called
     * during and at the end of the payload application.
     *
     * <p>By default the newly updated slot will be set active upon
     * successfully finishing an update. Device will attempt to boot into the
     * new slot on next reboot. This behavior can be customized by specifying
     * {@code SWITCH_SLOT_ON_REBOOT=0} in {@code headerKeyValuePairs}, which
     * allows the caller to later determine a good time to boot into the new
     * slot. Calling {@code applyPayload} again with the same payload but with
     * {@code SWITCH_SLOT_ON_REBOOT=1} will do the minimal work to set the new
     * slot active, after verifying its integrity.
     */
    public void applyPayload(String url, long offset, long size, String[] headerKeyValuePairs) {
        try {
            mUpdateEngine.applyPayload(url, offset, size, headerKeyValuePairs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Applies the payload passed as AssetFileDescriptor {@code assetFd}
     * instead of using the {@code file://} scheme.
     *
     * <p>See {@link #applyPayload(String)} for {@code offset}, {@code size} and
     * {@code headerKeyValuePairs} parameters.
     */
    public void applyPayload(@NonNull AssetFileDescriptor assetFd,
            @NonNull String[] headerKeyValuePairs) {
        try {
            mUpdateEngine.applyPayloadFd(assetFd.getParcelFileDescriptor(),
                    assetFd.getStartOffset(), assetFd.getLength(), headerKeyValuePairs);
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

    /**
     * Return value of {@link #allocateSpace.}
     */
    public static final class AllocateSpaceResult {
        private @ErrorCode int mErrorCode = ErrorCodeConstants.SUCCESS;
        private long mFreeSpaceRequired = 0;
        private AllocateSpaceResult() {}
        /**
         * Error code.
         *
         * @return The following error codes:
         * <ul>
         * <li>{@link ErrorCodeConstants#SUCCESS} if space has been allocated
         *         successfully.</li>
         * <li>{@link ErrorCodeConstants#NOT_ENOUGH_SPACE} if insufficient
         *         space.</li>
         * <li>Other {@link ErrorCodeConstants} for other errors.</li>
         * </ul>
         */
        @ErrorCode
        public int getErrorCode() {
            return mErrorCode;
        }

        /**
         * Estimated total space that needs to be available on the userdata partition to apply the
         * payload (in bytes).
         *
         * <p>
         * Note that in practice, more space needs to be made available before applying the payload
         * to keep the device working.
         *
         * @return The following values:
         * <ul>
         * <li>zero if {@link #getErrorCode} returns {@link ErrorCodeConstants#SUCCESS}</li>
         * <li>non-zero if {@link #getErrorCode} returns
         * {@link ErrorCodeConstants#NOT_ENOUGH_SPACE}.
         * Value is the estimated total space required on userdata partition.</li>
         * </ul>
         * @throws IllegalStateException if {@link #getErrorCode} is not one of the above.
         *
         */
        public long getFreeSpaceRequired() {
            if (mErrorCode == ErrorCodeConstants.SUCCESS) {
                return 0;
            }
            if (mErrorCode == ErrorCodeConstants.NOT_ENOUGH_SPACE) {
                return mFreeSpaceRequired;
            }
            throw new IllegalStateException(String.format(
                    "getFreeSpaceRequired() is not available when error code is %d", mErrorCode));
        }
    }

    /**
     * Initialize partitions for a payload associated with the given payload
     * metadata {@code payloadMetadataFilename} by preallocating required space.
     *
     * <p>This function should be called after payload has been verified after
     * {@link #verifyPayloadMetadata}. This function does not verify whether
     * the given payload is applicable or not.
     *
     * <p>Implementation of {@code allocateSpace} uses
     * {@code headerKeyValuePairs} to determine whether space has been allocated
     * for a different or same payload previously. If space has been allocated
     * for a different payload before, space will be reallocated for the given
     * payload. If space has been allocated for the same payload, no actions to
     * storage devices are taken.
     *
     * <p>This function is synchronous and may take a non-trivial amount of
     * time. Callers should call this function in a background thread.
     *
     * @param payloadMetadataFilename See {@link #verifyPayloadMetadata}.
     * @param headerKeyValuePairs See {@link #applyPayload}.
     * @return See {@link AllocateSpaceResult#getErrorCode} and
     *             {@link AllocateSpaceResult#getFreeSpaceRequired}.
     */
    @WorkerThread
    @NonNull
    public AllocateSpaceResult allocateSpace(
                @NonNull String payloadMetadataFilename,
                @NonNull String[] headerKeyValuePairs) {
        AllocateSpaceResult result = new AllocateSpaceResult();
        try {
            result.mFreeSpaceRequired = mUpdateEngine.allocateSpaceForPayload(
                    payloadMetadataFilename,
                    headerKeyValuePairs);
            result.mErrorCode = result.mFreeSpaceRequired == 0
                    ? ErrorCodeConstants.SUCCESS
                    : ErrorCodeConstants.NOT_ENOUGH_SPACE;
            return result;
        } catch (ServiceSpecificException e) {
            result.mErrorCode = e.errorCode;
            result.mFreeSpaceRequired = 0;
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class CleanupAppliedPayloadCallback extends IUpdateEngineCallback.Stub {
        private int mErrorCode = ErrorCodeConstants.ERROR;
        private boolean mCompleted = false;
        private Object mLock = new Object();
        private int getResult() {
            synchronized (mLock) {
                while (!mCompleted) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                        // do nothing, just wait again.
                    }
                }
                return mErrorCode;
            }
        }

        @Override
        public void onStatusUpdate(int status, float percent) {
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            synchronized (mLock) {
                mErrorCode = errorCode;
                mCompleted = true;
                mLock.notifyAll();
            }
        }
    }

    /**
     * Cleanup files used by the previous update and free up space after the
     * device has been booted successfully into the new build.
     *
     * <p>In particular, this function waits until delta files for snapshots for
     * Virtual A/B update are merged to OS partitions, then delete these delta
     * files.
     *
     * <p>This function is synchronous and may take a non-trivial amount of
     * time. Callers should call this function in a background thread.
     *
     * <p>This function does not delete payload binaries downloaded for a
     * non-streaming OTA update.
     *
     * @return One of the following:
     * <ul>
     * <li>{@link ErrorCodeConstants#SUCCESS} if execution is successful.</li>
     * <li>{@link ErrorCodeConstants#ERROR} if a transient error has occurred.
     * The device should be able to recover after a reboot. The function should
     * be retried after the reboot.</li>
     * <li>{@link ErrorCodeConstants#DEVICE_CORRUPTED} if a permanent error is
     * encountered. Device is corrupted, and future updates must not be applied.
     * The device cannot recover without flashing and factory resets.
     * </ul>
     */
    @WorkerThread
    @ErrorCode
    public int cleanupAppliedPayload() {
        CleanupAppliedPayloadCallback callback = new CleanupAppliedPayloadCallback();
        try {
            mUpdateEngine.cleanupSuccessfulUpdate(callback);
            return callback.getResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
