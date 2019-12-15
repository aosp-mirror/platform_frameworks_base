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

package android.net.wifi.wificond;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Objects;

/**
 * PnoSettings for wificond
 *
 * @hide
 */
public class PnoSettings implements Parcelable {
    public int intervalMs;
    public int min2gRssi;
    public int min5gRssi;
    public int min6gRssi;
    public ArrayList<PnoNetwork> pnoNetworks;

    /** public constructor */
    public PnoSettings() { }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof PnoSettings)) {
            return false;
        }
        PnoSettings settings = (PnoSettings) rhs;
        if (settings == null) {
            return false;
        }
        return intervalMs == settings.intervalMs
                && min2gRssi == settings.min2gRssi
                && min5gRssi == settings.min5gRssi
                && min6gRssi == settings.min6gRssi
                && pnoNetworks.equals(settings.pnoNetworks);
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(intervalMs, min2gRssi, min5gRssi, min6gRssi, pnoNetworks);
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flag| is ignored.
     **/
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(intervalMs);
        out.writeInt(min2gRssi);
        out.writeInt(min5gRssi);
        out.writeInt(min6gRssi);
        out.writeTypedList(pnoNetworks);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<PnoSettings> CREATOR =
            new Parcelable.Creator<PnoSettings>() {
        @Override
        public PnoSettings createFromParcel(Parcel in) {
            PnoSettings result = new PnoSettings();
            result.intervalMs = in.readInt();
            result.min2gRssi = in.readInt();
            result.min5gRssi = in.readInt();
            result.min6gRssi = in.readInt();

            result.pnoNetworks = new ArrayList<PnoNetwork>();
            in.readTypedList(result.pnoNetworks, PnoNetwork.CREATOR);

            return result;
        }

        @Override
        public PnoSettings[] newArray(int size) {
            return new PnoSettings[size];
        }
    };
}
