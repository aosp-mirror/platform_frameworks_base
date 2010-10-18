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

package com.android.internal.nfc;

import java.io.IOException;

import android.nfc.ErrorCodes;
import android.nfc.IP2pTarget;
import android.os.RemoteException;
import android.util.Log;

/**
 * P2pTarget represents the target in an NFC-IP1 peer-to-peer communication.
 *
 * @see P2pInitiator
 */
public class P2pTarget extends P2pDevice {

    private static final String TAG = "P2pTarget";

	/**
     * The entry point for P2P tag operations.
     */
	private final IP2pTarget mService;

    /**
     * Flag set when the object is closed and thus not usable any more.
     */
	private final boolean isClosed = false;

    /**
     * Flag set when the tag is connected.
     */
	private boolean isConnected = false;

    /**
     * Check if tag is still opened.
     *
     * @return data sent by the P2pInitiator.
     * @throws NfcException if accessing a closed target.
     */
    public void checkState() throws NfcException {
    	if(isClosed) {
    		throw new NfcException("Tag has been closed.");
    	}
    }

    /**
     * Internal constructor for the P2pTarget class.
     *
     * @param handle The handle returned by the NFC service and used to identify
     * 				 the tag in subsequent calls.
     */
    P2pTarget(IP2pTarget service, int handle) {
        this.mService = service;
        this.mHandle = handle;
    }

    /**
     * Connects to the P2pTarget. This shall be called prior to any other
     * operation on the P2pTarget.
     *
     * @throws NfcException
     */
    public void connect() throws NfcException {
    	// Check state
    	checkState();
    	if (isConnected) {
    		throw new NfcException("Already connected");
    	}

    	// Perform connect
        try {
            int result = mService.connect(mHandle);
            if (ErrorCodes.isError(result)) {
                if (result == ErrorCodes.ERROR_IO) {
                    throw new NfcException("Failed to connect");
                }
                else {
      //              TODO(nxp)
     //               throw NfcAdapter.convertErrorToNfcException(result);
                }
            }
            isConnected = true;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in connect(): ", e);
        }
    }

    /**
     * Disconnects from the P2p Target. This must be called so that other
     * targets can be discovered. It restarts the NFC discovery loop.
     *
     * @throws NFCException
     */
    public void disconnect() throws NfcException {
        checkState();
        try {
            mService.disconnect(mHandle);
            isConnected = true;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in disconnect(): ", e);
        }
    }

    /**
     * Exchanges raw data with the P2pTarget.
     *
     * @param data data to be sent to the P2pTarget
     * @return data sent in response by the P2pTarget
     * @throws IOException if the target has been lost or the connection has
     *             been closed.
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
     * Get the General bytes of the connected P2P Target
     *
     * @return general bytes of the connected P2P Target
     * @throws IOException if the target in not in connected state
     */
    @Override
    public byte[] getGeneralBytes() throws IOException {
        try {
            if(isConnected){
                return mService.getGeneralBytes(mHandle);
            }else{
                throw new IOException("Target not in connected state");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getGeneralBytes(): ", e);
            return null;
        }
    }

    @Override
    public int getMode() {
        return P2pDevice.MODE_P2P_TARGET;
    }
}
