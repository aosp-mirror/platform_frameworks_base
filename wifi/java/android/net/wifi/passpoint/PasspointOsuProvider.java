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

package android.net.wifi.passpoint;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * TODO: doc
 */
public class PasspointOsuProvider implements Parcelable {

    /** TODO: doc */
    public static final int OSU_METHOD_UNKNOWN = -1;

    /** TODO: doc */
    public static final int OSU_METHOD_OMADM = 0;

    /** TODO: doc */
    public static final int OSU_METHOD_SOAP = 1;

    /** TODO: doc */
    public String ssid;

    /** TODO: doc */
    public String friendlyName;

    /** TODO: doc */
    public String serverUri;

    /** TODO: doc */
    public int osuMethod = OSU_METHOD_UNKNOWN;

    /** TODO: doc */
    public int iconWidth;

    /** TODO: doc */
    public int iconHeight;

    /** TODO: doc */
    public String iconType;

    /** TODO: doc */
    public String iconFileName;

    /** TODO: doc */
    public Object icon; // TODO: should change to image format

    /** TODO: doc */
    public String osuNai;

    /** TODO: doc */
    public String osuService;


    /** default constructor @hide */
    public PasspointOsuProvider() {
        // TODO
    }

    /** copy constructor @hide */
    public PasspointOsuProvider(PasspointOsuProvider source) {
        // TODO
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("SSID: ").append(ssid);
        if (friendlyName != null)
            sb.append(" friendlyName: ").append(friendlyName);
        if (serverUri != null)
            sb.append(" serverUri: ").append(serverUri);
        sb.append(" osuMethod: ").append(osuMethod);
        if (iconFileName != null) {
            sb.append(" icon: [").append(iconWidth).append("x")
              .append(iconHeight).append(" ")
              .append(iconType).append(" ")
              .append(iconFileName);
        }
        if (osuNai != null)
            sb.append(" osuNai: ").append(osuNai);
        if (osuService != null)
            sb.append(" osuService: ").append(osuService);
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeValue(ssid);
        out.writeValue(friendlyName);
        out.writeValue(serverUri);
        out.writeInt(osuMethod);
        out.writeInt(iconWidth);
        out.writeInt(iconHeight);
        out.writeValue(iconType);
        out.writeValue(iconFileName);
        out.writeValue(osuNai);
        out.writeValue(osuService);
        // TODO: icon image?
    }

    public static final Parcelable.Creator<PasspointOsuProvider> CREATOR =
            new Parcelable.Creator<PasspointOsuProvider>() {
        @Override
        public PasspointOsuProvider createFromParcel(Parcel in) {
            PasspointOsuProvider osu = new PasspointOsuProvider();
            osu.ssid = (String) in.readValue(String.class.getClassLoader());
            osu.friendlyName = (String) in.readValue(String.class.getClassLoader());
            osu.serverUri = (String) in.readValue(String.class.getClassLoader());
            osu.osuMethod = in.readInt();
            osu.iconWidth = in.readInt();
            osu.iconHeight = in.readInt();
            osu.iconType = (String) in.readValue(String.class.getClassLoader());
            osu.iconFileName = (String) in.readValue(String.class.getClassLoader());
            osu.osuNai = (String) in.readValue(String.class.getClassLoader());
            osu.osuService = (String) in.readValue(String.class.getClassLoader());
            return osu;
        }

        @Override
        public PasspointOsuProvider[] newArray(int size) {
            return new PasspointOsuProvider[size];
        }
    };
}
