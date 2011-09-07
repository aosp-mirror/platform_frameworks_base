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

/**
 * A class representing a Wi-Fi P2p group
 * @hide
 *
 * {@see WifiP2pManager}
 */
public class WifiP2pGroup implements Parcelable {

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

    WifiP2pGroup() {
    }

    /**
     * @param supplicantEvent formats supported include
     *
     *  P2P-GROUP-STARTED p2p-wlan0-0 [client|GO] ssid="DIRECT-W8" freq=2437
     *  [psk=2182b2e50e53f260d04f3c7b25ef33c965a3291b9b36b455a82d77fd82ca15bc|
     *  passphrase="fKG4jMe3"] go_dev_addr=fa:7b:7a:42:02:13
     *
     *  P2P-GROUP-REMOVED p2p-wlan0-0 [client|GO] reason=REQUESTED
     *
     *  P2P-INVITATION-RECEIVED sa=fa:7b:7a:42:02:13 go_dev_addr=f8:7b:7a:42:02:13
     *  bssid=fa:7b:7a:42:82:13 unknown-network
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

            for (String token : tokens) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) continue;

                if (nameValue[0].equals("ssid")) {
                    mNetworkName = nameValue[1];
                    continue;
                }

                if (nameValue[0].equals("passphrase")) {
                    mPassphrase = nameValue[1];
                    continue;
                }

                if (nameValue[0].equals("go_dev_addr")) {
                    mOwner = new WifiP2pDevice(nameValue[1]);
                }
            }
        } else if (tokens[0].equals("P2P-INVITATION-RECEIVED")) {
            for (String token : tokens) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) continue;

                if (nameValue[0].equals("go_dev_addr")) {
                    mOwner = new WifiP2pDevice(nameValue[1]);
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
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("network: ").append(mNetworkName);
        sbuf.append("\n isGO: ").append(mIsGroupOwner);
        sbuf.append("\n GO: ").append(mOwner);
        for (WifiP2pDevice client : mClients) {
            sbuf.append("\n Client: ").append(client);
        }
        sbuf.append("\n interface: ").append(mInterface);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** copy constructor {@hide} */
    // TODO: implement
    public WifiP2pGroup(WifiP2pGroup source) {
        if (source != null) {
       }
    }

    /** Implement the Parcelable interface {@hide} */
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
    }

    /** Implement the Parcelable interface {@hide} */
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
                return group;
            }

            public WifiP2pGroup[] newArray(int size) {
                return new WifiP2pGroup[size];
            }
        };
}
