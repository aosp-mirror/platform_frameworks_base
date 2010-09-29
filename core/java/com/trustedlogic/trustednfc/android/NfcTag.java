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

/**
 * File            : NFCTag.java
 * Original-Author : Trusted Logic S.A. (Daniel Tomas)
 * Created         : 26-02-2010
 */

package com.trustedlogic.trustednfc.android;

import java.io.IOException;

import android.os.RemoteException;
import android.util.Log;

import com.trustedlogic.trustednfc.android.internal.ErrorCodes;

/**
 * This class represents tags with no known formatting. One can use the method
 * {@link #isNdef()} to determine if the tag can store NDEF-formatted messages.
 * <p>
 * 
 * <pre class="prettyprint">
 * if (tag.isNdef()) {
 *     NdefTag ndefTag = (NdefTag) tag;
 *     NdefMessage msg = ndefTag.read();
 * }
 * </pre>
 * 
 * @since AA01.04
 * @see NdefMessage
 * @hide
 */
public class NfcTag {

    private static final String TAG = "NfcTag";

    /**
     * The handle returned by the NFC service and used to identify the tag in
     * every call of this class.
     * 
     * @hide
     */
    protected int mHandle;

    /**
     * The entry point for tag operations.
     * 
     * @hide
     */
    protected INfcTag mService;

    /**
     * Flag set when the object is closed and thus not usable any more.
     * 
     * @hide
     */
    protected boolean isClosed = false;

    /**
     * Flag set when the tag is connected.
     * 
     * @hide
     */
    protected boolean isConnected = false;
    
    /**
     * Flag set when a check NDEF is performed.
     * 
     * @hide
     */
    protected boolean isNdef = false;

    /**
     * Check if tag is still opened.
     * 
     * @return data sent by the P2pInitiator.
     * @throws NfcException if accessing a closed target.
     * 
     * @hide               
     */
    public void checkState() throws NfcException {
        if (isClosed) {
            throw new NfcException("Tag has been closed.");
        }
        if (!isConnected) {
            throw new NfcException("Tag is not connected.");
        }
    }

    /**
     * Internal constructor for the NfcTag class.
     * 
     * @param service The entry point to the Nfc Service for NfcTag class.
     * @param handle The handle returned by the NFC service and used to identify
     *            the tag in subsequent calls.
     * @hide
     */
    NfcTag(INfcTag service, int handle) {
        this.mService = service;
        this.mHandle = handle;
    }

    /**
     * Connects to the tag. This shall be called prior to any other operation on
     * the tag.
     * 
     * @throws IOException if the tag has been lost or the connection has been
     *             closed.
     * @throws nfcException if the tag is already in connected state.
     */
    public void connect() throws NfcException, IOException {
        // Check state
        if (isClosed) {
            throw new NfcException("Tag has been closed.");
        }
        if (isConnected) {
            throw new NfcException("Already connected");
        }

        // Perform connect
        try {
            int result = mService.connect(mHandle);
            if (ErrorCodes.isError(result)) {
                if (result == ErrorCodes.ERROR_IO) {
                    throw new IOException("Failed to connect");
                }
                else {
                    throw NfcManager.convertErrorToNfcException(result);
                }
            }
            isConnected = true;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in connect(): ", e);
        }
    }

    /**
     * Disconnects from the tag. This must be called so that other targets can
     * be discovered. It restarts the NFC discovery loop.
     * 
     * @throws NfcException if the tag is already in disconnected state or not connected
     */
    public void close() throws NfcException {
        // Check state
        checkState();

        try {
            mService.close(mHandle);
            isClosed = true;
            isConnected = false;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in close(): ", e);
        }
    }

    /**
     * Exchanges raw data with the tag, whatever the tag type.
     * 
     * To exchange APDUs with a ISO14443-4-compliant tag, the data parameter
     * must be filled with the C-APDU (CLA, INS, P1, P2 [, ...]). The returned
     * data consists of the R-APDU ([...,] SW1, SW2).
     * 
     * @param data data to be sent to the tag
     * @return data sent in response by the tag
     * @throws IOException if the tag has been lost or the connection has been
     *             closed.
     * @throws NfcException in case of failure within the stack
     */
    public byte[] transceive(byte[] data) throws IOException, NfcException {
        // Check state
        checkState();

        // Perform transceive
        try {
            byte[] response = mService.transceive(mHandle, data);
            if (response == null) {
                throw new IOException("Transceive failed");
            }
            return response;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in transceive(): ", e);
            return null;
        }
    }

    /**
     * Checks whether tag is NDEF-compliant or not.
     * 
     * @return true if the tag is NDEF-compliant, false otherwise
     * @throws NfcException in case an error occurred when trying to determine
     *             whether the tag is NDEF-compliant
     */
    public boolean isNdef() throws NfcException {
        // Check state
        checkState();

        // Perform Check Ndef
        try {
            isNdef = mService.isNdef(mHandle);
            return isNdef;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in isNdef(): ", e);
            return false;
        }
    }

    /**
     * Returns target type. constants.
     * 
     * @return tag type.
     */
    public String getType() {
        try {
            return mService.getType(mHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getType(): ", e);
            return null;
        }
    }
    
    /**
     * Returns target UID.
     * 
     * @return tag UID.
     */
    public byte[] getUid() {
        try {
            return mService.getUid(mHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getType(): ", e);
            return null;
        }
    }

}
