/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Information to represent a closed subscriber group.
 */
public final class ClosedSubscriberGroupInfo implements Parcelable {
    private static final String TAG = "ClosedSubscriberGroupInfo";

    private final boolean mCsgIndicator;

    private final String mHomeNodebName;

    private final int mCsgIdentity;

    /** @hide */
    public ClosedSubscriberGroupInfo(boolean csgIndicator, @Nullable String homeNodebName,
            int csgIdentity) {
        mCsgIndicator = csgIndicator;
        mHomeNodebName = (homeNodebName == null) ? "" : homeNodebName;
        mCsgIdentity = csgIdentity;
    }

    /**
     * Indicates whether the cell is restricted to only CSG members.
     *
     * A cell not broadcasting the CSG Indication but reporting CSG information is considered a
     * Hybrid Cell.
     * Refer to the "csg-Indication" field in 3GPP TS 36.331 section 6.2.2
     * SystemInformationBlockType1.
     * Also refer to "CSG Indicator" in 3GPP TS 25.331 section 10.2.48.8.1 and TS 25.304.
     *
     * @return true if the cell is restricted to group members only.
     */
    public boolean getCsgIndicator() {
        return mCsgIndicator;
    }

    /**
     * Returns human-readable name of the closed subscriber group operating this cell (Node-B).
     *
     * Refer to "hnb-Name" in TS 36.331 section 6.2.2 SystemInformationBlockType9.
     * Also refer to "HNB Name" in 3GPP TS25.331 section 10.2.48.8.23 and TS 23.003 section 4.8.
     *
     * @return the home Node-B name if available.
     */
    public @NonNull String getHomeNodebName() {
        return mHomeNodebName;
    }

    /**
     * The identity of the closed subscriber group that the cell belongs to.
     *
     * Refer to "CSG-Identity" in TS 36.336 section 6.3.4.
     * Also refer to "CSG Identity" in 3GPP TS 25.331 section 10.3.2.8 and TS 23.003 section 4.7.
     *
     * @return the unique 27-bit CSG Identity.
     */
    @IntRange(from = 0, to = 0x7FFFFFF)
    public int getCsgIdentity() {
        return mCsgIdentity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCsgIndicator, mHomeNodebName, mCsgIdentity);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ClosedSubscriberGroupInfo)) {
            return false;
        }

        ClosedSubscriberGroupInfo o = (ClosedSubscriberGroupInfo) other;
        return mCsgIndicator == o.mCsgIndicator && o.mHomeNodebName.equals(mHomeNodebName)
                && mCsgIdentity == o.mCsgIdentity;
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG + ":{")
                .append(" mCsgIndicator = ").append(mCsgIndicator)
                .append(" mHomeNodebName = ").append(mHomeNodebName)
                .append(" mCsgIdentity = ").append(mCsgIdentity)
                .toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int type) {
        dest.writeBoolean(mCsgIndicator);
        dest.writeString(mHomeNodebName);
        dest.writeInt(mCsgIdentity);
    }

    /** Construct from Parcel, type has already been processed */
    private ClosedSubscriberGroupInfo(Parcel in) {
        this(in.readBoolean(), in.readString(), in.readInt());
    }

    /**
     * Implement the Parcelable interface
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<ClosedSubscriberGroupInfo> CREATOR =
            new Creator<ClosedSubscriberGroupInfo>() {
                @Override
                public ClosedSubscriberGroupInfo createFromParcel(Parcel in) {
                    return createFromParcelBody(in);
                }

                @Override
                public ClosedSubscriberGroupInfo[] newArray(int size) {
                    return new ClosedSubscriberGroupInfo[size];
                }
            };

    /** @hide */
    protected static ClosedSubscriberGroupInfo createFromParcelBody(Parcel in) {
        return new ClosedSubscriberGroupInfo(in);
    }
}
