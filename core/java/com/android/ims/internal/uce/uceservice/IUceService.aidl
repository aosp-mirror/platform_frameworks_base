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

package com.android.ims.internal.uce.uceservice;

import com.android.ims.internal.uce.uceservice.IUceListener;
import com.android.ims.internal.uce.presence.IPresenceService;
import com.android.ims.internal.uce.options.IOptionsListener;
import com.android.ims.internal.uce.presence.IPresenceListener;
import com.android.ims.internal.uce.options.IOptionsService;
import com.android.ims.internal.uce.common.UceLong;
import com.android.ims.internal.uce.common.StatusCode;

/** IUceService
 *  UCE service interface class.
 * {@hide} */
interface IUceService
{

    /**
     * Starts the Uce service.
     * @param uceListener IUceListener object
     * @return boolean true if the service stop start is processed successfully, FALSE otherwise.
     *
     * Service status is returned in setStatus callback in IUceListener.
     * @hide
     */
    @UnsupportedAppUsage
    boolean startService(IUceListener uceListener);

    /**
     * Stops the UCE service.
     * @return boolean true if the service stop request is processed successfully, FALSE otherwise.
     * @hide
     */
    @UnsupportedAppUsage
    boolean stopService();



    /**
     * Requests the UCE service start status.
     * @return boolean true if service started else false.
     * @hide
     */
    @UnsupportedAppUsage
    boolean isServiceStarted();

    /**
     * Creates a options service for Capability Discovery.
     * @param optionsListener IOptionsListener object.
     * @param optionsServiceListenerHdl wrapper for client's listener handle to be stored.
     *
     * The service will fill UceLong.mUceLong with presenceListenerHandle allocated and
     * used to validate callbacks received in IPresenceListener are indeed from the
     * service the client created.
     *
     * @return  optionsServiceHandle
     *
     * @hide
     *
     * @deprecated This is replaced with new API createOptionsServiceForSubscription()
     */
    @UnsupportedAppUsage
    int createOptionsService(IOptionsListener optionsListener,
                             inout UceLong optionsServiceListenerHdl);
    /**
     * Creates a options service for Capability Discovery.
     * @param optionsListener IOptionsListener object.
     * @param optionsServiceListenerHdl wrapper for client's listener handle to be stored.
     * @param iccId the ICC-ID derived from SubscriptionInfo for the Service requested
     *
     * The service will fill UceLong.mUceLong with presenceListenerHandle allocated and
     * used to validate callbacks received in IPresenceListener are indeed from the
     * service the client created.
     *
     * @return  optionsServiceHandle
     *
     * @hide
     */
    int createOptionsServiceForSubscription(IOptionsListener optionsListener,
                             inout UceLong optionsServiceListenerHdl,
                             in String iccId);

    /**
     * Destroys a Options service.
     * @param optionsServiceHandle this is received in serviceCreated() callback
     *        in IOptionsListener
     * @hide
     */
    @UnsupportedAppUsage
    void destroyOptionsService(int optionsServiceHandle);

    /**
     * Creates a presence service.
     * @param presenceServiceListener IPresenceListener object
     * @param presenceServiceListenerHdl wrapper for client's listener handle to be stored.
     *
     * The service will fill UceLong.mUceLong with presenceListenerHandle allocated and
     * used to validate callbacks received in IPresenceListener are indeed from the
     * service the client created.
     *
     * @return  presenceServiceHdl
     *
     * @hide
     *
     * @deprecated This is replaced with new API createPresenceServiceForSubscription()
     */
    @UnsupportedAppUsage
    int createPresenceService(IPresenceListener presenceServiceListener,
                              inout UceLong presenceServiceListenerHdl);
    /**
     * Creates a presence service.
     * @param presenceServiceListener IPresenceListener object
     * @param presenceServiceListenerHdl wrapper for client's listener handle to be stored.
     * @param iccId the ICC-ID derived from SubscriptionInfo for the Service requested
     *
     * The service will fill UceLong.mUceLong with presenceListenerHandle allocated and
     * used to validate callbacks received in IPresenceListener are indeed from the
     * service the client created.
     *
     * @return  presenceServiceHdl
     *
     * @hide
     */
    int createPresenceServiceForSubscription(IPresenceListener presenceServiceListener,
                              inout UceLong presenceServiceListenerHdl,
                              in String iccId);

    /**
     * Destroys a presence service.
     *
     * @param presenceServiceHdl handle returned during createPresenceService()
     *
     * @hide
     */
    @UnsupportedAppUsage
    void destroyPresenceService(int presenceServiceHdl);



    /**
     * Query the UCE Service for information to know whether the is registered.
     *
     * @return boolean, true if Registered to for network events else false.
     *
     * @hide
     */
    @UnsupportedAppUsage
    boolean getServiceStatus();

    /**
     * Query the UCE Service for presence Service.
     *
     * @return IPresenceService object.
     *
     * @hide
     *
     * @deprecated use API getPresenceServiceForSubscription()
     */
    @UnsupportedAppUsage
    IPresenceService getPresenceService();

    /**
     * Query the UCE Service for presence Service.
     *
     * @param iccId the ICC-ID derived from SubscriptionInfo for the Service requested
     *
     * @return IPresenceService object.
     *
     * @hide
     */
    IPresenceService getPresenceServiceForSubscription(in String iccId);

    /**
     * Query the UCE Service for options service object.
     *
     * @return IOptionsService object.
     *
     * @deprecated use API getOptionsServiceForSubscription()
     *
     * @hide
     */
    @UnsupportedAppUsage
    IOptionsService getOptionsService();

    /**
     * Query the UCE Service for options service object.
     *
     * @param iccId the ICC-ID derived from SubscriptionInfo for the Service requested
     *
     * @return IOptionsService object.
     *
     * @hide
     */
    IOptionsService getOptionsServiceForSubscription(in String iccId);

}
