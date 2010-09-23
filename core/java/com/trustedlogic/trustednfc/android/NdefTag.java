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
 * File            : NDEFTag.java
 * Original-Author : Trusted Logic S.A. (Jeremie Corbier)
 * Created         : 04-12-2009
 */

package com.trustedlogic.trustednfc.android;

import java.io.IOException;

import android.os.RemoteException;
import android.util.Log;

/**
 * NdefTag represents tags complying with the NFC Forum's NFC Data Exchange
 * Format.
 * 
 * @since AA01.04
 * @hide
 */
public class NdefTag extends NfcTag {

    private static final String TAG = "NdefTag";


    public NdefTag(NfcTag tag){
		super(tag.mService,tag.mHandle);
		this.isConnected = tag.isConnected; 
		this.isClosed = tag.isClosed;
		tag.isClosed = false;
    }
    
    /**
     * Internal constructor for the NfcNdefTag class.
     * 
     * @param service The entry point to the Nfc Service for NfcNdefTag class.
     * @param handle The handle returned by the NFC service and used to identify
     *            the tag in subsequent calls.
     * @hide
     */
    NdefTag(INfcTag service, int handle) {
        super(service, handle);
    }

    /**
     * Read NDEF data from an NDEF tag.
     * 
     * @return the NDEF message read from the tag.
     * @throws NfcException if the tag is not NDEF-formatted.
     * @throws IOException if the target has been lost or the connection has
     *             been closed.
     * @see NdefMessage
     */
    public NdefMessage read() throws NfcException, IOException {
        // Check state
        checkState();
        
        //Check if the tag is Ndef compliant
        if(isNdef != true){
            isNdef = isNdef();
            if(isNdef != true) {
                throw new NfcException("Tag is not NDEF compliant");
            }
        }

        // Perform transceive
        try {
            NdefMessage msg = mService.read(mHandle);
            if (msg == null) {
                throw new IOException("NDEF read failed");
            }
            return msg;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in read(): ", e);
            return null;
        }
    }

    /**
     * Write NDEF data to an NDEF-compliant tag.
     * 
     * @param msg NDEF message to be written to the tag.
     * @throws NfcException if the tag is not NDEF formatted.
     * @throws IOException if the target has been lost or the connection has
     *             been closed.
     * @see NdefMessage
     */
    public void write(NdefMessage msg) throws NfcException, IOException {
        // Check state
        checkState();
        
        //Check if the tag is Ndef compliant
        if(isNdef != true){
            isNdef = isNdef();
            if(isNdef != true) {
                throw new NfcException("Tag is not NDEF compliant");
            }
        }

        // Perform transceive
        try {
            boolean isSuccess = mService.write(mHandle, msg);
            if (!isSuccess) {
                throw new IOException("NDEF write failed");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in write(): ", e);
        }
    }
}
