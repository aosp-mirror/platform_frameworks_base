/*
 * Copyright (C) 2024 The Android Open Source Project
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

/**
 * UpdateEngineStable handles calls to the update engine stalbe which takes care of A/B OTA updates.
 * This interface has lesser functionalities than UpdateEngine and doesn't allow cancel.
 *
 * <p>The minimal flow is:
 *
 * <ol>
 *   <li>Create a new UpdateEngineStable instance.
 *   <li>Call {@link #bind}, provide callback function.
 *   <li>Call {@link #applyPayloadFd}.
 * </ol>
 *
 * The APIs defined in this class and UpdateEngineStableCallback class must be in sync with the ones
 * in {@code system/update_engine/stable/android/os/IUpdateEngineStable.aidl} and {@code
 * ssystem/update_engine/stable/android/os/IUpdateEngineStableCallback.aidl}.
 *
 * @hide
 */
public class UpdateEngineStable {
    private static final String TAG = "UpdateEngineStable";

    private static final String UPDATE_ENGINE_STABLE_SERVICE =
            "android.os.UpdateEngineStableService";

    /**
     * Error codes from update engine upon finishing a call to {@link applyPayloadFd}. Values will
     * be passed via the callback function {@link
     * UpdateEngineStableCallback#onPayloadApplicationComplete}. Values must agree with the ones in
     * {@code system/update_engine/common/error_code.h}.
     */
    /** @hide */
    @IntDef(
            value = {
                UpdateEngine.ErrorCodeConstants.SUCCESS,
                UpdateEngine.ErrorCodeConstants.ERROR,
                UpdateEngine.ErrorCodeConstants.FILESYSTEM_COPIER_ERROR,
                UpdateEngine.ErrorCodeConstants.POST_INSTALL_RUNNER_ERROR,
                UpdateEngine.ErrorCodeConstants.PAYLOAD_MISMATCHED_TYPE_ERROR,
                UpdateEngine.ErrorCodeConstants.INSTALL_DEVICE_OPEN_ERROR,
                UpdateEngine.ErrorCodeConstants.KERNEL_DEVICE_OPEN_ERROR,
                UpdateEngine.ErrorCodeConstants.DOWNLOAD_TRANSFER_ERROR,
                UpdateEngine.ErrorCodeConstants.PAYLOAD_HASH_MISMATCH_ERROR,
                UpdateEngine.ErrorCodeConstants.PAYLOAD_SIZE_MISMATCH_ERROR,
                UpdateEngine.ErrorCodeConstants.DOWNLOAD_PAYLOAD_VERIFICATION_ERROR,
                UpdateEngine.ErrorCodeConstants.PAYLOAD_TIMESTAMP_ERROR,
                UpdateEngine.ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE,
                UpdateEngine.ErrorCodeConstants.NOT_ENOUGH_SPACE,
                UpdateEngine.ErrorCodeConstants.DEVICE_CORRUPTED,
            })
    public @interface ErrorCode {}

    private final IUpdateEngineStable mUpdateEngineStable;
    private IUpdateEngineStableCallback mUpdateEngineStableCallback = null;
    private final Object mUpdateEngineStableCallbackLock = new Object();

    /**
     * Creates a new instance.
     *
     * @hide
     */
    public UpdateEngineStable() {
        mUpdateEngineStable =
                IUpdateEngineStable.Stub.asInterface(
                        ServiceManager.getService(UPDATE_ENGINE_STABLE_SERVICE));
        if (mUpdateEngineStable == null) {
            throw new IllegalStateException("Failed to find " + UPDATE_ENGINE_STABLE_SERVICE);
        }
    }

    /**
     * Prepares this instance for use. The callback will be notified on any status change, and when
     * the update completes. A handler can be supplied to control which thread runs the callback, or
     * null.
     *
     * @hide
     */
    public boolean bind(final UpdateEngineStableCallback callback, final Handler handler) {
        synchronized (mUpdateEngineStableCallbackLock) {
            mUpdateEngineStableCallback =
                    new IUpdateEngineStableCallback.Stub() {
                        @Override
                        public void onStatusUpdate(final int status, final float percent) {
                            if (handler != null) {
                                handler.post(
                                        new Runnable() {
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
                                handler.post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                callback.onPayloadApplicationComplete(errorCode);
                                            }
                                        });
                            } else {
                                callback.onPayloadApplicationComplete(errorCode);
                            }
                        }

                        @Override
                        public int getInterfaceVersion() {
                            return super.VERSION;
                        }

                        @Override
                        public String getInterfaceHash() {
                            return super.HASH;
                        }
                    };

            try {
                return mUpdateEngineStable.bind(mUpdateEngineStableCallback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Equivalent to {@code bind(callback, null)}.
     *
     * @hide
     */
    public boolean bind(final UpdateEngineStableCallback callback) {
        return bind(callback, null);
    }

    /**
     * Applies payload from given ParcelFileDescriptor. Usage is same as UpdateEngine#applyPayload
     *
     * @hide
     */
    public void applyPayloadFd(
            ParcelFileDescriptor fd, long offset, long size, String[] headerKeyValuePairs) {
        try {
            mUpdateEngineStable.applyPayloadFd(fd, offset, size, headerKeyValuePairs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unbinds the last bound callback function.
     *
     * @hide
     */
    public boolean unbind() {
        synchronized (mUpdateEngineStableCallbackLock) {
            if (mUpdateEngineStableCallback == null) {
                return true;
            }
            try {
                boolean result = mUpdateEngineStable.unbind(mUpdateEngineStableCallback);
                mUpdateEngineStableCallback = null;
                return result;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
