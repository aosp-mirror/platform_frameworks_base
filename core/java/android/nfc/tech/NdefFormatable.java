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
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

/**
 * Provide access to NDEF format operations on a {@link Tag}.
 *
 * <p>Acquire a {@link NdefFormatable} object using {@link #get}.
 *
 * <p>Android devices with NFC must only enumerate and implement this
 * class for tags for which it can format to NDEF.
 *
 * <p>Unfortunately the procedures to convert unformated tags to NDEF formatted
 * tags are not specified by NFC Forum, and are not generally well-known. So
 * there is no mandatory set of tags for which all Android devices with NFC
 * must support {@link NdefFormatable}.
 *
 * <p class="note"><strong>Note:</strong> Methods that perform I/O operations
 * require the {@link android.Manifest.permission#NFC} permission.
 */
public final class NdefFormatable extends BasicTagTechnology {
    private static final String TAG = "NFC";

    /**
     * Get an instance of {@link NdefFormatable} for the given tag.
     * <p>Does not cause any RF activity and does not block.
     * <p>Returns null if {@link NdefFormatable} was not enumerated in {@link Tag#getTechList}.
     * This indicates the tag is not NDEF formatable by this Android device.
     *
     * @param tag an NDEF formatable tag
     * @return NDEF formatable object
     */
    public static NdefFormatable get(Tag tag) {
        if (!tag.hasTech(TagTechnology.NDEF_FORMATABLE)) return null;
        try {
            return new NdefFormatable(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Internal constructor, to be used by NfcAdapter
     * @hide
     */
    public NdefFormatable(Tag tag) throws RemoteException {
        super(tag, TagTechnology.NDEF_FORMATABLE);
    }

    /**
     * Format a tag as NDEF, and write a {@link NdefMessage}.
     *
     * <p>This is a multi-step process, an IOException is thrown
     * if any one step fails.
     * <p>The card is left in a read-write state after this operation.
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param firstMessage the NDEF message to write after formatting, can be null
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or the operation is canceled
     * @throws FormatException if the NDEF Message to write is malformed
     */
    public void format(NdefMessage firstMessage) throws IOException, FormatException {
        format(firstMessage, false);
    }

    /**
     * Formats a tag as NDEF, write a {@link NdefMessage}, and make read-only.
     *
     * <p>This is a multi-step process, an IOException is thrown
     * if any one step fails.
     * <p>The card is left in a read-only state if this method returns successfully.
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param firstMessage the NDEF message to write after formatting
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or the operation is canceled
     * @throws FormatException if the NDEF Message to write is malformed
     */
    public void formatReadOnly(NdefMessage firstMessage) throws IOException, FormatException {
        format(firstMessage, true);
    }

    /*package*/ void format(NdefMessage firstMessage, boolean makeReadOnly) throws IOException,
            FormatException {
        checkConnected();

        try {
            int serviceHandle = mTag.getServiceHandle();
            INfcTag tagService = mTag.getTagService();
            int errorCode = tagService.formatNdef(serviceHandle, MifareClassic.KEY_DEFAULT);
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
            // Now check and see if the format worked
            if (!tagService.isNdef(serviceHandle)) {
                throw new IOException();
            }

            // Write a message, if one was provided
            if (firstMessage != null) {
                errorCode = tagService.ndefWrite(serviceHandle, firstMessage);
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

            // optionally make read-only
            if (makeReadOnly) {
                errorCode = tagService.ndefMakeReadOnly(serviceHandle);
                switch (errorCode) {
                    case ErrorCodes.SUCCESS:
                        break;
                    case ErrorCodes.ERROR_IO:
                        throw new IOException();
                    case ErrorCodes.ERROR_INVALID_PARAM:
                        throw new IOException();
                    default:
                        // Should not happen
                        throw new IOException();
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
        }
    }
}
