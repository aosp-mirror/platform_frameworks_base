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

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

/**
 * A class representing a Wi-Fi p2p provisional discovery request/response
 * See {@link #WifiP2pProvDiscEvent} for supported types
 *
 * @hide
 */
public class WifiP2pProvDiscEvent {

    private static final String TAG = "WifiP2pProvDiscEvent";

    public static final int PBC_REQ     = 1;
    public static final int PBC_RSP     = 2;
    public static final int ENTER_PIN   = 3;
    public static final int SHOW_PIN    = 4;

    /* One of PBC_REQ, PBC_RSP, ENTER_PIN or SHOW_PIN */
    public int event;

    public WifiP2pDevice device;

    /* Valid when event = SHOW_PIN */
    public String pin;

    public WifiP2pProvDiscEvent() {
        device = new WifiP2pDevice();
    }

    /**
     * @param string formats supported include
     *
     *  P2P-PROV-DISC-PBC-REQ 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0
     *
     *  P2P-PROV-DISC-PBC-RESP 02:12:47:f2:5a:36
     *
     *  P2P-PROV-DISC-ENTER-PIN 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0
     *
     *  P2P-PROV-DISC-SHOW-PIN 42:fc:89:e1:e2:27 44490607 p2p_dev_addr=42:fc:89:e1:e2:27
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0
     *
     *  Note: The events formats can be looked up in the wpa_supplicant code
     * @hide
     */
    public WifiP2pProvDiscEvent(String string) throws IllegalArgumentException {
        String[] tokens = string.split(" ");

        if (tokens.length < 2) {
            throw new IllegalArgumentException("Malformed event " + string);
        }

        if (tokens[0].endsWith("PBC-REQ")) event = PBC_REQ;
        else if (tokens[0].endsWith("PBC-RESP")) event = PBC_RSP;
        else if (tokens[0].endsWith("ENTER-PIN")) event = ENTER_PIN;
        else if (tokens[0].endsWith("SHOW-PIN")) event = SHOW_PIN;
        else throw new IllegalArgumentException("Malformed event " + string);

        device = new WifiP2pDevice();

        for (String token : tokens) {
            String[] nameValue = token.split("=");
            if (nameValue.length != 2) {
                //mac address without key is device address
                if (token.matches("(([0-9a-f]{2}:){5}[0-9a-f]{2})")) {
                    device.deviceAddress = token;
                } else if (token.matches("[0-9]+")) {
                    pin = token;
                } else {
                    //ignore;
                }
                continue;
            }

            if (nameValue[0].equals("p2p_dev_addr")) {
                device.deviceAddress = nameValue[1];
                continue;
            }

            if (nameValue[0].equals("pri_dev_type")) {
                device.primaryDeviceType = nameValue[1];
                continue;
            }

            if (nameValue[0].equals("name")) {
                device.deviceName = trimQuotes(nameValue[1]);
                continue;
            }

            if (nameValue[0].equals("config_methods")) {
                device.wpsConfigMethodsSupported = parseHex(nameValue[1]);
                continue;
            }

            if (nameValue[0].equals("dev_capab")) {
                device.deviceCapability = parseHex(nameValue[1]);
                continue;
            }

            if (nameValue[0].equals("group_capab")) {
                device.groupCapability = parseHex(nameValue[1]);
                continue;
            }
        }
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append(device);
        sbuf.append("\n event: ").append(event);
        sbuf.append("\n pin: ").append(pin);
        return sbuf.toString();
    }

    private String trimQuotes(String str) {
        str = str.trim();
        if (str.startsWith("'") && str.endsWith("'")) {
            return str.substring(1, str.length()-1);
        }
        return str;
    }

    //supported formats: 0x1abc, 0X1abc, 1abc
    private int parseHex(String hexString) {
        int num = 0;
        if (hexString.startsWith("0x") || hexString.startsWith("0X")) {
            hexString = hexString.substring(2);
        }

        try {
            num = Integer.parseInt(hexString, 16);
        } catch(NumberFormatException e) {
            Log.e(TAG, "Failed to parse hex string " + hexString);
        }
        return num;
    }
}
