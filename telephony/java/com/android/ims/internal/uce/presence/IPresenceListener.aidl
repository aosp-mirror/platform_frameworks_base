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

package com.android.ims.internal.uce.presence;

import com.android.ims.internal.uce.common.StatusCode;
import com.android.ims.internal.uce.presence.PresPublishTriggerType;
import com.android.ims.internal.uce.presence.PresCmdStatus;
import com.android.ims.internal.uce.presence.PresCapInfo;
import com.android.ims.internal.uce.presence.PresSipResponse;
import com.android.ims.internal.uce.presence.PresTupleInfo;
import com.android.ims.internal.uce.presence.PresResInstanceInfo;
import com.android.ims.internal.uce.presence.PresResInfo;
import com.android.ims.internal.uce.presence.PresRlmiInfo;


/**
 * IPresenceListener
 * {@hide} */
interface IPresenceListener
{
    /**
     * Gets the version of the presence listener implementation.
     * @param version, version information.
     */
    void getVersionCb(in String version );

    /**
     * Callback function to be invoked by the Presence service to notify the listener of service
     * availability.
     * @param statusCode, UCE_SUCCESS as service availability.
     */
    void serviceAvailable(in StatusCode statusCode);

    /**
     * Callback function to be invoked by the Presence service to notify the listener of service
     * unavailability.
     * @param statusCode, UCE_SUCCESS as service unAvailability.
     */
    void serviceUnAvailable(in StatusCode statusCode);

    /**
     * Callback function to be invoked by the Presence service to notify the listener to send a
     * publish request.
     * @param publishTrigger, Publish trigger for the network being supported.
     */
    void publishTriggering(in PresPublishTriggerType publishTrigger);

    /**
     * Callback function to be invoked to inform the client of the status of an asynchronous call.
     * @param cmdStatus, command status of the request placed.
     */
    void cmdStatus( in PresCmdStatus cmdStatus);

    /**
     * Callback function to be invoked to inform the client when the response for a SIP message,
     * such as PUBLISH or SUBSCRIBE, has been received.
     * @param sipResponse, network response received for the request placed.
     */
    void sipResponseReceived(in PresSipResponse sipResponse);

    /**
     * Callback function to be invoked to inform the client when the NOTIFY message carrying a
     * single contact's capabilities information is received.
     * @param presentityURI, URI of the remote entity the request was placed.
     * @param tupleInfo, array of capability information remote entity supports.
     */
    void capInfoReceived(in String presentityURI,
                         in PresTupleInfo [] tupleInfo);

    /**
     * Callback function to be invoked to inform the client when the NOTIFY message carrying
     * contact's capabilities information is received.
     * @param rlmiInfo, resource infomation received from network.
     * @param resInfo, array of capabilities received from network for the list of  remore URI.
     */
    void listCapInfoReceived(in PresRlmiInfo rlmiInfo,
                             in PresResInfo [] resInfo);

    /**
     * Callback function to be invoked to inform the client when Unpublish message
     * is sent to network.
     */
    void unpublishMessageSent();

}