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
import android.nfc.ILlcpConnectionlessSocket;
import android.nfc.LlcpPacket;
import android.os.RemoteException;
import android.util.Log;

public class LlcpConnectionlessSocket {
    private static final String TAG = "LlcpConnectionlessSocket";

    /**
     * The handle returned by the NFC service and used to identify the LLCP connectionless socket in
     * every call of this class.
     */
    protected int mHandle;


    /**
     * The entry point for LLCP Connectionless socket operations.
     */
    protected ILlcpConnectionlessSocket mService;


    /**
     * Internal constructor for the LlcpConnectionlessSocket class.
     *
     * @param service The entry point to the Nfc Service for  LLCP Connectionless socket  class.
     * @param handle The handle returned by the NFC service and used to identify
     *            the socket in subsequent calls.
     */
	LlcpConnectionlessSocket(ILlcpConnectionlessSocket service, int handle) {
        this.mService = service;
        this.mHandle = handle;
    }

    /**
     * Send data to a specific LLCP Connectionless client
     *
     * @param packet Service Access Point number related to a LLCP
     *            Connectionless client and a data buffer to send
     * @throws IOException if the LLCP link has been lost or deactivated.
     */
    public void sendTo(LlcpPacket packet) throws IOException {
		try {
			int result = mService.sendTo(mHandle, packet);
			// Handle potential errors
			if (ErrorCodes.isError(result)) {
				throw new IOException();
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in sendTo(): ", e);
		}
    }

    /**
     * Receive data from a LLCP Connectionless client
     *
     * @return data data received from a specific LLCP Connectionless client
     * @throws IOException if the LLCP link has been lost or deactivated.
     * @see LlcpPacket
     */
    public LlcpPacket receiveFrom() throws IOException {
		try {
			LlcpPacket packet = mService.receiveFrom(mHandle);
			if (packet != null) {
				return packet;
			}else{
				// Handle potential errors
				throw new IOException();
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in receiveFrom(): ", e);
		}
        return null;
    }

    /**
     * Close the created Connectionless socket.
     */
    public void close() {
		try {
			mService.close(mHandle);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in close(): ", e);
		}
    }

    /**
     * Returns the local Service Access Point number of the socket
     *
     * @return sap
     */
    public int getSap() {
    	int sap = 0;

    	try {
			sap = mService.getSap(mHandle);

		} catch (RemoteException e) {

			e.printStackTrace();
		}
    	return sap;
    }
}
