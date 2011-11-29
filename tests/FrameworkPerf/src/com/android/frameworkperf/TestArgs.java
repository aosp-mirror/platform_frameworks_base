/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.frameworkperf;

import android.os.Parcel;
import android.os.Parcelable;

public class TestArgs implements Parcelable {
    long maxTime;
    long maxOps = -1;
    int combOp = -1;
    int fgOp = -1;
    int bgOp = -1;

    public TestArgs() {
    }

    public TestArgs(Parcel source) {
        maxTime = source.readLong();
        maxOps = source.readLong();
        combOp = source.readInt();
        fgOp = source.readInt();
        bgOp = source.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(maxTime);
        dest.writeLong(maxOps);
        dest.writeInt(combOp);
        dest.writeInt(fgOp);
        dest.writeInt(bgOp);
    }

    public static final Parcelable.Creator<TestArgs> CREATOR
            = new Parcelable.Creator<TestArgs>() {
        public TestArgs createFromParcel(Parcel in) {
            return new TestArgs(in);
        }

        public TestArgs[] newArray(int size) {
            return new TestArgs[size];
        }
    };
}
