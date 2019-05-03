/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.android.internal.custom.hardware;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.custom.Concierge;
import com.android.internal.util.custom.Concierge.ParcelInfo;

/**
 * Display Modes API
 *
 * A device may implement a list of preset display modes for different
 * viewing intents, such as movies, photos, or extra vibrance. These
 * modes may have multiple components such as gamma correction, white
 * point adjustment, etc, but are activated by a single control point.
 *
 * This API provides support for enumerating and selecting the
 * modes supported by the hardware.
 *
 * A DisplayMode is referenced by it's identifier and carries an
 * associated name (up to the user to translate this value).
 */
public class DisplayMode implements Parcelable {
    public final int id;
    public final String name;

    public DisplayMode(int id, String name) {
        this.id = id;
        this.name = name;
    }

    private DisplayMode(Parcel parcel) {
        // Read parcelable version via the Concierge
        ParcelInfo parcelInfo = Concierge.receiveParcel(parcel);
        int parcelableVersion = parcelInfo.getParcelVersion();

        // temp vars
        int tmpId = -1;
        String tmpName = null;

        tmpId = parcel.readInt();
        if (parcel.readInt() != 0) {
            tmpName = parcel.readString();
        }

        // set temps
        this.id = tmpId;
        this.name = tmpName;

        // Complete parcel info for the concierge
        parcelInfo.complete();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        // Tell the concierge to prepare the parcel
        ParcelInfo parcelInfo = Concierge.prepareParcel(out);

        out.writeInt(id);
        if (name != null) {
            out.writeInt(1);
            out.writeString(name);
        } else {
            out.writeInt(0);
        }

        // Complete the parcel info for the concierge
        parcelInfo.complete();
    }

    /** @hide */
    public static final Parcelable.Creator<DisplayMode> CREATOR =
            new Parcelable.Creator<DisplayMode>() {
        public DisplayMode createFromParcel(Parcel in) {
            return new DisplayMode(in);
        }

        @Override
        public DisplayMode[] newArray(int size) {
            return new DisplayMode[size];
        }
    };

}
