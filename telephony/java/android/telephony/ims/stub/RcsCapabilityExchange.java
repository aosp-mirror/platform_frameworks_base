/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims.stub;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for different types of Capability exchange, presence using
 * {@link RcsPresenceExchangeImplBase} and SIP OPTIONS exchange using {@link RcsSipOptionsImplBase}.
 *
 * @hide
 */
public class RcsCapabilityExchange {

    /**  Service is unknown. */
    public static final int COMMAND_CODE_SERVICE_UNKNOWN = 0;
    /** The command completed successfully. */
    public static final int COMMAND_CODE_SUCCESS = 1;
    /** The command failed with an unknown error. */
    public static final int COMMAND_CODE_GENERIC_FAILURE = 2;
    /**  Invalid parameter(s). */
    public static final int COMMAND_CODE_INVALID_PARAM = 3;
    /**  Fetch error. */
    public static final int COMMAND_CODE_FETCH_ERROR = 4;
    /**  Request timed out. */
    public static final int COMMAND_CODE_REQUEST_TIMEOUT = 5;
    /**  Failure due to insufficient memory available. */
    public static final int COMMAND_CODE_INSUFFICIENT_MEMORY = 6;
    /**  Network connection is lost. */
    public static final int COMMAND_CODE_LOST_NETWORK_CONNECTION = 7;
    /**  Requested feature/resource is not supported. */
    public static final int COMMAND_CODE_NOT_SUPPORTED = 8;
    /**  Contact or resource is not found. */
    public static final int COMMAND_CODE_NOT_FOUND = 9;
    /**  Service is not available. */
    public static final int COMMAND_CODE_SERVICE_UNAVAILABLE = 10;
    /**  No Change in Capabilities */
    public static final int COMMAND_CODE_NO_CHANGE_IN_CAP = 11;

    /** @hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "COMMAND_CODE_", value = {
            COMMAND_CODE_SERVICE_UNKNOWN,
            COMMAND_CODE_SUCCESS,
            COMMAND_CODE_GENERIC_FAILURE,
            COMMAND_CODE_INVALID_PARAM,
            COMMAND_CODE_FETCH_ERROR,
            COMMAND_CODE_REQUEST_TIMEOUT,
            COMMAND_CODE_INSUFFICIENT_MEMORY,
            COMMAND_CODE_LOST_NETWORK_CONNECTION,
            COMMAND_CODE_NOT_SUPPORTED,
            COMMAND_CODE_NOT_FOUND,
            COMMAND_CODE_SERVICE_UNAVAILABLE,
            COMMAND_CODE_NO_CHANGE_IN_CAP
    })
    public @interface CommandCode {}

    /**
     * Provides the framework with an update as to whether or not a command completed successfully
     * locally. This includes capabilities requests and updates from the network. If it does not
     * complete successfully, then the framework may retry the command again later, depending on the
     * error. If the command does complete successfully, the framework will then wait for network
     * updates.
     *
     * @param code The result of the pending command. If {@link #COMMAND_CODE_SUCCESS}, further
     *             updates will be sent for this command using the associated operationToken.
     * @param operationToken the token associated with the pending command.
     */
    public final void onCommandUpdate(@CommandCode int code, int operationToken) {
        throw new UnsupportedOperationException();
    }
}
