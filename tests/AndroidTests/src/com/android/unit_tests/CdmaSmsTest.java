/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.unit_tests;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.cdma.sms.BearerData;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.telephony.cdma.sms.CdmaSmsAddress;
import com.android.internal.util.BitwiseInputStream;
import com.android.internal.util.BitwiseOutputStream;
import com.android.internal.util.HexDump;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.Iterator;

import android.util.Log;

public class CdmaSmsTest extends AndroidTestCase {
    private final static String LOG_TAG = "Cdma_Sms_Test";

    @SmallTest
    public void testUserData7bitGsm() throws Exception {
        String pdu = "00031040900112488ea794e074d69e1b7392c270326cde9e98";
        BearerData bearerData = BearerData.decode(HexDump.hexStringToByteArray(pdu));
        assertEquals("Test standard SMS", bearerData.userData.payloadStr);
    }

    @SmallTest
    public void testUserData7bitAscii() throws Exception {
        String pdu = "0003100160010610262d5ab500";
        BearerData bearerData = BearerData.decode(HexDump.hexStringToByteArray(pdu));
        assertEquals("bjjj", bearerData.userData.payloadStr);
    }

    @SmallTest
    public void testUserData7bitAsciiTwo() throws Exception {
        String pdu = "00031001d00109104539b4d052ebb3d0";
        BearerData bearerData = BearerData.decode(HexDump.hexStringToByteArray(pdu));
        assertEquals("SMS Rulz", bearerData.userData.payloadStr);
    }

    @SmallTest
    public void testUserDataIa5() throws Exception {
        String pdu = "00031002100109184539b4d052ebb3d0";
        BearerData bearerData = BearerData.decode(HexDump.hexStringToByteArray(pdu));
        assertEquals("SMS Rulz", bearerData.userData.payloadStr);
    }

    @SmallTest
    public void testUserData7bitAsciiFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "Test standard SMS";
        userData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
        userData.msgEncodingSet = true;
        bearerData.userData = userData;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(BearerData.MESSAGE_TYPE_DELIVER, revBearerData.messageType);
        assertEquals(0, revBearerData.messageId);
        assertEquals(false, revBearerData.hasUserDataHeader);
        assertEquals(userData.msgEncoding, revBearerData.userData.msgEncoding);
        assertEquals(userData.payloadStr.length(), revBearerData.userData.numFields);
        assertEquals(userData.payloadStr, revBearerData.userData.payloadStr);
    }

    @SmallTest
    public void testUserData7bitGsmFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "Test standard SMS";
        userData.msgEncoding = UserData.ENCODING_GSM_7BIT_ALPHABET;
        userData.msgEncodingSet = true;
        bearerData.userData = userData;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(BearerData.MESSAGE_TYPE_DELIVER, revBearerData.messageType);
        assertEquals(0, revBearerData.messageId);
        assertEquals(false, revBearerData.hasUserDataHeader);
        assertEquals(userData.msgEncoding, revBearerData.userData.msgEncoding);
        assertEquals(userData.payloadStr.length(), revBearerData.userData.numFields);
        assertEquals(userData.payloadStr, revBearerData.userData.payloadStr);
    }

    @SmallTest
    public void testUserDataUtf16Feedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "\u0160u\u1E5B\u0301r\u1ECFg\uD835\uDC1At\u00E9\u4E002\u3042";
        userData.msgEncoding = UserData.ENCODING_UNICODE_16;
        userData.msgEncodingSet = true;
        bearerData.userData = userData;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(BearerData.MESSAGE_TYPE_DELIVER, revBearerData.messageType);
        assertEquals(0, revBearerData.messageId);
        assertEquals(false, revBearerData.hasUserDataHeader);
        assertEquals(userData.msgEncoding, revBearerData.userData.msgEncoding);
        assertEquals(userData.payloadStr.length(), revBearerData.userData.numFields);
        assertEquals(userData.payloadStr, revBearerData.userData.payloadStr);
        userData.msgEncoding = UserData.ENCODING_OCTET;
        userData.msgEncodingSet = false;
        revBearerData = BearerData.decode(BearerData.encode(bearerData));
        assertEquals(BearerData.MESSAGE_TYPE_DELIVER, revBearerData.messageType);
        assertEquals(0, revBearerData.messageId);
        assertEquals(false, revBearerData.hasUserDataHeader);
        assertEquals(userData.msgEncoding, revBearerData.userData.msgEncoding);
        assertEquals(userData.payloadStr.length(), revBearerData.userData.numFields);
        assertEquals(userData.payloadStr, revBearerData.userData.payloadStr);
    }

    @SmallTest
    public void testReplyOption() throws Exception {
        String pdu1 = "0003104090011648b6a794e0705476bf77bceae934fe5f6d94d87450080a0180";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals("Test Acknowledgement 1", bd1.userData.payloadStr);
        assertEquals(true, bd1.userAckReq);
        assertEquals(false, bd1.deliveryAckReq);
        assertEquals(false, bd1.readAckReq);
        assertEquals(false, bd1.reportReq);
        String pdu2 = "0003104090011648b6a794e0705476bf77bceae934fe5f6d94d87490080a0140";
        BearerData bd2 = BearerData.decode(HexDump.hexStringToByteArray(pdu2));
        assertEquals("Test Acknowledgement 2", bd2.userData.payloadStr);
        assertEquals(false, bd2.userAckReq);
        assertEquals(true, bd2.deliveryAckReq);
        assertEquals(false, bd2.readAckReq);
        assertEquals(false, bd2.reportReq);
        String pdu3 = "0003104090011648b6a794e0705476bf77bceae934fe5f6d94d874d0080a0120";
        BearerData bd3 = BearerData.decode(HexDump.hexStringToByteArray(pdu3));
        assertEquals("Test Acknowledgement 3", bd3.userData.payloadStr);
        assertEquals(false, bd3.userAckReq);
        assertEquals(false, bd3.deliveryAckReq);
        assertEquals(true, bd3.readAckReq);
        assertEquals(false, bd3.reportReq);
        String pdu4 = "0003104090011648b6a794e0705476bf77bceae934fe5f6d94d87510080a0110";
        BearerData bd4 = BearerData.decode(HexDump.hexStringToByteArray(pdu4));
        assertEquals("Test Acknowledgement 4", bd4.userData.payloadStr);
        assertEquals(false, bd4.userAckReq);
        assertEquals(false, bd4.deliveryAckReq);
        assertEquals(false, bd4.readAckReq);
        assertEquals(true, bd4.reportReq);
    }

    @SmallTest
    public void testReplyOptionFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test reply option";
        bearerData.userData = userData;
        bearerData.userAckReq = true;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(true, revBearerData.userAckReq);
        assertEquals(false, revBearerData.deliveryAckReq);
        assertEquals(false, revBearerData.readAckReq);
        assertEquals(false, revBearerData.reportReq);
        bearerData.userAckReq = false;
        bearerData.deliveryAckReq = true;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        assertEquals(false, revBearerData.userAckReq);
        assertEquals(true, revBearerData.deliveryAckReq);
        assertEquals(false, revBearerData.readAckReq);
        assertEquals(false, revBearerData.reportReq);
        bearerData.deliveryAckReq = false;
        bearerData.readAckReq = true;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        assertEquals(false, revBearerData.userAckReq);
        assertEquals(false, revBearerData.deliveryAckReq);
        assertEquals(true, revBearerData.readAckReq);
        assertEquals(false, revBearerData.reportReq);
        bearerData.readAckReq = false;
        bearerData.reportReq = true;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        assertEquals(false, revBearerData.userAckReq);
        assertEquals(false, revBearerData.deliveryAckReq);
        assertEquals(false, revBearerData.readAckReq);
        assertEquals(true, revBearerData.reportReq);
    }

    @SmallTest
    public void testNumberOfMessages() throws Exception {
        String pdu1 = "000310409001124896a794e07595f69f199540ea759a0dc8e00b0163";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals("Test Voice mail 99", bd1.userData.payloadStr);
        assertEquals(99, bd1.numberOfMessages);
        String pdu2 = "00031040900113489ea794e07595f69f199540ea759a0988c0600b0164";
        BearerData bd2 = BearerData.decode(HexDump.hexStringToByteArray(pdu2));
        assertEquals("Test Voice mail 100", bd2.userData.payloadStr);
        assertEquals(100, bd2.numberOfMessages);
    }

    @SmallTest
    public void testNumberOfMessagesFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test message count";
        bearerData.userData = userData;
        bearerData.numberOfMessages = 27;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(bearerData.numberOfMessages, revBearerData.numberOfMessages);
    }

    @SmallTest
    public void testCallbackNum() throws Exception {
        String pdu1 = "00031040900112488ea794e070d436cb638bc5e035ce2f97900e06910431323334";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals("Test Callback nbr", bd1.userData.payloadStr);
        assertEquals(CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR, bd1.callbackNumber.digitMode);
        assertEquals(CdmaSmsAddress.TON_INTERNATIONAL_OR_IP, bd1.callbackNumber.ton);
        assertEquals(CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK, bd1.callbackNumber.numberMode);
        assertEquals(CdmaSmsAddress.NUMBERING_PLAN_ISDN_TELEPHONY, bd1.callbackNumber.numberPlan);
        assertEquals("1234", bd1.callbackNumber.address);
    }

    @SmallTest
    public void testCallbackNumDtmf() throws Exception {
        String pdu1 = "00031002300109104539b4d052ebb3d00e07052d4c90a55080";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals("SMS Rulz", bd1.userData.payloadStr);
        assertEquals(CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF, bd1.callbackNumber.digitMode);
        assertEquals(CdmaSmsAddress.TON_UNKNOWN, bd1.callbackNumber.ton);
        assertEquals(CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK, bd1.callbackNumber.numberMode);
        assertEquals(CdmaSmsAddress.NUMBERING_PLAN_UNKNOWN, bd1.callbackNumber.numberPlan);
        assertEquals("5099214001", bd1.callbackNumber.address);
    }

    @SmallTest
    public void testCallbackNumFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test callback number";
        bearerData.userData = userData;
        CdmaSmsAddress addr = new CdmaSmsAddress();
        addr.digitMode = CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR;
        addr.ton = CdmaSmsAddress.TON_NATIONAL_OR_EMAIL;
        addr.numberMode = CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK;
        addr.numberPlan = CdmaSmsAddress.NUMBERING_PLAN_UNKNOWN;
        addr.address = "8005551212";
        addr.numberOfDigits = (byte)addr.address.length();
        bearerData.callbackNumber = addr;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        CdmaSmsAddress revAddr = revBearerData.callbackNumber;
        assertEquals(addr.digitMode, revAddr.digitMode);
        assertEquals(addr.ton, revAddr.ton);
        assertEquals(addr.numberMode, revAddr.numberMode);
        assertEquals(addr.numberPlan, revAddr.numberPlan);
        assertEquals(addr.numberOfDigits, revAddr.numberOfDigits);
        assertEquals(addr.address, revAddr.address);
        addr.address = "8*55#1012";
        addr.numberOfDigits = (byte)addr.address.length();
        addr.digitMode = CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        revAddr = revBearerData.callbackNumber;
        assertEquals(addr.digitMode, revAddr.digitMode);
        assertEquals(addr.numberOfDigits, revAddr.numberOfDigits);
        assertEquals(addr.address, revAddr.address);
    }

    @SmallTest
    public void testMsgCenterTimeStampFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test message center timestamp";
        bearerData.userData = userData;
        bearerData.timeStamp = HexDump.hexStringToByteArray("112233445566");
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(HexDump.toHexString(bearerData.timeStamp),
                     HexDump.toHexString(revBearerData.timeStamp));
    }

    @SmallTest
    public void testPrivacyIndicator() throws Exception {
        String pdu1 = "0003104090010c485f4194dfea34becf61b840090140";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals(bd1.privacy, BearerData.PRIVACY_RESTRICTED);
        String pdu2 = "0003104090010c485f4194dfea34becf61b840090180";
        BearerData bd2 = BearerData.decode(HexDump.hexStringToByteArray(pdu2));
        assertEquals(bd2.privacy, BearerData.PRIVACY_CONFIDENTIAL);
        String pdu3 = "0003104090010c485f4194dfea34becf61b8400901c0";
        BearerData bd3 = BearerData.decode(HexDump.hexStringToByteArray(pdu3));
        assertEquals(bd3.privacy, BearerData.PRIVACY_SECRET);
    }

    @SmallTest
    public void testPrivacyIndicatorFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test privacy indicator";
        bearerData.userData = userData;
        bearerData.privacy = BearerData.PRIVACY_SECRET;
        bearerData.privacyIndicatorSet = true;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.userData.payloadStr, userData.payloadStr);
        assertEquals(revBearerData.privacyIndicatorSet, true);
        assertEquals(revBearerData.privacy, BearerData.PRIVACY_SECRET);
        bearerData.privacy = BearerData.PRIVACY_RESTRICTED;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.privacy, BearerData.PRIVACY_RESTRICTED);
    }

    @SmallTest
    public void testMsgDeliveryAlert() throws Exception {
        String pdu1 = "0003104090010d4866a794e07055965b91d040300c0100";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals(bd1.alert, 0);
        assertEquals(bd1.userData.payloadStr, "Test Alert 0");
        String pdu2 = "0003104090010d4866a794e07055965b91d140300c0140";
        BearerData bd2 = BearerData.decode(HexDump.hexStringToByteArray(pdu2));
        assertEquals(bd2.alert, 1);
        assertEquals(bd2.userData.payloadStr, "Test Alert 1");
        String pdu3 = "0003104090010d4866a794e07055965b91d240300c0180";
        BearerData bd3 = BearerData.decode(HexDump.hexStringToByteArray(pdu3));
        assertEquals(bd3.alert, 2);
        assertEquals(bd3.userData.payloadStr, "Test Alert 2");
        String pdu4 = "0003104090010d4866a794e07055965b91d340300c01c0";
        BearerData bd4 = BearerData.decode(HexDump.hexStringToByteArray(pdu4));
        assertEquals(bd4.alert, 3);
        assertEquals(bd4.userData.payloadStr, "Test Alert 3");
    }

    @SmallTest
    public void testMiscParams() throws Exception {
        String pdu1 = "00031002400109104539b4d052ebb3d00c0180";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals(bd1.alert, BearerData.ALERT_MEDIUM_PRIO);
        assertEquals(bd1.userData.payloadStr, "SMS Rulz");
        String pdu2 = "00031002500109104539b4d052ebb3d00801800901c0";
        BearerData bd2 = BearerData.decode(HexDump.hexStringToByteArray(pdu2));
        assertEquals(bd2.priority, BearerData.PRIORITY_URGENT);
        assertEquals(bd2.privacy, BearerData.PRIVACY_SECRET);
        assertEquals(bd2.userData.payloadStr, "SMS Rulz");
        String pdu3 = "00031002600109104539b4d052ebb3d00901400c01c0";
        BearerData bd3 = BearerData.decode(HexDump.hexStringToByteArray(pdu3));
        assertEquals(bd3.privacy, BearerData.PRIVACY_RESTRICTED);
        assertEquals(bd3.alert, BearerData.ALERT_HIGH_PRIO);
        assertEquals(bd3.userData.payloadStr, "SMS Rulz");
        String pdu4 = "00031002700109104539b4d052ebb3d00f0105";
        BearerData bd4 = BearerData.decode(HexDump.hexStringToByteArray(pdu4));
        assertEquals(bd4.displayMode, BearerData.DISPLAY_MODE_IMMEDIATE);
        assertEquals(bd4.userData.payloadStr, "SMS Rulz");
    }
   @SmallTest
    public void testMsgDeliveryAlertFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test message delivery alert";
        bearerData.userData = userData;
        bearerData.alert = BearerData.ALERT_MEDIUM_PRIO;
        bearerData.alertIndicatorSet = true;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.userData.payloadStr, userData.payloadStr);
        assertEquals(revBearerData.alertIndicatorSet, true);
        assertEquals(revBearerData.alert, bearerData.alert);
        bearerData.alert = BearerData.ALERT_HIGH_PRIO;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.userData.payloadStr, userData.payloadStr);
        assertEquals(revBearerData.alertIndicatorSet, true);
        assertEquals(revBearerData.alert, bearerData.alert);
    }

    @SmallTest
    public void testLanguageIndicator() throws Exception {
        String pdu1 = "0003104090011748bea794e0731436ef3bd7c2e0352eef27a1c263fe58080d0101";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        assertEquals(bd1.userData.payloadStr, "Test Language indicator");
        assertEquals(bd1.language, BearerData.LANGUAGE_ENGLISH);
        String pdu2 = "0003104090011748bea794e0731436ef3bd7c2e0352eef27a1c263fe58080d0106";
        BearerData bd2 = BearerData.decode(HexDump.hexStringToByteArray(pdu2));
        assertEquals(bd2.userData.payloadStr, "Test Language indicator");
        assertEquals(bd2.language, BearerData.LANGUAGE_CHINESE);
    }

    @SmallTest
    public void testLanguageIndicatorFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test language indicator";
        bearerData.userData = userData;
        bearerData.language = BearerData.LANGUAGE_ENGLISH;
        bearerData.languageIndicatorSet = true;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.userData.payloadStr, userData.payloadStr);
        assertEquals(revBearerData.languageIndicatorSet, true);
        assertEquals(revBearerData.language, bearerData.language);
        bearerData.language = BearerData.LANGUAGE_KOREAN;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.userData.payloadStr, userData.payloadStr);
        assertEquals(revBearerData.languageIndicatorSet, true);
        assertEquals(revBearerData.language, bearerData.language);
    }

    @SmallTest
    public void testDisplayMode() throws Exception {
        String pdu1 = "0003104090010c485f4194dfea34becf61b8400f0100";
        BearerData bd1 = BearerData.decode(HexDump.hexStringToByteArray(pdu1));
        //Log.d(LOG_TAG, "bd1 = " + bd1);
        assertEquals(bd1.displayMode, BearerData.DISPLAY_MODE_IMMEDIATE);
        String pdu2 = "0003104090010c485f4194dfea34becf61b8400f0140";
        BearerData bd2 = BearerData.decode(HexDump.hexStringToByteArray(pdu2));
        assertEquals(bd2.displayMode, BearerData.DISPLAY_MODE_DEFAULT);
        String pdu3 = "0003104090010c485f4194dfea34becf61b8400f0180";
        BearerData bd3 = BearerData.decode(HexDump.hexStringToByteArray(pdu3));
        assertEquals(bd3.displayMode, BearerData.DISPLAY_MODE_USER);
    }

    @SmallTest
    public void testDisplayModeFeedback() throws Exception {
        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;
        bearerData.messageId = 0;
        bearerData.hasUserDataHeader = false;
        UserData userData = new UserData();
        userData.payloadStr = "test display mode";
        bearerData.userData = userData;
        bearerData.displayMode = BearerData.DISPLAY_MODE_IMMEDIATE;
        bearerData.displayModeSet = true;
        byte []encodedSms = BearerData.encode(bearerData);
        BearerData revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.userData.payloadStr, userData.payloadStr);
        assertEquals(revBearerData.displayModeSet, true);
        assertEquals(revBearerData.displayMode, bearerData.displayMode);
        bearerData.displayMode = BearerData.DISPLAY_MODE_USER;
        encodedSms = BearerData.encode(bearerData);
        revBearerData = BearerData.decode(encodedSms);
        assertEquals(revBearerData.userData.payloadStr, userData.payloadStr);
        assertEquals(revBearerData.displayModeSet, true);
        assertEquals(revBearerData.displayMode, bearerData.displayMode);
    }
}
