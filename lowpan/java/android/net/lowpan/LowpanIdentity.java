/*
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

package android.net.lowpan;

import android.annotation.NonNull;
import android.icu.text.StringPrep;
import android.icu.text.StringPrepParseException;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import com.android.internal.util.HexDump;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Describes an instance of a LoWPAN network.
 *
 * @hide
 */
// @SystemApi
public class LowpanIdentity implements Parcelable {
    private static final String TAG = LowpanIdentity.class.getSimpleName();

    // Constants
    public static final int UNSPECIFIED_CHANNEL = -1;
    public static final int UNSPECIFIED_PANID = 0xFFFFFFFF;
    // Builder

    /** @hide */
    // @SystemApi
    public static class Builder {
        private static final StringPrep stringPrep =
                StringPrep.getInstance(StringPrep.RFC3920_RESOURCEPREP);

        final LowpanIdentity mIdentity = new LowpanIdentity();

        private static String escape(@NonNull byte[] bytes) {
            StringBuffer sb = new StringBuffer();
            for (byte b : bytes) {
                if (b >= 32 && b <= 126) {
                    sb.append((char) b);
                } else {
                    sb.append(String.format("\\0x%02x", b & 0xFF));
                }
            }
            return sb.toString();
        }

        public Builder setLowpanIdentity(@NonNull LowpanIdentity x) {
            Objects.requireNonNull(x);
            setRawName(x.getRawName());
            setXpanid(x.getXpanid());
            setPanid(x.getPanid());
            setChannel(x.getChannel());
            setType(x.getType());
            return this;
        }

        public Builder setName(@NonNull String name) {
            Objects.requireNonNull(name);
            try {
                mIdentity.mName = stringPrep.prepare(name, StringPrep.DEFAULT);
                mIdentity.mRawName = mIdentity.mName.getBytes(StandardCharsets.UTF_8);
                mIdentity.mIsNameValid = true;
            } catch (StringPrepParseException x) {
                Log.w(TAG, x.toString());
                setRawName(name.getBytes(StandardCharsets.UTF_8));
            }
            return this;
        }

        public Builder setRawName(@NonNull byte[] name) {
            Objects.requireNonNull(name);
            mIdentity.mRawName = name.clone();
            mIdentity.mName = new String(name, StandardCharsets.UTF_8);
            try {
                String nameCheck = stringPrep.prepare(mIdentity.mName, StringPrep.DEFAULT);
                mIdentity.mIsNameValid =
                        Arrays.equals(nameCheck.getBytes(StandardCharsets.UTF_8), name);
            } catch (StringPrepParseException x) {
                Log.w(TAG, x.toString());
                mIdentity.mIsNameValid = false;
            }

            // Non-normal names must be rendered differently to avoid confusion.
            if (!mIdentity.mIsNameValid) {
                mIdentity.mName = "«" + escape(name) + "»";
            }

            return this;
        }

        public Builder setXpanid(byte x[]) {
            mIdentity.mXpanid = (x != null ? x.clone() : null);
            return this;
        }

        public Builder setPanid(int x) {
            mIdentity.mPanid = x;
            return this;
        }

        public Builder setType(@NonNull String x) {
            mIdentity.mType = x;
            return this;
        }

        public Builder setChannel(int x) {
            mIdentity.mChannel = x;
            return this;
        }

        public LowpanIdentity build() {
            return mIdentity;
        }
    }

    LowpanIdentity() {}

    // Instance Variables

    private String mName = "";
    private boolean mIsNameValid = true;
    private byte[] mRawName = new byte[0];
    private String mType = "";
    private byte[] mXpanid = new byte[0];
    private int mPanid = UNSPECIFIED_PANID;
    private int mChannel = UNSPECIFIED_CHANNEL;

    // Public Getters

    public String getName() {
        return mName;
    }

    public boolean isNameValid() {
        return mIsNameValid;
    }

    public byte[] getRawName() {
        return mRawName.clone();
    }

    public byte[] getXpanid() {
        return mXpanid.clone();
    }

    public int getPanid() {
        return mPanid;
    }

    public String getType() {
        return mType;
    }

    public int getChannel() {
        return mChannel;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("Name:").append(getName());

        if (mType.length() > 0) {
            sb.append(", Type:").append(mType);
        }

        if (mXpanid.length > 0) {
            sb.append(", XPANID:").append(HexDump.toHexString(mXpanid));
        }

        if (mPanid != UNSPECIFIED_PANID) {
            sb.append(", PANID:").append(String.format("0x%04X", mPanid));
        }

        if (mChannel != UNSPECIFIED_CHANNEL) {
            sb.append(", Channel:").append(mChannel);
        }

        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LowpanIdentity)) {
            return false;
        }
        LowpanIdentity rhs = (LowpanIdentity) obj;
        return Arrays.equals(mRawName, rhs.mRawName)
                && Arrays.equals(mXpanid, rhs.mXpanid)
                && mType.equals(rhs.mType)
                && mPanid == rhs.mPanid
                && mChannel == rhs.mChannel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Arrays.hashCode(mRawName), mType, Arrays.hashCode(mXpanid), mPanid, mChannel);
    }

    /** Implement the Parcelable interface. */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(mRawName);
        dest.writeString(mType);
        dest.writeByteArray(mXpanid);
        dest.writeInt(mPanid);
        dest.writeInt(mChannel);
    }

    /** Implement the Parcelable interface. */
    public static final @android.annotation.NonNull Creator<LowpanIdentity> CREATOR =
            new Creator<LowpanIdentity>() {

                public LowpanIdentity createFromParcel(Parcel in) {
                    Builder builder = new Builder();

                    builder.setRawName(in.createByteArray());
                    builder.setType(in.readString());
                    builder.setXpanid(in.createByteArray());
                    builder.setPanid(in.readInt());
                    builder.setChannel(in.readInt());

                    return builder.build();
                }

                public LowpanIdentity[] newArray(int size) {
                    return new LowpanIdentity[size];
                }
            };
}
