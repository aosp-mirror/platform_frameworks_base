/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.PendingIntent;
import android.os.ServiceManager;

import java.util.List;

public class IccSmsInterfaceManagerProxy extends ISms.Stub {
    private IccSmsInterfaceManager mIccSmsInterfaceManager;

    public IccSmsInterfaceManagerProxy(IccSmsInterfaceManager
            iccSmsInterfaceManager) {
        this.mIccSmsInterfaceManager = iccSmsInterfaceManager;
        if(ServiceManager.getService("isms") == null) {
            ServiceManager.addService("isms", this);
        }
    }

    public void setmIccSmsInterfaceManager(IccSmsInterfaceManager iccSmsInterfaceManager) {
        this.mIccSmsInterfaceManager = iccSmsInterfaceManager;
    }

    public boolean
    updateMessageOnIccEf(int index, int status, byte[] pdu) throws android.os.RemoteException {
         return mIccSmsInterfaceManager.updateMessageOnIccEf(index, status, pdu);
    }

    public boolean copyMessageToIccEf(int status, byte[] pdu,
            byte[] smsc) throws android.os.RemoteException {
        return mIccSmsInterfaceManager.copyMessageToIccEf(status, pdu, smsc);
    }

    public List<SmsRawData> getAllMessagesFromIccEf() throws android.os.RemoteException {
        return mIccSmsInterfaceManager.getAllMessagesFromIccEf();
    }

    public void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mIccSmsInterfaceManager.sendData(destAddr, scAddr, destPort, data,
                sentIntent, deliveryIntent);
    }

    public void sendText(String destAddr, String scAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mIccSmsInterfaceManager.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent);
    }

    public void sendMultipartText(String destAddr, String scAddr,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) throws android.os.RemoteException {
        mIccSmsInterfaceManager.sendMultipartText(destAddr, scAddr,
                parts, sentIntents, deliveryIntents);
    }

    public boolean enableCellBroadcast(int messageIdentifier) throws android.os.RemoteException {
        return mIccSmsInterfaceManager.enableCellBroadcast(messageIdentifier);
    }

    public boolean disableCellBroadcast(int messageIdentifier) throws android.os.RemoteException {
        return mIccSmsInterfaceManager.disableCellBroadcast(messageIdentifier);
    }

    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId)
            throws android.os.RemoteException {
        return mIccSmsInterfaceManager.enableCellBroadcastRange(startMessageId, endMessageId);
    }

    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId)
            throws android.os.RemoteException {
        return mIccSmsInterfaceManager.disableCellBroadcastRange(startMessageId, endMessageId);
    }
}
