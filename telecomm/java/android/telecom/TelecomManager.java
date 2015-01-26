/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.telecom;

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telecom.ITelecomService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides access to information about active calls and registration/call-management functionality.
 * Apps can use methods in this class to determine the current call state.
 * <p>
 * Apps do not instantiate this class directly; instead, they retrieve a reference to an instance
 * through {@link Context#getSystemService Context.getSystemService(Context.TELECOM_SERVICE)}.
 * <p>
 * Note that access to some telecom information is permission-protected. Your app cannot access the
 * protected information or gain access to protected functionality unless it has the appropriate
 * permissions declared in its manifest file. Where permissions apply, they are noted in the method
 * descriptions.
 */
public class TelecomManager {

    /**
     * Activity action: Starts the UI for handing an incoming call. This intent starts the in-call
     * UI by notifying the Telecom system that an incoming call exists for a specific call service
     * (see {@link android.telecom.ConnectionService}). Telecom reads the Intent extras to find
     * and bind to the appropriate {@link android.telecom.ConnectionService} which Telecom will
     * ultimately use to control and get information about the call.
     * <p>
     * Input: get*Extra field {@link #EXTRA_PHONE_ACCOUNT_HANDLE} contains the component name of the
     * {@link android.telecom.ConnectionService} that Telecom should bind to. Telecom will then
     * ask the connection service for more information about the call prior to showing any UI.
     *
     * @hide
     */
    public static final String ACTION_INCOMING_CALL = "android.telecom.action.INCOMING_CALL";

    /**
     * Similar to {@link #ACTION_INCOMING_CALL}, but is used only by Telephony to add a new
     * sim-initiated MO call for carrier testing.
     * @hide
     */
    public static final String ACTION_NEW_UNKNOWN_CALL = "android.telecom.action.NEW_UNKNOWN_CALL";

    /**
     * The {@link android.content.Intent} action used to configure a
     * {@link android.telecom.ConnectionService}.
     * @hide
     */
    @SystemApi
    public static final String ACTION_CONNECTION_SERVICE_CONFIGURE =
            "android.telecom.action.CONNECTION_SERVICE_CONFIGURE";

    /**
     * The {@link android.content.Intent} action used to show the call settings page.
     */
    public static final String ACTION_SHOW_CALL_SETTINGS =
            "android.telecom.action.SHOW_CALL_SETTINGS";

    /**
     * The {@link android.content.Intent} action used to show the settings page used to configure
     * {@link PhoneAccount} preferences.
     * @hide
     */
    @SystemApi
    public static final String ACTION_CHANGE_PHONE_ACCOUNTS =
            "android.telecom.action.CHANGE_PHONE_ACCOUNTS";

    /**
     * Optional extra for {@link android.content.Intent#ACTION_CALL} containing a boolean that
     * determines whether the speakerphone should be automatically turned on for an outgoing call.
     */
    public static final String EXTRA_START_CALL_WITH_SPEAKERPHONE =
            "android.telecom.extra.START_CALL_WITH_SPEAKERPHONE";

    /**
     * Optional extra for {@link android.content.Intent#ACTION_CALL} containing an integer that
     * determines the desired video state for an outgoing call.
     * Valid options:
     * {@link VideoProfile.VideoState#AUDIO_ONLY},
     * {@link VideoProfile.VideoState#BIDIRECTIONAL},
     * {@link VideoProfile.VideoState#RX_ENABLED},
     * {@link VideoProfile.VideoState#TX_ENABLED}.
     * @hide
     */
    public static final String EXTRA_START_CALL_WITH_VIDEO_STATE =
            "android.telecom.extra.START_CALL_WITH_VIDEO_STATE";

    /**
     * The extra used with an {@link android.content.Intent#ACTION_CALL} and
     * {@link android.content.Intent#ACTION_DIAL} {@code Intent} to specify a
     * {@link PhoneAccountHandle} to use when making the call.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE =
            "android.telecom.extra.PHONE_ACCOUNT_HANDLE";

    /**
     * Optional extra for {@link #ACTION_INCOMING_CALL} containing a {@link Bundle} which contains
     * metadata about the call. This {@link Bundle} will be returned to the
     * {@link ConnectionService}.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_INCOMING_CALL_EXTRAS =
            "android.telecom.extra.INCOMING_CALL_EXTRAS";

    /**
     * Optional extra for {@link android.content.Intent#ACTION_CALL} and
     * {@link android.content.Intent#ACTION_DIAL} {@code Intent} containing a {@link Bundle}
     * which contains metadata about the call. This {@link Bundle} will be saved into
     * {@code Call.Details}.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_OUTGOING_CALL_EXTRAS =
            "android.telecom.extra.OUTGOING_CALL_EXTRAS";

    /**
     * @hide
     */
    public static final String EXTRA_UNKNOWN_CALL_HANDLE =
            "android.telecom.extra.UNKNOWN_CALL_HANDLE";

    /**
     * Optional extra for {@link android.telephony.TelephonyManager#ACTION_PHONE_STATE_CHANGED}
     * containing the disconnect code.
     */
    public static final String EXTRA_CALL_DISCONNECT_CAUSE =
            "android.telecom.extra.CALL_DISCONNECT_CAUSE";

    /**
     * Optional extra for {@link android.telephony.TelephonyManager#ACTION_PHONE_STATE_CHANGED}
     * containing the disconnect message.
     */
    public static final String EXTRA_CALL_DISCONNECT_MESSAGE =
            "android.telecom.extra.CALL_DISCONNECT_MESSAGE";

    /**
     * Optional extra for {@link android.telephony.TelephonyManager#ACTION_PHONE_STATE_CHANGED}
     * containing the component name of the associated connection service.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CONNECTION_SERVICE =
            "android.telecom.extra.CONNECTION_SERVICE";

    /**
     * An optional {@link android.content.Intent#ACTION_CALL} intent extra denoting the
     * package name of the app specifying an alternative gateway for the call.
     * The value is a string.
     *
     * (The following comment corresponds to the all GATEWAY_* extras)
     * An app which sends the {@link android.content.Intent#ACTION_CALL} intent can specify an
     * alternative address to dial which is different from the one specified and displayed to
     * the user. This alternative address is referred to as the gateway address.
     */
    public static final String GATEWAY_PROVIDER_PACKAGE =
            "android.telecom.extra.GATEWAY_PROVIDER_PACKAGE";

    /**
     * An optional {@link android.content.Intent#ACTION_CALL} intent extra corresponding to the
     * original address to dial for the call. This is used when an alternative gateway address is
     * provided to recall the original address.
     * The value is a {@link android.net.Uri}.
     *
     * (See {@link #GATEWAY_PROVIDER_PACKAGE} for details)
     */
    public static final String GATEWAY_ORIGINAL_ADDRESS =
            "android.telecom.extra.GATEWAY_ORIGINAL_ADDRESS";

    /**
     * The number which the party on the other side of the line will see (and use to return the
     * call).
     * <p>
     * {@link ConnectionService}s which interact with {@link RemoteConnection}s should only populate
     * this if the {@link android.telephony.TelephonyManager#getLine1Number()} value, as that is the
     * user's expected caller ID.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CALL_BACK_NUMBER = "android.telecom.extra.CALL_BACK_NUMBER";

    /**
     * The dual tone multi-frequency signaling character sent to indicate the dialing system should
     * pause for a predefined period.
     */
    public static final char DTMF_CHARACTER_PAUSE = ',';

    /**
     * The dual-tone multi-frequency signaling character sent to indicate the dialing system should
     * wait for user confirmation before proceeding.
     */
    public static final char DTMF_CHARACTER_WAIT = ';';

    /**
     * TTY (teletypewriter) mode is off.
     *
     * @hide
     */
    public static final int TTY_MODE_OFF = 0;

    /**
     * TTY (teletypewriter) mode is on. The speaker is off and the microphone is muted. The user
     * will communicate with the remote party by sending and receiving text messages.
     *
     * @hide
     */
    public static final int TTY_MODE_FULL = 1;

    /**
     * TTY (teletypewriter) mode is in hearing carryover mode (HCO). The microphone is muted but the
     * speaker is on. The user will communicate with the remote party by sending text messages and
     * hearing an audible reply.
     *
     * @hide
     */
    public static final int TTY_MODE_HCO = 2;

    /**
     * TTY (teletypewriter) mode is in voice carryover mode (VCO). The speaker is off but the
     * microphone is still on. User will communicate with the remote party by speaking and receiving
     * text message replies.
     *
     * @hide
     */
    public static final int TTY_MODE_VCO = 3;

    /**
     * Broadcast intent action indicating that the current TTY mode has changed. An intent extra
     * provides this state as an int.
     *
     * @see #EXTRA_CURRENT_TTY_MODE
     * @hide
     */
    public static final String ACTION_CURRENT_TTY_MODE_CHANGED =
            "android.telecom.action.CURRENT_TTY_MODE_CHANGED";

    /**
     * The lookup key for an int that indicates the current TTY mode.
     * Valid modes are:
     * - {@link #TTY_MODE_OFF}
     * - {@link #TTY_MODE_FULL}
     * - {@link #TTY_MODE_HCO}
     * - {@link #TTY_MODE_VCO}
     *
     * @hide
     */
    public static final String EXTRA_CURRENT_TTY_MODE =
            "android.telecom.intent.extra.CURRENT_TTY_MODE";

    /**
     * Broadcast intent action indicating that the TTY preferred operating mode has changed. An
     * intent extra provides the new mode as an int.
     *
     * @see #EXTRA_TTY_PREFERRED_MODE
     * @hide
     */
    public static final String ACTION_TTY_PREFERRED_MODE_CHANGED =
            "android.telecom.action.TTY_PREFERRED_MODE_CHANGED";

    /**
     * The lookup key for an int that indicates preferred TTY mode. Valid modes are: -
     * {@link #TTY_MODE_OFF} - {@link #TTY_MODE_FULL} - {@link #TTY_MODE_HCO} -
     * {@link #TTY_MODE_VCO}
     *
     * @hide
     */
    public static final String EXTRA_TTY_PREFERRED_MODE =
            "android.telecom.intent.extra.TTY_PREFERRED";

    /**
     * The following 4 constants define how properties such as phone numbers and names are
     * displayed to the user.
     */

    /** Property is displayed normally. */
    public static final int PRESENTATION_ALLOWED = 1;

    /** Property was blocked. */
    public static final int PRESENTATION_RESTRICTED = 2;

    /** Presentation was not specified or is unknown. */
    public static final int PRESENTATION_UNKNOWN = 3;

    /** Property should be displayed as a pay phone. */
    public static final int PRESENTATION_PAYPHONE = 4;

    private static final String TAG = "TelecomManager";

    private final Context mContext;

    /**
     * @hide
     */
    public static TelecomManager from(Context context) {
        return (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    }

    /**
     * @hide
     */
    public TelecomManager(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
    }

    /**
     * Return the {@link PhoneAccount} which is the user-chosen default for making outgoing phone
     * calls with a specified URI scheme.
     * <p>
     * Apps must be prepared for this method to return {@code null}, indicating that there currently
     * exists no user-chosen default {@code PhoneAccount}.
     * <p>
     * @param uriScheme The URI scheme.
     * @return The {@link PhoneAccountHandle} corresponding to the user-chosen default for outgoing
     * phone calls for a specified URI scheme.
     * @hide
     */
    @SystemApi
    public PhoneAccountHandle getDefaultOutgoingPhoneAccount(String uriScheme) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getDefaultOutgoingPhoneAccount(uriScheme);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getDefaultOutgoingPhoneAccount", e);
        }
        return null;
    }

    /**
     * Return the {@link PhoneAccount} which is the user-chosen default for making outgoing phone
     * calls. This {@code PhoneAccount} will always be a member of the list which is returned from
     * calling {@link #getCallCapablePhoneAccounts()}
     *
     * Apps must be prepared for this method to return {@code null}, indicating that there currently
     * exists no user-chosen default {@code PhoneAccount}.
     *
     * @return The user outgoing phone account selected by the user.
     * @hide
     */
    public PhoneAccountHandle getUserSelectedOutgoingPhoneAccount() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getUserSelectedOutgoingPhoneAccount();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getUserSelectedOutgoingPhoneAccount", e);
        }
        return null;
    }

    /**
     * Sets the default account for making outgoing phone calls.
     * @hide
     */
    public void setUserSelectedOutgoingPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                getTelecomService().setUserSelectedOutgoingPhoneAccount(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#setUserSelectedOutgoingPhoneAccount");
        }
    }

    /**
     * Returns the current SIM call manager. Apps must be prepared for this method to return
     * {@code null}, indicating that there currently exists no user-chosen default
     * {@code PhoneAccount}.
     * @return The phone account handle of the current sim call manager.
     * @hide
     */
    public PhoneAccountHandle getSimCallManager() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getSimCallManager();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getSimCallManager");
        }
        return null;
    }

    /**
     * Sets the SIM call manager to the specified phone account.
     * @param accountHandle The phone account handle of the account to set as the sim call manager.
     * @hide
     */
    public void setSimCallManager(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                getTelecomService().setSimCallManager(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#setSimCallManager");
        }
    }

    /**
     * Returns the list of registered SIM call managers.
     * @return List of registered SIM call managers.
     * @hide
     */
    public List<PhoneAccountHandle> getSimCallManagers() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getSimCallManagers();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getSimCallManagers");
        }
        return new ArrayList<>();
    }

    /**
     * Returns the current connection manager. Apps must be prepared for this method to return
     * {@code null}, indicating that there currently exists no user-chosen default
     * {@code PhoneAccount}.
     *
     * @return The phone account handle of the current connection manager.
     * @hide
     */
    @SystemApi
    public PhoneAccountHandle getConnectionManager() {
        return getSimCallManager();
    }

    /**
     * Returns the list of registered SIM call managers.
     * @return List of registered SIM call managers.
     * @hide
     */
    @SystemApi
    public List<PhoneAccountHandle> getRegisteredConnectionManagers() {
        return getSimCallManagers();
    }

    /**
     * Returns a list of the {@link PhoneAccountHandle}s which can be used to make and receive phone
     * calls which support the specified URI scheme.
     * <P>
     * For example, invoking with {@code "tel"} will find all {@link PhoneAccountHandle}s which
     * support telephone calls (e.g. URIs such as {@code tel:555-555-1212}).  Invoking with
     * {@code "sip"} will find all {@link PhoneAccountHandle}s which support SIP calls (e.g. URIs
     * such as {@code sip:example@sipexample.com}).
     *
     * @param uriScheme The URI scheme.
     * @return A list of {@code PhoneAccountHandle} objects supporting the URI scheme.
     * @hide
     */
    @SystemApi
    public List<PhoneAccountHandle> getPhoneAccountsSupportingScheme(String uriScheme) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getPhoneAccountsSupportingScheme(uriScheme);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getPhoneAccountsSupportingScheme", e);
        }
        return new ArrayList<>();
    }


    /**
     * Return a list of {@link PhoneAccountHandle}s which can be used to make and receive phone
     * calls.
     *
     * @see #EXTRA_PHONE_ACCOUNT_HANDLE
     * @return A list of {@code PhoneAccountHandle} objects.
     *
     * @hide
     */
    public List<PhoneAccountHandle> getCallCapablePhoneAccounts() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getCallCapablePhoneAccounts();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getCallCapablePhoneAccounts", e);
        }
        return new ArrayList<>();
    }

    /**
     * Determine whether the device has more than one account registered that can make and receive
     * phone calls.
     *
     * @return {@code true} if the device has more than one account registered and {@code false}
     * otherwise.
     * @hide
     */
    @SystemApi
    public boolean hasMultipleCallCapableAccounts() {
        return getCallCapablePhoneAccounts().size() > 1;
    }

    /**
     *  Returns a list of all {@link PhoneAccount}s registered for the calling package.
     *
     * @return A list of {@code PhoneAccountHandle} objects.
     * @hide
     */
    @SystemApi
    public List<PhoneAccountHandle> getPhoneAccountsForPackage() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getPhoneAccountsForPackage(mContext.getPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getPhoneAccountsForPackage", e);
        }
        return null;
    }

    /**
     * Return the {@link PhoneAccount} for a specified {@link PhoneAccountHandle}. Object includes
     * resources which can be used in a user interface.
     *
     * @param account The {@link PhoneAccountHandle}.
     * @return The {@link PhoneAccount} object.
     * @hide
     */
    @SystemApi
    public PhoneAccount getPhoneAccount(PhoneAccountHandle account) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getPhoneAccount(account);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getPhoneAccount", e);
        }
        return null;
    }

    /**
     * Returns a count of all {@link PhoneAccount}s.
     *
     * @return The count of {@link PhoneAccount}s.
     * @hide
     */
    @SystemApi
    public int getAllPhoneAccountsCount() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getAllPhoneAccountsCount();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getAllPhoneAccountsCount", e);
        }
        return 0;
    }

    /**
     * Returns a list of all {@link PhoneAccount}s.
     *
     * @return All {@link PhoneAccount}s.
     * @hide
     */
    @SystemApi
    public List<PhoneAccount> getAllPhoneAccounts() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getAllPhoneAccounts();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getAllPhoneAccounts", e);
        }
        return Collections.EMPTY_LIST;
    }

    /**
     * Returns a list of all {@link PhoneAccountHandle}s.
     *
     * @return All {@link PhoneAccountHandle}s.
     * @hide
     */
    @SystemApi
    public List<PhoneAccountHandle> getAllPhoneAccountHandles() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getAllPhoneAccountHandles();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getAllPhoneAccountHandles", e);
        }
        return Collections.EMPTY_LIST;
    }

    /**
     * Register a {@link PhoneAccount} for use by the system. When registering
     * {@link PhoneAccount}s, existing registrations will be overwritten if the
     * {@link PhoneAccountHandle} matches that of a {@link PhoneAccount} which is already
     * registered. Once registered, the {@link PhoneAccount} is listed to the user as an option
     * when placing calls. The user may still need to enable the {@link PhoneAccount} within
     * the phone app settings before the account is usable.
     * <p>
     * A {@link SecurityException} will be thrown if an app tries to register a
     * {@link PhoneAccountHandle} where the package name specified within
     * {@link PhoneAccountHandle#getComponentName()} does not match the package name of the app.
     *
     * @param account The complete {@link PhoneAccount}.
     *
     * @hide
     */
    @SystemApi
    public void registerPhoneAccount(PhoneAccount account) {
        try {
            if (isServiceConnected()) {
                getTelecomService().registerPhoneAccount(account);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#registerPhoneAccount", e);
        }
    }

    /**
     * Remove a {@link PhoneAccount} registration from the system.
     *
     * @param accountHandle A {@link PhoneAccountHandle} for the {@link PhoneAccount} to unregister.
     * @hide
     */
    @SystemApi
    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                getTelecomService().unregisterPhoneAccount(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#unregisterPhoneAccount", e);
        }
    }

    /**
     * Remove all Accounts that belong to the calling package from the system.
     * @hide
     */
    @SystemApi
    public void clearAccounts() {
        try {
            if (isServiceConnected()) {
                getTelecomService().clearAccounts(mContext.getPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#clearAccounts", e);
        }
    }

    /**
     * Remove all Accounts that belong to the specified package from the system.
     * @hide
     */
    public void clearAccountsForPackage(String packageName) {
        try {
            if (isServiceConnected() && !TextUtils.isEmpty(packageName)) {
                getTelecomService().clearAccounts(packageName);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#clearAccountsForPackage", e);
        }
    }

    /**
     * @hide
     */
    @SystemApi
    public ComponentName getDefaultPhoneApp() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getDefaultPhoneApp();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the default phone app.", e);
        }
        return null;
    }

    /**
     * Return whether a given phone number is the configured voicemail number for a
     * particular phone account.
     *
     * @param accountHandle The handle for the account to check the voicemail number against
     * @param number The number to look up.
     *
     * @hide
     */
    @SystemApi
    public boolean isVoiceMailNumber(PhoneAccountHandle accountHandle, String number) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isVoiceMailNumber(accountHandle, number);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling ITelecomService#isVoiceMailNumber.", e);
        }
        return false;
    }

    /**
     * Return whether a given phone account has a voicemail number configured.
     *
     * @param accountHandle The handle for the account to check for a voicemail number.
     * @return {@code true} If the given phone account has a voicemail number.
     *
     * @hide
     */
    @SystemApi
    public boolean hasVoiceMailNumber(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().hasVoiceMailNumber(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling ITelecomService#hasVoiceMailNumber.", e);
        }
        return false;
    }

    /**
     * Return the line 1 phone number for given phone account.
     *
     * @param accountHandle The handle for the account retrieve a number for.
     * @return A string representation of the line 1 phone number.
     *
     * @hide
     */
    @SystemApi
    public String getLine1Number(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getLine1Number(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling ITelecomService#getLine1Number.", e);
        }
        return null;
    }

    /**
     * Returns whether there is an ongoing phone call (can be in dialing, ringing, active or holding
     * states).
     * <p>
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     * </p>
     */
    public boolean isInCall() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isInCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling isInCall().", e);
        }
        return false;
    }

    /**
     * Returns one of the following constants that represents the current state of Telecom:
     *
     * {@link TelephonyManager#CALL_STATE_RINGING}
     * {@link TelephonyManager#CALL_STATE_OFFHOOK}
     * {@link TelephonyManager#CALL_STATE_IDLE}
     *
     * Note that this API does not require the
     * {@link android.Manifest.permission#READ_PHONE_STATE} permission. This is intentional, to
     * preserve the behavior of {@link TelephonyManager#getCallState()}, which also did not require
     * the permission.
     * @hide
     */
    @SystemApi
    public int getCallState() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getCallState();
            }
        } catch (RemoteException e) {
            Log.d(TAG, "RemoteException calling getCallState().", e);
        }
        return TelephonyManager.CALL_STATE_IDLE;
    }

    /**
     * Returns whether there currently exists is a ringing incoming-call.
     *
     * @hide
     */
    @SystemApi
    public boolean isRinging() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isRinging();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get ringing state of phone app.", e);
        }
        return false;
    }

    /**
     * Ends an ongoing call.
     * TODO: L-release - need to convert all invocations of ITelecomService#endCall to use this
     * method (clockwork & gearhead).
     * @hide
     */
    @SystemApi
    public boolean endCall() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().endCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#endCall", e);
        }
        return false;
    }

    /**
     * If there is a ringing incoming call, this method accepts the call on behalf of the user.
     * TODO: L-release - need to convert all invocation of ITelecmmService#answerRingingCall to use
     * this method (clockwork & gearhead).
     *
     * @hide
     */
    @SystemApi
    public void acceptRingingCall() {
        try {
            if (isServiceConnected()) {
                getTelecomService().acceptRingingCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#acceptRingingCall", e);
        }
    }

    /**
     * Silences the ringer if a ringing call exists.
     *
     * @hide
     */
    @SystemApi
    public void silenceRinger() {
        try {
            if (isServiceConnected()) {
                getTelecomService().silenceRinger();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#silenceRinger", e);
        }
    }

    /**
     * Returns whether TTY is supported on this device.
     *
     * @hide
     */
    @SystemApi
    public boolean isTtySupported() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isTtySupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get TTY supported state.", e);
        }
        return false;
    }

    /**
     * Returns the current TTY mode of the device. For TTY to be on the user must enable it in
     * settings and have a wired headset plugged in.
     * Valid modes are:
     * - {@link TelecomManager#TTY_MODE_OFF}
     * - {@link TelecomManager#TTY_MODE_FULL}
     * - {@link TelecomManager#TTY_MODE_HCO}
     * - {@link TelecomManager#TTY_MODE_VCO}
     * @hide
     */
    public int getCurrentTtyMode() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getCurrentTtyMode();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the current TTY mode.", e);
        }
        return TTY_MODE_OFF;
    }

    /**
     * Registers a new incoming call. A {@link ConnectionService} should invoke this method when it
     * has an incoming call. The specified {@link PhoneAccountHandle} must have been registered
     * with {@link #registerPhoneAccount}. Once invoked, this method will cause the system to bind
     * to the {@link ConnectionService} associated with the {@link PhoneAccountHandle} and request
     * additional information about the call (See
     * {@link ConnectionService#onCreateIncomingConnection}) before starting the incoming call UI.
     *
     * @param phoneAccount A {@link PhoneAccountHandle} registered with
     *            {@link #registerPhoneAccount}.
     * @param extras A bundle that will be passed through to
     *            {@link ConnectionService#onCreateIncomingConnection}.
     * @hide
     */
    @SystemApi
    public void addNewIncomingCall(PhoneAccountHandle phoneAccount, Bundle extras) {
        try {
            if (isServiceConnected()) {
                getTelecomService().addNewIncomingCall(
                        phoneAccount, extras == null ? new Bundle() : extras);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException adding a new incoming call: " + phoneAccount, e);
        }
    }

    /**
     * Registers a new unknown call with Telecom. This can only be called by the system Telephony
     * service. This is invoked when Telephony detects a new unknown connection that was neither
     * a new incoming call, nor an user-initiated outgoing call.
     *
     * @param phoneAccount A {@link PhoneAccountHandle} registered with
     *            {@link #registerPhoneAccount}.
     * @param extras A bundle that will be passed through to
     *            {@link ConnectionService#onCreateIncomingConnection}.
     * @hide
     */
    @SystemApi
    public void addNewUnknownCall(PhoneAccountHandle phoneAccount, Bundle extras) {
        try {
            if (isServiceConnected()) {
                getTelecomService().addNewUnknownCall(
                        phoneAccount, extras == null ? new Bundle() : extras);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException adding a new unknown call: " + phoneAccount, e);
        }
    }

    /**
     * Processes the specified dial string as an MMI code.
     * MMI codes are any sequence of characters entered into the dialpad that contain a "*" or "#".
     * Some of these sequences launch special behavior through handled by Telephony.
     * This method uses the default subscription.
     * <p>
     * Requires that the method-caller be set as the system dialer app.
     * </p>
     *
     * @param dialString The digits to dial.
     * @return True if the digits were processed as an MMI code, false otherwise.
     */
    public boolean handleMmi(String dialString) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                return service.handlePinMmi(dialString);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#handlePinMmi", e);
            }
        }
        return false;
    }

    /**
     * Processes the specified dial string as an MMI code.
     * MMI codes are any sequence of characters entered into the dialpad that contain a "*" or "#".
     * Some of these sequences launch special behavior through handled by Telephony.
     * <p>
     * Requires that the method-caller be set as the system dialer app.
     * </p>
     *
     * @param accountHandle The handle for the account the MMI code should apply to.
     * @param dialString The digits to dial.
     * @return True if the digits were processed as an MMI code, false otherwise.
     * @hide
     */
    @SystemApi
    public boolean handleMmi(PhoneAccountHandle accountHandle, String dialString) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                return service.handlePinMmiForPhoneAccount(accountHandle, dialString);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#handlePinMmi", e);
            }
        }
        return false;
    }

    /**
     * @param accountHandle The handle for the account to derive an adn query URI for or
     * {@code null} to return a URI which will use the default account.
     * @return The URI (with the content:// scheme) specific to the specified {@link PhoneAccount}
     * for the the content retrieve.
     * @hide
     */
    @SystemApi
    public Uri getAdnUriForPhoneAccount(PhoneAccountHandle accountHandle) {
        ITelecomService service = getTelecomService();
        if (service != null && accountHandle != null) {
            try {
                return service.getAdnUriForPhoneAccount(accountHandle);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#getAdnUriForPhoneAccount", e);
            }
        }
        return Uri.parse("content://icc/adn");
    }

    /**
     * Removes the missed-call notification if one is present.
     * <p>
     * Requires that the method-caller be set as the system dialer app.
     * </p>
     */
    public void cancelMissedCallsNotification() {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                service.cancelMissedCallsNotification();
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#cancelMissedCallsNotification", e);
            }
        }
    }

    /**
     * Brings the in-call screen to the foreground if there is an ongoing call. If there is
     * currently no ongoing call, then this method does nothing.
     * <p>
     * Requires that the method-caller be set as the system dialer app or have the
     * {@link android.Manifest.permission#READ_PHONE_STATE} permission.
     * </p>
     *
     * @param showDialpad Brings up the in-call dialpad as part of showing the in-call screen.
     */
    public void showInCallScreen(boolean showDialpad) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                service.showInCallScreen(showDialpad);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#showCallScreen", e);
            }
        }
    }

    private ITelecomService getTelecomService() {
        return ITelecomService.Stub.asInterface(ServiceManager.getService(Context.TELECOM_SERVICE));
    }

    private boolean isServiceConnected() {
        boolean isConnected = getTelecomService() != null;
        if (!isConnected) {
            Log.w(TAG, "Telecom Service not found.");
        }
        return isConnected;
    }
}
