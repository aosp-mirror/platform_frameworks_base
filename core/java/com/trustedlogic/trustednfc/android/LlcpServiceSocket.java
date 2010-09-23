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
 * File            : LLCPServerSocket.java
 * Original-Author : Trusted Logic S.A. (Daniel Tomas)
 * Created         : 18-02-2010
 */

package com.trustedlogic.trustednfc.android;

import java.io.IOException;

import com.trustedlogic.trustednfc.android.internal.ErrorCodes;

import android.os.RemoteException;
import android.util.Log;

/**
 * LlcpServiceSocket represents a LLCP Service to be used in a
 * Connection-oriented communication
 * 
 * @since AA02.01
 * @hide
 */
public class LlcpServiceSocket {

	private static final String TAG = "LlcpServiceSocket";

	/**
	 * The handle returned by the NFC service and used to identify the LLCP
	 * Service socket in every call of this class.
	 * 
	 * @hide
	 */
	protected int mHandle;

	/**
	 * The entry point for LLCP Service socket operations.
	 * 
	 * @hide
	 */
	protected ILlcpServiceSocket mService;
	
    private ILlcpSocket mLlcpSocketService;

	static LlcpException convertErrorToLlcpException(int errorCode) {
		return convertErrorToLlcpException(errorCode, null);
	}

	static LlcpException convertErrorToLlcpException(int errorCode,
			String message) {
		if (message == null) {
			message = "";
		} else {
			message = " (" + message + ")";
		}

		switch (errorCode) {
		case ErrorCodes.ERROR_SOCKET_CREATION:
			return new LlcpException(
					"Error during the creation of an Llcp socket" + message);
		case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
			return new LlcpException("Not enough ressources are available"
					+ message);
		default:
			return new LlcpException("Unkown error code " + errorCode + message);
		}
	}

	/**
	 * Internal constructor for the LlcpServiceSocket class.
	 * 
	 * @param service
	 *            The entry point to the Nfc Service for LlcpServiceSocket
	 *            class.
	 * @param handle
	 *            The handle returned by the NFC service and used to identify
	 *            the socket in subsequent calls.
	 * @hide
	 */
	LlcpServiceSocket(ILlcpServiceSocket service, ILlcpSocket socketService, int handle) {
		this.mService = service;
		this.mHandle = handle;
		this.mLlcpSocketService = socketService;
	}

	/**
	 * Wait for incomming connection request from a LLCP client and accept this
	 * request
	 * 
	 * @return socket object to be used to communicate with a LLCP client
	 * 
	 * @throws IOException
	 *             if the llcp link is lost or deactivated
	 * @throws LlcpException
	 *             if not enough ressources are available
	 * 
	 * @see LlcpSocket
	 * @since AA02.01
	 */
	public LlcpSocket accept() throws IOException, LlcpException {

		try {
			int handle = mService.accept(mHandle);
			// Handle potential errors
			if (ErrorCodes.isError(handle)) {
				if (handle == ErrorCodes.ERROR_IO) {
					throw new IOException();
				} else {
					throw convertErrorToLlcpException(handle);
				}
			}
	        
			// Build the public LlcpSocket object
			return new LlcpSocket(mLlcpSocketService, handle);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in accept(): ", e);
			return null;
		}

	}

	/**
	 * Set the timeout for the accept request
	 * 
	 * @param timeout
	 *            value of the timeout for the accept request
	 * @since AA02.01
	 */
	public void setAcceptTimeout(int timeout) {
		try {
			mService.setAcceptTimeout(mHandle, timeout);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in setAcceptTimeout(): ", e);
		}
	}

	/**
	 * Get the timeout value of the accept request
	 * 
	 * @return mTimeout
	 * @since AA02.01
	 */
	public int getAcceptTimeout() {
		try {
			return mService.getAcceptTimeout(mHandle);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in getAcceptTimeout(): ", e);
			return 0;
		}
	}

	/**
	 * Close the created Llcp Service socket
	 * 
	 * @since AA02.01
	 */
	public void close() {
		try {
			mService.close(mHandle);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in close(): ", e);
		}
	}

}
