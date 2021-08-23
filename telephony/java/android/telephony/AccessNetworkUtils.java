package android.telephony;

import static android.telephony.ServiceState.DUPLEX_MODE_FDD;
import static android.telephony.ServiceState.DUPLEX_MODE_TDD;
import static android.telephony.ServiceState.DUPLEX_MODE_UNKNOWN;

import android.telephony.AccessNetworkConstants.EutranBandArfcnFrequency;
import android.telephony.AccessNetworkConstants.EutranBand;
import android.telephony.AccessNetworkConstants.GeranBand;
import android.telephony.AccessNetworkConstants.GeranBandArfcnFrequency;
import android.telephony.AccessNetworkConstants.NgranArfcnFrequency;
import android.telephony.AccessNetworkConstants.NgranBands;
import android.telephony.AccessNetworkConstants.UtranBand;
import android.telephony.AccessNetworkConstants.UtranBandArfcnFrequency;
import android.telephony.ServiceState.DuplexMode;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Utilities to map between radio constants.
 *
 * @hide
 */
public class AccessNetworkUtils {

    // do not instantiate
    private AccessNetworkUtils() {}

    public static final int INVALID_BAND = -1;
    public static final int INVALID_FREQUENCY = -1;

    /** ISO country code of Japan. */
    private static final String JAPAN_ISO_COUNTRY_CODE = "jp";
    private static final String TAG = "AccessNetworkUtils";

    private static final int FREQUENCY_KHZ = 1000;
    private static final int FREQUENCY_RANGE_LOW_KHZ = 1000000;
    private static final int FREQUENCY_RANGE_MID_KHZ = 3000000;
    private static final int FREQUENCY_RANGE_HIGH_KHZ = 6000000;

    private static final Set<Integer> UARFCN_NOT_GENERAL_BAND;
    static {
        UARFCN_NOT_GENERAL_BAND = new HashSet<Integer>();
        UARFCN_NOT_GENERAL_BAND.add(UtranBand.BAND_A);
        UARFCN_NOT_GENERAL_BAND.add(UtranBand.BAND_B);
        UARFCN_NOT_GENERAL_BAND.add(UtranBand.BAND_C);
        UARFCN_NOT_GENERAL_BAND.add(UtranBand.BAND_D);
        UARFCN_NOT_GENERAL_BAND.add(UtranBand.BAND_E);
        UARFCN_NOT_GENERAL_BAND.add(UtranBand.BAND_F);
    }

    /**
     * Gets the duplex mode for the given EUTRAN operating band.
     *
     * <p>See 3GPP 36.101 sec 5.5-1 for calculation
     *
     * @param band The EUTRAN band number
     * @return The duplex mode of the given EUTRAN band
     */
    @DuplexMode
    public static int getDuplexModeForEutranBand(int band) {
        if (band == INVALID_BAND) {
            return DUPLEX_MODE_UNKNOWN;
        }

        if (band > EutranBand.BAND_88) {
            return DUPLEX_MODE_UNKNOWN;
        } else if (band >= EutranBand.BAND_65) {
            return DUPLEX_MODE_FDD;
        } else if (band >= EutranBand.BAND_33) {
            return DUPLEX_MODE_TDD;
        } else if (band >= EutranBand.BAND_1) {
            return DUPLEX_MODE_FDD;
        }

        return DUPLEX_MODE_UNKNOWN;
    }

    /**
     * Gets the EUTRAN Operating band for a given downlink EARFCN.
     *
     * <p>See 3GPP TS 36.101 clause 5.7.3-1 for calculation.
     *
     * @param earfcn The downlink EARFCN
     * @return Operating band number, or {@link #INVALID_BAND} if no corresponding band exists
     */
    public static int getOperatingBandForEarfcn(int earfcn) {
        if (earfcn > 70645) {
            return INVALID_BAND;
        } else if (earfcn >= 70596) {
            return EutranBand.BAND_88;
        } else if (earfcn >= 70546) {
            return EutranBand.BAND_87;
        } else if (earfcn >= 70366) {
            return EutranBand.BAND_85;
        } else if (earfcn > 69465) {
            return INVALID_BAND;
        } else if (earfcn >= 69036) {
            return EutranBand.BAND_74;
        } else if (earfcn >= 68986) {
            return EutranBand.BAND_73;
        } else if (earfcn >= 68936) {
            return EutranBand.BAND_72;
        } else if (earfcn >= 68586) {
            return EutranBand.BAND_71;
        } else if (earfcn >= 68336) {
            return EutranBand.BAND_70;
        } else if (earfcn > 67835) {
            return INVALID_BAND;
        } else if (earfcn >= 67536) {
            return EutranBand.BAND_68;
        } else if (earfcn >= 67366) {
            return INVALID_BAND; // band 67 only for CarrierAgg
        } else if (earfcn >= 66436) {
            return EutranBand.BAND_66;
        } else if (earfcn >= 65536) {
            return EutranBand.BAND_65;
        } else if (earfcn > 60254) {
            return INVALID_BAND;
        } else if (earfcn >= 60140) {
            return EutranBand.BAND_53;
        } else if (earfcn >= 59140) {
            return EutranBand.BAND_52;
        } else if (earfcn >= 59090) {
            return EutranBand.BAND_51;
        } else if (earfcn >= 58240) {
            return EutranBand.BAND_50;
        } else if (earfcn >= 56740) {
            return EutranBand.BAND_49;
        } else if (earfcn >= 55240) {
            return EutranBand.BAND_48;
        } else if (earfcn >= 54540) {
            return EutranBand.BAND_47;
        } else if (earfcn >= 46790) {
            return EutranBand.BAND_46;
        } else if (earfcn >= 46590) {
            return EutranBand.BAND_45;
        } else if (earfcn >= 45590) {
            return EutranBand.BAND_44;
        } else if (earfcn >= 43590) {
            return EutranBand.BAND_43;
        } else if (earfcn >= 41590) {
            return EutranBand.BAND_42;
        } else if (earfcn >= 39650) {
            return EutranBand.BAND_41;
        } else if (earfcn >= 38650) {
            return EutranBand.BAND_40;
        } else if (earfcn >= 38250) {
            return EutranBand.BAND_39;
        } else if (earfcn >= 37750) {
            return EutranBand.BAND_38;
        } else if (earfcn >= 37550) {
            return EutranBand.BAND_37;
        } else if (earfcn >= 36950) {
            return EutranBand.BAND_36;
        } else if (earfcn >= 36350) {
            return EutranBand.BAND_35;
        } else if (earfcn >= 36200) {
            return EutranBand.BAND_34;
        } else if (earfcn >= 36000) {
            return EutranBand.BAND_33;
        } else if (earfcn > 10359) {
            return INVALID_BAND;
        } else if (earfcn >= 9920) {
            return INVALID_BAND; // band 32 only for CarrierAgg
        } else if (earfcn >= 9870) {
            return EutranBand.BAND_31;
        } else if (earfcn >= 9770) {
            return EutranBand.BAND_30;
        } else if (earfcn >= 9660) {
            return INVALID_BAND; // band 29 only for CarrierAgg
        } else if (earfcn >= 9210) {
            return EutranBand.BAND_28;
        } else if (earfcn >= 9040) {
            return EutranBand.BAND_27;
        } else if (earfcn >= 8690) {
            return EutranBand.BAND_26;
        } else if (earfcn >= 8040) {
            return EutranBand.BAND_25;
        } else if (earfcn >= 7700) {
            return EutranBand.BAND_24;
        } else if (earfcn >= 7500) {
            return EutranBand.BAND_23;
        } else if (earfcn >= 6600) {
            return EutranBand.BAND_22;
        } else if (earfcn >= 6450) {
            return EutranBand.BAND_21;
        } else if (earfcn >= 6150) {
            return EutranBand.BAND_20;
        } else if (earfcn >= 6000) {
            return EutranBand.BAND_19;
        } else if (earfcn >= 5850) {
            return EutranBand.BAND_18;
        } else if (earfcn >= 5730) {
            return EutranBand.BAND_17;
        } else if (earfcn > 5379) {
            return INVALID_BAND;
        } else if (earfcn >= 5280) {
            return EutranBand.BAND_14;
        } else if (earfcn >= 5180) {
            return EutranBand.BAND_13;
        } else if (earfcn >= 5010) {
            return EutranBand.BAND_12;
        } else if (earfcn >= 4750) {
            return EutranBand.BAND_11;
        } else if (earfcn >= 4150) {
            return EutranBand.BAND_10;
        } else if (earfcn >= 3800) {
            return EutranBand.BAND_9;
        } else if (earfcn >= 3450) {
            return EutranBand.BAND_8;
        } else if (earfcn >= 2750) {
            return EutranBand.BAND_7;
        } else if (earfcn >= 2650) {
            return EutranBand.BAND_6;
        } else if (earfcn >= 2400) {
            return EutranBand.BAND_5;
        } else if (earfcn >= 1950) {
            return EutranBand.BAND_4;
        } else if (earfcn >= 1200) {
            return EutranBand.BAND_3;
        } else if (earfcn >= 600) {
            return EutranBand.BAND_2;
        } else if (earfcn >= 0) {
            return EutranBand.BAND_1;
        }

        return INVALID_BAND;
    }

    /**
     * Gets the GERAN Operating band for a given ARFCN.
     *
     * <p>See 3GPP TS 45.005 clause 2 for calculation.
     *
     * @param arfcn The ARFCN
     * @return Operating band number, or {@link #INVALID_BAND} if no corresponding band exists
     */
    public static int getOperatingBandForArfcn(int arfcn) {
        if (arfcn >= 0 && arfcn <= 124) {
            return GeranBand.BAND_E900;
        } else if (arfcn >= 128 && arfcn <= 251) {
            return GeranBand.BAND_850;
        } else if (arfcn >= 259 && arfcn <= 293) {
            return GeranBand.BAND_450;
        } else if (arfcn >= 306 && arfcn <= 340) {
            return GeranBand.BAND_480;
        } else if (arfcn >= 438 && arfcn <= 511) {
            return GeranBand.BAND_750;
        } else if (arfcn >= 512 && arfcn <= 885) {
            // ARFCN between 512 and 810 are also part of BAND_PCS1900.
            // Returning BAND_DCS1800 in both cases.
            return GeranBand.BAND_DCS1800;
        } else if (arfcn >= 940 && arfcn <= 974) {
            return GeranBand.BAND_ER900;
        } else if (arfcn >= 975 && arfcn <= 1023) {
            return GeranBand.BAND_E900;
        }
        return INVALID_BAND;
    }

    /**
     * Gets the UTRAN Operating band for a given downlink UARFCN.
     *
     * <p>See 3GPP TS 25.101 clause 5.4.4 for calculation.
     *
     * @param uarfcn The downlink UARFCN
     * @return Operating band number, or {@link #INVALID_BAND} if no corresponding band exists
     */
    public static int getOperatingBandForUarfcn(int uarfcn) {
        // List of additional bands defined in TS 25.101.
        int[] addlBand2 = {412, 437, 462, 487, 512, 537, 562, 587, 612, 637, 662, 687};
        int[] addlBand4 = {1887, 1912, 1937, 1962, 1987, 2012, 2037, 2062, 2087};
        int[] addlBand5 = {1007, 1012, 1032, 1037, 1062, 1087};
        int[] addlBand6 = {1037, 1062};
        int[] addlBand7 =
                {2587, 2612, 2637, 2662, 2687, 2712, 2737, 2762, 2787, 2812, 2837, 2862,
                2887, 2912};
        int[] addlBand10 =
                {3412, 3437, 3462, 3487, 3512, 3537, 3562, 3587, 3612, 3637, 3662, 3687};
        int[] addlBand12 = {3932, 3957, 3962, 3987, 3992};
        int[] addlBand13 = {4067, 4092};
        int[] addlBand14 = {4167, 4192};
        int[] addlBand19 = {787, 812, 837};
        int[] addlBand25 =
                {6292, 6317, 6342, 6367, 6392, 6417, 6442, 6467, 6492, 6517, 6542, 6567, 6592};
        int[] addlBand26 = {5937, 5962, 5987, 5992, 6012, 6017, 6037, 6042, 6062, 6067, 6087};

        if (uarfcn >= 10562 && uarfcn <= 10838) {
            return UtranBand.BAND_1;
        } else if ((uarfcn >= 9662 && uarfcn <= 9938)
                || Arrays.binarySearch(addlBand2, uarfcn) >= 0) {
            return UtranBand.BAND_2;
        } else if (uarfcn >= 1162 && uarfcn <= 1513) {
            return UtranBand.BAND_3;
        } else if ((uarfcn >= 1537 && uarfcn <= 1738)
                || Arrays.binarySearch(addlBand4, uarfcn) >= 0) {
            return UtranBand.BAND_4;
        } else if (uarfcn >= 4387 && uarfcn <= 4413) {
            // Band 6 is a subset of band 5. Only Japan uses band 6 and Japan does not have band 5.
            String country = TelephonyManager.getDefault().getNetworkCountryIso();
            if (JAPAN_ISO_COUNTRY_CODE.compareToIgnoreCase(country) == 0) {
                return UtranBand.BAND_6;
            } else {
                return UtranBand.BAND_5;
            }
        } else if ((uarfcn >= 4357 && uarfcn <= 4458)
                || Arrays.binarySearch(addlBand5, uarfcn) >= 0) {
            return UtranBand.BAND_5;
        } else if (Arrays.binarySearch(addlBand6, uarfcn) >= 0) {
            return UtranBand.BAND_6;
        } else if ((uarfcn >= 2237 && uarfcn <= 2563)
                || Arrays.binarySearch(addlBand7, uarfcn) >= 0) {
            return UtranBand.BAND_7;
        } else if (uarfcn >= 2937 && uarfcn <= 3088) {
            return UtranBand.BAND_8;
        } else if (uarfcn >= 9237 && uarfcn <= 9387) {
            return UtranBand.BAND_9;
        } else if ((uarfcn >= 3112 && uarfcn <= 3388)
                || Arrays.binarySearch(addlBand10, uarfcn) >= 0) {
            return UtranBand.BAND_10;
        } else if (uarfcn >= 3712 && uarfcn <= 3787) {
            return UtranBand.BAND_11;
        } else if ((uarfcn >= 3842 && uarfcn <= 3903)
                || Arrays.binarySearch(addlBand12, uarfcn) >= 0) {
            return UtranBand.BAND_12;
        } else if ((uarfcn >= 4017 && uarfcn <= 4043)
                || Arrays.binarySearch(addlBand13, uarfcn) >= 0) {
            return UtranBand.BAND_13;
        } else if ((uarfcn >= 4117 && uarfcn <= 4143)
                || Arrays.binarySearch(addlBand14, uarfcn) >= 0) {
            return UtranBand.BAND_14;
        } else if ((uarfcn >= 712 && uarfcn <= 763)
                || Arrays.binarySearch(addlBand19, uarfcn) >= 0) {
            return UtranBand.BAND_19;
        } else if (uarfcn >= 4512 && uarfcn <= 4638) {
            return UtranBand.BAND_20;
        } else if (uarfcn >= 862 && uarfcn <= 912) {
            return UtranBand.BAND_21;
        } else if (uarfcn >= 4662 && uarfcn <= 5038) {
            return UtranBand.BAND_22;
        } else if ((uarfcn >= 5112 && uarfcn <= 5413)
                || Arrays.binarySearch(addlBand25, uarfcn) >= 0) {
            return UtranBand.BAND_25;
        } else if ((uarfcn >= 5762 && uarfcn <= 5913)
                || Arrays.binarySearch(addlBand26, uarfcn) >= 0) {
            return UtranBand.BAND_26;
        }
        return INVALID_BAND;
    }

    /**
     * Get geran bands from {@link PhysicalChannelConfig#getBand()}
     */
    public static int getFrequencyRangeGroupFromGeranBand(@GeranBand.GeranBands int band) {
        switch (band) {
            case GeranBand.BAND_T380:
            case GeranBand.BAND_T410:
            case GeranBand.BAND_450:
            case GeranBand.BAND_480:
            case GeranBand.BAND_710:
            case GeranBand.BAND_750:
            case GeranBand.BAND_T810:
            case GeranBand.BAND_850:
            case GeranBand.BAND_P900:
            case GeranBand.BAND_E900:
            case GeranBand.BAND_R900:
            case GeranBand.BAND_ER900:
                return ServiceState.FREQUENCY_RANGE_LOW;
            case GeranBand.BAND_DCS1800:
            case GeranBand.BAND_PCS1900:
                return ServiceState.FREQUENCY_RANGE_MID;
            default:
                return ServiceState.FREQUENCY_RANGE_UNKNOWN;
        }
    }

    /**
     * Get utran bands from {@link PhysicalChannelConfig#getBand()}
     */
    public static int getFrequencyRangeGroupFromUtranBand(@UtranBand.UtranBands int band) {
        switch (band) {
            case UtranBand.BAND_5:
            case UtranBand.BAND_6:
            case UtranBand.BAND_8:
            case UtranBand.BAND_12:
            case UtranBand.BAND_13:
            case UtranBand.BAND_14:
            case UtranBand.BAND_19:
            case UtranBand.BAND_20:
            case UtranBand.BAND_26:
                return ServiceState.FREQUENCY_RANGE_LOW;
            case UtranBand.BAND_1:
            case UtranBand.BAND_2:
            case UtranBand.BAND_3:
            case UtranBand.BAND_4:
            case UtranBand.BAND_7:
            case UtranBand.BAND_9:
            case UtranBand.BAND_10:
            case UtranBand.BAND_11:
            case UtranBand.BAND_21:
            case UtranBand.BAND_25:
            case UtranBand.BAND_A:
            case UtranBand.BAND_B:
            case UtranBand.BAND_C:
            case UtranBand.BAND_D:
            case UtranBand.BAND_E:
            case UtranBand.BAND_F:
                return ServiceState.FREQUENCY_RANGE_MID;
            case UtranBand.BAND_22:
                return ServiceState.FREQUENCY_RANGE_HIGH;
            default:
                return ServiceState.FREQUENCY_RANGE_UNKNOWN;
        }
    }

    /**
     * Get eutran bands from {@link PhysicalChannelConfig#getBand()}
     * 3GPP TS 36.101 Table 5.5 EUTRA operating bands
     */
    public static int getFrequencyRangeGroupFromEutranBand(@EutranBand.EutranBands int band) {
        switch (band) {
            case EutranBand.BAND_5:
            case EutranBand.BAND_6:
            case EutranBand.BAND_8:
            case EutranBand.BAND_12:
            case EutranBand.BAND_13:
            case EutranBand.BAND_14:
            case EutranBand.BAND_17:
            case EutranBand.BAND_18:
            case EutranBand.BAND_19:
            case EutranBand.BAND_20:
            case EutranBand.BAND_26:
            case EutranBand.BAND_27:
            case EutranBand.BAND_28:
            case EutranBand.BAND_31:
            case EutranBand.BAND_44:
            case EutranBand.BAND_50:
            case EutranBand.BAND_51:
            case EutranBand.BAND_68:
            case EutranBand.BAND_71:
            case EutranBand.BAND_72:
            case EutranBand.BAND_73:
            case EutranBand.BAND_85:
            case EutranBand.BAND_87:
            case EutranBand.BAND_88:
                return ServiceState.FREQUENCY_RANGE_LOW;
            case EutranBand.BAND_1:
            case EutranBand.BAND_2:
            case EutranBand.BAND_3:
            case EutranBand.BAND_4:
            case EutranBand.BAND_7:
            case EutranBand.BAND_9:
            case EutranBand.BAND_10:
            case EutranBand.BAND_11:
            case EutranBand.BAND_21:
            case EutranBand.BAND_23:
            case EutranBand.BAND_24:
            case EutranBand.BAND_25:
            case EutranBand.BAND_30:
            case EutranBand.BAND_33:
            case EutranBand.BAND_34:
            case EutranBand.BAND_35:
            case EutranBand.BAND_36:
            case EutranBand.BAND_37:
            case EutranBand.BAND_38:
            case EutranBand.BAND_39:
            case EutranBand.BAND_40:
            case EutranBand.BAND_41:
            case EutranBand.BAND_45:
            case EutranBand.BAND_53:
            case EutranBand.BAND_65:
            case EutranBand.BAND_66:
            case EutranBand.BAND_70:
            case EutranBand.BAND_74:
                return ServiceState.FREQUENCY_RANGE_MID;
            case EutranBand.BAND_22:
            case EutranBand.BAND_42:
            case EutranBand.BAND_43:
            case EutranBand.BAND_46:
            case EutranBand.BAND_47:
            case EutranBand.BAND_48:
            case EutranBand.BAND_49:
            case EutranBand.BAND_52:
                return ServiceState.FREQUENCY_RANGE_HIGH;
            default:
                return ServiceState.FREQUENCY_RANGE_UNKNOWN;
        }
    }

    /**
     * Get ngran band from {@link PhysicalChannelConfig#getBand()}
     * 3GPP TS 38.104 Table 5.2-1 NR operating bands in FR1
     * 3GPP TS 38.104 Table 5.2-2 NR operating bands in FR2
     */
    public static int getFrequencyRangeGroupFromNrBand(@NgranBands.NgranBand int band) {
        switch (band) {
            case NgranBands.BAND_5:
            case NgranBands.BAND_8:
            case NgranBands.BAND_12:
            case NgranBands.BAND_14:
            case NgranBands.BAND_18:
            case NgranBands.BAND_20:
            case NgranBands.BAND_26:
            case NgranBands.BAND_28:
            case NgranBands.BAND_29:
            case NgranBands.BAND_71:
            case NgranBands.BAND_81:
            case NgranBands.BAND_82:
            case NgranBands.BAND_83:
            case NgranBands.BAND_89:
                return ServiceState.FREQUENCY_RANGE_LOW;
            case NgranBands.BAND_1:
            case NgranBands.BAND_2:
            case NgranBands.BAND_3:
            case NgranBands.BAND_7:
            case NgranBands.BAND_25:
            case NgranBands.BAND_30:
            case NgranBands.BAND_34:
            case NgranBands.BAND_38:
            case NgranBands.BAND_39:
            case NgranBands.BAND_40:
            case NgranBands.BAND_41:
            case NgranBands.BAND_50:
            case NgranBands.BAND_51:
            case NgranBands.BAND_53:
            case NgranBands.BAND_65:
            case NgranBands.BAND_66:
            case NgranBands.BAND_70:
            case NgranBands.BAND_74:
            case NgranBands.BAND_75:
            case NgranBands.BAND_76:
            case NgranBands.BAND_80:
            case NgranBands.BAND_84:
            case NgranBands.BAND_86:
            case NgranBands.BAND_90:
            case NgranBands.BAND_91:
            case NgranBands.BAND_92:
            case NgranBands.BAND_93:
            case NgranBands.BAND_94:
            case NgranBands.BAND_95:
                return ServiceState.FREQUENCY_RANGE_MID;
            case NgranBands.BAND_46:
            case NgranBands.BAND_48:
            case NgranBands.BAND_77:
            case NgranBands.BAND_78:
            case NgranBands.BAND_79:
                return ServiceState.FREQUENCY_RANGE_HIGH;
            case NgranBands.BAND_96:
            case NgranBands.BAND_257:
            case NgranBands.BAND_258:
            case NgranBands.BAND_260:
            case NgranBands.BAND_261:
                return ServiceState.FREQUENCY_RANGE_MMWAVE;
            default:
                return ServiceState.FREQUENCY_RANGE_UNKNOWN;
        }
    }

    /**
     * 3GPP TS 38.104 Table 5.4.2.1-1 NR-ARFCN parameters for the global frequency raster.
     * Formula of NR-ARFCN convert to actual frequency:
     * Actual frequency(kHz) = (RANGE_OFFSET + GLOBAL_KHZ * (ARFCN - ARFCN_OFFSET))
     */
    public static int getFrequencyFromNrArfcn(int nrArfcn) {

        if (nrArfcn == PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
            return PhysicalChannelConfig.FREQUENCY_UNKNOWN;
        }

        int globalKhz = 0;
        int rangeOffset = 0;
        int arfcnOffset = 0;
        for (NgranArfcnFrequency nrArfcnFrequency : AccessNetworkConstants.
                NgranArfcnFrequency.values()) {
            if (nrArfcn >= nrArfcnFrequency.rangeFirst
                    && nrArfcn <= nrArfcnFrequency.rangeLast) {
                globalKhz = nrArfcnFrequency.globalKhz;
                rangeOffset = nrArfcnFrequency.rangeOffset;
                arfcnOffset = nrArfcnFrequency.arfcnOffset;
                break;
            }
        }
        return rangeOffset + globalKhz * (nrArfcn - arfcnOffset);
    }

    /**
     * Get actual frequency from E-UTRA ARFCN.
     */
    public static int getFrequencyFromEarfcn(int band, int earfcn, boolean isUplink) {

        int low = 0;
        int offset = 0;
        for (EutranBandArfcnFrequency earfcnFrequency : EutranBandArfcnFrequency.values()) {
            if (band == earfcnFrequency.band) {
                if (isInEarfcnRange(earfcn, earfcnFrequency, isUplink)) {
                    low = isUplink ? earfcnFrequency.uplinkLowKhz : earfcnFrequency.downlinkLowKhz;
                    offset = isUplink ? earfcnFrequency.uplinkOffset
                            : earfcnFrequency.downlinkOffset;
                    break;
                } else {
                    Rlog.w(TAG,"Band and the range of EARFCN are not consistent: band = " + band
                            + " ,earfcn = " + earfcn + " ,isUplink = " + isUplink);
                    return INVALID_FREQUENCY;
                }
            }
        }
        return convertEarfcnToFrequency(low, earfcn, offset);
    }

    /**
     * 3GPP TS 36.101 Table 5.7.3-1 E-UTRA channel numbers.
     * Formula of E-UTRA ARFCN convert to actual frequency:
     * Actual frequency(kHz) = (DOWNLINK_LOW + 0.1 * (ARFCN - DOWNLINK_OFFSET)) * FREQUENCY_KHZ
     * Actual frequency(kHz) = (UPLINK_LOW + 0.1 * (ARFCN - UPLINK_OFFSET)) * FREQUENCY_KHZ
     */
    private static int convertEarfcnToFrequency(int low, int earfcn, int offset) {
        return low + 100 * (earfcn - offset);
    }

    private static boolean isInEarfcnRange(int earfcn, EutranBandArfcnFrequency earfcnFrequency,
            boolean isUplink) {
        if (isUplink) {
            return earfcn >= earfcnFrequency.uplinkOffset && earfcn <= earfcnFrequency.uplinkRange;
        } else {
            return earfcn >= earfcnFrequency.downlinkOffset
                    && earfcn <= earfcnFrequency.downlinkRange;
        }
    }

    /**
     * Get actual frequency from UTRA ARFCN.
     */
    public static int getFrequencyFromUarfcn(int band, int uarfcn, boolean isUplink) {

        if (uarfcn == PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
            return PhysicalChannelConfig.FREQUENCY_UNKNOWN;
        }

        int offsetKhz = 0;
        for (UtranBandArfcnFrequency uarfcnFrequency : AccessNetworkConstants.
                UtranBandArfcnFrequency.values()) {
            if (band == uarfcnFrequency.band) {
                if (isInUarfcnRange(uarfcn, uarfcnFrequency, isUplink)) {
                    offsetKhz = isUplink ? uarfcnFrequency.uplinkOffset
                            : uarfcnFrequency.downlinkOffset;
                    break;
                } else {
                    Rlog.w(TAG,"Band and the range of UARFCN are not consistent: band = " + band
                            + " ,uarfcn = " + uarfcn + " ,isUplink = " + isUplink);
                    return INVALID_FREQUENCY;
                }
            }
        }

        if (!UARFCN_NOT_GENERAL_BAND.contains(band)) {
            return convertUarfcnToFrequency(offsetKhz, uarfcn);
        } else {
            return convertUarfcnTddToFrequency(band, uarfcn);
        }
    }

    /**
     * 3GPP TS 25.101, Table 5.1 UARFCN definition (general).
     * Formula of UTRA ARFCN convert to actual frequency:
     * For general bands:
     * Downlink actual frequency(kHz) = (DOWNLINK_OFFSET + 0.2 * ARFCN) * FREQUENCY_KHZ
     * Uplink actual frequency(kHz) = (UPLINK_OFFSET + 0.2 * ARFCN) * FREQUENCY_KHZ
     */
    private static int convertUarfcnToFrequency(int offsetKhz, int uarfcn) {
        return offsetKhz + (200 * uarfcn);
    }

    /**
     * 3GPP TS 25.102, Table 5.2 UTRA Absolute Radio Frequency Channel Number 1.28 Mcps TDD Option.
     * For FDD bands A, B, C, E, F:
     * Actual frequency(kHz) =  5 * ARFCN * FREQUENCY_KHZ
     * For TDD bands D:
     * Actual frequency(kHz) =  (5 * (ARFCN - 2150.1MHz)) * FREQUENCY_KHZ
     */
    private static int convertUarfcnTddToFrequency(int band, int uarfcn) {
        if (band != UtranBand.BAND_D) {
            return 5 * uarfcn * FREQUENCY_KHZ;
        } else {
            return 5 * ((FREQUENCY_KHZ * uarfcn) - 2150100);
        }
    }

    private static boolean isInUarfcnRange(int uarfcn, UtranBandArfcnFrequency uarfcnFrequency,
                                           boolean isUplink) {
        if (isUplink) {
            return uarfcn >= uarfcnFrequency.uplinkRangeFirst
                    && uarfcn <= uarfcnFrequency.uplinkRangeLast;
        } else {
            if (uarfcnFrequency.downlinkRangeFirst != 0 && uarfcnFrequency.downlinkRangeLast != 0) {
                return uarfcn >= uarfcnFrequency.downlinkRangeFirst
                        && uarfcn <= uarfcnFrequency.downlinkRangeLast;
            } else {
                // BAND_C, BAND_D, BAND_E and BAND_F do not have the downlink range.
                return true;
            }
        }
    }

    /**
     * Get actual frequency from GERAN ARFCN.
     */
    public static int getFrequencyFromArfcn(int band, int arfcn, boolean isUplink) {

        if (arfcn == PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
            return PhysicalChannelConfig.FREQUENCY_UNKNOWN;
        }

        int uplinkFrequencyFirst = 0;
        int arfcnOffset = 0;
        int downlinkOffset = 0;
        int frequency = 0;
        for (GeranBandArfcnFrequency arfcnFrequency : AccessNetworkConstants.
                GeranBandArfcnFrequency.values()) {
            if (band == arfcnFrequency.band) {
                if (arfcn >= arfcnFrequency.arfcnRangeFirst
                        && arfcn <= arfcnFrequency.arfcnRangeLast) {
                    uplinkFrequencyFirst = arfcnFrequency.uplinkFrequencyFirst;
                    downlinkOffset = arfcnFrequency.downlinkOffset;
                    arfcnOffset = arfcnFrequency.arfcnOffset;
                    frequency = convertArfcnToFrequency(arfcn, uplinkFrequencyFirst,
                            arfcnOffset);
                    break;
                } else {
                    Rlog.w(TAG,"Band and the range of ARFCN are not consistent: band = " + band
                            + " ,arfcn = " + arfcn + " ,isUplink = " + isUplink);
                    return INVALID_FREQUENCY;
                }
            }
        }

        return isUplink ? frequency : frequency + downlinkOffset;
    }

    /**
     * 3GPP TS 45.005 Table 2-1 Dynamically mapped ARFCN
     * Formula of Geran ARFCN convert to actual frequency:
     * Uplink actual frequency(kHz) =
     *      (UPLINK_FREQUENCY_FIRST + 0.2 * (ARFCN - ARFCN_RANGE_FIRST)) * FREQUENCY_KHZ
     * Downlink actual frequency(kHz) = Uplink actual frequency + 10
     */
    private static int convertArfcnToFrequency(int arfcn, int uplinkFrequencyFirstKhz,
            int arfcnOffset) {
        return uplinkFrequencyFirstKhz + 200 * (arfcn - arfcnOffset);
    }

    public static int getFrequencyRangeFromArfcn(int frequency) {
        if (frequency < FREQUENCY_RANGE_LOW_KHZ) {
            return ServiceState.FREQUENCY_RANGE_LOW;
        } else if (frequency < FREQUENCY_RANGE_MID_KHZ
                && frequency >= FREQUENCY_RANGE_LOW_KHZ) {
            return ServiceState.FREQUENCY_RANGE_MID;
        } else if (frequency < FREQUENCY_RANGE_HIGH_KHZ
                && frequency >= FREQUENCY_RANGE_MID_KHZ) {
            return ServiceState.FREQUENCY_RANGE_HIGH;
        } else {
            return ServiceState.FREQUENCY_RANGE_MMWAVE;
        }
    }
}
