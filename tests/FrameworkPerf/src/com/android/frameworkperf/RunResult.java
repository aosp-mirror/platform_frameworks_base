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

public class RunResult implements Parcelable {
    final String name;
    final String fgLongName;
    final String bgLongName;
    final long fgTime;
    final long fgOps;
    final long bgTime;
    final long bgOps;

    RunResult(TestService.TestRunner op) {
        name = op.getName();
        fgLongName = op.getForegroundLongName();
        bgLongName = op.getBackgroundLongName();
        fgTime = op.getForegroundTime();
        fgOps = op.getForegroundOps();
        bgTime = op.getBackgroundTime();
        bgOps = op.getBackgroundOps();
    }

    RunResult(Parcel source) {
        name = source.readString();
        fgLongName = source.readString();
        bgLongName = source.readString();
        fgTime = source.readLong();
        fgOps = source.readLong();
        bgTime = source.readLong();
        bgOps = source.readLong();
    }

    float getFgMsPerOp() {
        return fgOps != 0 ? (fgTime / (float)fgOps) : 0;
    }

    float getBgMsPerOp() {
        return bgOps != 0 ? (bgTime / (float)bgOps) : 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(fgLongName);
        dest.writeString(bgLongName);
        dest.writeLong(fgTime);
        dest.writeLong(fgOps);
        dest.writeLong(bgTime);
        dest.writeLong(bgOps);
    }

    public static final Parcelable.Creator<RunResult> CREATOR
            = new Parcelable.Creator<RunResult>() {
        public RunResult createFromParcel(Parcel in) {
            return new RunResult(in);
        }

        public RunResult[] newArray(int size) {
            return new RunResult[size];
        }
    };
}