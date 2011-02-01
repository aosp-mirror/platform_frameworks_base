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

package android.nfc.tech;

import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.INfcTag;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

/**
 * A high-level connection to a {@link Tag} using one of the NFC type 1, 2, 3, or 4 technologies
 * to interact with NDEF data. MiFare Classic cards that present NDEF data may also be used
 * via this class. To determine the exact technology being used call {@link #getType()}
 *
 * <p>You can acquire this kind of connection with {@link #get}.
 *
 * <p class="note"><strong>Note:</strong>
 * Use of this class requires the {@link android.Manifest.permission#NFC}
 * permission.
 */
public final class Ndef extends BasicTagTechnology {
    private static final String TAG = "NFC";

    /** @hide */
    public static final int NDEF_MODE_READ_ONLY = 1;
    /** @hide */
    public static final int NDEF_MODE_READ_WRITE = 2;
    /** @hide */
    public static final int NDEF_MODE_UNKNOWN = 3;

    /** @hide */
    public static final String EXTRA_NDEF_MSG = "ndefmsg";

    /** @hide */
    public static final String EXTRA_NDEF_MAXLENGTH = "ndefmaxlength";

    /** @hide */
    public static final String EXTRA_NDEF_CARDSTATE = "ndefcardstate";

    /** @hide */
    public static final String EXTRA_NDEF_TYPE = "ndeftype";

    /** @hide */
    public static final int TYPE_OTHER = -1;
    /** @hide */
    public static final int TYPE_1 = 1;
    /** @hide */
    public static final int TYPE_2 = 2;
    /** @hide */
    public static final int TYPE_3 = 3;
    /** @hide */
    public static final int TYPE_4 = 4;
    /** @hide */
    public static final int TYPE_MIFARE_CLASSIC = 101;

    /** @hide */
    public static final String UNKNOWN = "android.ndef.unknown";

    public static final String NFC_FORUM_TYPE_1 = "org.nfcforum.ndef.type1";

    public static final String NFC_FORUM_TYPE_2 = "org.nfcforum.ndef.type2";

    public static final String NFC_FORUM_TYPE_3 = "org.nfcforum.ndef.type3";

    public static final String NFC_FORUM_TYPE_4 = "org.nfcforum.ndef.type4";

    public static final String MIFARE_CLASSIC = "com.nxp.ndef.mifareclassic";

    private final int mMaxNdefSize;
    private final int mCardState;
    private final NdefMessage mNdefMsg;
    private final int mNdefType;

    /**
     * Returns an instance of this tech for the given tag. If the tag doesn't support
     * this tech type null is returned.
     *
     * @param tag The tag to get the tech from
     */
    public static Ndef get(Tag tag) {
        if (!tag.hasTech(TagTechnology.NDEF)) return null;
        try {
            return new Ndef(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Internal constructor, to be used by NfcAdapter
     * @hide
     */
    public Ndef(Tag tag) throws RemoteException {
        super(tag, TagTechnology.NDEF);
        Bundle extras = tag.getTechExtras(TagTechnology.NDEF);
        if (extras != null) {
            mMaxNdefSize = extras.getInt(EXTRA_NDEF_MAXLENGTH);
            mCardState = extras.getInt(EXTRA_NDEF_CARDSTATE);
            mNdefMsg = extras.getParcelable(EXTRA_NDEF_MSG);
            mNdefType = extras.getInt(EXTRA_NDEF_TYPE);
        } else {
            throw new NullPointerException("NDEF tech extras are null.");
        }

    }

    /**
     * Get the primary NDEF message on this tag. This data is read at discovery time
     * and does not require a connection.
     */
    public NdefMessage getCachedNdefMessage() {
        return mNdefMsg;
    }

    /**
     * Get NDEF tag type.
     * <p>Returns one of {@link #NFC_FORUM_TYPE_1}, {@link #NFC_FORUM_TYPE_2},
     * {@link #NFC_FORUM_TYPE_3}, {@link #NFC_FORUM_TYPE_4},
     * {@link #MIFARE_CLASSIC} or another NDEF tag type that is not yet in the
     * Android API.
     * <p>Android devices with NFC support must always correctly enumerate
     * NFC Forum tag types, and may optionally enumerate
     * {@link #MIFARE_CLASSIC} since it requires proprietary technology.
     */
    public String getType() {
        switch (mNdefType) {
            case TYPE_1:
                return NFC_FORUM_TYPE_1;
            case TYPE_2:
                return NFC_FORUM_TYPE_2;
            case TYPE_3:
                return NFC_FORUM_TYPE_3;
            case TYPE_4:
                return NFC_FORUM_TYPE_4;
            case TYPE_MIFARE_CLASSIC:
                return MIFARE_CLASSIC;
            default:
                return UNKNOWN;
        }
    }

    /**
     * Get maximum NDEF message size in bytes
     */
    public int getMaxSize() {
        return mMaxNdefSize;
    }

    /**
     * Provides a hint on whether writes are likely to succeed.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @return true if write is likely to succeed
     */
    public boolean isWritable() {
        return (mCardState == NDEF_MODE_READ_WRITE);
    }

    // Methods that require connect()
    /**
     * Get the primary NDEF message on this tag. This data is read actively
     * and requires a connection.
     */
    public NdefMessage getNdefMessage() throws IOException, FormatException {
        checkConnected();

        try {
            INfcTag tagService = mTag.getTagService();
            int serviceHandle = mTag.getServiceHandle();
            if (tagService.isNdef(serviceHandle)) {
                NdefMessage msg = tagService.ndefRead(serviceHandle);
                if (msg == null) {
                    int errorCode = tagService.getLastError(serviceHandle);
                    switch (errorCode) {
                        case ErrorCodes.ERROR_IO:
                            throw new IOException();
                        case ErrorCodes.ERROR_INVALID_PARAM:
                            throw new FormatException();
                        default:
                            // Should not happen
                            throw new IOException();
                    }
                }
                return msg;
            } else {
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
            return null;
        }
    }

    /**
     * Overwrite the primary NDEF message
     * @throws IOException
     */
    public void writeNdefMessage(NdefMessage msg) throws IOException, FormatException {
        checkConnected();

        try {
            INfcTag tagService = mTag.getTagService();
            int serviceHandle = mTag.getServiceHandle();
            if (tagService.isNdef(serviceHandle)) {
                int errorCode = tagService.ndefWrite(serviceHandle, msg);
                switch (errorCode) {
                    case ErrorCodes.SUCCESS:
                        break;
                    case ErrorCodes.ERROR_IO:
                        throw new IOException();
                    case ErrorCodes.ERROR_INVALID_PARAM:
                        throw new FormatException();
                    default:
                        // Should not happen
                        throw new IOException();
                }
            }
            else {
                throw new IOException("Tag is not ndef");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
        }
    }

    /**
     * Indicates whether a tag can be made read-only with
     * {@link #makeReadOnly()}
     */
    public boolean canMakeReadOnly() {
        if (mNdefType == TYPE_1 || mNdefType == TYPE_2) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sets the CC field to indicate this tag is read-only
     * and permanently sets the lock bits to prevent any further NDEF
     * modifications.
     * This is a one-way process and can not be reverted!
     * @throws IOException
     */
    public boolean makeReadOnly() throws IOException {
        checkConnected();

        try {
            INfcTag tagService = mTag.getTagService();
            if (tagService.isNdef(mTag.getServiceHandle())) {
                int errorCode = tagService.ndefMakeReadOnly(mTag.getServiceHandle());
                switch (errorCode) {
                    case ErrorCodes.SUCCESS:
                        return true;
                    case ErrorCodes.ERROR_IO:
                        throw new IOException();
                    case ErrorCodes.ERROR_INVALID_PARAM:
                        return false;
                    default:
                        // Should not happen
                        throw new IOException();
                }
           }
           else {
               throw new IOException("Tag is not ndef");
           }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
            return false;
        }
    }
}
