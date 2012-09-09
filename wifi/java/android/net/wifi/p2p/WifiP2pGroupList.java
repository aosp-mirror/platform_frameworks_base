/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.util.Collection;
import java.util.Map;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.LruCache;


/**
 * A class representing a Wi-Fi P2p group list
 *
 * {@see WifiP2pManager}
 * @hide
 */
public class WifiP2pGroupList implements Parcelable {

    private static final int CREDENTIAL_MAX_NUM             =   32;

    private final LruCache<Integer, WifiP2pGroup> mGroups;
    private final GroupDeleteListener mListener;

    private boolean isClearCalled = false;

    public interface GroupDeleteListener {
        public void onDeleteGroup(int netId);
    }

    WifiP2pGroupList() {
        this(null, null);
    }

    WifiP2pGroupList(WifiP2pGroupList source, GroupDeleteListener listener) {
        mListener = listener;
        mGroups = new LruCache<Integer, WifiP2pGroup>(CREDENTIAL_MAX_NUM) {
            @Override
            protected void entryRemoved(boolean evicted, Integer netId,
                    WifiP2pGroup oldValue, WifiP2pGroup newValue) {
                if (mListener != null && !isClearCalled) {
                    mListener.onDeleteGroup(oldValue.getNetworkId());
                }
            }
        };

        if (source != null) {
            for (Map.Entry<Integer, WifiP2pGroup> item : source.mGroups.snapshot().entrySet()) {
                mGroups.put(item.getKey(), item.getValue());
            }
        }
    }

    /**
     * Return the list of p2p group.
     *
     * @return the list of p2p group.
     */
    public Collection<WifiP2pGroup> getGroupList() {
        return mGroups.snapshot().values();
    }

    /**
     * Add the specified group to this group list.
     *
     * @param group
     */
    void add(WifiP2pGroup group) {
        mGroups.put(group.getNetworkId(), group);
    }

    /**
     * Remove the group with the specified network id from this group list.
     *
     * @param netId
     */
    void remove(int netId) {
        mGroups.remove(netId);
    }

    /**
     * Remove the group with the specified device address from this group list.
     *
     * @param deviceAddress
     */
    void remove(String deviceAddress) {
        remove(getNetworkId(deviceAddress));
    }

    /**
     * Clear the group.
     */
    boolean clear() {
        if (mGroups.size() == 0) return false;
        isClearCalled = true;
        mGroups.evictAll();
        isClearCalled = false;
        return true;
    }

    /**
     * Return the network id of the group owner profile with the specified p2p device
     * address.
     * If more than one persistent group of the same address is present in the list,
     * return the first one.
     *
     * @param deviceAddress p2p device address.
     * @return the network id. if not found, return -1.
     */
    int getNetworkId(String deviceAddress) {
        if (deviceAddress == null) return -1;

        final Collection<WifiP2pGroup> groups = mGroups.snapshot().values();
        for (WifiP2pGroup grp: groups) {
            if (deviceAddress.equalsIgnoreCase(grp.getOwner().deviceAddress)) {
                // update cache ordered.
                mGroups.get(grp.getNetworkId());
                return grp.getNetworkId();
            }
        }
        return -1;
    }

    /**
     * Return the network id of the group with the specified p2p device address
     * and the ssid.
     *
     * @param deviceAddress p2p device address.
     * @param ssid ssid.
     * @return the network id. if not found, return -1.
     */
    int getNetworkId(String deviceAddress, String ssid) {
        if (deviceAddress == null || ssid == null) {
            return -1;
        }

        final Collection<WifiP2pGroup> groups = mGroups.snapshot().values();
        for (WifiP2pGroup grp: groups) {
            if (deviceAddress.equalsIgnoreCase(grp.getOwner().deviceAddress) &&
                    ssid.equals(grp.getNetworkName())) {
                // update cache ordered.
                mGroups.get(grp.getNetworkId());
                return grp.getNetworkId();
            }
        }

        return -1;
    }

    /**
     * Return the group owner address of the group with the specified network id
     *
     * @param netId network id.
     * @return the address. if not found, return null.
     */
    String getOwnerAddr(int netId) {
        WifiP2pGroup grp = mGroups.get(netId);
        if (grp != null) {
            return grp.getOwner().deviceAddress;
        }
        return null;
    }

    /**
     * Return true if this group list contains the specified network id.
     * This function does NOT update LRU information.
     * It means the internal queue is NOT reordered.
     *
     * @param netId network id.
     * @return true if the specified network id is present in this group list.
     */
    boolean contains(int netId) {
        final Collection<WifiP2pGroup> groups = mGroups.snapshot().values();
        for (WifiP2pGroup grp: groups) {
            if (netId == grp.getNetworkId()) {
                return true;
            }
        }
        return false;
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();

        final Collection<WifiP2pGroup> groups = mGroups.snapshot().values();
        for (WifiP2pGroup grp: groups) {
            sbuf.append(grp).append("\n");
        }
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        final Collection<WifiP2pGroup> groups = mGroups.snapshot().values();
        dest.writeInt(groups.size());
        for(WifiP2pGroup group : groups) {
            dest.writeParcelable(group, flags);
        }
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pGroupList> CREATOR =
        new Creator<WifiP2pGroupList>() {
            public WifiP2pGroupList createFromParcel(Parcel in) {
                WifiP2pGroupList grpList = new WifiP2pGroupList();

                int deviceCount = in.readInt();
                for (int i = 0; i < deviceCount; i++) {
                    grpList.add((WifiP2pGroup)in.readParcelable(null));
                }
                return grpList;
            }

            public WifiP2pGroupList[] newArray(int size) {
                return new WifiP2pGroupList[size];
            }
        };
}
