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

import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Represents a (generic) discovered tag.
 * <p>
 * A tag is a passive NFC element, such as NFC Forum Tag's, MIFARE class Tags,
 * Sony FeliCa Tags, etc.
 * <p>
 * Tag's have a type and usually have a UID.
 * <p>
 * {@link Tag} objects are passed to applications via the {@link NfcAdapter#EXTRA_TAG} extra
 * in {@link NfcAdapter#ACTION_TAG_DISCOVERED} intents. A {@link Tag} object is immutable
 * and represents the state of the tag at the time of discovery. It can be
 * directly queried for its UID and Type, or used to create a {@link TagTechnology} using the
 * static <code>get()</code> methods on the varios tech classes.
 * <p>
 * A {@link Tag} can  be used to create a {@link TagTechnology} only while the tag is in
 * range. If it is removed and then returned to range, then the most recent
 * {@link Tag} object (in {@link NfcAdapter#ACTION_TAG_DISCOVERED}) should be used to create a
 * {@link TagTechnology}.
 * <p>This is an immutable data class. All properties are set at Tag discovery
 * time and calls on this class will retrieve those read-only properties, and
 * not cause any further RF activity or block. Note however that arrays passed to and
 * returned by this class are *not* cloned, so be careful not to modify them.
 */
public final class Tag implements Parcelable {
    /*package*/ final byte[] mId;
    /*package*/ final int[] mTechList;
    /*package*/ final String[] mTechStringList;
    /*package*/ final Bundle[] mTechExtras;
    /*package*/ final int mServiceHandle;  // for use by NFC service, 0 indicates a mock
    /*package*/ final INfcTag mTagService;

    /*package*/ int mConnectedTechnology;

    /**
     * Hidden constructor to be used by NFC service and internal classes.
     * @hide
     */
    public Tag(byte[] id, int[] techList, Bundle[] techListExtras, int serviceHandle,
            INfcTag tagService) {
        if (techList == null) {
            throw new IllegalArgumentException("rawTargets cannot be null");
        }
        mId = id;
        mTechList = Arrays.copyOf(techList, techList.length);
        mTechStringList = generateTechStringList(techList);
        // Ensure mTechExtras is as long as mTechList
        mTechExtras = Arrays.copyOf(techListExtras, techList.length);
        mServiceHandle = serviceHandle;
        mTagService = tagService;

        mConnectedTechnology = -1;
    }

    /**
     * Construct a mock Tag.
     * <p>This is an application constructed tag, so NfcAdapter methods on this Tag may fail
     * with {@link IllegalArgumentException} since it does not represent a physical Tag.
     * <p>This constructor might be useful for mock testing.
     * @param id The tag identifier, can be null
     * @param techList must not be null
     * @return freshly constructed tag
     * @hide
     */
    public static Tag createMockTag(byte[] id, int[] techList, Bundle[] techListExtras) {
        // set serviceHandle to 0 to indicate mock tag
        return new Tag(id, techList, techListExtras, 0, null);
    }

    private String[] generateTechStringList(int[] techList) {
        final int size = techList.length;
        String[] strings = new String[size];
        for (int i = 0; i < size; i++) {
            switch (techList[i]) {
                case TagTechnology.ISO_DEP:
                    strings[i] = IsoDep.class.getName();
                    break;
                case TagTechnology.MIFARE_CLASSIC:
                    strings[i] = MifareClassic.class.getName();
                    break;
                case TagTechnology.MIFARE_ULTRALIGHT:
                    strings[i] = MifareUltralight.class.getName();
                    break;
                case TagTechnology.NDEF:
                    strings[i] = Ndef.class.getName();
                    break;
                case TagTechnology.NDEF_FORMATABLE:
                    strings[i] = NdefFormatable.class.getName();
                    break;
                case TagTechnology.NFC_A:
                    strings[i] = NfcA.class.getName();
                    break;
                case TagTechnology.NFC_B:
                    strings[i] = NfcB.class.getName();
                    break;
                case TagTechnology.NFC_F:
                    strings[i] = NfcF.class.getName();
                    break;
                case TagTechnology.NFC_V:
                    strings[i] = NfcV.class.getName();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown tech type " + techList[i]);
            }
        }
        return strings;
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
     *
     * The elements of the list are the names of the classes implementing the technology. 
     *
     * The ordering of the returned array is undefined and should not be relied upon.
     */
    public String[] getTechList() {
        return mTechStringList;
    }

    /** @hide */
    public boolean hasTech(int techType) {
        for (int tech : mTechList) {
            if (tech == techType) return true;
        }
        return false;
    }
    
    /** @hide */
    public Bundle getTechExtras(int tech) {
        int pos = -1;
        for (int idx = 0; idx < mTechList.length; idx++) {
          if (mTechList[idx] == tech) {
              pos = idx;
              break;
          }
        }
        if (pos < 0) {
            return null;
        }

        return mTechExtras[pos];
    }

    /** @hide */
    public INfcTag getTagService() {
        return mTagService;
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
        // Null mTagService means this is a mock tag
        int isMock = (mTagService == null)?1:0;

        writeBytesWithNull(dest, mId);
        dest.writeInt(mTechList.length);
        dest.writeIntArray(mTechList);
        dest.writeTypedArray(mTechExtras, 0);
        dest.writeInt(mServiceHandle);
        dest.writeInt(isMock);
        if (isMock == 0) {
            dest.writeStrongBinder(mTagService.asBinder());
        }
    }

    public static final Parcelable.Creator<Tag> CREATOR =
            new Parcelable.Creator<Tag>() {
        @Override
        public Tag createFromParcel(Parcel in) {
            INfcTag tagService;

            // Tag fields
            byte[] id = Tag.readBytesWithNull(in);
            int[] techList = new int[in.readInt()];
            in.readIntArray(techList);
            Bundle[] techExtras = in.createTypedArray(Bundle.CREATOR);
            int serviceHandle = in.readInt();
            int isMock = in.readInt();
            if (isMock == 0) {
                tagService = INfcTag.Stub.asInterface(in.readStrongBinder());
            }
            else {
                tagService = null;
            }

            return new Tag(id, techList, techExtras, serviceHandle, tagService);
        }

        @Override
        public Tag[] newArray(int size) {
            return new Tag[size];
        }
    };

    /**
     * For internal use only.
     *
     * @hide
     */
    public synchronized void setConnectedTechnology(int technology) {
        if (mConnectedTechnology == -1) {
            mConnectedTechnology = technology;
        } else {
            throw new IllegalStateException("Close other technology first!");
        }
    }

    /**
     * For internal use only.
     *
     * @hide
     */
    public int getConnectedTechnology() {
        return mConnectedTechnology;
    }

    /**
     * For internal use only.
     *
     * @hide
     */
    public void setTechnologyDisconnected() {
        mConnectedTechnology = -1;
    }
}
