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
import java.util.List;
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

    /* Applicabe to UTRAN */
    /** Barring indicator for circuit-switched service; applicable to UTRAN */
    public static final int BARRING_SERVICE_TYPE_CS_SERVICE =
            android.hardware.radio.V1_5.BarringServiceType.CS_SERVICE;
    /** Barring indicator for packet-switched service; applicable to UTRAN */
    public static final int BARRING_SERVICE_TYPE_PS_SERVICE =
            android.hardware.radio.V1_5.BarringServiceType.PS_SERVICE;
    /** Barring indicator for circuit-switched voice service; applicable to UTRAN */
    public static final int BARRING_SERVICE_TYPE_CS_VOICE =
            android.hardware.radio.V1_5.BarringServiceType.CS_VOICE;

    /* Applicable to EUTRAN, NGRAN */
    /** Barring indicator for mobile-originated signalling; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MO_SIGNALLING =
            android.hardware.radio.V1_5.BarringServiceType.MO_SIGNALLING;
    /** Barring indicator for mobile-originated data traffic; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MO_DATA =
            android.hardware.radio.V1_5.BarringServiceType.MO_DATA;
    /** Barring indicator for circuit-switched fallback for voice; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_CS_FALLBACK =
            android.hardware.radio.V1_5.BarringServiceType.CS_FALLBACK;
    /** Barring indicator for MMTEL (IMS) voice; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MMTEL_VOICE =
            android.hardware.radio.V1_5.BarringServiceType.MMTEL_VOICE;
    /** Barring indicator for MMTEL (IMS) video; applicable to EUTRAN and NGRAN */
    public static final int BARRING_SERVICE_TYPE_MMTEL_VIDEO =
            android.hardware.radio.V1_5.BarringServiceType.MMTEL_VIDEO;

    /* Applicable to UTRAN, EUTRAN, NGRAN */
    /** Barring indicator for emergency services; applicable to UTRAN, EUTRAN, and NGRAN */
    public static final int BARRING_SERVICE_TYPE_EMERGENCY =
            android.hardware.radio.V1_5.BarringServiceType.EMERGENCY;
    /** Barring indicator for SMS sending; applicable to UTRAN, EUTRAN, and NGRAN */
    public static final int BARRING_SERVICE_TYPE_SMS =
            android.hardware.radio.V1_5.BarringServiceType.SMS;

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
                    BARRING_TYPE_CONDITIONAL})
        public @interface BarringType {}

        /** Barring is inactive */
        public static final int BARRING_TYPE_NONE = android.hardware.radio.V1_5.BarringType.NONE;
        /** The service is barred */
        public static final int BARRING_TYPE_UNCONDITIONAL =
                android.hardware.radio.V1_5.BarringType.UNCONDITIONAL;
        /** The service may be barred based on additional factors */
        public static final int BARRING_TYPE_CONDITIONAL =
                android.hardware.radio.V1_5.BarringType.CONDITIONAL;

        private final @BarringType int mBarringType;

        private final boolean mIsConditionallyBarred;
        private final int mConditionalBarringFactor;
        private final int mConditionalBarringTimeSeconds;

        /** @hide */
        public BarringServiceInfo() {
            this(BARRING_TYPE_NONE, false, 0, 0);
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

    private CellIdentity mCellIdentity;

    // A SparseArray potentially mapping each BarringService type to a BarringServiceInfo config
    // that describes the current barring status of that particular service.
    private SparseArray<BarringServiceInfo> mBarringServiceInfos;

    /** @hide */
    @TestApi
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

    /** @hide */
    public static BarringInfo create(
            @NonNull android.hardware.radio.V1_5.CellIdentity halBarringCellId,
            @NonNull List<android.hardware.radio.V1_5.BarringInfo> halBarringInfos) {
        CellIdentity ci = CellIdentity.create(halBarringCellId);
        SparseArray<BarringServiceInfo> serviceInfos = new SparseArray<>();

        for (android.hardware.radio.V1_5.BarringInfo halBarringInfo : halBarringInfos) {
            if (halBarringInfo.type == android.hardware.radio.V1_5.BarringType.CONDITIONAL) {
                if (halBarringInfo.typeSpecificInfo.getDiscriminator()
                        != android.hardware.radio.V1_5.BarringTypeSpecificInfo
                                .hidl_discriminator.conditionalBarringInfo) {
                    // this is an error case where the barring info is conditional but the
                    // conditional barring fields weren't included
                    continue;
                }
                android.hardware.radio.V1_5.ConditionalBarringInfo conditionalInfo =
                        halBarringInfo.typeSpecificInfo.conditionalBarringInfo();
                serviceInfos.put(
                        halBarringInfo.service, new BarringServiceInfo(
                                halBarringInfo.type, // will always be CONDITIONAL here
                                conditionalInfo.isBarred,
                                conditionalInfo.barringFactor,
                                conditionalInfo.barringTimeSeconds));
            } else {
                // Barring type is either NONE or UNCONDITIONAL
                serviceInfos.put(
                        halBarringInfo.service, new BarringServiceInfo(halBarringInfo.type,
                                false, 0, 0));
            }
        }
        return new BarringInfo(ci, serviceInfos);
    }

    /**
     * Return whether a service is currently barred based on the BarringInfo
     *
     * @param service the service to be checked.
     * @return true if the service is currently being barred, otherwise false
     */
    public boolean isServiceBarred(@BarringServiceType int service) {
        BarringServiceInfo bsi = mBarringServiceInfos.get(service);
        return bsi != null && (bsi.isBarred());
    }

    /**
     * Get the BarringServiceInfo for a specified service.
     *
     * @return a BarringServiceInfo struct describing the current barring status for a service
     */
    public @NonNull BarringServiceInfo getBarringServiceInfo(@BarringServiceType int service) {
        BarringServiceInfo bsi = mBarringServiceInfos.get(service);
        return (bsi != null) ? bsi : new BarringServiceInfo();
    }

    /** @hide */
    @SystemApi
    public @NonNull BarringInfo createLocationInfoSanitizedCopy() {
        return new BarringInfo(mCellIdentity.sanitizeLocationInfo(), mBarringServiceInfos);
    }

    /** @hide */
    public BarringInfo(Parcel p) {
        mCellIdentity = p.readParcelable(CellIdentity.class.getClassLoader());
        mBarringServiceInfos = p.readSparseArray(BarringServiceInfo.class.getClassLoader());
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
