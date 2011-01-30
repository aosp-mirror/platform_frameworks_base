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
import android.nfc.ILlcpSocket;
import android.os.RemoteException;
import android.util.Log;

/**
 * LlcpClientSocket represents a LLCP Connection-Oriented client to be used in a
 * connection-oriented communication
 */
public class LlcpSocket {

	private static final String TAG = "LlcpSocket";

	/**
	 * The handle returned by the NFC service and used to identify the LLCP
	 * socket in every call of this class.
	 */
	protected int mHandle;

	/**
	 * The entry point for LLCP socket operations.
	 */
	protected ILlcpSocket mService;

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
		case ErrorCodes.ERROR_SOCKET_NOT_CONNECTED:
			return new LlcpException("Socket not connected to an Llcp Service"
					+ message);
		default:
			return new LlcpException("Unkown error code " + errorCode + message);
		}
	}

	/**
	 * Internal constructor for the LlcpSocket class.
	 *
	 * @param service
	 *            The entry point to the Nfc Service for LlcpServiceSocket
	 *            class.
	 * @param handle
	 *            The handle returned by the NFC service and used to identify
	 *            the socket in subsequent calls.
	 * @hide
	 */
	public LlcpSocket(ILlcpSocket service, int handle) {
		this.mService = service;
		this.mHandle = handle;
	}

	/**
	 * Connect request to a specific LLCP Service by its SAP.
	 *
	 * @param sap
	 *            Service Access Point number of the LLCP Service
	 * @throws IOException
	 *             if the LLCP has been lost or deactivated.
	 * @throws LlcpException
	 *             if the connection request is rejected by the remote LLCP
	 *             Service
	 */
	public void connect(int sap) throws IOException, LlcpException {
		try {
			int result = mService.connect(mHandle, sap);
			// Handle potential errors
			if (ErrorCodes.isError(result)) {
				if (result == ErrorCodes.ERROR_IO) {
					throw new IOException();
				} else {
					throw convertErrorToLlcpException(result);
				}
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in accept(): ", e);
		}
	}

	/**
	 * Connect request to a specific LLCP Service by its Service Name.
	 *
	 * @param sn
	 *            Service Name of the LLCP Service
	 * @throws IOException
	 *             if the LLCP has been lost or deactivated.
	 * @throws LlcpException
	 *             if the connection request is rejected by the remote LLCP
	 *             Service
	 */
	public void connect(String sn) throws IOException, LlcpException {
		try {
			int result = mService.connectByName(mHandle, sn);
			// Handle potential errors
			if (ErrorCodes.isError(result)) {
				if (result == ErrorCodes.ERROR_IO) {
					throw new IOException();
				} else {
					throw convertErrorToLlcpException(result);
				}
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in accept(): ", e);
		}
	}

	/**
	 * Disconnect request to the connected LLCP socket and close the created
	 * socket.
	 *
	 * @throws IOException
	 *             if the LLCP has been lost or deactivated.
	 */
	public void close() throws IOException {
		try {
			int result = mService.close(mHandle);
			// Handle potential errors
			if (ErrorCodes.isError(result)) {
				throw new IOException();
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in close(): ", e);
		}
	}

	/**
	 * Send data to the connected LLCP Socket.
	 *
	 * @throws IOException
	 *             if the LLCP has been lost or deactivated.
	 */
	public void send(byte[] data) throws IOException {
		try {
			int result = mService.send(mHandle, data);
			// Handle potential errors
			if (ErrorCodes.isError(result)) {
				throw new IOException();
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in send(): ", e);
		}
	}

	/**
	 * Receive data from the connected LLCP socket
	 *
	 * @param receiveBuffer
	 *            a buffer for the received data
	 * @return length length of the data received
	 * @throws IOException
	 *             if the LLCP has been lost or deactivated.
	 */
	public int receive(byte[] receiveBuffer) throws IOException {
		int receivedLength = 0;
		try {
			receivedLength = mService.receive(mHandle, receiveBuffer);
			if(receivedLength == 0){
				throw new IOException();
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in receive(): ", e);
		}

		return receivedLength;
	}

	/**
	 * Returns the local Service Access Point number of the socket
	 *
	 * @return localSap
	 */
	public int getLocalSap() {
		try {
			return  mService.getLocalSap(mHandle);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in getLocalSap(): ", e);
			return 0;
		}
	}

	/**
	 * Returns the local Maximum Information Unit(MIU) of the socket
	 *
	 * @return miu
	 */
	public int getLocalSocketMiu() {
		try {
			return  mService.getLocalSocketMiu(mHandle);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in getLocalSocketMiu(): ", e);
			return 0;
		}
	}

	/**
	 * Returns the local Receive Window(RW) of the socket
	 *
	 * @return rw
	 */
	public int getLocalSocketRw() {
		try {
			return  mService.getLocalSocketRw(mHandle);
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in getLocalSocketRw(): ", e);
			return 0;
		}
	}

	/**
	 * Returns the remote Maximum Information Unit(MIU) of the socket.
	 * <p>
	 * This method must be called when the socket is in CONNECTED_STATE
	 *
	 * @return remoteMiu
	 * @throws LlcpException
	 *             if the LlcpClientSocket is not in a CONNECTED_STATE
	 */
	public int getRemoteSocketMiu() throws LlcpException {
		try {
			int result = mService.getRemoteSocketMiu(mHandle);
			if(result != ErrorCodes.ERROR_SOCKET_NOT_CONNECTED){
				return result;
			}else{
				throw convertErrorToLlcpException(result);
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in getRemoteSocketMiu(): ", e);
			return 0;
		}
	}

	/**
	 * Returns the remote Receive Window(RW) of the connected remote socket.
	 * <p>
	 * This method must be called when the socket is in CONNECTED_STATE
	 *
	 * @return rw
	 * @throws LlcpException
	 *             if the LlcpClientSocket is not in a CONNECTED_STATE
	 */
	public int getRemoteSocketRw() throws LlcpException {
		try {
			int result = mService.getRemoteSocketRw(mHandle);
			if( result != ErrorCodes.ERROR_SOCKET_NOT_CONNECTED){
				return result;
			}else{
				throw convertErrorToLlcpException(result);
			}
		} catch (RemoteException e) {
			Log.e(TAG, "RemoteException in getRemoteSocketRw(): ", e);
			return 0;
		}
	}
}
