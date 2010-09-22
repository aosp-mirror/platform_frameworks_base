/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.cat;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Container class for PlayTone commands parameters.
 *
 */
public class ToneSettings implements Parcelable {
    public Duration duration;
    public Tone tone;
    public boolean vibrate;

    public ToneSettings(Duration duration, Tone tone, boolean vibrate) {
        this.duration = duration;
        this.tone = tone;
        this.vibrate = vibrate;
    }

    private ToneSettings(Parcel in) {
        duration = in.readParcelable(null);
        tone = in.readParcelable(null);
        vibrate = in.readInt() == 1;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(duration, 0);
        dest.writeParcelable(tone, 0);
        dest.writeInt(vibrate ? 1 : 0);
    }

    public static final Parcelable.Creator<ToneSettings> CREATOR = new Parcelable.Creator<ToneSettings>() {
        public ToneSettings createFromParcel(Parcel in) {
            return new ToneSettings(in);
        }

        public ToneSettings[] newArray(int size) {
            return new ToneSettings[size];
        }
    };
}