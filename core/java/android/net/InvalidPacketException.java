/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Thrown when a packet is invalid.
 * @hide
 */
@SystemApi
public class InvalidPacketException extends Exception {
    private final int mError;

    // Must match SocketKeepalive#ERROR_INVALID_IP_ADDRESS.
    /** Invalid IP address. */
    public static final int ERROR_INVALID_IP_ADDRESS = -21;

    // Must match SocketKeepalive#ERROR_INVALID_PORT.
    /** Invalid port number. */
    public static final int ERROR_INVALID_PORT = -22;

    // Must match SocketKeepalive#ERROR_INVALID_LENGTH.
    /** Invalid packet length. */
    public static final int ERROR_INVALID_LENGTH = -23;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ERROR_" }, value = {
        ERROR_INVALID_IP_ADDRESS,
        ERROR_INVALID_PORT,
        ERROR_INVALID_LENGTH
    })
    public @interface ErrorCode {}

    /**
     * This packet is invalid.
     * See the error code for details.
     */
    public InvalidPacketException(@ErrorCode final int error) {
        this.mError = error;
    }

    /** Get error code. */
    public int getError() {
        return mError;
    }
}
