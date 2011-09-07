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

package android.net.wifi.p2p;

import android.net.wifi.Wps;
import android.net.wifi.Wps.Setup;
import android.os.Parcelable;
import android.os.Parcel;

/**
 * A class representing a Wi-Fi P2p configuration
 * @hide
 */
public class WifiP2pConfig implements Parcelable {

    /**
     * Device address
     */
    public String deviceAddress;

    /**
     * WPS configuration
     */
    public Wps wpsConfig;

    /**
     * This is an integer value between 0 and 15 where 0 indicates the least
     * inclination to be a group owner and 15 indicates the highest inclination
     * to be a group owner.
     *
     * A value of -1 indicates the system can choose an appropriate value.
     */
    public int groupOwnerIntent = -1;

    /**
     * Indicates whether the configuration is saved
     * @hide
     */
    public enum Persist {
        SYSTEM_DEFAULT,
        YES,
        NO
    }

    /** @hide */
    public Persist persist = Persist.SYSTEM_DEFAULT;

    public WifiP2pConfig() {
        //set defaults
        wpsConfig = new Wps();
        wpsConfig.setup = Setup.PBC;
    }

    /* P2P-GO-NEG-REQUEST 42:fc:89:a8:96:09 dev_passwd_id=4 */
    public WifiP2pConfig(String supplicantEvent) throws IllegalArgumentException {
        String[] tokens = supplicantEvent.split(" ");

        if (tokens.length < 2 || !tokens[0].equals("P2P-GO-NEG-REQUEST")) {
            throw new IllegalArgumentException("Malformed supplicant event");
        }

        deviceAddress = tokens[1];
        wpsConfig = new Wps();

        if (tokens.length > 2) {
            String[] nameVal = tokens[2].split("=");
            int devPasswdId;
            try {
                devPasswdId = Integer.parseInt(nameVal[1]);
            } catch (NumberFormatException e) {
                devPasswdId = 0;
            }
            //As defined in wps/wps_defs.h
            switch (devPasswdId) {
                case 0x00:
                    wpsConfig.setup = Setup.LABEL;
                    break;
                case 0x01:
                    wpsConfig.setup = Setup.KEYPAD;
                    break;
                case 0x04:
                    wpsConfig.setup = Setup.PBC;
                    break;
                case 0x05:
                    wpsConfig.setup = Setup.DISPLAY;
                    break;
                default:
                    wpsConfig.setup = Setup.PBC;
                    break;
            }
        }
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("\n address: ").append(deviceAddress);
        sbuf.append("\n wps: ").append(wpsConfig);
        sbuf.append("\n groupOwnerIntent: ").append(groupOwnerIntent);
        sbuf.append("\n persist: ").append(persist.toString());
        return sbuf.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** copy constructor {@hide} */
    public WifiP2pConfig(WifiP2pConfig source) {
        if (source != null) {
            //TODO: implement
       }
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(deviceAddress);
        dest.writeParcelable(wpsConfig, flags);
        dest.writeInt(groupOwnerIntent);
        dest.writeString(persist.name());
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiP2pConfig> CREATOR =
        new Creator<WifiP2pConfig>() {
            public WifiP2pConfig createFromParcel(Parcel in) {
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = in.readString();
                config.wpsConfig = (Wps) in.readParcelable(null);
                config.groupOwnerIntent = in.readInt();
                config.persist = Persist.valueOf(in.readString());
                return config;
            }

            public WifiP2pConfig[] newArray(int size) {
                return new WifiP2pConfig[size];
            }
        };
}
