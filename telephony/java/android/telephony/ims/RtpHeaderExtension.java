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

package android.telephony.ims;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * A representation of an RTP header extension.
 * <p>
 * Per RFC8285, an RTP header extension consists of both a local identifier in the range 1-14, an
 * 8-bit length indicator and a number of extension data bytes equivalent to the stated length.
 * @hide
 */
@SystemApi
public final class RtpHeaderExtension implements Parcelable {
    private int mLocalIdentifier;
    private byte[] mExtensionData;

    /**
     * Creates a new {@link RtpHeaderExtension}.
     * @param localIdentifier The local identifier for this RTP header extension.
     * @param extensionData The data for this RTP header extension.
     * @throws IllegalArgumentException if {@code extensionId} is not in the range 1-14.
     * @throws NullPointerException if {@code extensionData} is null.
     */
    public RtpHeaderExtension(@IntRange(from = 1, to = 14) int localIdentifier,
            @NonNull byte[] extensionData) {
        if (localIdentifier < 1 || localIdentifier > 13) {
            throw new IllegalArgumentException("localIdentifier must be in range 1-14");
        }
        if (extensionData == null) {
            throw new NullPointerException("extensionDa is required.");
        }
        mLocalIdentifier = localIdentifier;
        mExtensionData = extensionData;
    }

    /**
     * Creates a new instance of {@link RtpHeaderExtension} from a parcel.
     * @param in The parceled data to read.
     */
    private RtpHeaderExtension(@NonNull Parcel in) {
        mLocalIdentifier = in.readInt();
        mExtensionData = in.createByteArray();
    }

    /**
     * The local identifier for the RTP header extension.
     * <p>
     * Per RFC8285, the extension ID is a value in the range 1-14 (0 is reserved for padding and
     * 15 is reserved for the one-byte header form.
     * <p>
     * Within the current call, this extension ID will match one of the
     * {@link RtpHeaderExtensionType#getLocalIdentifier()}s.
     *
     * @return The local identifier for this RTP header extension.
     */
    @IntRange(from = 1, to = 14)
    public int getLocalIdentifier() {
        return mLocalIdentifier;
    }

    /**
     * The data payload for the RTP header extension.
     * <p>
     * Per RFC8285 Sec 4.3, an RTP header extension includes an 8-bit length field which indicate
     * how many bytes of data are present in the RTP header extension.  The extension includes this
     * many bytes of actual data.
     * <p>
     * We represent this as a byte array who's length is equivalent to the 8-bit length field.
     * @return RTP header extension data payload.  The payload may be up to 255 bytes in length.
     */
    public @NonNull byte[] getExtensionData() {
        return mExtensionData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLocalIdentifier);
        dest.writeByteArray(mExtensionData);
    }

    public static final @NonNull Creator<RtpHeaderExtension> CREATOR =
            new Creator<RtpHeaderExtension>() {
                @Override
                public RtpHeaderExtension createFromParcel(@NonNull Parcel in) {
                    return new RtpHeaderExtension(in);
                }

                @Override
                public RtpHeaderExtension[] newArray(int size) {
                    return new RtpHeaderExtension[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RtpHeaderExtension that = (RtpHeaderExtension) o;
        return mLocalIdentifier == that.mLocalIdentifier
                && Arrays.equals(mExtensionData, that.mExtensionData);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mLocalIdentifier);
        result = 31 * result + Arrays.hashCode(mExtensionData);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RtpHeaderExtension{mLocalIdentifier=");
        sb.append(mLocalIdentifier);
        sb.append(", mData=");
        for (byte b : mExtensionData) {
            sb.append(Integer.toBinaryString(b));
            sb.append("b_");
        }
        sb.append("}");

        return sb.toString();
    }
}
