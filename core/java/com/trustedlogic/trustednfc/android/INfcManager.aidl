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

package com.trustedlogic.trustednfc.android;

import com.trustedlogic.trustednfc.android.ILlcpSocket;
import com.trustedlogic.trustednfc.android.ILlcpServiceSocket;
import com.trustedlogic.trustednfc.android.ILlcpConnectionlessSocket;
import com.trustedlogic.trustednfc.android.INfcTag;
import com.trustedlogic.trustednfc.android.IP2pTarget;
import com.trustedlogic.trustednfc.android.IP2pInitiator;


/**
 * Interface that allows controlling NFC activity.
 *
 * {@hide}
 */
interface INfcManager
{

    ILlcpSocket                 getLlcpInterface();
    ILlcpConnectionlessSocket   getLlcpConnectionlessInterface();
    ILlcpServiceSocket          getLlcpServiceInterface();
    INfcTag                     getNfcTagInterface();
    IP2pTarget                  getP2pTargetInterface();
    IP2pInitiator               getP2pInitiatorInterface();
    
    void    cancel();
    int     createLlcpConnectionlessSocket(int sap);
    int     createLlcpServiceSocket(int sap, String sn, int miu, int rw, int linearBufferLength);
    int     createLlcpSocket(int sap, int miu, int rw, int linearBufferLength);
    int     deselectSecureElement();
    boolean disable();
    boolean enable();
    int     getOpenTimeout();
    String  getProperties(String param);
    int[]   getSecureElementList();
    int     getSelectedSecureElement();
    boolean isEnabled();
    int     openP2pConnection();
    int     openTagConnection();
    int     selectSecureElement(int seId);
    void    setOpenTimeout(int timeout);
    int     setProperties(String param, String value);

}

