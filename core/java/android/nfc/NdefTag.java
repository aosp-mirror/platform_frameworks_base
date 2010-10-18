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
 * Represents a discovered tag that contains {@link NdefMessage}s (or a tag that can store them).
 * In practice, a tag is a thing that an NFC-enabled device can communicate with. This
 * class is a representation of such a tag and can contain the NDEF messages shared by the tag.
 * <p>An NDEF tag can contain zero or more NDEF messages (represented by {@link NdefMessage}
 * objects) in addition to the basic tag properties of UID and Type.
 * <p>
 * {@link NdefTag}s that have been initialized will usually contain a single {@link NdefMessage}
 * (and that Message can contain multiple {@link NdefRecord}s). However it
 * is possible for {@link NdefTag}s to contain multiple {@link NdefMessage}s.
 * <p>{@link NfcAdapter#createNdefTagConnection createNdefTagConnection()} can be used to modify the
 * contents of some tags.
 * <p>This is an immutable data class.
 */
public class NdefTag extends Tag implements Parcelable {
    private final NdefMessage[] mMessages;

    /**
     * Hidden constructor to be used by NFC service when a
     * tag is discovered and by Parcelable methods.
     * @hide
     */
    public NdefTag(String typeName, byte[] uid, int nativeHandle, NdefMessage[] messages) {
        super(typeName, true, uid, nativeHandle);
        mMessages = messages.clone();
    }

    /**
     * Get all NDEF Messages.
     * <p>
     * This retrieves the NDEF Messages that were found on the Tag at discovery
     * time. It does not cause any further RF activity, and does not block.
     * <p>
     * Most tags only contain a single NDEF message.
     *
     * @return NDEF Messages found at Tag discovery
     */
    public NdefMessage[] getNdefMessages() {
        return mMessages.clone();
    }

    /**
     * Get only the NDEF Messages from a single NDEF target on a tag.
     * <p>
     * This retrieves the NDEF Messages that were found on the Tag at discovery
     * time. It does not cause any further RF activity, and does not block.
     * <p>
     * Most tags only contain a single NDEF message.
     *
     * @param target One of targets strings provided by getNdefTargets()
     * @return NDEF Messages found at Tag discovery
     */
    public NdefMessage[] getNdefMessages(String target) {
        // TODO: handle multiprotocol
        String[] localTypes = convertToNdefType(mTypeName);
        if (!target.equals(localTypes[0])) {
            throw new IllegalArgumentException();
        }
        return getNdefMessages();
    }

    /** TODO(npelly):
     * - check that any single tag can only have one of each NDEF type
     * - ok to include mifare_classic?
     */
    public static final String TARGET_TYPE_1 = "type_1";
    public static final String TARGET_TYPE_2 = "type_2";
    public static final String TARGET_TYPE_3 = "type_3";
    public static final String TARGET_TYPE_4 = "type_4";
    public static final String TARGET_MIFARE_CLASSIC = "type_mifare_classic";
    public static final String TARGET_OTHER = "other";

    private static final HashMap<String, String[]> NDEF_TYPES_CONVERTION_TABLE = new HashMap<String, String[]>() {
        {
            // TODO: handle multiprotocol
            // TODO: move INTERNAL_TARGET_Type to TARGET_TYPE mapping to NFC service
            put(Tag.INTERNAL_TARGET_TYPE_JEWEL, new String[] { NdefTag.TARGET_TYPE_1 });
            put(Tag.INTERNAL_TARGET_TYPE_MIFARE_UL, new String[] { NdefTag.TARGET_TYPE_2 });
            put(Tag.INTERNAL_TARGET_TYPE_MIFARE_1K, new String[] { NdefTag.TARGET_MIFARE_CLASSIC });
            put(Tag.INTERNAL_TARGET_TYPE_MIFARE_4K, new String[] { NdefTag.TARGET_MIFARE_CLASSIC });
            put(Tag.INTERNAL_TARGET_TYPE_FELICA, new String[] { NdefTag.TARGET_TYPE_3 });
            put(Tag.INTERNAL_TARGET_TYPE_ISO14443_4, new String[] { NdefTag.TARGET_TYPE_4 });
            put(Tag.INTERNAL_TARGET_TYPE_MIFARE_DESFIRE, new String[] { NdefTag.TARGET_TYPE_4 });
        }
    };

    private String[] convertToNdefType(String internalTypeName) {
        String[] result =  NDEF_TYPES_CONVERTION_TABLE.get(internalTypeName);
        if (result == null) {
            return new String[] { NdefTag.TARGET_OTHER };
        }
        return result;
    }

    /**
     * Return the
     *
     * @return
     */
    public String[] getNdefTargets() {
        return convertToNdefType(mTypeName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mMessages.length);
        dest.writeTypedArray(mMessages, flags);
    }

    public static final Parcelable.Creator<NdefTag> CREATOR =
            new Parcelable.Creator<NdefTag>() {
        public NdefTag createFromParcel(Parcel in) {
            Tag tag = Tag.CREATOR.createFromParcel(in);
            int messagesLength = in.readInt();
            NdefMessage[] messages = new NdefMessage[messagesLength];
            in.readTypedArray(messages, NdefMessage.CREATOR);
            return new NdefTag(tag.mTypeName, tag.mUid, tag.mNativeHandle, messages);
        }
        public NdefTag[] newArray(int size) {
            return new NdefTag[size];
        }
    };
}