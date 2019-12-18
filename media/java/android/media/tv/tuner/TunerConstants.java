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
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
final class TunerConstants {
    public static final int INVALID_TS_PID = Constants.Constant.INVALID_TS_PID;
    public static final int INVALID_STREAM_ID = Constants.Constant.INVALID_STREAM_ID;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FRONTEND_TYPE_UNDEFINED, FRONTEND_TYPE_ANALOG, FRONTEND_TYPE_ATSC, FRONTEND_TYPE_ATSC3,
            FRONTEND_TYPE_DVBC, FRONTEND_TYPE_DVBS, FRONTEND_TYPE_DVBT, FRONTEND_TYPE_ISDBS,
            FRONTEND_TYPE_ISDBS3, FRONTEND_TYPE_ISDBT})
    public @interface FrontendType {}

    public static final int FRONTEND_TYPE_UNDEFINED = Constants.FrontendType.UNDEFINED;
    public static final int FRONTEND_TYPE_ANALOG = Constants.FrontendType.ANALOG;
    public static final int FRONTEND_TYPE_ATSC = Constants.FrontendType.ATSC;
    public static final int FRONTEND_TYPE_ATSC3 = Constants.FrontendType.ATSC3;
    public static final int FRONTEND_TYPE_DVBC = Constants.FrontendType.DVBC;
    public static final int FRONTEND_TYPE_DVBS = Constants.FrontendType.DVBS;
    public static final int FRONTEND_TYPE_DVBT = Constants.FrontendType.DVBT;
    public static final int FRONTEND_TYPE_ISDBS = Constants.FrontendType.ISDBS;
    public static final int FRONTEND_TYPE_ISDBS3 = Constants.FrontendType.ISDBS3;
    public static final int FRONTEND_TYPE_ISDBT = Constants.FrontendType.ISDBT;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FRONTEND_EVENT_TYPE_LOCKED, FRONTEND_EVENT_TYPE_NO_SIGNAL,
            FRONTEND_EVENT_TYPE_LOST_LOCK})
    public @interface FrontendEventType {}

    public static final int FRONTEND_EVENT_TYPE_LOCKED = Constants.FrontendEventType.LOCKED;
    public static final int FRONTEND_EVENT_TYPE_NO_SIGNAL = Constants.FrontendEventType.NO_SIGNAL;
    public static final int FRONTEND_EVENT_TYPE_LOST_LOCK = Constants.FrontendEventType.LOST_LOCK;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DATA_FORMAT_TS, DATA_FORMAT_PES, DATA_FORMAT_ES, DATA_FORMAT_SHV_TLV})
    public @interface DataFormat {}

    public static final int DATA_FORMAT_TS = Constants.DataFormat.TS;
    public static final int DATA_FORMAT_PES = Constants.DataFormat.PES;
    public static final int DATA_FORMAT_ES = Constants.DataFormat.ES;
    public static final int DATA_FORMAT_SHV_TLV = Constants.DataFormat.SHV_TLV;


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DEMUX_T_PID, DEMUX_MMPT_PID})
    public @interface DemuxPidType {}

    public static final int DEMUX_T_PID = 1;
    public static final int DEMUX_MMPT_PID = 2;

    @IntDef({FRONTEND_SETTINGS_ANALOG, FRONTEND_SETTINGS_ATSC, FRONTEND_SETTINGS_ATSC3,
            FRONTEND_SETTINGS_DVBS, FRONTEND_SETTINGS_DVBC, FRONTEND_SETTINGS_DVBT,
            FRONTEND_SETTINGS_ISDBS, FRONTEND_SETTINGS_ISDBS3, FRONTEND_SETTINGS_ISDBT})
    public @interface FrontendSettingsType {}

    public static final int FRONTEND_SETTINGS_ANALOG = 1;
    public static final int FRONTEND_SETTINGS_ATSC = 2;
    public static final int FRONTEND_SETTINGS_ATSC3 = 3;
    public static final int FRONTEND_SETTINGS_DVBS = 4;
    public static final int FRONTEND_SETTINGS_DVBC = 5;
    public static final int FRONTEND_SETTINGS_DVBT = 6;
    public static final int FRONTEND_SETTINGS_ISDBS = 7;
    public static final int FRONTEND_SETTINGS_ISDBS3 = 8;
    public static final int FRONTEND_SETTINGS_ISDBT = 9;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FILTER_SETTINGS_TS, FILTER_SETTINGS_MMTP, FILTER_SETTINGS_IP, FILTER_SETTINGS_TLV,
            FILTER_SETTINGS_ALP})
    public @interface FilterSettingsType {}

    public static final int FILTER_SETTINGS_TS = Constants.DemuxFilterMainType.TS;
    public static final int FILTER_SETTINGS_MMTP = Constants.DemuxFilterMainType.MMTP;
    public static final int FILTER_SETTINGS_IP = Constants.DemuxFilterMainType.IP;
    public static final int FILTER_SETTINGS_TLV = Constants.DemuxFilterMainType.TLV;
    public static final int FILTER_SETTINGS_ALP = Constants.DemuxFilterMainType.ALP;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DVR_SETTINGS_RECORD, DVR_SETTINGS_PLAYBACK})
    public @interface DvrSettingsType {}

    public static final int DVR_SETTINGS_RECORD = Constants.DvrType.RECORD;
    public static final int DVR_SETTINGS_PLAYBACK = Constants.DvrType.PLAYBACK;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RESULT_SUCCESS, RESULT_UNAVAILABLE, RESULT_NOT_INITIALIZED, RESULT_INVALID_STATE,
            RESULT_INVALID_ARGUMENT, RESULT_OUT_OF_MEMORY, RESULT_UNKNOWN_ERROR})
    public @interface Result {}

    public static final int RESULT_SUCCESS = Constants.Result.SUCCESS;
    public static final int RESULT_UNAVAILABLE = Constants.Result.UNAVAILABLE;
    public static final int RESULT_NOT_INITIALIZED = Constants.Result.NOT_INITIALIZED;
    public static final int RESULT_INVALID_STATE = Constants.Result.INVALID_STATE;
    public static final int RESULT_INVALID_ARGUMENT = Constants.Result.INVALID_ARGUMENT;
    public static final int RESULT_OUT_OF_MEMORY = Constants.Result.OUT_OF_MEMORY;
    public static final int RESULT_UNKNOWN_ERROR = Constants.Result.UNKNOWN_ERROR;

    private TunerConstants() {
    }
}
