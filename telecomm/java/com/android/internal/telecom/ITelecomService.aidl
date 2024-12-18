/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telecom;

import android.content.ComponentName;
import android.content.Intent;
import android.telecom.TelecomAnalytics;
import android.telecom.PhoneAccountHandle;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.telecom.PhoneAccount;
import android.content.pm.ParceledListSlice;
import android.telecom.CallAttributes;
import com.android.internal.telecom.ICallEventCallback;

/**
 * Interface used to interact with Telecom. Mostly this is used by TelephonyManager for passing
 * commands that were previously handled by ITelephony.
 * {@hide}
 */
interface ITelecomService {
    /**
     * Brings the in-call screen to the foreground if there is an active call.
     *
     * @param showDialpad if true, make the dialpad visible initially.
     */
    void showInCallScreen(boolean showDialpad, String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#getDefaultOutgoingPhoneAccount
     */
    PhoneAccountHandle getDefaultOutgoingPhoneAccount(in String uriScheme, String callingPackage,
            String callingFeatureId);

    /**
     * @see TelecomServiceImpl#getUserSelectedOutgoingPhoneAccount
     */
    PhoneAccountHandle getUserSelectedOutgoingPhoneAccount(String callingPackage);

    /**
     * @see TelecomServiceImpl#setUserSelectedOutgoingPhoneAccount
     */
    void setUserSelectedOutgoingPhoneAccount(in PhoneAccountHandle account);

    /**
     * @see TelecomServiceImpl#getCallCapablePhoneAccounts
     */
    ParceledListSlice<PhoneAccountHandle> getCallCapablePhoneAccounts(
            boolean includeDisabledAccounts, String callingPackage,
            String callingFeatureId, boolean acrossProfiles);

    /**
     * @see TelecomServiceImpl#getSelfManagedPhoneAccounts
     */
    ParceledListSlice<PhoneAccountHandle> getSelfManagedPhoneAccounts(String callingPackage,
            String callingFeatureId);

    /**
     * @see TelecomServiceImpl#getOwnSelfManagedPhoneAccounts
     */
    ParceledListSlice<PhoneAccountHandle> getOwnSelfManagedPhoneAccounts(String callingPackage,
            String callingFeatureId);

    /**
     * @see TelecomManager#getPhoneAccountsSupportingScheme
     */
    ParceledListSlice<PhoneAccountHandle> getPhoneAccountsSupportingScheme(in String uriScheme,
            String callingPackage);

    /**
     * @see TelecomManager#getPhoneAccountsForPackage
     */
    ParceledListSlice<PhoneAccountHandle> getPhoneAccountsForPackage(in String packageName);

    /**
     * @see TelecomManager#getPhoneAccount
     */
    PhoneAccount getPhoneAccount(in PhoneAccountHandle account, String callingPackage);

    /**
     * @see TelecomManager#getPhoneAccount
     */
    ParceledListSlice<PhoneAccount> getRegisteredPhoneAccounts(String callingPackage,
            String callingFeatureId);

    /**
     * @see TelecomManager#getAllPhoneAccountsCount
     */
    int getAllPhoneAccountsCount();

    /**
     * @see TelecomManager#getAllPhoneAccounts
     */
    ParceledListSlice<PhoneAccount> getAllPhoneAccounts();

    /**
     * @see TelecomManager#getAllPhoneAccountHandles
     */
    ParceledListSlice<PhoneAccountHandle> getAllPhoneAccountHandles();

    /**
     * @see TelecomServiceImpl#getSimCallManager
     */
    PhoneAccountHandle getSimCallManager(int subId, String callingPackage);

    /**
     * @see TelecomServiceImpl#getSimCallManagerForUser
     */
    PhoneAccountHandle getSimCallManagerForUser(int userId, String callingPackage);

    /**
     * @see TelecomServiceImpl#registerPhoneAccount
     */
    void registerPhoneAccount(in PhoneAccount metadata, String callingPackage);

    /**
     * @see TelecomServiceImpl#unregisterPhoneAccount
     */
    void unregisterPhoneAccount(in PhoneAccountHandle account, String callingPackage);

    /**
     * @see TelecomServiceImpl#clearAccounts
     */
    void clearAccounts(String packageName);

    /**
     * @see TelecomServiceImpl#isVoiceMailNumber
     */
    boolean isVoiceMailNumber(in PhoneAccountHandle accountHandle, String number,
            String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#getVoiceMailNumber
     */
    String getVoiceMailNumber(in PhoneAccountHandle accountHandle, String callingPackage,
            String callingFeatureId);

    /**
     * @see TelecomServiceImpl#getLine1Number
     */
    String getLine1Number(in PhoneAccountHandle accountHandle, String callingPackage,
            String callingFeatureId);

    /**
     * @see TelecomServiceImpl#getDefaultPhoneApp
     */
    ComponentName getDefaultPhoneApp();

    /**
     * @see TelecomServiceImpl#getDefaultDialerPackage
     */
    String getDefaultDialerPackage(String callingPackage);

    /**
     * @see TelecomServiceImpl#getDefaultDialerPackage
     */
    String getDefaultDialerPackageForUser(int userId);

    /**
     * @see TelecomServiceImpl#getSystemDialerPackage
     */
    String getSystemDialerPackage(String callingPackage);

    /**
    * @see TelecomServiceImpl#dumpCallAnalytics
    */
    TelecomAnalytics dumpCallAnalytics();

    //
    // Internal system apis relating to call management.
    //

    /**
     * @see TelecomServiceImpl#silenceRinger
     */
    void silenceRinger(String callingPackage);

    /**
     * @see TelecomServiceImpl#isInCall
     */
    boolean isInCall(String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#hasManageOngoingCallsPermission
     */
    boolean hasManageOngoingCallsPermission(String callingPackage);

    /**
     * @see TelecomServiceImpl#isInManagedCall
     */
    boolean isInManagedCall(String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#isRinging
     */
    boolean isRinging(String callingPackage);

    /**
     * @see TelecomServiceImpl#getCallState
     * Note: only kept around to not break app compat, however this will throw a SecurityException
     * on API 31+.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int getCallState();

    /**
     * @see TelecomServiceImpl#getCallState
     */
    int getCallStateUsingPackage(String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#endCall
     */
    boolean endCall(String callingPackage);

    /**
     * @see TelecomServiceImpl#acceptRingingCall
     */
    void acceptRingingCall(String callingPackage);

    /**
     * @see TelecomServiceImpl#acceptRingingCallWithVideoState(int)
     */
    void acceptRingingCallWithVideoState(String callingPackage, int videoState);

    /**
     * @see TelecomServiceImpl#cancelMissedCallsNotification
     */
    void cancelMissedCallsNotification(String callingPackage);

    /**
     * @see TelecomServiceImpl#handleMmi
     */
    boolean handlePinMmi(String dialString, String callingPackage);

    /**
     * @see TelecomServiceImpl#handleMmi
     */
    boolean handlePinMmiForPhoneAccount(in PhoneAccountHandle accountHandle, String dialString,
            String callingPackage);

    /**
     * @see TelecomServiceImpl#getAdnUriForPhoneAccount
     */
    Uri getAdnUriForPhoneAccount(in PhoneAccountHandle accountHandle, String callingPackage);

    /**
     * @see TelecomServiceImpl#isTtySupported
     */
    boolean isTtySupported(String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#getCurrentTtyMode
     */
    int getCurrentTtyMode(String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#addNewIncomingCall
     */
    void addNewIncomingCall(in PhoneAccountHandle phoneAccount, in Bundle extras,
            String callingPackage);

    /**
     * @see TelecomServiceImpl#addNewIncomingConference
     */
    void addNewIncomingConference(in PhoneAccountHandle phoneAccount, in Bundle extras,
            String callingPackage);


    /**
     * @see TelecomServiceImpl#addNewUnknownCall
     */
    void addNewUnknownCall(in PhoneAccountHandle phoneAccount, in Bundle extras);

    /**
     * @see TelecomServiceImpl#startConference
     */
    void startConference(in List<Uri> participants, in Bundle extras,
            String callingPackage);

    /**
     * @see TelecomServiceImpl#placeCall
     */
    void placeCall(in Uri handle, in Bundle extras, String callingPackage, String callingFeatureId);

    /**
     * @see TelecomServiceImpl#enablePhoneAccount
     */
    boolean enablePhoneAccount(in PhoneAccountHandle accountHandle, boolean isEnabled);

    /**
     * @see TelecomServiceImpl#setDefaultDialer
     */
    boolean setDefaultDialer(in String packageName);

    /**
     * Stop suppressing blocked numbers after a call to emergency services. Shell only.
     */
    void stopBlockSuppression();

    /**
    * @see TelecomServiceImpl#createManageBlockedNumbersIntent
    **/
    Intent createManageBlockedNumbersIntent(String callingPackage);

   /**
    * @see TelecomServiceImpl#createLaunchEmergencyDialerIntent
    */
    Intent createLaunchEmergencyDialerIntent(in String number);

    /**
     * @see TelecomServiceImpl#isIncomingCallPermitted
     */
    boolean isIncomingCallPermitted(in PhoneAccountHandle phoneAccountHandle,
            String callingPackage);

    /**
     * @see TelecomServiceImpl#isOutgoingCallPermitted
     */
    boolean isOutgoingCallPermitted(in PhoneAccountHandle phoneAccountHandle,
            String callingPackage);

    /**
     * @see TelecomServiceImpl#waitOnHandler
     */
    void waitOnHandlers();

    /**
     * @see TelecomServiceImpl#acceptHandover
     */
    void acceptHandover(in Uri srcAddr, int videoState, in PhoneAccountHandle destAcct,
                String callingPackage);

    /**
     * @see TelecomServiceImpl#setTestEmergencyPhoneAccountPackageNameFilter
     */
    void setTestEmergencyPhoneAccountPackageNameFilter(String packageName);

    /**
     * @see TelecomServiceImpl#isInEmergencyCall
     */
    boolean isInEmergencyCall();

    /**
     * @see TelecomServiceImpl#handleCallIntent
     */
    void handleCallIntent(in Intent intent, in String callingPackageProxy);

    void cleanupStuckCalls();

    int cleanupOrphanPhoneAccounts();

    boolean isNonUiInCallServiceBound(in String packageName);

    void resetCarMode();

    void setTestDefaultCallRedirectionApp(String packageName);

    /**
     * @see TelecomServiceImpl#requestLogMark
     */
    void requestLogMark(in String message);

    void setTestPhoneAcctSuggestionComponent(String flattenedComponentName,
        in UserHandle userHandle);

    void setTestDefaultCallScreeningApp(String packageName);

    void addOrRemoveTestCallCompanionApp(String packageName, boolean isAdded);

    /**
     * @see TelecomServiceImpl#setSystemDialer
     */
    void setSystemDialer(in ComponentName testComponentName);

    /**
     * @see TelecomServiceImpl#setTestDefaultDialer
     */
    void setTestDefaultDialer(in String packageName);

    /**
     * @see TelecomServiceImpl#setTestCallDiagnosticService
     */
    void setTestCallDiagnosticService(in String packageName);

    /**
     * @see TelecomServiceImpl#isInSelfManagedCall
     */
    boolean isInSelfManagedCall(String packageName, in UserHandle userHandle,
        String callingPackage);

    /**
     * @see TelecomServiceImpl#addCall
     */
    void addCall(in CallAttributes callAttributes, in ICallEventCallback callback, String callId,
        String callingPackage);
}
