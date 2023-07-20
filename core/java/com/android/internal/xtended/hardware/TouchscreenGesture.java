/*
 * Copyright (C) 2016 The CyanogenMod Project
 *               2017 The LineageOS Project
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

package com.android.internal.xtended.hardware;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Touchscreen gestures API
 *
 * A device may implement several touchscreen gestures for use while
 * the display is turned off, such as drawing alphabets and shapes.
 * These gestures can be interpreted by userspace to activate certain
 * actions and launch certain apps, such as to skip music tracks,
 * to turn on the flashlight, or to launch the camera app.
 *
 * This *should always* be supported by the hardware directly.
 * A lot of recent touch controllers have a firmware option for this.
 *
 * This API provides support for enumerating the gestures
 * supported by the touchscreen.
 *
 * A TouchscreenGesture is referenced by it's identifier and carries an
 * associated name (up to the user to translate this value).
 */
public class TouchscreenGesture implements Parcelable {

    public final int id;
    public final String name;
    public final int keycode;

    public TouchscreenGesture(int id, String name, int keycode) {
        this.id = id;
        this.name = name;
        this.keycode = keycode;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(id);
        parcel.writeString(name);
        parcel.writeInt(keycode);
    }

    /** @hide */
    public static final Parcelable.Creator<TouchscreenGesture> CREATOR =
            new Parcelable.Creator<TouchscreenGesture>() {

        public TouchscreenGesture createFromParcel(Parcel in) {
            return new TouchscreenGesture(in.readInt(), in.readString(), in.readInt());
        }

        @Override
        public TouchscreenGesture[] newArray(int size) {
            return new TouchscreenGesture[size];
        }
    };
}
