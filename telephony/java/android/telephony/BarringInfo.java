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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Provides the barring configuration for a particular service type.
 *
 * Provides indication about the barring of a particular service for use. Certain barring types
 * are only valid for certain technology families. Any service that does not have a barring
 * configuration is unbarred by default.
 */
public final class BarringInfo implements Parcelable {

    /**
     * Barring Service Type
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "BARRING_SERVICE_TYPE_", value = {
            BARRING_SERVICE_TYPE_CS_SERVICE,
            BARRING_SERVICE_TYPE_PS_SERVICE,
            BARRING_SERVICE_TYPE_CS_VOICE,
            BARRING_SERVICE_TYPE_MO_SIGNALLING,
            BARRING_SERVICE_TYPE_MO_DATA,
            BARRING_SERVICE_TYPE_CS_FALLBACK,
            BARRING_SERVICE_TYPE_MMTEL_VOICE,
            BARRING_SERVICE_TYPE_MMTEL_VIDEO,
            BARRING_SERVICE_TYPE_EMERGENCY,
            BARRING_SERVICE_TYPE_SMS})
    public @interface BarringServiceType {}

    /* Applicable to UTRAN */
    /** Barring indicator for circuit-switched service; applicable to UTRAN */
    public static final int BARRING_SERVICE_TYPE_CS_SERVICE =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_CS_SERVICE;
    /** Barring indicator for packet-switched service; applicable to UTRAN */
    public static final int BARRING_SERVICE_TYPE_PS_SERVICE =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_PS_SERVICE;
    /** Barring indicator for circuit-switched voice service; applicable to UTRAN */
    public static final int BARRING_SERVICE_TYPE_CS_VOICE =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_CS_VOICE;

    /* Applicable to EUTRAN, NGRAN */
    /** Barring indicator for mobile-originated signalling; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MO_SIGNALLING =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_MO_SIGNALLING;
    /** Barring indicator for mobile-originated data traffic; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MO_DATA =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_MO_DATA;
    /** Barring indicator for circuit-switched fallback for voice; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_CS_FALLBACK =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_CS_FALLBACK;
    /** Barring indicator for MMTEL (IMS) voice; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MMTEL_VOICE =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_MMTEL_VOICE;
    /** Barring indicator for MMTEL (IMS) video; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MMTEL_VIDEO =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_MMTEL_VIDEO;

    /* Applicable to UTRAN, EUTRAN, NGRAN */
    /** Barring indicator for emergency services; applicable to UTRAN, EUTRAN, and NGRAN */
    public static final int BARRING_SERVICE_TYPE_EMERGENCY =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_EMERGENCY;
    /** Barring indicator for SMS sending; applicable to UTRAN, EUTRAN, and NGRAN */
    public static final int BARRING_SERVICE_TYPE_SMS =
            android.hardware.radio.network.BarringInfo.SERVICE_TYPE_SMS;

    //TODO: add barring constants for Operator-Specific barring codes

    /** Describe the current barring configuration of a cell */
    public static final class BarringServiceInfo implements Parcelable {
        /**
         * Barring Type
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = "BARRING_TYPE_", value =
                    {BARRING_TYPE_NONE,
                    BARRING_TYPE_UNCONDITIONAL,
                    BARRING_TYPE_CONDITIONAL,
                    BARRING_TYPE_UNKNOWN})
        public @interface BarringType {}

        /** Barring is inactive */
        public static final int BARRING_TYPE_NONE =
                android.hardware.radio.network.BarringInfo.BARRING_TYPE_NONE;
        /** The service is barred */
        public static final int BARRING_TYPE_UNCONDITIONAL =
                android.hardware.radio.network.BarringInfo.BARRING_TYPE_UNCONDITIONAL;
        /** The service may be barred based on additional factors */
        public static final int BARRING_TYPE_CONDITIONAL =
                android.hardware.radio.network.BarringInfo.BARRING_TYPE_CONDITIONAL;

        /** If a modem does not report barring info, then the barring type will be UNKNOWN */
        public static final int BARRING_TYPE_UNKNOWN = -1;

        private final @BarringType int mBarringType;

        private final boolean mIsConditionallyBarred;
        private final int mConditionalBarringFactor;
        private final int mConditionalBarringTimeSeconds;

        /** @hide */
        public BarringServiceInfo(@BarringType int type) {
            this(type, false, 0, 0);
        }

        /** @hide */
        @TestApi
        public BarringServiceInfo(@BarringType int barringType, boolean isConditionallyBarred,
                int conditionalBarringFactor, int conditionalBarringTimeSeconds) {
            mBarringType = barringType;
            mIsConditionallyBarred = isConditionallyBarred;
            mConditionalBarringFactor = conditionalBarringFactor;
            mConditionalBarringTimeSeconds = conditionalBarringTimeSeconds;
        }

        public @BarringType int getBarringType() {
            return mBarringType;
        }

        /**
         * @return true if the conditional barring parameters have resulted in the service being
         *         barred; false if the service has either not been evaluated for conditional
         *         barring or has been evaluated and isn't barred.
         */
        public boolean isConditionallyBarred() {
            return mIsConditionallyBarred;
        }

        /**
         * @return the conditional barring factor as a percentage 0-100, which is the probability of
         *         a random device being barred for the service type.
         */
        public int getConditionalBarringFactor() {
            return mConditionalBarringFactor;
        }

        /**
         * @return the conditional barring time seconds, which is the interval between successive
         *         evaluations for conditional barring based on the barring factor.
         */
        @SuppressLint("MethodNameUnits")
        public int getConditionalBarringTimeSeconds() {
            return mConditionalBarringTimeSeconds;
        }

        /**
         * Return whether a service is currently barred based on the BarringInfo
         *
         * @return true if the service is currently being barred, otherwise false
         */
        public boolean isBarred() {
            return mBarringType == BarringServiceInfo.BARRING_TYPE_UNCONDITIONAL
                    || (mBarringType == BarringServiceInfo.BARRING_TYPE_CONDITIONAL
                            && mIsConditionallyBarred);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mBarringType, mIsConditionallyBarred,
                    mConditionalBarringFactor, mConditionalBarringTimeSeconds);
        }

        @Override
        public boolean equals(Object rhs) {
            if (!(rhs instanceof BarringServiceInfo)) return false;

            BarringServiceInfo other = (BarringServiceInfo) rhs;
            return mBarringType == other.mBarringType
                    && mIsConditionallyBarred == other.mIsConditionallyBarred
                    && mConditionalBarringFactor == other.mConditionalBarringFactor
                    && mConditionalBarringTimeSeconds == other.mConditionalBarringTimeSeconds;
        }

        private static String barringTypeToString(@BarringType int barringType) {
            return switch (barringType) {
                case BARRING_TYPE_NONE -> "NONE";
                case BARRING_TYPE_CONDITIONAL -> "CONDITIONAL";
                case BARRING_TYPE_UNCONDITIONAL -> "UNCONDITIONAL";
                case BARRING_TYPE_UNKNOWN -> "UNKNOWN";
                default -> "UNKNOWN(" + barringType + ")";
            };
        }

        @Override
        public String toString() {
            return "BarringServiceInfo {mBarringType=" + barringTypeToString(mBarringType)
                    + ", mIsConditionallyBarred=" + mIsConditionallyBarred
                    + ", mConditionalBarringFactor=" + mConditionalBarringFactor
                    + ", mConditionalBarringTimeSeconds=" + mConditionalBarringTimeSeconds + "}";
        }

        /** @hide */
        public BarringServiceInfo(Parcel p) {
            mBarringType = p.readInt();
            mIsConditionallyBarred = p.readBoolean();
            mConditionalBarringFactor = p.readInt();
            mConditionalBarringTimeSeconds = p.readInt();
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mBarringType);
            dest.writeBoolean(mIsConditionallyBarred);
            dest.writeInt(mConditionalBarringFactor);
            dest.writeInt(mConditionalBarringTimeSeconds);
        }

        /* @inheritDoc */
        public static final @NonNull Parcelable.Creator<BarringServiceInfo> CREATOR =
                new Parcelable.Creator<BarringServiceInfo>() {
                    @Override
                    public BarringServiceInfo createFromParcel(Parcel source) {
                        return new BarringServiceInfo(source);
                    }

                    @Override
                    public BarringServiceInfo[] newArray(int size) {
                        return new BarringServiceInfo[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }
    }

    private static final BarringServiceInfo BARRING_SERVICE_INFO_UNKNOWN =
            new BarringServiceInfo(BarringServiceInfo.BARRING_TYPE_UNKNOWN);

    private static final BarringServiceInfo BARRING_SERVICE_INFO_UNBARRED =
            new BarringServiceInfo(BarringServiceInfo.BARRING_TYPE_NONE);

    private CellIdentity mCellIdentity;

    // A SparseArray potentially mapping each BarringService type to a BarringServiceInfo config
    // that describes the current barring status of that particular service.
    private SparseArray<BarringServiceInfo> mBarringServiceInfos;

    /** @hide */
    @SystemApi
    public BarringInfo() {
        mBarringServiceInfos = new SparseArray<>();
    }

    /**
     * Constructor for new BarringInfo instances.
     *
     * @hide
     */
    @TestApi
    public BarringInfo(@Nullable CellIdentity barringCellId,
            @NonNull SparseArray<BarringServiceInfo> barringServiceInfos) {
        mCellIdentity = barringCellId;
        mBarringServiceInfos = barringServiceInfos;
    }

    /**
     * Get the BarringServiceInfo for a specified service.
     *
     * @return a BarringServiceInfo struct describing the current barring status for a service
     */
    public @NonNull BarringServiceInfo getBarringServiceInfo(@BarringServiceType int service) {
        BarringServiceInfo bsi = mBarringServiceInfos.get(service);
        // If barring is reported but not for a particular service, then we report the barring
        // type as UNKNOWN; if the modem reports barring info but doesn't report for a particular
        // service then we can safely assume that the service isn't barred (for instance because
        // that particular service isn't applicable to the current RAN).
        return (bsi != null) ? bsi : mBarringServiceInfos.size() > 0
                ? BARRING_SERVICE_INFO_UNBARRED : BARRING_SERVICE_INFO_UNKNOWN;
    }

    /** @hide */
    @SystemApi
    public @NonNull BarringInfo createLocationInfoSanitizedCopy() {
        // The only thing that would need sanitizing is the CellIdentity
        if (mCellIdentity == null) return this;

        return new BarringInfo(mCellIdentity.sanitizeLocationInfo(), mBarringServiceInfos);
    }

    /** @hide */
    public BarringInfo(Parcel p) {
        mCellIdentity = p.readParcelable(CellIdentity.class.getClassLoader(), android.telephony.CellIdentity.class);
        mBarringServiceInfos = p.readSparseArray(BarringServiceInfo.class.getClassLoader(), android.telephony.BarringInfo.BarringServiceInfo.class);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mCellIdentity, flags);
        dest.writeSparseArray(mBarringServiceInfos);
    }

    public static final @NonNull Parcelable.Creator<BarringInfo> CREATOR =
            new Parcelable.Creator<BarringInfo>() {
                @Override
                public BarringInfo createFromParcel(Parcel source) {
                    return new BarringInfo(source);
                }

                @Override
                public BarringInfo[] newArray(int size) {
                    return new BarringInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        int hash = mCellIdentity != null ? mCellIdentity.hashCode() : 7;
        for (int i = 0; i < mBarringServiceInfos.size(); i++) {
            hash = hash + 15 * mBarringServiceInfos.keyAt(i);
            hash = hash + 31 * mBarringServiceInfos.valueAt(i).hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object rhs) {
        if (!(rhs instanceof BarringInfo)) return false;

        BarringInfo bi = (BarringInfo) rhs;

        if (hashCode() != bi.hashCode()) return false;

        if (mBarringServiceInfos.size() != bi.mBarringServiceInfos.size()) return false;

        for (int i = 0; i < mBarringServiceInfos.size(); i++) {
            if (mBarringServiceInfos.keyAt(i) != bi.mBarringServiceInfos.keyAt(i)) return false;
            if (!Objects.equals(mBarringServiceInfos.valueAt(i),
                        bi.mBarringServiceInfos.valueAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "BarringInfo {mCellIdentity=" + mCellIdentity
               + ", mBarringServiceInfos=" + mBarringServiceInfos + "}";
    }
}
