/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net;

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A class representing the capabilities of a link
 *
 * @hide
 */
public class LinkCapabilities implements Parcelable {
    private static final String TAG = "LinkCapabilities";
    private static final boolean DBG = false;

    /** The Map of Keys to Values */
    private HashMap<Integer, String> mCapabilities;


    /**
     * The set of keys defined for a links capabilities.
     *
     * Keys starting with RW are read + write, i.e. the application
     * can request for a certain requirement corresponding to that key.
     * Keys starting with RO are read only, i.e. the the application
     * can read the value of that key from the socket but cannot request
     * a corresponding requirement.
     *
     * TODO: Provide a documentation technique for concisely and precisely
     * define the syntax for each value string associated with a key.
     */
    public static final class Key {
        /** No constructor */
        private Key() {}

        /**
         * An integer representing the network type.
         * @see ConnectivityManager
         */
        public final static int RO_NETWORK_TYPE = 1;

        /**
         * Desired minimum forward link (download) bandwidth for the
         * in kilobits per second (kbps). Values should be strings such
         * "50", "100", "1500", etc.
         */
        public final static int RW_DESIRED_FWD_BW = 2;

        /**
         * Required minimum forward link (download) bandwidth, in
         * per second (kbps), below which the socket cannot function.
         * Values should be strings such as "50", "100", "1500", etc.
         */
        public final static int RW_REQUIRED_FWD_BW = 3;

        /**
         * Available forward link (download) bandwidth for the socket.
         * This value is in kilobits per second (kbps).
         * Values will be strings such as "50", "100", "1500", etc.
         */
        public final static int RO_AVAILABLE_FWD_BW = 4;

        /**
         * Desired minimum reverse link (upload) bandwidth for the socket
         * in kilobits per second (kbps).
         * Values should be strings such as "50", "100", "1500", etc.
         * <p>
         * This key is set via the needs map.
         */
        public final static int RW_DESIRED_REV_BW = 5;

        /**
         * Required minimum reverse link (upload) bandwidth, in kilobits
         * per second (kbps), below which the socket cannot function.
         * If a rate is not specified, the default rate of kbps will be
         * Values should be strings such as "50", "100", "1500", etc.
         */
        public final static int RW_REQUIRED_REV_BW = 6;

        /**
         * Available reverse link (upload) bandwidth for the socket.
         * This value is in kilobits per second (kbps).
         * Values will be strings such as "50", "100", "1500", etc.
         */
        public final static int RO_AVAILABLE_REV_BW = 7;

        /**
         * Maximum latency for the socket, in milliseconds, above which
         * socket cannot function.
         * Values should be strings such as "50", "300", "500", etc.
         */
        public final static int RW_MAX_ALLOWED_LATENCY = 8;

        /**
         * Interface that the socket is bound to. This can be a virtual
         * interface (e.g. VPN or Mobile IP) or a physical interface
         * (e.g. wlan0 or rmnet0).
         * Values will be strings such as "wlan0", "rmnet0"
         */
        public final static int RO_BOUND_INTERFACE = 9;

        /**
         * Physical interface that the socket is routed on.
         * This can be different from BOUND_INTERFACE in cases such as
         * VPN or Mobile IP. The physical interface may change over time
         * if seamless mobility is supported.
         * Values will be strings such as "wlan0", "rmnet0"
         */
        public final static int RO_PHYSICAL_INTERFACE = 10;
    }

    /**
     * Role informs the LinkSocket about the data usage patterns of your
     * application.
     * <P>
     * {@code Role.DEFAULT} is the default role, and is used whenever
     * a role isn't set.
     */
    public static final class Role {
        /** No constructor */
        private Role() {}

        // examples only, discuss which roles should be defined, and then
        // code these to match

        /** Default Role */
        public static final String DEFAULT = "default";
        /** Bulk down load */
        public static final String BULK_DOWNLOAD = "bulk.download";
        /** Bulk upload */
        public static final String BULK_UPLOAD = "bulk.upload";

        /** VoIP Application at 24kbps */
        public static final String VOIP_24KBPS = "voip.24k";
        /** VoIP Application at 32kbps */
        public static final String VOIP_32KBPS = "voip.32k";

        /** Video Streaming at 480p */
        public static final String VIDEO_STREAMING_480P = "video.streaming.480p";
        /** Video Streaming at 720p */
        public static final String VIDEO_STREAMING_720I = "video.streaming.720i";

        /** Video Chat Application at 360p */
        public static final String VIDEO_CHAT_360P = "video.chat.360p";
        /** Video Chat Application at 480p */
        public static final String VIDEO_CHAT_480P = "video.chat.480i";
    }

    /**
     * Constructor
     */
    public LinkCapabilities() {
        mCapabilities = new HashMap<Integer, String>();
    }

    /**
     * Copy constructor.
     *
     * @param source
     */
    public LinkCapabilities(LinkCapabilities source) {
        if (source != null) {
            mCapabilities = new HashMap<Integer, String>(source.mCapabilities);
        } else {
            mCapabilities = new HashMap<Integer, String>();
        }
    }

    /**
     * Create the {@code LinkCapabilities} with values depending on role type.
     * @param applicationRole a {@code LinkSocket.Role}
     * @return the {@code LinkCapabilities} associated with the applicationRole, empty if none
     */
    public static LinkCapabilities createNeedsMap(String applicationRole) {
        if (DBG) log("createNeededCapabilities(applicationRole) EX");
        return new LinkCapabilities();
    }

    /**
     * Remove all capabilities
     */
    public void clear() {
        mCapabilities.clear();
    }

    /**
     * Returns whether this map is empty.
     */
    public boolean isEmpty() {
        return mCapabilities.isEmpty();
    }

    /**
     * Returns the number of elements in this map.
     *
     * @return the number of elements in this map.
     */
    public int size() {
        return mCapabilities.size();
    }

    /**
     * Given the key return the capability string
     *
     * @param key
     * @return the capability string
     */
    public String get(int key) {
        return mCapabilities.get(key);
    }

    /**
     * Store the key/value capability pair
     *
     * @param key
     * @param value
     */
    public void put(int key, String value) {
        mCapabilities.put(key, value);
    }

    /**
     * Returns whether this map contains the specified key.
     *
     * @param key to search for.
     * @return {@code true} if this map contains the specified key,
     *         {@code false} otherwise.
     */
    public boolean containsKey(int key) {
        return mCapabilities.containsKey(key);
    }

    /**
     * Returns whether this map contains the specified value.
     *
     * @param value to search for.
     * @return {@code true} if this map contains the specified value,
     *         {@code false} otherwise.
     */
    public boolean containsValue(String value) {
        return mCapabilities.containsValue(value);
    }

    /**
     * Returns a set containing all of the mappings in this map. Each mapping is
     * an instance of {@link Map.Entry}. As the set is backed by this map,
     * changes in one will be reflected in the other.
     *
     * @return a set of the mappings.
     */
    public Set<Entry<Integer, String>> entrySet() {
        return mCapabilities.entrySet();
    }

    /**
     * @return the set of the keys.
     */
    public Set<Integer> keySet() {
        return mCapabilities.keySet();
    }

    /**
     * @return the set of values
     */
    public Collection<String> values() {
        return mCapabilities.values();
    }

    /**
     * Implement the Parcelable interface
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Convert to string for debugging
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean firstTime = true;
        for (Entry<Integer, String> entry : mCapabilities.entrySet()) {
            if (firstTime) {
                firstTime = false;
            } else {
                sb.append(",");
            }
            sb.append(entry.getKey());
            sb.append(":\"");
            sb.append(entry.getValue());
            sb.append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCapabilities.size());
        for (Entry<Integer, String> entry : mCapabilities.entrySet()) {
            dest.writeInt(entry.getKey().intValue());
            dest.writeString(entry.getValue());
        }
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<LinkCapabilities> CREATOR =
        new Creator<LinkCapabilities>() {
            public LinkCapabilities createFromParcel(Parcel in) {
                LinkCapabilities capabilities = new LinkCapabilities();
                int size = in.readInt();
                while (size-- != 0) {
                    int key = in.readInt();
                    String value = in.readString();
                    capabilities.mCapabilities.put(key, value);
                }
                return capabilities;
            }

            public LinkCapabilities[] newArray(int size) {
                return new LinkCapabilities[size];
            }
        };

    /**
     * Debug logging
     */
    protected static void log(String s) {
        Log.d(TAG, s);
    }
}
