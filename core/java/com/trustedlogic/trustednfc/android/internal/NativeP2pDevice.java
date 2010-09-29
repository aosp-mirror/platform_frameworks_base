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
 * File            : NativeP2pDevice.java
 * Original-Author : Trusted Logic S.A. (Sylvain Fonteneau)
 * Created         : 18-02-2010
 */

package com.trustedlogic.trustednfc.android.internal;

/**
 * Native interface to the P2P Initiator functions
 * 
 * {@hide}
 */
public class NativeP2pDevice {
	
	/**
	* Peer-to-Peer Target.
	*/
	public static final short MODE_P2P_TARGET          = 0x00;

	/**
	* Peer-to-Peer Initiator.
	*/
	public static final short MODE_P2P_INITIATOR       = 0x01;

	/**
	* Invalid target type.
	*/
	public static final short MODE_INVALID			   = 0xff;

	private int mHandle;

	private int mMode;

	private byte[] mGeneralBytes;

	public native byte[] doReceive();

	public native boolean doSend(byte[] data);

	public native boolean doConnect();

	public native boolean doDisconnect();

	public native byte[] doTransceive(byte[] data);
	
	public int getHandle() {
		return mHandle;
	}

	public int getMode() {
		return mMode;
	}

	public byte[] getGeneralBytes() {
		return mGeneralBytes;
	}

}
