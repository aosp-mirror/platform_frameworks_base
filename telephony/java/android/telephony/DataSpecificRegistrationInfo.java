/*
 * Copyright 2018 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;


/**
 * Class that stores information specific to data network registration.
 * @hide
 */
@SystemApi
public final class DataSpecificRegistrationInfo implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        prefix = "LTE_ATTACH_TYPE_",
        value = {
            LTE_ATTACH_TYPE_UNKNOWN,
            LTE_ATTACH_TYPE_EPS_ONLY,
            LTE_ATTACH_TYPE_COMBINED,
        })
    public @interface LteAttachResultType {}

    /**
     * Default value.
     * Attach type is unknown.
     */
    public static final int LTE_ATTACH_TYPE_UNKNOWN = 0;

    /**
     * LTE is attached with EPS only.
     *
     * Reference: 3GPP TS 24.301 9.9.3 EMM information elements.
     */
    public static final int LTE_ATTACH_TYPE_EPS_ONLY = 1;

    /**
     * LTE combined EPS and IMSI attach.
     *
     * Reference: 3GPP TS 24.301 9.9.3 EMM information elements.
     */
    public static final int LTE_ATTACH_TYPE_COMBINED = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"LTE_ATTACH_EXTRA_INFO_"},
            value = {
                    LTE_ATTACH_EXTRA_INFO_NONE,
                    LTE_ATTACH_EXTRA_INFO_CSFB_NOT_PREFERRED,
                    LTE_ATTACH_EXTRA_INFO_SMS_ONLY
            })
    public @interface LteAttachExtraInfo {}

    /**
     * Default value.
     */
    public static final int LTE_ATTACH_EXTRA_INFO_NONE = 0;

    /**
     * CSFB is not preferred.
     * Applicable for LTE only.
     *
     * Reference: 3GPP TS 24.301 9.9.3 EMM information elements.
     */
    public static final int LTE_ATTACH_EXTRA_INFO_CSFB_NOT_PREFERRED = 1 << 0;

    /**
     * Attached for SMS only.
     * Applicable for LTE only.
     *
     * Reference: 3GPP TS 24.301 9.9.3 EMM information elements.
     */
    public static final int LTE_ATTACH_EXTRA_INFO_SMS_ONLY = 1 << 1;

    /**
     * @hide
     * The maximum number of simultaneous Data Calls that
     * must be established using setupDataCall().
     */
    public final int maxDataCalls;

    /**
     * @hide
     * Indicates if the use of dual connectivity with NR is restricted.
     * Reference: 3GPP TS 24.301 v15.03 section 9.3.3.12A.
     */
    public final boolean isDcNrRestricted;

    /**
     * Indicates if NR is supported by the selected PLMN.
     * @hide
     * {@code true} if the bit N is in the PLMN-InfoList-r15 is true and the selected PLMN is
     * present in plmn-IdentityList at position N.
     * Reference: 3GPP TS 36.331 v15.2.2 section 6.3.1 PLMN-InfoList-r15.
     *            3GPP TS 36.331 v15.2.2 section 6.2.2 SystemInformationBlockType1 message.
     */
    public final boolean isNrAvailable;

    /**
     * @hide
     * Indicates that if E-UTRA-NR Dual Connectivity (EN-DC) is supported by the primary serving
     * cell.
     *
     * True the primary serving cell is LTE cell and the plmn-InfoList-r15 is present in SIB2 and
     * at least one bit in this list is true, otherwise this value should be false.
     *
     * Reference: 3GPP TS 36.331 v15.2.2 6.3.1 System information blocks.
     */
    public final boolean isEnDcAvailable;

    /**
     * Provides network support info for VoPS and Emergency bearer support
     */
    @Nullable
    private final VopsSupportInfo mVopsSupportInfo;

    /** The type of network attachment */
    private final @LteAttachResultType int mLteAttachResultType;

    /** LTE attach extra info */
    private final @LteAttachExtraInfo int mLteAttachExtraInfo;

    private DataSpecificRegistrationInfo(Builder builder) {
        this.maxDataCalls = builder.mMaxDataCalls;
        this.isDcNrRestricted = builder.mIsDcNrRestricted;
        this.isNrAvailable = builder.mIsNrAvailable;
        this.isEnDcAvailable = builder.mIsEnDcAvailable;
        this.mVopsSupportInfo = builder.mVopsSupportInfo;
        this.mLteAttachResultType = builder.mLteAttachResultType;
        this.mLteAttachExtraInfo = builder.mLteAttachExtraInfo;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public DataSpecificRegistrationInfo(
            int maxDataCalls, boolean isDcNrRestricted, boolean isNrAvailable,
            boolean isEnDcAvailable, @Nullable VopsSupportInfo vops) {
        this.maxDataCalls = maxDataCalls;
        this.isDcNrRestricted = isDcNrRestricted;
        this.isNrAvailable = isNrAvailable;
        this.isEnDcAvailable = isEnDcAvailable;
        this.mVopsSupportInfo = vops;
        this.mLteAttachResultType = LTE_ATTACH_TYPE_UNKNOWN;
        this.mLteAttachExtraInfo = LTE_ATTACH_EXTRA_INFO_NONE;
    }

    /**
     * Constructor from another data specific registration info
     *
     * @param dsri another data specific registration info
     * @hide
     */
    DataSpecificRegistrationInfo(@NonNull DataSpecificRegistrationInfo dsri) {
        maxDataCalls = dsri.maxDataCalls;
        isDcNrRestricted = dsri.isDcNrRestricted;
        isNrAvailable = dsri.isNrAvailable;
        isEnDcAvailable = dsri.isEnDcAvailable;
        mVopsSupportInfo = dsri.mVopsSupportInfo;
        mLteAttachResultType = dsri.mLteAttachResultType;
        mLteAttachExtraInfo = dsri.mLteAttachExtraInfo;
    }

    private DataSpecificRegistrationInfo(/* @NonNull */ Parcel source) {
        maxDataCalls = source.readInt();
        isDcNrRestricted = source.readBoolean();
        isNrAvailable = source.readBoolean();
        isEnDcAvailable = source.readBoolean();
        mVopsSupportInfo = source.readParcelable(VopsSupportInfo.class.getClassLoader(), android.telephony.VopsSupportInfo.class);
        mLteAttachResultType = source.readInt();
        mLteAttachExtraInfo = source.readInt();
    }

    @Override
    public void writeToParcel(/* @NonNull */ Parcel dest, int flags) {
        dest.writeInt(maxDataCalls);
        dest.writeBoolean(isDcNrRestricted);
        dest.writeBoolean(isNrAvailable);
        dest.writeBoolean(isEnDcAvailable);
        dest.writeParcelable(mVopsSupportInfo, flags);
        dest.writeInt(mLteAttachResultType);
        dest.writeInt(mLteAttachExtraInfo);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return new StringBuilder().append(this.getClass().getName())
                .append(" :{")
                .append(" maxDataCalls = " + maxDataCalls)
                .append(" isDcNrRestricted = " + isDcNrRestricted)
                .append(" isNrAvailable = " + isNrAvailable)
                .append(" isEnDcAvailable = " + isEnDcAvailable)
                .append(" mLteAttachResultType = " + mLteAttachResultType)
                .append(" mLteAttachExtraInfo = " + mLteAttachExtraInfo)
                .append(" " + mVopsSupportInfo)
                .append(" }")
                .toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDataCalls, isDcNrRestricted, isNrAvailable,
                isEnDcAvailable, mVopsSupportInfo,
                mLteAttachResultType, mLteAttachExtraInfo);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;

        if (!(o instanceof DataSpecificRegistrationInfo)) return false;

        DataSpecificRegistrationInfo other = (DataSpecificRegistrationInfo) o;
        return this.maxDataCalls == other.maxDataCalls
                && this.isDcNrRestricted == other.isDcNrRestricted
                && this.isNrAvailable == other.isNrAvailable
                && this.isEnDcAvailable == other.isEnDcAvailable
                && Objects.equals(mVopsSupportInfo, other.mVopsSupportInfo)
                && this.mLteAttachResultType == other.mLteAttachResultType
                && this.mLteAttachExtraInfo == other.mLteAttachExtraInfo;
    }

    public static final @NonNull Parcelable.Creator<DataSpecificRegistrationInfo> CREATOR =
            new Parcelable.Creator<DataSpecificRegistrationInfo>() {
                @Override
                public DataSpecificRegistrationInfo createFromParcel(Parcel source) {
                    return new DataSpecificRegistrationInfo(source);
                }

                @Override
                public DataSpecificRegistrationInfo[] newArray(int size) {
                    return new DataSpecificRegistrationInfo[size];
                }
            };

    /**
     * @return The LTE VOPS (Voice over Packet Switched) support information
     *
     * @deprecated use {@link #getVopsSupportInfo()}
     */
    @Deprecated
    @NonNull
    public LteVopsSupportInfo getLteVopsSupportInfo() {
        return mVopsSupportInfo instanceof LteVopsSupportInfo
                ? (LteVopsSupportInfo) mVopsSupportInfo
                : new LteVopsSupportInfo(LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE,
                LteVopsSupportInfo.LTE_STATUS_NOT_AVAILABLE);
    }

    /**
     * @return The VOPS (Voice over Packet Switched) support information.
     *
     * The instance of {@link LteVopsSupportInfo}, or {@link NrVopsSupportInfo},
     * null if there is there is no VOPS support information available.
     */
    @Nullable
    public VopsSupportInfo getVopsSupportInfo() {
        return mVopsSupportInfo;
    }

    /**
     * Provides the LTE attach type.
     */
    public @LteAttachResultType int getLteAttachResultType() {
        return mLteAttachResultType;
    }

    /**
     * Provides the extra information of LTE attachment.
     *
     * @return the bitwise OR of {@link LteAttachExtraInfo}.
     */
    public @LteAttachExtraInfo int getLteAttachExtraInfo() {
        return mLteAttachExtraInfo;
    }

    /**
     * Builds {@link DataSpecificRegistrationInfo} instances, which may include optional parameters.
     * @hide
     */
    public static final class Builder {
        private final int mMaxDataCalls;

        private boolean mIsDcNrRestricted;
        private boolean mIsNrAvailable;
        private boolean mIsEnDcAvailable;
        private @Nullable VopsSupportInfo mVopsSupportInfo;
        private @LteAttachResultType int mLteAttachResultType = LTE_ATTACH_TYPE_UNKNOWN;
        private @LteAttachExtraInfo int mLteAttachExtraInfo = LTE_ATTACH_EXTRA_INFO_NONE;

        public Builder(int maxDataCalls) {
            mMaxDataCalls = maxDataCalls;
        }

        /**
         * Ses whether the use of dual connectivity with NR is restricted.
         * @param isDcNrRestricted {@code true} if the use of dual connectivity with NR is
         *        restricted.
         */
        public @NonNull Builder setDcNrRestricted(boolean isDcNrRestricted) {
            mIsDcNrRestricted = isDcNrRestricted;
            return this;
        }

        /**
         * Sets whether NR is supported by the selected PLMN.
         * @param isNrAvailable {@code true} if NR is supported.
         */
        public @NonNull Builder setNrAvailable(boolean isNrAvailable) {
            mIsNrAvailable = isNrAvailable;
            return this;
        }

        /**
         * Sets whether E-UTRA-NR Dual Connectivity (EN-DC) is supported by the primary serving
         * cell.
         * @param isEnDcAvailable {@code true} if EN_DC is supported.
         */
        public @NonNull Builder setEnDcAvailable(boolean isEnDcAvailable) {
            mIsEnDcAvailable = isEnDcAvailable;
            return this;
        }

        /**
         * Sets the network support info for VoPS and Emergency bearer support.
         * @param vops The network support info for VoPS and Emergency bearer support.
         */
        @Nullable
        public @NonNull Builder setVopsSupportInfo(VopsSupportInfo vops) {
            mVopsSupportInfo = vops;
            return this;
        }

        /**
         * Sets the LTE attach type.
         * @param lteAttachResultType the Lte attach type
         */
        public @NonNull Builder setLteAttachResultType(
                @LteAttachResultType int lteAttachResultType) {
            mLteAttachResultType = lteAttachResultType;
            return this;
        }

        /**
         * Sets the extra information of LTE attachment.
         * @param lteAttachExtraInfo the extra information of LTE attachment.
         */
        public @NonNull Builder setLteAttachExtraInfo(
                @LteAttachExtraInfo int lteAttachExtraInfo) {
            mLteAttachExtraInfo = lteAttachExtraInfo;
            return this;
        }

        /**
         * @return a built {@link DataSpecificRegistrationInfo} instance.
         */
        public @NonNull DataSpecificRegistrationInfo build() {
            return new DataSpecificRegistrationInfo(this);
        }
    }
}
