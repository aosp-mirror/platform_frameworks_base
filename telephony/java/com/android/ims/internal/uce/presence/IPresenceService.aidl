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

import com.android.ims.internal.uce.presence.IPresenceListener;
import com.android.ims.internal.uce.presence.PresCapInfo;
import com.android.ims.internal.uce.presence.PresServiceInfo;
import com.android.ims.internal.uce.common.UceLong;
import com.android.ims.internal.uce.common.StatusCode;

/** IPresenceService
{@hide} */
interface IPresenceService
{

    /**
     * Gets the version of the Presence service implementation.
     * The verion information is received in getVersionCb callback.
     * @param presenceServiceHdl returned in createPresenceService().
     * @return StatusCode, status of the request placed.
     */
    StatusCode getVersion(int presenceServiceHdl);

    /**
     * Adds a listener to the Presence service.
     * @param presenceServiceHdl returned in createPresenceService().
     * @param presenceServiceListener IPresenceListener Object.
     * @param presenceServiceListenerHdl wrapper for client's listener handle to be stored.
     *
     * The service will fill UceLong.mUceLong with presenceListenerHandle.
     *
     * @return StatusCode, status of the request placed
     */
    StatusCode addListener(int presenceServiceHdl, IPresenceListener presenceServiceListener,
                           inout UceLong presenceServiceListenerHdl);

    /**
     * Removes a listener from the Presence service.
     * @param presenceServiceHdl returned in createPresenceService().
     * @param presenceServiceListenerHdl provided in createPresenceService() or Addlistener().
     * @return StatusCode, status of the request placed.
     */
    StatusCode removeListener(int presenceServiceHdl, in UceLong presenceServiceListenerHdl);

    /**
     * Re-enables the Presence service if it is in the Blocked state due to receiving a SIP
     * response 489 Bad event.
     * The application must call this API before calling any presence API after receiving a SIP
     * response 489 Bad event.
     * The status of this request is notified in cmdStatus callback.
     *
     * @param presenceServiceHdl returned in createPresenceService().
     * @param userData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    StatusCode reenableService(int presenceServiceHdl, int userData);

    /**
     * Sends a request to publish current device capabilities.
     * The network response is notifed in sipResponseReceived() callback.
     * @param presenceServiceHdl returned in createPresenceService().
     * @param myCapInfo PresCapInfo object.
     * @param userData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    StatusCode publishMyCap(int presenceServiceHdl, in PresCapInfo myCapInfo , int userData);

    /**
     * Retrieves the capability information for a single contact. Clients receive the requested
     * information via the listener callback function capInfoReceived() callback.
     *
     * @param presenceServiceHdl returned in createPresenceService().
     * @param remoteUri remote contact URI
     * @param userData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    StatusCode getContactCap(int presenceServiceHdl , String remoteUri, int userData);

    /**
     * Retrieves the capability information for a list of contacts. Clients receive the requested
     * information via the listener callback function listCapInfoReceived() callback.
     *
     * @param presenceServiceHdl returned in createPresenceService().
     * @param remoteUriList list of remote contact URI's.
     * @param userData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    StatusCode getContactListCap(int presenceServiceHdl, in String[] remoteUriList, int userData);

    /**
     * Sets the mapping between a new feature tag and the corresponding service tuple information
     * to be included in the published document.
     * The staus of this call is received in cmdStatus callback.
     *
     * @param presenceServiceHdl returned in createPresenceService().
     * @param featureTag to be supported
     * @param PresServiceInfo service information describing the featureTag.
     * @param userData, userData provided by client to identify the request/API call, it
     *                  is returned in the cmdStatus() callback for client to match response
     *                  with original request.
     * @return StatusCode, status of the request placed.
     */
    StatusCode  setNewFeatureTag(int presenceServiceHdl, String featureTag,
                                 in PresServiceInfo serviceInfo, int userData);

}
