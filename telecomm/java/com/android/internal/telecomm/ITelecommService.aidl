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

package com.android.internal.telecomm;

import android.content.ComponentName;
import android.telecomm.PhoneAccountHandle;
import android.os.Bundle;
import android.telecomm.PhoneAccount;

/**
 * Interface used to interact with Telecomm. Mostly this is used by TelephonyManager for passing
 * commands that were previously handled by ITelephony.
 * {@hide}
 */
interface ITelecommService {
    /**
     * Brings the in-call screen to the foreground if there is an active call.
     *
     * @param showDialpad if true, make the dialpad visible initially.
     */
    void showInCallScreen(boolean showDialpad);

    /**
     * @see TelecommServiceImpl#getDefaultOutgoingPhoneAccount
     */
    PhoneAccountHandle getDefaultOutgoingPhoneAccount(in String uriScheme);

    /**
     * @see TelecommServiceImpl#getUserSelectedOutgoingPhoneAccount
     */
    PhoneAccountHandle getUserSelectedOutgoingPhoneAccount();

    /**
     * @see TelecommServiceImpl#setUserSelectedOutgoingPhoneAccount
     */
    void setUserSelectedOutgoingPhoneAccount(in PhoneAccountHandle account);

    /**
     * @see TelecommServiceImpl#getEnabledPhoneAccounts
     */
    List<PhoneAccountHandle> getEnabledPhoneAccounts();

    /**
     * @see TelecommManager#getPhoneAccountsSupportingScheme
     */
    List<PhoneAccountHandle> getPhoneAccountsSupportingScheme(in String uriScheme);

    /**
     * @see TelecommManager#getPhoneAccount
     */
    PhoneAccount getPhoneAccount(in PhoneAccountHandle account);

    /**
     * @see TelecommManager#getAllPhoneAccountsCount
     */
    int getAllPhoneAccountsCount();

    /**
     * @see TelecommManager#getAllPhoneAccounts
     */
    List<PhoneAccount> getAllPhoneAccounts();

    /**
     * @see TelecommManager#getAllPhoneAccountHandles
     */
    List<PhoneAccountHandle> getAllPhoneAccountHandles();

    /**
     * @see TelecommServiceImpl#getSimCallManager
     */
    PhoneAccountHandle getSimCallManager();

    /**
     * @see TelecommServiceImpl#setSimCallManager
     */
    void setSimCallManager(in PhoneAccountHandle account);

    /**
     * @see TelecommServiceImpl#getSimCallManagers
     */
    List<PhoneAccountHandle> getSimCallManagers();

    /**
     * @see TelecommServiceImpl#setPhoneAccountEnabled
     */
    void setPhoneAccountEnabled(in PhoneAccountHandle account, in boolean isEnabled);

    /**
     * @see TelecommServiceImpl#registerPhoneAccount
     */
    void registerPhoneAccount(in PhoneAccount metadata);

    /**
     * @see TelecommServiceImpl#unregisterPhoneAccount
     */
    void unregisterPhoneAccount(in PhoneAccountHandle account);

    /**
     * @see TelecommServiceImpl#clearAccounts
     */
    void clearAccounts(String packageName);

    /**
     * @see TelecommServiceImpl#getDefaultPhoneApp
     */
    ComponentName getDefaultPhoneApp();

    //
    // Internal system apis relating to call management.
    //

    /**
     * @see TelecommServiceImpl#silenceRinger
     */
    void silenceRinger();

    /**
     * @see TelecommServiceImpl#isInCall
     */
    boolean isInCall();

    /**
     * @see TelecommServiceImpl#isRinging
     */
    boolean isRinging();

    /**
     * @see TelecommServiceImpl#endCall
     */
    boolean endCall();

    /**
     * @see TelecommServiceImpl#acceptRingingCall
     */
    void acceptRingingCall();

    /**
     * @see TelecommServiceImpl#cancelMissedCallsNotification
     */
    void cancelMissedCallsNotification();

    /**
     * @see TelecommServiceImpl#handleMmi
     */
    boolean handlePinMmi(String dialString);

    /**
     * @see TelecommServiceImpl#isTtySupported
     */
    boolean isTtySupported();

    /**
     * @see TelecommServiceImpl#getCurrentTtyMode
     */
    int getCurrentTtyMode();

    /**
     * @see TelecommServiceImpl#addNewIncomingCall
     */
    void addNewIncomingCall(in PhoneAccountHandle phoneAccount, in Bundle extras);
}
