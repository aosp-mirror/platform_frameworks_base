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
 * File            : NativeLlcpConnectionLessSocket.java
 * Original-Author : Trusted Logic S.A. (Sylvain Fonteneau)
 * Created         : 18-02-2010
 */

package com.trustedlogic.trustednfc.android.internal;

import com.trustedlogic.trustednfc.android.LlcpPacket;

/**
 * LlcpConnectionlessSocket represents a LLCP Connectionless object to be used
 * in a connectionless communication
 * 
 * @since AA02.01
 * {@hide}
 */

public class NativeLlcpConnectionlessSocket {
	
	private int mHandle;
	
	private int mSap;
	
	private int mLinkMiu;
	
	public NativeLlcpConnectionlessSocket(){;
	}
	
	public NativeLlcpConnectionlessSocket(int sap){
		mSap = sap;
	}
	
    public native boolean doSendTo(int sap, byte[] data);

    public native LlcpPacket doReceiveFrom(int linkMiu);

    public native boolean doClose();
    
    public int getLinkMiu(){
    	return mLinkMiu;
    }
    
    public int getSap(){
    	return mSap;
    }
    
    public int getHandle(){
    	return mHandle;
    }

}
