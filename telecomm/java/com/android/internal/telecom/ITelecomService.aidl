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
    void showInCallScreen(boolean showDialpad);

    /**
     * @see TelecomServiceImpl#getDefaultOutgoingPhoneAccount
     */
    PhoneAccountHandle getDefaultOutgoingPhoneAccount(in String uriScheme);

    /**
     * @see TelecomServiceImpl#getUserSelectedOutgoingPhoneAccount
     */
    PhoneAccountHandle getUserSelectedOutgoingPhoneAccount();

    /**
     * @see TelecomServiceImpl#setUserSelectedOutgoingPhoneAccount
     */
    void setUserSelectedOutgoingPhoneAccount(in PhoneAccountHandle account);

    /**
     * @see TelecomServiceImpl#getCallCapablePhoneAccounts
     */
    List<PhoneAccountHandle> getCallCapablePhoneAccounts();

    /**
     * @see TelecomManager#getPhoneAccountsSupportingScheme
     */
    List<PhoneAccountHandle> getPhoneAccountsSupportingScheme(in String uriScheme);

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
     * @see TelecomServiceImpl#setSimCallManager
     */
    void setSimCallManager(in PhoneAccountHandle account);

    /**
     * @see TelecomServiceImpl#getSimCallManagers
     */
    List<PhoneAccountHandle> getSimCallManagers();

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
    boolean isVoiceMailNumber(in PhoneAccountHandle accountHandle, String number);

    /**
     * @see TelecomServiceImpl#hasVoiceMailNumber
     */
    boolean hasVoiceMailNumber(in PhoneAccountHandle accountHandle);

    /**
     * @see TelecomServiceImpl#getLine1Number
     */
    String getLine1Number(in PhoneAccountHandle accountHandle);

    /**
     * @see TelecomServiceImpl#getDefaultPhoneApp
     */
    ComponentName getDefaultPhoneApp();

    //
    // Internal system apis relating to call management.
    //

    /**
     * @see TelecomServiceImpl#silenceRinger
     */
    void silenceRinger();

    /**
     * @see TelecomServiceImpl#isInCall
     */
    boolean isInCall();

    /**
     * @see TelecomServiceImpl#isRinging
     */
    boolean isRinging();

    /**
     * @see TelecomServiceImpl#getCallState
     */
    int getCallState();

    /**
     * @see TelecomServiceImpl#endCall
     */
    boolean endCall();

    /**
     * @see TelecomServiceImpl#acceptRingingCall
     */
    void acceptRingingCall();

    /**
     * @see TelecomServiceImpl#cancelMissedCallsNotification
     */
    void cancelMissedCallsNotification();

    /**
     * @see TelecomServiceImpl#handleMmi
     */
    boolean handlePinMmi(String dialString);

    /**
     * @see TelecomServiceImpl#handleMmi
     */
    boolean handlePinMmiForPhoneAccount(in PhoneAccountHandle accountHandle, String dialString);

    /**
     * @see TelecomServiceImpl#getAdnUriForPhoneAccount
     */
    Uri getAdnUriForPhoneAccount(in PhoneAccountHandle accountHandle);

    /**
     * @see TelecomServiceImpl#isTtySupported
     */
    boolean isTtySupported();

    /**
     * @see TelecomServiceImpl#getCurrentTtyMode
     */
    int getCurrentTtyMode();

    /**
     * @see TelecomServiceImpl#addNewIncomingCall
     */
    void addNewIncomingCall(in PhoneAccountHandle phoneAccount, in Bundle extras);

    /**
     * @see TelecomServiceImpl#addNewUnknownCall
     */
    void addNewUnknownCall(in PhoneAccountHandle phoneAccount, in Bundle extras);
}
