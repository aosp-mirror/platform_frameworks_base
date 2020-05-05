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

import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressAutoDoc;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.telephony.Annotation.CallState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telecom.ITelecomService;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

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
@SuppressAutoDoc
@SystemService(Context.TELECOM_SERVICE)
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
     * @deprecated Use {@link #addNewIncomingCall} instead.
     */
    public static final String ACTION_INCOMING_CALL = "android.telecom.action.INCOMING_CALL";

    /**
     * Similar to {@link #ACTION_INCOMING_CALL}, but is used only by Telephony to add a new
     * sim-initiated MO call for carrier testing.
     * @deprecated Use {@link #addNewUnknownCall} instead.
     * @hide
     */
    public static final String ACTION_NEW_UNKNOWN_CALL = "android.telecom.action.NEW_UNKNOWN_CALL";

    /**
     * An {@link android.content.Intent} action sent by the telecom framework to start a
     * configuration dialog for a registered {@link PhoneAccount}. There is no default dialog
     * and each app that registers a {@link PhoneAccount} should provide one if desired.
     * <p>
     * A user can access the list of enabled {@link android.telecom.PhoneAccount}s through the Phone
     * app's settings menu. For each entry, the settings app will add a click action. When
     * triggered, the click-action will start this intent along with the extra
     * {@link #EXTRA_PHONE_ACCOUNT_HANDLE} to indicate the {@link PhoneAccount} to configure. If the
     * {@link PhoneAccount} package does not register an {@link android.app.Activity} for this
     * intent, then it will not be sent.
     */
    public static final String ACTION_CONFIGURE_PHONE_ACCOUNT =
            "android.telecom.action.CONFIGURE_PHONE_ACCOUNT";

    /**
     * The {@link android.content.Intent} action used to show the call accessibility settings page.
     */
    public static final String ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS =
            "android.telecom.action.SHOW_CALL_ACCESSIBILITY_SETTINGS";

    /**
     * The {@link android.content.Intent} action used to show the call settings page.
     */
    public static final String ACTION_SHOW_CALL_SETTINGS =
            "android.telecom.action.SHOW_CALL_SETTINGS";

    /**
     * The {@link android.content.Intent} action used to show the respond via SMS settings page.
     */
    public static final String ACTION_SHOW_RESPOND_VIA_SMS_SETTINGS =
            "android.telecom.action.SHOW_RESPOND_VIA_SMS_SETTINGS";

    /**
     * The {@link android.content.Intent} action used to show the settings page used to configure
     * {@link PhoneAccount} preferences.
     */
    public static final String ACTION_CHANGE_PHONE_ACCOUNTS =
            "android.telecom.action.CHANGE_PHONE_ACCOUNTS";

    /**
     * {@link android.content.Intent} action used indicate that a new phone account was just
     * registered.
     * <p>
     * The Intent {@link Intent#getExtras() extras} will contain {@link #EXTRA_PHONE_ACCOUNT_HANDLE}
     * to indicate which {@link PhoneAccount} was registered.
     * <p>
     * Will only be sent to the default dialer app (see {@link #getDefaultDialerPackage()}).
     */
    public static final String ACTION_PHONE_ACCOUNT_REGISTERED =
            "android.telecom.action.PHONE_ACCOUNT_REGISTERED";

    /**
     * {@link android.content.Intent} action used indicate that a phone account was just
     * unregistered.
     * <p>
     * The Intent {@link Intent#getExtras() extras} will contain {@link #EXTRA_PHONE_ACCOUNT_HANDLE}
     * to indicate which {@link PhoneAccount} was unregistered.
     * <p>
     * Will only be sent to the default dialer app (see {@link #getDefaultDialerPackage()}).
     */
    public static final String ACTION_PHONE_ACCOUNT_UNREGISTERED =
            "android.telecom.action.PHONE_ACCOUNT_UNREGISTERED";

    /**
     * Activity action: Shows a dialog asking the user whether or not they want to replace the
     * current default Dialer with the one specified in
     * {@link #EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME}.
     *
     * Usage example:
     * <pre>
     * Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
     * intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
     *         getActivity().getPackageName());
     * startActivity(intent);
     * </pre>
     * <p>
     * This is no longer supported since Q, please use
     * {@link android.app.role.RoleManager#createRequestRoleIntent(String)} with
     * {@link android.app.role.RoleManager#ROLE_DIALER} instead.
     */
    public static final String ACTION_CHANGE_DEFAULT_DIALER =
            "android.telecom.action.CHANGE_DEFAULT_DIALER";

    /**
     * Broadcast intent action indicating that the current default dialer has changed.
     * The string extra {@link #EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME} will contain the
     * name of the package that the default dialer was changed to.
     *
     * @see #EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME
     */
    public static final String ACTION_DEFAULT_DIALER_CHANGED =
            "android.telecom.action.DEFAULT_DIALER_CHANGED";

    /**
     * Extra value used to provide the package name for {@link #ACTION_CHANGE_DEFAULT_DIALER}.
     */
    public static final String EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME =
            "android.telecom.extra.CHANGE_DEFAULT_DIALER_PACKAGE_NAME";

    /**
     * Broadcast intent action indicating that the current default call screening app has changed.
     * <p>
     * Note: This intent is NEVER actually broadcast and will be deprecated in the future.
     * <p>
     * An app that want to know if it holds the
     * {@link android.app.role.RoleManager#ROLE_CALL_SCREENING} role can use
     * {@link android.app.role.RoleManager#isRoleHeld(String)} to confirm if it holds the role or
     * not.
     */
    public static final String ACTION_DEFAULT_CALL_SCREENING_APP_CHANGED =
        "android.telecom.action.DEFAULT_CALL_SCREENING_APP_CHANGED";

    /**
     * Extra value used with {@link #ACTION_DEFAULT_CALL_SCREENING_APP_CHANGED} broadcast to
     * indicate the ComponentName of the call screening app which has changed.
     * <p>
     * Note: This extra is NOT used and will be deprecated in the future.
     */
    public static final String EXTRA_DEFAULT_CALL_SCREENING_APP_COMPONENT_NAME =
            "android.telecom.extra.DEFAULT_CALL_SCREENING_APP_COMPONENT_NAME";

    /**
     * Extra value used with {@link #ACTION_DEFAULT_CALL_SCREENING_APP_CHANGED} broadcast to
     * indicate whether an app is the default call screening app.
     * <p>
     * Note: This extra is NOT used and will be deprecated in the future.
     */
    public static final String EXTRA_IS_DEFAULT_CALL_SCREENING_APP =
            "android.telecom.extra.IS_DEFAULT_CALL_SCREENING_APP";

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
     * {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_RX_ENABLED},
     * {@link VideoProfile#STATE_TX_ENABLED}.
     */
    public static final String EXTRA_START_CALL_WITH_VIDEO_STATE =
            "android.telecom.extra.START_CALL_WITH_VIDEO_STATE";

    /**
     * Optional extra for {@link #addNewIncomingCall(PhoneAccountHandle, Bundle)} containing an
     * integer that determines the requested video state for an incoming call.
     * Valid options:
     * {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_RX_ENABLED},
     * {@link VideoProfile#STATE_TX_ENABLED}.
     */
    public static final String EXTRA_INCOMING_VIDEO_STATE =
            "android.telecom.extra.INCOMING_VIDEO_STATE";

    /**
     * The extra used with an {@link android.content.Intent#ACTION_CALL} and
     * {@link android.content.Intent#ACTION_DIAL} {@code Intent} to specify a
     * {@link PhoneAccountHandle} to use when making the call.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE =
            "android.telecom.extra.PHONE_ACCOUNT_HANDLE";

    /**
     * Optional extra for {@link android.content.Intent#ACTION_CALL} containing a string call
     * subject which will be associated with an outgoing call.  Should only be specified if the
     * {@link PhoneAccount} supports the capability {@link PhoneAccount#CAPABILITY_CALL_SUBJECT}.
     */
    public static final String EXTRA_CALL_SUBJECT = "android.telecom.extra.CALL_SUBJECT";

    /**
     * The extra used by a {@link ConnectionService} to provide the handle of the caller that
     * has initiated a new incoming call.
     */
    public static final String EXTRA_INCOMING_CALL_ADDRESS =
            "android.telecom.extra.INCOMING_CALL_ADDRESS";

    /**
     * Optional extra for {@link #ACTION_INCOMING_CALL} containing a {@link Bundle} which contains
     * metadata about the call. This {@link Bundle} will be returned to the
     * {@link ConnectionService}.
     */
    public static final String EXTRA_INCOMING_CALL_EXTRAS =
            "android.telecom.extra.INCOMING_CALL_EXTRAS";

    /**
     * Optional extra for {@link #ACTION_INCOMING_CALL} containing a boolean to indicate that the
     * call has an externally generated ringer. Used by the HfpClientConnectionService when In Band
     * Ringtone is enabled to prevent two ringers from being generated.
     * @hide
     */
    public static final String EXTRA_CALL_EXTERNAL_RINGER =
            "android.telecom.extra.CALL_EXTERNAL_RINGER";

    /**
     * Optional extra for {@link android.content.Intent#ACTION_CALL} and
     * {@link android.content.Intent#ACTION_DIAL} {@code Intent} containing a {@link Bundle}
     * which contains metadata about the call. This {@link Bundle} will be saved into
     * {@code Call.Details} and passed to the {@link ConnectionService} when placing the call.
     */
    public static final String EXTRA_OUTGOING_CALL_EXTRAS =
            "android.telecom.extra.OUTGOING_CALL_EXTRAS";

    /**
     * An optional boolean extra on {@link android.content.Intent#ACTION_CALL_EMERGENCY} to tell
     * whether the user's dial intent is emergency; this is required to specify when the dialed
     * number is ambiguous, identified as both emergency number and any other non-emergency number;
     * e.g. in some situation, 611 could be both an emergency number in a country and a
     * non-emergency number of a carrier's customer service hotline.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_IS_USER_INTENT_EMERGENCY_CALL =
            "android.telecom.extra.IS_USER_INTENT_EMERGENCY_CALL";

    /**
     * A mandatory extra containing a {@link Uri} to be passed in when calling
     * {@link #addNewUnknownCall(PhoneAccountHandle, Bundle)}. The {@link Uri} value indicates
     * the remote handle of the new call.
     * @hide
     */
    public static final String EXTRA_UNKNOWN_CALL_HANDLE =
            "android.telecom.extra.UNKNOWN_CALL_HANDLE";

    /**
     * Optional extra for incoming and outgoing calls containing a long which specifies the time the
     * call was created. This value is in milliseconds since boot.
     * @hide
     */
    public static final String EXTRA_CALL_CREATED_TIME_MILLIS =
            "android.telecom.extra.CALL_CREATED_TIME_MILLIS";

    /**
     * Optional extra for incoming and outgoing calls containing a long which specifies the Epoch
     * time the call was created.
     * @hide
     */
    public static final String EXTRA_CALL_CREATED_EPOCH_TIME_MILLIS =
            "android.telecom.extra.CALL_CREATED_EPOCH_TIME_MILLIS";

    /**
     * Optional extra for incoming and outgoing calls containing a long which specifies the time
     * telecom began routing the call. This value is in milliseconds since boot.
     * @hide
     */
    public static final String EXTRA_CALL_TELECOM_ROUTING_START_TIME_MILLIS =
            "android.telecom.extra.CALL_TELECOM_ROUTING_START_TIME_MILLIS";

    /**
     * Optional extra for incoming and outgoing calls containing a long which specifies the time
     * telecom finished routing the call. This value is in milliseconds since boot.
     * @hide
     */
    public static final String EXTRA_CALL_TELECOM_ROUTING_END_TIME_MILLIS =
            "android.telecom.extra.CALL_TELECOM_ROUTING_END_TIME_MILLIS";

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
     * Optional extra for communicating the call technology used by a {@link ConnectionService}
     * to Telecom. Valid values are:
     * <ul>
     *     <li>{@link TelephonyManager#PHONE_TYPE_CDMA}</li>
     *     <li>{@link TelephonyManager#PHONE_TYPE_GSM}</li>
     *     <li>{@link TelephonyManager#PHONE_TYPE_IMS}</li>
     *     <li>{@link TelephonyManager#PHONE_TYPE_THIRD_PARTY}</li>
     *     <li>{@link TelephonyManager#PHONE_TYPE_SIP}</li>
     * </ul>
     * @hide
     */
    public static final String EXTRA_CALL_TECHNOLOGY_TYPE =
            "android.telecom.extra.CALL_TECHNOLOGY_TYPE";

    /**
     * Optional extra for communicating the call network technology used by a
     * {@link android.telecom.Connection} to Telecom and InCallUI.
     *
     * {@code NETWORK_TYPE_*} in {@link android.telephony.TelephonyManager}.
     */
    public static final String EXTRA_CALL_NETWORK_TYPE =
            "android.telecom.extra.CALL_NETWORK_TYPE";

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
     */
    public static final String EXTRA_CALL_BACK_NUMBER = "android.telecom.extra.CALL_BACK_NUMBER";

    /**
     * The number of milliseconds that Telecom should wait after disconnecting a call via the
     * ACTION_NEW_OUTGOING_CALL broadcast, in order to wait for the app which cancelled the call
     * to make a new one.
     * @hide
     */
    public static final String EXTRA_NEW_OUTGOING_CALL_CANCEL_TIMEOUT =
            "android.telecom.extra.NEW_OUTGOING_CALL_CANCEL_TIMEOUT";

    /**
     * Boolean extra specified to indicate that the intention of adding a call is to handover an
     * existing call from the user's device to a different {@link PhoneAccount}.
     * <p>
     * Used when calling {@link #addNewIncomingCall(PhoneAccountHandle, Bundle)}
     * to indicate to Telecom that the purpose of adding a new incoming call is to handover an
     * existing call from the user's device to a different {@link PhoneAccount}.  This occurs on
     * the receiving side of a handover.
     * <p>
     * Used when Telecom calls
     * {@link ConnectionService#onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}
     * to indicate that the purpose of Telecom requesting a new outgoing connection it to request
     * a handover to this {@link ConnectionService} from an ongoing call on the user's device.  This
     * occurs on the initiating side of a handover.
     * <p>
     * The phone number of the call used by Telecom to determine which call should be handed over.
     * @hide
     * @deprecated Use the public handover APIs.  See
     * {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} for more information.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 119305590)
    public static final String EXTRA_IS_HANDOVER = "android.telecom.extra.IS_HANDOVER";

    /**
     * When {@code true} indicates that a request to create a new connection is for the purpose of
     * a handover.  Note: This is used with the
     * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)} API as part of the
     * internal communication mechanism with the {@link android.telecom.ConnectionService}.  It is
     * not the same as the legacy {@link #EXTRA_IS_HANDOVER} extra.
     * @hide
     */
    public static final String EXTRA_IS_HANDOVER_CONNECTION =
            "android.telecom.extra.IS_HANDOVER_CONNECTION";

    /**
     * Parcelable extra used with {@link #EXTRA_IS_HANDOVER} to indicate the source
     * {@link PhoneAccountHandle} when initiating a handover which {@link ConnectionService}
     * the handover is from.
     * @hide
     */
    public static final String EXTRA_HANDOVER_FROM_PHONE_ACCOUNT =
            "android.telecom.extra.HANDOVER_FROM_PHONE_ACCOUNT";

    /**
     * Extra key specified in the {@link ConnectionRequest#getExtras()} when Telecom calls
     * {@link ConnectionService#onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}
     * to inform the {@link ConnectionService} what the initial {@link CallAudioState} of the
     * {@link Connection} will be.
     * @hide
     */
    public static final String EXTRA_CALL_AUDIO_STATE = "android.telecom.extra.CALL_AUDIO_STATE";

    /**
     * A boolean extra, which when set on the {@link Intent#ACTION_CALL} intent or on the bundle
     * passed into {@link #placeCall(Uri, Bundle)}, indicates that the call should be initiated with
     * an RTT session open. See {@link android.telecom.Call.RttCall} for more information on RTT.
     */
    public static final String EXTRA_START_CALL_WITH_RTT =
            "android.telecom.extra.START_CALL_WITH_RTT";

    /**
     * Start an activity indicating that the completion of an outgoing call or an incoming call
     * which was not blocked by the {@link CallScreeningService}, and which was NOT terminated
     * while the call was in {@link Call#STATE_AUDIO_PROCESSING}.
     *
     * The {@link Uri} extra {@link #EXTRA_HANDLE} will contain the uri handle(phone number) for the
     * call which completed.
     *
     * The integer extra {@link #EXTRA_DISCONNECT_CAUSE} will indicate the reason for the call
     * disconnection. See {@link #EXTRA_DISCONNECT_CAUSE} for more information.
     *
     * The integer extra {@link #EXTRA_CALL_DURATION} will indicate the duration of the call. See
     * {@link #EXTRA_CALL_DURATION} for more information.
     */
    public static final String ACTION_POST_CALL = "android.telecom.action.POST_CALL";

    /**
     * A {@link Uri} extra, which when set on the {@link #ACTION_POST_CALL} intent, indicates the
     * uri handle(phone number) of the completed call.
     */
    public static final String EXTRA_HANDLE = "android.telecom.extra.HANDLE";

    /**
     * A integer value provided for completed calls to indicate the reason for the call
     * disconnection.
     * <p>
     * Allowed values:
     * <ul>
     * <li>{@link DisconnectCause#UNKNOWN}</li>
     * <li>{@link DisconnectCause#LOCAL}</li>
     * <li>{@link DisconnectCause#REMOTE}</li>
     * <li>{@link DisconnectCause#REJECTED}</li>
     * <li>{@link DisconnectCause#MISSED}</li>
     * </ul>
     * </p>
     */
    public static final String EXTRA_DISCONNECT_CAUSE = "android.telecom.extra.DISCONNECT_CAUSE";

    /**
     * A integer value provided for completed calls to indicate the duration of the call.
     * <p>
     * Allowed values:
     * <ul>
     * <li>{@link #DURATION_VERY_SHORT}</li>
     * <li>{@link #DURATION_SHORT}</li>
     * <li>{@link #DURATION_MEDIUM}</li>
     * <li>{@link #DURATION_LONG}</li>
     * </ul>
     * </p>
     */
    public static final String EXTRA_CALL_DURATION = "android.telecom.extra.CALL_DURATION";

    /**
     * A integer value for {@link #EXTRA_CALL_DURATION}, indicates the duration of the completed
     * call was < 3 seconds.
     */
    public static final int DURATION_VERY_SHORT = 0;

    /**
     * A integer value for {@link #EXTRA_CALL_DURATION}, indicates the duration of the completed
     * call was >= 3 seconds and < 60 seconds.
     */
    public static final int DURATION_SHORT = 1;

    /**
     * A integer value for {@link #EXTRA_CALL_DURATION}, indicates the duration of the completed
     * call was >= 60 seconds and < 120 seconds.
     */
    public static final int DURATION_MEDIUM = 2;

    /**
     * A integer value for {@link #EXTRA_CALL_DURATION}, indicates the duration of the completed
     * call was >= 120 seconds.
     */
    public static final int DURATION_LONG = 3;

    /**
     * The threshold between {@link #DURATION_VERY_SHORT} calls and {@link #DURATION_SHORT} calls in
     * milliseconds.
     * @hide
     */
    public static final long VERY_SHORT_CALL_TIME_MS = 3000;

    /**
     * The threshold between {@link #DURATION_SHORT} calls and {@link #DURATION_MEDIUM} calls in
     * milliseconds.
     * @hide
     */
    public static final long SHORT_CALL_TIME_MS = 60000;

    /**
     * The threshold between {@link #DURATION_MEDIUM} calls and {@link #DURATION_LONG} calls in
     * milliseconds.
     * @hide
     */
    public static final long MEDIUM_CALL_TIME_MS = 120000;

    /**
     * A boolean meta-data value indicating whether an {@link InCallService} implements an
     * in-call user interface. Dialer implementations (see {@link #getDefaultDialerPackage()}) which
     * would also like to replace the in-call interface should set this meta-data to {@code true} in
     * the manifest registration of their {@link InCallService}.
     */
    public static final String METADATA_IN_CALL_SERVICE_UI = "android.telecom.IN_CALL_SERVICE_UI";

    /**
     * A boolean meta-data value indicating whether an {@link InCallService} implements an
     * in-call user interface to be used while the device is in car-mode (see
     * {@link android.content.res.Configuration#UI_MODE_TYPE_CAR}).
     */
    public static final String METADATA_IN_CALL_SERVICE_CAR_MODE_UI =
            "android.telecom.IN_CALL_SERVICE_CAR_MODE_UI";

    /**
     * A boolean meta-data value indicating whether an {@link InCallService} implements ringing.
     * Dialer implementations (see {@link #getDefaultDialerPackage()}) which would also like to
     * override the system provided ringing should set this meta-data to {@code true} in the
     * manifest registration of their {@link InCallService}.
     * <p>
     * When {@code true}, it is the {@link InCallService}'s responsibility to play a ringtone for
     * all incoming calls.
     */
    public static final String METADATA_IN_CALL_SERVICE_RINGING =
            "android.telecom.IN_CALL_SERVICE_RINGING";

    /**
     * A boolean meta-data value indicating whether an {@link InCallService} wants to be informed of
     * calls which have the {@link Call.Details#PROPERTY_IS_EXTERNAL_CALL} property.  An external
     * call is one which a {@link ConnectionService} knows about, but is not connected to directly.
     * Dialer implementations (see {@link #getDefaultDialerPackage()}) which would like to be
     * informed of external calls should set this meta-data to {@code true} in the manifest
     * registration of their {@link InCallService}.  By default, the {@link InCallService} will NOT
     * be informed of external calls.
     */
    public static final String METADATA_INCLUDE_EXTERNAL_CALLS =
            "android.telecom.INCLUDE_EXTERNAL_CALLS";

    /**
     * A boolean meta-data value indicating whether an {@link InCallService} wants to be informed of
     * calls which have the {@link Call.Details#PROPERTY_SELF_MANAGED} property.  A self-managed
     * call is one which originates from a self-managed {@link ConnectionService} which has chosen
     * to implement its own call user interface.  An {@link InCallService} implementation which
     * would like to be informed of external calls should set this meta-data to {@code true} in the
     * manifest registration of their {@link InCallService}.  By default, the {@link InCallService}
     * will NOT be informed about self-managed calls.
     * <p>
     * An {@link InCallService} which receives self-managed calls is free to view and control the
     * state of calls in the self-managed {@link ConnectionService}.  An example use-case is
     * exposing these calls to an automotive device via its companion app.
     * <p>
     * This meta-data can only be set for an {@link InCallService} which also sets
     * {@link #METADATA_IN_CALL_SERVICE_UI}. Only the default phone/dialer app, or a car-mode
     * {@link InCallService} can see self-managed calls.
     * <p>
     * See also {@link Connection#PROPERTY_SELF_MANAGED}.
     */
    public static final String METADATA_INCLUDE_SELF_MANAGED_CALLS =
            "android.telecom.INCLUDE_SELF_MANAGED_CALLS";

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
     * @hide
     */
    @IntDef(prefix = { "TTY_MODE_" },
            value = {TTY_MODE_OFF, TTY_MODE_FULL, TTY_MODE_HCO, TTY_MODE_VCO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TtyMode {}

    /**
     * TTY (teletypewriter) mode is off.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int TTY_MODE_OFF = 0;

    /**
     * TTY (teletypewriter) mode is on. The speaker is off and the microphone is muted. The user
     * will communicate with the remote party by sending and receiving text messages.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int TTY_MODE_FULL = 1;

    /**
     * TTY (teletypewriter) mode is in hearing carryover mode (HCO). The microphone is muted but the
     * speaker is on. The user will communicate with the remote party by sending text messages and
     * hearing an audible reply.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int TTY_MODE_HCO = 2;

    /**
     * TTY (teletypewriter) mode is in voice carryover mode (VCO). The speaker is off but the
     * microphone is still on. User will communicate with the remote party by speaking and receiving
     * text message replies.
     *
     * @hide
     */
    @TestApi
    @SystemApi
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
     * <ul>
     *     <li>{@link #TTY_MODE_OFF}</li>
     *     <li>{@link #TTY_MODE_FULL}</li>
     *     <li>{@link #TTY_MODE_HCO}</li>
     *     <li>{@link #TTY_MODE_VCO}</li>
     * </ul>
     * @hide
     */
    public static final String EXTRA_CURRENT_TTY_MODE =
            "android.telecom.extra.CURRENT_TTY_MODE";

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
            "android.telecom.extra.TTY_PREFERRED_MODE";

    /**
     * Broadcast intent action for letting custom component know to show the missed call
     * notification. If no custom component exists then this is sent to the default dialer which
     * should post a missed-call notification.
     */
    public static final String ACTION_SHOW_MISSED_CALLS_NOTIFICATION =
            "android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION";

    /**
     * The number of calls associated with the notification. If the number is zero then the missed
     * call notification should be dismissed.
     */
    public static final String EXTRA_NOTIFICATION_COUNT =
            "android.telecom.extra.NOTIFICATION_COUNT";

    /**
     * The number associated with the missed calls. This number is only relevant
     * when EXTRA_NOTIFICATION_COUNT is 1.
     */
    public static final String EXTRA_NOTIFICATION_PHONE_NUMBER =
            "android.telecom.extra.NOTIFICATION_PHONE_NUMBER";

    /**
     * The intent to clear missed calls.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CLEAR_MISSED_CALLS_INTENT =
            "android.telecom.extra.CLEAR_MISSED_CALLS_INTENT";

    /**
     * The intent to call back a missed call.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CALL_BACK_INTENT =
            "android.telecom.extra.CALL_BACK_INTENT";

    /**
     * The dialer activity responsible for placing emergency calls from, for example, a locked
     * keyguard.
     * @hide
     */
    public static final ComponentName EMERGENCY_DIALER_COMPONENT =
            ComponentName.createRelative("com.android.phone", ".EmergencyDialer");

    /**
     * The boolean indicated by this extra controls whether or not a call is eligible to undergo
     * assisted dialing. This extra is stored under {@link #EXTRA_OUTGOING_CALL_EXTRAS}.
     * <p>
     * The call initiator can use this extra to indicate that a call used assisted dialing to help
     * place the call.  This is most commonly used by a Dialer app which provides the ability to
     * automatically add dialing prefixes when placing international calls.
     * <p>
     * Setting this extra on the outgoing call extras will cause the
     * {@link Connection#PROPERTY_ASSISTED_DIALING} property and
     * {@link Call.Details#PROPERTY_ASSISTED_DIALING} property to be set on the
     * {@link Connection}/{@link Call} in question.  When the call is logged to the call log, the
     * {@link android.provider.CallLog.Calls#FEATURES_ASSISTED_DIALING_USED} call feature is set to
     * indicate that assisted dialing was used for the call.
     */
    public static final String EXTRA_USE_ASSISTED_DIALING =
            "android.telecom.extra.USE_ASSISTED_DIALING";

    /**
     * Optional extra for {@link #placeCall(Uri, Bundle)} containing an integer that specifies
     * the source where user initiated this call. This data is used in metrics.
     * Valid sources are:
     * {@link TelecomManager#CALL_SOURCE_UNSPECIFIED},
     * {@link TelecomManager#CALL_SOURCE_EMERGENCY_DIALPAD},
     * {@link TelecomManager#CALL_SOURCE_EMERGENCY_SHORTCUT}.
     *
     * @hide
     */
    public static final String EXTRA_CALL_SOURCE = "android.telecom.extra.CALL_SOURCE";

    /**
     * Indicating the call is initiated via emergency dialer's shortcut button.
     *
     * @hide
     */
    public static final int CALL_SOURCE_EMERGENCY_SHORTCUT = 2;

    /**
     * Indicating the call is initiated via emergency dialer's dialpad.
     *
     * @hide
     */
    public static final int CALL_SOURCE_EMERGENCY_DIALPAD = 1;

    /**
     * Indicating the call source is not specified.
     *
     * @hide
     */
    public static final int CALL_SOURCE_UNSPECIFIED = 0;

    /**
     * The following 4 constants define how properties such as phone numbers and names are
     * displayed to the user.
     */

    /**
     * Indicates that the address or number of a call is allowed to be displayed for caller ID.
     */
    public static final int PRESENTATION_ALLOWED = 1;

    /**
     * Indicates that the address or number of a call is blocked by the other party.
     */
    public static final int PRESENTATION_RESTRICTED = 2;

    /**
     * Indicates that the address or number of a call is not specified or known by the carrier.
     */
    public static final int PRESENTATION_UNKNOWN = 3;

    /**
     * Indicates that the address or number of a call belongs to a pay phone.
     */
    public static final int PRESENTATION_PAYPHONE = 4;


    /*
     * Values for the adb property "persist.radio.videocall.audio.output"
     */
    /** @hide */
    public static final int AUDIO_OUTPUT_ENABLE_SPEAKER = 0;
    /** @hide */
    public static final int AUDIO_OUTPUT_DISABLE_SPEAKER = 1;
    /** @hide */
    public static final int AUDIO_OUTPUT_DEFAULT = AUDIO_OUTPUT_ENABLE_SPEAKER;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = { "PRESENTATION_" },
            value = {PRESENTATION_ALLOWED, PRESENTATION_RESTRICTED, PRESENTATION_UNKNOWN,
            PRESENTATION_PAYPHONE})
    public @interface Presentation {}

    private static final String TAG = "TelecomManager";

    private final Context mContext;

    private final ITelecomService mTelecomServiceOverride;

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static TelecomManager from(Context context) {
        return (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    }

    /**
     * @hide
     */
    public TelecomManager(Context context) {
        this(context, null);
    }

    /**
     * @hide
     */
    public TelecomManager(Context context, ITelecomService telecomServiceImpl) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
        mTelecomServiceOverride = telecomServiceImpl;
    }

    /**
     * Return the {@link PhoneAccount} which will be used to place outgoing calls to addresses with
     * the specified {@code uriScheme}. This {@link PhoneAccount} will always be a member of the
     * list which is returned from invoking {@link #getCallCapablePhoneAccounts()}. The specific
     * account returned depends on the following priorities:
     * <ul>
     * <li> If the user-selected default {@link PhoneAccount} supports the specified scheme, it will
     * be returned.
     * </li>
     * <li> If there exists only one {@link PhoneAccount} that supports the specified scheme, it
     * will be returned.
     * </li>
     * </ul>
     * <p>
     * If no {@link PhoneAccount} fits the criteria above, this method will return {@code null}.
     *
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     *
     * @param uriScheme The URI scheme.
     * @return The {@link PhoneAccountHandle} corresponding to the account to be used.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public PhoneAccountHandle getDefaultOutgoingPhoneAccount(String uriScheme) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getDefaultOutgoingPhoneAccount(uriScheme,
                        mContext.getOpPackageName());
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
     * <p>
     * Apps must be prepared for this method to return {@code null}, indicating that there currently
     * exists no user-chosen default {@code PhoneAccount}.
     * <p>
     * The default dialer has access to use this method.
     *
     * @return The user outgoing phone account selected by the user, or {@code null} if there is no
     * user selected outgoing {@link PhoneAccountHandle}.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public @Nullable PhoneAccountHandle getUserSelectedOutgoingPhoneAccount() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getUserSelectedOutgoingPhoneAccount(
                        mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getUserSelectedOutgoingPhoneAccount", e);
        }
        return null;
    }

    /**
     * Sets the user-chosen default {@link PhoneAccountHandle} for making outgoing phone calls.
     *
     * @param accountHandle The {@link PhoneAccountHandle} which will be used by default for making
     *                      outgoing voice calls, or {@code null} if no default is specified (the
     *                      user will be asked each time a call is placed in this case).
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @TestApi
    @SystemApi
    public void setUserSelectedOutgoingPhoneAccount(@Nullable PhoneAccountHandle accountHandle) {
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
     * {@code null}, indicating that there currently exists no SIM call manager {@link PhoneAccount}
     * for the default voice subscription.
     *
     * @return The phone account handle of the current sim call manager for the default voice
     * subscription.
     * @see SubscriptionManager#getDefaultVoiceSubscriptionId()
     */
    public PhoneAccountHandle getSimCallManager() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getSimCallManager(
                        SubscriptionManager.getDefaultSubscriptionId());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getSimCallManager");
        }
        return null;
    }

    /**
     * Returns current SIM call manager for the Telephony Subscription ID specified. Apps must be
     * prepared for this method to return {@code null}, indicating that there currently exists no
     * SIM call manager {@link PhoneAccount} for the subscription specified.
     *
     * @param subscriptionId The Telephony Subscription ID that the SIM call manager should be
     *                       queried for.
     * @return The phone account handle of the current sim call manager.
     * @see SubscriptionManager#getActiveSubscriptionInfoList()
     */
    public @Nullable PhoneAccountHandle getSimCallManagerForSubscription(int subscriptionId) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getSimCallManager(subscriptionId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getSimCallManager");
        }
        return null;
    }

    /**
     * Returns the current SIM call manager for the user-chosen default Telephony Subscription ID
     * (see {@link SubscriptionManager#getDefaultSubscriptionId()}) and the specified user. Apps
     * must be prepared for this method to return {@code null}, indicating that there currently
     * exists no SIM call manager {@link PhoneAccount} for the default voice subscription.
     *
     * @return The phone account handle of the current sim call manager.
     *
     * @hide
     * @deprecated Use {@link #getSimCallManager()}.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 119305590)
    public PhoneAccountHandle getSimCallManager(int userId) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getSimCallManagerForUser(userId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getSimCallManagerForUser");
        }
        return null;
    }

    /**
     * Returns the current connection manager. Apps must be prepared for this method to return
     * {@code null}, indicating that there currently exists no Connection Manager
     * {@link PhoneAccount} for the default voice subscription.
     *
     * @return The phone account handle of the current connection manager.
     * @hide
     */
    @SystemApi
    public PhoneAccountHandle getConnectionManager() {
        return getSimCallManager();
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
    @RequiresPermission(anyOf = {
            READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public List<PhoneAccountHandle> getPhoneAccountsSupportingScheme(String uriScheme) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getPhoneAccountsSupportingScheme(uriScheme,
                        mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getPhoneAccountsSupportingScheme", e);
        }
        return new ArrayList<>();
    }


    /**
     * Returns a list of {@link PhoneAccountHandle}s which can be used to make and receive phone
     * calls. The returned list includes only those accounts which have been explicitly enabled
     * by the user.
     *
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     *
     * @see #EXTRA_PHONE_ACCOUNT_HANDLE
     * @return A list of {@code PhoneAccountHandle} objects.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public List<PhoneAccountHandle> getCallCapablePhoneAccounts() {
        return getCallCapablePhoneAccounts(false);
    }

    /**
     * Returns a list of {@link PhoneAccountHandle}s for self-managed {@link ConnectionService}s.
     * <p>
     * Self-Managed {@link ConnectionService}s have a {@link PhoneAccount} with
     * {@link PhoneAccount#CAPABILITY_SELF_MANAGED}.
     * <p>
     * Requires permission {@link android.Manifest.permission#READ_PHONE_STATE}, or that the caller
     * is the default dialer app.
     * <p>
     * A {@link SecurityException} will be thrown if a called is not the default dialer, or lacks
     * the {@link android.Manifest.permission#READ_PHONE_STATE} permission.
     *
     * @return A list of {@code PhoneAccountHandle} objects.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public List<PhoneAccountHandle> getSelfManagedPhoneAccounts() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getSelfManagedPhoneAccounts(mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getSelfManagedPhoneAccounts()", e);
        }
        return new ArrayList<>();
    }

    /**
     * Returns a list of {@link PhoneAccountHandle}s including those which have not been enabled
     * by the user.
     *
     * @param includeDisabledAccounts When {@code true}, disabled phone accounts will be included,
     *                                when {@code false}, only enabled phone accounts will be
     *                                included.
     * @return A list of {@code PhoneAccountHandle} objects.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(READ_PRIVILEGED_PHONE_STATE)
    public @NonNull List<PhoneAccountHandle> getCallCapablePhoneAccounts(
            boolean includeDisabledAccounts) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getCallCapablePhoneAccounts(
                        includeDisabledAccounts, mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getCallCapablePhoneAccounts(" +
                    includeDisabledAccounts + ")", e);
        }
        return new ArrayList<>();
    }

    /**
     *  Returns a list of all {@link PhoneAccount}s registered for the calling package.
     *
     * @return A list of {@code PhoneAccountHandle} objects.
     * @hide
     */
    @SystemApi
    @SuppressLint("Doclava125")
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
     */
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
     * Register a {@link PhoneAccount} for use by the system that will be stored in Device Encrypted
     * storage. When registering {@link PhoneAccount}s, existing registrations will be overwritten
     * if the {@link PhoneAccountHandle} matches that of a {@link PhoneAccount} which is already
     * registered. Once registered, the {@link PhoneAccount} is listed to the user as an option
     * when placing calls. The user may still need to enable the {@link PhoneAccount} within
     * the phone app settings before the account is usable.
     * <p>
     * A {@link SecurityException} will be thrown if an app tries to register a
     * {@link PhoneAccountHandle} where the package name specified within
     * {@link PhoneAccountHandle#getComponentName()} does not match the package name of the app.
     *
     * @param account The complete {@link PhoneAccount}.
     */
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
     */
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
    @SuppressLint("Doclava125")
    public void clearPhoneAccounts() {
        clearAccounts();
    }
    /**
     * Remove all Accounts that belong to the calling package from the system.
     * @deprecated Use {@link #clearPhoneAccounts()} instead.
     * @hide
     */
    @SystemApi
    @SuppressLint("Doclava125")
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
     * @deprecated - Use {@link TelecomManager#getDefaultDialerPackage} to directly access
     *         the default dialer's package name instead.
     * @hide
     */
    @SystemApi
    @SuppressLint("Doclava125")
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
     * Used to determine the currently selected default dialer package.
     *
     * @return package name for the default dialer package or null if no package has been
     *         selected as the default dialer.
     */
    public String getDefaultDialerPackage() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getDefaultDialerPackage();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the default dialer package name.", e);
        }
        return null;
    }

    /**
     * Used to determine the currently selected default dialer package for a specific user.
     *
     * @param userHandle the user id to query the default dialer package for.
     * @return package name for the default dialer package or null if no package has been
     *         selected as the default dialer.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(READ_PRIVILEGED_PHONE_STATE)
    public @Nullable String getDefaultDialerPackage(@NonNull UserHandle userHandle) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getDefaultDialerPackageForUser(
                        userHandle.getIdentifier());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the default dialer package name.", e);
        }
        return null;
    }

    /**
     * Used to set the default dialer package.
     *
     * @param packageName to set the default dialer to, or {@code null} if the system provided
     *                    dialer should be used instead.
     *
     * @result {@code true} if the default dialer was successfully changed, {@code false} if
     *         the specified package does not correspond to an installed dialer, or is already
     *         the default dialer.
     *
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * Requires permission: {@link android.Manifest.permission#WRITE_SECURE_SETTINGS}
     *
     * @hide
     * @deprecated Use
     * {@link android.app.role.RoleManager#addRoleHolderAsUser(String, String, int, UserHandle,
     * Executor, java.util.function.Consumer)} instead.
     * @removed
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(allOf = {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            android.Manifest.permission.WRITE_SECURE_SETTINGS})
    public boolean setDefaultDialer(@Nullable String packageName) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().setDefaultDialer(packageName);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to set the default dialer.", e);
        }
        return false;
    }

    /**
     * Determines the package name of the system-provided default phone app.
     *
     * @return package name for the system dialer package or {@code null} if no system dialer is
     *         preloaded.
     */
    public @Nullable String getSystemDialerPackage() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getSystemDialerPackage();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the system dialer package name.", e);
        }
        return null;
    }

    /**
     * Return whether a given phone number is the configured voicemail number for a
     * particular phone account.
     *
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     *
     * @param accountHandle The handle for the account to check the voicemail number against
     * @param number The number to look up.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isVoiceMailNumber(PhoneAccountHandle accountHandle, String number) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isVoiceMailNumber(accountHandle, number,
                        mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling ITelecomService#isVoiceMailNumber.", e);
        }
        return false;
    }

    /**
     * Return the voicemail number for a given phone account.
     *
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     *
     * @param accountHandle The handle for the phone account.
     * @return The voicemail number for the phone account, and {@code null} if one has not been
     *         configured.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVoiceMailNumber(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getVoiceMailNumber(accountHandle,
                        mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling ITelecomService#hasVoiceMailNumber.", e);
        }
        return null;
    }

    /**
     * Return the line 1 phone number for given phone account.
     *
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     *
     * @param accountHandle The handle for the account retrieve a number for.
     * @return A string representation of the line 1 phone number.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getLine1Number(PhoneAccountHandle accountHandle) {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getLine1Number(accountHandle,
                        mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling ITelecomService#getLine1Number.", e);
        }
        return null;
    }

    /**
     * Returns whether there is an ongoing phone call (can be in dialing, ringing, active or holding
     * states) originating from either a manager or self-managed {@link ConnectionService}.
     * <p>
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     *
     * @return {@code true} if there is an ongoing call in either a managed or self-managed
     *      {@link ConnectionService}, {@code false} otherwise.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isInCall() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isInCall(mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling isInCall().", e);
        }
        return false;
    }

    /**
     * Returns whether there is an ongoing call originating from a managed
     * {@link ConnectionService}.  An ongoing call can be in dialing, ringing, active or holding
     * states.
     * <p>
     * If you also need to know if there are ongoing self-managed calls, use {@link #isInCall()}
     * instead.
     * <p>
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     *
     * @return {@code true} if there is an ongoing call in a managed {@link ConnectionService},
     *      {@code false} otherwise.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isInManagedCall() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isInManagedCall(mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling isInManagedCall().", e);
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
     *
     * Takes into consideration both managed and self-managed calls.
     *
     * @hide
     */
    @SystemApi
    public @CallState int getCallState() {
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
     * @return {@code true} if there is a managed or self-managed ringing call.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isRinging() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isRinging(mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get ringing state of phone app.", e);
        }
        return false;
    }

    /**
     * Ends the foreground call on the device.
     * <p>
     * If there is a ringing call, calling this method rejects the ringing call.  Otherwise the
     * foreground call is ended.
     * <p>
     * Requires permission {@link android.Manifest.permission#ANSWER_PHONE_CALLS}.
     * <p>
     * Note: this method CANNOT be used to end ongoing emergency calls and will return {@code false}
     * if an attempt is made to end an emergency call.
     *
     * @return {@code true} if there is a call which will be rejected or terminated, {@code false}
     * otherwise.
     * @deprecated Companion apps for wearable devices should use the {@link InCallService} API
     * instead.  Apps performing call screening should use the {@link CallScreeningService} API
     * instead.
     */
    @RequiresPermission(Manifest.permission.ANSWER_PHONE_CALLS)
    @Deprecated
    public boolean endCall() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().endCall(mContext.getPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#endCall", e);
        }
        return false;
    }

    /**
     * If there is a ringing incoming call, this method accepts the call on behalf of the user.
     *
     * If the incoming call is a video call, the call will be answered with the same video state as
     * the incoming call requests.  This means, for example, that an incoming call requesting
     * {@link VideoProfile#STATE_BIDIRECTIONAL} will be answered, accepting that state.
     *
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE} or
     * {@link android.Manifest.permission#ANSWER_PHONE_CALLS}
     *
     * @deprecated Companion apps for wearable devices should use the {@link InCallService} API
     * instead.
     */
    //TODO: L-release - need to convert all invocation of ITelecmmService#answerRingingCall to use
    // this method (clockwork & gearhead).
    @RequiresPermission(anyOf =
            {Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.MODIFY_PHONE_STATE})
    @Deprecated
    public void acceptRingingCall() {
        try {
            if (isServiceConnected()) {
                getTelecomService().acceptRingingCall(mContext.getPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#acceptRingingCall", e);
        }
    }

    /**
     * If there is a ringing incoming call, this method accepts the call on behalf of the user,
     * with the specified video state.
     *
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE} or
     * {@link android.Manifest.permission#ANSWER_PHONE_CALLS}
     *
     * @param videoState The desired video state to answer the call with.
     * @deprecated Companion apps for wearable devices should use the {@link InCallService} API
     * instead.
     */
    @RequiresPermission(anyOf =
            {Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.MODIFY_PHONE_STATE})
    @Deprecated
    public void acceptRingingCall(int videoState) {
        try {
            if (isServiceConnected()) {
                getTelecomService().acceptRingingCallWithVideoState(
                        mContext.getPackageName(), videoState);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#acceptRingingCallWithVideoState", e);
        }
    }

    /**
     * Silences the ringer if a ringing call exists.
     * <p>
     * This method can only be relied upon to stop the ringtone for a call if the ringtone has
     * already started playing.  It is intended to handle use-cases such as silencing a ringing call
     * when the user presses the volume button during ringing.
     * <p>
     * If this method is called prior to when the ringtone begins playing, the ringtone will not be
     * silenced.  As such it is not intended as a means to avoid playing of a ringtone.
     * <p>
     * A dialer app which wants to have more control over ringtone playing should declare
     * {@link TelecomManager#METADATA_IN_CALL_SERVICE_RINGING} in the manifest entry for their
     * {@link InCallService} implementation to indicate that the app wants to be responsible for
     * playing the ringtone for all incoming calls.
     * <p>
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE} or that the
     * app fills the dialer role (see {@link #getDefaultDialerPackage()}).
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void silenceRinger() {
        try {
            if (isServiceConnected()) {
                getTelecomService().silenceRinger(mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#silenceRinger", e);
        }
    }

    /**
     * Returns whether TTY is supported on this device.
     */
    @RequiresPermission(anyOf = {
            READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isTtySupported() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isTtySupported(mContext.getOpPackageName());
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
    @SystemApi
    @TestApi
    @RequiresPermission(READ_PRIVILEGED_PHONE_STATE)
    public @TtyMode int getCurrentTtyMode() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().getCurrentTtyMode(mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the current TTY mode.", e);
        }
        return TTY_MODE_OFF;
    }

    /**
     * Registers a new incoming call. A {@link ConnectionService} should invoke this method when it
     * has an incoming call. For managed {@link ConnectionService}s, the specified
     * {@link PhoneAccountHandle} must have been registered with {@link #registerPhoneAccount} and
     * the user must have enabled the corresponding {@link PhoneAccount}.  This can be checked using
     * {@link #getPhoneAccount}. Self-managed {@link ConnectionService}s must have
     * {@link android.Manifest.permission#MANAGE_OWN_CALLS} to add a new incoming call.
     * <p>
     * The incoming call you are adding is assumed to have a video state of
     * {@link VideoProfile#STATE_AUDIO_ONLY}, unless the extra value
     * {@link #EXTRA_INCOMING_VIDEO_STATE} is specified.
     * <p>
     * Once invoked, this method will cause the system to bind to the {@link ConnectionService}
     * associated with the {@link PhoneAccountHandle} and request additional information about the
     * call (See {@link ConnectionService#onCreateIncomingConnection}) before starting the incoming
     * call UI.
     * <p>
     * For a managed {@link ConnectionService}, a {@link SecurityException} will be thrown if either
     * the {@link PhoneAccountHandle} does not correspond to a registered {@link PhoneAccount} or
     * the associated {@link PhoneAccount} is not currently enabled by the user.
     * <p>
     * For a self-managed {@link ConnectionService}, a {@link SecurityException} will be thrown if
     * the {@link PhoneAccount} has {@link PhoneAccount#CAPABILITY_SELF_MANAGED} and the calling app
     * does not have {@link android.Manifest.permission#MANAGE_OWN_CALLS}.
     *
     * @param phoneAccount A {@link PhoneAccountHandle} registered with
     *            {@link #registerPhoneAccount}.
     * @param extras A bundle that will be passed through to
     *            {@link ConnectionService#onCreateIncomingConnection}.
     */
    public void addNewIncomingCall(PhoneAccountHandle phoneAccount, Bundle extras) {
        try {
            if (isServiceConnected()) {
                if (extras != null && extras.getBoolean(EXTRA_IS_HANDOVER) &&
                        mContext.getApplicationContext().getApplicationInfo().targetSdkVersion >
                                Build.VERSION_CODES.O_MR1) {
                    Log.e("TAG", "addNewIncomingCall failed. Use public api " +
                            "acceptHandover for API > O-MR1");
                    return;
                }
                getTelecomService().addNewIncomingCall(
                        phoneAccount, extras == null ? new Bundle() : extras);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException adding a new incoming call: " + phoneAccount, e);
        }
    }

    /**
     * Registers a new incoming conference. A {@link ConnectionService} should invoke this method
     * when it has an incoming conference. For managed {@link ConnectionService}s, the specified
     * {@link PhoneAccountHandle} must have been registered with {@link #registerPhoneAccount} and
     * the user must have enabled the corresponding {@link PhoneAccount}.  This can be checked using
     * {@link #getPhoneAccount}. Self-managed {@link ConnectionService}s must have
     * {@link android.Manifest.permission#MANAGE_OWN_CALLS} to add a new incoming call.
     * <p>
     * The incoming conference you are adding is assumed to have a video state of
     * {@link VideoProfile#STATE_AUDIO_ONLY}, unless the extra value
     * {@link #EXTRA_INCOMING_VIDEO_STATE} is specified.
     * <p>
     * Once invoked, this method will cause the system to bind to the {@link ConnectionService}
     * associated with the {@link PhoneAccountHandle} and request additional information about the
     * call (See {@link ConnectionService#onCreateIncomingConference}) before starting the incoming
     * call UI.
     * <p>
     * For a managed {@link ConnectionService}, a {@link SecurityException} will be thrown if either
     * the {@link PhoneAccountHandle} does not correspond to a registered {@link PhoneAccount} or
     * the associated {@link PhoneAccount} is not currently enabled by the user.
     *
     * @param phoneAccount A {@link PhoneAccountHandle} registered with
     *            {@link #registerPhoneAccount}.
     * @param extras A bundle that will be passed through to
     *            {@link ConnectionService#onCreateIncomingConference}.
     * @hide
     */
    public void addNewIncomingConference(@NonNull PhoneAccountHandle phoneAccount,
            @NonNull Bundle extras) {
        try {
            if (isServiceConnected()) {
                getTelecomService().addNewIncomingConference(
                        phoneAccount, extras == null ? new Bundle() : extras);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException adding a new incoming conference: " + phoneAccount, e);
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
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     *
     * @param dialString The digits to dial.
     * @return True if the digits were processed as an MMI code, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean handleMmi(String dialString) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                return service.handlePinMmi(dialString, mContext.getOpPackageName());
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
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     *
     * @param accountHandle The handle for the account the MMI code should apply to.
     * @param dialString The digits to dial.
     * @return True if the digits were processed as an MMI code, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean handleMmi(String dialString, PhoneAccountHandle accountHandle) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                return service.handlePinMmiForPhoneAccount(accountHandle, dialString,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#handlePinMmi", e);
            }
        }
        return false;
    }

    /**
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     *
     * @param accountHandle The handle for the account to derive an adn query URI for or
     * {@code null} to return a URI which will use the default account.
     * @return The URI (with the content:// scheme) specific to the specified {@link PhoneAccount}
     * for the the content retrieve.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public Uri getAdnUriForPhoneAccount(PhoneAccountHandle accountHandle) {
        ITelecomService service = getTelecomService();
        if (service != null && accountHandle != null) {
            try {
                return service.getAdnUriForPhoneAccount(accountHandle, mContext.getOpPackageName());
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
     *
     * Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void cancelMissedCallsNotification() {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                service.cancelMissedCallsNotification(mContext.getOpPackageName());
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
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public void showInCallScreen(boolean showDialpad) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                service.showInCallScreen(showDialpad, mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#showCallScreen", e);
            }
        }
    }

    /**
     * Places a new outgoing call to the provided address using the system telecom service with
     * the specified extras.
     *
     * This method is equivalent to placing an outgoing call using {@link Intent#ACTION_CALL},
     * except that the outgoing call will always be sent via the system telecom service. If
     * method-caller is either the user selected default dialer app or preloaded system dialer
     * app, then emergency calls will also be allowed.
     *
     * Placing a call via a managed {@link ConnectionService} requires permission:
     * {@link android.Manifest.permission#CALL_PHONE}
     *
     * Usage example:
     * <pre>
     * Uri uri = Uri.fromParts("tel", "12345", null);
     * Bundle extras = new Bundle();
     * extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true);
     * telecomManager.placeCall(uri, extras);
     * </pre>
     *
     * The following keys are supported in the supplied extras.
     * <ul>
     *   <li>{@link #EXTRA_OUTGOING_CALL_EXTRAS}</li>
     *   <li>{@link #EXTRA_PHONE_ACCOUNT_HANDLE}</li>
     *   <li>{@link #EXTRA_START_CALL_WITH_SPEAKERPHONE}</li>
     *   <li>{@link #EXTRA_START_CALL_WITH_VIDEO_STATE}</li>
     * </ul>
     * <p>
     * An app which implements the self-managed {@link ConnectionService} API uses
     * {@link #placeCall(Uri, Bundle)} to inform Telecom of a new outgoing call.  A self-managed
     * {@link ConnectionService} must include {@link #EXTRA_PHONE_ACCOUNT_HANDLE} to specify its
     * associated {@link android.telecom.PhoneAccountHandle}.
     *
     * Self-managed {@link ConnectionService}s require permission
     * {@link android.Manifest.permission#MANAGE_OWN_CALLS}.
     *
     * <p class="note"><strong>Note:</strong> If this method is used to place an emergency call, it
     * is not guaranteed that the call will be placed on the {@link PhoneAccount} provided in
     * the {@link #EXTRA_PHONE_ACCOUNT_HANDLE} extra (if specified) and may be placed on another
     * {@link PhoneAccount} with the {@link PhoneAccount#CAPABILITY_PLACE_EMERGENCY_CALLS}
     * capability, depending on external factors, such as network conditions and Modem/SIM status.
     * </p>
     *
     * @param address The address to make the call to.
     * @param extras Bundle of extras to use with the call.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.MANAGE_OWN_CALLS})
    public void placeCall(Uri address, Bundle extras) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            if (address == null) {
                Log.w(TAG, "Cannot place call to empty address.");
            }
            try {
                service.placeCall(address, extras == null ? new Bundle() : extras,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#placeCall", e);
            }
        }
    }


    /**
     * Place a new conference call with the provided participants using the system telecom service
     * This method doesn't support placing of emergency calls.
     *
     * An adhoc conference call is established by providing a list of addresses to
     * {@code TelecomManager#startConference(List<Uri>, int videoState)} where the
     * {@link ConnectionService} is responsible for connecting all indicated participants
     * to a conference simultaneously.
     * This is in contrast to conferences formed by merging calls together (e.g. using
     * {@link android.telecom.Call#mergeConference()}).
     *
     * The following keys are supported in the supplied extras.
     * <ul>
     *   <li>{@link #EXTRA_PHONE_ACCOUNT_HANDLE}</li>
     *   <li>{@link #EXTRA_START_CALL_WITH_SPEAKERPHONE}</li>
     *   <li>{@link #EXTRA_START_CALL_WITH_VIDEO_STATE}</li>
     * </ul>
     *
     * @param participants List of participants to start conference with
     * @param extras Bundle of extras to use with the call
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    public void startConference(@NonNull List<Uri> participants,
            @NonNull Bundle extras) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                service.startConference(participants, extras,
                        mContext.getOpPackageName());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#placeCall", e);
            }
        }
    }

    /**
     * Enables and disables specified phone account.
     *
     * @param handle Handle to the phone account.
     * @param isEnabled Enable state of the phone account.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void enablePhoneAccount(PhoneAccountHandle handle, boolean isEnabled) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                service.enablePhoneAccount(handle, isEnabled);
            } catch (RemoteException e) {
                Log.e(TAG, "Error enablePhoneAbbount", e);
            }
        }
    }

    /**
     * Dumps telecom analytics for uploading.
     *
     * @return
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.DUMP)
    public TelecomAnalytics dumpAnalytics() {
        ITelecomService service = getTelecomService();
        TelecomAnalytics result = null;
        if (service != null) {
            try {
                result = service.dumpCallAnalytics();
            } catch (RemoteException e) {
                Log.e(TAG, "Error dumping call analytics", e);
            }
        }
        return result;
    }

    /**
     * Creates the {@link Intent} which can be used with {@link Context#startActivity(Intent)} to
     * launch the activity to manage blocked numbers.
     * <p> The activity will display the UI to manage blocked numbers only if
     * {@link android.provider.BlockedNumberContract#canCurrentUserBlockNumbers(Context)} returns
     * {@code true} for the current user.
     */
    public Intent createManageBlockedNumbersIntent() {
        ITelecomService service = getTelecomService();
        Intent result = null;
        if (service != null) {
            try {
                result = service.createManageBlockedNumbersIntent();
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling ITelecomService#createManageBlockedNumbersIntent", e);
            }
        }
        return result;
    }


    /**
     * Creates the {@link Intent} which can be used with {@link Context#startActivity(Intent)} to
     * launch the activity for emergency dialer.
     *
     * @param number Optional number to call in emergency dialer
     * @hide
     */
    @SystemApi
    @NonNull
    public Intent createLaunchEmergencyDialerIntent(@Nullable String number) {
        ITelecomService service = getTelecomService();
        Intent result = null;
        if (service != null) {
            try {
                result = service.createLaunchEmergencyDialerIntent(number);
            } catch (RemoteException e) {
                Log.e(TAG, "Error createLaunchEmergencyDialerIntent", e);
            }
        }
        return result;
    }

    /**
     * Determines whether Telecom would permit an incoming call to be added via the
     * {@link #addNewIncomingCall(PhoneAccountHandle, Bundle)} API for the specified
     * {@link PhoneAccountHandle}.
     * <p>
     * A {@link ConnectionService} may not add a call for the specified {@link PhoneAccountHandle}
     * in the following situations:
     * <ul>
     *     <li>{@link PhoneAccount} does not have property
     *     {@link PhoneAccount#CAPABILITY_SELF_MANAGED} set (i.e. it is a managed
     *     {@link ConnectionService}), and the active or held call limit has
     *     been reached.</li>
     *     <li>There is an ongoing emergency call.</li>
     * </ul>
     *
     * @param phoneAccountHandle The {@link PhoneAccountHandle} the call will be added for.
     * @return {@code true} if telecom will permit an incoming call to be added, {@code false}
     *      otherwise.
     */
    public boolean isIncomingCallPermitted(PhoneAccountHandle phoneAccountHandle) {
        if (phoneAccountHandle == null) {
            return false;
        }

        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                return service.isIncomingCallPermitted(phoneAccountHandle);
            } catch (RemoteException e) {
                Log.e(TAG, "Error isIncomingCallPermitted", e);
            }
        }
        return false;
    }

    /**
     * Determines whether Telecom would permit an outgoing call to be placed via the
     * {@link #placeCall(Uri, Bundle)} API for the specified {@link PhoneAccountHandle}.
     * <p>
     * A {@link ConnectionService} may not place a call for the specified {@link PhoneAccountHandle}
     * in the following situations:
     * <ul>
     *     <li>{@link PhoneAccount} does not have property
     *     {@link PhoneAccount#CAPABILITY_SELF_MANAGED} set (i.e. it is a managed
     *     {@link ConnectionService}), and the active, held or ringing call limit has
     *     been reached.</li>
     *     <li>{@link PhoneAccount} has property {@link PhoneAccount#CAPABILITY_SELF_MANAGED} set
     *     (i.e. it is a self-managed {@link ConnectionService} and there is an ongoing call in
     *     another {@link ConnectionService}.</li>
     *     <li>There is an ongoing emergency call.</li>
     * </ul>
     *
     * @param phoneAccountHandle The {@link PhoneAccountHandle} the call will be added for.
     * @return {@code true} if telecom will permit an outgoing call to be placed, {@code false}
     *      otherwise.
     */
    public boolean isOutgoingCallPermitted(PhoneAccountHandle phoneAccountHandle) {
        ITelecomService service = getTelecomService();
        if (service != null) {
            try {
                return service.isOutgoingCallPermitted(phoneAccountHandle);
            } catch (RemoteException e) {
                Log.e(TAG, "Error isOutgoingCallPermitted", e);
            }
        }
        return false;
    }

    /**
     * Called by an app to indicate that it wishes to accept the handover of an ongoing call to a
     * {@link PhoneAccountHandle} it defines.
     * <p>
     * A call handover is the process where an ongoing call is transferred from one app (i.e.
     * {@link ConnectionService} to another app.  The user could, for example, choose to continue a
     * mobile network call in a video calling app.  The mobile network call via the Telephony stack
     * is referred to as the source of the handover, and the video calling app is referred to as the
     * destination.
     * <p>
     * When considering a handover scenario the <em>initiating</em> device is where a user initiated
     * the handover process (e.g. by calling {@link android.telecom.Call#handoverTo(
     * PhoneAccountHandle, int, Bundle)}, and the other device is considered the <em>receiving</em>
     * device.
     * <p>
     * For a full discussion of the handover process and the APIs involved, see
     * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)}.
     * <p>
     * This method is called from the <em>receiving</em> side of a handover to indicate a desire to
     * accept the handover of an ongoing call to another {@link ConnectionService} identified by
     * {@link PhoneAccountHandle} destAcct. For managed {@link ConnectionService}s, the specified
     * {@link PhoneAccountHandle} must have been registered with {@link #registerPhoneAccount} and
     * the user must have enabled the corresponding {@link PhoneAccount}.  This can be checked using
     * {@link #getPhoneAccount}. Self-managed {@link ConnectionService}s must have
     * {@link android.Manifest.permission#MANAGE_OWN_CALLS} to handover a call to it.
     * <p>
     * Once invoked, this method will cause the system to bind to the {@link ConnectionService}
     * associated with the {@link PhoneAccountHandle} destAcct and call
     * (See {@link ConnectionService#onCreateIncomingHandoverConnection}).
     * <p>
     * For a managed {@link ConnectionService}, a {@link SecurityException} will be thrown if either
     * the {@link PhoneAccountHandle} destAcct does not correspond to a registered
     * {@link PhoneAccount} or the associated {@link PhoneAccount} is not currently enabled by the
     * user.
     * <p>
     * For a self-managed {@link ConnectionService}, a {@link SecurityException} will be thrown if
     * the calling app does not have {@link android.Manifest.permission#MANAGE_OWN_CALLS}.
     *
     * @param srcAddr The {@link android.net.Uri} of the ongoing call to handover to the caller’s
     *                {@link ConnectionService}.
     * @param videoState Video state after the handover.
     * @param destAcct The {@link PhoneAccountHandle} registered to the calling package.
     */
    public void acceptHandover(Uri srcAddr, @VideoProfile.VideoState int videoState,
            PhoneAccountHandle destAcct) {
        try {
            if (isServiceConnected()) {
                getTelecomService().acceptHandover(srcAddr, videoState, destAcct);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException acceptHandover: " + e);
        }
    }

    /**
     * Determines if there is an ongoing emergency call.  This can be either an outgoing emergency
     * call, as identified by the dialed number, or because a call was identified by the network
     * as an emergency call.
     * @return {@code true} if there is an ongoing emergency call, {@code false} otherwise.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean isInEmergencyCall() {
        try {
            if (isServiceConnected()) {
                return getTelecomService().isInEmergencyCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException isInEmergencyCall: " + e);
            return false;
        }
        return false;
    }

    /**
     * Handles {@link Intent#ACTION_CALL} intents trampolined from UserCallActivity.
     * @param intent The {@link Intent#ACTION_CALL} intent to handle.
     * @param callingPackageProxy The original package that called this before it was trampolined.
     * @hide
     */
    public void handleCallIntent(Intent intent, String callingPackageProxy) {
        try {
            if (isServiceConnected()) {
                getTelecomService().handleCallIntent(intent, callingPackageProxy);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException handleCallIntent: " + e);
        }
    }

    private ITelecomService getTelecomService() {
        if (mTelecomServiceOverride != null) {
            return mTelecomServiceOverride;
        }
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
