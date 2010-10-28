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

import android.os.Parcel;
import android.os.Parcelable;

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
 * directly queried for its UID and Type, or used to create a {@link RawTagConnection}
 * (with {@link NfcAdapter#createRawTagConnection createRawTagConnection()}).
 * <p>
 * A {@link Tag} can  be used to create a {@link RawTagConnection} only while the tag is in
 * range. If it is removed and then returned to range, then the most recent
 * {@link Tag} object (in {@link NfcAdapter#ACTION_TAG_DISCOVERED}) should be used to create a
 * {@link RawTagConnection}.
 * <p>This is an immutable data class. All properties are set at Tag discovery
 * time and calls on this class will retrieve those read-only properties, and
 * not cause any further RF activity or block. Note however that arrays passed to and
 * returned by this class are *not* cloned, so be careful not to modify them.
 * @hide
 */
public class Tag implements Parcelable {
    /**
     * ISO 14443-3A technology.
     * <p>
     * Includes Topaz (which is -3A compatible)
     */
    public static final String TARGET_ISO_14443_3A = "iso14443_3a";

    /**
     * ISO 14443-3B technology.
     */
    public static final String TARGET_ISO_14443_3B = "iso14443_3b";

    /**
     * ISO 14443-4 technology.
     */
    public static final String TARGET_ISO_14443_4 = "iso14443_4";

    /**
     * ISO 15693 technology, commonly known as RFID.
     */
    public static final String TARGET_ISO_15693 = "iso15693";

    /**
     * JIS X-6319-4 technology, commonly known as Felica.
     */
    public static final String TARGET_JIS_X_6319_4 = "jis_x_6319_4";

    /**
     * Any other technology.
     */
    public static final String TARGET_OTHER = "other";

    /*package*/ final boolean mIsNdef;
    /*package*/ final byte[] mId;
    /*package*/ final String[] mRawTargets;
    /*package*/ final byte[] mPollBytes;
    /*package*/ final byte[] mActivationBytes;
    /*package*/ final int mServiceHandle;  // for use by NFC service, 0 indicates a mock

    /**
     * Hidden constructor to be used by NFC service and internal classes.
     * @hide
     */
    public Tag(byte[] id, boolean isNdef, String[] rawTargets, byte[] pollBytes,
            byte[] activationBytes, int serviceHandle) {
        if (rawTargets == null) {
            throw new IllegalArgumentException("rawTargets cannot be null");
        }
        mIsNdef = isNdef;
        mId = id;
        mRawTargets = rawTargets;
        mPollBytes = pollBytes;
        mActivationBytes = activationBytes;
        mServiceHandle = serviceHandle;
    }

    /**
     * Construct a mock Tag.
     * <p>This is an application constructed tag, so NfcAdapter methods on this
     * Tag such as {@link NfcAdapter#createRawTagConnection} will fail with
     * {@link IllegalArgumentException} since it does not represent a physical Tag.
     * <p>This constructor might be useful for mock testing.
     * @param id The tag identifier, can be null
     * @param rawTargets must not be null
     * @param pollBytes can be null
     * @param activationBytes can be null
     * @return freshly constructed tag
     */
    public static Tag createMockTag(byte[] id, String[] rawTargets, byte[] pollBytes,
            byte[] activationBytes) {
        // set serviceHandle to 0 to indicate mock tag
        return new Tag(id, false, rawTargets, pollBytes, activationBytes, 0);
    }

    /**
     * For use by NfcService only.
     * @hide
     */
    public int getServiceHandle() {
        return mServiceHandle;
    }

    /**
     * Return the available targets that this NFC adapter can use to create
     * a RawTagConnection.
     *
     * @return raw targets, will not be null
     */
    public String[] getRawTargets() {
        return mRawTargets;
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
     * Get the low-level bytes returned by this Tag at poll-time.
     * <p>These can be used to help with advanced identification of a Tag.
     * <p>The meaning of these bytes depends on the Tag technology.
     * <p>ISO14443-3A: ATQA/SENS_RES
     * <p>ISO14443-3B: Application data (4 bytes) and Protocol Info (3 bytes) from ATQB/SENSB_RES
     * <p>JIS_X_6319_4: PAD0 (2 byte), PAD1 (2 byte), MRTI(2 byte), PAD2 (1 byte), RC (2 byte)
     * <p>ISO15693: response flags (1 byte), DSFID (1 byte)
     * from SENSF_RES
     *
     * @return poll bytes, or null if they do not exist for this Tag technology
     * @hide
     */
    public byte[] getPollBytes() {
        return mPollBytes;
    }

    /**
     * Get the low-level bytes returned by this Tag at activation-time.
     * <p>These can be used to help with advanced identification of a Tag.
     * <p>The meaning of these bytes depends on the Tag technology.
     * <p>ISO14443-3A: SAK/SEL_RES
     * <p>ISO14443-3B: null
     * <p>ISO14443-3A & ISO14443-4: SAK/SEL_RES, historical bytes from ATS  <TODO: confirm>
     * <p>ISO14443-3B & ISO14443-4: ATTRIB response
     * <p>JIS_X_6319_4: null
     * <p>ISO15693: response flags (1 byte), DSFID (1 byte): null
     * @return activation bytes, or null if they do not exist for this Tag technology
     * @hide
     */
    public byte[] getActivationBytes() {
        return mActivationBytes;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TAG ")
            .append("uid = ")
            .append(mId)
            .append(" poll ")
            .append(mPollBytes)
            .append(" activation ")
            .append(mActivationBytes)
            .append(" Raw [");
        for (String s : mRawTargets) {
            sb.append(s)
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
        dest.writeInt(mIsNdef ? 1 : 0);
        writeBytesWithNull(dest, mId);
        dest.writeInt(mRawTargets.length);
        dest.writeStringArray(mRawTargets);
        writeBytesWithNull(dest, mPollBytes);
        writeBytesWithNull(dest, mActivationBytes);
        dest.writeInt(mServiceHandle);
    }

    public static final Parcelable.Creator<Tag> CREATOR =
            new Parcelable.Creator<Tag>() {
        public Tag createFromParcel(Parcel in) {
            boolean isNdef = (in.readInt() == 1);
            if (isNdef) {
                throw new IllegalArgumentException("Creating Tag from NdefTag parcel");
            }
            // Tag fields
            byte[] id = Tag.readBytesWithNull(in);
            String[] rawTargets = new String[in.readInt()];
            in.readStringArray(rawTargets);
            byte[] pollBytes = Tag.readBytesWithNull(in);
            byte[] activationBytes = Tag.readBytesWithNull(in);
            int serviceHandle = in.readInt();

            return new Tag(id, isNdef, rawTargets, pollBytes, activationBytes, serviceHandle);
        }
        public Tag[] newArray(int size) {
            return new Tag[size];
        }
    };
}