/*
 * Copyright (C) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.nfc;

import android.annotation.UnsupportedAppUsage;

/**
 * This class defines all the error codes that can be returned by the service
 * and producing an exception on the application level. These are needed since
 * binders does not support exceptions.
 *
 * @hide
 */
public class ErrorCodes {

    @UnsupportedAppUsage
    public static boolean isError(int code) {
        if (code < 0) {
            return true;
        } else {
            return false;
        }
    }

    public static String asString(int code) {
        switch (code) {
            case SUCCESS: return "SUCCESS";
            case ERROR_IO: return "IO";
            case ERROR_CANCELLED: return "CANCELLED";
            case ERROR_TIMEOUT: return "TIMEOUT";
            case ERROR_BUSY: return "BUSY";
            case ERROR_CONNECT: return "CONNECT/DISCONNECT";
//            case ERROR_DISCONNECT: return "DISCONNECT";
            case ERROR_READ: return "READ";
            case ERROR_WRITE: return "WRITE";
            case ERROR_INVALID_PARAM: return "INVALID_PARAM";
            case ERROR_INSUFFICIENT_RESOURCES: return "INSUFFICIENT_RESOURCES";
            case ERROR_SOCKET_CREATION: return "SOCKET_CREATION";
            case ERROR_SOCKET_NOT_CONNECTED: return "SOCKET_NOT_CONNECTED";
            case ERROR_BUFFER_TO_SMALL: return "BUFFER_TO_SMALL";
            case ERROR_SAP_USED: return "SAP_USED";
            case ERROR_SERVICE_NAME_USED: return "SERVICE_NAME_USED";
            case ERROR_SOCKET_OPTIONS: return "SOCKET_OPTIONS";
            case ERROR_NFC_ON: return "NFC_ON";
            case ERROR_NOT_INITIALIZED: return "NOT_INITIALIZED";
            case ERROR_SE_ALREADY_SELECTED: return "SE_ALREADY_SELECTED";
            case ERROR_SE_CONNECTED: return "SE_CONNECTED";
            case ERROR_NO_SE_CONNECTED: return "NO_SE_CONNECTED";
            case ERROR_NOT_SUPPORTED: return "NOT_SUPPORTED";
            default: return "UNKNOWN ERROR";
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

    public static final int ERROR_NOT_SUPPORTED = -21;

}
