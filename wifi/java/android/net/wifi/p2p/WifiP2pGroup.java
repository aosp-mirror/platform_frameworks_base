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
 */
public class WifiP2pGroup implements Parcelable {

    /** The network name */
    private String mNetworkName;

    /** The network bssid */
    private String mNetworkBssid;

    /** Group owner */
    private WifiP2pDevice mOwner;

    /** Device is group owner */
    private boolean mIsGroupOwner;

    /** Group clients */
    private List<WifiP2pDevice> mClients = new ArrayList<WifiP2pDevice>();

    private int mChannel;

    /**
     * The network passphrase
     * <p/>
     * The passphrase used for WPA2-PSK
     */
    private String mPassphrase;

    /**
     * TODO: fix
     * Sometimes supplicant sends a psk
     */
    private String mPsk;

    /** Indicates that the group is persistent */
    private boolean mIsPersistent;

    private String mInterface;

    public WifiP2pGroup() {
    }

    /**
     * @param string formats supported include
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

                if (nameValue[0].equals("freq")) {
                    try {
                        mChannel = Integer.parseInt(nameValue[1]);
                    } catch (NumberFormatException e) {
                        mChannel = 0; //invalid
                    }
                    continue;
                }

                if (nameValue[0].equals("psk")) {
                    mPsk = nameValue[1];
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

                if (nameValue[0].equals("bssid")) {
                    mNetworkBssid = nameValue[1];
                }
            }
        } else {
            throw new IllegalArgumentException("Malformed supplicant event");
        }
    }

    public boolean isGroupOwner() {
        return mIsGroupOwner;
    }

    public WifiP2pDevice getOwner() {
        return mOwner;
    }

    public void addClient(String address) {
        addClient(new WifiP2pDevice(address));
    }

    public void addClient(WifiP2pDevice device) {
        for (WifiP2pDevice client : mClients) {
            if (client.equals(device)) return;
        }
        mClients.add(device);
    }

    public boolean removeClient(String address) {
        return mClients.remove(new WifiP2pDevice(address));
    }

    public boolean removeClient(WifiP2pDevice device) {
        return mClients.remove(device);
    }

    public boolean isClientListEmpty() {
        return mClients.size() == 0;
    }

    public Collection<WifiP2pDevice> getClientList() {
        return Collections.unmodifiableCollection(mClients);
    }

    public String getInterface() {
        return mInterface;
    }

    // TODO: implement
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        //sbuf.append("SSID: ").append(SSID);
        //sbuf.append("\n passphrase: ").append(passphrase);
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
    // STOPSHIP: implement
    public void writeToParcel(Parcel dest, int flags) {
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiP2pGroup> CREATOR =
        new Creator<WifiP2pGroup>() {
            public WifiP2pGroup createFromParcel(Parcel in) {
                WifiP2pGroup group = new WifiP2pGroup();
                return group;
            }

            public WifiP2pGroup[] newArray(int size) {
                return new WifiP2pGroup[size];
            }
        };
}
