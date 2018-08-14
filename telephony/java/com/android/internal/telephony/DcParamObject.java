/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telephony;

import android.os.Parcelable;
import android.os.Parcel;

public class DcParamObject implements Parcelable {

    private int mSubId;

    public DcParamObject(int subId) {
        mSubId = subId;
    }

    public DcParamObject(Parcel in) {
        readFromParcel(in);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSubId);
    }

    private void readFromParcel(Parcel in) {
        mSubId = in.readInt();
    }

    public static final Parcelable.Creator<DcParamObject> CREATOR = new Parcelable.Creator<DcParamObject>() {
        public DcParamObject createFromParcel(Parcel in) {
            return new DcParamObject(in);
        }
        public DcParamObject[] newArray(int size) {
            return new DcParamObject[size];
        }
    };

    public int getSubId() {
        return mSubId;
    }
}
