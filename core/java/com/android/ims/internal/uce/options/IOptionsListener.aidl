/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.ims.internal.uce.options;

import com.android.ims.internal.uce.options.OptionsSipResponse;
import com.android.ims.internal.uce.options.OptionsCapInfo;
import com.android.ims.internal.uce.options.OptionsCmdStatus;
import com.android.ims.internal.uce.common.StatusCode;

/** {@hide} */
interface IOptionsListener
{
    /**
     * Callback invoked with the version information of Options service implementation.
     * @param version, version information of the service.
     * @hide
     */
    @UnsupportedAppUsage
    void getVersionCb(in String version );

    /**
     * Callback function to be invoked by the Options service to notify the listener of service
     * availability.
     * @param statusCode, UCE_SUCCESS as service availability.
     * @hide
     */
    @UnsupportedAppUsage
    void serviceAvailable(in StatusCode statusCode);

    /**
     * Callback function to be invoked by the Options service to notify the listener of service
     * unavailability.
     * @param statusCode, UCE_SUCCESS as service unavailability.
     * @hide
     */
    @UnsupportedAppUsage
    void serviceUnavailable(in StatusCode statusCode);

    /**
     * Callback function to be invoked to inform the client when the response for a SIP OPTIONS
     * has been received.
     * @param uri, URI of the remote entity received in network response.
     * @param sipResponse, data of the network response received.
     * @param capInfo, capabilities of the remote entity received.
     * @hide
     */
    @UnsupportedAppUsage
    void sipResponseReceived( String uri,
                                in OptionsSipResponse sipResponse, in OptionsCapInfo capInfo);

    /**
     * Callback function to be invoked to inform the client of the status of an asynchronous call.
     * @param cmdStatus, command status of the request placed.
     * @hide
     */
    @UnsupportedAppUsage
    void cmdStatus(in OptionsCmdStatus cmdStatus);

    /**
     * Callback function to be invoked to inform the client of an incoming OPTIONS request
     * from the network.
     * @param uri, URI of the remote entity received.
     * @param capInfo, capabilities of the remote entity.
     * @param tID, transation of the request received from network.
     * @hide
     */
    @UnsupportedAppUsage
    void incomingOptions( String uri, in OptionsCapInfo capInfo,
                                            in int tID);
}
