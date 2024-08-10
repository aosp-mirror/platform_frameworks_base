/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.uicc.IccUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * Contains Carrier-specific (and opaque) Protocol configuration Option
 * Data.  In general this is only passed on to carrier-specific applications
 * for interpretation.
 *
 * @hide
 */
public class PcoData implements Parcelable {

    public final int cid;
    public final String bearerProto;
    public final int pcoId;
    public final byte[] contents;

    public PcoData(int cid, String bearerProto, int pcoId, byte[]contents) {
        this.cid = cid;
        this.bearerProto = bearerProto;
        this.pcoId = pcoId;
        this.contents = contents;
    }

    public PcoData(Parcel in) {
        cid = in.readInt();
        bearerProto = in.readString();
        pcoId = in.readInt();
        contents = in.createByteArray();
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(cid);
        out.writeString(bearerProto);
        out.writeInt(pcoId);
        out.writeByteArray(contents);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable.Creator}
     *
     * @hide
     */
    public static final @android.annotation.NonNull Parcelable.Creator<PcoData> CREATOR = new Parcelable.Creator() {
        public PcoData createFromParcel(Parcel in) {
            return new PcoData(in);
        }

        public PcoData[] newArray(int size) {
            return new PcoData[size];
        }
    };

    @Override
    public String toString() {
        return "PcoData(" + cid + ", " + bearerProto + ", " + pcoId + " "
                + IccUtils.bytesToHexString(contents) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PcoData pcoData = (PcoData) o;
        return cid == pcoData.cid
                && pcoId == pcoData.pcoId
                && Objects.equals(bearerProto, pcoData.bearerProto)
                && Arrays.equals(contents, pcoData.contents);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(cid, bearerProto, pcoId);
        result = 31 * result + Arrays.hashCode(contents);
        return result;
    }
}
