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

import android.nfc.IP2pInitiator;
import android.os.RemoteException;
import android.util.Log;

/**
 * P2pInitiator represents the initiator in an NFC-IP1 peer-to-peer
 * communication.
 *
 * @see P2pTarget
 */
public class P2pInitiator extends P2pDevice {

    private static final String TAG = "P2pInitiator";

	/**
     * The entry point for P2P tag operations.
     */
	private final IP2pInitiator mService;

    /**
     * Internal constructor for the P2pInitiator class.
     *
     * @param handle The handle returned by the NFC service and used to identify
     * 				 the tag in subsequent calls.
     */
    P2pInitiator(IP2pInitiator service, int handle) {
        this.mService = service;
        this.mHandle = handle;
    }

    /**
     * Receives data from a P2pInitiator.
     *
     * @return data sent by the P2pInitiator.
     * @throws IOException if the target has been lost or if the connection has
     *             been closed.
     */
    public byte[] receive() throws IOException {
        try {
        	byte[] result = mService.receive(mHandle);
        	if (result == null) {
        		throw new IOException("Tag has been lost");
        	}
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in receive(): ", e);
            return null;
        }
    }

    /**
     * Sends data to a P2pInitiator.
     *
     * @param data data to be sent to the P2pInitiator.
     * @throws IOException if the target has been lost or if the connection has
     *             been closed.
     */
    public void send(byte[] data) throws IOException {
        try {
        	boolean isSuccess = mService.send(mHandle, data);
        	if (!isSuccess) {
        		throw new IOException("Tag has been lost");
        	}
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in send(): ", e);
        }
    }

    @Override
    public byte[] getGeneralBytes() {
        try {
            return mService.getGeneralBytes(mHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getGeneralBytes(): ", e);
            return null;
        }
    }

    @Override
    public int getMode() {
        return P2pDevice.MODE_P2P_INITIATOR;
    }

}
