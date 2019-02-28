/* -*- Mode: Java; tab-width: 4 -*-
 *
 * Copyright (c) 2004 Apple Computer, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.

 To do:
 - implement remove()
 - fix set() to replace existing values
 */

package android.net.nsd;

import android.os.Parcelable;
import android.os.Parcel;

import java.util.Arrays;

/**
 * This class handles TXT record data for DNS based service discovery as specified at
 * http://tools.ietf.org/html/draft-cheshire-dnsext-dns-sd-11
 *
 * DNS-SD specifies that a TXT record corresponding to an SRV record consist of
 * a packed array of bytes, each preceded by a length byte. Each string
 * is an attribute-value pair.
 *
 * The DnsSdTxtRecord object stores the entire TXT data as a single byte array, traversing it
 * as need be to implement its various methods.
 * @hide
 *
 */
public class DnsSdTxtRecord implements Parcelable {
    private static final byte mSeperator = '=';

    private byte[] mData;

    /** Constructs a new, empty TXT record. */
    public DnsSdTxtRecord()  {
        mData = new byte[0];
    }

    /** Constructs a new TXT record from a byte array in the standard format. */
    public DnsSdTxtRecord(byte[] data) {
        mData = (byte[]) data.clone();
    }

    /** Copy constructor */
    public DnsSdTxtRecord(DnsSdTxtRecord src) {
        if (src != null && src.mData != null) {
            mData = (byte[]) src.mData.clone();
        }
    }

    /**
     * Set a key/value pair. Setting an existing key will replace its value.
     * @param key Must be ascii with no '='
     * @param value matching value to key
     */
    public void set(String key, String value) {
        byte[] keyBytes;
        byte[] valBytes;
        int valLen;

        if (value != null) {
            valBytes = value.getBytes();
            valLen = valBytes.length;
        } else {
            valBytes = null;
            valLen = 0;
        }

        try {
            keyBytes = key.getBytes("US-ASCII");
        }
        catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalArgumentException("key should be US-ASCII");
        }

        for (int i = 0; i < keyBytes.length; i++) {
            if (keyBytes[i] == '=') {
                throw new IllegalArgumentException("= is not a valid character in key");
            }
        }

        if (keyBytes.length + valLen >= 255) {
            throw new IllegalArgumentException("Key and Value length cannot exceed 255 bytes");
        }

        int currentLoc = remove(key);
        if (currentLoc == -1)
            currentLoc = keyCount();

        insert(keyBytes, valBytes, currentLoc);
    }

    /**
     * Get a value for a key
     *
     * @param key
     * @return The value associated with the key
     */
    public String get(String key) {
        byte[] val = this.getValue(key);
        return val != null ? new String(val) : null;
    }

    /** Remove a key/value pair. If found, returns the index or -1 if not found */
    public int remove(String key) {
        int avStart = 0;

        for (int i=0; avStart < mData.length; i++) {
            int avLen = mData[avStart];
            if (key.length() <= avLen &&
                    (key.length() == avLen || mData[avStart + key.length() + 1] == mSeperator)) {
                String s = new String(mData, avStart + 1, key.length());
                if (0 == key.compareToIgnoreCase(s)) {
                    byte[] oldBytes = mData;
                    mData = new byte[oldBytes.length - avLen - 1];
                    System.arraycopy(oldBytes, 0, mData, 0, avStart);
                    System.arraycopy(oldBytes, avStart + avLen + 1, mData, avStart,
                            oldBytes.length - avStart - avLen - 1);
                    return i;
                }
            }
            avStart += (0xFF & (avLen + 1));
        }
        return -1;
    }

    /** Return the count of keys */
    public int keyCount() {
        int count = 0, nextKey;
        for (nextKey = 0; nextKey < mData.length; count++) {
            nextKey += (0xFF & (mData[nextKey] + 1));
        }
        return count;
    }

    /** Return true if key is present, false if not. */
    public boolean contains(String key) {
        String s = null;
        for (int i = 0; null != (s = this.getKey(i)); i++) {
            if (0 == key.compareToIgnoreCase(s)) return true;
        }
        return false;
    }

    /* Gets the size in bytes */
    public int size() {
        return mData.length;
    }

    /* Gets the raw data in bytes */
    public byte[] getRawData() {
        return (byte[]) mData.clone();
    }

    private void insert(byte[] keyBytes, byte[] value, int index) {
        byte[] oldBytes = mData;
        int valLen = (value != null) ? value.length : 0;
        int insertion = 0;
        int newLen, avLen;

        for (int i = 0; i < index && insertion < mData.length; i++) {
            insertion += (0xFF & (mData[insertion] + 1));
        }

        avLen = keyBytes.length + valLen + (value != null ? 1 : 0);
        newLen = avLen + oldBytes.length + 1;

        mData = new byte[newLen];
        System.arraycopy(oldBytes, 0, mData, 0, insertion);
        int secondHalfLen = oldBytes.length - insertion;
        System.arraycopy(oldBytes, insertion, mData, newLen - secondHalfLen, secondHalfLen);
        mData[insertion] = (byte) avLen;
        System.arraycopy(keyBytes, 0, mData, insertion + 1, keyBytes.length);
        if (value != null) {
            mData[insertion + 1 + keyBytes.length] = mSeperator;
            System.arraycopy(value, 0, mData, insertion + keyBytes.length + 2, valLen);
        }
    }

    /** Return a key in the TXT record by zero-based index. Returns null if index exceeds the total number of keys. */
    private String getKey(int index) {
        int avStart = 0;

        for (int i=0; i < index && avStart < mData.length; i++) {
            avStart += mData[avStart] + 1;
        }

        if (avStart < mData.length) {
            int avLen = mData[avStart];
            int aLen = 0;

            for (aLen=0; aLen < avLen; aLen++) {
                if (mData[avStart + aLen + 1] == mSeperator) break;
            }
            return new String(mData, avStart + 1, aLen);
        }
        return null;
    }

    /**
     * Look up a key in the TXT record by zero-based index and return its value.
     * Returns null if index exceeds the total number of keys.
     * Returns null if the key is present with no value.
     */
    private byte[] getValue(int index) {
        int avStart = 0;
        byte[] value = null;

        for (int i=0; i < index && avStart < mData.length; i++) {
            avStart += mData[avStart] + 1;
        }

        if (avStart < mData.length) {
            int avLen = mData[avStart];
            int aLen = 0;

            for (aLen=0; aLen < avLen; aLen++) {
                if (mData[avStart + aLen + 1] == mSeperator) {
                    value = new byte[avLen - aLen - 1];
                    System.arraycopy(mData, avStart + aLen + 2, value, 0, avLen - aLen - 1);
                    break;
                }
            }
        }
        return value;
    }

    private String getValueAsString(int index) {
        byte[] value = this.getValue(index);
        return value != null ? new String(value) : null;
    }

    private byte[] getValue(String forKey) {
        String s = null;
        int i;

        for (i = 0; null != (s = this.getKey(i)); i++) {
            if (0 == forKey.compareToIgnoreCase(s)) {
                return this.getValue(i);
            }
        }

        return null;
    }

    /**
     * Return a string representation.
     * Example : {key1=value1},{key2=value2}..
     *
     * For a key say like "key3" with null value
     * {key1=value1},{key2=value2}{key3}
     */
    public String toString() {
        String a, result = null;

        for (int i = 0; null != (a = this.getKey(i)); i++) {
            String av =  "{" + a;
            String val = this.getValueAsString(i);
            if (val != null)
                av += "=" + val + "}";
            else
                av += "}";
            if (result == null)
                result = av;
            else
                result = result + ", " + av;
        }
        return result != null ? result : "";
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof DnsSdTxtRecord)) {
            return false;
        }

        DnsSdTxtRecord record = (DnsSdTxtRecord)o;
        return  Arrays.equals(record.mData, mData);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mData);
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(mData);
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<DnsSdTxtRecord> CREATOR =
        new Creator<DnsSdTxtRecord>() {
            public DnsSdTxtRecord createFromParcel(Parcel in) {
                DnsSdTxtRecord info = new DnsSdTxtRecord();
                in.readByteArray(info.mData);
                return info;
            }

            public DnsSdTxtRecord[] newArray(int size) {
                return new DnsSdTxtRecord[size];
            }
        };
}
