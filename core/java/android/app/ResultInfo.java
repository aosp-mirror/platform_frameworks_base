/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app;

import android.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * {@hide}
 */
public class ResultInfo implements Parcelable {
    @UnsupportedAppUsage
    public final String mResultWho;
    @UnsupportedAppUsage
    public final int mRequestCode;
    public final int mResultCode;
    @UnsupportedAppUsage
    public final Intent mData;

    @UnsupportedAppUsage
    public ResultInfo(String resultWho, int requestCode, int resultCode,
            Intent data) {
        mResultWho = resultWho;
        mRequestCode = requestCode;
        mResultCode = resultCode;
        mData = data;
    }

    public String toString() {
        return "ResultInfo{who=" + mResultWho + ", request=" + mRequestCode
            + ", result=" + mResultCode + ", data=" + mData + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mResultWho);
        out.writeInt(mRequestCode);
        out.writeInt(mResultCode);
        if (mData != null) {
            out.writeInt(1);
            mData.writeToParcel(out, 0);
        } else {
            out.writeInt(0);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final Parcelable.Creator<ResultInfo> CREATOR
            = new Parcelable.Creator<ResultInfo>() {
        public ResultInfo createFromParcel(Parcel in) {
            return new ResultInfo(in);
        }

        public ResultInfo[] newArray(int size) {
            return new ResultInfo[size];
        }
    };

    public ResultInfo(Parcel in) {
        mResultWho = in.readString();
        mRequestCode = in.readInt();
        mResultCode = in.readInt();
        if (in.readInt() != 0) {
            mData = Intent.CREATOR.createFromParcel(in);
        } else {
            mData = null;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ResultInfo)) {
            return false;
        }
        final ResultInfo other = (ResultInfo) obj;
        final boolean intentsEqual = mData == null ? (other.mData == null)
                : mData.filterEquals(other.mData);
        return intentsEqual && Objects.equals(mResultWho, other.mResultWho)
                && mResultCode == other.mResultCode
                && mRequestCode == other.mRequestCode;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + mRequestCode;
        result = 31 * result + mResultCode;
        result = 31 * result + Objects.hashCode(mResultWho);
        if (mData != null) {
            result = 31 * result + mData.filterHashCode();
        }
        return result;
    }
}
