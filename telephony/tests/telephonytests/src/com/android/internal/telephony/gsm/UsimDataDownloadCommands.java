/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.telephony.BaseCommands;
import com.android.internal.telephony.IccIoResult;
import com.android.internal.telephony.UUSInfo;

import junit.framework.Assert;

/**
 * Dummy BaseCommands for UsimDataDownloadTest. Only implements UICC envelope and
 * SMS acknowledgement commands.
 */
class UsimDataDownloadCommands extends BaseCommands {
    private static final String TAG = "UsimDataDownloadCommands";

    private boolean mExpectingAcknowledgeGsmSms;        // true if expecting ack GSM SMS
    private boolean mExpectingAcknowledgeGsmSmsSuccess; // true if expecting ack SMS success
    private int mExpectingAcknowledgeGsmSmsFailureCause;    // expecting ack SMS failure cause
    private String mExpectingAcknowledgeGsmSmsPdu;          // expecting ack SMS PDU

    private boolean mExpectingSendEnvelope;         // true to expect a send envelope command
    private String mExpectingSendEnvelopeContents;  // expected string for send envelope
    private int mExpectingSendEnvelopeResponseSw1;  // SW1/SW2 response status
    private int mExpectingSendEnvelopeResponseSw2;  // SW1/SW2 response status
    private String mExpectingSendEnvelopeResponse;  // Response string for Send Envelope

    UsimDataDownloadCommands(Context context) {
        super(context);
    }

    /**
     * Expect a call to acknowledgeLastIncomingGsmSms with success flag and failure cause.
     * @param success true if expecting success; false if expecting failure
     * @param cause the failure cause, if success is false
     */
    synchronized void expectAcknowledgeGsmSms(boolean success, int cause) {
        Assert.assertFalse("expectAcknowledgeGsmSms called twice", mExpectingAcknowledgeGsmSms);
        mExpectingAcknowledgeGsmSms = true;
        mExpectingAcknowledgeGsmSmsSuccess = success;
        mExpectingAcknowledgeGsmSmsFailureCause = cause;
    }

    /**
     * Expect a call to acknowledgeLastIncomingGsmSmsWithPdu with success flag and PDU.
     * @param success true if expecting success; false if expecting failure
     * @param ackPdu the acknowledgement PDU to expect
     */
    synchronized void expectAcknowledgeGsmSmsWithPdu(boolean success, String ackPdu) {
        Assert.assertFalse("expectAcknowledgeGsmSms called twice", mExpectingAcknowledgeGsmSms);
        mExpectingAcknowledgeGsmSms = true;
        mExpectingAcknowledgeGsmSmsSuccess = success;
        mExpectingAcknowledgeGsmSmsPdu = ackPdu;
    }

    /**
     * Expect a call to sendEnvelopeWithStatus().
     * @param contents expected envelope contents to send
     * @param sw1 simulated SW1 status to return
     * @param sw2 simulated SW2 status to return
     * @param response simulated envelope response to return
     */
    synchronized void expectSendEnvelope(String contents, int sw1, int sw2, String response) {
        Assert.assertFalse("expectSendEnvelope called twice", mExpectingSendEnvelope);
        mExpectingSendEnvelope = true;
        mExpectingSendEnvelopeContents = contents;
        mExpectingSendEnvelopeResponseSw1 = sw1;
        mExpectingSendEnvelopeResponseSw2 = sw2;
        mExpectingSendEnvelopeResponse = response;
    }

    synchronized void assertExpectedMethodsCalled() {
        long stopTime = SystemClock.elapsedRealtime() + 5000;
        while ((mExpectingAcknowledgeGsmSms || mExpectingSendEnvelope)
                && SystemClock.elapsedRealtime() < stopTime) {
            try {
                wait();
            } catch (InterruptedException ignored) {}
        }
        Assert.assertFalse("expecting SMS acknowledge call", mExpectingAcknowledgeGsmSms);
        Assert.assertFalse("expecting send envelope call", mExpectingSendEnvelope);
    }

    @Override
    public synchronized void acknowledgeLastIncomingGsmSms(boolean success, int cause,
            Message response) {
        Log.d(TAG, "acknowledgeLastIncomingGsmSms: success=" + success + ", cause=" + cause);
        Assert.assertTrue("unexpected call to acknowledge SMS", mExpectingAcknowledgeGsmSms);
        Assert.assertEquals(mExpectingAcknowledgeGsmSmsSuccess, success);
        Assert.assertEquals(mExpectingAcknowledgeGsmSmsFailureCause, cause);
        mExpectingAcknowledgeGsmSms = false;
        if (response != null) {
            AsyncResult.forMessage(response);
            response.sendToTarget();
        }
        notifyAll();    // wake up assertExpectedMethodsCalled()
    }

    @Override
    public synchronized void acknowledgeIncomingGsmSmsWithPdu(boolean success, String ackPdu,
            Message response) {
        Log.d(TAG, "acknowledgeLastIncomingGsmSmsWithPdu: success=" + success
                + ", ackPDU= " + ackPdu);
        Assert.assertTrue("unexpected call to acknowledge SMS", mExpectingAcknowledgeGsmSms);
        Assert.assertEquals(mExpectingAcknowledgeGsmSmsSuccess, success);
        Assert.assertEquals(mExpectingAcknowledgeGsmSmsPdu, ackPdu);
        mExpectingAcknowledgeGsmSms = false;
        if (response != null) {
            AsyncResult.forMessage(response);
            response.sendToTarget();
        }
        notifyAll();    // wake up assertExpectedMethodsCalled()
    }

    @Override
    public synchronized void sendEnvelopeWithStatus(String contents, Message response) {
        // Add spaces between hex bytes for readability
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < contents.length(); i += 2) {
            builder.append(contents.charAt(i)).append(contents.charAt(i+1)).append(' ');
        }
        Log.d(TAG, "sendEnvelopeWithStatus: " + builder.toString());

        Assert.assertTrue("unexpected call to send envelope", mExpectingSendEnvelope);
        Assert.assertEquals(mExpectingSendEnvelopeContents, contents);
        mExpectingSendEnvelope = false;

        IccIoResult result = new IccIoResult(mExpectingSendEnvelopeResponseSw1,
                mExpectingSendEnvelopeResponseSw2, mExpectingSendEnvelopeResponse);

        if (response != null) {
            AsyncResult.forMessage(response, result, null);
            response.sendToTarget();
        }
        notifyAll();    // wake up assertExpectedMethodsCalled()
    }

    @Override
    public void setSuppServiceNotifications(boolean enable, Message result) {
    }

    @Override
    public void supplyIccPin(String pin, Message result) {
    }

    @Override
    public void supplyIccPinForApp(String pin, String aid, Message result) {
    }

    @Override
    public void supplyIccPuk(String puk, String newPin, Message result) {
    }

    @Override
    public void supplyIccPukForApp(String puk, String newPin, String aid, Message result) {
    }

    @Override
    public void supplyIccPin2(String pin2, Message result) {
    }

    @Override
    public void supplyIccPin2ForApp(String pin2, String aid, Message result) {
    }

    @Override
    public void supplyIccPuk2(String puk2, String newPin2, Message result) {
    }

    @Override
    public void supplyIccPuk2ForApp(String puk2, String newPin2, String aid, Message result) {
    }

    @Override
    public void changeIccPin(String oldPin, String newPin, Message result) {
    }

    @Override
    public void changeIccPinForApp(String oldPin, String newPin, String aidPtr, Message result) {
    }

    @Override
    public void changeIccPin2(String oldPin2, String newPin2, Message result) {
    }

    @Override
    public void changeIccPin2ForApp(String oldPin2, String newPin2, String aidPtr, Message result) {
    }

    @Override
    public void changeBarringPassword(String facility, String oldPwd, String newPwd,
            Message result) {
    }

    @Override
    public void supplyNetworkDepersonalization(String netpin, Message result) {
    }

    @Override
    public void getCurrentCalls(Message result) {
    }

    @Override
    public void getPDPContextList(Message result) {
    }

    @Override
    public void getDataCallList(Message result) {
    }

    @Override
    public void dial(String address, int clirMode, Message result) {
    }

    @Override
    public void dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
    }

    @Override
    public void getIMSI(Message result) {
    }

    @Override
    public void getIMEI(Message result) {
    }

    @Override
    public void getIMEISV(Message result) {
    }

    @Override
    public void hangupConnection(int gsmIndex, Message result) {
    }

    @Override
    public void hangupWaitingOrBackground(Message result) {
    }

    @Override
    public void hangupForegroundResumeBackground(Message result) {
    }

    @Override
    public void switchWaitingOrHoldingAndActive(Message result) {
    }

    @Override
    public void conference(Message result) {
    }

    @Override
    public void setPreferredVoicePrivacy(boolean enable, Message result) {
    }

    @Override
    public void getPreferredVoicePrivacy(Message result) {
    }

    @Override
    public void separateConnection(int gsmIndex, Message result) {
    }

    @Override
    public void acceptCall(Message result) {
    }

    @Override
    public void rejectCall(Message result) {
    }

    @Override
    public void explicitCallTransfer(Message result) {
    }

    @Override
    public void getLastCallFailCause(Message result) {
    }

    @Override
    public void getLastPdpFailCause(Message result) {
    }

    @Override
    public void getLastDataCallFailCause(Message result) {
    }

    @Override
    public void setMute(boolean enableMute, Message response) {
    }

    @Override
    public void getMute(Message response) {
    }

    @Override
    public void getSignalStrength(Message response) {
    }

    @Override
    public void getVoiceRegistrationState(Message response) {
    }

    @Override
    public void getDataRegistrationState(Message response) {
    }

    @Override
    public void getOperator(Message response) {
    }

    @Override
    public void sendDtmf(char c, Message result) {
    }

    @Override
    public void startDtmf(char c, Message result) {
    }

    @Override
    public void stopDtmf(Message result) {
    }

    @Override
    public void sendBurstDtmf(String dtmfString, int on, int off, Message result) {
    }

    @Override
    public void sendSMS(String smscPDU, String pdu, Message response) {
    }

    @Override
    public void sendCdmaSms(byte[] pdu, Message response) {
    }

    @Override
    public void deleteSmsOnSim(int index, Message response) {
    }

    @Override
    public void deleteSmsOnRuim(int index, Message response) {
    }

    @Override
    public void writeSmsToSim(int status, String smsc, String pdu, Message response) {
    }

    @Override
    public void writeSmsToRuim(int status, String pdu, Message response) {
    }

    @Override
    public void setRadioPower(boolean on, Message response) {
    }

    @Override
    public void acknowledgeLastIncomingCdmaSms(boolean success, int cause, Message response) {
    }

    @Override
    public void iccIO(int command, int fileid, String path, int p1, int p2, int p3, String data,
            String pin2, Message response) {
    }

    @Override
    public void queryCLIP(Message response) {
    }

    @Override
    public void getCLIR(Message response) {
    }

    @Override
    public void setCLIR(int clirMode, Message response) {
    }

    @Override
    public void queryCallWaiting(int serviceClass, Message response) {
    }

    @Override
    public void setCallWaiting(boolean enable, int serviceClass, Message response) {
    }

    @Override
    public void setCallForward(int action, int cfReason, int serviceClass, String number,
            int timeSeconds, Message response) {
    }

    @Override
    public void queryCallForwardStatus(int cfReason, int serviceClass, String number,
            Message response) {
    }

    @Override
    public void setNetworkSelectionModeAutomatic(Message response) {
    }

    @Override
    public void setNetworkSelectionModeManual(String operatorNumeric, Message response) {
    }

    @Override
    public void getNetworkSelectionMode(Message response) {
    }

    @Override
    public void getAvailableNetworks(Message response) {
    }

    @Override
    public void getBasebandVersion(Message response) {
    }

    @Override
    public void queryFacilityLock(String facility, String password, int serviceClass,
            Message response) {
    }

    @Override
    public void queryFacilityLockForApp(String facility, String password, int serviceClass,
            String appId, Message response) {
    }

    @Override
    public void setFacilityLock(String facility, boolean lockState, String password,
            int serviceClass, Message response) {
    }

    @Override
    public void setFacilityLockForApp(String facility, boolean lockState, String password,
            int serviceClass, String appId, Message response) {
    }

    @Override
    public void sendUSSD(String ussdString, Message response) {
    }

    @Override
    public void cancelPendingUssd(Message response) {
    }

    @Override
    public void resetRadio(Message result) {
    }

    @Override
    public void setBandMode(int bandMode, Message response) {
    }

    @Override
    public void queryAvailableBandMode(Message response) {
    }

    @Override
    public void setPreferredNetworkType(int networkType, Message response) {
    }

    @Override
    public void getPreferredNetworkType(Message response) {
    }

    @Override
    public void getNeighboringCids(Message response) {
    }

    @Override
    public void setLocationUpdates(boolean enable, Message response) {
    }

    @Override
    public void getSmscAddress(Message result) {
    }

    @Override
    public void setSmscAddress(String address, Message result) {
    }

    @Override
    public void reportSmsMemoryStatus(boolean available, Message result) {
    }

    @Override
    public void reportStkServiceIsRunning(Message result) {
    }

    @Override
    public void invokeOemRilRequestRaw(byte[] data, Message response) {
    }

    @Override
    public void invokeOemRilRequestStrings(String[] strings, Message response) {
    }

    @Override
    public void sendTerminalResponse(String contents, Message response) {
    }

    @Override
    public void sendEnvelope(String contents, Message response) {
    }

    @Override
    public void handleCallSetupRequestFromSim(boolean accept, Message response) {
    }

    @Override
    public void setGsmBroadcastActivation(boolean activate, Message result) {
    }

    @Override
    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message response) {
    }

    @Override
    public void getGsmBroadcastConfig(Message response) {
    }

    @Override
    public void getDeviceIdentity(Message response) {
    }

    @Override
    public void getCDMASubscription(Message response) {
    }

    @Override
    public void sendCDMAFeatureCode(String FeatureCode, Message response) {
    }

    @Override
    public void setPhoneType(int phoneType) {
    }

    @Override
    public void queryCdmaRoamingPreference(Message response) {
    }

    @Override
    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
    }

    @Override
    public void setCdmaSubscriptionSource(int cdmaSubscriptionType, Message response) {
    }

    @Override
    public void getCdmaSubscriptionSource(Message response) {
    }

    @Override
    public void setTTYMode(int ttyMode, Message response) {
    }

    @Override
    public void queryTTYMode(Message response) {
    }

    @Override
    public void setupDataCall(String radioTechnology, String profile, String apn, String user,
            String password, String authType, String protocol, Message result) {
    }

    @Override
    public void deactivateDataCall(int cid, int reason, Message result) {
    }

    @Override
    public void setCdmaBroadcastActivation(boolean activate, Message result) {
    }

    @Override
    public void setCdmaBroadcastConfig(int[] configValuesArray, Message result) {
    }

    @Override
    public void getCdmaBroadcastConfig(Message result) {
    }

    @Override
    public void exitEmergencyCallbackMode(Message response) {
    }

    @Override
    public void getIccCardStatus(Message result) {
    }

    @Override
    public void requestIsimAuthentication(String nonce, Message response) {
    }

    @Override
    public void getVoiceRadioTechnology(Message response) {
    }
}
