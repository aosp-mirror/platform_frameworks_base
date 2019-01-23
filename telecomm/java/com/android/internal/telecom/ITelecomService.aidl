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
import android.telecom.PhoneAccount;

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
    void showInCallScreen(boolean showDialpad, String callingPackage);

    /**
     * @see TelecomServiceImpl#getDefaultOutgoingPhoneAccount
     */
    PhoneAccountHandle getDefaultOutgoingPhoneAccount(in String uriScheme, String callingPackage);

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
    List<PhoneAccountHandle> getCallCapablePhoneAccounts(
            boolean includeDisabledAccounts, String callingPackage);

    /**
     * @see TelecomServiceImpl#getSelfManagedPhoneAccounts
     */
    List<PhoneAccountHandle> getSelfManagedPhoneAccounts(String callingPackage);

    /**
     * @see TelecomManager#getPhoneAccountsSupportingScheme
     */
    List<PhoneAccountHandle> getPhoneAccountsSupportingScheme(in String uriScheme,
            String callingPackage);

    /**
     * @see TelecomManager#getPhoneAccountsForPackage
     */
    List<PhoneAccountHandle> getPhoneAccountsForPackage(in String packageName);

    /**
     * @see TelecomManager#getPhoneAccount
     */
    PhoneAccount getPhoneAccount(in PhoneAccountHandle account);

    /**
     * @see TelecomManager#getAllPhoneAccountsCount
     */
    int getAllPhoneAccountsCount();

    /**
     * @see TelecomManager#getAllPhoneAccounts
     */
    List<PhoneAccount> getAllPhoneAccounts();

    /**
     * @see TelecomManager#getAllPhoneAccountHandles
     */
    List<PhoneAccountHandle> getAllPhoneAccountHandles();

    /**
     * @see TelecomServiceImpl#getSimCallManager
     */
    PhoneAccountHandle getSimCallManager();

    /**
     * @see TelecomServiceImpl#getSimCallManagerForUser
     */
    PhoneAccountHandle getSimCallManagerForUser(int userId);

    /**
     * @see TelecomServiceImpl#registerPhoneAccount
     */
    void registerPhoneAccount(in PhoneAccount metadata);

    /**
     * @see TelecomServiceImpl#unregisterPhoneAccount
     */
    void unregisterPhoneAccount(in PhoneAccountHandle account);

    /**
     * @see TelecomServiceImpl#clearAccounts
     */
    void clearAccounts(String packageName);

    /**
     * @see TelecomServiceImpl#isVoiceMailNumber
     */
    boolean isVoiceMailNumber(in PhoneAccountHandle accountHandle, String number,
            String callingPackage);

    /**
     * @see TelecomServiceImpl#getVoiceMailNumber
     */
    String getVoiceMailNumber(in PhoneAccountHandle accountHandle, String callingPackage);

    /**
     * @see TelecomServiceImpl#getLine1Number
     */
    String getLine1Number(in PhoneAccountHandle accountHandle, String callingPackage);

    /**
     * @see TelecomServiceImpl#getDefaultPhoneApp
     */
    ComponentName getDefaultPhoneApp();

    /**
     * @see TelecomServiceImpl#getDefaultDialerPackage
     */
    String getDefaultDialerPackage();

    /**
     * @see TelecomServiceImpl#getSystemDialerPackage
     */
    String getSystemDialerPackage();

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
    boolean isInCall(String callingPackage);

    /**
     * @see TelecomServiceImpl#isInManagedCall
     */
    boolean isInManagedCall(String callingPackage);

    /**
     * @see TelecomServiceImpl#isRinging
     */
    boolean isRinging(String callingPackage);

    /**
     * @see TelecomServiceImpl#getCallState
     */
    int getCallState();

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
    boolean isTtySupported(String callingPackage);

    /**
     * @see TelecomServiceImpl#getCurrentTtyMode
     */
    int getCurrentTtyMode(String callingPackage);

    /**
     * @see TelecomServiceImpl#addNewIncomingCall
     */
    void addNewIncomingCall(in PhoneAccountHandle phoneAccount, in Bundle extras);

    /**
     * @see TelecomServiceImpl#addNewUnknownCall
     */
    void addNewUnknownCall(in PhoneAccountHandle phoneAccount, in Bundle extras);

    /**
     * @see TelecomServiceImpl#placeCall
     */
    void placeCall(in Uri handle, in Bundle extras, String callingPackage);

    /**
     * @see TelecomServiceImpl#enablePhoneAccount
     */
    boolean enablePhoneAccount(in PhoneAccountHandle accountHandle, boolean isEnabled);

    /**
     * @see TelecomServiceImpl#setDefaultDialer
     */
    boolean setDefaultDialer(in String packageName);

    /**
    * @see TelecomServiceImpl#createManageBlockedNumbersIntent
    **/
    Intent createManageBlockedNumbersIntent();

    /**
     * @see TelecomServiceImpl#isIncomingCallPermitted
     */
    boolean isIncomingCallPermitted(in PhoneAccountHandle phoneAccountHandle);

    /**
     * @see TelecomServiceImpl#isOutgoingCallPermitted
     */
    boolean isOutgoingCallPermitted(in PhoneAccountHandle phoneAccountHandle);

    /**
     * @see TelecomServiceImpl#waitOnHandler
     */
    void waitOnHandlers();

    /**
     * @see TelecomServiceImpl#acceptHandover
     */
    void acceptHandover(in Uri srcAddr, int videoState, in PhoneAccountHandle destAcct);

    /**
     * @see TelecomServiceImpl#isInEmergencyCall
     */
    boolean isInEmergencyCall();

    oneway void reportNuisanceCallStatus(in Uri address, boolean isNuisance, String callingPackage);

    /**
     * @see TelecomServiceImpl#handleCallIntent
     */
    void handleCallIntent(in Intent intent);

    void setTestDefaultCallRedirectionApp(String packageName);

    void setTestPhoneAcctSuggestionComponent(String flattenedComponentName);

    void setTestDefaultCallScreeningApp(String packageName);

    void addOrRemoveTestCallCompanionApp(String packageName, boolean isAdded);

    void setTestAutoModeApp(String packageName);

}
