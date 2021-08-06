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
import android.hardware.tv.tuner.DemuxAlpFilterType;
import android.hardware.tv.tuner.DemuxIpFilterType;
import android.hardware.tv.tuner.DemuxMmtpFilterType;
import android.hardware.tv.tuner.DemuxTlvFilterType;
import android.hardware.tv.tuner.DemuxTsFilterType;
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
                    return DemuxTsFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return DemuxTsFilterType.SECTION;
                case Filter.SUBTYPE_PES:
                    return DemuxTsFilterType.PES;
                case Filter.SUBTYPE_TS:
                    return DemuxTsFilterType.TS;
                case Filter.SUBTYPE_AUDIO:
                    return DemuxTsFilterType.AUDIO;
                case Filter.SUBTYPE_VIDEO:
                    return DemuxTsFilterType.VIDEO;
                case Filter.SUBTYPE_PCR:
                    return DemuxTsFilterType.PCR;
                case Filter.SUBTYPE_RECORD:
                    return DemuxTsFilterType.RECORD;
                case Filter.SUBTYPE_TEMI:
                    return DemuxTsFilterType.TEMI;
                default:
                    break;
            }
        } else if (mainType == Filter.TYPE_MMTP) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return DemuxMmtpFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return DemuxMmtpFilterType.SECTION;
                case Filter.SUBTYPE_PES:
                    return DemuxMmtpFilterType.PES;
                case Filter.SUBTYPE_MMTP:
                    return DemuxMmtpFilterType.MMTP;
                case Filter.SUBTYPE_AUDIO:
                    return DemuxMmtpFilterType.AUDIO;
                case Filter.SUBTYPE_VIDEO:
                    return DemuxMmtpFilterType.VIDEO;
                case Filter.SUBTYPE_RECORD:
                    return DemuxMmtpFilterType.RECORD;
                case Filter.SUBTYPE_DOWNLOAD:
                    return DemuxMmtpFilterType.DOWNLOAD;
                default:
                    break;
            }

        } else if (mainType == Filter.TYPE_IP) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return DemuxIpFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return DemuxIpFilterType.SECTION;
                case Filter.SUBTYPE_NTP:
                    return DemuxIpFilterType.NTP;
                case Filter.SUBTYPE_IP_PAYLOAD:
                    return DemuxIpFilterType.IP_PAYLOAD;
                case Filter.SUBTYPE_IP:
                    return DemuxIpFilterType.IP;
                case Filter.SUBTYPE_PAYLOAD_THROUGH:
                    return DemuxIpFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        } else if (mainType == Filter.TYPE_TLV) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return DemuxTlvFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return DemuxTlvFilterType.SECTION;
                case Filter.SUBTYPE_TLV:
                    return DemuxTlvFilterType.TLV;
                case Filter.SUBTYPE_PAYLOAD_THROUGH:
                    return DemuxTlvFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        } else if (mainType == Filter.TYPE_ALP) {
            switch (subtype) {
                case Filter.SUBTYPE_UNDEFINED:
                    return DemuxAlpFilterType.UNDEFINED;
                case Filter.SUBTYPE_SECTION:
                    return DemuxAlpFilterType.SECTION;
                case Filter.SUBTYPE_PTP:
                    return DemuxAlpFilterType.PTP;
                case Filter.SUBTYPE_PAYLOAD_THROUGH:
                    return DemuxAlpFilterType.PAYLOAD_THROUGH;
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

    /**
     * Checks the state of a resource instance.
     *
     * @throws IllegalStateException if the resource has already been closed.
     */
    public static void checkResourceState(String name, boolean closed) {
        if (closed) {
            throw new IllegalStateException(name + " has been closed");
        }
    }

    private TunerUtils() {}
}
