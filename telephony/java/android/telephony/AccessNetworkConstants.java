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
import android.hardware.radio.V1_1.EutranBands;
import android.hardware.radio.V1_1.GeranBands;
import android.hardware.radio.V1_5.AccessNetwork;
import android.hardware.radio.V1_5.UtranBands;

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
        public static final int UNKNOWN = AccessNetwork.UNKNOWN;
        public static final int GERAN = AccessNetwork.GERAN;
        public static final int UTRAN = AccessNetwork.UTRAN;
        public static final int EUTRAN = AccessNetwork.EUTRAN;
        public static final int CDMA2000 = AccessNetwork.CDMA2000;
        public static final int IWLAN = AccessNetwork.IWLAN;
        public static final int NGRAN = AccessNetwork.NGRAN;

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
        public static final int BAND_T380 = GeranBands.BAND_T380;
        public static final int BAND_T410 = GeranBands.BAND_T410;
        public static final int BAND_450 = GeranBands.BAND_450;
        public static final int BAND_480 = GeranBands.BAND_480;
        public static final int BAND_710 = GeranBands.BAND_710;
        public static final int BAND_750 = GeranBands.BAND_750;
        public static final int BAND_T810 = GeranBands.BAND_T810;
        public static final int BAND_850 = GeranBands.BAND_850;
        public static final int BAND_P900 = GeranBands.BAND_P900;
        public static final int BAND_E900 = GeranBands.BAND_E900;
        public static final int BAND_R900 = GeranBands.BAND_R900;
        public static final int BAND_DCS1800 = GeranBands.BAND_DCS1800;
        public static final int BAND_PCS1900 = GeranBands.BAND_PCS1900;
        public static final int BAND_ER900 = GeranBands.BAND_ER900;

        /** @hide */
        private GeranBand() {}
    }

    /**
     * Frequency bands for UTRAN.
     * http://www.etsi.org/deliver/etsi_ts/125100_125199/125104/13.03.00_60/ts_125104v130p.pdf
     */
    public static final class UtranBand {
        public static final int BAND_1 = UtranBands.BAND_1;
        public static final int BAND_2 = UtranBands.BAND_2;
        public static final int BAND_3 = UtranBands.BAND_3;
        public static final int BAND_4 = UtranBands.BAND_4;
        public static final int BAND_5 = UtranBands.BAND_5;
        public static final int BAND_6 = UtranBands.BAND_6;
        public static final int BAND_7 = UtranBands.BAND_7;
        public static final int BAND_8 = UtranBands.BAND_8;
        public static final int BAND_9 = UtranBands.BAND_9;
        public static final int BAND_10 = UtranBands.BAND_10;
        public static final int BAND_11 = UtranBands.BAND_11;
        public static final int BAND_12 = UtranBands.BAND_12;
        public static final int BAND_13 = UtranBands.BAND_13;
        public static final int BAND_14 = UtranBands.BAND_14;
        // band 15, 16, 17, 18 are reserved
        public static final int BAND_19 = UtranBands.BAND_19;
        public static final int BAND_20 = UtranBands.BAND_20;
        public static final int BAND_21 = UtranBands.BAND_21;
        public static final int BAND_22 = UtranBands.BAND_22;
        // band 23, 24 are reserved
        public static final int BAND_25 = UtranBands.BAND_25;
        public static final int BAND_26 = UtranBands.BAND_26;

        // Frequency bands for TD-SCDMA. Defined in 3GPP TS 25.102, Table 5.2.

        /**
         * Band A
         * 1900 - 1920 MHz: Uplink and downlink transmission
         * 2010 - 2025 MHz: Uplink and downlink transmission
         */
        public static final int BAND_A = UtranBands.BAND_A;

        /**
         * Band B
         * 1850 - 1910 MHz: Uplink and downlink transmission
         * 1930 - 1990 MHz: Uplink and downlink transmission
         */
        public static final int BAND_B = UtranBands.BAND_B;

        /**
         * Band C
         * 1910 - 1930 MHz: Uplink and downlink transmission
         */
        public static final int BAND_C = UtranBands.BAND_C;

        /**
         * Band D
         * 2570 - 2620 MHz: Uplink and downlink transmission
         */
        public static final int BAND_D = UtranBands.BAND_D;

        /**
         * Band E
         * 2300—2400 MHz: Uplink and downlink transmission
         */
        public static final int BAND_E = UtranBands.BAND_E;

        /**
         * Band F
         * 1880 - 1920 MHz: Uplink and downlink transmission
         */
        public static final int BAND_F = UtranBands.BAND_F;

        /** @hide */
        private UtranBand() {}
    }

    /**
     * Frequency bands for EUTRAN.
     * http://www.etsi.org/deliver/etsi_ts/136100_136199/136101/14.03.00_60/ts_136101v140p.pdf
     */
    public static final class EutranBand {
        public static final int BAND_1 = EutranBands.BAND_1;
        public static final int BAND_2 = EutranBands.BAND_2;
        public static final int BAND_3 = EutranBands.BAND_3;
        public static final int BAND_4 = EutranBands.BAND_4;
        public static final int BAND_5 = EutranBands.BAND_5;
        public static final int BAND_6 = EutranBands.BAND_6;
        public static final int BAND_7 = EutranBands.BAND_7;
        public static final int BAND_8 = EutranBands.BAND_8;
        public static final int BAND_9 = EutranBands.BAND_9;
        public static final int BAND_10 = EutranBands.BAND_10;
        public static final int BAND_11 = EutranBands.BAND_11;
        public static final int BAND_12 = EutranBands.BAND_12;
        public static final int BAND_13 = EutranBands.BAND_13;
        public static final int BAND_14 = EutranBands.BAND_14;
        public static final int BAND_17 = EutranBands.BAND_17;
        public static final int BAND_18 = EutranBands.BAND_18;
        public static final int BAND_19 = EutranBands.BAND_19;
        public static final int BAND_20 = EutranBands.BAND_20;
        public static final int BAND_21 = EutranBands.BAND_21;
        public static final int BAND_22 = EutranBands.BAND_22;
        public static final int BAND_23 = EutranBands.BAND_23;
        public static final int BAND_24 = EutranBands.BAND_24;
        public static final int BAND_25 = EutranBands.BAND_25;
        public static final int BAND_26 = EutranBands.BAND_26;
        public static final int BAND_27 = EutranBands.BAND_27;
        public static final int BAND_28 = EutranBands.BAND_28;
        public static final int BAND_30 = EutranBands.BAND_30;
        public static final int BAND_31 = EutranBands.BAND_31;
        public static final int BAND_33 = EutranBands.BAND_33;
        public static final int BAND_34 = EutranBands.BAND_34;
        public static final int BAND_35 = EutranBands.BAND_35;
        public static final int BAND_36 = EutranBands.BAND_36;
        public static final int BAND_37 = EutranBands.BAND_37;
        public static final int BAND_38 = EutranBands.BAND_38;
        public static final int BAND_39 = EutranBands.BAND_39;
        public static final int BAND_40 = EutranBands.BAND_40;
        public static final int BAND_41 = EutranBands.BAND_41;
        public static final int BAND_42 = EutranBands.BAND_42;
        public static final int BAND_43 = EutranBands.BAND_43;
        public static final int BAND_44 = EutranBands.BAND_44;
        public static final int BAND_45 = EutranBands.BAND_45;
        public static final int BAND_46 = EutranBands.BAND_46;
        public static final int BAND_47 = EutranBands.BAND_47;
        public static final int BAND_48 = EutranBands.BAND_48;
        public static final int BAND_65 = EutranBands.BAND_65;
        public static final int BAND_66 = EutranBands.BAND_66;
        public static final int BAND_68 = EutranBands.BAND_68;
        public static final int BAND_70 = EutranBands.BAND_70;

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
        public static final int BAND_1 = android.hardware.radio.V1_5.NgranBands.BAND_1;
        public static final int BAND_2 = android.hardware.radio.V1_5.NgranBands.BAND_2;
        public static final int BAND_3 = android.hardware.radio.V1_5.NgranBands.BAND_3;
        public static final int BAND_5 = android.hardware.radio.V1_5.NgranBands.BAND_5;
        public static final int BAND_7 = android.hardware.radio.V1_5.NgranBands.BAND_7;
        public static final int BAND_8 = android.hardware.radio.V1_5.NgranBands.BAND_8;
        public static final int BAND_12 = android.hardware.radio.V1_5.NgranBands.BAND_12;
        public static final int BAND_14 = android.hardware.radio.V1_5.NgranBands.BAND_14;
        public static final int BAND_18 = android.hardware.radio.V1_5.NgranBands.BAND_18;
        public static final int BAND_20 = android.hardware.radio.V1_5.NgranBands.BAND_20;
        public static final int BAND_25 = android.hardware.radio.V1_5.NgranBands.BAND_25;
        public static final int BAND_28 = android.hardware.radio.V1_5.NgranBands.BAND_28;
        public static final int BAND_29 = android.hardware.radio.V1_5.NgranBands.BAND_29;
        public static final int BAND_30 = android.hardware.radio.V1_5.NgranBands.BAND_30;
        public static final int BAND_34 = android.hardware.radio.V1_5.NgranBands.BAND_34;
        public static final int BAND_38 = android.hardware.radio.V1_5.NgranBands.BAND_38;
        public static final int BAND_39 = android.hardware.radio.V1_5.NgranBands.BAND_39;
        public static final int BAND_40 = android.hardware.radio.V1_5.NgranBands.BAND_40;
        public static final int BAND_41 = android.hardware.radio.V1_5.NgranBands.BAND_41;
        public static final int BAND_48 = android.hardware.radio.V1_5.NgranBands.BAND_48;
        public static final int BAND_50 = android.hardware.radio.V1_5.NgranBands.BAND_50;
        public static final int BAND_51 = android.hardware.radio.V1_5.NgranBands.BAND_51;
        public static final int BAND_65 = android.hardware.radio.V1_5.NgranBands.BAND_65;
        public static final int BAND_66 = android.hardware.radio.V1_5.NgranBands.BAND_66;
        public static final int BAND_70 = android.hardware.radio.V1_5.NgranBands.BAND_70;
        public static final int BAND_71 = android.hardware.radio.V1_5.NgranBands.BAND_71;
        public static final int BAND_74 = android.hardware.radio.V1_5.NgranBands.BAND_74;
        public static final int BAND_75 = android.hardware.radio.V1_5.NgranBands.BAND_75;
        public static final int BAND_76 = android.hardware.radio.V1_5.NgranBands.BAND_76;
        public static final int BAND_77 = android.hardware.radio.V1_5.NgranBands.BAND_77;
        public static final int BAND_78 = android.hardware.radio.V1_5.NgranBands.BAND_78;
        public static final int BAND_79 = android.hardware.radio.V1_5.NgranBands.BAND_79;
        public static final int BAND_80 = android.hardware.radio.V1_5.NgranBands.BAND_80;
        public static final int BAND_81 = android.hardware.radio.V1_5.NgranBands.BAND_81;
        public static final int BAND_82 = android.hardware.radio.V1_5.NgranBands.BAND_82;
        public static final int BAND_83 = android.hardware.radio.V1_5.NgranBands.BAND_83;
        public static final int BAND_84 = android.hardware.radio.V1_5.NgranBands.BAND_84;
        public static final int BAND_86 = android.hardware.radio.V1_5.NgranBands.BAND_86;
        public static final int BAND_90 = android.hardware.radio.V1_5.NgranBands.BAND_90;

        /** FR2 bands */
        public static final int BAND_257 = android.hardware.radio.V1_5.NgranBands.BAND_257;
        public static final int BAND_258 = android.hardware.radio.V1_5.NgranBands.BAND_258;
        public static final int BAND_260 = android.hardware.radio.V1_5.NgranBands.BAND_260;
        public static final int BAND_261 = android.hardware.radio.V1_5.NgranBands.BAND_261;

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
