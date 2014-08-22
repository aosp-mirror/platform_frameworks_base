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

package android.telecomm;

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.telecomm.ITelecommService;

import java.util.List;

/**
 * Provides access to Telecomm-related functionality.
 * TODO: Move this all into PhoneManager.
 */
public class TelecommManager {

    /**
     * Activity action: Starts the UI for handing an incoming call. This intent starts the in-call
     * UI by notifying the Telecomm system that an incoming call exists for a specific call service
     * (see {@link android.telecomm.ConnectionService}). Telecomm reads the Intent extras to find
     * and bind to the appropriate {@link android.telecomm.ConnectionService} which Telecomm will
     * ultimately use to control and get information about the call.
     * <p>
     * Input: get*Extra field {@link #EXTRA_PHONE_ACCOUNT_HANDLE} contains the component name of the
     * {@link android.telecomm.ConnectionService} that Telecomm should bind to. Telecomm will then
     * ask the connection service for more information about the call prior to showing any UI.
     *
     * @hide
     */
    public static final String ACTION_INCOMING_CALL = "android.intent.action.INCOMING_CALL";

    /**
     * The {@link android.content.Intent} action used to configure a
     * {@link android.telecomm.ConnectionService}.
     */
    public static final String ACTION_CONNECTION_SERVICE_CONFIGURE =
            "android.intent.action.CONNECTION_SERVICE_CONFIGURE";

    /**
     * The {@link android.content.Intent} action used to show the call settings page.
     */
    public static final String ACTION_SHOW_CALL_SETTINGS =
            "android.telecomm.intent.action.SHOW_CALL_SETTINGS";

    /**
     * Optional extra for {@link android.content.Intent#ACTION_CALL} containing a boolean that
     * determines whether the speakerphone should be automatically turned on for an outgoing call.
     */
    public static final String EXTRA_START_CALL_WITH_SPEAKERPHONE =
            "android.intent.extra.START_CALL_WITH_SPEAKERPHONE";

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
            "android.intent.extra.START_CALL_WITH_VIDEO_STATE";

    /**
     * The extra used with an {@link android.content.Intent#ACTION_CALL} and
     * {@link android.content.Intent#ACTION_DIAL} {@code Intent} to specify a
     * {@link PhoneAccountHandle} to use when making the call.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE =
            "android.intent.extra.PHONE_ACCOUNT_HANDLE";

    /**
     * Optional extra for {@link #ACTION_INCOMING_CALL} containing a {@link Bundle} which contains
     * metadata about the call. This {@link Bundle} will be returned to the
     * {@link ConnectionService}.
     *
     * @hide
     */
    public static final String EXTRA_INCOMING_CALL_EXTRAS =
            "android.intent.extra.INCOMING_CALL_EXTRAS";

    /**
     * Optional extra for {@link android.content.Intent#ACTION_CALL} and
     * {@link android.content.Intent#ACTION_DIAL} {@code Intent} containing a {@link Bundle}
     * which contains metadata about the call. This {@link Bundle} will be saved into
     * {@code Call.Details}.
     *
     * @hide
     */
    public static final String EXTRA_OUTGOING_CALL_EXTRAS =
            "android.intent.extra.OUTGOING_CALL_EXTRAS";

    /**
     * Optional extra for {@link android.telephony.TelephonyManager#ACTION_PHONE_STATE_CHANGED}
     * containing the disconnect code.
     */
    public static final String EXTRA_CALL_DISCONNECT_CAUSE =
            "android.telecomm.extra.CALL_DISCONNECT_CAUSE";

    /**
     * Optional extra for {@link android.telephony.TelephonyManager#ACTION_PHONE_STATE_CHANGED}
     * containing the disconnect message.
     */
    public static final String EXTRA_CALL_DISCONNECT_MESSAGE =
            "android.telecomm.extra.CALL_DISCONNECT_MESSAGE";

    /**
     * Optional extra for {@link android.telephony.TelephonyManager#ACTION_PHONE_STATE_CHANGED}
     * containing the component name of the associated connection service.
     */
    public static final String EXTRA_CONNECTION_SERVICE =
            "android.telecomm.extra.CONNECTION_SERVICE";

    /**
     * The number which the party on the other side of the line will see (and use to return the
     * call).
     * <p>
     * {@link ConnectionService}s which interact with {@link RemoteConnection}s should only populate
     * this if the {@link android.telephony.TelephonyManager#getLine1Number()} value, as that is the
     * user's expected caller ID.
     */
    public static final String EXTRA_CALL_BACK_NUMBER = "android.telecomm.extra.CALL_BACK_NUMBER";

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
            "android.telecomm.intent.action.CURRENT_TTY_MODE_CHANGED";

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
            "android.telecomm.intent.extra.CURRENT_TTY_MODE";

    /**
     * Broadcast intent action indicating that the TTY preferred operating mode has changed. An
     * intent extra provides the new mode as an int.
     *
     * @see #EXTRA_TTY_PREFERRED_MODE
     * @hide
     */
    public static final String ACTION_TTY_PREFERRED_MODE_CHANGED =
            "android.telecomm.intent.action.TTY_PREFERRED_MODE_CHANGED";

    /**
     * The lookup key for an int that indicates preferred TTY mode. Valid modes are: -
     * {@link #TTY_MODE_OFF} - {@link #TTY_MODE_FULL} - {@link #TTY_MODE_HCO} -
     * {@link #TTY_MODE_VCO}
     *
     * @hide
     */
    public static final String EXTRA_TTY_PREFERRED_MODE =
            "android.telecomm.intent.extra.TTY_PREFERRED";

    private static final String TAG = "TelecommManager";

    private static final String TELECOMM_SERVICE_NAME = "telecomm";

    private final Context mContext;

    /**
     * @hide
     */
    public static TelecommManager from(Context context) {
        return (TelecommManager) context.getSystemService(Context.TELECOMM_SERVICE);
    }

    /**
     * @hide
     */
    public TelecommManager(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
    }

    /**
     * Return the {@link PhoneAccount} which is the user-chosen default for making outgoing phone
     * calls. This {@code PhoneAccount} will always be a member of the list which is returned from
     * calling {@link #getEnabledPhoneAccounts()}.
     * <p>
     * Apps must be prepared for this method to return {@code null}, indicating that there currently
     * exists no user-chosen default {@code PhoneAccount}. In this case, apps wishing to initiate a
     * phone call must either create their {@link android.content .Intent#ACTION_CALL} or
     * {@link android.content.Intent#ACTION_DIAL} {@code Intent} with no
     * {@link TelecommManager#EXTRA_PHONE_ACCOUNT_HANDLE}, or present the user with an affordance to
     * select one of the elements of {@link #getEnabledPhoneAccounts()}.
     * <p>
     * An {@link android.content.Intent#ACTION_CALL} or {@link android.content.Intent#ACTION_DIAL}
     * {@code Intent} with no {@link TelecommManager#EXTRA_PHONE_ACCOUNT_HANDLE} is valid, and
     * subsequent steps in the phone call flow are responsible for presenting the user with an
     * affordance, if necessary, to choose a {@code PhoneAccount}.
     */
    public PhoneAccountHandle getDefaultOutgoingPhoneAccount() {
        try {
            if (isServiceConnected()) {
                return getTelecommService().getDefaultOutgoingPhoneAccount();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#getDefaultOutgoingPhoneAccount", e);
        }
        return null;
    }

    /**
     * Return a list of {@link PhoneAccountHandle}s which can be used to make and receive phone
     * calls.
     *
     * @see #EXTRA_PHONE_ACCOUNT_HANDLE
     * @return A list of {@code PhoneAccountHandle} objects.
     */
    public List<PhoneAccountHandle> getEnabledPhoneAccounts() {
        try {
            if (isServiceConnected()) {
                return getTelecommService().getOutgoingPhoneAccounts();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#getOutgoingPhoneAccounts", e);
        }
        return null;
    }

    /**
     * Determine whether the device has more than one account registered and enabled.
     *
     * @return {@code true} if the device has more than one account registered and enabled and
     * {@code false} otherwise.
     */
    public boolean hasMultipleEnabledAccounts() {
        return getEnabledPhoneAccounts().size() > 1;
    }

    /**
     * Return the {@link PhoneAccount} for a specified {@link PhoneAccountHandle}. Object includes
     * resources which can be used in a user interface.
     *
     * @param account The {@link PhoneAccountHandle}.
     * @return The {@link PhoneAccount} object.
     */
    public PhoneAccount getPhoneAccount(PhoneAccountHandle account) {
        try {
            if (isServiceConnected()) {
                return getTelecommService().getPhoneAccount(account);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#getPhoneAccount", e);
        }
        return null;
    }

    /**
     * Register a {@link PhoneAccount} for use by the system.
     *
     * @param account The complete {@link PhoneAccount}.
     */
    public void registerPhoneAccount(PhoneAccount account) {
        try {
            if (isServiceConnected()) {
                getTelecommService().registerPhoneAccount(account);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#registerPhoneAccount", e);
        }
    }

    /**
     * Remove a {@link PhoneAccount} registration from the system.
     *
     * @param accountHandle A {@link PhoneAccountHandle} for the {@link PhoneAccount} to unregister.
     */
    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                getTelecommService().unregisterPhoneAccount(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#unregisterPhoneAccount", e);
        }
    }

    /**
     * Remove all Accounts for a given package from the system.
     *
     * @param packageName A package name that may have registered Accounts.
     */
    @SystemApi
    public void clearAccounts(String packageName) {
        try {
            if (isServiceConnected()) {
                getTelecommService().clearAccounts(packageName);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#clearAccounts", e);
        }
    }

    /**
     * @hide
     */
    @SystemApi
    public ComponentName getDefaultPhoneApp() {
        try {
            if (isServiceConnected()) {
                return getTelecommService().getDefaultPhoneApp();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the default phone app.", e);
        }
        return null;
    }

    /**
     * Returns whether there is an ongoing phone call (can be in dialing, ringing, active or holding
     * states).
     *
     * @hide
     */
    @SystemApi
    public boolean isInAPhoneCall() {
        try {
            if (isServiceConnected()) {
                return getTelecommService().isInAPhoneCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get default phone app.", e);
        }
        return false;
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
                return getTelecommService().isRinging();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get ringing state of phone app.", e);
        }
        return false;
    }

    /**
     * Ends an ongoing call.
     * TODO: L-release - need to convert all invocations of ITelecommService#endCall to use this
     * method (clockwork & gearhead).
     * @hide
     */
    @SystemApi
    public boolean endCall() {
        try {
            if (isServiceConnected()) {
                return getTelecommService().endCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#endCall", e);
        }
        return false;
    }

    /**
     * If there is a ringing incoming call, this method accepts the call on behalf of the user.
     * TODO: L-release - need to convert all invocation of ITelecommService#answerRingingCall to use
     * this method (clockwork & gearhead).
     *
     * @hide
     */
    @SystemApi
    public void acceptRingingCall() {
        try {
            if (isServiceConnected()) {
                getTelecommService().acceptRingingCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#acceptRingingCall", e);
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
                getTelecommService().silenceRinger();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#silenceRinger", e);
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
                return getTelecommService().isTtySupported();
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
     * - {@link TelecommManager#TTY_MODE_OFF}
     * - {@link TelecommManager#TTY_MODE_FULL}
     * - {@link TelecommManager#TTY_MODE_HCO}
     * - {@link TelecommManager#TTY_MODE_VCO}
     * @hide
     */
    public int getCurrentTtyMode() {
        try {
            if (isServiceConnected()) {
                return getTelecommService().getCurrentTtyMode();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the current TTY mode.", e);
        }
        return TTY_MODE_OFF;
    }

    /**
     * Registers a new incoming call. A {@link ConnectionService} should invoke this method when it
     * has an incoming call. The specified {@link PhoneAccountHandle} must have been registered
     * with {@link #registerPhoneAccount} and subsequently enabled by the user within the phone's
     * settings. Once invoked, this method will cause the system to bind to the
     * {@link ConnectionService} associated with the {@link PhoneAccountHandle} and request
     * additional information about the call (See
     * {@link ConnectionService#onCreateIncomingConnection}) before starting the incoming call UI.
     *
     * @param phoneAccount A {@link PhoneAccountHandle} registered with
     *            {@link #registerPhoneAccount}.
     * @param extras A bundle that will be passed through to
     *            {@link ConnectionService#onCreateIncomingConnection}.
     */
    public void addNewIncomingCall(PhoneAccountHandle phoneAccount, Bundle extras) {
        try {
            if (isServiceConnected()) {
                getTelecommService().addNewIncomingCall(
                        phoneAccount, extras == null ? new Bundle() : extras);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException adding a new incoming call: " + phoneAccount, e);
        }
    }

    private ITelecommService getTelecommService() {
        return ITelecommService.Stub.asInterface(ServiceManager.getService(TELECOMM_SERVICE_NAME));
    }

    private boolean isServiceConnected() {
        boolean isConnected = getTelecommService() != null;
        if (!isConnected) {
            Log.w(TAG, "Telecomm Service not found.");
        }
        return isConnected;
    }
}
