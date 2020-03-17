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

package android.media.tv.tuner.frontend;

import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for tune and scan operations.
 *
 * @hide
 */
@SystemApi
public abstract class FrontendSettings {
    /** @hide */
    @IntDef(prefix = "TYPE_",
            value = {TYPE_UNDEFINED, TYPE_ANALOG, TYPE_ATSC, TYPE_ATSC3, TYPE_DVBC, TYPE_DVBS,
                    TYPE_DVBT, TYPE_ISDBS, TYPE_ISDBS3, TYPE_ISDBT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Undefined frontend type.
     */
    public static final int TYPE_UNDEFINED = Constants.FrontendType.UNDEFINED;
    /**
     * Analog frontend type.
     */
    public static final int TYPE_ANALOG = Constants.FrontendType.ANALOG;
    /**
     * Advanced Television Systems Committee (ATSC) frontend type.
     */
    public static final int TYPE_ATSC = Constants.FrontendType.ATSC;
    /**
     * Advanced Television Systems Committee 3.0 (ATSC-3) frontend type.
     */
    public static final int TYPE_ATSC3 = Constants.FrontendType.ATSC3;
    /**
     * Digital Video Broadcasting-Cable (DVB-C) frontend type.
     */
    public static final int TYPE_DVBC = Constants.FrontendType.DVBC;
    /**
     * Digital Video Broadcasting-Satellite (DVB-S) frontend type.
     */
    public static final int TYPE_DVBS = Constants.FrontendType.DVBS;
    /**
     * Digital Video Broadcasting-Terrestrial (DVB-T) frontend type.
     */
    public static final int TYPE_DVBT = Constants.FrontendType.DVBT;
    /**
     * Integrated Services Digital Broadcasting-Satellite (ISDB-S) frontend type.
     */
    public static final int TYPE_ISDBS = Constants.FrontendType.ISDBS;
    /**
     * Integrated Services Digital Broadcasting-Satellite 3 (ISDB-S3) frontend type.
     */
    public static final int TYPE_ISDBS3 = Constants.FrontendType.ISDBS3;
    /**
     * Integrated Services Digital Broadcasting-Terrestrial (ISDB-T) frontend type.
     */
    public static final int TYPE_ISDBT = Constants.FrontendType.ISDBT;



    /** @hide */
    @LongDef(flag = true,
            prefix = "FEC_",
            value = {FEC_UNDEFINED, FEC_AUTO, FEC_1_2, FEC_1_3, FEC_1_4, FEC_1_5, FEC_2_3, FEC_2_5,
            FEC_2_9, FEC_3_4, FEC_3_5, FEC_4_5, FEC_4_15, FEC_5_6, FEC_5_9, FEC_6_7, FEC_7_8,
            FEC_7_9, FEC_7_15, FEC_8_9, FEC_8_15, FEC_9_10, FEC_9_20, FEC_11_15, FEC_11_20,
            FEC_11_45, FEC_13_18, FEC_13_45, FEC_14_45, FEC_23_36, FEC_25_36, FEC_26_45, FEC_28_45,
            FEC_29_45, FEC_31_45, FEC_32_45, FEC_77_90})
    @Retention(RetentionPolicy.SOURCE)
    public @interface InnerFec {}

    /**
     * FEC not defined.
     */
    public static final long FEC_UNDEFINED = Constants.FrontendInnerFec.FEC_UNDEFINED;
    /**
     * hardware is able to detect and set FEC automatically.
     */
    public static final long FEC_AUTO = Constants.FrontendInnerFec.AUTO;
    /**
     * 1/2 conv. code rate.
     */
    public static final long FEC_1_2 = Constants.FrontendInnerFec.FEC_1_2;
    /**
     * 1/3 conv. code rate.
     */
    public static final long FEC_1_3 = Constants.FrontendInnerFec.FEC_1_3;
    /**
     * 1/4 conv. code rate.
     */
    public static final long FEC_1_4 = Constants.FrontendInnerFec.FEC_1_4;
    /**
     * 1/5 conv. code rate.
     */
    public static final long FEC_1_5 = Constants.FrontendInnerFec.FEC_1_5;
    /**
     * 2/3 conv. code rate.
     */
    public static final long FEC_2_3 = Constants.FrontendInnerFec.FEC_2_3;
    /**
     * 2/5 conv. code rate.
     */
    public static final long FEC_2_5 = Constants.FrontendInnerFec.FEC_2_5;
    /**
     * 2/9 conv. code rate.
     */
    public static final long FEC_2_9 = Constants.FrontendInnerFec.FEC_2_9;
    /**
     * 3/4 conv. code rate.
     */
    public static final long FEC_3_4 = Constants.FrontendInnerFec.FEC_3_4;
    /**
     * 3/5 conv. code rate.
     */
    public static final long FEC_3_5 = Constants.FrontendInnerFec.FEC_3_5;
    /**
     * 4/5 conv. code rate.
     */
    public static final long FEC_4_5 = Constants.FrontendInnerFec.FEC_4_5;
    /**
     * 4/15 conv. code rate.
     */
    public static final long FEC_4_15 = Constants.FrontendInnerFec.FEC_4_15;
    /**
     * 5/6 conv. code rate.
     */
    public static final long FEC_5_6 = Constants.FrontendInnerFec.FEC_5_6;
    /**
     * 5/9 conv. code rate.
     */
    public static final long FEC_5_9 = Constants.FrontendInnerFec.FEC_5_9;
    /**
     * 6/7 conv. code rate.
     */
    public static final long FEC_6_7 = Constants.FrontendInnerFec.FEC_6_7;
    /**
     * 7/8 conv. code rate.
     */
    public static final long FEC_7_8 = Constants.FrontendInnerFec.FEC_7_8;
    /**
     * 7/9 conv. code rate.
     */
    public static final long FEC_7_9 = Constants.FrontendInnerFec.FEC_7_9;
    /**
     * 7/15 conv. code rate.
     */
    public static final long FEC_7_15 = Constants.FrontendInnerFec.FEC_7_15;
    /**
     * 8/9 conv. code rate.
     */
    public static final long FEC_8_9 = Constants.FrontendInnerFec.FEC_8_9;
    /**
     * 8/15 conv. code rate.
     */
    public static final long FEC_8_15 = Constants.FrontendInnerFec.FEC_8_15;
    /**
     * 9/10 conv. code rate.
     */
    public static final long FEC_9_10 = Constants.FrontendInnerFec.FEC_9_10;
    /**
     * 9/20 conv. code rate.
     */
    public static final long FEC_9_20 = Constants.FrontendInnerFec.FEC_9_20;
    /**
     * 11/15 conv. code rate.
     */
    public static final long FEC_11_15 = Constants.FrontendInnerFec.FEC_11_15;
    /**
     * 11/20 conv. code rate.
     */
    public static final long FEC_11_20 = Constants.FrontendInnerFec.FEC_11_20;
    /**
     * 11/45 conv. code rate.
     */
    public static final long FEC_11_45 = Constants.FrontendInnerFec.FEC_11_45;
    /**
     * 13/18 conv. code rate.
     */
    public static final long FEC_13_18 = Constants.FrontendInnerFec.FEC_13_18;
    /**
     * 13/45 conv. code rate.
     */
    public static final long FEC_13_45 = Constants.FrontendInnerFec.FEC_13_45;
    /**
     * 14/45 conv. code rate.
     */
    public static final long FEC_14_45 = Constants.FrontendInnerFec.FEC_14_45;
    /**
     * 23/36 conv. code rate.
     */
    public static final long FEC_23_36 = Constants.FrontendInnerFec.FEC_23_36;
    /**
     * 25/36 conv. code rate.
     */
    public static final long FEC_25_36 = Constants.FrontendInnerFec.FEC_25_36;
    /**
     * 26/45 conv. code rate.
     */
    public static final long FEC_26_45 = Constants.FrontendInnerFec.FEC_26_45;
    /**
     * 28/45 conv. code rate.
     */
    public static final long FEC_28_45 = Constants.FrontendInnerFec.FEC_28_45;
    /**
     * 29/45 conv. code rate.
     */
    public static final long FEC_29_45 = Constants.FrontendInnerFec.FEC_29_45;
    /**
     * 31/45 conv. code rate.
     */
    public static final long FEC_31_45 = Constants.FrontendInnerFec.FEC_31_45;
    /**
     * 32/45 conv. code rate.
     */
    public static final long FEC_32_45 = Constants.FrontendInnerFec.FEC_32_45;
    /**
     * 77/90 conv. code rate.
     */
    public static final long FEC_77_90 = Constants.FrontendInnerFec.FEC_77_90;



    private final int mFrequency;

    FrontendSettings(int frequency) {
        mFrequency = frequency;
    }

    /**
     * Returns the frontend type.
     */
    @Type
    public abstract int getType();

    /**
     * Gets the frequency.
     *
     * @return the frequency in Hz.
     */
    public int getFrequency() {
        return mFrequency;
    }
}
