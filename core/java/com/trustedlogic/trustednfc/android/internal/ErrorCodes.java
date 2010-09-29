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
 * File            : ErrorCodes.java
 * Original-Author : Trusted Logic S.A. (Sylvain Fonteneau)
 * Created         : 26-02-2010
 */

package com.trustedlogic.trustednfc.android.internal;

/**
 * This class defines all the error codes that can be returned by the service
 * and producing an exception on the application level. These are needed since
 * binders does not support exceptions.
 * 
 * @hide
 */
public class ErrorCodes {

    public static boolean isError(int code) {
        if (code < 0) {
            return true;
        } else {
            return false;
        }
    }

    public static final int SUCCESS = 0;
    
    public static final int ERROR_IO = -1;

    public static final int ERROR_CANCELLED = -2;

    public static final int ERROR_TIMEOUT = -3;

    public static final int ERROR_BUSY = -4;

    public static final int ERROR_CONNECT = -5;

    public static final int ERROR_DISCONNECT = -5;

    public static final int ERROR_READ = -6;

    public static final int ERROR_WRITE = -7;

    public static final int ERROR_INVALID_PARAM = -8;
    
    public static final int ERROR_INSUFFICIENT_RESOURCES = -9;
    
    public static final int ERROR_SOCKET_CREATION = -10;
    
    public static final int ERROR_SOCKET_NOT_CONNECTED = -11;
    
    public static final int ERROR_BUFFER_TO_SMALL = -12;

    public static final int ERROR_SAP_USED = -13;
    
    public static final int ERROR_SERVICE_NAME_USED = -14;
    
    public static final int ERROR_SOCKET_OPTIONS = -15;
    
    public static final int ERROR_NFC_ON = -16;
    
    public static final int ERROR_NOT_INITIALIZED = -17;
    
    public static final int ERROR_SE_ALREADY_SELECTED = -18;
    
    public static final int ERROR_SE_CONNECTED = -19;
    
    public static final int ERROR_NO_SE_CONNECTED = -20;
    
    
    
    
    
    
}
