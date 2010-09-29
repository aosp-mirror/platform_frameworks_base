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
 * File            : NativeNfcTag.java
 * Original-Author : Trusted Logic S.A. (Sylvain Fonteneau)
 * Created         : 18-02-2010
 */

package com.trustedlogic.trustednfc.android.internal;

/**
 * Native interface to the NFC tag functions
 * 
 * {@hide}
 */
public class NativeNfcTag {
	private int mHandle;

	private String mType;
	
	private byte[] mUid;

	public native boolean doConnect();

	public native boolean doDisconnect();
	
	public native void doAsyncDisconnect();

	public native byte[] doTransceive(byte[] data);

	public native boolean checkNDEF();
	
    public native byte[] doRead();

    public native boolean doWrite(byte[] buf);

	public int getHandle() {
		return mHandle;
	}
	
	public String getType() {
		return mType;
	}
	
	public byte[] getUid() {
		return mUid;
	}
}
