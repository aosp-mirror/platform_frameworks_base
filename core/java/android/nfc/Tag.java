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

package android.nfc;

import android.nfc.technology.IsoDep;
import android.nfc.technology.MifareClassic;
import android.nfc.technology.NfcV;
import android.nfc.technology.Ndef;
import android.nfc.technology.NfcA;
import android.nfc.technology.NfcB;
import android.nfc.technology.NfcF;
import android.nfc.technology.TagTechnology;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.Arrays;

/**
 * Represents a (generic) discovered tag.
 * <p>
 * A tag is a passive NFC element, such as NFC Forum Tag's, Mifare class Tags,
 * Sony Felica Tags.
 * <p>
 * Tag's have a type and usually have a UID.
 * <p>
 * {@link Tag} objects are passed to applications via the {@link NfcAdapter#EXTRA_TAG} extra
 * in {@link NfcAdapter#ACTION_TAG_DISCOVERED} intents. A {@link Tag} object is immutable
 * and represents the state of the tag at the time of discovery. It can be
 * directly queried for its UID and Type, or used to create a {@link TagTechnology}
 * (with {@link Tag#getTechnology(int)}).
 * <p>
 * A {@link Tag} can  be used to create a {@link TagTechnology} only while the tag is in
 * range. If it is removed and then returned to range, then the most recent
 * {@link Tag} object (in {@link NfcAdapter#ACTION_TAG_DISCOVERED}) should be used to create a
 * {@link TagTechnology}.
 * <p>This is an immutable data class. All properties are set at Tag discovery
 * time and calls on this class will retrieve those read-only properties, and
 * not cause any further RF activity or block. Note however that arrays passed to and
 * returned by this class are *not* cloned, so be careful not to modify them.
 * @hide
 */
public class Tag implements Parcelable {
    /*package*/ final byte[] mId;
    /*package*/ final int[] mTechList;
    /*package*/ final Bundle[] mTechExtras;
    /*package*/ final int mServiceHandle;  // for use by NFC service, 0 indicates a mock

    /**
     * Hidden constructor to be used by NFC service and internal classes.
     * @hide
     */
    public Tag(byte[] id, int[] techList, Bundle[] techListExtras, int serviceHandle) {
        if (techList == null) {
            throw new IllegalArgumentException("rawTargets cannot be null");
        }
        mId = id;
        mTechList = Arrays.copyOf(techList, techList.length);
        Arrays.sort(mTechList);
        // Ensure mTechExtras is as long as mTechList
        mTechExtras = Arrays.copyOf(techListExtras, techList.length);
        mServiceHandle = serviceHandle;
    }

    /**
     * Construct a mock Tag.
     * <p>This is an application constructed tag, so NfcAdapter methods on this
     * Tag such as {@link #getTechnology} may fail with
     * {@link IllegalArgumentException} since it does not represent a physical Tag.
     * <p>This constructor might be useful for mock testing.
     * @param id The tag identifier, can be null
     * @param techList must not be null
     * @return freshly constructed tag
     */
    public static Tag createMockTag(byte[] id, int[] techList, Bundle[] techListExtras) {
        // set serviceHandle to 0 to indicate mock tag
        return new Tag(id, techList, techListExtras, 0);
    }

    /**
     * For use by NfcService only.
     * @hide
     */
    public int getServiceHandle() {
        return mServiceHandle;
    }

    /**
     * Get the Tag Identifier (if it has one).
     * <p>Tag ID is usually a serial number for the tag.
     *
     * @return ID, or null if it does not exist
     */
    public byte[] getId() {
        return mId;
    }

    /**
     * Returns technologies present in the tag that this implementation understands,
     * or a zero length array if there are no supported technologies on this tag.
     */
    public int[] getTechnologyList() { 
        return Arrays.copyOf(mTechList, mTechList.length);
    }

    /**
     * Returns the technology, or null if not present
     */
    public TagTechnology getTechnology(int tech) {
        int pos = Arrays.binarySearch(mTechList, tech);
        if (pos < 0) {
            return null;
        }

        Bundle extras = mTechExtras[pos];
        NfcAdapter adapter = null;
        try {
            switch (tech) {
                case TagTechnology.NFC_A: {
                    return new NfcA(adapter, this, extras);
                }
                case TagTechnology.NFC_B: {
                    return new NfcB(adapter, this, extras);
                }
                case TagTechnology.ISO_DEP: {
                    return new IsoDep(adapter, this, extras);
                }
                case TagTechnology.NFC_V: {
                    return new NfcV(adapter, this, extras);
                }
                case TagTechnology.TYPE_1:
                case TagTechnology.TYPE_2:
                case TagTechnology.TYPE_3:
                case TagTechnology.TYPE_4: {
                    return new Ndef(adapter, this, tech, extras);
                }
                case TagTechnology.NFC_F: {
                    return new NfcF(adapter, this, extras);
                }
                case TagTechnology.MIFARE_CLASSIC: {
                    return new MifareClassic(adapter, this, extras);
                }

                default: {
                    throw new UnsupportedOperationException("Tech " + tech + " not supported");
                }
            }
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TAG ")
            .append("uid = ")
            .append(mId)
            .append(" Tech [");
        for (int i : mTechList) {
            sb.append(i)
            .append(", ");
        }
        return sb.toString();
    }

    /*package*/ static byte[] readBytesWithNull(Parcel in) {
        int len = in.readInt();
        byte[] result = null;
        if (len >= 0) {
            result = new byte[len];
            in.readByteArray(result);
        }
        return result;
    }

    /*package*/ static void writeBytesWithNull(Parcel out, byte[] b) {
        if (b == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(b.length);
        out.writeByteArray(b);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeBytesWithNull(dest, mId);
        dest.writeInt(mTechList.length);
        dest.writeIntArray(mTechList);
        dest.writeTypedArray(mTechExtras, 0);
        dest.writeInt(mServiceHandle);
    }

    public static final Parcelable.Creator<Tag> CREATOR =
            new Parcelable.Creator<Tag>() {
        @Override
        public Tag createFromParcel(Parcel in) {
            // Tag fields
            byte[] id = Tag.readBytesWithNull(in);
            int[] techList = new int[in.readInt()];
            in.readIntArray(techList);
            Bundle[] techExtras = in.createTypedArray(Bundle.CREATOR);
            int serviceHandle = in.readInt();

            return new Tag(id, techList, techExtras, serviceHandle);
        }

        @Override
        public Tag[] newArray(int size) {
            return new Tag[size];
        }
    };
}