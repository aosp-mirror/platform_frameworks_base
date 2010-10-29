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

import java.io.IOException;

import android.os.RemoteException;
import android.util.Log;

/**
 * A connection to an NDEF target on an {@link NdefTag}.
 * <p>You can acquire this kind of connection with {@link NfcAdapter#createNdefTagConnection
 * createNdefTagConnection()}. Use the connection to read or write {@link NdefMessage}s.
 * <p class="note"><strong>Note:</strong>
 * Use of this class requires the {@link android.Manifest.permission#NFC}
 * permission.
 * @hide
 */
public class NdefTagConnection extends RawTagConnection {
    public static final int NDEF_MODE_READ_ONCE = 1;
    public static final int NDEF_MODE_READ_ONLY = 2;
    public static final int NDEF_MODE_WRITE_ONCE = 3;
    public static final int NDEF_MODE_WRITE_MANY = 4;
    public static final int NDEF_MODE_UNKNOWN = 5;

    private static final String TAG = "NFC";

    /**
     * Internal constructor, to be used by NfcAdapter
     * @hide
     */
    /* package private */ NdefTagConnection(NfcAdapter adapter, NdefTag tag, String target) throws RemoteException {
        super(adapter, tag);
        String[] targets = tag.getNdefTargets();
        int i;

        // Check target validity
        for (i=0; i<targets.length; i++) {
            if (target.equals(targets[i])) {
                break;
            }
        }
        if (i >= targets.length) {
            // Target not found
            throw new IllegalArgumentException();
        }
    }

    /**
     * Internal constructor, to be used by NfcAdapter
     * @hide
     */
    /* package private */ NdefTagConnection(NfcAdapter adapter, NdefTag tag) throws RemoteException {
        this(adapter, tag, tag.getNdefTargets()[0]);
    }

    /**
     * Read NDEF message(s).
     * This will always return the most up to date payload, and can block.
     * It can be canceled with {@link RawTagConnection#close}.
     * Most NDEF tags will contain just one NDEF message.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @throws FormatException if the tag is not NDEF formatted
     * @throws IOException if the target is lost or connection closed
     * @throws FormatException
     */
    public NdefMessage[] readNdefMessages() throws IOException, FormatException {
        //TODO(nxp): do not use getLastError(), it is racy
        try {
            NdefMessage[] msgArray = new NdefMessage[1];
            NdefMessage msg = mTagService.read(mTag.mServiceHandle);
            if (msg == null) {
                int errorCode = mTagService.getLastError(mTag.mServiceHandle);
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
            msgArray[0] = msg;
            return msgArray;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return null;
        }
    }

    /**
     * Attempt to write an NDEF message to a tag.
     * This method will block until the data is written. It can be canceled
     * with {@link RawTagConnection#close}.
     * Many tags are write-once, so use this method carefully.
     * Specification allows for multiple NDEF messages per NDEF tag, but it is
     * encourage to only write one message, this so API only takes a single
     * message. Use {@link NdefRecord} to write several records to a single tag.
     * For write-many tags, use {@link #makeReadOnly} after this method to attempt
     * to prevent further modification. For write-once tags this is not
     * necessary.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     *
     * @throws FormatException if the tag is not suitable for NDEF messages
     * @throws IOException if the target is lost or connection closed or the
     *                     write failed
     */
    public void writeNdefMessage(NdefMessage message) throws IOException, FormatException {
        try {
            int errorCode = mTagService.write(mTag.mServiceHandle, message);
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
     * Attempts to make the NDEF data in this tag read-only.
     * This method will block until the action is complete. It can be canceled
     * with {@link RawTagConnection#close}.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @return true if the tag is now read-only
     * @throws IOException if the target is lost, or connection closed
     */
    public boolean makeReadOnly() throws IOException {
        try {
            int errorCode = mTagService.makeReadOnly(mTag.mServiceHandle);
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
     * Read/Write mode hint.
     * Provides a hint if further reads or writes are likely to succeed.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * @return one of NDEF_MODE
     * @throws IOException if the target is lost or connection closed
     */
    public int getModeHint() throws IOException {
        try {
            int result = mTagService.getModeHint(mTag.mServiceHandle);
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
}