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

package android.app;

import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;

/**
 * Information returned after waiting for an activity start.
 *
 * @hide
 */
public class WaitResult implements Parcelable {
    public int result;
    public boolean timeout;
    public ComponentName who;
    public long thisTime;
    public long totalTime;

    public WaitResult() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeInt(timeout ? 1 : 0);
        ComponentName.writeToParcel(who, dest);
        dest.writeLong(thisTime);
        dest.writeLong(totalTime);
    }

    public static final Parcelable.Creator<WaitResult> CREATOR
            = new Parcelable.Creator<WaitResult>() {
        @Override
        public WaitResult createFromParcel(Parcel source) {
            return new WaitResult(source);
        }

        @Override
        public WaitResult[] newArray(int size) {
            return new WaitResult[size];
        }
    };

    private WaitResult(Parcel source) {
        result = source.readInt();
        timeout = source.readInt() != 0;
        who = ComponentName.readFromParcel(source);
        thisTime = source.readLong();
        totalTime = source.readLong();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "WaitResult:");
        pw.println(prefix + "  result=" + result);
        pw.println(prefix + "  timeout=" + timeout);
        pw.println(prefix + "  who=" + who);
        pw.println(prefix + "  thisTime=" + thisTime);
        pw.println(prefix + "  totalTime=" + totalTime);
    }
}