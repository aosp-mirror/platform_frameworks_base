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

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerConstants.FilterSubtype;
import android.media.tv.tuner.filter.FilterConfiguration;
import android.media.tv.tuner.filter.FilterConfiguration.FilterType;

/**
 * Utility class for tuner framework.
 *
 * @hide
 */
public final class TunerUtils {
    private static final String PERMISSION = android.Manifest.permission.ACCESS_TV_TUNER;

    /**
     * Checks whether the caller has permission to access tuner.
     *
     * @param context context of the caller.
     * @throws SecurityException if the caller doesn't have the permission.
     */
    public static void checkTunerPermission(Context context) {
        if (context.checkCallingOrSelfPermission(PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have " + PERMISSION + " permission.");
        }
    }

    /**
     * Gets the corresponding filter subtype constant defined in tuner HAL.
     *
     * @param mainType filter main type.
     * @param subtype filter subtype.
     */
    public static int getFilterSubtype(@FilterType int mainType, @FilterSubtype int subtype) {
        if (mainType == FilterConfiguration.FILTER_TYPE_TS) {
            switch (subtype) {
                case TunerConstants.FILTER_SUBTYPE_UNDEFINED:
                    return Constants.DemuxTsFilterType.UNDEFINED;
                case TunerConstants.FILTER_SUBTYPE_SECTION:
                    return Constants.DemuxTsFilterType.SECTION;
                case TunerConstants.FILTER_SUBTYPE_PES:
                    return Constants.DemuxTsFilterType.PES;
                case TunerConstants.FILTER_SUBTYPE_TS:
                    return Constants.DemuxTsFilterType.TS;
                case TunerConstants.FILTER_SUBTYPE_AUDIO:
                    return Constants.DemuxTsFilterType.AUDIO;
                case TunerConstants.FILTER_SUBTYPE_VIDEO:
                    return Constants.DemuxTsFilterType.VIDEO;
                case TunerConstants.FILTER_SUBTYPE_PCR:
                    return Constants.DemuxTsFilterType.PCR;
                case TunerConstants.FILTER_SUBTYPE_RECORD:
                    return Constants.DemuxTsFilterType.RECORD;
                case TunerConstants.FILTER_SUBTYPE_TEMI:
                    return Constants.DemuxTsFilterType.TEMI;
                default:
                    break;
            }
        } else if (mainType == FilterConfiguration.FILTER_TYPE_MMTP) {
            switch (subtype) {
                case TunerConstants.FILTER_SUBTYPE_UNDEFINED:
                    return Constants.DemuxMmtpFilterType.UNDEFINED;
                case TunerConstants.FILTER_SUBTYPE_SECTION:
                    return Constants.DemuxMmtpFilterType.SECTION;
                case TunerConstants.FILTER_SUBTYPE_PES:
                    return Constants.DemuxMmtpFilterType.PES;
                case TunerConstants.FILTER_SUBTYPE_MMPT:
                    return Constants.DemuxMmtpFilterType.MMTP;
                case TunerConstants.FILTER_SUBTYPE_AUDIO:
                    return Constants.DemuxMmtpFilterType.AUDIO;
                case TunerConstants.FILTER_SUBTYPE_VIDEO:
                    return Constants.DemuxMmtpFilterType.VIDEO;
                case TunerConstants.FILTER_SUBTYPE_RECORD:
                    return Constants.DemuxMmtpFilterType.RECORD;
                case TunerConstants.FILTER_SUBTYPE_DOWNLOAD:
                    return Constants.DemuxMmtpFilterType.DOWNLOAD;
                default:
                    break;
            }

        } else if (mainType == FilterConfiguration.FILTER_TYPE_IP) {
            switch (subtype) {
                case TunerConstants.FILTER_SUBTYPE_UNDEFINED:
                    return Constants.DemuxIpFilterType.UNDEFINED;
                case TunerConstants.FILTER_SUBTYPE_SECTION:
                    return Constants.DemuxIpFilterType.SECTION;
                case TunerConstants.FILTER_SUBTYPE_NTP:
                    return Constants.DemuxIpFilterType.NTP;
                case TunerConstants.FILTER_SUBTYPE_IP_PAYLOAD:
                    return Constants.DemuxIpFilterType.IP_PAYLOAD;
                case TunerConstants.FILTER_SUBTYPE_IP:
                    return Constants.DemuxIpFilterType.IP;
                case TunerConstants.FILTER_SUBTYPE_PAYLOAD_THROUGH:
                    return Constants.DemuxIpFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        } else if (mainType == FilterConfiguration.FILTER_TYPE_TLV) {
            switch (subtype) {
                case TunerConstants.FILTER_SUBTYPE_UNDEFINED:
                    return Constants.DemuxTlvFilterType.UNDEFINED;
                case TunerConstants.FILTER_SUBTYPE_SECTION:
                    return Constants.DemuxTlvFilterType.SECTION;
                case TunerConstants.FILTER_SUBTYPE_TLV:
                    return Constants.DemuxTlvFilterType.TLV;
                case TunerConstants.FILTER_SUBTYPE_PAYLOAD_THROUGH:
                    return Constants.DemuxTlvFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        } else if (mainType == FilterConfiguration.FILTER_TYPE_ALP) {
            switch (subtype) {
                case TunerConstants.FILTER_SUBTYPE_UNDEFINED:
                    return Constants.DemuxAlpFilterType.UNDEFINED;
                case TunerConstants.FILTER_SUBTYPE_SECTION:
                    return Constants.DemuxAlpFilterType.SECTION;
                case TunerConstants.FILTER_SUBTYPE_PTP:
                    return Constants.DemuxAlpFilterType.PTP;
                case TunerConstants.FILTER_SUBTYPE_PAYLOAD_THROUGH:
                    return Constants.DemuxAlpFilterType.PAYLOAD_THROUGH;
                default:
                    break;
            }
        }
        throw new IllegalArgumentException(
                "Invalid filter types. Main type=" + mainType + ", subtype=" + subtype);
    }

    private TunerUtils() {}
}
