/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.AccessNetworkConstants.RadioAccessNetworkType;
import android.telephony.TelephonyManager.NetworkTypeBitMask;

import com.android.internal.telephony.util.TelephonyUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Define capability of a modem group. That is, the capabilities
 * are shared between those modems defined by list of modem IDs.
 */
public final class PhoneCapability implements Parcelable {
    /** Modem feature indicating 3GPP2 capability. */
    public static final long MODEM_FEATURE_3GPP2_REG = 1 << 0;
    /** Modem feature indicating 3GPP capability. */
    public static final long MODEM_FEATURE_3GPP_REG = 1 << 1;
    /** Modem feature indicating CDMA 2000 with EHRPD capability. */
    public static final long MODEM_FEATURE_CDMA2000_EHRPD_REG = 1 << 2;
    /** Modem feature indicating GSM capability. */
    public static final long MODEM_FEATURE_GERAN_REG = 1 << 3;
    /** Modem feature indicating UMTS capability. */
    public static final long MODEM_FEATURE_UTRAN_REG = 1 << 4;
    /** Modem feature indicating LTE capability. */
    public static final long MODEM_FEATURE_EUTRAN_REG = 1 << 5;
    /** Modem feature indicating 5G capability.*/
    public static final long MODEM_FEATURE_NGRAN_REG = 1 << 6;
    /** Modem feature indicating EN-DC capability. */
    public static final long MODEM_FEATURE_EUTRA_NR_DUAL_CONNECTIVITY_REG = 1 << 7;
    /** Modem feature indicating VoLTE capability (IMS registered). */
    public static final long MODEM_FEATURE_PS_VOICE_REG = 1 << 8;
    /** Modem feature indicating CS voice call capability. */
    public static final long MODEM_FEATURE_CS_VOICE_SESSION = 1 << 9;
    /** Modem feature indicating Internet connection capability. */
    public static final long MODEM_FEATURE_INTERACTIVE_DATA_SESSION = 1 << 10;
    /**
     * Modem feature indicating dedicated bearer capability.
     * For services that require a high level QoS (eg. VoLTE), the network can create
     * a dedicated bearer with the required QoS on top of an established default bearer.
     * This will provide a dedicated tunnel for one or more specific traffic types.
     */
    public static final long MODEM_FEATURE_DEDICATED_BEARER = 1 << 11;
    /** Modem feature indicating network scan capability. */
    public static final long MODEM_FEATURE_NETWORK_SCAN = 1 << 12;
    /** Modem feature indicating corresponding SIM has CDMA capability. */
    public static final long MODEM_FEATURE_CSIM = 1 << 13;

    /** @hide */
    @LongDef(flag = true, prefix = {"MODEM_FEATURE_" }, value = {
            MODEM_FEATURE_3GPP2_REG,
            MODEM_FEATURE_3GPP_REG,
            MODEM_FEATURE_CDMA2000_EHRPD_REG,
            MODEM_FEATURE_GERAN_REG,
            MODEM_FEATURE_UTRAN_REG,
            MODEM_FEATURE_EUTRAN_REG,
            MODEM_FEATURE_NGRAN_REG,
            MODEM_FEATURE_EUTRA_NR_DUAL_CONNECTIVITY_REG,
            MODEM_FEATURE_PS_VOICE_REG,
            MODEM_FEATURE_CS_VOICE_SESSION,
            MODEM_FEATURE_INTERACTIVE_DATA_SESSION,
            MODEM_FEATURE_DEDICATED_BEARER,
            MODEM_FEATURE_NETWORK_SCAN,
            MODEM_FEATURE_CSIM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModemFeature {
    }

    /**
     * Hardcoded default DSDS capability.
     * @hide
     */
    public static final PhoneCapability DEFAULT_DSDS_CAPABILITY;
    /**
     * Hardcoded default Single SIM single standby capability.
     * @hide
     */
    public static final PhoneCapability DEFAULT_SSSS_CAPABILITY;

    static {
        List<List<Long>> capabilities = new ArrayList<>();
        List<Long> modem1 = new ArrayList<>();
        List<Long> modem2 = new ArrayList<>();
        modem1.add(MODEM_FEATURE_GERAN_REG | MODEM_FEATURE_UTRAN_REG | MODEM_FEATURE_EUTRAN_REG
                | MODEM_FEATURE_PS_VOICE_REG | MODEM_FEATURE_CS_VOICE_SESSION
                | MODEM_FEATURE_INTERACTIVE_DATA_SESSION | MODEM_FEATURE_DEDICATED_BEARER);
        modem2.add(MODEM_FEATURE_GERAN_REG | MODEM_FEATURE_UTRAN_REG | MODEM_FEATURE_EUTRAN_REG
                | MODEM_FEATURE_PS_VOICE_REG | MODEM_FEATURE_INTERACTIVE_DATA_SESSION
                | MODEM_FEATURE_DEDICATED_BEARER);
        capabilities.add(modem1);
        capabilities.add(modem2);
        List<String> uuids = new ArrayList<>();
        uuids.add("com.xxxx.lm0");
        uuids.add("com.xxxx.lm1");
        long rats = TelephonyManager.NETWORK_TYPE_BITMASK_GSM
                | TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
                | TelephonyManager.NETWORK_TYPE_BITMASK_EDGE
                | TelephonyManager.NETWORK_TYPE_BITMASK_UMTS
                | TelephonyManager.NETWORK_TYPE_BITMASK_LTE;
        DEFAULT_DSDS_CAPABILITY = new PhoneCapability(0, 0, 0, 0, 0, rats, null, null, null, null,
                uuids, null, capabilities);

        capabilities = new ArrayList<>();
        capabilities.add(modem1);
        uuids = new ArrayList<>();
        uuids.add("com.xxxx.lm0");
        DEFAULT_SSSS_CAPABILITY = new PhoneCapability(0, 0, 0, 0, 0, rats, null, null, null, null,
                uuids, null, capabilities);
    }

    private final int mUtranUeCategoryDl;
    private final int mUtranUeCategoryUl;
    private final int mEutranUeCategoryDl;
    private final int mEutranUeCategoryUl;
    private final long mPsDataConnectionLingerTimeMillis;
    private final @NetworkTypeBitMask long mSupportedRats;
    private final List<Integer> mGeranBands;
    private final List<Integer> mUtranBands;
    private final List<Integer> mEutranBands;
    private final List<Integer> mNgranBands;
    private final List<String> mLogicalModemUuids;
    private final List<SimSlotCapability> mSimSlotCapabilities;
    private final @ModemFeature List<List<Long>> mConcurrentFeaturesSupport;

    /**
     * Default constructor to create a PhoneCapability object.
     * @param utranUeCategoryDl 3GPP UE category for UTRAN downlink.
     * @param utranUeCategoryUl 3GPP UE category for UTRAN uplink.
     * @param eutranUeCategoryDl 3GPP UE category for EUTRAN downlink.
     * @param eutranUeCategoryUl 3GPP UE category for EUTRAN uplink.
     * @param psDataConnectionLingerTimeMillis length of the grace period to allow a smooth
     *                                         "handover" between data connections.
     * @param supportedRats all radio access technologies this phone is capable of supporting.
     * @param geranBands list of supported {@link AccessNetworkConstants.GeranBand}.
     * @param utranBands list of supported {@link AccessNetworkConstants.UtranBand}.
     * @param eutranBands list of supported {@link AccessNetworkConstants.EutranBand}.
     * @param ngranBands list of supported {@link AccessNetworkConstants.NgranBands}.
     * @param logicalModemUuids list of logical modem UUIDs, typically of the form
     *                          "com.xxxx.lmX", where X is the logical modem ID.
     * @param simSlotCapabilities list of {@link SimSlotCapability} for the device
     * @param concurrentFeaturesSupport list of list of concurrently supportable modem feature sets.
     * @hide
     */
    public PhoneCapability(int utranUeCategoryDl, int utranUeCategoryUl, int eutranUeCategoryDl,
            int eutranUeCategoryUl, long psDataConnectionLingerTimeMillis,
            @NetworkTypeBitMask long supportedRats, @Nullable List<Integer> geranBands,
            @Nullable List<Integer> utranBands, @Nullable List<Integer> eutranBands,
            @Nullable List<Integer> ngranBands, @Nullable List<String> logicalModemUuids,
            @Nullable List<SimSlotCapability> simSlotCapabilities,
            @Nullable @ModemFeature List<List<Long>> concurrentFeaturesSupport) {
        this.mUtranUeCategoryDl = utranUeCategoryDl;
        this.mUtranUeCategoryUl = utranUeCategoryUl;
        this.mEutranUeCategoryDl = eutranUeCategoryDl;
        this.mEutranUeCategoryUl = eutranUeCategoryUl;
        this.mPsDataConnectionLingerTimeMillis = psDataConnectionLingerTimeMillis;
        this.mSupportedRats = supportedRats;
        this.mGeranBands = TelephonyUtils.emptyIfNull(geranBands);
        this.mUtranBands = TelephonyUtils.emptyIfNull(utranBands);
        this.mEutranBands = TelephonyUtils.emptyIfNull(eutranBands);
        this.mNgranBands = TelephonyUtils.emptyIfNull(ngranBands);
        this.mLogicalModemUuids = TelephonyUtils.emptyIfNull(logicalModemUuids);
        this.mSimSlotCapabilities = TelephonyUtils.emptyIfNull(simSlotCapabilities);
        this.mConcurrentFeaturesSupport = TelephonyUtils.emptyIfNull(concurrentFeaturesSupport);
    }

    private PhoneCapability(Parcel in) {
        mUtranUeCategoryDl = in.readInt();
        mUtranUeCategoryUl = in.readInt();
        mEutranUeCategoryDl = in.readInt();
        mEutranUeCategoryUl = in.readInt();
        mPsDataConnectionLingerTimeMillis = in.readLong();
        mSupportedRats = in.readLong();
        mGeranBands = new ArrayList<>();
        in.readList(mGeranBands, Integer.class.getClassLoader());
        mUtranBands = new ArrayList<>();
        in.readList(mUtranBands, Integer.class.getClassLoader());
        mEutranBands = new ArrayList<>();
        in.readList(mEutranBands, Integer.class.getClassLoader());
        mNgranBands = new ArrayList<>();
        in.readList(mNgranBands, Integer.class.getClassLoader());
        mLogicalModemUuids = in.createStringArrayList();
        mSimSlotCapabilities = in.createTypedArrayList(SimSlotCapability.CREATOR);
        int length = in.readInt();
        mConcurrentFeaturesSupport = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            ArrayList<Long> feature = new ArrayList<>();
            in.readList(feature, Long.class.getClassLoader());
            mConcurrentFeaturesSupport.add(feature);
        }
    }

    /**
     * 3GPP UE category for a given Radio Access Network and direction.
     *
     * References are:
     * TS 25.306 Table 4.1a     EUTRAN downlink
     * TS 25.306 Table 5.1a-2   EUTRAN uplink
     * TS 25.306 Table 5.1a     UTRAN downlink
     * TS 25.306 Table 5.1g     UTRAN uplink
     *
     * @param uplink true for uplink direction and false for downlink direction.
     * @param accessNetworkType accessNetworkType, defined in {@link AccessNetworkType}.
     * @return the UE category, or -1 if it is not supported.
     */
    public int getUeCategory(boolean uplink, @RadioAccessNetworkType int accessNetworkType) {
        if (uplink) {
            switch (accessNetworkType) {
                case AccessNetworkType.UTRAN: return mUtranUeCategoryUl;
                case AccessNetworkType.EUTRAN: return mEutranUeCategoryUl;
                default: return -1;
            }
        } else {
            switch (accessNetworkType) {
                case AccessNetworkType.UTRAN: return mUtranUeCategoryDl;
                case AccessNetworkType.EUTRAN: return mEutranUeCategoryDl;
                default: return -1;
            }
        }
    }

    /**
     * In cellular devices that support a greater number of logical modems than
     * Internet connections, some devices support a grace period to allow a smooth "handover"
     * between those connections. If that feature is supported, then this API will provide
     * the length of that grace period in milliseconds. If it is not supported, the default value
     * for the grace period is 0.
     * @return handover linger time in milliseconds, or 0 if it is not supported.
     */
    public long getPsDataConnectionLingerTimeMillis() {
        return mPsDataConnectionLingerTimeMillis;
    }

    /**
     * The radio access technologies this device is capable of supporting.
     * @return a bitfield of all supported network types, defined in {@link TelephonyManager}
     */
    public @NetworkTypeBitMask long getSupportedRats() {
        return mSupportedRats;
    }

    /**
     * List of supported cellular bands for the given accessNetworkType.
     * @param accessNetworkType accessNetworkType, defined in {@link AccessNetworkType}.
     * @return a list of bands, or an empty list if the access network type is unsupported.
     */
    public @NonNull List<Integer> getBands(@RadioAccessNetworkType int accessNetworkType) {
        switch (accessNetworkType) {
            case AccessNetworkType.GERAN: return mGeranBands;
            case AccessNetworkType.UTRAN: return mUtranBands;
            case AccessNetworkType.EUTRAN: return mEutranBands;
            case AccessNetworkType.NGRAN: return mNgranBands;
            default: return new ArrayList<>();
        }
    }

    /**
     * List of logical modem UUIDs, each typically "com.xxxx.lmX", where X is the logical modem ID.
     * @return a list of modem UUIDs, one for every logical modem the device has.
     */
    public @NonNull List<String> getLogicalModemUuids() {
        return mLogicalModemUuids;
    }

    /**
     * List of {@link SimSlotCapability} for the device. The order of SIMs corresponds to the
     * order of modems in {@link #getLogicalModemUuids}.
     * @return a list of SIM slot capabilities, one for every SIM slot the device has.
     */
    public @NonNull List<SimSlotCapability> getSimSlotCapabilities() {
        return mSimSlotCapabilities;
    }

    /**
     * A List of Lists of concurrently supportable modem feature sets.
     *
     * Each entry in the top-level list is an independent configuration across all modems
     * that describes the capabilities of the device as a whole.
     *
     * Each entry in the second-level list is a bitfield of ModemFeatures that describes
     * the capabilities for a single modem. In the second-level list, the order of the modems
     * corresponds to order of the UUIDs in {@link #getLogicalModemUuids}.
     *
     * For symmetric capabilities that can only be active on one modem at a time, there will be
     * multiple configurations (equal to the number of modems) that shows it active on each modem.
     * For asymmetric capabilities that are only available on one of the modems, all configurations
     * will have that capability on just that one modem.
     *
     * The example below shows the concurrentFeaturesSupport for a 3-modem device with
     * theoretical capabilities SYMMETRIC (available on all modems, but only one at a time) and
     * ASYMMETRIC (only available on the first modem):
     * {
     *      Configuration 1: ASYMMETRIC and SYMMETRIC on modem 1, modem 2 empty, modem 3 empty
     *      {(ASYMMETRIC | SYMMETRIC), (), ()},
     *
     *      Configuration 2: ASYMMETRIC on modem 1, SYMMETRIC on modem 2, modem 3 empty
     *      {(ASYMMETRIC), (SYMMETRIC), ()},
     *
     *      Configuration 3: ASYMMETRIC on modem 1, modem 2 empty, SYMMETRIC on modem 3
     *      {(ASYMMETRIC), (), (SYMMETRIC)}
     * }
     *
     * @return List of all concurrently supportable modem features.
     */
    public @NonNull @ModemFeature List<List<Long>> getConcurrentFeaturesSupport() {
        return mConcurrentFeaturesSupport;
    }

    /**
     * How many modems can simultaneously have PS attached.
     * @return maximum number of active PS voice connections.
     */
    public int getMaxActivePsVoice() {
        return countFeature(MODEM_FEATURE_PS_VOICE_REG);
    }

    /**
     * How many modems can simultaneously support active data connections.
     * For DSDS, this will be 1, and for DSDA this will be 2.
     * @return maximum number of active Internet data sessions.
     */
    public int getMaxActiveInternetData() {
        return countFeature(MODEM_FEATURE_INTERACTIVE_DATA_SESSION);
    }

    /**
     * How many modems can simultaneously have dedicated bearer capability.
     * @return maximum number of active dedicated bearers.
     */
    public int getMaxActiveDedicatedBearers() {
        return countFeature(MODEM_FEATURE_DEDICATED_BEARER);
    }

    /**
     * Whether the CBRS band 48 is supported or not.
     * @return true if any RadioAccessNetwork supports CBRS and false if none do.
     * @hide
     */
    public boolean isCbrsSupported() {
        return mEutranBands.contains(AccessNetworkConstants.EutranBand.BAND_48)
                || mNgranBands.contains(AccessNetworkConstants.NgranBands.BAND_48);
    }

    private int countFeature(@ModemFeature long feature) {
        int count = 0;
        for (long featureSet : mConcurrentFeaturesSupport.get(0)) {
            if ((featureSet & feature) != 0) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "utranUeCategoryDl=" + mUtranUeCategoryDl
                + " utranUeCategoryUl=" + mUtranUeCategoryUl
                + " eutranUeCategoryDl=" + mEutranUeCategoryDl
                + " eutranUeCategoryUl=" + mEutranUeCategoryUl
                + " psDataConnectionLingerTimeMillis=" + mPsDataConnectionLingerTimeMillis
                + " supportedRats=" + mSupportedRats + " geranBands=" + mGeranBands
                + " utranBands=" + mUtranBands + " eutranBands=" + mEutranBands
                + " ngranBands=" + mNgranBands + " logicalModemUuids=" + mLogicalModemUuids
                + " simSlotCapabilities=" + mSimSlotCapabilities
                + " concurrentFeaturesSupport=" + mConcurrentFeaturesSupport;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUtranUeCategoryDl, mUtranUeCategoryUl, mEutranUeCategoryDl,
                mEutranUeCategoryUl, mPsDataConnectionLingerTimeMillis, mSupportedRats, mGeranBands,
                mUtranBands, mEutranBands, mNgranBands, mLogicalModemUuids, mSimSlotCapabilities,
                mConcurrentFeaturesSupport);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof PhoneCapability) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        PhoneCapability s = (PhoneCapability) o;

        return (mUtranUeCategoryDl == s.mUtranUeCategoryDl
                && mUtranUeCategoryUl == s.mUtranUeCategoryUl
                && mEutranUeCategoryDl == s.mEutranUeCategoryDl
                && mEutranUeCategoryUl == s.mEutranUeCategoryUl
                && mPsDataConnectionLingerTimeMillis == s.mPsDataConnectionLingerTimeMillis
                && mSupportedRats == s.mSupportedRats
                && mGeranBands.equals(s.mGeranBands)
                && mUtranBands.equals(s.mUtranBands)
                && mEutranBands.equals(s.mEutranBands)
                && mNgranBands.equals(s.mNgranBands)
                && mLogicalModemUuids.equals(s.mLogicalModemUuids)
                && mSimSlotCapabilities.equals(s.mSimSlotCapabilities)
                && mConcurrentFeaturesSupport.equals(s.mConcurrentFeaturesSupport));
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public @Parcelable.ContentsFlags int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(@NonNull Parcel dest, @Parcelable.WriteFlags int flags) {
        dest.writeInt(mUtranUeCategoryDl);
        dest.writeInt(mUtranUeCategoryUl);
        dest.writeInt(mEutranUeCategoryDl);
        dest.writeInt(mEutranUeCategoryUl);
        dest.writeLong(mPsDataConnectionLingerTimeMillis);
        dest.writeLong(mSupportedRats);
        dest.writeList(mGeranBands);
        dest.writeList(mUtranBands);
        dest.writeList(mEutranBands);
        dest.writeList(mNgranBands);
        dest.writeStringList(mLogicalModemUuids);
        dest.writeTypedList(mSimSlotCapabilities);
        dest.writeInt(mConcurrentFeaturesSupport.size());
        for (List<Long> feature : mConcurrentFeaturesSupport) {
            dest.writeList(feature);
        }
    }

    public static final @NonNull Parcelable.Creator<PhoneCapability> CREATOR =
            new Parcelable.Creator() {
                public PhoneCapability createFromParcel(Parcel in) {
                    return new PhoneCapability(in);
                }

                public PhoneCapability[] newArray(int size) {
                    return new PhoneCapability[size];
                }
            };
}
