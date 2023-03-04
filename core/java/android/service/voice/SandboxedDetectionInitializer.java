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

package android.service.voice;

import android.annotation.DurationMillisLong;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;

import java.util.function.IntConsumer;

/**
 * Provides common initialzation methods for sandboxed detection services.
 *
 * @hide
 */
@SystemApi
public interface SandboxedDetectionInitializer {

    /**
     * Indicates that the updated status is successful.
     */
    int INITIALIZATION_STATUS_SUCCESS = 0;

    /**
     * Indicates that the callback wasn’t invoked within the timeout.
     * This is used by system.
     */
    int INITIALIZATION_STATUS_UNKNOWN = 100;

    /** @hide */
    String KEY_INITIALIZATION_STATUS = "initialization_status";

    /**
     * The maximum number of initialization status for some application specific failed reasons.
     *
     * @hide
     */
    int MAXIMUM_NUMBER_OF_INITIALIZATION_STATUS_CUSTOM_ERROR = 2;

    /**
     * Returns the maximum number of initialization status for some application specific failed
     * reasons.
     *
     * Note: The value 0 is reserved for success.
     */
    static int getMaxCustomInitializationStatus() {
        return MAXIMUM_NUMBER_OF_INITIALIZATION_STATUS_CUSTOM_ERROR;
    }

    /**
     * Creates a {@link IntConsumer} that sends the initialization status to the
     * {@link VoiceInteractionService} via {@link IRemoteCallback}.
     *
     * @hide
     */
    static IntConsumer createInitializationStatusConsumer(IRemoteCallback callback) {
        IntConsumer intConsumer = null;
        if (callback != null) {
            intConsumer =
                    value -> {
                        if (value > SandboxedDetectionInitializer
                                .getMaxCustomInitializationStatus()) {
                            throw new IllegalArgumentException(
                                    "The initialization status is invalid for " + value);
                        }
                        try {
                            Bundle status = new Bundle();
                            status.putInt(KEY_INITIALIZATION_STATUS, value);
                            callback.sendResult(status);
                        } catch (RemoteException e) {
                            throw e.rethrowFromSystemServer();
                        }
                    };
        }
        return intConsumer;
    }

    /**
     * Called when sandboxed detectors that extend {@link HotwordDetector} are created or
     * {@link HotwordDetector#updateState(PersistableBundle, SharedMemory)} requests an
     * update of the sandboxed detection parameters.
     *
     * @param options Application configuration data to provide to sandboxed detection services.
     * PersistableBundle does not allow any remotable objects or other contents that can be used to
     * communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to sandboxed detection services.
     * Use this to provide model data or other such data to the trusted process.
     * @param callbackTimeoutMillis Timeout in milliseconds for the operation to invoke the
     * statusCallback.
     * @param statusCallback Use this to return the updated result; the allowed values are
     * {@link #INITIALIZATION_STATUS_SUCCESS}, 1<->{@link #getMaxCustomInitializationStatus()}.
     * This is non-null only when sandboxed detection services are being initialized; and it
     * is null if the state is updated after that.
     */
    void onUpdateState(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @DurationMillisLong long callbackTimeoutMillis,
            @Nullable IntConsumer statusCallback);
}
