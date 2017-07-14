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

import android.os.Parcel;
import android.os.Parcelable;
import com.android.internal.util.HexDump;
import java.util.Arrays;
import java.util.Objects;

/**
 * Describes a credential for a LoWPAN network.
 *
 * @hide
 */
// @SystemApi
public class LowpanCredential implements Parcelable {

    public static final int UNSPECIFIED_KEY_INDEX = 0;

    private byte[] mMasterKey = null;
    private int mMasterKeyIndex = UNSPECIFIED_KEY_INDEX;

    LowpanCredential() {}

    private LowpanCredential(byte[] masterKey, int keyIndex) {
        setMasterKey(masterKey, keyIndex);
    }

    private LowpanCredential(byte[] masterKey) {
        setMasterKey(masterKey);
    }

    public static LowpanCredential createMasterKey(byte[] masterKey) {
        return new LowpanCredential(masterKey);
    }

    public static LowpanCredential createMasterKey(byte[] masterKey, int keyIndex) {
        return new LowpanCredential(masterKey, keyIndex);
    }

    void setMasterKey(byte[] masterKey) {
        if (masterKey != null) {
            masterKey = masterKey.clone();
        }
        mMasterKey = masterKey;
    }

    void setMasterKeyIndex(int keyIndex) {
        mMasterKeyIndex = keyIndex;
    }

    void setMasterKey(byte[] masterKey, int keyIndex) {
        setMasterKey(masterKey);
        setMasterKeyIndex(keyIndex);
    }

    public byte[] getMasterKey() {
        if (mMasterKey != null) {
            return mMasterKey.clone();
        }
        return null;
    }

    public int getMasterKeyIndex() {
        return mMasterKeyIndex;
    }

    public boolean isMasterKey() {
        return mMasterKey != null;
    }

    public String toSensitiveString() {
        StringBuffer sb = new StringBuffer();

        sb.append("<LowpanCredential");

        if (isMasterKey()) {
            sb.append(" MasterKey:").append(HexDump.toHexString(mMasterKey));
            if (mMasterKeyIndex != UNSPECIFIED_KEY_INDEX) {
                sb.append(", Index:").append(mMasterKeyIndex);
            }
        } else {
            sb.append(" empty");
        }

        sb.append(">");

        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("<LowpanCredential");

        if (isMasterKey()) {
            // We don't print out the contents of the key here,
            // we only do that in toSensitiveString.
            sb.append(" MasterKey");
            if (mMasterKeyIndex != UNSPECIFIED_KEY_INDEX) {
                sb.append(", Index:").append(mMasterKeyIndex);
            }
        } else {
            sb.append(" empty");
        }

        sb.append(">");

        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LowpanCredential)) {
            return false;
        }
        LowpanCredential rhs = (LowpanCredential) obj;
        return Arrays.equals(mMasterKey, rhs.mMasterKey) && mMasterKeyIndex == rhs.mMasterKeyIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(mMasterKey), mMasterKeyIndex);
    }

    /** Implement the Parcelable interface. */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(mMasterKey);
        dest.writeInt(mMasterKeyIndex);
    }

    /** Implement the Parcelable interface. */
    public static final Creator<LowpanCredential> CREATOR =
            new Creator<LowpanCredential>() {

                public LowpanCredential createFromParcel(Parcel in) {
                    LowpanCredential credential = new LowpanCredential();

                    credential.mMasterKey = in.createByteArray();
                    credential.mMasterKeyIndex = in.readInt();

                    return credential;
                }

                public LowpanCredential[] newArray(int size) {
                    return new LowpanCredential[size];
                }
            };
}
