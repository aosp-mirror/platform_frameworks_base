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

import java.util.HashMap;

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
 * <p>This is an immutable data class.
 */
public class Tag implements Parcelable {

    /**
     * @hide
     */
    public static final int NFC_TAG_ISO14443_A = 1; /* phNfc_eISO14443_A_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_ISO14443_4A = 2; /* phNfc_eISO14443_4A_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_ISO14443_3A = 3; /* phNfc_eISO14443_3A_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_MIFARE = 4; /* phNfc_eMifare_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_ISO14443_B = 5; /* phNfc_eISO14443_B_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_ISO14443_4B = 6; /* phNfc_eISO14443_4B_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_ISO14443_B_PRIME = 7; /* phNfc_eISO14443_BPrime_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_FELICA = 8; /* phNfc_eFelica_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_JEWEL = 9; /* phNfc_eJewel_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_ISO15693 = 10; /* phNfc_eISO15693_PICC */

    /**
     * @hide
     */
    public static final int NFC_TAG_OTHER = 11; /* phNfc_ePICC_DevType */


    public static final String TARGET_ISO_14443_3A = "iso14443_3a";

    public static final String TARGET_ISO_14443_3B = "iso14443_3b";

    public static final String TARGET_ISO_14443_3B_PRIME = "iso14443_3b";

    public static final String TARGET_ISO_14443_4 = "iso14443_4";

    public static final String TARGET_ISO_15693 = "iso15693";

    public static final String TARGET_JIS_X_6319_4 = "jis_x_6319_4";

    public static final String TARGET_TOPAZ = "topaz";

    public static final String TARGET_OTHER = "other";

    /*package*/ final String mTypeName;
    /*package*/ final boolean mIsNdef;
    /*package*/ final byte[] mUid;
    /*package*/ final int mNativeHandle;

    /*package*/ static final String INTERNAL_TARGET_TYPE_ISO14443_3A = "Iso14443-3A";
    /*package*/ static final String INTERNAL_TARGET_TYPE_ISO14443_3B = "Iso14443-3B";
    /*package*/ static final String INTERNAL_TARGET_TYPE_ISO14443_4 = "Iso14443-4";
    /*package*/ static final String INTERNAL_TARGET_TYPE_MIFARE_UL = "MifareUL";
    /*package*/ static final String INTERNAL_TARGET_TYPE_MIFARE_1K = "Mifare1K";
    /*package*/ static final String INTERNAL_TARGET_TYPE_MIFARE_4K = "Mifare4K";
    /*package*/ static final String INTERNAL_TARGET_TYPE_MIFARE_DESFIRE = "MifareDESFIRE";
    /*package*/ static final String INTERNAL_TARGET_TYPE_MIFARE_UNKNOWN = "Unknown Mifare";
    /*package*/ static final String INTERNAL_TARGET_TYPE_FELICA = "Felica";
    /*package*/ static final String INTERNAL_TARGET_TYPE_JEWEL = "Jewel";
    /*package*/ static final String INTERNAL_TARGET_TYPE_UNKNOWN = "Unknown Type";

	private static final HashMap<String, Integer> INT_TYPES_CONVERTION_TABLE = new HashMap<String, Integer>() {
		{
			put(Tag.INTERNAL_TARGET_TYPE_ISO14443_3A, Tag.NFC_TAG_ISO14443_A );
			put(Tag.INTERNAL_TARGET_TYPE_ISO14443_3B, Tag.NFC_TAG_ISO14443_B );
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_UL, Tag.NFC_TAG_MIFARE );
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_1K, Tag.NFC_TAG_MIFARE );
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_4K, Tag.NFC_TAG_MIFARE );
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_DESFIRE, Tag.NFC_TAG_MIFARE );
			put(Tag.INTERNAL_TARGET_TYPE_FELICA, Tag.NFC_TAG_FELICA );
			put(Tag.INTERNAL_TARGET_TYPE_JEWEL, Tag.NFC_TAG_JEWEL );
		}
	};

	private int convertToInt(String internalTypeName) {
		Integer result = INT_TYPES_CONVERTION_TABLE.get(internalTypeName);
		if (result == null) {
		    return Tag.NFC_TAG_OTHER;
		}
		return result;
    }

	private static final HashMap<String, String[]> RAW_TYPES_CONVERTION_TABLE = new HashMap<String, String[]>() {
		{
			/* TODO: handle multiprotocol */
			put(Tag.INTERNAL_TARGET_TYPE_ISO14443_3A, new String[] { Tag.TARGET_ISO_14443_3A });
			put(Tag.INTERNAL_TARGET_TYPE_ISO14443_3B, new String[] { Tag.TARGET_ISO_14443_3B });
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_UL, new String[] { Tag.TARGET_ISO_14443_3A });
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_1K, new String[] { Tag.TARGET_ISO_14443_3A });
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_4K, new String[] { Tag.TARGET_ISO_14443_3A });
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_DESFIRE, new String[] { Tag.TARGET_ISO_14443_3A });
			put(Tag.INTERNAL_TARGET_TYPE_MIFARE_UNKNOWN, new String[] { Tag.TARGET_ISO_14443_3A });
			put(Tag.INTERNAL_TARGET_TYPE_FELICA, new String[] { Tag.TARGET_JIS_X_6319_4 });
			put(Tag.INTERNAL_TARGET_TYPE_JEWEL, new String[] { Tag.TARGET_TOPAZ });
		}
	};

	private String[] convertToRaw(String internalTypeName) {
	    String[] result =  RAW_TYPES_CONVERTION_TABLE.get(internalTypeName);
	    if (result == null) {
	        return new String[] { Tag.TARGET_OTHER };
	    }
	    return result;
	}

    /**
     * Hidden constructor to be used by NFC service only.
     * @hide
     */
    public Tag(String typeName, boolean isNdef, byte[] uid, int nativeHandle) {
        mTypeName = typeName;
        mIsNdef = isNdef;
        mUid = uid.clone();
        mNativeHandle = nativeHandle;
    }

    /**
     * For use by NfcService only.
     * @hide
     */
    public int getHandle() {
        return mNativeHandle;
    }

    /**
     * Return the available targets that this NFC adapter can use to create
     * a RawTagConnection.
     *
     * @return
     */
    public String[] getRawTargets() {
        return convertToRaw(mTypeName);
    }

    /**
     * Get the Tag type.
     * <p>
     * The Tag type is one of the NFC_TAG constants. It is read at discovery
     * time and this method does not cause any further RF activity and does not
     * block.
     *
     * @return a NFC_TAG constant
     * @hide
     */
    public int getType() {
        return convertToInt(mTypeName);
    }

    /**
     * Get the Tag Identifier (if it has one).
     * <p>
     * Tag ID is usually a serial number for the tag.
     * <p>
     * The Tag ID is read at discovery time and this method does not cause any
     * further RF activity and does not block.
     *
     * @return ID, or null if it does not exist
     */
    public byte[] getId() {
        if (mUid.length > 0) {
            return mUid.clone();
        } else {
            return null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        boolean[] booleans = new boolean[] {mIsNdef};
        dest.writeString(mTypeName);
        dest.writeBooleanArray(booleans);
        dest.writeInt(mUid.length);
        dest.writeByteArray(mUid);
        dest.writeInt(mNativeHandle);
    }

    public static final Parcelable.Creator<Tag> CREATOR =
            new Parcelable.Creator<Tag>() {
        public Tag createFromParcel(Parcel in) {
            boolean[] booleans = new boolean[1];
            String type = in.readString();
            in.readBooleanArray(booleans);
            boolean isNdef = booleans[0];
            int uidLength = in.readInt();
            byte[] uid = new byte[uidLength];
            in.readByteArray(uid);
            int nativeHandle = in.readInt();

            return new Tag(type, isNdef, uid, nativeHandle);
        }
        public Tag[] newArray(int size) {
            return new Tag[size];
        }
    };
}