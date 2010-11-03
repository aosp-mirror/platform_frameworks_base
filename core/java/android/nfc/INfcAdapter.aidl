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

package android.nfc;

import android.nfc.NdefMessage;
import android.nfc.Tag;
import android.nfc.ILlcpSocket;
import android.nfc.ILlcpServiceSocket;
import android.nfc.ILlcpConnectionlessSocket;
import android.nfc.INfcTag;
import android.nfc.IP2pTarget;
import android.nfc.IP2pInitiator;

/**
 * @hide
 */
interface INfcAdapter
{
    ILlcpSocket getLlcpInterface();
    ILlcpConnectionlessSocket getLlcpConnectionlessInterface();
    ILlcpServiceSocket getLlcpServiceInterface();
    INfcTag getNfcTagInterface();
    IP2pTarget getP2pTargetInterface();
    IP2pInitiator getP2pInitiatorInterface();

    // NfcAdapter-class related methods
    boolean isEnabled();
    void openTagConnection(in Tag tag);

    // Non-public methods
    // TODO: check and complete
    int createLlcpConnectionlessSocket(int sap);
    int createLlcpServiceSocket(int sap, String sn, int miu, int rw, int linearBufferLength);
    int createLlcpSocket(int sap, int miu, int rw, int linearBufferLength);
    int deselectSecureElement();
    boolean disable();
    boolean enable();
    String getProperties(String param);
    int[] getSecureElementList();
    int getSelectedSecureElement();
    int selectSecureElement(int seId);
    int setProperties(String param, String value);
}