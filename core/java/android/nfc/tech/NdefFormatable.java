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
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

/**
 * An interface to a {@link Tag} allowing to format the tag as NDEF.
 *
 * <p>You can acquire this kind of connection with {@link #get}.
 *
 * <p class="note"><strong>Note:</strong>
 * Use of this class requires the {@link android.Manifest.permission#NFC}
 * permission.
 */
public final class NdefFormatable extends BasicTagTechnology {
    private static final String TAG = "NFC";

    /**
     * Returns an instance of this tech for the given tag. If the tag doesn't support
     * this tech type null is returned.
     *
     * @param tag The tag to get the tech from
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
     * Formats a tag as NDEF, if possible. You may supply a first
     * NdefMessage to be written on the tag.
     */
    public void format(NdefMessage firstMessage) throws IOException, FormatException {
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
            if (tagService.isNdef(serviceHandle)) {
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
            } else {
                throw new IOException();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
        }
    }
}
