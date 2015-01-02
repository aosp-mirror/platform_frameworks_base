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
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

/**
 * Provides access to NDEF content and operations on a {@link Tag}.
 *
 * <p>Acquire a {@link Ndef} object using {@link #get}.
 *
 * <p>NDEF is an NFC Forum data format. The data formats are implemented in
 * {@link android.nfc.NdefMessage} and
 * {@link android.nfc.NdefRecord}. This class provides methods to
 * retrieve and modify the {@link android.nfc.NdefMessage}
 * on a tag.
 *
 * <p>There are currently four NFC Forum standardized tag types that can be
 * formatted to contain NDEF data.
 * <ul>
 * <li>NFC Forum Type 1 Tag ({@link #NFC_FORUM_TYPE_1}), such as the Innovision Topaz
 * <li>NFC Forum Type 2 Tag ({@link #NFC_FORUM_TYPE_2}), such as the NXP MIFARE Ultralight
 * <li>NFC Forum Type 3 Tag ({@link #NFC_FORUM_TYPE_3}), such as Sony Felica
 * <li>NFC Forum Type 4 Tag ({@link #NFC_FORUM_TYPE_4}), such as NXP MIFARE Desfire
 * </ul>
 * It is mandatory for all Android devices with NFC to correctly enumerate
 * {@link Ndef} on NFC Forum Tag Types 1-4, and implement all NDEF operations
 * as defined in this class.
 *
 * <p>Some vendors have their own well defined specifications for storing NDEF data
 * on tags that do not fall into the above categories. Android devices with NFC
 * should enumerate and implement {@link Ndef} under these vendor specifications
 * where possible, but it is not mandatory. {@link #getType} returns a String
 * describing this specification, for example {@link #MIFARE_CLASSIC} is
 * <code>com.nxp.ndef.mifareclassic</code>.
 *
 * <p>Android devices that support MIFARE Classic must also correctly
 * implement {@link Ndef} on MIFARE Classic tags formatted to NDEF.
 *
 * <p>For guaranteed compatibility across all Android devices with NFC, it is
 * recommended to use NFC Forum Types 1-4 in new deployments of NFC tags
 * with NDEF payload. Vendor NDEF formats will not work on all Android devices.
 *
 * <p class="note"><strong>Note:</strong> Methods that perform I/O operations
 * require the {@link android.Manifest.permission#NFC} permission.
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
    public static final int TYPE_ICODE_SLI = 102;

    /** @hide */
    public static final String UNKNOWN = "android.ndef.unknown";

    /** NFC Forum Tag Type 1 */
    public static final String NFC_FORUM_TYPE_1 = "org.nfcforum.ndef.type1";
    /** NFC Forum Tag Type 2 */
    public static final String NFC_FORUM_TYPE_2 = "org.nfcforum.ndef.type2";
    /** NFC Forum Tag Type 4 */
    public static final String NFC_FORUM_TYPE_3 = "org.nfcforum.ndef.type3";
    /** NFC Forum Tag Type 4 */
    public static final String NFC_FORUM_TYPE_4 = "org.nfcforum.ndef.type4";
    /** NDEF on MIFARE Classic */
    public static final String MIFARE_CLASSIC = "com.nxp.ndef.mifareclassic";
    /**
     * NDEF on iCODE SLI
     * @hide
     */
    public static final String ICODE_SLI = "com.nxp.ndef.icodesli";

    private final int mMaxNdefSize;
    private final int mCardState;
    private final NdefMessage mNdefMsg;
    private final int mNdefType;

    /**
     * Get an instance of {@link Ndef} for the given tag.
     *
     * <p>Returns null if {@link Ndef} was not enumerated in {@link Tag#getTechList}.
     * This indicates the tag is not NDEF formatted, or that this tag
     * is NDEF formatted but under a vendor specification that this Android
     * device does not implement.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @param tag an NDEF compatible tag
     * @return Ndef object
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
     * Get the {@link NdefMessage} that was read from the tag at discovery time.
     *
     * <p>If the NDEF Message is modified by an I/O operation then it
     * will not be updated here, this function only returns what was discovered
     * when the tag entered the field.
     * <p>Note that this method may return null if the tag was in the
     * INITIALIZED state as defined by NFC Forum, as in this state the
     * tag is formatted to support NDEF but does not contain a message yet.
     * <p>Does not cause any RF activity and does not block.
     * @return NDEF Message read from the tag at discovery time, can be null
     */
    public NdefMessage getCachedNdefMessage() {
        return mNdefMsg;
    }

    /**
     * Get the NDEF tag type.
     *
     * <p>Returns one of {@link #NFC_FORUM_TYPE_1}, {@link #NFC_FORUM_TYPE_2},
     * {@link #NFC_FORUM_TYPE_3}, {@link #NFC_FORUM_TYPE_4},
     * {@link #MIFARE_CLASSIC} or another NDEF tag type that has not yet been
     * formalized in this Android API.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @return a string representing the NDEF tag type
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
            case TYPE_ICODE_SLI:
                return ICODE_SLI;
            default:
                return UNKNOWN;
        }
    }

    /**
     * Get the maximum NDEF message size in bytes.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @return size in bytes
     */
    public int getMaxSize() {
        return mMaxNdefSize;
    }

    /**
     * Determine if the tag is writable.
     *
     * <p>NFC Forum tags can be in read-only or read-write states.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     *
     * @return true if the tag is writable
     */
    public boolean isWritable() {
        return (mCardState == NDEF_MODE_READ_WRITE);
    }

    /**
     * Read the current {@link android.nfc.NdefMessage} on this tag.
     *
     * <p>This always reads the current NDEF Message stored on the tag.
     *
     * <p>Note that this method may return null if the tag was in the
     * INITIALIZED state as defined by NFC Forum, as in that state the
     * tag is formatted to support NDEF but does not contain a message yet.
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @return the NDEF Message, can be null
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or the operation is canceled
     * @throws FormatException if the NDEF Message on the tag is malformed
     */
    public NdefMessage getNdefMessage() throws IOException, FormatException {
        checkConnected();

        try {
            INfcTag tagService = mTag.getTagService();
            if (tagService == null) {
                throw new IOException("Mock tags don't support this operation.");
            }
            int serviceHandle = mTag.getServiceHandle();
            if (tagService.isNdef(serviceHandle)) {
                NdefMessage msg = tagService.ndefRead(serviceHandle);
                if (msg == null && !tagService.isPresent(serviceHandle)) {
                    throw new TagLostException();
                }
                return msg;
            } else if (!tagService.isPresent(serviceHandle)) {
                throw new TagLostException();
            } else {
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
            return null;
        }
    }

    /**
     * Overwrite the {@link NdefMessage} on this tag.
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param msg the NDEF Message to write, must not be null
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or the operation is canceled
     * @throws FormatException if the NDEF Message to write is malformed
     */
    public void writeNdefMessage(NdefMessage msg) throws IOException, FormatException {
        checkConnected();

        try {
            INfcTag tagService = mTag.getTagService();
            if (tagService == null) {
                throw new IOException("Mock tags don't support this operation.");
            }
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
     * Indicates whether a tag can be made read-only with {@link #makeReadOnly()}.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @return true if it is possible to make this tag read-only
     */
    public boolean canMakeReadOnly() {
        INfcTag tagService = mTag.getTagService();
        if (tagService == null) {
            return false;
        }
        try {
            return tagService.canMakeReadOnly(mNdefType);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
            return false;
        }
    }

    /**
     * Make a tag read-only.
     *
     * <p>This sets the CC field to indicate the tag is read-only,
     * and where possible permanently sets the lock bits to prevent
     * any further modification of the memory.
     * <p>This is a one-way process and cannot be reverted!
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @return true on success, false if it is not possible to make this tag read-only
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or the operation is canceled
     */
    public boolean makeReadOnly() throws IOException {
        checkConnected();

        try {
            INfcTag tagService = mTag.getTagService();
            if (tagService == null) {
                return false;
            }
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
