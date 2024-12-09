/**
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

package android.hardware.radio;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A set of identifiers necessary to tune to a given station.
 *
 * <p>This can hold various identifiers, like
 * <ui>
 *     <li>AM/FM frequency</li>
 *     <li>HD Radio subchannel</li>
 *     <li>DAB channel info</li>
 * </ui>
 *
 * <p>The primary ID uniquely identifies a station and can be used for equality
 * check. The secondary IDs are supplementary and can speed up tuning process,
 * but the primary ID is sufficient (ie. after a full band scan).
 *
 * <p>Two selectors with different secondary IDs, but the same primary ID are
 * considered equal. In particular, secondary IDs vector may get updated for
 * an entry on the program list (ie. when a better frequency for a given
 * station is found).
 *
 * <p>The primaryId of a given programType MUST be of a specific type:
 * <ui>
 *     <li>AM, FM: RDS_PI if the station broadcasts RDS, AMFM_FREQUENCY otherwise;</li>
 *     <li>AM_HD, FM_HD: HD_STATION_ID_EXT;</li>
 *     <li>DAB: DAB_SIDECC;</li>
 *     <li>DRMO: DRMO_SERVICE_ID;</li>
 *     <li>SXM: SXM_SERVICE_ID;</li>
 *     <li>VENDOR: VENDOR_PRIMARY.</li>
 * </ui>
 * @hide
 */
@SystemApi
public final class ProgramSelector implements Parcelable {
    /** Invalid program type.
     * @deprecated use {@link IdentifierType} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_INVALID = 0;
    /** Analog AM radio (with or without RDS).
     * @deprecated use {@link IdentifierType} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_AM = 1;
    /** analog FM radio (with or without RDS).
     * @deprecated use {@link IdentifierType} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_FM = 2;
    /** AM HD Radio.
     * @deprecated use {@link Identifier} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_AM_HD = 3;
    /** FM HD Radio.
     * @deprecated use {@link Identifier} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_FM_HD = 4;
    /** Digital audio broadcasting.
     * @deprecated use {@link Identifier} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_DAB = 5;
    /** Digital Radio Mondiale.
     * @deprecated use {@link Identifier} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_DRMO = 6;
    /** SiriusXM Satellite Radio.
     * @deprecated use {@link Identifier} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_SXM = 7;
    /** Vendor-specific, not synced across devices.
     * @deprecated use {@link Identifier} instead
     */
    @Deprecated
    public static final int PROGRAM_TYPE_VENDOR_START = 1000;
    /** @deprecated use {@link Identifier} instead */
    @Deprecated
    public static final int PROGRAM_TYPE_VENDOR_END = 1999;
    /**
     * @deprecated use {@link Identifier} instead
     * @removed mistakenly exposed previously
     */
    @Deprecated
    @IntDef(prefix = { "PROGRAM_TYPE_" }, value = {
        PROGRAM_TYPE_INVALID,
        PROGRAM_TYPE_AM,
        PROGRAM_TYPE_FM,
        PROGRAM_TYPE_AM_HD,
        PROGRAM_TYPE_FM_HD,
        PROGRAM_TYPE_DAB,
        PROGRAM_TYPE_DRMO,
        PROGRAM_TYPE_SXM,
    })
    @IntRange(from = PROGRAM_TYPE_VENDOR_START, to = PROGRAM_TYPE_VENDOR_END)
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProgramType {}

    /**
     * Bitmask for HD radio subchannel 1
     *
     * <p>There are at most 8 HD radio subchannels of 1-based om HD radio standard. It is
     * converted to 0-based index. 0 is the index of main program service (MPS). 1 to 7 are
     * indexes of additional supplemental program services (SPS).
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_1 = 1 << 0;

    /**
     * Bitmask for HD radio subchannel 2
     *
     * <p>For further reference, see {@link #SUB_CHANNEL_HD_1}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_2 = 1 << 1;

    /**
     * Bitmask for HD radio subchannel 3
     *
     * <p>For further reference, see {@link #SUB_CHANNEL_HD_1}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_3 = 1 << 2;

    /**
     * Bitmask for HD radio subchannel 4
     *
     * <p>For further reference, see {@link #SUB_CHANNEL_HD_1}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_4 = 1 << 3;

    /**
     * Bitmask for HD radio subchannel 5
     *
     * <p>For further reference, see {@link #SUB_CHANNEL_HD_1}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_5 = 1 << 4;

    /**
     * Bitmask for HD radio subchannel 6
     *
     * <p>For further reference, see {@link #SUB_CHANNEL_HD_1}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_6 = 1 << 5;

    /**
     * Bitmask for HD radio subchannel 7
     *
     * <p>For further reference, see {@link #SUB_CHANNEL_HD_1}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_7 = 1 << 6;

    /**
     * Bitmask for HD radio subchannel 8
     *
     * <p>For further reference, see {@link #SUB_CHANNEL_HD_1}
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int SUB_CHANNEL_HD_8 = 1 << 7;

    /** @hide */
    @IntDef(prefix = { "SUB_CHANNEL_HD_" }, value = {
            SUB_CHANNEL_HD_1,
            SUB_CHANNEL_HD_2,
            SUB_CHANNEL_HD_3,
            SUB_CHANNEL_HD_4,
            SUB_CHANNEL_HD_5,
            SUB_CHANNEL_HD_6,
            SUB_CHANNEL_HD_7,
            SUB_CHANNEL_HD_8,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HdSubChannel {}

    public static final int IDENTIFIER_TYPE_INVALID = 0;
    /**
     * Primary identifier for analog (without RDS) AM/FM stations:
     * frequency in kHz.
     *
     * <p>This identifier also contains band information:
     * <li>
     *     <ul><500kHz: AM LW.
     *     <ul>500kHz - 1705kHz: AM MW.
     *     <ul>1.71MHz - 30MHz: AM SW.
     *     <ul>>60MHz: FM.
     * </li>
     */
    public static final int IDENTIFIER_TYPE_AMFM_FREQUENCY = 1;
    /**
     * 16bit primary identifier for FM RDS station.
     */
    public static final int IDENTIFIER_TYPE_RDS_PI = 2;
    /**
     * 64bit compound primary identifier for HD Radio.
     *
     * <p>Consists of (from the LSB):
     * <li>
     *     <ul>32bit: Station ID number.</ul>
     *     <ul>4bit: HD subchannel, see {@link #SUB_CHANNEL_HD_1}.</ul>
     *     <ul>18bit: AMFM_FREQUENCY.</ul>
     * </li>
     *
     * <p>While station ID number should be unique globally, it sometimes gets
     * abused by broadcasters (i.e. not being set at all). To ensure local
     * uniqueness, AMFM_FREQUENCY_KHZ was added here. Global uniqueness is
     * a best-effort - see {@link #IDENTIFIER_TYPE_HD_STATION_NAME}.
     *
     * <p>The remaining bits should be set to zeros when writing on the chip side
     * and ignored when read.
     */
    public static final int IDENTIFIER_TYPE_HD_STATION_ID_EXT = 3;
    /**
     * HD Radio subchannel - a value in range of 0-7.
     *
     * <p>The subchannel index is 0-based (where 0 is MPS and 1..7 are SPS),
     * as opposed to HD Radio standard (where it's 1-based).
     *
     * @deprecated use IDENTIFIER_TYPE_HD_STATION_ID_EXT instead
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_HD_SUBCHANNEL = 4;
    /**
     * 64bit additional identifier for HD Radio.
     *
     * <p>Due to Station ID abuse, some {@link #IDENTIFIER_TYPE_HD_STATION_ID_EXT}
     * identifiers may be not globally unique. To provide a best-effort solution, a
     * short version of station name may be carried as additional identifier and
     * may be used by the tuner hardware to double-check tuning.
     *
     * <p>The name is limited to the first 8 A-Z0-9 characters (lowercase
     * letters must be converted to uppercase). Encoded in little-endian
     * ASCII: the first character of the name is the LSB.
     *
     * <p>For example: "Abc" is encoded as 0x434241.
     */
    public static final int IDENTIFIER_TYPE_HD_STATION_NAME = 10004;
    /**
     * @see #IDENTIFIER_TYPE_DAB_SID_EXT
     *
     * @deprecated use {@link #IDENTIFIER_TYPE_DAB_DMB_SID_EXT} instead
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_DAB_SIDECC = 5;
    /**
     * 28bit compound primary identifier for Digital Audio Broadcasting.
     *
     * <p>Consists of (from the LSB):
     * <li>
     *     <ul>16bit: SId.</ul>
     *     <ul>8bit: ECC code.</ul>
     *     <ul>4bit: SCIdS.</ul>
     * </li>
     *
     * <p>SCIdS (Service Component Identifier within the Service) value
     * of 0 represents the main service, while 1 and above represents
     * secondary services.
     *
     * <p>The remaining bits should be set to zeros when writing on the chip
     * side and ignored when read.
     *
     * @deprecated use {@link #IDENTIFIER_TYPE_DAB_DMB_SID_EXT} instead
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_DAB_SID_EXT = IDENTIFIER_TYPE_DAB_SIDECC;
    /** 16bit */
    public static final int IDENTIFIER_TYPE_DAB_ENSEMBLE = 6;
    /** 12bit */
    public static final int IDENTIFIER_TYPE_DAB_SCID = 7;
    /** kHz */
    public static final int IDENTIFIER_TYPE_DAB_FREQUENCY = 8;
    /**
     * 24bit primary identifier for Digital Radio Mondiale.
     */
    public static final int IDENTIFIER_TYPE_DRMO_SERVICE_ID = 9;
    /** kHz */
    public static final int IDENTIFIER_TYPE_DRMO_FREQUENCY = 10;
    /**
     * 1: AM, 2:FM
     * @deprecated use {@link #IDENTIFIER_TYPE_DRMO_FREQUENCY} instead
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_DRMO_MODULATION = 11;
    /**
     * 32bit primary identifier for SiriusXM Satellite Radio.
     *
     * @deprecated SiriusXM Satellite Radio is not supported
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_SXM_SERVICE_ID = 12;
    /**
     * 0-999 range
     *
     * @deprecated SiriusXM Satellite Radio is not supported
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_SXM_CHANNEL = 13;
    /**
     * 44bit compound primary identifier for Digital Audio Broadcasting and
     * Digital Multimedia Broadcasting.
     *
     * <p>Consists of (from the LSB):
     * <li>
     *     <ul>32bit: SId;</ul>
     *     <ul>8bit: ECC code;</ul>
     *     <ul>4bit: SCIdS.</ul>
     * </li>
     *
     * <p>SCIdS (Service Component Identifier within the Service) value
     * of 0 represents the main service, while 1 and above represents
     * secondary services.
     *
     * <p>The remaining bits should be set to zeros when writing on the chip
     * side and ignored when read.
     */
    public static final int IDENTIFIER_TYPE_DAB_DMB_SID_EXT = 14;
    /**
     * 64bit additional identifier for HD Radio representing station location.
     *
     * <p>Consists of (from the LSB):
     * <li>
     *     <ul>4 bit: Bits 0:3 of altitude</ul>
     *     <ul>13 bit: Fractional bits of longitude</ul>
     *     <ul>8 bit: Integer bits of longitude</ul>
     *     <ul>1 bit: 0 for east and 1 for west for longitude</ul>
     *     <ul>1 bit: 0, representing longitude</ul>
     *     <ul>5 bit: pad of zeros separating longitude and latitude</ul>
     *     <ul>4 bit: Bits 4:7 of altitude</ul>
     *     <ul>13 bit: Fractional bits of latitude</ul>
     *     <ul>8 bit: Integer bits of latitude</ul>
     *     <ul>1 bit: 0 for north and 1 for south for latitude</ul>
     *     <ul>1 bit: 1, representing latitude</ul>
     *     <ul>5 bit: pad of zeros</ul>
     * </li>
     *
     * <p>This format is defined in NRSC-5-C document: SY_IDD_1020s.
     *
     * <p>Due to Station ID abuse, some
     * {@link #IDENTIFIER_TYPE_HD_STATION_ID_EXT} identifiers may be not
     * globally unique. To provide a best-effort solution, the station’s
     * broadcast antenna containing the latitude and longitude may be
     * carried as additional identifier and may be used by the tuner hardware
     * to double-check tuning.
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int IDENTIFIER_TYPE_HD_STATION_LOCATION = 15;
    /**
     * Primary identifier for vendor-specific radio technology.
     * The value format is determined by a vendor.
     *
     * <p>It must not be used in any other programType than corresponding VENDOR
     * type between VENDOR_START and VENDOR_END (e.g. identifier type 1015 must
     * not be used in any program type other than 1015).
     */
    public static final int IDENTIFIER_TYPE_VENDOR_START = PROGRAM_TYPE_VENDOR_START;
    /**
     * @see #IDENTIFIER_TYPE_VENDOR_START
     */
    public static final int IDENTIFIER_TYPE_VENDOR_END = PROGRAM_TYPE_VENDOR_END;
    /**
     * @deprecated use {@link #IDENTIFIER_TYPE_VENDOR_START} instead
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_VENDOR_PRIMARY_START = IDENTIFIER_TYPE_VENDOR_START;
    /**
     * @deprecated use {@link #IDENTIFIER_TYPE_VENDOR_END} instead
     */
    @Deprecated
    public static final int IDENTIFIER_TYPE_VENDOR_PRIMARY_END = IDENTIFIER_TYPE_VENDOR_END;
    /** @removed mistakenly exposed previously */
    @IntDef(prefix = { "IDENTIFIER_TYPE_" }, value = {
        IDENTIFIER_TYPE_INVALID,
        IDENTIFIER_TYPE_AMFM_FREQUENCY,
        IDENTIFIER_TYPE_RDS_PI,
        IDENTIFIER_TYPE_HD_STATION_ID_EXT,
        IDENTIFIER_TYPE_HD_SUBCHANNEL,
        IDENTIFIER_TYPE_HD_STATION_NAME,
        IDENTIFIER_TYPE_DAB_SID_EXT,
        IDENTIFIER_TYPE_DAB_SIDECC,
        IDENTIFIER_TYPE_DAB_ENSEMBLE,
        IDENTIFIER_TYPE_DAB_SCID,
        IDENTIFIER_TYPE_DAB_FREQUENCY,
        IDENTIFIER_TYPE_DRMO_SERVICE_ID,
        IDENTIFIER_TYPE_DRMO_FREQUENCY,
        IDENTIFIER_TYPE_DRMO_MODULATION,
        IDENTIFIER_TYPE_SXM_SERVICE_ID,
        IDENTIFIER_TYPE_SXM_CHANNEL,
        IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
        IDENTIFIER_TYPE_HD_STATION_LOCATION,
    })
    @IntRange(from = IDENTIFIER_TYPE_VENDOR_START, to = IDENTIFIER_TYPE_VENDOR_END)
    @Retention(RetentionPolicy.SOURCE)
    public @interface IdentifierType {}

    private final @ProgramType int mProgramType;
    private final @NonNull Identifier mPrimaryId;
    private final @NonNull Identifier[] mSecondaryIds;
    private final @NonNull long[] mVendorIds;

    /**
     * Constructor for ProgramSelector.
     *
     * <p>It's not desired to modify selector objects, so all its fields are initialized at
     * creation.
     *
     * <p>Identifier lists must not contain any nulls, but can itself be null to be interpreted
     * as empty list at object creation.
     *
     * @param programType type of a radio technology.
     * @param primaryId primary program identifier.
     * @param secondaryIds list of secondary program identifiers.
     * @param vendorIds list of vendor-specific program identifiers.
     */
    public ProgramSelector(@ProgramType int programType, @NonNull Identifier primaryId,
            @Nullable Identifier[] secondaryIds, @Nullable long[] vendorIds) {
        if (secondaryIds == null) secondaryIds = new Identifier[0];
        if (vendorIds == null) vendorIds = new long[0];
        if (Stream.of(secondaryIds).anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("secondaryIds list must not contain nulls");
        }
        mProgramType = programType;
        mPrimaryId = Objects.requireNonNull(primaryId);
        mSecondaryIds = secondaryIds;
        mVendorIds = vendorIds;
    }

    /**
     * Type of a radio technology.
     *
     * @return program type.
     * @deprecated use {@link #getPrimaryId} instead
     */
    @Deprecated
    public @ProgramType int getProgramType() {
        return mProgramType;
    }

    /**
     * Primary program identifier uniquely identifies a station and is used to
     * determine equality between two ProgramSelectors.
     *
     * @return primary identifier.
     */
    public @NonNull Identifier getPrimaryId() {
        return mPrimaryId;
    }

    /**
     * Secondary program identifier is not required for tuning, but may make it
     * faster or more reliable.
     *
     * @return secondary identifier list, must not be modified.
     */
    public @NonNull Identifier[] getSecondaryIds() {
        return mSecondaryIds;
    }

    /**
     * Looks up an identifier of a given type (either primary or secondary).
     *
     * <p>If there are multiple identifiers if a given type, then first in order (where primary id
     * is before any secondary) is selected.
     *
     * @param type type of identifier.
     * @return identifier value, if found.
     * @throws IllegalArgumentException, if not found.
     */
    public long getFirstId(@IdentifierType int type) {
        if (mPrimaryId.getType() == type) return mPrimaryId.getValue();
        for (Identifier id : mSecondaryIds) {
            if (id.getType() == type) return id.getValue();
        }
        throw new IllegalArgumentException("Identifier " + type + " not found");
    }

    /**
     * Looks up all identifier of a given type (either primary or secondary).
     *
     * <p>Some identifiers may be provided multiple times, for example
     * {@link #IDENTIFIER_TYPE_AMFM_FREQUENCY} for FM Alternate Frequencies.
     *
     * @param type type of identifier.
     * @return an array of identifiers, generated on each call. May be modified.
     */
    public @NonNull Identifier[] getAllIds(@IdentifierType int type) {
        List<Identifier> out = new ArrayList<>();

        if (mPrimaryId.getType() == type) out.add(mPrimaryId);
        for (Identifier id : mSecondaryIds) {
            if (id.getType() == type) out.add(id);
        }

        return out.toArray(new Identifier[out.size()]);
    }

    /**
     * Vendor identifiers are passed as-is to the HAL implementation,
     * preserving elements order.
     *
     * @return an array of vendor identifiers, must not be modified.
     * @deprecated for HAL 1.x compatibility;
     *             HAL 2.x uses standard primary/secondary lists for vendor IDs
     */
    @Deprecated
    public @NonNull long[] getVendorIds() {
        return mVendorIds;
    }

    /**
     * Creates an equivalent ProgramSelector with a given secondary identifier preferred.
     *
     * <p>Used to point to a specific physical identifier for technologies that may broadcast the
     * same program on different channels. For example, with a DAB program broadcasted over multiple
     * ensembles, the radio hardware may select the one with the strongest signal. The UI may select
     * preferred ensemble though, so the radio hardware may try to use it in the first place.
     *
     * <p>This is a best-effort hint for the tuner, not a guaranteed behavior.
     *
     * <p>Setting the given secondary identifier as preferred means filtering out other secondary
     * identifiers of its type and adding it to the list.
     *
     * @param preferred preferred secondary identifier
     * @return a new ProgramSelector with a given secondary identifier preferred
     */
    public @NonNull ProgramSelector withSecondaryPreferred(@NonNull Identifier preferred) {
        int preferredType = preferred.getType();
        Identifier[] secondaryIds = Stream.concat(
            // remove other identifiers of that type
            Arrays.stream(mSecondaryIds).filter(id -> id.getType() != preferredType),
            // add preferred identifier instead
            Stream.of(preferred)).toArray(Identifier[]::new);

        return new ProgramSelector(
            mProgramType,
            mPrimaryId,
            secondaryIds,
            mVendorIds
        );
    }

    /**
     * Builds new ProgramSelector for AM/FM frequency.
     *
     * @param band the band.
     * @param frequencyKhz the frequency in kHz.
     * @return new {@link ProgramSelector} object representing given frequency.
     * @throws IllegalArgumentException if provided frequency is out of bounds.
     */
    public static @NonNull ProgramSelector createAmFmSelector(
            @RadioManager.Band int band, int frequencyKhz) {
        return createAmFmSelector(band, frequencyKhz, 0);
    }

    /**
     * Checks, if a given AM/FM frequency is roughly valid and in correct unit.
     *
     * <p>It does not check the range precisely: it may provide false positives, but not false
     * negatives. In particular, it may be way off for certain regions.
     * The main purpose is to avoid passing improper units, ie. MHz instead of kHz.
     *
     * @param isAm true, if AM, false if FM.
     * @param frequencyKhz the frequency in kHz.
     * @return true, if the frequency is roughly valid.
     */
    private static boolean isValidAmFmFrequency(boolean isAm, int frequencyKhz) {
        if (isAm) {
            return frequencyKhz > 150 && frequencyKhz <= 30000;
        } else {
            return frequencyKhz > 60000 && frequencyKhz < 110000;
        }
    }

    /**
     * Builds new ProgramSelector for AM/FM frequency.
     *
     * <p>This method variant supports HD Radio subchannels, but it's undesirable to
     * select them manually. Instead, the value should be retrieved from program list.
     *
     * @param band the band.
     * @param frequencyKhz the frequency in kHz.
     * @param subChannel 1-based HD Radio subchannel.
     * @return new ProgramSelector object representing given frequency.
     * @throws IllegalArgumentException if provided frequency is out of bounds,
     *         or tried setting a subchannel for analog AM/FM.
     */
    public static @NonNull ProgramSelector createAmFmSelector(
            @RadioManager.Band int band, int frequencyKhz, int subChannel) {
        if (band == RadioManager.BAND_INVALID) {
            // 50MHz is a rough boundary between AM (<30MHz) and FM (>60MHz).
            if (frequencyKhz < 50000) {
                band = (subChannel <= 0) ? RadioManager.BAND_AM : RadioManager.BAND_AM_HD;
            } else {
                band = (subChannel <= 0) ? RadioManager.BAND_FM : RadioManager.BAND_FM_HD;
            }
        }

        boolean isAm = (band == RadioManager.BAND_AM || band == RadioManager.BAND_AM_HD);
        boolean isDigital = (band == RadioManager.BAND_AM_HD || band == RadioManager.BAND_FM_HD);
        if (!isAm && !isDigital && band != RadioManager.BAND_FM) {
            throw new IllegalArgumentException("Unknown band: " + band);
        }
        if (subChannel < 0 || subChannel > 8) {
            throw new IllegalArgumentException("Invalid subchannel: " + subChannel);
        }
        if (subChannel > 0 && !isDigital) {
            throw new IllegalArgumentException("Subchannels are not supported for non-HD radio");
        }
        if (!isValidAmFmFrequency(isAm, frequencyKhz)) {
            throw new IllegalArgumentException("Provided value is not a valid AM/FM frequency: "
                    + frequencyKhz);
        }

        // We can't use AM_HD or FM_HD, because we don't know HD station ID.
        @ProgramType int programType = isAm ? PROGRAM_TYPE_AM : PROGRAM_TYPE_FM;
        Identifier primary = new Identifier(IDENTIFIER_TYPE_AMFM_FREQUENCY, frequencyKhz);

        Identifier[] secondary = null;
        if (subChannel > 0) {
            /* Stating sub channel for non-HD AM/FM does not give any guarantees,
             * but we can't do much more without HD station ID.
             *
             * The legacy APIs had 1-based subChannels, while ProgramSelector is 0-based.
             */
            secondary = new Identifier[]{
                    new Identifier(IDENTIFIER_TYPE_HD_SUBCHANNEL, subChannel - 1)};
        }

        return new ProgramSelector(programType, primary, secondary, null);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ProgramSelector(type=").append(mProgramType)
                .append(", primary=").append(mPrimaryId);
        if (mSecondaryIds.length > 0) {
            sb.append(", secondary=").append(Arrays.toString(mSecondaryIds));
        }
        if (mVendorIds.length > 0) {
            sb.append(", vendor=").append(Arrays.toString(mVendorIds));
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public int hashCode() {
        // secondaryIds and vendorIds are ignored for equality/hashing
        return mPrimaryId.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProgramSelector)) return false;
        ProgramSelector other = (ProgramSelector) obj;
        // secondaryIds and vendorIds are ignored for equality/hashing
        // programType can be inferred from primaryId, thus not checked
        return mPrimaryId.equals(other.getPrimaryId());
    }

    /** @hide */
    public boolean strictEquals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProgramSelector)) return false;
        ProgramSelector other = (ProgramSelector) obj;
        // vendorIds are ignored for equality
        // programType can be inferred from primaryId, thus not checked
        return mPrimaryId.equals(other.getPrimaryId())
                && mSecondaryIds.length == other.mSecondaryIds.length
                && Arrays.asList(mSecondaryIds).containsAll(
                        Arrays.asList(other.mSecondaryIds));
    }

    private ProgramSelector(Parcel in) {
        mProgramType = in.readInt();
        mPrimaryId = in.readTypedObject(Identifier.CREATOR);
        mSecondaryIds = in.createTypedArray(Identifier.CREATOR);
        if (Stream.of(mSecondaryIds).anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("secondaryIds list must not contain nulls");
        }
        mVendorIds = in.createLongArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mProgramType);
        dest.writeTypedObject(mPrimaryId, 0);
        dest.writeTypedArray(mSecondaryIds, 0);
        dest.writeLongArray(mVendorIds);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ProgramSelector> CREATOR =
            new Parcelable.Creator<ProgramSelector>() {
        public ProgramSelector createFromParcel(Parcel in) {
            return new ProgramSelector(in);
        }

        public ProgramSelector[] newArray(int size) {
            return new ProgramSelector[size];
        }
    };

    /**
     * A single program identifier component, e.g. frequency or channel ID.
     *
     * <p>The long value field holds the value in format described in comments for
     * IdentifierType constants.
     */
    public static final class Identifier implements Parcelable {
        private final @IdentifierType int mType;
        private final long mValue;

        public Identifier(@IdentifierType int type, long value) {
            if (type == IDENTIFIER_TYPE_HD_STATION_NAME) {
                // see getType
                type = IDENTIFIER_TYPE_HD_SUBCHANNEL;
            }
            mType = type;
            mValue = value;
        }

        /**
         * Type of an identifier.
         *
         * @return type of an identifier.
         */
        public @IdentifierType int getType() {
            if (mType == IDENTIFIER_TYPE_HD_SUBCHANNEL && mValue > 10) {
                /* HD_SUBCHANNEL and HD_STATION_NAME use the same identifier type, but they differ
                 * in possible values: sub channel is 0-7, station name is greater than ASCII space
                 * code (32).
                 */
                return IDENTIFIER_TYPE_HD_STATION_NAME;
            }
            return mType;
        }

        /**
         * Returns whether this identifier's type is considered a category when filtering
         * ProgramLists for category entries.
         *
         * @see ProgramList.Filter#areCategoriesIncluded
         * @return False if this identifier's type is not tunable (e.g. DAB ensemble or
         *         vendor-specified type). True otherwise.
         */
        public boolean isCategoryType() {
            return (mType >= IDENTIFIER_TYPE_VENDOR_START && mType <= IDENTIFIER_TYPE_VENDOR_END)
                    || mType == IDENTIFIER_TYPE_DAB_ENSEMBLE;
        }

        /**
         * Value of an identifier.
         *
         * <p>Its meaning depends on identifier type, ie. for
         * {@link #IDENTIFIER_TYPE_AMFM_FREQUENCY} type, the value is a frequency in kHz.
         *
         * <p>The range of a value depends on its type; it does not always require the whole long
         * range. Casting to necessary type (ie. int) without range checking is correct in front-end
         * code - any range violations are either errors in the framework or in the
         * HAL implementation. For example, {@link #IDENTIFIER_TYPE_AMFM_FREQUENCY} always fits in
         * int, as {@link Integer#MAX_VALUE} would mean 2.1THz.
         *
         * @return value of an identifier.
         */
        public long getValue() {
            return mValue;
        }

        @NonNull
        @Override
        public String toString() {
            return "Identifier(" + mType + ", " + mValue + ")";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mType, mValue);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Identifier)) return false;
            Identifier other = (Identifier) obj;
            return other.getType() == mType && other.getValue() == mValue;
        }

        private Identifier(Parcel in) {
            mType = in.readInt();
            mValue = in.readLong();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mType);
            dest.writeLong(mValue);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Identifier> CREATOR =
                new Parcelable.Creator<Identifier>() {
            public Identifier createFromParcel(Parcel in) {
                return new Identifier(in);
            }

            public Identifier[] newArray(int size) {
                return new Identifier[size];
            }
        };
    }
}
