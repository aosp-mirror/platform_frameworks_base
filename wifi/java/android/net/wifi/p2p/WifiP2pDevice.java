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

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * A class representing a Wi-Fi p2p device
 *
 * Note that the operations are not thread safe
 * {@see WifiP2pManager}
 */
public class WifiP2pDevice implements Parcelable {

    private static final String TAG = "WifiP2pDevice";

    /**
     * The device name is a user friendly string to identify a Wi-Fi p2p device
     */
    public String deviceName = "";

    /**
     * The device MAC address uniquely identifies a Wi-Fi p2p device
     */
    public String deviceAddress = "";

    /**
     * Primary device type identifies the type of device. For example, an application
     * could filter the devices discovered to only display printers if the purpose is to
     * enable a printing action from the user. See the Wi-Fi Direct technical specification
     * for the full list of standard device types supported.
     */
    public String primaryDeviceType;

    /**
     * Secondary device type is an optional attribute that can be provided by a device in
     * addition to the primary device type.
     */
    public String secondaryDeviceType;


    // These definitions match the ones in wpa_supplicant
    /* WPS config methods supported */
    private static final int WPS_CONFIG_DISPLAY         = 0x0008;
    private static final int WPS_CONFIG_PUSHBUTTON      = 0x0080;
    private static final int WPS_CONFIG_KEYPAD          = 0x0100;

    /* Device Capability bitmap */
    private static final int DEVICE_CAPAB_SERVICE_DISCOVERY         = 1;
    private static final int DEVICE_CAPAB_CLIENT_DISCOVERABILITY    = 1<<1;
    private static final int DEVICE_CAPAB_CONCURRENT_OPER           = 1<<2;
    private static final int DEVICE_CAPAB_INFRA_MANAGED             = 1<<3;
    private static final int DEVICE_CAPAB_DEVICE_LIMIT              = 1<<4;
    private static final int DEVICE_CAPAB_INVITATION_PROCEDURE      = 1<<5;

    /* Group Capability bitmap */
    private static final int GROUP_CAPAB_GROUP_OWNER                = 1;
    private static final int GROUP_CAPAB_PERSISTENT_GROUP           = 1<<1;
    private static final int GROUP_CAPAB_GROUP_LIMIT                = 1<<2;
    private static final int GROUP_CAPAB_INTRA_BSS_DIST             = 1<<3;
    private static final int GROUP_CAPAB_CROSS_CONN                 = 1<<4;
    private static final int GROUP_CAPAB_PERSISTENT_RECONN          = 1<<5;
    private static final int GROUP_CAPAB_GROUP_FORMATION            = 1<<6;

    /**
     * WPS config methods supported
     * @hide
     */
    public int wpsConfigMethodsSupported;

    /**
     * Device capability
     * @hide
     */
    public int deviceCapability;

    /**
     * Group capability
     * @hide
     */
    public int groupCapability;

    public static final int CONNECTED   = 0;
    public static final int INVITED     = 1;
    public static final int FAILED      = 2;
    public static final int AVAILABLE   = 3;
    public static final int UNAVAILABLE = 4;

    /** Device connection status */
    public int status = UNAVAILABLE;

    /** @hide */
    public WifiP2pWfdInfo wfdInfo;

    /** Detailed device string pattern with WFD info
     * Example:
     *  P2P-DEVICE-FOUND 00:18:6b:de:a3:6e p2p_dev_addr=00:18:6b:de:a3:6e
     *  pri_dev_type=1-0050F204-1 name='DWD-300-DEA36E' config_methods=0x188
     *  dev_capab=0x21 group_capab=0x9
     */
    private static final Pattern detailedDevicePattern = Pattern.compile(
        "((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) " +
        "(\\d+ )?" +
        "p2p_dev_addr=((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) " +
        "pri_dev_type=(\\d+-[0-9a-fA-F]+-\\d+) " +
        "name='(.*)' " +
        "config_methods=(0x[0-9a-fA-F]+) " +
        "dev_capab=(0x[0-9a-fA-F]+) " +
        "group_capab=(0x[0-9a-fA-F]+)" +
        "( wfd_dev_info=0x000006([0-9a-fA-F]{12}))?"
    );

    /** 2 token device address pattern
     * Example:
     *  P2P-DEVICE-LOST p2p_dev_addr=fa:7b:7a:42:02:13
     *  AP-STA-DISCONNECTED 42:fc:89:a8:96:09
     */
    private static final Pattern twoTokenPattern = Pattern.compile(
        "(p2p_dev_addr=)?((?:[0-9a-f]{2}:){5}[0-9a-f]{2})"
    );

    /** 3 token device address pattern
     * Example:
     *  AP-STA-CONNECTED 42:fc:89:a8:96:09 p2p_dev_addr=fa:7b:7a:42:02:13
     *  AP-STA-DISCONNECTED 42:fc:89:a8:96:09 p2p_dev_addr=fa:7b:7a:42:02:13
     */
    private static final Pattern threeTokenPattern = Pattern.compile(
        "(?:[0-9a-f]{2}:){5}[0-9a-f]{2} p2p_dev_addr=((?:[0-9a-f]{2}:){5}[0-9a-f]{2})"
    );


    public WifiP2pDevice() {
    }

    /**
     * @param string formats supported include
     *  P2P-DEVICE-FOUND fa:7b:7a:42:02:13 p2p_dev_addr=fa:7b:7a:42:02:13
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST1' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0 wfd_dev_info=000006015d022a0032
     *
     *  P2P-DEVICE-LOST p2p_dev_addr=fa:7b:7a:42:02:13
     *
     *  AP-STA-CONNECTED 42:fc:89:a8:96:09 [p2p_dev_addr=02:90:4c:a0:92:54]
     *
     *  AP-STA-DISCONNECTED 42:fc:89:a8:96:09 [p2p_dev_addr=02:90:4c:a0:92:54]
     *
     *  fa:7b:7a:42:02:13
     *
     *  Note: The events formats can be looked up in the wpa_supplicant code
     * @hide
     */
    public WifiP2pDevice(String string) throws IllegalArgumentException {
        String[] tokens = string.split("[ \n]");
        Matcher match;

        if (tokens.length < 1) {
            throw new IllegalArgumentException("Malformed supplicant event");
        }

        switch (tokens.length) {
            case 1:
                /* Just a device address */
                deviceAddress = string;
                return;
            case 2:
                match = twoTokenPattern.matcher(string);
                if (!match.find()) {
                    throw new IllegalArgumentException("Malformed supplicant event");
                }
                deviceAddress = match.group(2);
                return;
            case 3:
                match = threeTokenPattern.matcher(string);
                if (!match.find()) {
                    throw new IllegalArgumentException("Malformed supplicant event");
                }
                deviceAddress = match.group(1);
                return;
            default:
                match = detailedDevicePattern.matcher(string);
                if (!match.find()) {
                    throw new IllegalArgumentException("Malformed supplicant event");
                }

                deviceAddress = match.group(3);
                primaryDeviceType = match.group(4);
                deviceName = match.group(5);
                wpsConfigMethodsSupported = parseHex(match.group(6));
                deviceCapability = parseHex(match.group(7));
                groupCapability = parseHex(match.group(8));
                if (match.group(9) != null) {
                    String str = match.group(10);
                    wfdInfo = new WifiP2pWfdInfo(parseHex(str.substring(0,4)),
                            parseHex(str.substring(4,8)),
                            parseHex(str.substring(8,12)));
                }
                break;
        }

        if (tokens[0].startsWith("P2P-DEVICE-FOUND")) {
            status = AVAILABLE;
        }
    }

    /** Returns true if WPS push button configuration is supported */
    public boolean wpsPbcSupported() {
        return (wpsConfigMethodsSupported & WPS_CONFIG_PUSHBUTTON) != 0;
    }

    /** Returns true if WPS keypad configuration is supported */
    public boolean wpsKeypadSupported() {
        return (wpsConfigMethodsSupported & WPS_CONFIG_KEYPAD) != 0;
    }

    /** Returns true if WPS display configuration is supported */
    public boolean wpsDisplaySupported() {
        return (wpsConfigMethodsSupported & WPS_CONFIG_DISPLAY) != 0;
    }

    /** Returns true if the device is capable of service discovery */
    public boolean isServiceDiscoveryCapable() {
        return (deviceCapability & DEVICE_CAPAB_SERVICE_DISCOVERY) != 0;
    }

    /** Returns true if the device is capable of invitation {@hide}*/
    public boolean isInvitationCapable() {
        return (deviceCapability & DEVICE_CAPAB_INVITATION_PROCEDURE) != 0;
    }

    /** Returns true if the device reaches the limit. {@hide}*/
    public boolean isDeviceLimit() {
        return (deviceCapability & DEVICE_CAPAB_DEVICE_LIMIT) != 0;
    }

    /** Returns true if the device is a group owner */
    public boolean isGroupOwner() {
        return (groupCapability & GROUP_CAPAB_GROUP_OWNER) != 0;
    }

    /** Returns true if the group reaches the limit. {@hide}*/
    public boolean isGroupLimit() {
        return (groupCapability & GROUP_CAPAB_GROUP_LIMIT) != 0;
    }

    /**
     * Update device details. This will be throw an exception if the device address
     * does not match.
     * @param device to be updated
     * @throws IllegalArgumentException if the device is null or device address does not match
     * @hide
     */
    public void update(WifiP2pDevice device) {
        updateSupplicantDetails(device);
        status = device.status;
    }

    /** Updates details obtained from supplicant @hide */
    void updateSupplicantDetails(WifiP2pDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device is null");
        }
        if (device.deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress is null");
        }
        if (!deviceAddress.equals(device.deviceAddress)) {
            throw new IllegalArgumentException("deviceAddress does not match");
        }
        deviceName = device.deviceName;
        primaryDeviceType = device.primaryDeviceType;
        secondaryDeviceType = device.secondaryDeviceType;
        wpsConfigMethodsSupported = device.wpsConfigMethodsSupported;
        deviceCapability = device.deviceCapability;
        groupCapability = device.groupCapability;
        wfdInfo = device.wfdInfo;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WifiP2pDevice)) return false;

        WifiP2pDevice other = (WifiP2pDevice) obj;
        if (other == null || other.deviceAddress == null) {
            return (deviceAddress == null);
        }
        return other.deviceAddress.equals(deviceAddress);
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("Device: ").append(deviceName);
        sbuf.append("\n deviceAddress: ").append(deviceAddress);
        sbuf.append("\n primary type: ").append(primaryDeviceType);
        sbuf.append("\n secondary type: ").append(secondaryDeviceType);
        sbuf.append("\n wps: ").append(wpsConfigMethodsSupported);
        sbuf.append("\n grpcapab: ").append(groupCapability);
        sbuf.append("\n devcapab: ").append(deviceCapability);
        sbuf.append("\n status: ").append(status);
        sbuf.append("\n wfdInfo: ").append(wfdInfo);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    public WifiP2pDevice(WifiP2pDevice source) {
        if (source != null) {
            deviceName = source.deviceName;
            deviceAddress = source.deviceAddress;
            primaryDeviceType = source.primaryDeviceType;
            secondaryDeviceType = source.secondaryDeviceType;
            wpsConfigMethodsSupported = source.wpsConfigMethodsSupported;
            deviceCapability = source.deviceCapability;
            groupCapability = source.groupCapability;
            status = source.status;
            wfdInfo = new WifiP2pWfdInfo(source.wfdInfo);
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(deviceName);
        dest.writeString(deviceAddress);
        dest.writeString(primaryDeviceType);
        dest.writeString(secondaryDeviceType);
        dest.writeInt(wpsConfigMethodsSupported);
        dest.writeInt(deviceCapability);
        dest.writeInt(groupCapability);
        dest.writeInt(status);
        if (wfdInfo != null) {
            dest.writeInt(1);
            wfdInfo.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pDevice> CREATOR =
        new Creator<WifiP2pDevice>() {
            public WifiP2pDevice createFromParcel(Parcel in) {
                WifiP2pDevice device = new WifiP2pDevice();
                device.deviceName = in.readString();
                device.deviceAddress = in.readString();
                device.primaryDeviceType = in.readString();
                device.secondaryDeviceType = in.readString();
                device.wpsConfigMethodsSupported = in.readInt();
                device.deviceCapability = in.readInt();
                device.groupCapability = in.readInt();
                device.status = in.readInt();
                if (in.readInt() == 1) {
                    device.wfdInfo = WifiP2pWfdInfo.CREATOR.createFromParcel(in);
                }
                return device;
            }

            public WifiP2pDevice[] newArray(int size) {
                return new WifiP2pDevice[size];
            }
        };

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
