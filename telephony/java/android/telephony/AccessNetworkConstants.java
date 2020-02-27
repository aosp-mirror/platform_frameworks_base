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

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains access network related constants.
 */
public final class AccessNetworkConstants {

    /**
     * Wireless transportation type
     *
     * @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"TRANSPORT_TYPE_"},
            value = {
                    TRANSPORT_TYPE_INVALID,
                    TRANSPORT_TYPE_WWAN,
                    TRANSPORT_TYPE_WLAN})
    public @interface TransportType {}

    /**
     * Invalid transport type
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int TRANSPORT_TYPE_INVALID = -1;

    /**
     * Transport type for Wireless Wide Area Networks (i.e. Cellular)
     */
    public static final int TRANSPORT_TYPE_WWAN = 1;

    /**
     * Transport type for Wireless Local Area Networks (i.e. Wifi)
     */
    public static final int TRANSPORT_TYPE_WLAN = 2;

    /** @hide */
    public static String transportTypeToString(@TransportType int transportType) {
        switch (transportType) {
            case TRANSPORT_TYPE_WWAN: return "WWAN";
            case TRANSPORT_TYPE_WLAN: return "WLAN";
            default: return Integer.toString(transportType);
        }
    }

    /**
     * Access network type
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"RADIO_ACCESS_NETWORK_TYPE_"},
            value = {
                    AccessNetworkType.UNKNOWN,
                    AccessNetworkType.GERAN,
                    AccessNetworkType.UTRAN,
                    AccessNetworkType.EUTRAN,
                    AccessNetworkType.CDMA2000,
                    AccessNetworkType.IWLAN,
                    AccessNetworkType.NGRAN})
    public @interface RadioAccessNetworkType {}

    public static final class AccessNetworkType {
        public static final int UNKNOWN = 0;
        public static final int GERAN = 1;
        public static final int UTRAN = 2;
        public static final int EUTRAN = 3;
        public static final int CDMA2000 = 4;
        public static final int IWLAN = 5;
        public static final int NGRAN = 6;

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
                case NGRAN: return "NGRAN";
                default: return Integer.toString(type);
            }
        }
    }

    /**
     * Frequency bands for GERAN.
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
        private GeranBand() {}
    }

    /**
     * Frequency bands for UTRAN.
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
        // band 15, 16, 17, 18 are reserved
        public static final int BAND_19 = 19;
        public static final int BAND_20 = 20;
        public static final int BAND_21 = 21;
        public static final int BAND_22 = 22;
        // band 23, 24 are reserved
        public static final int BAND_25 = 25;
        public static final int BAND_26 = 26;

        // Frequency bands for TD-SCDMA. Defined in 3GPP TS 25.102, Table 5.2.

        /**
         * Band A
         * 1900 - 1920 MHz: Uplink and downlink transmission
         * 2010 - 2025 MHz: Uplink and downlink transmission
         */
        public static final int BAND_A = 101;

        /**
         * Band B
         * 1850 - 1910 MHz: Uplink and downlink transmission
         * 1930 - 1990 MHz: Uplink and downlink transmission
         */
        public static final int BAND_B = 102;

        /**
         * Band C
         * 1910 - 1930 MHz: Uplink and downlink transmission
         */
        public static final int BAND_C = 103;

        /**
         * Band D
         * 2570 - 2620 MHz: Uplink and downlink transmission
         */
        public static final int BAND_D = 104;

        /**
         * Band E
         * 2300—2400 MHz: Uplink and downlink transmission
         */
        public static final int BAND_E = 105;

        /**
         * Band F
         * 1880 - 1920 MHz: Uplink and downlink transmission
         */
        public static final int BAND_F = 106;

        /** @hide */
        private UtranBand() {}
    }

    /**
     * Frequency bands for EUTRAN.
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
     * Frequency bands for CDMA2000.
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
        private CdmaBands() {}
    }

    /**
     * Frequency bands for NGRAN
     */
    public static final class NgranBands {
        /** FR1 bands */
        public static final int BAND_1 = 1;
        public static final int BAND_2 = 2;
        public static final int BAND_3 = 3;
        public static final int BAND_5 = 5;
        public static final int BAND_7 = 7;
        public static final int BAND_8 = 8;
        public static final int BAND_12 = 12;
        public static final int BAND_14 = 14;
        public static final int BAND_18 = 18;
        public static final int BAND_20 = 20;
        public static final int BAND_25 = 25;
        public static final int BAND_28 = 28;
        public static final int BAND_29 = 29;
        public static final int BAND_30 = 30;
        public static final int BAND_34 = 34;
        public static final int BAND_38 = 38;
        public static final int BAND_39 = 39;
        public static final int BAND_40 = 40;
        public static final int BAND_41 = 41;
        public static final int BAND_48 = 48;
        public static final int BAND_50 = 50;
        public static final int BAND_51 = 51;
        public static final int BAND_65 = 65;
        public static final int BAND_66 = 66;
        public static final int BAND_70 = 70;
        public static final int BAND_71 = 71;
        public static final int BAND_74 = 74;
        public static final int BAND_75 = 75;
        public static final int BAND_76 = 76;
        public static final int BAND_77 = 77;
        public static final int BAND_78 = 78;
        public static final int BAND_79 = 79;
        public static final int BAND_80 = 80;
        public static final int BAND_81 = 81;
        public static final int BAND_82 = 82;
        public static final int BAND_83 = 83;
        public static final int BAND_84 = 84;
        public static final int BAND_86 = 86;
        public static final int BAND_90 = 90;

        /** FR2 bands */
        public static final int BAND_257 = 257;
        public static final int BAND_258 = 258;
        public static final int BAND_260 = 260;
        public static final int BAND_261 = 261;

        /**
         * NR Bands
         *
         * @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"BAND_"},
                value = {BAND_1,
                        BAND_2,
                        BAND_3,
                        BAND_5,
                        BAND_7,
                        BAND_8,
                        BAND_12,
                        BAND_14,
                        BAND_18,
                        BAND_20,
                        BAND_25,
                        BAND_28,
                        BAND_29,
                        BAND_30,
                        BAND_34,
                        BAND_38,
                        BAND_39,
                        BAND_40,
                        BAND_41,
                        BAND_48,
                        BAND_50,
                        BAND_51,
                        BAND_65,
                        BAND_66,
                        BAND_70,
                        BAND_71,
                        BAND_74,
                        BAND_75,
                        BAND_76,
                        BAND_77,
                        BAND_78,
                        BAND_79,
                        BAND_80,
                        BAND_81,
                        BAND_82,
                        BAND_83,
                        BAND_84,
                        BAND_86,
                        BAND_90,
                        BAND_257,
                        BAND_258,
                        BAND_260,
                        BAND_261})
        public @interface NgranBand {}

        /**
         * Unknown NR frequency.
         *
         * @hide
         */
        @SystemApi
        @TestApi
        public static final int FREQUENCY_RANGE_GROUP_UNKNOWN = 0;

        /**
         * NR frequency group 1 defined in 3GPP TS 38.101-1 table 5.2-1
         *
         * @hide
         */
        @SystemApi
        @TestApi
        public static final int FREQUENCY_RANGE_GROUP_1 = 1;

        /**
         * NR frequency group 2 defined in 3GPP TS 38.101-2 table 5.2-1
         *
         * @hide
         */
        @SystemApi
        @TestApi
        public static final int FREQUENCY_RANGE_GROUP_2 = 2;

        /**
         * Radio frequency range group
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"FREQUENCY_RANGE_GROUP_"},
                value = {
                        FREQUENCY_RANGE_GROUP_UNKNOWN,
                        FREQUENCY_RANGE_GROUP_1,
                        FREQUENCY_RANGE_GROUP_2})
        public @interface FrequencyRangeGroup {}

        /**
         * Get frequency range group
         *
         * @param band NR band
         * @return The frequency range group
         *
         * @hide
         */
        @SystemApi
        @TestApi
        public static @FrequencyRangeGroup int getFrequencyRangeGroup(@NgranBand int band) {
            switch (band) {
                case BAND_1:
                case BAND_2:
                case BAND_3:
                case BAND_5:
                case BAND_7:
                case BAND_8:
                case BAND_12:
                case BAND_14:
                case BAND_18:
                case BAND_20:
                case BAND_25:
                case BAND_28:
                case BAND_29:
                case BAND_30:
                case BAND_34:
                case BAND_38:
                case BAND_39:
                case BAND_40:
                case BAND_41:
                case BAND_48:
                case BAND_50:
                case BAND_51:
                case BAND_65:
                case BAND_66:
                case BAND_70:
                case BAND_71:
                case BAND_74:
                case BAND_75:
                case BAND_76:
                case BAND_77:
                case BAND_78:
                case BAND_79:
                case BAND_80:
                case BAND_81:
                case BAND_82:
                case BAND_83:
                case BAND_84:
                case BAND_86:
                case BAND_90:
                    return FREQUENCY_RANGE_GROUP_1;
                case BAND_257:
                case BAND_258:
                case BAND_260:
                case BAND_261:
                    return FREQUENCY_RANGE_GROUP_2;
                default:
                    return FREQUENCY_RANGE_GROUP_UNKNOWN;
            }
        };

        /** @hide */
        private NgranBands() {}
    }

    /** @hide */
    private AccessNetworkConstants() {};
}
