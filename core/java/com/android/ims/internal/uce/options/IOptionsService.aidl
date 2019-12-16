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

import com.android.ims.internal.uce.options.IOptionsListener;
import com.android.ims.internal.uce.options.OptionsCapInfo;
import com.android.ims.internal.uce.common.CapInfo;
import com.android.ims.internal.uce.common.StatusCode;
import com.android.ims.internal.uce.common.UceLong;

/** {@hide} */
interface IOptionsService
{

    /**
     * Gets the version of the Options service implementation.
     * the result of this Call is received in getVersionCb
     * @param optionsServiceHandle, received in serviceCreated() of IOptionsListener.
     * @return StatusCode, status of the request placed.
     * @hide
     */
    @UnsupportedAppUsage
    StatusCode getVersion(int optionsServiceHandle);

    /**
     * Adds a listener to the Options service.
     * @param optionsServiceHandle, this returned in serviceCreated() of IOptionsListener.
     * @param optionsListener, IOptionsListener object.
     * @param optionsServiceListenerHdl wrapper for client's listener handle to be stored.
     *
     * The service will fill UceLong.mUceLong with optionsServiceListenerHdl
     * @return StatusCode, status of the request placed.
     */
    @UnsupportedAppUsage
    StatusCode addListener(int optionsServiceHandle, IOptionsListener optionsListener,
                           inout UceLong optionsServiceListenerHdl);

    /**
     * Removes a listener from the Options service.
     * @param optionsServiceHandle, received in serviceCreated() of IOptionsListener.
     * @param optionsListenerHandle, received in serviceCreated() of IOptionsListener.
     * @param optionsServiceListenerHdl provided in createOptionsService() or Addlistener().
     * @return StatusCode, status of the request placed.
     */
    @UnsupportedAppUsage
    StatusCode removeListener(int optionsServiceHandle, in UceLong optionsServiceListenerHdl);

    /**
     * Sets the capabilities information of the self device.
     * The status of the call is received in cmdStatus callback
     * @param optionsServiceHandle, this returned in serviceCreated() of IOptionsListener.
     * @param capInfo, capability information to store.
     * @param reqUserData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    @UnsupportedAppUsage
    StatusCode setMyInfo(int optionsServiceHandle , in CapInfo capInfo, int reqUserData);


    /**
     * Gets the capabilities information of remote device.
     * The Capability information is received in cmdStatus callback
     * @param optionsServiceHandle, this returned in serviceCreated() of IOptionsListener.
     * @param reqUserData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    @UnsupportedAppUsage
    StatusCode getMyInfo(int optionsServiceHandle , int reqUserdata);

    /**
     * Requests the capabilities information of a remote URI.
     * the remote party capability is received in sipResponseReceived() callback.
     * @param optionsServiceHandle, this returned in serviceCreated() of IOptionsListener.
     * @param remoteURI, URI of the remote contact.
     * @param reqUserData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    @UnsupportedAppUsage
    StatusCode getContactCap(int optionsServiceHandle , String remoteURI, int reqUserData);


    /**
     * Requests the capabilities information of specified contacts.
     * For each remote party capability is received in sipResponseReceived() callback
     * @param optionsServiceHandle, this returned in serviceCreated() of IOptionsListener.
     * @param remoteURIList, list of remote contact URI's.
     * @param reqUserData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    @UnsupportedAppUsage
    StatusCode getContactListCap(int optionsServiceHandle, in String[] remoteURIList,
                                 int reqUserData);


    /**
     * Requests the capabilities information of specified contacts.
     * The incoming Options request is received in incomingOptions() callback.
     *
     * @param optionsServiceHandle, this returned in serviceCreated() of IOptionsListener.
     * @param tId, transaction ID received in incomingOptions() call of IOptionsListener.
     * @param sipResponseCode, SIP response code the UE needs to share to network.
     * @param reasonPhrase, response phrase corresponding to the response code.
     * @param capInfo, capabilities to share in the resonse to network.
     * @param bContactInBL, true if the contact is blacklisted, else false.
     * @return StatusCode, status of the request placed.
     */
    @UnsupportedAppUsage
    StatusCode responseIncomingOptions(int optionsServiceHandle,  int tId, int sipResponseCode,
                                       String reasonPhrase, in OptionsCapInfo capInfo,
                                       in boolean bContactInBL);

}
