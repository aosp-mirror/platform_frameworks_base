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
 * <p>This is an immutable data class. All properties are set at Tag discovery
 * time and calls on this class will retrieve those read-only properties, and
 * not cause any further RF activity or block. Note however that arrays passed to and
 * returned by this class are *not* cloned, so be careful not to modify them.
 * @hide
 */
public class NdefTag extends Tag implements Parcelable {
    /**
     * Target for NFC Forum Type 1 compliant tag.
     * <p>This is based on Jewel/Topaz technology
     */
    public static final String TARGET_TYPE_1 = "type_1";

    /**
     * Target for NFC Forum Type 2 compliant tag.
     * <p>This is based on Mifare Ultralight technology.
     */
    public static final String TARGET_TYPE_2 = "type_2";

    /**
     * Target for NFC Forum Type 3 compliant tag.
     * <p>This is based on Felica technology.
     */
    public static final String TARGET_TYPE_3 = "type_3";

    /**
     * Target for NFC Forum Type 4 compliant tag.
     * <p>This is based on Mifare Desfire technology.
     */
    public static final String TARGET_TYPE_4 = "type_4";

    /**
     * Target for NFC Forum Enabled: Mifare Classic tag.
     * <p>This is not strictly a NFC Forum tag type, but is a common
     * NDEF message container.
     */
    public static final String TARGET_MIFARE_CLASSIC = "type_mifare_classic";

    /**
     * Any other target.
     */
    public static final String TARGET_OTHER = "other";

    private final String[] mNdefTargets;
    private final NdefMessage[][] mMessages;  // one NdefMessage[] per NDEF target
    private NdefMessage[] mFlatMessages;  // collapsed mMessages, built lazily, protected by (this)

    /**
     * Hidden constructor to be used by NFC service only.
     * @hide
     */
    public NdefTag(byte[] id, String[] rawTargets, byte[] pollBytes, byte[] activationBytes,
            int serviceHandle, String[] ndefTargets, NdefMessage[][] messages) {
        super(id, true, rawTargets, pollBytes, activationBytes, serviceHandle);
        if (ndefTargets == null || messages == null) {
            throw new IllegalArgumentException("ndefTargets or messages cannot be null");
        }
        if (ndefTargets.length != messages.length){
            throw new IllegalArgumentException("ndefTargets and messages arrays must match");
        }
        for (NdefMessage[] ms : messages) {
            if (ms == null) {
                throw new IllegalArgumentException("messages elements cannot be null");
            }
        }
        mNdefTargets = ndefTargets;
        mMessages = messages;
    }

    /**
     * Construct a mock NdefTag.
     * <p>This is an application constructed tag, so NfcAdapter methods on this
     * Tag such as {@link NfcAdapter#createRawTagConnection} will fail with
     * {@link IllegalArgumentException} since it does not represent a physical Tag.
     * <p>This constructor might be useful for mock testing.
     * @param id The tag identifier, can be null
     * @param rawTargets must not be null
     * @param pollBytes can be null
     * @param activationBytes can be null
     * @param ndefTargets NDEF target array, such as {TARGET_TYPE_2}, cannot be null
     * @param messages messages, one array per NDEF target, cannot be null
     * @return freshly constructed NdefTag
     */
    public static NdefTag createMockNdefTag(byte[] id, String[] rawTargets, byte[] pollBytes,
            byte[] activationBytes, String[] ndefTargets, NdefMessage[][] messages) {
        // set serviceHandle to 0 to indicate mock tag
        return new NdefTag(id, rawTargets, pollBytes, activationBytes, 0, ndefTargets, messages);
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
        // common-case optimization
        if (mMessages.length == 1) {
            return mMessages[0];
        }

        // return cached flat array
        synchronized(this) {
            if (mFlatMessages != null) {
                return mFlatMessages;
            }
            // not cached - build a flat array
            int sz = 0;
            for (NdefMessage[] ms : mMessages) {
                sz += ms.length;
            }
            mFlatMessages = new NdefMessage[sz];
            int i = 0;
            for (NdefMessage[] ms : mMessages) {
                System.arraycopy(ms, 0, mFlatMessages, i, ms.length);
                i += ms.length;
            }
            return mFlatMessages;
        }
    }

    /**
     * Get only the NDEF Messages from a single NDEF target on a tag.
     * <p>
     * This retrieves the NDEF Messages that were found on the Tag at discovery
     * time. It does not cause any further RF activity, and does not block.
     * <p>
     * Most tags only contain a single NDEF message.
     *
     * @param target one of targets strings provided by getNdefTargets()
     * @return NDEF Messages found at Tag discovery
     */
    public NdefMessage[] getNdefMessages(String target) {
        for (int i=0; i<mNdefTargets.length; i++) {
            if (target.equals(mNdefTargets[i])) {
                return mMessages[i];
            }
        }
        throw new IllegalArgumentException("target (" + target + ") not found");
    }

    /**
     * Return the NDEF targets on this Tag that support NDEF messages.
     *
     * @return
     */
    public String[] getNdefTargets() {
        return mNdefTargets;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Tag fields
        dest.writeInt(mIsNdef ? 1 : 0);
        writeBytesWithNull(dest, mId);
        dest.writeInt(mRawTargets.length);
        dest.writeStringArray(mRawTargets);
        writeBytesWithNull(dest, mPollBytes);
        writeBytesWithNull(dest, mActivationBytes);
        dest.writeInt(mServiceHandle);

        // NdefTag fields
        dest.writeInt(mNdefTargets.length);
        dest.writeStringArray(mNdefTargets);
        dest.writeInt(mMessages.length);
        for (NdefMessage[] ms : mMessages) {
            dest.writeInt(ms.length);
            dest.writeTypedArray(ms, flags);
        }
    }

    public static final Parcelable.Creator<NdefTag> CREATOR =
            new Parcelable.Creator<NdefTag>() {
        public NdefTag createFromParcel(Parcel in) {
            boolean isNdef = (in.readInt() == 1);
            if (!isNdef) {
                throw new IllegalArgumentException("Creating NdefTag from Tag parcel");
            }

            // Tag fields
            byte[] id = readBytesWithNull(in);
            String[] rawTargets = new String[in.readInt()];
            in.readStringArray(rawTargets);
            byte[] pollBytes = readBytesWithNull(in);
            byte[] activationBytes = readBytesWithNull(in);
            int serviceHandle = in.readInt();

            // NdefTag fields
            String[] ndefTargets = new String[in.readInt()];
            in.readStringArray(ndefTargets);
            NdefMessage[][] messages = new NdefMessage[in.readInt()][];
            for (int i=0; i<messages.length; i++) {
                messages[i] = new NdefMessage[in.readInt()];
                in.readTypedArray(messages[i], NdefMessage.CREATOR);
            }
            return new NdefTag(id, rawTargets, pollBytes, activationBytes, serviceHandle,
                    ndefTargets, messages);
        }
        public NdefTag[] newArray(int size) {
            return new NdefTag[size];
        }
    };
}