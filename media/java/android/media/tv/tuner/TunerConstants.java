/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constants for tuner framework.
 *
 * @hide
 */
@SystemApi
public final class TunerConstants {
    /**
     * Invalid TS packet ID.
     */
    public static final int INVALID_TS_PID = Constants.Constant.INVALID_TS_PID;
    /**
     * Invalid stream ID.
     */
    public static final int INVALID_STREAM_ID = Constants.Constant.INVALID_STREAM_ID;
    /**
     * Invalid filter ID.
     */
    public static final int INVALID_FILTER_ID = Constants.Constant.INVALID_FILTER_ID;
    /**
     * Invalid AV Sync ID.
     */
    public static final int INVALID_AV_SYNC_ID = Constants.Constant.INVALID_AV_SYNC_ID;
    /**
     * Timestamp is unavailable.
     *
     * <p>Returned by {@link android.media.tv.tuner.filter.TimeFilter#getSourceTime()},
     * {@link android.media.tv.tuner.filter.TimeFilter#getTimeStamp()}, or
     * {@link Tuner#getAvSyncTime(int)} when the requested timestamp is not available.
     *
     * @see android.media.tv.tuner.filter.TimeFilter#getSourceTime()
     * @see android.media.tv.tuner.filter.TimeFilter#getTimeStamp()
     * @see Tuner#getAvSyncTime(int)
     * @hide
     */
    public static final long TIMESTAMP_UNAVAILABLE = -1L;

    /** @hide */
    @IntDef(prefix = "SCAN_TYPE_", value = {SCAN_TYPE_UNDEFINED, SCAN_TYPE_AUTO, SCAN_TYPE_BLIND})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanType {}
    /**
     * Scan type undefined.
     */
    public static final int SCAN_TYPE_UNDEFINED = Constants.FrontendScanType.SCAN_UNDEFINED;
    /**
     * Scan type auto.
     *
     * <p> Tuner will send {@link android.media.tv.tuner.frontend.ScanCallback#onLocked}
     */
    public static final int SCAN_TYPE_AUTO = Constants.FrontendScanType.SCAN_AUTO;
    /**
     * Blind scan.
     *
     * <p>Frequency range is not specified. The {@link android.media.tv.tuner.Tuner} will scan an
     * implementation specific range.
     */
    public static final int SCAN_TYPE_BLIND = Constants.FrontendScanType.SCAN_BLIND;

    /** @hide */
    @IntDef({RESULT_SUCCESS, RESULT_UNAVAILABLE, RESULT_NOT_INITIALIZED, RESULT_INVALID_STATE,
            RESULT_INVALID_ARGUMENT, RESULT_OUT_OF_MEMORY, RESULT_UNKNOWN_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {}

    /**
     * Operation succeeded.
     */
    public static final int RESULT_SUCCESS = Constants.Result.SUCCESS;
    /**
     * Operation failed because the corresponding resources are not available.
     */
    public static final int RESULT_UNAVAILABLE = Constants.Result.UNAVAILABLE;
    /**
     * Operation failed because the corresponding resources are not initialized.
     */
    public static final int RESULT_NOT_INITIALIZED = Constants.Result.NOT_INITIALIZED;
    /**
     * Operation failed because it's not in a valid state.
     */
    public static final int RESULT_INVALID_STATE = Constants.Result.INVALID_STATE;
    /**
     * Operation failed because there are invalid arguments.
     */
    public static final int RESULT_INVALID_ARGUMENT = Constants.Result.INVALID_ARGUMENT;
    /**
     * Memory allocation failed.
     */
    public static final int RESULT_OUT_OF_MEMORY = Constants.Result.OUT_OF_MEMORY;
    /**
     * Operation failed due to unknown errors.
     */
    public static final int RESULT_UNKNOWN_ERROR = Constants.Result.UNKNOWN_ERROR;

    private TunerConstants() {
    }
}
