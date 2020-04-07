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

import android.annotation.Nullable;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.filter.Filter;

/**
 * Utility class for tuner framework.
 *
 * @hide
 */
public final class TunerUtils {

    /**
     * Gets the corresponding filter subtype constant defined in tuner HAL.
     *
     * @param mainType filter main type.
     * @param subtype filter subtype.
     */
    public static int getFilterSubtype(@Filter.Type int mainType, @Filter.Subtype int subtype) {
        if (mainType == Filter.TYPE_TS) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return Constants.DemuxTsFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return Constants.DemuxTsFilterType.SECTION;
                case Filter.SUBTYPE_PES:
                    return Constants.DemuxTsFilterType.PES;
                case Filter.SUBTYPE_TS:
                    return Constants.DemuxTsFilterType.TS;
                case Filter.SUBTYPE_AUDIO:
                    return Constants.DemuxTsFilterType.AUDIO;
                case Filter.SUBTYPE_VIDEO:
                    return Constants.DemuxTsFilterType.VIDEO;
                case Filter.SUBTYPE_PCR:
                    return Constants.DemuxTsFilterType.PCR;
                case Filter.SUBTYPE_RECORD:
                    return Constants.DemuxTsFilterType.RECORD;
                case Filter.SUBTYPE_TEMI:
                    return Constants.DemuxTsFilterType.TEMI;
                default:
                    break;
            }
        } else if (mainType == Filter.TYPE_MMTP) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return Constants.DemuxMmtpFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return Constants.DemuxMmtpFilterType.SECTION;
                case Filter.SUBTYPE_PES:
                    return Constants.DemuxMmtpFilterType.PES;
                case Filter.SUBTYPE_MMTP:
                    return Constants.DemuxMmtpFilterType.MMTP;
                case Filter.SUBTYPE_AUDIO:
                    return Constants.DemuxMmtpFilterType.AUDIO;
                case Filter.SUBTYPE_VIDEO:
                    return Constants.DemuxMmtpFilterType.VIDEO;
                case Filter.SUBTYPE_RECORD:
                    return Constants.DemuxMmtpFilterType.RECORD;
                case Filter.SUBTYPE_DOWNLOAD:
                    return Constants.DemuxMmtpFilterType.DOWNLOAD;
                default:
                    break;
            }

        } else if (mainType == Filter.TYPE_IP) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return Constants.DemuxIpFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return Constants.DemuxIpFilterType.SECTION;
                case Filter.SUBTYPE_NTP:
                    return Constants.DemuxIpFilterType.NTP;
                case Filter.SUBTYPE_IP_PAYLOAD:
                    return Constants.DemuxIpFilterType.IP_PAYLOAD;
                case Filter.SUBTYPE_IP:
                    return Constants.DemuxIpFilterType.IP;
                case Filter.SUBTYPE_PAYLOAD_THROUGH:
                    return Constants.DemuxIpFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        } else if (mainType == Filter.TYPE_TLV) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return Constants.DemuxTlvFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return Constants.DemuxTlvFilterType.SECTION;
                case Filter.SUBTYPE_TLV:
                    return Constants.DemuxTlvFilterType.TLV;
                case Filter.SUBTYPE_PAYLOAD_THROUGH:
                    return Constants.DemuxTlvFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        } else if (mainType == Filter.TYPE_ALP) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return Constants.DemuxAlpFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return Constants.DemuxAlpFilterType.SECTION;
                case Filter.SUBTYPE_PTP:
                    return Constants.DemuxAlpFilterType.PTP;
                case Filter.SUBTYPE_PAYLOAD_THROUGH:
                    return Constants.DemuxAlpFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        }
        throw new IllegalArgumentException(
                "Invalid filter types. Main type=" + mainType + ", subtype=" + subtype);
    }

    /**
     * Gets an throwable instance for the corresponding result.
     */
    @Nullable
    public static void throwExceptionForResult(
            @Tuner.Result int r, @Nullable String msg) {
        if (msg == null) {
            msg = "";
        }
        switch (r) {
            case Tuner.RESULT_SUCCESS:
                return;
            case Tuner.RESULT_INVALID_ARGUMENT:
                throw new IllegalArgumentException(msg);
            case Tuner.RESULT_INVALID_STATE:
                throw new IllegalStateException(msg);
            case Tuner.RESULT_NOT_INITIALIZED:
                throw new IllegalStateException("Invalid state: not initialized. " + msg);
            case Tuner.RESULT_OUT_OF_MEMORY:
                throw new OutOfMemoryError(msg);
            case Tuner.RESULT_UNAVAILABLE:
                throw new IllegalStateException("Invalid state: resource unavailable. " + msg);
            case Tuner.RESULT_UNKNOWN_ERROR:
                throw new RuntimeException("Unknown error" + msg);
            default:
                break;
        }
        throw new RuntimeException("Unexpected result " + r + ".  " + msg);
    }

    private TunerUtils() {}
}
