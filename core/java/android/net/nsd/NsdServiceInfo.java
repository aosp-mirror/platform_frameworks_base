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

package android.net.nsd;

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;
import android.util.ArrayMap;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;


/**
 * A class representing service information for network service discovery
 * {@see NsdManager}
 */
public final class NsdServiceInfo implements Parcelable {

    private static final String TAG = "NsdServiceInfo";

    private String mServiceName;

    private String mServiceType;

    private final ArrayMap<String, byte[]> mTxtRecord = new ArrayMap<String, byte[]>();

    private InetAddress mHost;

    private int mPort;

    public NsdServiceInfo() {
    }

    /** @hide */
    public NsdServiceInfo(String sn, String rt) {
        mServiceName = sn;
        mServiceType = rt;
    }

    /** Get the service name */
    public String getServiceName() {
        return mServiceName;
    }

    /** Set the service name */
    public void setServiceName(String s) {
        mServiceName = s;
    }

    /** Get the service type */
    public String getServiceType() {
        return mServiceType;
    }

    /** Set the service type */
    public void setServiceType(String s) {
        mServiceType = s;
    }

    /** Get the host address. The host address is valid for a resolved service. */
    public InetAddress getHost() {
        return mHost;
    }

    /** Set the host address */
    public void setHost(InetAddress s) {
        mHost = s;
    }

    /** Get port number. The port number is valid for a resolved service. */
    public int getPort() {
        return mPort;
    }

    /** Set port number */
    public void setPort(int p) {
        mPort = p;
    }

    /** @hide */
    public void setAttribute(String key, byte[] value) {
        // Key must be printable US-ASCII, excluding =.
        for (int i = 0; i < key.length(); ++i) {
            char character = key.charAt(i);
            if (character < 0x20 || character > 0x7E) {
                throw new IllegalArgumentException("Key strings must be printable US-ASCII");
            } else if (character == 0x3D) {
                throw new IllegalArgumentException("Key strings must not include '='");
            }
        }

        // Key length + value length must be < 255.
        if (key.length() + (value == null ? 0 : value.length) >= 255) {
            throw new IllegalArgumentException("Key length + value length must be < 255 bytes");
        }

        // Warn if key is > 9 characters, as recommended by RFC 6763 section 6.4.
        if (key.length() > 9) {
            Log.w(TAG, "Key lengths > 9 are discouraged: " + key);
        }

        // Check against total TXT record size limits.
        // Arbitrary 400 / 1300 byte limits taken from RFC 6763 section 6.2.
        int txtRecordSize = getTxtRecordSize();
        int futureSize = txtRecordSize + key.length() + (value == null ? 0 : value.length) + 2;
        if (futureSize > 1300) {
            throw new IllegalArgumentException("Total length of attributes must be < 1300 bytes");
        } else if (futureSize > 400) {
            Log.w(TAG, "Total length of all attributes exceeds 400 bytes; truncation may occur");
        }

        mTxtRecord.put(key, value);
    }

    /**
     * Add a service attribute as a key/value pair.
     *
     * <p> Service attributes are included as DNS-SD TXT record pairs.
     *
     * <p> The key must be US-ASCII printable characters, excluding the '=' character.  Values may
     * be UTF-8 strings or null.  The total length of key + value must be less than 255 bytes.
     *
     * <p> Keys should be short, ideally no more than 9 characters, and unique per instance of
     * {@link NsdServiceInfo}.  Calling {@link #setAttribute} twice with the same key will overwrite
     * first value.
     */
    public void setAttribute(String key, String value) {
        try {
            setAttribute(key, value == null ? (byte []) null : value.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException("Value must be UTF-8");
        }
    }

    /** Remove an attribute by key */
    public void removeAttribute(String key) {
        mTxtRecord.remove(key);
    }

    /**
     * Retrive attributes as a map of String keys to byte[] values.
     *
     * <p> The returned map is unmodifiable; changes must be made through {@link #setAttribute} and
     * {@link #removeAttribute}.
     */
    public Map<String, byte[]> getAttributes() {
        return Collections.unmodifiableMap(mTxtRecord);
    }

    private int getTxtRecordSize() {
        int txtRecordSize = 0;
        for (Map.Entry<String, byte[]> entry : mTxtRecord.entrySet()) {
            txtRecordSize += 2;  // One for the length byte, one for the = between key and value.
            txtRecordSize += entry.getKey().length();
            byte[] value = entry.getValue();
            txtRecordSize += value == null ? 0 : value.length;
        }
        return txtRecordSize;
    }

    /** @hide */
    public byte[] getTxtRecord() {
        int txtRecordSize = getTxtRecordSize();
        if (txtRecordSize == 0) {
            return null;
        }

        byte[] txtRecord = new byte[txtRecordSize];
        int ptr = 0;
        for (Map.Entry<String, byte[]> entry : mTxtRecord.entrySet()) {
            String key = entry.getKey();
            byte[] value = entry.getValue();

            // One byte to record the length of this key/value pair.
            txtRecord[ptr++] = (byte) (key.length() + (value == null ? 0 : value.length) + 1);

            // The key, in US-ASCII.
            // Note: use the StandardCharsets const here because it doesn't raise exceptions and we
            // already know the key is ASCII at this point.
            System.arraycopy(key.getBytes(StandardCharsets.US_ASCII), 0, txtRecord, ptr,
                    key.length());
            ptr += key.length();

            // US-ASCII '=' character.
            txtRecord[ptr++] = (byte)'=';

            // The value, as any raw bytes.
            if (value != null) {
                System.arraycopy(value, 0, txtRecord, ptr, value.length);
                ptr += value.length;
            }
        }
        return txtRecord;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("name: ").append(mServiceName)
                .append(", type: ").append(mServiceType)
                .append(", host: ").append(mHost)
                .append(", port: ").append(mPort);

        byte[] txtRecord = getTxtRecord();
        if (txtRecord != null) {
            sb.append(", txtRecord: ").append(new String(txtRecord, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeString(mServiceType);
        if (mHost != null) {
            dest.writeInt(1);
            dest.writeByteArray(mHost.getAddress());
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mPort);

        // TXT record key/value pairs.
        dest.writeInt(mTxtRecord.size());
        for (String key : mTxtRecord.keySet()) {
            byte[] value = mTxtRecord.get(key);
            if (value != null) {
                dest.writeInt(1);
                dest.writeInt(value.length);
                dest.writeByteArray(value);
            } else {
                dest.writeInt(0);
            }
            dest.writeString(key);
        }
    }

    /** Implement the Parcelable interface */
    public static final Creator<NsdServiceInfo> CREATOR =
        new Creator<NsdServiceInfo>() {
            public NsdServiceInfo createFromParcel(Parcel in) {
                NsdServiceInfo info = new NsdServiceInfo();
                info.mServiceName = in.readString();
                info.mServiceType = in.readString();

                if (in.readInt() == 1) {
                    try {
                        info.mHost = InetAddress.getByAddress(in.createByteArray());
                    } catch (java.net.UnknownHostException e) {}
                }

                info.mPort = in.readInt();

                // TXT record key/value pairs.
                int recordCount = in.readInt();
                for (int i = 0; i < recordCount; ++i) {
                    byte[] valueArray = null;
                    if (in.readInt() == 1) {
                        int valueLength = in.readInt();
                        valueArray = new byte[valueLength];
                        in.readByteArray(valueArray);
                    }
                    info.mTxtRecord.put(in.readString(), valueArray);
                }
                return info;
            }

            public NsdServiceInfo[] newArray(int size) {
                return new NsdServiceInfo[size];
            }
        };
}
