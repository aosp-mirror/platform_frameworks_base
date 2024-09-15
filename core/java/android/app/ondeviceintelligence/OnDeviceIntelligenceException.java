/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;


import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.PersistableBundle;

import androidx.annotation.IntDef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Exception type to be used for errors related to on-device intelligence system service with
 * appropriate error code.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public class OnDeviceIntelligenceException extends Exception {

    public static final int PROCESSING_ERROR_UNKNOWN = 1;

    /** Request passed contains bad data for e.g. format. */
    public static final int PROCESSING_ERROR_BAD_DATA = 2;

    /** Bad request for inputs. */
    public static final int PROCESSING_ERROR_BAD_REQUEST = 3;

    /** Whole request was classified as not safe, and no response will be generated. */
    public static final int PROCESSING_ERROR_REQUEST_NOT_SAFE = 4;

    /** Underlying processing encountered an error and failed to compute results. */
    public static final int PROCESSING_ERROR_COMPUTE_ERROR = 5;

    /** Encountered an error while performing IPC */
    public static final int PROCESSING_ERROR_IPC_ERROR = 6;

    /** Request was cancelled either by user signal or by the underlying implementation. */
    public static final int PROCESSING_ERROR_CANCELLED = 7;

    /** Underlying processing in the remote implementation is not available. */
    public static final int PROCESSING_ERROR_NOT_AVAILABLE = 8;

    /** The service is currently busy. Callers should retry with exponential backoff. */
    public static final int PROCESSING_ERROR_BUSY = 9;

    /** Something went wrong with safety classification service. */
    public static final int PROCESSING_ERROR_SAFETY_ERROR = 10;

    /** Response generated was classified unsafe. */
    public static final int PROCESSING_ERROR_RESPONSE_NOT_SAFE = 11;

    /** Request is too large to be processed. */
    public static final int PROCESSING_ERROR_REQUEST_TOO_LARGE = 12;

    /** Inference suspended so that higher-priority inference can run. */
    public static final int PROCESSING_ERROR_SUSPENDED = 13;

    /**
     * Underlying processing encountered an internal error, like a violated precondition
     * .
     */
    public static final int PROCESSING_ERROR_INTERNAL = 14;

    /**
     * The processing was not able to be passed on to the remote implementation, as the
     * service
     * was unavailable.
     */
    public static final int PROCESSING_ERROR_SERVICE_UNAVAILABLE = 15;
    /**
     * Error code returned when the OnDeviceIntelligenceManager service is unavailable.
     */
    public static final int ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE = 100;

    /**
     * The connection to remote service failed and the processing state could not be updated.
     */
    public static final int PROCESSING_UPDATE_STATUS_CONNECTION_FAILED = 200;


    /**
     * Error code associated with the on-device intelligence failure.
     *
     * @hide
     */
    @IntDef(
            value = {
                    PROCESSING_ERROR_UNKNOWN,
                    PROCESSING_ERROR_BAD_DATA,
                    PROCESSING_ERROR_BAD_REQUEST,
                    PROCESSING_ERROR_REQUEST_NOT_SAFE,
                    PROCESSING_ERROR_COMPUTE_ERROR,
                    PROCESSING_ERROR_IPC_ERROR,
                    PROCESSING_ERROR_CANCELLED,
                    PROCESSING_ERROR_NOT_AVAILABLE,
                    PROCESSING_ERROR_BUSY,
                    PROCESSING_ERROR_SAFETY_ERROR,
                    PROCESSING_ERROR_RESPONSE_NOT_SAFE,
                    PROCESSING_ERROR_REQUEST_TOO_LARGE,
                    PROCESSING_ERROR_SUSPENDED,
                    PROCESSING_ERROR_INTERNAL,
                    PROCESSING_ERROR_SERVICE_UNAVAILABLE,
                    ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                    PROCESSING_UPDATE_STATUS_CONNECTION_FAILED
            }, open = true)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    @interface OnDeviceIntelligenceError {
    }

    private final int mErrorCode;
    private final PersistableBundle mErrorParams;

    /** Returns the error code of the exception. */
    public int getErrorCode() {
        return mErrorCode;
    }

    /** Returns the error params of the exception. */
    @NonNull
    public PersistableBundle getErrorParams() {
        return mErrorParams;
    }

    /**
     * Creates a new OnDeviceIntelligenceException with the specified error code, error message and
     * error params.
     *
     * @param errorCode The error code.
     * @param errorMessage The error message.
     * @param errorParams The error params.
     */
    public OnDeviceIntelligenceException(
            @OnDeviceIntelligenceError int errorCode, @NonNull String errorMessage,
            @NonNull PersistableBundle errorParams) {
        super(errorMessage);
        this.mErrorCode = errorCode;
        this.mErrorParams = errorParams;
    }

    /**
     * Creates a new OnDeviceIntelligenceException with the specified error code and error params.
     *
     * @param errorCode The error code.
     * @param errorParams The error params.
     */
    public OnDeviceIntelligenceException(
            @OnDeviceIntelligenceError int errorCode,
            @NonNull PersistableBundle errorParams) {
        this.mErrorCode = errorCode;
        this.mErrorParams = errorParams;
    }

    /**
     * Creates a new OnDeviceIntelligenceException with the specified error code and error message.
     *
     * @param errorCode The error code.
     * @param errorMessage The error message.
     */
    public OnDeviceIntelligenceException(
            @OnDeviceIntelligenceError int errorCode, @NonNull String errorMessage) {
        super(errorMessage);
        this.mErrorCode = errorCode;
        this.mErrorParams = new PersistableBundle();
    }

    /**
     * Creates a new OnDeviceIntelligenceException with the specified error code.
     *
     * @param errorCode The error code.
     */
    public OnDeviceIntelligenceException(
            @OnDeviceIntelligenceError int errorCode) {
        this.mErrorCode = errorCode;
        this.mErrorParams = new PersistableBundle();
    }
}
