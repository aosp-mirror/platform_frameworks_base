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

package android.nfc.technology;

import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.RemoteException;

import java.io.IOException;

/**
 * A high-level connection to a {@link Tag} using one of the NFC type 1, 2, 3, or 4 technologies
 * to interact with NDEF data. MiFare Classic cards that present NDEF data may also be used
 * via this class. To determine the exact technology being used call {@link #getTechnologyId()}
 *
 * <p>You can acquire this kind of connection with {@link Tag#getTechnology(int)}.
 *
 * <p class="note"><strong>Note:</strong>
 * Use of this class requires the {@link android.Manifest.permission#NFC}
 * permission.
 */
public final class Ndef extends BasicTagTechnology {
    public static final int NDEF_MODE_READ_ONCE = 1;
    public static final int NDEF_MODE_READ_ONLY = 2;
    public static final int NDEF_MODE_WRITE_ONCE = 3;
    public static final int NDEF_MODE_WRITE_MANY = 4;
    public static final int NDEF_MODE_UNKNOWN = 5;

    /** @hide */
    public static final String EXTRA_NDEF_MSG = "ndefmsg";

    /**
     * Internal constructor, to be used by NfcAdapter
     * @hide
     */
    public Ndef(NfcAdapter adapter, Tag tag, int tech, Bundle extras) throws RemoteException {
        super(adapter, tag, tech);
    }

    /**
     * Get the primary NDEF message on this tag. This data is read at discovery time
     * and does not require a connection.
     */
    public NdefMessage getNdefMessage() throws IOException, FormatException {
        try {
            int serviceHandle = mTag.getServiceHandle();
            NdefMessage msg = mTagService.read(serviceHandle);
            if (msg == null) {
                int errorCode = mTagService.getLastError(serviceHandle);
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
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }

    /**
     * Get optional extra NDEF messages.
     * Some tags may contain extra NDEF messages, but not all
     * implementations will be able to read them.
     */
    public NdefMessage[] getExtraNdefMessage() throws IOException, FormatException {
        throw new UnsupportedOperationException();
    }

    /**
     * Get maximum NDEF message size in bytes
     */
    public int getSize() {
        throw new UnsupportedOperationException();
    }

    /**
     * Read/Write mode hint.
     * Provides a hint if further reads or writes are likely to succeed.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @return one of NDEF_MODE
     * @throws IOException if the target is lost or connection closed
     */
    public int getModeHint() throws IOException {
        try {
            int result = mTagService.getModeHint(mTag.getServiceHandle());
            if (ErrorCodes.isError(result)) {
                switch (result) {
                    case ErrorCodes.ERROR_IO:
                        throw new IOException();
                    default:
                        // Should not happen
                        throw new IOException();
                }
            }
            return result;

        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return NDEF_MODE_UNKNOWN;
        }
    }

    // Methods that require connect()
    /**
     * Overwrite the primary NDEF message
     * @throws IOException
     */
    public void writeNdefMessage(NdefMessage msg) throws IOException, FormatException {
        try {
            int errorCode = mTagService.write(mTag.getServiceHandle(), msg);
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
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Attempt to write extra NDEF messages.
     * Implementations may be able to write extra NDEF
     * message after the first primary message, but it is not
     * guaranteed. Even if it can be written, other implementations
     * may not be able to read NDEF messages after the primary message.
     * It is recommended to use additional NDEF records instead.
     *
     * @throws IOException
     */
    public void writeExtraNdefMessage(int i, NdefMessage msg) throws IOException, FormatException {
        throw new UnsupportedOperationException();
    }

    /**
     * Set the CC field to indicate this tag is read-only
     * @throws IOException
     */
    public boolean makeReadonly() throws IOException {
        try {
            int errorCode = mTagService.makeReadOnly(mTag.getServiceHandle());
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
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return false;
        }
    }

    /**
     * Attempt to use tag specific technology to really make
     * the tag read-only
     * For NFC Forum Type 1 and 2 only.
     */
    public void makeLowLevelReadonly() {
        throw new UnsupportedOperationException();
    }
}
