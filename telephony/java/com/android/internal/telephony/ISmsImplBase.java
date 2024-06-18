/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/**
 * Base class for ISms that facilitates forward compatibility with new features.
 */
public class ISmsImplBase extends ISms.Stub {

    @Override
    public List<SmsRawData> getAllMessagesFromIccEfForSubscriber(int subId, String callingPkg) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateMessageOnIccEfForSubscriber(int subId, String callingPkg, int messageIndex,
            int newStatus, byte[] pdu) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean copyMessageToIccEfForSubscriber(int subId, String callingPkg, int status,
            byte[] pdu, byte[] smsc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendDataForSubscriber(int subId, String callingPkg, String callingAttributionTag,
            String destAddr, String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendTextForSubscriber(int subId, String callingPkg, String callingAttributionTag,
            String destAddr, String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean persistMessageForNonDefaultSmsApp,
            long messageId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendTextForSubscriberWithOptions(int subId, String callingPkg,
            String callingAttributionTag, String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent,
            boolean persistMessageForNonDefaultSmsApp, int priority, boolean expectMore,
            int validityPeriod) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void injectSmsPduForSubscriber(
            int subId, byte[] pdu, String format, PendingIntent receivedIntent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendMultipartTextForSubscriber(int subId, String callingPkg,
            String callingAttributionTag, String destinationAddress, String scAddress,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents, boolean persistMessageForNonDefaultSmsApp,
            long messageId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendMultipartTextForSubscriberWithOptions(int subId, String callingPkg,
            String callingAttributionTag, String destinationAddress, String scAddress,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents, boolean persistMessageForNonDefaultSmsApp,
            int priority, boolean expectMore, int validityPeriod) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean enableCellBroadcastForSubscriber(int subId, int messageIdentifier, int ranType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean disableCellBroadcastForSubscriber(int subId, int messageIdentifier,
            int ranType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean enableCellBroadcastRangeForSubscriber(int subId, int startMessageId,
            int endMessageId, int ranType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean disableCellBroadcastRangeForSubscriber(int subId, int startMessageId,
            int endMessageId, int ranType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPremiumSmsPermission(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPremiumSmsPermissionForSubscriber(int subId, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPremiumSmsPermission(String packageName, int permission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPremiumSmsPermissionForSubscriber(int subId, String packageName,
            int permission) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isImsSmsSupportedForSubscriber(int subId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSmsSimPickActivityNeeded(int subId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPreferredSmsSubscription() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getImsSmsFormatForSubscriber(int subId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSMSPromptEnabled() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendStoredText(int subId, String callingPkg, String callingAttributionTag,
            Uri messageUri, String scAddress, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendStoredMultipartText(int subId, String callingPkg, String callingAttributionTag,
            Uri messageUri, String scAddress, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Bundle getCarrierConfigValuesForSubscriber(int subId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String createAppSpecificSmsToken(int subId, String callingPkg, PendingIntent intent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String createAppSpecificSmsTokenWithPackageInfo(
            int subId, String callingPkg, String prefixes, PendingIntent intent) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setStorageMonitorMemoryStatusOverride(int subId, boolean storageAvailable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearStorageMonitorMemoryStatusOverride(int subId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkSmsShortCodeDestination(int subid, String callingPackage,
            String callingFeatureId, String destAddress, String countryIso) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSmscAddressFromIccEfForSubscriber(int subId, String callingPackage) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean setSmscAddressOnIccEfForSubscriber(
            String smsc, int subId, String callingPackage) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSmsCapacityOnIccForSubscriber(int subId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean resetAllCellBroadcastRanges(int subId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getWapMessageSize(String locationUrl) {
        throw new UnsupportedOperationException();
    }

}
