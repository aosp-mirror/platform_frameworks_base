/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Information available from AudioService about the current routes.
 * @hide
 */
public class AudioRoutesInfo implements Parcelable {
    public static final int MAIN_SPEAKER = 0;
    public static final int MAIN_HEADSET = 1<<0;
    public static final int MAIN_HEADPHONES = 1<<1;
    public static final int MAIN_DOCK_SPEAKERS = 1<<2;
    public static final int MAIN_HDMI = 1<<3;
    public static final int MAIN_USB = 1<<4;

    public CharSequence bluetoothName;
    public int mainType = MAIN_SPEAKER;

    public AudioRoutesInfo() {
    }

    public AudioRoutesInfo(AudioRoutesInfo o) {
        bluetoothName = o.bluetoothName;
        mainType = o.mainType;
    }

    AudioRoutesInfo(Parcel src) {
        bluetoothName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(src);
        mainType = src.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{ type=" + typeToString(mainType)
                + (TextUtils.isEmpty(bluetoothName) ? "" : ", bluetoothName=" + bluetoothName)
                + " }";
    }

    private static String typeToString(int type) {
        if (type == MAIN_SPEAKER) return "SPEAKER";
        if ((type & MAIN_HEADSET) != 0) return "HEADSET";
        if ((type & MAIN_HEADPHONES) != 0) return "HEADPHONES";
        if ((type & MAIN_DOCK_SPEAKERS) != 0) return "DOCK_SPEAKERS";
        if ((type & MAIN_HDMI) != 0) return "HDMI";
        if ((type & MAIN_USB) != 0) return "USB";
        return Integer.toHexString(type);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(bluetoothName, dest, flags);
        dest.writeInt(mainType);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AudioRoutesInfo> CREATOR
            = new Parcelable.Creator<AudioRoutesInfo>() {
        public AudioRoutesInfo createFromParcel(Parcel in) {
            return new AudioRoutesInfo(in);
        }

        public AudioRoutesInfo[] newArray(int size) {
            return new AudioRoutesInfo[size];
        }
    };
}
