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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.telephony.AccessNetworkConstants.AccessNetworkType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Class stores information related to LTE network VoPS support
 * @hide
 */
@SystemApi
public final class LteVopsSupportInfo extends VopsSupportInfo {

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {LTE_STATUS_NOT_AVAILABLE, LTE_STATUS_SUPPORTED,
                    LTE_STATUS_NOT_SUPPORTED}, prefix = "LTE_STATUS_")
    public @interface LteVopsStatus {}
    /**
     * Indicates information not available from modem.
     *
     * @deprecated as no instance will be created in this case
     */
    @Deprecated
    public static final int LTE_STATUS_NOT_AVAILABLE = 1;

    /**
     * Indicates network support the feature.
     */
    public static final int LTE_STATUS_SUPPORTED = 2;

    /**
     * Indicates network does not support the feature.
     */
    public static final int LTE_STATUS_NOT_SUPPORTED = 3;

    @LteVopsStatus
    private final int mVopsSupport;
    @LteVopsStatus
    private final int mEmcBearerSupport;

    public LteVopsSupportInfo(@LteVopsStatus int vops, @LteVopsStatus int emergency) {
        mVopsSupport = vops;
        mEmcBearerSupport = emergency;
    }

    /**
     * Provides the LTE VoPS support capability as described in:
     * 3GPP 24.301 EPS network feature support -> IMS VoPS
     */
    public @LteVopsStatus int getVopsSupport() {
        return mVopsSupport;
    }

    /**
     * Provides the LTE Emergency bearer support capability as described in:
     *    3GPP 24.301 EPS network feature support -> EMC BS
     *    25.331 LTE RRC SIB1 : ims-EmergencySupport-r9
     */
    public @LteVopsStatus int getEmcBearerSupport() {
        return mEmcBearerSupport;
    }

    /**
     * Returns whether VoPS is supported by the network
     */
    @Override
    public boolean isVopsSupported() {
        return mVopsSupport == LTE_STATUS_SUPPORTED;
    }

    /**
     * Returns whether emergency service is supported by the network
     */
    @Override
    public boolean isEmergencyServiceSupported() {
        return mEmcBearerSupport == LTE_STATUS_SUPPORTED;
    }

    /**
     * Returns whether emergency service fallback is supported by the network
     */
    @Override
    public boolean isEmergencyServiceFallbackSupported() {
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        super.writeToParcel(out, flags, AccessNetworkType.EUTRAN);
        out.writeInt(mVopsSupport);
        out.writeInt(mEmcBearerSupport);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == null || !(o instanceof LteVopsSupportInfo)) {
            return false;
        }
        if (this == o) return true;
        LteVopsSupportInfo other = (LteVopsSupportInfo) o;
        return mVopsSupport == other.mVopsSupport
            && mEmcBearerSupport == other.mEmcBearerSupport;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mVopsSupport, mEmcBearerSupport);
    }

    /**
     * @return string representation.
     */
    @NonNull
    @Override
    public String toString() {
        return ("LteVopsSupportInfo : "
                + " mVopsSupport = " + mVopsSupport
                + " mEmcBearerSupport = " + mEmcBearerSupport);
    }

    public static final @android.annotation.NonNull Creator<LteVopsSupportInfo> CREATOR =
            new Creator<LteVopsSupportInfo>() {
        @Override
        public LteVopsSupportInfo createFromParcel(Parcel in) {
            // Skip the type info.
            in.readInt();
            return new LteVopsSupportInfo(in);
        }

        @Override
        public LteVopsSupportInfo[] newArray(int size) {
            return new LteVopsSupportInfo[size];
        }
    };

    /** @hide */
    protected static LteVopsSupportInfo createFromParcelBody(Parcel in) {
        return new LteVopsSupportInfo(in);
    }

    private LteVopsSupportInfo(Parcel in) {
        mVopsSupport = in.readInt();
        mEmcBearerSupport = in.readInt();
    }
}
