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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.radio.AccessNetwork;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;

/**
 * Contains access network related constants.
 */
public final class AccessNetworkConstants {

    private static final String TAG = AccessNetworkConstants.class.getSimpleName();

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
            case TRANSPORT_TYPE_INVALID: return "INVALID";
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

        /** @hide */
        public static @RadioAccessNetworkType int fromString(@NonNull String str) {
            switch (str.toUpperCase(Locale.ROOT)) {
                case "UNKNOWN": return UNKNOWN;
                case "GERAN": return GERAN;
                case "UTRAN": return UTRAN;
                case "EUTRAN": return EUTRAN;
                case "CDMA2000": return CDMA2000;
                case "IWLAN": return IWLAN;
                case "NGRAN": return NGRAN;
                default:
                    throw new IllegalArgumentException("Invalid access network type " + str);
            }
        }
    }

    /**
     * Frequency bands for GERAN.
     * http://www.etsi.org/deliver/etsi_ts/145000_145099/145005/14.00.00_60/ts_145005v140000p.pdf
     */
    public static final class GeranBand {
        public static final int BAND_T380 = android.hardware.radio.network.GeranBands.BAND_T380;
        public static final int BAND_T410 = android.hardware.radio.network.GeranBands.BAND_T410;
        public static final int BAND_450 = android.hardware.radio.network.GeranBands.BAND_450;
        public static final int BAND_480 = android.hardware.radio.network.GeranBands.BAND_480;
        public static final int BAND_710 = android.hardware.radio.network.GeranBands.BAND_710;
        public static final int BAND_750 = android.hardware.radio.network.GeranBands.BAND_750;
        public static final int BAND_T810 = android.hardware.radio.network.GeranBands.BAND_T810;
        public static final int BAND_850 = android.hardware.radio.network.GeranBands.BAND_850;
        public static final int BAND_P900 = android.hardware.radio.network.GeranBands.BAND_P900;
        public static final int BAND_E900 = android.hardware.radio.network.GeranBands.BAND_E900;
        public static final int BAND_R900 = android.hardware.radio.network.GeranBands.BAND_R900;
        public static final int BAND_DCS1800 =
                android.hardware.radio.network.GeranBands.BAND_DCS1800;
        public static final int BAND_PCS1900 =
                android.hardware.radio.network.GeranBands.BAND_PCS1900;
        public static final int BAND_ER900 = android.hardware.radio.network.GeranBands.BAND_ER900;

        /**
         * GeranBand
         *
         * @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"BAND_"},
                value = {BAND_T380,
                        BAND_T410,
                        BAND_450,
                        BAND_480,
                        BAND_710,
                        BAND_750,
                        BAND_T810,
                        BAND_850,
                        BAND_P900,
                        BAND_E900,
                        BAND_R900,
                        BAND_DCS1800,
                        BAND_PCS1900,
                        BAND_ER900})

        public @interface GeranBands {}

        /** @hide */
        private GeranBand() {}
    }

    /**
     * 3GPP TS 45.005 Table 2-1 Dynamically mapped ARFCN.
     * 3GPP TS 45.005 Table 2-2 Fixed designation of ARFCN.
     * @hide
     */
    enum GeranBandArfcnFrequency {

        // Dynamically mapped ARFCN
//        GERAN_ARFCN_FREQUENCY_BAND_T380(GeranBand.BAND_T380, 380.2, 0),
//        GERAN_ARFCN_FREQUENCY_BAND_T410(GeranBand.BAND_T410, 410.2, 0),
//        GERAN_ARFCN_FREQUENCY_BAND_710(GeranBand.BAND_710, 698, 0),
//        GERAN_ARFCN_FREQUENCY_BAND_750(GeranBand.BAND_750, 747, 438, 30),
//        GERAN_ARFCN_FREQUENCY_BAND_T810(GeranBand.BAND_T810, 806, 350),
        // Fixed designation of ARFCN
        GERAN_ARFCN_FREQUENCY_BAND_450(GeranBand.BAND_450, 450600, 259, 259, 293, 10),
        GERAN_ARFCN_FREQUENCY_BAND_480(GeranBand.BAND_480, 479000, 306, 306, 340, 10),
        GERAN_ARFCN_FREQUENCY_BAND_850(GeranBand.BAND_850, 824200, 128, 128, 251, 45),
        GERAN_ARFCN_FREQUENCY_BAND_DCS1800(GeranBand.BAND_DCS1800, 1710200, 512, 512, 885, 95),
        GERAN_ARFCN_FREQUENCY_BAND_PCS1900(GeranBand.BAND_PCS1900, 1850200, 512, 512, 810, 80),
        GERAN_ARFCN_FREQUENCY_BAND_E900_1(GeranBand.BAND_E900, 890000, 0, 0, 124, 45),
        GERAN_ARFCN_FREQUENCY_BAND_E900_2(GeranBand.BAND_E900, 890000, 1024, 975, 1023, 45),
        GERAN_ARFCN_FREQUENCY_BAND_R900_1(GeranBand.BAND_R900, 890000, 0, 0, 124, 45),
        GERAN_ARFCN_FREQUENCY_BAND_R900_2(GeranBand.BAND_R900, 890000, 1024, 955, 1023, 45),
        GERAN_ARFCN_FREQUENCY_BAND_P900(GeranBand.BAND_P900, 890000, 0, 1, 124, 45),
        GERAN_ARFCN_FREQUENCY_BAND_ER900_1(GeranBand.BAND_ER900, 890000, 0, 0, 124, 45),
        GERAN_ARFCN_FREQUENCY_BAND_ER900_2(GeranBand.BAND_ER900, 890000, 1024, 940, 1023, 1024);

        GeranBandArfcnFrequency(int band, int uplinkFrequencyFirstKhz, int arfcnOffset,
                                int arfcnRangeFirst, int arfcnRangeLast, int downlinkOffset) {
            this.band = band;
            this.uplinkFrequencyFirst = uplinkFrequencyFirstKhz;
            this.arfcnOffset = arfcnOffset;
            this.arfcnRangeFirst = arfcnRangeFirst;
            this.arfcnRangeLast = arfcnRangeLast;
            this.downlinkOffset = downlinkOffset;
        }

        int band;
        int uplinkFrequencyFirst;
        int arfcnOffset;
        int arfcnRangeFirst;
        int arfcnRangeLast;
        int downlinkOffset;
    }

    /**
     * Frequency bands for UTRAN.
     * http://www.etsi.org/deliver/etsi_ts/125100_125199/125104/13.03.00_60/ts_125104v130p.pdf
     */
    public static final class UtranBand {
        public static final int BAND_1 = android.hardware.radio.network.UtranBands.BAND_1;
        public static final int BAND_2 = android.hardware.radio.network.UtranBands.BAND_2;
        public static final int BAND_3 = android.hardware.radio.network.UtranBands.BAND_3;
        public static final int BAND_4 = android.hardware.radio.network.UtranBands.BAND_4;
        public static final int BAND_5 = android.hardware.radio.network.UtranBands.BAND_5;
        public static final int BAND_6 = android.hardware.radio.network.UtranBands.BAND_6;
        public static final int BAND_7 = android.hardware.radio.network.UtranBands.BAND_7;
        public static final int BAND_8 = android.hardware.radio.network.UtranBands.BAND_8;
        public static final int BAND_9 = android.hardware.radio.network.UtranBands.BAND_9;
        public static final int BAND_10 = android.hardware.radio.network.UtranBands.BAND_10;
        public static final int BAND_11 = android.hardware.radio.network.UtranBands.BAND_11;
        public static final int BAND_12 = android.hardware.radio.network.UtranBands.BAND_12;
        public static final int BAND_13 = android.hardware.radio.network.UtranBands.BAND_13;
        public static final int BAND_14 = android.hardware.radio.network.UtranBands.BAND_14;
        // band 15, 16, 17, 18 are reserved
        public static final int BAND_19 = android.hardware.radio.network.UtranBands.BAND_19;
        public static final int BAND_20 = android.hardware.radio.network.UtranBands.BAND_20;
        public static final int BAND_21 = android.hardware.radio.network.UtranBands.BAND_21;
        public static final int BAND_22 = android.hardware.radio.network.UtranBands.BAND_22;
        // band 23, 24 are reserved
        public static final int BAND_25 = android.hardware.radio.network.UtranBands.BAND_25;
        public static final int BAND_26 = android.hardware.radio.network.UtranBands.BAND_26;

        // Frequency bands for TD-SCDMA. Defined in 3GPP TS 25.102, Table 5.2.

        /**
         * Band A
         * 1900 - 1920 MHz: Uplink and downlink transmission
         * 2010 - 2025 MHz: Uplink and downlink transmission
         */
        public static final int BAND_A = android.hardware.radio.network.UtranBands.BAND_A;

        /**
         * Band B
         * 1850 - 1910 MHz: Uplink and downlink transmission
         * 1930 - 1990 MHz: Uplink and downlink transmission
         */
        public static final int BAND_B = android.hardware.radio.network.UtranBands.BAND_B;

        /**
         * Band C
         * 1910 - 1930 MHz: Uplink and downlink transmission
         */
        public static final int BAND_C = android.hardware.radio.network.UtranBands.BAND_C;

        /**
         * Band D
         * 2570 - 2620 MHz: Uplink and downlink transmission
         */
        public static final int BAND_D = android.hardware.radio.network.UtranBands.BAND_D;

        /**
         * Band E
         * 2300—2400 MHz: Uplink and downlink transmission
         */
        public static final int BAND_E = android.hardware.radio.network.UtranBands.BAND_E;

        /**
         * Band F
         * 1880 - 1920 MHz: Uplink and downlink transmission
         */
        public static final int BAND_F = android.hardware.radio.network.UtranBands.BAND_F;

        /**
         * UtranBand
         *
         * @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"BAND_"},
                value = {BAND_1,
                        BAND_2,
                        BAND_3,
                        BAND_4,
                        BAND_5,
                        BAND_6,
                        BAND_7,
                        BAND_8,
                        BAND_9,
                        BAND_10,
                        BAND_11,
                        BAND_12,
                        BAND_13,
                        BAND_14,
                        BAND_19,
                        BAND_20,
                        BAND_21,
                        BAND_22,
                        BAND_25,
                        BAND_26,
                        BAND_A,
                        BAND_B,
                        BAND_C,
                        BAND_D,
                        BAND_E,
                        BAND_F})

        public @interface UtranBands {}

        /** @hide */
        private UtranBand() {}
    }

    /**
     * 3GPP TS 25.101, Table 5.1 UARFCN definition (general)
     * 3GPP TS 25.102, Table 5.2 UTRA Absolute Radio Frequency Channel Number 1.28 Mcps TDD Option.
     *
     * @hide
     */
    enum UtranBandArfcnFrequency {

        UTRAN_ARFCN_FREQUENCY_BAND_1(UtranBand.BAND_1, 0, 10562, 10838, 0, 9612, 9888),
        UTRAN_ARFCN_FREQUENCY_BAND_2(UtranBand.BAND_2, 0, 9662, 9938, 0, 9262, 9538),
        UTRAN_ARFCN_FREQUENCY_BAND_3(UtranBand.BAND_3, 1575000, 1162, 1513, 1525000, 937, 1288),
        UTRAN_ARFCN_FREQUENCY_BAND_4(UtranBand.BAND_4, 1805000, 1537, 1738, 1450000, 1312, 1513),
        UTRAN_ARFCN_FREQUENCY_BAND_5(UtranBand.BAND_5, 0, 4357, 4458, 0, 4132, 4233),
        UTRAN_ARFCN_FREQUENCY_BAND_6(UtranBand.BAND_6, 0, 4387, 4413, 0, 4162, 4188),
        UTRAN_ARFCN_FREQUENCY_BAND_7(UtranBand.BAND_7, 2175000, 2237, 2563, 2100000, 2012, 2338),
        UTRAN_ARFCN_FREQUENCY_BAND_8(UtranBand.BAND_8, 340000, 2937, 3088, 340000, 2712, 2863),
        UTRAN_ARFCN_FREQUENCY_BAND_9(UtranBand.BAND_9, 0, 9327, 9837, 0, 8762, 8912),
        UTRAN_ARFCN_FREQUENCY_BAND_10(UtranBand.BAND_10, 1490000, 3112, 3388, 1135000, 2887, 3163),
        UTRAN_ARFCN_FREQUENCY_BAND_11(UtranBand.BAND_11, 736000, 3712, 3787, 733000, 3487, 3562),
        UTRAN_ARFCN_FREQUENCY_BAND_12(UtranBand.BAND_12, -37000, 3842, 3903, -22000, 3617, 3678),
        UTRAN_ARFCN_FREQUENCY_BAND_13(UtranBand.BAND_13, -55000, 4017, 4043, 21000, 3792, 3818),
        UTRAN_ARFCN_FREQUENCY_BAND_14(UtranBand.BAND_14, -63000, 4117, 4143, 12000, 3892, 3918),
        UTRAN_ARFCN_FREQUENCY_BAND_19(UtranBand.BAND_19, 735000, 712, 763, 770000, 312, 363),
        UTRAN_ARFCN_FREQUENCY_BAND_20(UtranBand.BAND_20, -109000, 4512, 4638, -23000, 4287, 4413),
        UTRAN_ARFCN_FREQUENCY_BAND_21(UtranBand.BAND_21, 1326000, 862, 912, 1358000, 462, 512),
        UTRAN_ARFCN_FREQUENCY_BAND_22(UtranBand.BAND_22, 2580000, 4662, 5038, 2525000, 4437, 4813),
        UTRAN_ARFCN_FREQUENCY_BAND_25(UtranBand.BAND_25, 910000, 5112, 5413, 875000, 4887, 5188),
        UTRAN_ARFCN_FREQUENCY_BAND_A(UtranBand.BAND_A, 0, 10054, 10121, 0, 9504, 9596),
        UTRAN_ARFCN_FREQUENCY_BAND_B(UtranBand.BAND_B, 0, 9654, 9946, 0, 9254, 9546),
        UTRAN_ARFCN_FREQUENCY_BAND_C(UtranBand.BAND_C, 0, 0, 0, 0, 9554, 9646),
        UTRAN_ARFCN_FREQUENCY_BAND_D(UtranBand.BAND_D, 0, 0, 0, 0, 12854, 13096),
        UTRAN_ARFCN_FREQUENCY_BAND_E(UtranBand.BAND_E, 0, 0, 0, 0, 11504, 11996),
        UTRAN_ARFCN_FREQUENCY_BAND_F(UtranBand.BAND_F, 0, 0, 0, 0, 9404, 9596);

        UtranBandArfcnFrequency(int band, int downlinkOffsetKhz, int downlinkRangeFirst,
                                int downlinkRangeLast, int uplinkOffsetKhz, int uplinkRangeFirst,
                                int uplinkRangeLast) {
            this.band = band;
            this.downlinkOffset = downlinkOffsetKhz;
            this.downlinkRangeFirst = downlinkRangeFirst;
            this.downlinkRangeLast = downlinkRangeLast;
            this.uplinkOffset = uplinkOffsetKhz;
            this.uplinkRangeFirst = uplinkRangeFirst;
            this.uplinkRangeLast = uplinkRangeLast;
        }

        int band;
        int downlinkOffset;
        int downlinkRangeFirst;
        int downlinkRangeLast;
        int uplinkOffset;
        int uplinkRangeFirst;
        int uplinkRangeLast;
    }

    /**
     * Frequency bands for EUTRAN.
     * 3GPP TS 36.101, Version 16.4.0, Table 5.5: Operating bands
     * https://www.etsi.org/deliver/etsi_ts/136100_136199/136101/15.09.00_60/ts_136101v150900p.pdf
     */
    public static final class EutranBand {
        public static final int BAND_1 = android.hardware.radio.network.EutranBands.BAND_1;
        public static final int BAND_2 = android.hardware.radio.network.EutranBands.BAND_2;
        public static final int BAND_3 = android.hardware.radio.network.EutranBands.BAND_3;
        public static final int BAND_4 = android.hardware.radio.network.EutranBands.BAND_4;
        public static final int BAND_5 = android.hardware.radio.network.EutranBands.BAND_5;
        public static final int BAND_6 = android.hardware.radio.network.EutranBands.BAND_6;
        public static final int BAND_7 = android.hardware.radio.network.EutranBands.BAND_7;
        public static final int BAND_8 = android.hardware.radio.network.EutranBands.BAND_8;
        public static final int BAND_9 = android.hardware.radio.network.EutranBands.BAND_9;
        public static final int BAND_10 = android.hardware.radio.network.EutranBands.BAND_10;
        public static final int BAND_11 = android.hardware.radio.network.EutranBands.BAND_11;
        public static final int BAND_12 = android.hardware.radio.network.EutranBands.BAND_12;
        public static final int BAND_13 = android.hardware.radio.network.EutranBands.BAND_13;
        public static final int BAND_14 = android.hardware.radio.network.EutranBands.BAND_14;
        public static final int BAND_17 = android.hardware.radio.network.EutranBands.BAND_17;
        public static final int BAND_18 = android.hardware.radio.network.EutranBands.BAND_18;
        public static final int BAND_19 = android.hardware.radio.network.EutranBands.BAND_19;
        public static final int BAND_20 = android.hardware.radio.network.EutranBands.BAND_20;
        public static final int BAND_21 = android.hardware.radio.network.EutranBands.BAND_21;
        public static final int BAND_22 = android.hardware.radio.network.EutranBands.BAND_22;
        public static final int BAND_23 = android.hardware.radio.network.EutranBands.BAND_23;
        public static final int BAND_24 = android.hardware.radio.network.EutranBands.BAND_24;
        public static final int BAND_25 = android.hardware.radio.network.EutranBands.BAND_25;
        public static final int BAND_26 = android.hardware.radio.network.EutranBands.BAND_26;
        public static final int BAND_27 = android.hardware.radio.network.EutranBands.BAND_27;
        public static final int BAND_28 = android.hardware.radio.network.EutranBands.BAND_28;
        public static final int BAND_30 = android.hardware.radio.network.EutranBands.BAND_30;
        public static final int BAND_31 = android.hardware.radio.network.EutranBands.BAND_31;
        public static final int BAND_33 = android.hardware.radio.network.EutranBands.BAND_33;
        public static final int BAND_34 = android.hardware.radio.network.EutranBands.BAND_34;
        public static final int BAND_35 = android.hardware.radio.network.EutranBands.BAND_35;
        public static final int BAND_36 = android.hardware.radio.network.EutranBands.BAND_36;
        public static final int BAND_37 = android.hardware.radio.network.EutranBands.BAND_37;
        public static final int BAND_38 = android.hardware.radio.network.EutranBands.BAND_38;
        public static final int BAND_39 = android.hardware.radio.network.EutranBands.BAND_39;
        public static final int BAND_40 = android.hardware.radio.network.EutranBands.BAND_40;
        public static final int BAND_41 = android.hardware.radio.network.EutranBands.BAND_41;
        public static final int BAND_42 = android.hardware.radio.network.EutranBands.BAND_42;
        public static final int BAND_43 = android.hardware.radio.network.EutranBands.BAND_43;
        public static final int BAND_44 = android.hardware.radio.network.EutranBands.BAND_44;
        public static final int BAND_45 = android.hardware.radio.network.EutranBands.BAND_45;
        public static final int BAND_46 = android.hardware.radio.network.EutranBands.BAND_46;
        public static final int BAND_47 = android.hardware.radio.network.EutranBands.BAND_47;
        public static final int BAND_48 = android.hardware.radio.network.EutranBands.BAND_48;
        public static final int BAND_49 = android.hardware.radio.network.EutranBands.BAND_49;
        public static final int BAND_50 = android.hardware.radio.network.EutranBands.BAND_50;
        public static final int BAND_51 = android.hardware.radio.network.EutranBands.BAND_51;
        public static final int BAND_52 = android.hardware.radio.network.EutranBands.BAND_52;
        public static final int BAND_53 = android.hardware.radio.network.EutranBands.BAND_53;
        public static final int BAND_65 = android.hardware.radio.network.EutranBands.BAND_65;
        public static final int BAND_66 = android.hardware.radio.network.EutranBands.BAND_66;
        public static final int BAND_68 = android.hardware.radio.network.EutranBands.BAND_68;
        public static final int BAND_70 = android.hardware.radio.network.EutranBands.BAND_70;
        public static final int BAND_71 = android.hardware.radio.network.EutranBands.BAND_71;
        public static final int BAND_72 = android.hardware.radio.network.EutranBands.BAND_72;
        public static final int BAND_73 = android.hardware.radio.network.EutranBands.BAND_73;
        public static final int BAND_74 = android.hardware.radio.network.EutranBands.BAND_74;
        public static final int BAND_85 = android.hardware.radio.network.EutranBands.BAND_85;
        public static final int BAND_87 = android.hardware.radio.network.EutranBands.BAND_87;
        public static final int BAND_88 = android.hardware.radio.network.EutranBands.BAND_88;

        /**
         * EutranBands
         *
         * @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"BAND_"},
                value = {BAND_1,
                        BAND_2,
                        BAND_3,
                        BAND_4,
                        BAND_5,
                        BAND_6,
                        BAND_7,
                        BAND_8,
                        BAND_9,
                        BAND_10,
                        BAND_11,
                        BAND_12,
                        BAND_13,
                        BAND_14,
                        BAND_17,
                        BAND_18,
                        BAND_19,
                        BAND_20,
                        BAND_21,
                        BAND_22,
                        BAND_23,
                        BAND_24,
                        BAND_25,
                        BAND_26,
                        BAND_27,
                        BAND_28,
                        BAND_30,
                        BAND_31,
                        BAND_33,
                        BAND_34,
                        BAND_35,
                        BAND_36,
                        BAND_37,
                        BAND_38,
                        BAND_39,
                        BAND_40,
                        BAND_41,
                        BAND_42,
                        BAND_43,
                        BAND_44,
                        BAND_45,
                        BAND_46,
                        BAND_47,
                        BAND_48,
                        BAND_49,
                        BAND_50,
                        BAND_51,
                        BAND_52,
                        BAND_53,
                        BAND_65,
                        BAND_66,
                        BAND_68,
                        BAND_70,
                        BAND_71,
                        BAND_72,
                        BAND_73,
                        BAND_74,
                        BAND_85,
                        BAND_87,
                        BAND_88})

        public @interface EutranBands {}

        /** @hide */
        private EutranBand() {};
    }

    /**
     * 3GPP TS 36.101 Table 5.7.3-1 E-UTRA channel numbers.
     *
     * @hide
     */
    enum EutranBandArfcnFrequency {

        EUTRAN_ARFCN_FREQUENCY_BAND_1(
                EutranBand.BAND_1, 2110000, 0, 599, 1920000, 18800, 18599),
        EUTRAN_ARFCN_FREQUENCY_BAND_2(
                EutranBand.BAND_2, 1930000, 600, 1199, 1850000, 18600, 19199),
        EUTRAN_ARFCN_FREQUENCY_BAND_3(
                EutranBand.BAND_3, 1805000, 1200, 1949, 1710000, 19200, 19949),
        EUTRAN_ARFCN_FREQUENCY_BAND_4(
                EutranBand.BAND_4, 2110000, 1950, 2399, 1710000, 19950, 20399),
        EUTRAN_ARFCN_FREQUENCY_BAND_5(
                EutranBand.BAND_5, 869000, 2400, 2649, 824000, 20400, 20649),
        EUTRAN_ARFCN_FREQUENCY_BAND_6(
                EutranBand.BAND_6, 875000, 2650, 2749, 830000, 20650, 20749),
        EUTRAN_ARFCN_FREQUENCY_BAND_7(
                EutranBand.BAND_7, 2620000, 2750, 3449, 2500000, 20750, 21449),
        EUTRAN_ARFCN_FREQUENCY_BAND_8(
                EutranBand.BAND_8, 925000, 3450, 3799, 880000, 21450, 21799),
        EUTRAN_ARFCN_FREQUENCY_BAND_9(
                EutranBand.BAND_9, 1844900, 3800, 4149, 1749900, 21800, 22149),
        EUTRAN_ARFCN_FREQUENCY_BAND_10(
                EutranBand.BAND_10, 2110000, 4150, 4749, 1710000, 22150, 22749),
        EUTRAN_ARFCN_FREQUENCY_BAND_11(
                EutranBand.BAND_11, 1475900, 4750, 4949, 1427900, 22750, 22949),
        EUTRAN_ARFCN_FREQUENCY_BAND_12(
                EutranBand.BAND_12, 729000, 5010, 5179, 699000, 23010, 23179),
        EUTRAN_ARFCN_FREQUENCY_BAND_13(
                EutranBand.BAND_13, 746000, 5180, 5279, 777000, 23180, 23279),
        EUTRAN_ARFCN_FREQUENCY_BAND_14(
                EutranBand.BAND_14, 758000, 5280, 5379, 788000, 23230, 23379),
        EUTRAN_ARFCN_FREQUENCY_BAND_17(
                EutranBand.BAND_17, 734000, 5730, 5849, 704000, 23730, 23849),
        EUTRAN_ARFCN_FREQUENCY_BAND_18(
                EutranBand.BAND_18, 860000, 5850, 5999, 815000, 23850, 23999),
        EUTRAN_ARFCN_FREQUENCY_BAND_19(
                EutranBand.BAND_19, 875000, 6000, 6149, 830000, 24000, 24149),
        EUTRAN_ARFCN_FREQUENCY_BAND_20(
                EutranBand.BAND_20, 791000, 6150, 6449, 832000, 24150, 24449),
        EUTRAN_ARFCN_FREQUENCY_BAND_21(
                EutranBand.BAND_21, 1495900, 6450, 6599, 1447900, 24450, 24599),
        EUTRAN_ARFCN_FREQUENCY_BAND_22(
                EutranBand.BAND_22, 3510000, 6600, 7399, 3410000, 24600, 25399),
        EUTRAN_ARFCN_FREQUENCY_BAND_23(
                EutranBand.BAND_23, 2180000, 7500, 7699, 2000000, 25500, 25699),
        EUTRAN_ARFCN_FREQUENCY_BAND_24(
                EutranBand.BAND_24, 1525000, 7700, 8039, 1626500, 25700, 26039),
        EUTRAN_ARFCN_FREQUENCY_BAND_25(
                EutranBand.BAND_25, 1930000, 8040, 8689, 1850000, 26040, 26689),
        EUTRAN_ARFCN_FREQUENCY_BAND_26(
                EutranBand.BAND_26, 859000, 8690, 9039, 814000, 26690, 27039),
        EUTRAN_ARFCN_FREQUENCY_BAND_27(
                EutranBand.BAND_27, 852000, 9040, 9209, 807000, 27040, 27209),
        EUTRAN_ARFCN_FREQUENCY_BAND_28(
                EutranBand.BAND_28, 758000, 9210, 9659, 703000, 27210, 27659),
        EUTRAN_ARFCN_FREQUENCY_BAND_30(
                EutranBand.BAND_30, 2350000, 9770, 9869, 2305000, 27660, 27759),
        EUTRAN_ARFCN_FREQUENCY_BAND_31(
                EutranBand.BAND_31, 462500, 9870, 9919, 452500, 27760, 27809),
        EUTRAN_ARFCN_FREQUENCY_BAND_33(
                EutranBand.BAND_33, 1900000, 36000, 36199, 1900000, 36000, 36199),
        EUTRAN_ARFCN_FREQUENCY_BAND_34(
                EutranBand.BAND_34, 2010000, 36200, 36349, 2010000, 36200, 36349),
        EUTRAN_ARFCN_FREQUENCY_BAND_35(
                EutranBand.BAND_35, 1850000, 36350, 36949, 1850000, 36350, 36949),
        EUTRAN_ARFCN_FREQUENCY_BAND_36(
                EutranBand.BAND_36, 1930000, 36950, 37549, 1930000, 36950, 37549),
        EUTRAN_ARFCN_FREQUENCY_BAND_37(
                EutranBand.BAND_37, 1910000, 37550, 37749, 1910000, 37550, 37749),
        EUTRAN_ARFCN_FREQUENCY_BAND_38(
                EutranBand.BAND_38, 2570000, 37750, 38249, 2570000, 37750, 38249),
        EUTRAN_ARFCN_FREQUENCY_BAND_39(
                EutranBand.BAND_39, 1880000, 38250, 38649, 1880000, 38250, 38649),
        EUTRAN_ARFCN_FREQUENCY_BAND_40(
                EutranBand.BAND_40, 2300000, 38650, 39649, 2300000, 38650, 39649),
        EUTRAN_ARFCN_FREQUENCY_BAND_41(
                EutranBand.BAND_41, 2496000, 39650, 41589, 2496000, 39650, 41589),
        EUTRAN_ARFCN_FREQUENCY_BAND_42(
                EutranBand.BAND_42, 3400000, 41590, 43589, 3400000, 41590, 43589),
        EUTRAN_ARFCN_FREQUENCY_BAND_43(
                EutranBand.BAND_43, 3600000, 43590, 45589, 3600000, 43590, 45589),
        EUTRAN_ARFCN_FREQUENCY_BAND_44(
                EutranBand.BAND_44, 703000, 45590, 46589, 703000, 45590, 46589),
        EUTRAN_ARFCN_FREQUENCY_BAND_45(
                EutranBand.BAND_45, 1447000, 46590, 46789, 1447000, 46590, 46789),
        EUTRAN_ARFCN_FREQUENCY_BAND_46(
                EutranBand.BAND_46, 5150000, 46790, 54539, 5150000, 46790, 54539),
        EUTRAN_ARFCN_FREQUENCY_BAND_47(
                EutranBand.BAND_47, 5855000, 54540, 55239, 5855000, 54540, 55239),
        EUTRAN_ARFCN_FREQUENCY_BAND_48(
                EutranBand.BAND_48, 3550000, 55240, 56739, 3550000, 55240, 56739),
        EUTRAN_ARFCN_FREQUENCY_BAND_49(
                EutranBand.BAND_49, 3550000, 56740, 58239, 3550000, 56740, 58239),
        EUTRAN_ARFCN_FREQUENCY_BAND_50(
                EutranBand.BAND_50, 1432000, 58240, 59089, 1432000, 58240, 59089),
        EUTRAN_ARFCN_FREQUENCY_BAND_51(
                EutranBand.BAND_51, 1427000, 59090, 59139, 1427000, 59090, 59139),
        EUTRAN_ARFCN_FREQUENCY_BAND_52(
                EutranBand.BAND_52, 3300000, 59140, 60139, 3300000, 59140, 60139),
        EUTRAN_ARFCN_FREQUENCY_BAND_53(
                EutranBand.BAND_53, 2483500, 60140, 60254, 2483500, 60140, 60254),
        EUTRAN_ARFCN_FREQUENCY_BAND_65(
                EutranBand.BAND_65, 2110000, 65536, 66435, 1920000, 131072, 131971),
        EUTRAN_ARFCN_FREQUENCY_BAND_66(
                EutranBand.BAND_66, 2110000, 66436, 67335, 1710000, 131972, 132671),
        EUTRAN_ARFCN_FREQUENCY_BAND_68(
                EutranBand.BAND_68, 753000, 67536, 67835, 698000, 132672, 132971),
        EUTRAN_ARFCN_FREQUENCY_BAND_70(
                EutranBand.BAND_70, 1995000, 68336, 68585, 1695000, 132972, 133121),
        EUTRAN_ARFCN_FREQUENCY_BAND_71(
                EutranBand.BAND_71, 617000, 68586, 68935, 663000, 133122, 133471),
        EUTRAN_ARFCN_FREQUENCY_BAND_72(
                EutranBand.BAND_72, 461000, 68936, 68985, 451000, 133472, 133521),
        EUTRAN_ARFCN_FREQUENCY_BAND_73(
                EutranBand.BAND_73, 460000, 68986, 69035, 450000, 133522, 133571),
        EUTRAN_ARFCN_FREQUENCY_BAND_74(
                EutranBand.BAND_74, 1475000, 69036, 69465, 1427000, 133572, 134001),
        EUTRAN_ARFCN_FREQUENCY_BAND_85(
                EutranBand.BAND_85, 728000, 70366, 70545, 698000, 134002, 134181),
        EUTRAN_ARFCN_FREQUENCY_BAND_87(
                EutranBand.BAND_87, 420000, 70546, 70595, 410000, 134182, 134231),
        EUTRAN_ARFCN_FREQUENCY_BAND_88(
                EutranBand.BAND_88, 422000, 70596, 70645, 412000, 134231, 134280);

        EutranBandArfcnFrequency(int band, int downlinkLowKhz, int downlinkOffset,
                                 int downlinkRange, int uplinkLowKhz, int uplinkOffset,
                                 int uplinkRange) {
            this.band = band;
            this.downlinkLowKhz = downlinkLowKhz;
            this.downlinkOffset = downlinkOffset;
            this.downlinkRange = downlinkRange;
            this.uplinkLowKhz = uplinkLowKhz;
            this.uplinkOffset = uplinkOffset;
            this.uplinkRange = uplinkRange;
        }

        int band;
        int downlinkLowKhz;
        int downlinkOffset;
        int downlinkRange;
        int uplinkLowKhz;
        int uplinkOffset;
        int uplinkRange;
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
     * https://www.etsi.org/deliver/etsi_ts/138100_138199/13810101/15.08.02_60/ts_13810101v150802p.pdf
     * https://www.etsi.org/deliver/etsi_ts/138100_138199/13810102/15.08.00_60/ts_13810102v150800p.pdf
     */
    public static final class NgranBands {
        /** 3GPP TS 38.101-1, Version 16.5.0, Table 5.2-1: FR1 bands */
        public static final int BAND_1 = android.hardware.radio.network.NgranBands.BAND_1;
        public static final int BAND_2 = android.hardware.radio.network.NgranBands.BAND_2;
        public static final int BAND_3 = android.hardware.radio.network.NgranBands.BAND_3;
        public static final int BAND_5 = android.hardware.radio.network.NgranBands.BAND_5;
        public static final int BAND_7 = android.hardware.radio.network.NgranBands.BAND_7;
        public static final int BAND_8 = android.hardware.radio.network.NgranBands.BAND_8;
        public static final int BAND_12 = android.hardware.radio.network.NgranBands.BAND_12;
        public static final int BAND_14 = android.hardware.radio.network.NgranBands.BAND_14;
        public static final int BAND_18 = android.hardware.radio.network.NgranBands.BAND_18;
        public static final int BAND_20 = android.hardware.radio.network.NgranBands.BAND_20;
        public static final int BAND_25 = android.hardware.radio.network.NgranBands.BAND_25;
        public static final int BAND_26 = android.hardware.radio.network.NgranBands.BAND_26;
        public static final int BAND_28 = android.hardware.radio.network.NgranBands.BAND_28;
        public static final int BAND_29 = android.hardware.radio.network.NgranBands.BAND_29;
        public static final int BAND_30 = android.hardware.radio.network.NgranBands.BAND_30;
        public static final int BAND_34 = android.hardware.radio.network.NgranBands.BAND_34;
        public static final int BAND_38 = android.hardware.radio.network.NgranBands.BAND_38;
        public static final int BAND_39 = android.hardware.radio.network.NgranBands.BAND_39;
        public static final int BAND_40 = android.hardware.radio.network.NgranBands.BAND_40;
        public static final int BAND_41 = android.hardware.radio.network.NgranBands.BAND_41;
        public static final int BAND_46 = android.hardware.radio.network.NgranBands.BAND_46;
        public static final int BAND_48 = android.hardware.radio.network.NgranBands.BAND_48;
        public static final int BAND_50 = android.hardware.radio.network.NgranBands.BAND_50;
        public static final int BAND_51 = android.hardware.radio.network.NgranBands.BAND_51;
        public static final int BAND_53 = android.hardware.radio.network.NgranBands.BAND_53;
        public static final int BAND_65 = android.hardware.radio.network.NgranBands.BAND_65;
        public static final int BAND_66 = android.hardware.radio.network.NgranBands.BAND_66;
        public static final int BAND_70 = android.hardware.radio.network.NgranBands.BAND_70;
        public static final int BAND_71 = android.hardware.radio.network.NgranBands.BAND_71;
        public static final int BAND_74 = android.hardware.radio.network.NgranBands.BAND_74;
        public static final int BAND_75 = android.hardware.radio.network.NgranBands.BAND_75;
        public static final int BAND_76 = android.hardware.radio.network.NgranBands.BAND_76;
        public static final int BAND_77 = android.hardware.radio.network.NgranBands.BAND_77;
        public static final int BAND_78 = android.hardware.radio.network.NgranBands.BAND_78;
        public static final int BAND_79 = android.hardware.radio.network.NgranBands.BAND_79;
        public static final int BAND_80 = android.hardware.radio.network.NgranBands.BAND_80;
        public static final int BAND_81 = android.hardware.radio.network.NgranBands.BAND_81;
        public static final int BAND_82 = android.hardware.radio.network.NgranBands.BAND_82;
        public static final int BAND_83 = android.hardware.radio.network.NgranBands.BAND_83;
        public static final int BAND_84 = android.hardware.radio.network.NgranBands.BAND_84;
        public static final int BAND_86 = android.hardware.radio.network.NgranBands.BAND_86;
        public static final int BAND_89 = android.hardware.radio.network.NgranBands.BAND_89;
        public static final int BAND_90 = android.hardware.radio.network.NgranBands.BAND_90;
        public static final int BAND_91 = android.hardware.radio.network.NgranBands.BAND_91;
        public static final int BAND_92 = android.hardware.radio.network.NgranBands.BAND_92;
        public static final int BAND_93 = android.hardware.radio.network.NgranBands.BAND_93;
        public static final int BAND_94 = android.hardware.radio.network.NgranBands.BAND_94;
        public static final int BAND_95 = android.hardware.radio.network.NgranBands.BAND_95;
        public static final int BAND_96 = android.hardware.radio.network.NgranBands.BAND_96;

        /** 3GPP TS 38.101-2, Version 16.2.0, Table 5.2-1: FR2 bands */
        public static final int BAND_257 = android.hardware.radio.network.NgranBands.BAND_257;
        public static final int BAND_258 = android.hardware.radio.network.NgranBands.BAND_258;
        public static final int BAND_260 = android.hardware.radio.network.NgranBands.BAND_260;
        public static final int BAND_261 = android.hardware.radio.network.NgranBands.BAND_261;

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
                        BAND_26,
                        BAND_28,
                        BAND_29,
                        BAND_30,
                        BAND_34,
                        BAND_38,
                        BAND_39,
                        BAND_40,
                        BAND_41,
                        BAND_46,
                        BAND_48,
                        BAND_50,
                        BAND_51,
                        BAND_53,
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
                        BAND_89,
                        BAND_90,
                        BAND_91,
                        BAND_92,
                        BAND_93,
                        BAND_94,
                        BAND_95,
                        BAND_96,
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
        public static final int FREQUENCY_RANGE_GROUP_UNKNOWN = 0;

        /**
         * NR frequency group 1 defined in 3GPP TS 38.101-1 table 5.2-1
         *
         * @hide
         */
        @SystemApi
        public static final int FREQUENCY_RANGE_GROUP_1 = 1;

        /**
         * NR frequency group 2 defined in 3GPP TS 38.101-2 table 5.2-1
         *
         * @hide
         */
        @SystemApi
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
                        FREQUENCY_RANGE_GROUP_2
                })
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
                case BAND_26:
                case BAND_28:
                case BAND_29:
                case BAND_30:
                case BAND_34:
                case BAND_38:
                case BAND_39:
                case BAND_40:
                case BAND_41:
                case BAND_46:
                case BAND_48:
                case BAND_50:
                case BAND_51:
                case BAND_53:
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
                case BAND_89:
                case BAND_90:
                case BAND_91:
                case BAND_92:
                case BAND_93:
                case BAND_94:
                case BAND_95:
                case BAND_96:
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

    /**
     * 3GPP TS 38.104 Table 5.4.2.1-1 NR-ARFCN parameters for the global frequency raster.
     *
     * @hide
     */
    enum NgranArfcnFrequency {

        NGRAN_ARFCN_FREQUENCY_RANGE_1(5, 0, 0, 0, 599999),
        NGRAN_ARFCN_FREQUENCY_RANGE_2(15, 3000000, 600000, 600000, 2016666),
        NGRAN_ARFCN_FREQUENCY_RANGE_3(60, 24250080, 2016667, 2016667, 3279165);

        NgranArfcnFrequency(int globalKhz, int rangeOffset, int arfcnOffset,
                            int rangeFirst, int rangeLast) {
            this.globalKhz = globalKhz;
            this.rangeOffset = rangeOffset;
            this.arfcnOffset = arfcnOffset;
            this.rangeFirst = rangeFirst;
            this.rangeLast = rangeLast;
        }

        int globalKhz;
        int rangeOffset;
        int arfcnOffset;
        int rangeFirst;
        int rangeLast;
    }

    /** @hide */
    private AccessNetworkConstants() {};
}
