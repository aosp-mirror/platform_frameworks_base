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

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * A class representing a Wi-Fi P2p group. A p2p group consists of a single group
 * owner and one or more clients. In the case of a group with only two devices, one
 * will be the group owner and the other will be a group client.
 *
 * {@see WifiP2pManager}
 */
public class WifiP2pGroup implements Parcelable {

    /** The temporary network id.
     * {@hide} */
    public static final int TEMPORARY_NET_ID = -1;

    /** The persistent network id.
     * If a matching persistent profile is found, use it.
     * Otherwise, create a new persistent profile.
     * {@hide} */
    public static final int PERSISTENT_NET_ID = -2;

    /** The network name */
    private String mNetworkName;

    /** Group owner */
    private WifiP2pDevice mOwner;

    /** Device is group owner */
    private boolean mIsGroupOwner;

    /** Group clients */
    private List<WifiP2pDevice> mClients = new ArrayList<WifiP2pDevice>();

    /** The passphrase used for WPA2-PSK */
    private String mPassphrase;

    private String mInterface;

    /** The network id in the wpa_supplicant */
    private int mNetId;

    /** P2P group started string pattern */
    private static final Pattern groupStartedPattern = Pattern.compile(
        "ssid=\"(.+)\" " +
        "freq=(\\d+) " +
        "(?:psk=)?([0-9a-fA-F]{64})?" +
        "(?:passphrase=)?(?:\"(.{0,63})\")? " +
        "go_dev_addr=((?:[0-9a-f]{2}:){5}[0-9a-f]{2})" +
        " ?(\\[PERSISTENT\\])?"
    );

    public WifiP2pGroup() {
    }

    /**
     * @param supplicantEvent formats supported include
     *
     *  P2P-GROUP-STARTED p2p-wlan0-0 [client|GO] ssid="DIRECT-W8" freq=2437
     *  [psk=2182b2e50e53f260d04f3c7b25ef33c965a3291b9b36b455a82d77fd82ca15bc|
     *  passphrase="fKG4jMe3"] go_dev_addr=fa:7b:7a:42:02:13 [PERSISTENT]
     *
     *  P2P-GROUP-REMOVED p2p-wlan0-0 [client|GO] reason=REQUESTED
     *
     *  P2P-INVITATION-RECEIVED sa=fa:7b:7a:42:02:13 go_dev_addr=f8:7b:7a:42:02:13
     *  bssid=fa:7b:7a:42:82:13 unknown-network
     *
     *  P2P-INVITATION-RECEIVED sa=b8:f9:34:2a:c7:9d persistent=0
     *
     *  Note: The events formats can be looked up in the wpa_supplicant code
     *  @hide
     */
    public WifiP2pGroup(String supplicantEvent) throws IllegalArgumentException {

        String[] tokens = supplicantEvent.split(" ");

        if (tokens.length < 3) {
            throw new IllegalArgumentException("Malformed supplicant event");
        }

        if (tokens[0].startsWith("P2P-GROUP")) {
            mInterface = tokens[1];
            mIsGroupOwner = tokens[2].equals("GO");

            Matcher match = groupStartedPattern.matcher(supplicantEvent);
            if (!match.find()) {
                return;
            }

            mNetworkName = match.group(1);
            //freq and psk are unused right now
            //int freq = Integer.parseInt(match.group(2));
            //String psk = match.group(3);
            mPassphrase = match.group(4);
            mOwner = new WifiP2pDevice(match.group(5));
            if (match.group(6) != null) {
                mNetId = PERSISTENT_NET_ID;
            } else {
                mNetId = TEMPORARY_NET_ID;
            }
        } else if (tokens[0].equals("P2P-INVITATION-RECEIVED")) {
            String sa = null;
            mNetId = PERSISTENT_NET_ID;
            for (String token : tokens) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) continue;

                if (nameValue[0].equals("sa")) {
                    sa = nameValue[1];

                    // set source address into the client list.
                    WifiP2pDevice dev = new WifiP2pDevice();
                    dev.deviceAddress = nameValue[1];
                    mClients.add(dev);
                    continue;
                }

                if (nameValue[0].equals("go_dev_addr")) {
                    mOwner = new WifiP2pDevice(nameValue[1]);
                    continue;
                }

                if (nameValue[0].equals("persistent")) {
                    mNetId = Integer.parseInt(nameValue[1]);
                    continue;
                }
            }
        } else {
            throw new IllegalArgumentException("Malformed supplicant event");
        }
    }

    /** @hide */
    public void setNetworkName(String networkName) {
        mNetworkName = networkName;
    }

    /**
     * Get the network name (SSID) of the group. Legacy Wi-Fi clients will discover
     * the p2p group using the network name.
     */
    public String getNetworkName() {
        return mNetworkName;
    }

    /** @hide */
    public void setIsGroupOwner(boolean isGo) {
        mIsGroupOwner = isGo;
    }

    /** Check whether this device is the group owner of the created p2p group */
    public boolean isGroupOwner() {
        return mIsGroupOwner;
    }

    /** @hide */
    public void setOwner(WifiP2pDevice device) {
        mOwner = device;
    }

    /** Get the details of the group owner as a {@link WifiP2pDevice} object */
    public WifiP2pDevice getOwner() {
        return mOwner;
    }

    /** @hide */
    public void addClient(String address) {
        addClient(new WifiP2pDevice(address));
    }

    /** @hide */
    public void addClient(WifiP2pDevice device) {
        for (WifiP2pDevice client : mClients) {
            if (client.equals(device)) return;
        }
        mClients.add(device);
    }

    /** @hide */
    public boolean removeClient(String address) {
        return mClients.remove(new WifiP2pDevice(address));
    }

    /** @hide */
    public boolean removeClient(WifiP2pDevice device) {
        return mClients.remove(device);
    }

    /** @hide */
    public boolean isClientListEmpty() {
        return mClients.size() == 0;
    }

    /** @hide Returns {@code true} if the device is part of the group */
    public boolean contains(WifiP2pDevice device) {
        if (mOwner.equals(device) || mClients.contains(device)) return true;
        return false;
    }

    /** Get the list of clients currently part of the p2p group */
    public Collection<WifiP2pDevice> getClientList() {
        return Collections.unmodifiableCollection(mClients);
    }

    /** @hide */
    public void setPassphrase(String passphrase) {
        mPassphrase = passphrase;
    }

    /**
     * Get the passphrase of the group. This function will return a valid passphrase only
     * at the group owner. Legacy Wi-Fi clients will need this passphrase alongside
     * network name obtained from {@link #getNetworkName()} to join the group
     */
    public String getPassphrase() {
        return mPassphrase;
    }

    /** @hide */
    public void setInterface(String intf) {
        mInterface = intf;
    }

    /** Get the interface name on which the group is created */
    public String getInterface() {
        return mInterface;
    }

    /** @hide */
    public int getNetworkId() {
        return mNetId;
    }

    /** @hide */
    public void setNetworkId(int netId) {
        this.mNetId = netId;
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("network: ").append(mNetworkName);
        sbuf.append("\n isGO: ").append(mIsGroupOwner);
        sbuf.append("\n GO: ").append(mOwner);
        for (WifiP2pDevice client : mClients) {
            sbuf.append("\n Client: ").append(client);
        }
        sbuf.append("\n interface: ").append(mInterface);
        sbuf.append("\n networkId: ").append(mNetId);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    public WifiP2pGroup(WifiP2pGroup source) {
        if (source != null) {
            mNetworkName = source.getNetworkName();
            mOwner = new WifiP2pDevice(source.getOwner());
            mIsGroupOwner = source.mIsGroupOwner;
            for (WifiP2pDevice d : source.getClientList()) mClients.add(d);
            mPassphrase = source.getPassphrase();
            mInterface = source.getInterface();
            mNetId = source.getNetworkId();
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mNetworkName);
        dest.writeParcelable(mOwner, flags);
        dest.writeByte(mIsGroupOwner ? (byte) 1: (byte) 0);
        dest.writeInt(mClients.size());
        for (WifiP2pDevice client : mClients) {
            dest.writeParcelable(client, flags);
        }
        dest.writeString(mPassphrase);
        dest.writeString(mInterface);
        dest.writeInt(mNetId);
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pGroup> CREATOR =
        new Creator<WifiP2pGroup>() {
            public WifiP2pGroup createFromParcel(Parcel in) {
                WifiP2pGroup group = new WifiP2pGroup();
                group.setNetworkName(in.readString());
                group.setOwner((WifiP2pDevice)in.readParcelable(null));
                group.setIsGroupOwner(in.readByte() == (byte)1);
                int clientCount = in.readInt();
                for (int i=0; i<clientCount; i++) {
                    group.addClient((WifiP2pDevice) in.readParcelable(null));
                }
                group.setPassphrase(in.readString());
                group.setInterface(in.readString());
                group.setNetworkId(in.readInt());
                return group;
            }

            public WifiP2pGroup[] newArray(int size) {
                return new WifiP2pGroup[size];
            }
        };
}
