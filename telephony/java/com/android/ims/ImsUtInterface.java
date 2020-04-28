/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import android.os.Handler;
import android.os.Message;
import android.telephony.ims.ImsCallForwardInfo;
import android.telephony.ims.ImsSsInfo;

/**
 * Provides APIs for the supplementary service settings using IMS (Ut interface).
 * It is created from 3GPP TS 24.623 (XCAP(XML Configuration Access Protocol)
 * over the Ut interface for manipulating supplementary services).
 *
 * @hide
 */
public interface ImsUtInterface {
    /**
     * Actions
     * @hide
     */
    public static final int ACTION_DEACTIVATION = 0;
    public static final int ACTION_ACTIVATION = 1;
    public static final int ACTION_REGISTRATION = 3;
    public static final int ACTION_ERASURE = 4;
    public static final int ACTION_INTERROGATION = 5;

    /**
     * OIR (Originating Identification Restriction, 3GPP TS 24.607)
     * OIP (Originating Identification Presentation, 3GPP TS 24.607)
     * TIR (Terminating Identification Restriction, 3GPP TS 24.608)
     * TIP (Terminating Identification Presentation, 3GPP TS 24.608)
     */
    public static final int OIR_DEFAULT = 0;    // "user subscription default value"
    public static final int OIR_PRESENTATION_RESTRICTED = 1;
    public static final int OIR_PRESENTATION_NOT_RESTRICTED = 2;

    /**
     * CW (Communication Waiting, 3GPP TS 24.615)
     */

    /**
     * CDIV (Communication Diversion, 3GPP TS 24.604)
     *     actions: target, no reply timer
     */
    public static final int CDIV_CF_UNCONDITIONAL = 0;
    public static final int CDIV_CF_BUSY = 1;
    public static final int CDIV_CF_NO_REPLY = 2;
    public static final int CDIV_CF_NOT_REACHABLE = 3;
    // For CS service code: 002
    public static final int CDIV_CF_ALL = 4;
    // For CS service code: 004
    public static final int CDIV_CF_ALL_CONDITIONAL = 5;
    // It's only supported in the IMS service (CS does not define it).
    // IR.92 recommends that an UE activates both the CFNRc and the CFNL
    // (CDIV using condition not-registered) to the same target.
    public static final int CDIV_CF_NOT_LOGGED_IN = 6;

    /**
     * CB (Communication Barring, 3GPP TS 24.611)
     */
    // Barring of All Incoming Calls
    public static final int CB_BAIC = 1;
    // Barring of All Outgoing Calls
    public static final int CB_BAOC = 2;
    // Barring of Outgoing International Calls
    public static final int CB_BOIC = 3;
    // Barring of Outgoing International Calls - excluding Home Country
    public static final int CB_BOIC_EXHC = 4;
    // Barring of Incoming Calls - when roaming
    public static final int CB_BIC_WR = 5;
    // Barring of Anonymous Communication Rejection (ACR) - a particular case of ICB service
    public static final int CB_BIC_ACR = 6;
    // Barring of All Calls
    public static final int CB_BA_ALL = 7;
    // Barring of Outgoing Services (Service Code 333 - 3GPP TS 22.030 Table B-1)
    public static final int CB_BA_MO = 8;
    // Barring of Incoming Services (Service Code 353 - 3GPP TS 22.030 Table B-1)
    public static final int CB_BA_MT = 9;
    // Barring of Specific Incoming calls
    public static final int CB_BS_MT = 10;

    /**
     * Invalid result value.
     */
    public static final int INVALID = (-1);



    /**
     * Operations for the supplementary service configuration
     */

    /**
     * Retrieves the configuration of the call barring.
     * The return value of ((AsyncResult)result.obj) is an array of {@link ImsSsInfo}.
     */
    public void queryCallBarring(int cbType, Message result);

    /**
     * Retrieves the configuration of the call barring for specified service class.
     * The return value of ((AsyncResult)result.obj) is an array of {@link ImsSsInfo}.
     */
    public void queryCallBarring(int cbType, Message result, int serviceClass);

    /**
     * Retrieves the configuration of the call forward.
     * The return value of ((AsyncResult)result.obj) is an array of {@link ImsCallForwardInfo}.
     */
    public void queryCallForward(int condition, String number, Message result);

    /**
     * Retrieves the configuration of the call waiting.
     * The return value of ((AsyncResult)result.obj) is an array of {@link ImsSsInfo}.
     */
    public void queryCallWaiting(Message result);

    /**
     * Retrieves the default CLIR setting.
     */
    public void queryCLIR(Message result);

    /**
     * Retrieves the CLIP call setting.
     */
    public void queryCLIP(Message result);

    /**
     * Retrieves the COLR call setting.
     */
    public void queryCOLR(Message result);

    /**
     * Retrieves the COLP call setting.
     */
    public void queryCOLP(Message result);

    /**
     * Modifies the configuration of the call barring.
     */
    public void updateCallBarring(int cbType, int action,
            Message result, String[] barrList);

    /**
     * Modifies the configuration of the call barring for specified service class.
     */
    public void updateCallBarring(int cbType, int action, Message result,
            String[] barrList, int serviceClass);

    /**
     * Modifies the configuration of the call forward.
     */
    public void updateCallForward(int action, int condition, String number,
            int serviceClass, int timeSeconds, Message result);

    /**
     * Modifies the configuration of the call waiting.
     */
    public void updateCallWaiting(boolean enable, int serviceClass, Message result);

    /**
     * Updates the configuration of the CLIR supplementary service.
     */
    public void updateCLIR(int clirMode, Message result);

    /**
     * Updates the configuration of the CLIP supplementary service.
     */
    public void updateCLIP(boolean enable, Message result);

    /**
     * Updates the configuration of the COLR supplementary service.
     */
    public void updateCOLR(int presentation, Message result);

    /**
     * Updates the configuration of the COLP supplementary service.
     */
    public void updateCOLP(boolean enable, Message result);

    /**
     * Register for UNSOL_ON_SS indications.
     * @param handler the {@link Handler} that is notified when there is an ss indication.
     * @param event  Supplimentary service indication event.
     * @param Object user object.
     */
    public void registerForSuppServiceIndication(Handler handler, int event, Object object);

    /**
     * Deregister for UNSOL_ON_SS indications.
     * @param handler the {@link Handler} that is notified when there is an ss indication.
     */
    public void unregisterForSuppServiceIndication(Handler handler);
}
