package android.telephony;

import static android.telephony.ServiceState.DUPLEX_MODE_FDD;
import static android.telephony.ServiceState.DUPLEX_MODE_TDD;
import static android.telephony.ServiceState.DUPLEX_MODE_UNKNOWN;

import android.telephony.AccessNetworkConstants.EutranBand;
import android.telephony.ServiceState.DuplexMode;


/**
 * Utilities to map between radio constants.
 *
 * @hide
 */
public class AccessNetworkUtils {

    // do not instantiate
    private AccessNetworkUtils() {}

    public static final int INVALID_BAND = -1;

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

        if (band >= EutranBand.BAND_68) {
            return DUPLEX_MODE_UNKNOWN;
        } else if (band >= EutranBand.BAND_65) {
            return DUPLEX_MODE_FDD;
        } else if (band >= EutranBand.BAND_47) {
            return DUPLEX_MODE_UNKNOWN;
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
     * <p>See 3GPP 36.101 sec 5.7.3-1 for calculation.
     *
     * @param earfcn The downlink EARFCN
     * @return Operating band number, or {@link #INVALID_BAND} if no corresponding band exists
     */
    public static int getOperatingBandForEarfcn(int earfcn) {
        if (earfcn > 67535) {
            return INVALID_BAND;
        } else if (earfcn >= 67366) {
            return INVALID_BAND; // band 67 only for CarrierAgg
        } else if (earfcn >= 66436) {
            return EutranBand.BAND_66;
        } else if (earfcn >= 65536) {
            return EutranBand.BAND_65;
        } else if (earfcn > 54339) {
            return INVALID_BAND;
        } else if (earfcn >= 46790 /* inferred from the end range of BAND_45 */) {
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
}
