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
 * File            : NativeLlcpServerSocket.java
 * Original-Author : Trusted Logic S.A. (Sylvain Fonteneau)
 * Created         : 18-02-2010
 */

package com.trustedlogic.trustednfc.android.internal;

/**
 * LlcpServiceSocket represents a LLCP Service to be used in a
 * Connection-oriented communication
 * {@hide}
 */

public class NativeLlcpServiceSocket {

	private int mHandle;
	
	private int mLocalMiu;
	
	private int mLocalRw;
	
	private int mLocalLinearBufferLength;
	
	private int mSap;
	
	private int mTimeout;
	
	private String mServiceName;
	
	public NativeLlcpServiceSocket(){
		
	}
	
	public NativeLlcpServiceSocket(String serviceName){
		mServiceName = serviceName;
	}
	
    public native NativeLlcpSocket doAccept(int timeout, int miu, int rw, int linearBufferLength);

    public native boolean doClose();
    
    public int getHandle(){
    	return mHandle;
    }
    
    public void setAcceptTimeout(int timeout){
    	mTimeout = timeout; 
    }
    
    public int getAcceptTimeout(){
    	return mTimeout;
    }
    
    public int getRw(){
    	return mLocalRw;
    }
    
    public int getMiu(){
    	return mLocalMiu;
    }
    
    public int getLinearBufferLength(){
    	return mLocalLinearBufferLength;
    }
}
