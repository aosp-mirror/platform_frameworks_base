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
 * NdefTag is a Tag that has NDEF messages or can store NDEF messages.
 * <p>
 * NDEF Tag's contain zero or more NDEF Messages in addition to the basic
 * Tag properties of UID and Type.
 * <p>
 * NDEF Tag's that have been initialized will usually contain a single NDEF
 * Message (and that Message can contain multiple NDEF Records). However it
 * is possible for NDEF Tag's to contain multiple NDEF Messages.
 * <p>
 * This class is a immutable data class that contains the contents of the NDEF
 * Message(s) as read at Tag discovery time.
 * <p>
 * NfcAdapter.createNdefTagConnection() can be used to modify the contents of
 * some NDEF Tag's.
 */
public class NdefTag extends Tag implements Parcelable {
    private final NdefMessage[] mMessages;

    /**
     * Hidden constructor to be used by NFC service when a
     * tag is discovered and by Parcelable methods.
     * @hide
     */
    public NdefTag(int type, byte[] uid, int nativeHandle, NdefMessage[] messages) {
        super(type, true, uid, nativeHandle);
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
     */
    public NdefMessage[] getNdefMessages(String target) {
        //TODO(nxp): new api method
        throw new UnsupportedOperationException();
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

    /**
     * Return the
     *
     * @return
     */
    public String[] getNdefTargets() {
        throw new UnsupportedOperationException();
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
            return new NdefTag(tag.mType, tag.mUid, tag.mNativeHandle, messages);
        }
        public NdefTag[] newArray(int size) {
            return new NdefTag[size];
        }
    };
}