/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.telephony;

import android.annotation.SystemApi;

/**
 * Contains access network related constants.
 */
public final class AccessNetworkConstants {

    public static final class AccessNetworkType {
        public static final int UNKNOWN = 0;
        public static final int GERAN = 1;
        public static final int UTRAN = 2;
        public static final int EUTRAN = 3;
        public static final int CDMA2000 = 4;
        public static final int IWLAN = 5;

        /** @hide */
        private AccessNetworkType() {}

        /** @hide */
        public static String toString(int type) {
            switch (type) {
                case UNKNOWN: return "UNKNOWN";
                case GERAN: return "GERAN";
                case UTRAN: return "UTRAN";
                case EUTRAN: return "EUTRAN";
                case CDMA2000: return "CDMA2000";
                case IWLAN: return "IWLAN";
                default: return Integer.toString(type);
            }
        }
    }

    /**
     * Wireless transportation type
     * @hide
     */
    @SystemApi
    public static final class TransportType {
        /**
         * Invalid transport type.
         * @hide
         */
        public static final int INVALID = -1;

        /** Wireless Wide Area Networks (i.e. Cellular) */
        public static final int WWAN = 1;

        /** Wireless Local Area Networks (i.e. Wifi) */
        public static final int WLAN = 2;

        /** @hide */
        private TransportType() {}

        /** @hide */
        public static String toString(int type) {
            switch (type) {
                case INVALID: return "INVALID";
                case WWAN: return "WWAN";
                case WLAN: return "WLAN";
                default: return Integer.toString(type);
            }
        }
    }

    /**
     * Frenquency bands for GERAN.
     * http://www.etsi.org/deliver/etsi_ts/145000_145099/145005/14.00.00_60/ts_145005v140000p.pdf
     */
    public static final class GeranBand {
        public static final int BAND_T380 = 1;
        public static final int BAND_T410 = 2;
        public static final int BAND_450 = 3;
        public static final int BAND_480 = 4;
        public static final int BAND_710 = 5;
        public static final int BAND_750 = 6;
        public static final int BAND_T810 = 7;
        public static final int BAND_850 = 8;
        public static final int BAND_P900 = 9;
        public static final int BAND_E900 = 10;
        public static final int BAND_R900 = 11;
        public static final int BAND_DCS1800 = 12;
        public static final int BAND_PCS1900 = 13;
        public static final int BAND_ER900 = 14;

        /** @hide */
        private GeranBand() {};
    }

    /**
     * Frenquency bands for UTRAN.
     * http://www.etsi.org/deliver/etsi_ts/125100_125199/125104/13.03.00_60/ts_125104v130p.pdf
     */
    public static final class UtranBand {
        public static final int BAND_1 = 1;
        public static final int BAND_2 = 2;
        public static final int BAND_3 = 3;
        public static final int BAND_4 = 4;
        public static final int BAND_5 = 5;
        public static final int BAND_6 = 6;
        public static final int BAND_7 = 7;
        public static final int BAND_8 = 8;
        public static final int BAND_9 = 9;
        public static final int BAND_10 = 10;
        public static final int BAND_11 = 11;
        public static final int BAND_12 = 12;
        public static final int BAND_13 = 13;
        public static final int BAND_14 = 14;
        /** band 15, 16, 17, 18 are reserved */
        public static final int BAND_19 = 19;
        public static final int BAND_20 = 20;
        public static final int BAND_21 = 21;
        public static final int BAND_22 = 22;
        /** band 23, 24 are reserved */
        public static final int BAND_25 = 25;
        public static final int BAND_26 = 26;

        /** @hide */
        private UtranBand() {};
    }

    /**
     * Frenquency bands for EUTRAN.
     * http://www.etsi.org/deliver/etsi_ts/136100_136199/136101/14.03.00_60/ts_136101v140p.pdf
     */
    public static final class EutranBand {
        public static final int BAND_1 = 1;
        public static final int BAND_2 = 2;
        public static final int BAND_3 = 3;
        public static final int BAND_4 = 4;
        public static final int BAND_5 = 5;
        public static final int BAND_6 = 6;
        public static final int BAND_7 = 7;
        public static final int BAND_8 = 8;
        public static final int BAND_9 = 9;
        public static final int BAND_10 = 10;
        public static final int BAND_11 = 11;
        public static final int BAND_12 = 12;
        public static final int BAND_13 = 13;
        public static final int BAND_14 = 14;
        public static final int BAND_17 = 17;
        public static final int BAND_18 = 18;
        public static final int BAND_19 = 19;
        public static final int BAND_20 = 20;
        public static final int BAND_21 = 21;
        public static final int BAND_22 = 22;
        public static final int BAND_23 = 23;
        public static final int BAND_24 = 24;
        public static final int BAND_25 = 25;
        public static final int BAND_26 = 26;
        public static final int BAND_27 = 27;
        public static final int BAND_28 = 28;
        public static final int BAND_30 = 30;
        public static final int BAND_31 = 31;
        public static final int BAND_33 = 33;
        public static final int BAND_34 = 34;
        public static final int BAND_35 = 35;
        public static final int BAND_36 = 36;
        public static final int BAND_37 = 37;
        public static final int BAND_38 = 38;
        public static final int BAND_39 = 39;
        public static final int BAND_40 = 40;
        public static final int BAND_41 = 41;
        public static final int BAND_42 = 42;
        public static final int BAND_43 = 43;
        public static final int BAND_44 = 44;
        public static final int BAND_45 = 45;
        public static final int BAND_46 = 46;
        public static final int BAND_47 = 47;
        public static final int BAND_48 = 48;
        public static final int BAND_65 = 65;
        public static final int BAND_66 = 66;
        public static final int BAND_68 = 68;
        public static final int BAND_70 = 70;

        /** @hide */
        private EutranBand() {};
    }

    /**
     * Frenquency bands for CDMA2000.
     * http://www.3gpp2.org/Public_html/Specs/C.S0057-E_v1.0_Bandclass_Specification.pdf
     * @hide
     *
     * TODO(yinxu): Check with the nexus team about the definition of CDMA bands.
     */
    public static final class CdmaBands {
        public static final int BAND_0 = 1;
        public static final int BAND_1 = 2;
        public static final int BAND_2 = 3;
        public static final int BAND_3 = 4;
        public static final int BAND_4 = 5;
        public static final int BAND_5 = 6;
        public static final int BAND_6 = 7;
        public static final int BAND_7 = 8;
        public static final int BAND_8 = 9;
        public static final int BAND_9 = 10;
        public static final int BAND_10 = 11;
        public static final int BAND_11 = 12;
        public static final int BAND_12 = 13;
        public static final int BAND_13 = 14;
        public static final int BAND_14 = 15;
        public static final int BAND_15 = 16;
        public static final int BAND_16 = 17;
        public static final int BAND_17 = 18;
        public static final int BAND_18 = 19;
        public static final int BAND_19 = 20;
        public static final int BAND_20 = 21;
        public static final int BAND_21 = 22;

        /** @hide */
        private CdmaBands() {};
    }

    /** @hide */
    private AccessNetworkConstants() {};
}
