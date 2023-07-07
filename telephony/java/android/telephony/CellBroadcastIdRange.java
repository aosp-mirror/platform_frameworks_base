/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Describes a particular cell broadcast message identifier range.
 * @hide
 */
@SystemApi
public final class CellBroadcastIdRange implements Parcelable {

    @IntRange(from = 0, to = 0xFFFF)
    private int mStartId;
    @IntRange(from = 0, to = 0xFFFF)
    private int mEndId;
    private int mType;
    private boolean mIsEnabled;

    /**
     * Create a new CellBroacastRange
     *
     * @param startId first message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2). The value must be between 0 and 0xFFFF.
     * @param endId last message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2). The value must be between 0 and 0xFFFF.
     * @param type the message format as defined in {@link SmsCbMessage}
     * @param isEnabled whether the range is enabled
     *
     * @throws IllegalArgumentException if endId < startId or invalid value
     */
    public CellBroadcastIdRange(@IntRange(from = 0, to = 0xFFFF) int startId,
            @IntRange(from = 0, to = 0xFFFF) int endId,
            @android.telephony.SmsCbMessage.MessageFormat int type, boolean isEnabled)
            throws IllegalArgumentException {
        if (startId < 0 || endId < 0 || startId > 0xFFFF || endId > 0xFFFF) {
            throw new IllegalArgumentException("invalid id");
        }
        if (endId < startId) {
            throw new IllegalArgumentException("endId must be greater than or equal to startId");
        }
        mStartId = startId;
        mEndId = endId;
        mType = type;
        mIsEnabled = isEnabled;
    }

    /**
     * Return the first message identifier of this range as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     */
    @IntRange(from = 0, to = 0xFFFF)
    public int getStartId() {
        return mStartId;
    }

    /**
     * Return the last message identifier of this range as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     */
    @IntRange(from = 0, to = 0xFFFF)
    public int getEndId() {
        return mEndId;
    }

    /**
     * Return the message format of this range as defined in {@link SmsCbMessage}
     */
    public @android.telephony.SmsCbMessage.MessageFormat int getType() {
        return mType;
    }

    /**
     * Return whether the range is enabled
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mStartId);
        out.writeInt(mEndId);
        out.writeInt(mType);
        out.writeBoolean(mIsEnabled);
    }

    /**
     * {@link Parcelable.Creator}
     *
     */
    public static final @NonNull Parcelable.Creator<CellBroadcastIdRange> CREATOR =
            new Creator<CellBroadcastIdRange>() {
                @NonNull
                @Override
                public CellBroadcastIdRange createFromParcel(Parcel in) {
                    int startId = in.readInt();
                    int endId = in.readInt();
                    int type = in.readInt();
                    boolean isEnabled = in.readBoolean();

                    return new CellBroadcastIdRange(startId, endId, type, isEnabled);
                }

                @NonNull
                @Override
                public CellBroadcastIdRange[] newArray(int size) {
                    return new CellBroadcastIdRange[size];
                }
            };

    /**
     * {@link Parcelable#describeContents}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStartId, mEndId, mType, mIsEnabled);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CellBroadcastIdRange)) {
            return false;
        }

        CellBroadcastIdRange other = (CellBroadcastIdRange) obj;

        return mStartId == other.mStartId && mEndId == other.mEndId && mType == other.mType
                && mIsEnabled == other.mIsEnabled;
    }

    @Override
    public String toString() {
        return "CellBroadcastIdRange[" + mStartId + ", " + mEndId + ", " + mType + ", "
                + mIsEnabled + "]";
    }
}
