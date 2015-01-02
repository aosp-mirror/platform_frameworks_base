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

/** @hide */
public class WifiPasspointOsuProvider implements Parcelable {

    /** TODO: doc
     * @hide
     */
    public static final int OSU_METHOD_UNKNOWN = -1;

    /** TODO: doc
     * @hide
     */
    public static final int OSU_METHOD_OMADM = 0;

    /** TODO: doc
     * @hide
     */
    public static final int OSU_METHOD_SOAP = 1;

    /** TODO: doc */
    public String ssid;

    /** TODO: doc */
    public String friendlyName;

    /** TODO: doc
     * @hide
     */
    public String serverUri;

    /** TODO: doc
     * @hide
     */
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
    public WifiPasspointOsuProvider() {
        // TODO
    }

    /** copy constructor @hide */
    public WifiPasspointOsuProvider(WifiPasspointOsuProvider source) {
        // TODO
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("SSID: ").append("<").append(ssid).append(">");
        if (friendlyName != null)
            sb.append(" friendlyName: ").append("<").append(friendlyName).append(">");
        if (serverUri != null)
            sb.append(" serverUri: ").append("<").append(serverUri).append(">");
        sb.append(" osuMethod: ").append("<").append(osuMethod).append(">");
        if (iconFileName != null) {
            sb.append(" icon: <").append(iconWidth).append("x")
                    .append(iconHeight).append(" ")
                    .append(iconType).append(" ")
                    .append(iconFileName).append(">");
        }
        if (osuNai != null)
            sb.append(" osuNai: ").append("<").append(osuNai).append(">");
        if (osuService != null)
            sb.append(" osuService: ").append("<").append(osuService).append(">");
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ssid);
        out.writeString(friendlyName);
        out.writeString(serverUri);
        out.writeInt(osuMethod);
        out.writeInt(iconWidth);
        out.writeInt(iconHeight);
        out.writeString(iconType);
        out.writeString(iconFileName);
        out.writeString(osuNai);
        out.writeString(osuService);
        // TODO: icon image?
    }

    public static final Parcelable.Creator<WifiPasspointOsuProvider> CREATOR =
            new Parcelable.Creator<WifiPasspointOsuProvider>() {
                @Override
                public WifiPasspointOsuProvider createFromParcel(Parcel in) {
                    WifiPasspointOsuProvider osu = new WifiPasspointOsuProvider();
                    osu.ssid = in.readString();
                    osu.friendlyName = in.readString();
                    osu.serverUri = in.readString();
                    osu.osuMethod = in.readInt();
                    osu.iconWidth = in.readInt();
                    osu.iconHeight = in.readInt();
                    osu.iconType = in.readString();
                    osu.iconFileName = in.readString();
                    osu.osuNai = in.readString();
                    osu.osuService = in.readString();
                    return osu;
                }

                @Override
                public WifiPasspointOsuProvider[] newArray(int size) {
                    return new WifiPasspointOsuProvider[size];
                }
            };
}
