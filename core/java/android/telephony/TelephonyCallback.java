/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telephony;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.annotation.ChangeId;
import android.os.Binder;
import android.os.Build;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.MediaQualityStatus;
import android.telephony.ims.MediaThreshold;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.flags.Flags;

import dalvik.system.VMRuntime;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * A callback class for monitoring changes in specific telephony states
 * on the device, including service state, signal strength, message
 * waiting indicator (voicemail), and others.
 * <p>
 * To register a callback, use a {@link TelephonyCallback} which implements interfaces regarding
 * EVENT_*. For example,
 * FakeServiceStateCallback extends {@link TelephonyCallback} implements
 * {@link TelephonyCallback.ServiceStateListener}.
 * <p>
 * Then override the methods for the state that you wish to receive updates for, and
 * pass the executor and your TelephonyCallback object to
 * {@link TelephonyManager#registerTelephonyCallback}.
 * Methods are called when the state changes, as well as once on initial registration.
 * <p>
 * Note that access to some telephony information is
 * permission-protected. Your application won't receive updates for protected
 * information unless it has the appropriate permissions declared in
 * its manifest file. Where permissions apply, they are noted in the
 * appropriate sub-interfaces.
 */
public class TelephonyCallback {
    private static final String LOG_TAG = "TelephonyCallback";
    /**
     * Experiment flag to set the per-pid registration limit for TelephonyCallback
     *
     * Limit on registrations of {@link TelephonyCallback}s on a per-pid basis. When this limit is
     * exceeded, any calls to {@link TelephonyManager#registerTelephonyCallback} will fail with an
     * {@link IllegalStateException}.
     *
     * {@link android.os.Process#PHONE_UID}, {@link android.os.Process#SYSTEM_UID}, and the uid that
     * TelephonyRegistry runs under are exempt from this limit.
     *
     * If the value of the flag is less than 1, enforcement of the limit will be disabled.
     * @hide
     */
    public static final String FLAG_PER_PID_REGISTRATION_LIMIT =
            "phone_state_listener_per_pid_registration_limit";

    /**
     * Default value for the per-pid registration limit.
     * See {@link #FLAG_PER_PID_REGISTRATION_LIMIT}.
     * @hide
     */
    public static final int DEFAULT_PER_PID_REGISTRATION_LIMIT = 50;

    /**
     * This change enables a limit on the number of {@link TelephonyCallback} objects any process
     * may register via {@link TelephonyManager#registerTelephonyCallback}. The default limit is 50,
     * which may change via remote device config updates.
     *
     * This limit is enforced via an {@link IllegalStateException} thrown from
     * {@link TelephonyManager#registerTelephonyCallback} when the offending process attempts to
     * register one too many callbacks.
     *
     * @hide
     */
    @ChangeId
    public static final long PHONE_STATE_LISTENER_LIMIT_CHANGE_ID = 150880553L;

    /**
     * Event for changes to the network service state (cellular).
     *
     * <p>Requires {@link Manifest.permission#ACCESS_FINE_LOCATION} or {@link
     * Manifest.permission#ACCESS_COARSE_LOCATION} depending on the accuracy of the location info
     * listeners want to get.
     *
     * @hide
     * @see ServiceStateListener#onServiceStateChanged
     * @see ServiceState
     */
    @SystemApi
    public static final int EVENT_SERVICE_STATE_CHANGED = 1;

    /**
     * Event for changes to the network signal strength (cellular).
     *
     * @hide
     * @see SignalStrengthsListener#onSignalStrengthsChanged
     */
    @SystemApi
    public static final int EVENT_SIGNAL_STRENGTH_CHANGED = 2;

    /**
     * Event for changes to the message-waiting indicator.
     * <p>
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE} or that
     * the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     * <p>
     * Example: The status bar uses this to determine when to display the
     * voicemail icon.
     *
     * @hide
     * @see MessageWaitingIndicatorListener#onMessageWaitingIndicatorChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public static final int EVENT_MESSAGE_WAITING_INDICATOR_CHANGED = 3;

    /**
     * Event for changes to the call-forwarding indicator.
     * <p>
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE} or that
     * the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see CallForwardingIndicatorListener#onCallForwardingIndicatorChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public static final int EVENT_CALL_FORWARDING_INDICATOR_CHANGED = 4;

    /**
     * Event for changes to the device's cell location. Note that
     * this will result in frequent listeners to the listener.
     * <p>
     * If you need regular location updates but want more control over
     * the update interval or location precision, you can set up a callback
     * through the {@link android.location.LocationManager location manager}
     * instead.
     *
     * @hide
     * @see CellLocationListener#onCellLocationChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public static final int EVENT_CELL_LOCATION_CHANGED = 5;

    /**
     * Event for changes to the device call state.
     * <p>
     * Handles callbacks to {@link CallStateListener#onCallStateChanged(int)}.
     * <p>
     * Note: This is different from the legacy {@link #EVENT_LEGACY_CALL_STATE_CHANGED} listener
     * which can include the phone number of the caller.  We purposely do not include the phone
     * number as that information is not required for call state listeners going forward.
     * @hide
     */
    @SystemApi
    public static final int EVENT_CALL_STATE_CHANGED = 6;

    /**
     * Event for changes to the data connection state (cellular).
     *
     * @hide
     * @see DataConnectionStateListener#onDataConnectionStateChanged
     */
    @SystemApi
    public static final int EVENT_DATA_CONNECTION_STATE_CHANGED = 7;

    /**
     * Event for changes to the direction of data traffic on the data
     * connection (cellular).
     * <p>
     * Example: The status bar uses this to display the appropriate
     * data-traffic icon.
     *
     * @hide
     * @see DataActivityListener#onDataActivity
     */
    @SystemApi
    public static final int EVENT_DATA_ACTIVITY_CHANGED = 8;

    /**
     * Event for changes to the network signal strengths (cellular).
     * <p>
     * Example: The status bar uses this to control the signal-strength
     * icon.
     *
     * @hide
     * @see SignalStrengthsListener#onSignalStrengthsChanged
     */
    @SystemApi
    public static final int EVENT_SIGNAL_STRENGTHS_CHANGED = 9;

    /**
     * Event for changes of the network signal strengths (cellular) always reported from modem,
     * even in some situations such as the screen of the device is off.
     *
     * @hide
     * @see TelephonyManager#setSignalStrengthUpdateRequest
     */
    @SystemApi
    public static final int EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED = 10;

    /**
     * Event for changes to observed cell info.
     *
     * @hide
     * @see CellInfoListener#onCellInfoChanged
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
    })    public static final int EVENT_CELL_INFO_CHANGED = 11;

    /**
     * Event for {@link android.telephony.Annotation.PreciseCallStates} of ringing,
     * background and foreground calls.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see PreciseCallStateListener#onPreciseCallStateChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_PRECISE_CALL_STATE_CHANGED = 12;

    /**
     * Event for {@link PreciseDataConnectionState} on the data connection (cellular).
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see PreciseDataConnectionStateListener#onPreciseDataConnectionStateChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED = 13;

    /**
     * Event for real time info for all data connections (cellular)).
     *
     * @hide
     * @see PhoneStateListener#onDataConnectionRealTimeInfoChanged(DataConnectionRealTimeInfo)
     * @deprecated Use {@link TelephonyManager#requestModemActivityInfo}
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_DATA_CONNECTION_REAL_TIME_INFO_CHANGED = 14;

    /**
     * Event for OEM hook raw event
     *
     * @hide
     * @see PhoneStateListener#onOemHookRawEvent
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int EVENT_OEM_HOOK_RAW = 15;

    /**
     * Event for changes to the SRVCC state of the active call.
     *
     * @hide
     * @see SrvccStateListener#onSrvccStateChanged
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int EVENT_SRVCC_STATE_CHANGED = 16;

    /**
     * Event for carrier network changes indicated by a carrier app.
     *
     * @hide
     * @see android.service.carrier.CarrierService#notifyCarrierNetworkChange(boolean)
     * @see CarrierNetworkListener#onCarrierNetworkChange
     */
    @SystemApi
    public static final int EVENT_CARRIER_NETWORK_CHANGED = 17;

    /**
     * Event for changes to the sim voice activation state
     *
     * @hide
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATING
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_DEACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_RESTRICTED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_UNKNOWN
     * <p>
     * Example: TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED indicates voice service has been
     * fully activated
     * @see VoiceActivationStateListener#onVoiceActivationStateChanged
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int EVENT_VOICE_ACTIVATION_STATE_CHANGED = 18;

    /**
     * Event for changes to the sim data activation state
     *
     * @hide
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATING
     * @see TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_DEACTIVATED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_RESTRICTED
     * @see TelephonyManager#SIM_ACTIVATION_STATE_UNKNOWN
     * <p>
     * Example: TelephonyManager#SIM_ACTIVATION_STATE_ACTIVATED indicates data service has been
     * fully activated
     * @see DataActivationStateListener#onDataActivationStateChanged
     */
    @SystemApi
    public static final int EVENT_DATA_ACTIVATION_STATE_CHANGED = 19;

    /**
     * Event for changes to the user mobile data state
     *
     * @hide
     * @see UserMobileDataStateListener#onUserMobileDataStateChanged
     */
    @SystemApi
    public static final int EVENT_USER_MOBILE_DATA_STATE_CHANGED = 20;

    /**
     * Event for display info changed event.
     *
     * @hide
     * @see DisplayInfoListener#onDisplayInfoChanged
     */
    @SystemApi
    public static final int EVENT_DISPLAY_INFO_CHANGED = 21;

    /**
     * Event for changes to the phone capability.
     *
     * @hide
     * @see PhoneCapabilityListener#onPhoneCapabilityChanged
     */
    @SystemApi
    public static final int EVENT_PHONE_CAPABILITY_CHANGED = 22;

    /**
     * Event for changes to active data subscription ID. Active data subscription is
     * the current subscription used to setup Cellular Internet data. The data is only active on the
     * subscription at a time, even it is multi-SIM mode. For example, it could be the current
     * active opportunistic subscription in use, or the subscription user selected as default data
     * subscription in DSDS mode.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PHONE_STATE} or the calling
     * app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see ActiveDataSubscriptionIdListener#onActiveDataSubscriptionIdChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public static final int EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED = 23;

    /**
     * Event for changes to the radio power state.
     *
     * @hide
     * @see RadioPowerStateListener#onRadioPowerStateChanged
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int EVENT_RADIO_POWER_STATE_CHANGED = 24;

    /**
     * Event for changes to emergency number list based on all active subscriptions.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PHONE_STATE} or the calling
     * app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see EmergencyNumberListListener#onEmergencyNumberListChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public static final int EVENT_EMERGENCY_NUMBER_LIST_CHANGED = 25;

    /**
     * Event for call disconnect causes which contains {@link DisconnectCause} and
     * {@link PreciseDisconnectCause}.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see CallDisconnectCauseListener#onCallDisconnectCauseChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_CALL_DISCONNECT_CAUSE_CHANGED = 26;

    /**
     * Event for changes to the call attributes of a currently active call.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see CallAttributesListener#onCallAttributesChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_CALL_ATTRIBUTES_CHANGED = 27;

    /**
     * Event for IMS call disconnect causes which contains
     * {@link android.telephony.ims.ImsReasonInfo}
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see ImsCallDisconnectCauseListener#onImsCallDisconnectCauseChanged(ImsReasonInfo)
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED = 28;

    /**
     * Event for the emergency number placed from an outgoing call.
     *
     * @hide
     * @see OutgoingEmergencyCallListener#onOutgoingEmergencyCall
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
    public static final int EVENT_OUTGOING_EMERGENCY_CALL = 29;

    /**
     * Event for the emergency number placed from an outgoing SMS.
     *
     * @hide
     * @see OutgoingEmergencySmsListener#onOutgoingEmergencySms
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
    public static final int EVENT_OUTGOING_EMERGENCY_SMS = 30;

    /**
     * Event for registration failures.
     * <p>
     * Event for indications that a registration procedure has failed in either the CS or PS
     * domain. This indication does not necessarily indicate a change of service state, which should
     * be tracked via {@link #EVENT_SERVICE_STATE_CHANGED}.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE} or
     * the calling app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * <p>Requires the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission in case that
     * listener want to get location info in {@link CellIdentity} regardless of whether the calling
     * app has carrier privileges.
     *
     * @hide
     * @see RegistrationFailedListener#onRegistrationFailed
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.READ_PRECISE_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
    })
    public static final int EVENT_REGISTRATION_FAILURE = 31;

    /**
     * Event for Barring Information for the current registered / camped cell.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE} or
     * the calling app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * <p>Requires the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission in case that
     * listener want to get {@link BarringInfo} which includes location info in {@link CellIdentity}
     * regardless of whether the calling app has carrier privileges.
     *
     * @hide
     * @see BarringInfoListener#onBarringInfoChanged
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.READ_PRECISE_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
    })
    public static final int EVENT_BARRING_INFO_CHANGED = 32;

    /**
     * Event for changes to the physical channel configuration.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see PhysicalChannelConfigListener#onPhysicalChannelConfigChanged
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED = 33;


    /**
     * Event for changes to the data enabled.
     * <p>
     * Event for indications that the enabled status of current data has changed.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     * or the calling app has carrier privileges
     * (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see DataEnabledListener#onDataEnabledChanged
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_DATA_ENABLED_CHANGED = 34;

    /**
     * Event for changes to allowed network list based on all active subscriptions.
     *
     * @hide
     * @see AllowedNetworkTypesListener#onAllowedNetworkTypesChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED = 35;

    /**
     * Event for changes to the legacy call state changed listener implemented by
     * {@link PhoneStateListener#onCallStateChanged(int, String)}.  This listener variant is similar
     * to the new {@link CallStateListener#onCallStateChanged(int)} with the important distinction
     * that it CAN provide the phone number associated with a call.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_CALL_LOG)
    public static final int EVENT_LEGACY_CALL_STATE_CHANGED = 36;


    /**
     * Event for changes to the link capacity estimate (LCE)
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     *
     * @see LinkCapacityEstimateChangedListener#onLinkCapacityEstimateChanged
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_LINK_CAPACITY_ESTIMATE_CHANGED = 37;

    /**
     * Event to norify the Anbr information from Radio to Ims.
     *
     * @see ImsCallSessionImplBase#callSessionNotifyAnbr.
     *
     * @hide
     */
    public static final int EVENT_TRIGGER_NOTIFY_ANBR = 38;

    /**
     * Event for changes to the media quality status
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
     *
     * @see MediaQualityStatusChangedListener#onMediaQualityStatusChanged
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public static final int EVENT_MEDIA_QUALITY_STATUS_CHANGED = 39;


    /**
     * Event for changes to the Emergency callback mode
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
     * @see EmergencyCallbackModeListener#onCallbackModeStarted(int)
     * @see EmergencyCallbackModeListener#onCallbackModeStopped(int, int)
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int EVENT_EMERGENCY_CALLBACK_MODE_CHANGED = 40;

    /**
     * Event for listening to changes in simultaneous cellular calling subscriptions.
     *
     * @see SimultaneousCellularCallingSupportListener
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SIMULTANEOUS_CALLING_INDICATIONS)
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @SystemApi
    public static final int EVENT_SIMULTANEOUS_CELLULAR_CALLING_SUBSCRIPTIONS_CHANGED = 41;

    /**
     * Event for listening to changes in carrier roaming non-terrestrial network mode.
     *
     * @see CarrierRoamingNtnModeListener
     *
     * @hide
     */
    public static final int EVENT_CARRIER_ROAMING_NTN_MODE_CHANGED = 42;

    /**
     * Event for listening to changes in carrier roaming non-terrestrial network eligibility.
     *
     * @see CarrierRoamingNtnModeListener
     *
     * Device is eligible for satellite communication if all the following conditions are met:
     * <ul>
     * <li>Any subscription on the device supports P2P satellite messaging which is defined by
     * {@link CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL} </li>
     * <li>{@link CarrierConfigManager#KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT} set to
     * {@link CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_MANUAL} </li>
     * <li>The device is in {@link ServiceState#STATE_OUT_OF_SERVICE}, not connected to Wi-Fi,
     * and the hysteresis timer defined by {@link CarrierConfigManager
     * #KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT} is expired.
     * </li>
     * </ul>
     *
     * @hide
     */
    public static final int EVENT_CARRIER_ROAMING_NTN_ELIGIBLE_STATE_CHANGED = 43;

    /**
     * @hide
     */
    @IntDef(prefix = {"EVENT_"}, value = {
            EVENT_SERVICE_STATE_CHANGED,
            EVENT_SIGNAL_STRENGTH_CHANGED,
            EVENT_MESSAGE_WAITING_INDICATOR_CHANGED,
            EVENT_CALL_FORWARDING_INDICATOR_CHANGED,
            EVENT_CELL_LOCATION_CHANGED,
            EVENT_CALL_STATE_CHANGED,
            EVENT_DATA_CONNECTION_STATE_CHANGED,
            EVENT_DATA_ACTIVITY_CHANGED,
            EVENT_SIGNAL_STRENGTHS_CHANGED,
            EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED,
            EVENT_CELL_INFO_CHANGED,
            EVENT_PRECISE_CALL_STATE_CHANGED,
            EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED,
            EVENT_DATA_CONNECTION_REAL_TIME_INFO_CHANGED,
            EVENT_OEM_HOOK_RAW,
            EVENT_SRVCC_STATE_CHANGED,
            EVENT_CARRIER_NETWORK_CHANGED,
            EVENT_VOICE_ACTIVATION_STATE_CHANGED,
            EVENT_DATA_ACTIVATION_STATE_CHANGED,
            EVENT_USER_MOBILE_DATA_STATE_CHANGED,
            EVENT_DISPLAY_INFO_CHANGED,
            EVENT_PHONE_CAPABILITY_CHANGED,
            EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED,
            EVENT_RADIO_POWER_STATE_CHANGED,
            EVENT_EMERGENCY_NUMBER_LIST_CHANGED,
            EVENT_CALL_DISCONNECT_CAUSE_CHANGED,
            EVENT_CALL_ATTRIBUTES_CHANGED,
            EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED,
            EVENT_OUTGOING_EMERGENCY_CALL,
            EVENT_OUTGOING_EMERGENCY_SMS,
            EVENT_REGISTRATION_FAILURE,
            EVENT_BARRING_INFO_CHANGED,
            EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED,
            EVENT_DATA_ENABLED_CHANGED,
            EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED,
            EVENT_LEGACY_CALL_STATE_CHANGED,
            EVENT_LINK_CAPACITY_ESTIMATE_CHANGED,
            EVENT_TRIGGER_NOTIFY_ANBR,
            EVENT_MEDIA_QUALITY_STATUS_CHANGED,
            EVENT_EMERGENCY_CALLBACK_MODE_CHANGED,
            EVENT_SIMULTANEOUS_CELLULAR_CALLING_SUBSCRIPTIONS_CHANGED,
            EVENT_CARRIER_ROAMING_NTN_MODE_CHANGED,
            EVENT_CARRIER_ROAMING_NTN_ELIGIBLE_STATE_CHANGED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TelephonyEvent {
    }

    /**
     * @hide
     */
    //TODO: The maxTargetSdk should be S if the build time tool updates it.
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public IPhoneStateListener callback;

    /**
     * @hide
     */
    public void init(@NonNull @CallbackExecutor Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("TelephonyCallback Executor must be non-null");
        }
        callback = new IPhoneStateListenerStub(this, executor);
    }

    /**
     * Interface for service state listener.
     */
    public interface ServiceStateListener {
        /**
         * Callback invoked when device service state changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         * <p>
         * The instance of {@link ServiceState} passed as an argument here will have various
         * levels of location information stripped from it depending on the location permissions
         * that your app holds.
         * Only apps holding the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission will
         * receive all the information in {@link ServiceState}, otherwise the cellIdentity
         * will be null if apps only holding the {@link Manifest.permission#ACCESS_COARSE_LOCATION}
         * permission. Network operator name in long/short alphanumeric format and numeric id will
         * be null if apps holding neither {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
         *
         * @see ServiceState#STATE_EMERGENCY_ONLY
         * @see ServiceState#STATE_IN_SERVICE
         * @see ServiceState#STATE_OUT_OF_SERVICE
         * @see ServiceState#STATE_POWER_OFF
         */
        void onServiceStateChanged(@NonNull ServiceState serviceState);
    }

    /**
     * Interface for message waiting indicator listener.
     */
    public interface MessageWaitingIndicatorListener {
        /**
         * Callback invoked when the message-waiting indicator changes on the registered
         * subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PHONE_STATE}.
         *
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        void onMessageWaitingIndicatorChanged(boolean mwi);
    }

    /**
     * Interface for call-forwarding indicator listener.
     */
    public interface CallForwardingIndicatorListener {
        /**
         * Callback invoked when the call-forwarding indicator changes on the registered
         * subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PHONE_STATE}.
         *
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        void onCallForwardingIndicatorChanged(boolean cfi);
    }

    /**
     * Interface for device cell location listener.
     */
    public interface CellLocationListener {
        /**
         * Callback invoked when device cell location changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         */
        @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        void onCellLocationChanged(@NonNull CellLocation location);
    }

    /**
     * Interface for call state listener.
     */
    public interface CallStateListener {
        /**
         * Callback invoked when device call state changes.
         * <p>
         * Reports the state of Telephony (mobile) calls on the device for the registered
         * subscription.
         * <p>
         * Note: the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         * <p>
         * Note: The state returned here may differ from that returned by
         * {@link TelephonyManager#getCallState()}. Receivers of this callback should be aware that
         * calling {@link TelephonyManager#getCallState()} from within this callback may return a
         * different state than the callback reports.
         *
         * @param state the current call state
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        void onCallStateChanged(@Annotation.CallState int state);
    }

    /**
     * Interface for data connection state listener.
     */
    public interface DataConnectionStateListener {
        /**
         * Callback invoked when connection state changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param state       is the current state of data connection.
         * @param networkType is the current network type of data connection.
         * @see TelephonyManager#DATA_DISCONNECTED
         * @see TelephonyManager#DATA_CONNECTING
         * @see TelephonyManager#DATA_CONNECTED
         * @see TelephonyManager#DATA_SUSPENDED
         * @see TelephonyManager#DATA_HANDOVER_IN_PROGRESS
         */
        void onDataConnectionStateChanged(@TelephonyManager.DataState int state,
                @Annotation.NetworkType int networkType);
    }

    /**
     * Interface for data activity state listener.
     */
    public interface DataActivityListener {
        /**
         * Callback invoked when data activity state changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @see TelephonyManager#DATA_ACTIVITY_NONE
         * @see TelephonyManager#DATA_ACTIVITY_IN
         * @see TelephonyManager#DATA_ACTIVITY_OUT
         * @see TelephonyManager#DATA_ACTIVITY_INOUT
         * @see TelephonyManager#DATA_ACTIVITY_DORMANT
         */
        void onDataActivity(@Annotation.DataActivityType int direction);
    }

    /**
     * Interface for network signal strengths listener.
     */
    public interface SignalStrengthsListener {
        /**
         * Callback invoked when network signal strengths changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         */
        void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength);
    }

    /**
     * Interface for cell info listener.
     */
    public interface CellInfoListener {
        /**
         * Callback invoked when a observed cell info has changed or new cells have been added
         * or removed on the registered subscription.
         * Note, the registration subscription ID s from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param cellInfo is the list of currently visible cells.
         */
        @RequiresPermission(allOf = {
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION
        })
        void onCellInfoChanged(@NonNull List<CellInfo> cellInfo);
    }

    /**
     * Interface for precise device call state listener.
     *
     * @hide
     */
    @SystemApi
    public interface PreciseCallStateListener {
        /**
         * Callback invoked when precise device call state changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}.
         *
         * @param callState {@link PreciseCallState}
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onPreciseCallStateChanged(@NonNull PreciseCallState callState);
    }

    /**
     * Interface for call disconnect cause listener.
     */
    public interface CallDisconnectCauseListener {
        /**
         * Callback invoked when call disconnect cause changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param disconnectCause        the disconnect cause
         * @param preciseDisconnectCause the precise disconnect cause
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onCallDisconnectCauseChanged(@Annotation.DisconnectCauses int disconnectCause,
                @Annotation.PreciseDisconnectCauses int preciseDisconnectCause);
    }

    /**
     * Interface for IMS call disconnect cause listener.
     */
    public interface ImsCallDisconnectCauseListener {
        /**
         * Callback invoked when IMS call disconnect cause changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}.
         *
         * @param imsReasonInfo {@link ImsReasonInfo} contains details on why IMS call failed.
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onImsCallDisconnectCauseChanged(@NonNull ImsReasonInfo imsReasonInfo);
    }

    /**
     * Interface for precise data connection state listener.
     */
    public interface PreciseDataConnectionStateListener {
        /**
         * Callback providing update about the default/internet data connection on the registered
         * subscription.
         * <p>
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}.
         *
         * @param dataConnectionState {@link PreciseDataConnectionState}
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onPreciseDataConnectionStateChanged(
            @NonNull PreciseDataConnectionState dataConnectionState);
    }

    /**
     * Interface for Single Radio Voice Call Continuity listener.
     *
     * @hide
     */
    @SystemApi
    public interface SrvccStateListener {
        /**
         * Callback invoked when there has been a change in the Single Radio Voice Call Continuity
         * (SRVCC) state for the currently active call on the registered subscription.
         * <p>
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         */
        @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onSrvccStateChanged(@Annotation.SrvccState int srvccState);
    }

    /**
     * Interface for SIM voice activation state listener.
     *
     * @hide
     */
    @SystemApi
    public interface VoiceActivationStateListener {
        /**
         * Callback invoked when the SIM voice activation state has changed on the registered
         * subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param state is the current SIM voice activation state
         */
        @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onVoiceActivationStateChanged(@Annotation.SimActivationState int state);

    }

    /**
     * Interface for SIM data activation state listener.
     */
    public interface DataActivationStateListener {
        /**
         * Callback invoked when the SIM data activation state has changed on the registered
         * subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param state is the current SIM data activation state
         */
        void onDataActivationStateChanged(@Annotation.SimActivationState int state);
    }

    /**
     * Interface for user mobile data state listener.
     */
    public interface UserMobileDataStateListener {
        /**
         * Callback invoked when the user mobile data state has changed on the registered
         * subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param enabled indicates whether the current user mobile data state is enabled or
         *                disabled.
         */
        void onUserMobileDataStateChanged(boolean enabled);
    }

    /**
     * Interface for display info listener.
     */
    public interface DisplayInfoListener {
        /**
         * Callback invoked when the display info has changed on the registered subscription.
         * <p> The {@link TelephonyDisplayInfo} contains status information shown to the user
         * based on carrier policy.
         *
         * @param telephonyDisplayInfo The display information.
         */
        void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo telephonyDisplayInfo);
    }

    /**
     * Interface for the current emergency number list listener.
     */
    public interface EmergencyNumberListListener {
        /**
         * Callback invoked when the current emergency number list has changed on the registered
         * subscription.
         * <p>
         * Note, the registered subscription is associated with {@link TelephonyManager} object
         * on which
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}
         * was called.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * given subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PHONE_STATE}.
         *
         * @param emergencyNumberList Map associating all active subscriptions on the device with
         *                            the list of emergency numbers originating from that
         *                            subscription.
         *                            If there are no active subscriptions, the map will contain a
         *                            single entry with
         *                            {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} as
         *                            the key and a list of emergency numbers as the value. If no
         *                            emergency number information is available, the value will be
         *                            empty.
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        void onEmergencyNumberListChanged(@NonNull Map<Integer,
                List<EmergencyNumber>> emergencyNumberList);
    }

    /**
     * Interface for outgoing emergency call listener.
     *
     * @hide
     */
    @SystemApi
    public interface OutgoingEmergencyCallListener {
        /**
         * Callback invoked when an outgoing call is placed to an emergency number.
         * <p>
         * This method will be called when an emergency call is placed on any subscription
         * (including the no-SIM case), regardless of which subscription this callback was
         * registered on.
         * <p>
         *
         * @param placedEmergencyNumber The {@link EmergencyNumber} the emergency call was
         *                              placed to.
         * @param subscriptionId        The subscription ID used to place the emergency call. If the
         *                              emergency call was placed without a valid subscription
         *                              (e.g. when there are no SIM cards in the device), this
         *                              will be
         *                              equal to
         *                              {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
         */
        @RequiresPermission(Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
        void onOutgoingEmergencyCall(@NonNull EmergencyNumber placedEmergencyNumber,
                int subscriptionId);
    }

    /**
     * Interface for outgoing emergency sms listener.
     *
     * @hide
     */
    @SystemApi
    public interface OutgoingEmergencySmsListener {
        /**
         * Smsback invoked when an outgoing sms is sent to an emergency number.
         * <p>
         * This method will be called when an emergency sms is sent on any subscription,
         * regardless of which subscription this callback was registered on.
         *
         * @param sentEmergencyNumber The {@link EmergencyNumber} the emergency sms was sent to.
         * @param subscriptionId      The subscription ID used to send the emergency sms.
         */
        @RequiresPermission(Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
        void onOutgoingEmergencySms(@NonNull EmergencyNumber sentEmergencyNumber,
                int subscriptionId);
    }

    /**
     * Interface for phone capability listener.
     *
     * @hide
     */
    @SystemApi
    public interface PhoneCapabilityListener {
        /**
         * Callback invoked when phone capability changes.
         * Note, this callback triggers regardless of registered subscription.
         *
         * @param capability the new phone capability
         */
        void onPhoneCapabilityChanged(@NonNull PhoneCapability capability);
    }

    /**
     * Interface for active data subscription ID listener.
     */
    public interface ActiveDataSubscriptionIdListener {
        /**
         * Callback invoked when active data subscription ID changes.
         * Note, this callback triggers regardless of registered subscription.
         *
         * @param subId current subscription used to setup Cellular Internet data. The data is
         *              only active on the subscription at a time, even it is multi-SIM mode.
         *              For example, it could be the current active opportunistic subscription
         *              in use, or the subscription user selected as default data subscription in
         *              DSDS mode.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PHONE_STATE}.
         *
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        void onActiveDataSubscriptionIdChanged(int subId);
    }

    /**
     * Interface for modem radio power state listener.
     *
     * @hide
     */
    @SystemApi
    public interface RadioPowerStateListener {
        /**
         * Callback invoked when modem radio power state changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param state the modem radio power state
         */
        @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onRadioPowerStateChanged(@Annotation.RadioPowerState int state);
    }

    /**
     * Interface for carrier network listener.
     */
    public interface CarrierNetworkListener {
        /**
         * Callback invoked when telephony has received notice from a carrier
         * app that a network action that could result in connectivity loss
         * has been requested by an app using
         * {@link android.service.carrier.CarrierService#notifyCarrierNetworkChange(boolean)}
         * <p>
         * This is optional and is only used to allow the system to provide alternative UI while
         * telephony is performing an action that may result in intentional, temporary network
         * lack of connectivity.
         * <p>
         * Note, this callback is pinned to the registered subscription and will be invoked when
         * the notifying carrier app has carrier privilege rule on the registered
         * subscription. {@link android.telephony.TelephonyManager#hasCarrierPrivileges}
         *
         * @param active If the carrier network change is or shortly will be active,
         *               {@code true} indicate that showing alternative UI, {@code false} otherwise.
         */
        void onCarrierNetworkChange(boolean active);
    }

    /**
     * Interface for registration failures listener.
     */
    public interface RegistrationFailedListener {
        /**
         * Report that Registration or a Location/Routing/Tracking Area update has failed.
         *
         * <p>Indicate whenever a registration procedure, including a location, routing, or tracking
         * area update fails. This includes procedures that do not necessarily result in a change of
         * the modem's registration status. If the modem's registration status changes, that is
         * reflected in the onNetworkStateChanged() and subsequent
         * get{Voice/Data}RegistrationState().
         *
         * <p>Because registration failures are ephemeral, this callback is not sticky.
         * Registrants will not receive the most recent past value when registering.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE} and
         * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
         *
         * If the calling app doesn't have {@link android.Manifest.permission#ACCESS_FINE_LOCATION},
         * it will receive {@link CellIdentity} without location-sensitive information included.
         *
         * @param cellIdentity        the CellIdentity, which must include the globally unique
         *                            identifier
         *                            for the cell (for example, all components of the CGI or ECGI).
         * @param chosenPlmn          a 5 or 6 digit alphanumeric PLMN (MCC|MNC) among those
         *                            broadcast by the
         *                            cell that was chosen for the failed registration attempt.
         * @param domain              DOMAIN_CS, DOMAIN_PS or both in case of a combined procedure.
         * @param causeCode           the primary failure cause code of the procedure.
         *                            For GSM/UMTS (MM), values are in TS 24.008 Sec 10.5.95
         *                            For GSM/UMTS (GMM), values are in TS 24.008 Sec 10.5.147
         *                            For LTE (EMM), cause codes are TS 24.301 Sec 9.9.3.9
         *                            For NR (5GMM), cause codes are TS 24.501 Sec 9.11.3.2
         *                            Integer.MAX_VALUE if this value is unused.
         * @param additionalCauseCode the cause code of any secondary/combined procedure
         *                            if appropriate. For UMTS, if a combined attach succeeds for
         *                            PS only, then the GMM cause code shall be included as an
         *                            additionalCauseCode. For LTE (ESM), cause codes are in
         *                            TS 24.301 9.9.4.4. Integer.MAX_VALUE if this value is unused.
         */
        @RequiresPermission(allOf = {
                Manifest.permission.READ_PRECISE_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION
        })
        void onRegistrationFailed(@NonNull CellIdentity cellIdentity, @NonNull String chosenPlmn,
                @NetworkRegistrationInfo.Domain int domain, int causeCode, int additionalCauseCode);
    }

    /**
     * Interface for the current allowed network type list listener. This list involves values of
     * allowed network type for each of reasons.
     *
     * @hide
     */
    @SystemApi
    public interface AllowedNetworkTypesListener {
        /**
         * Callback invoked when the current allowed network type list has changed on the
         * registered subscription for a specified reason.
         * Note, the registered subscription is associated with {@link TelephonyManager} object
         * on which {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}
         * was called.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * given subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param reason an allowed network type reasons.
         * @see TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_USER
         * @see TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_POWER
         * @see TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_CARRIER
         * @see TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G
         *
         * @param allowedNetworkType an allowed network type bitmask value. (for example,
         * the long bitmask value is {{@link TelephonyManager#NETWORK_TYPE_BITMASK_NR}|
         * {@link TelephonyManager#NETWORK_TYPE_BITMASK_LTE}})
         *
         * For example:
         * If the latest allowed network type is changed by user, then the system
         * notifies the {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_USER} and
         * long type value}.
         */
        @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onAllowedNetworkTypesChanged(@TelephonyManager.AllowedNetworkTypesReason int reason,
                @TelephonyManager.NetworkTypeBitMask long allowedNetworkType);
    }

    /**
     * Interface for listening to changes in the simultaneous cellular calling state for active
     * cellular subscriptions.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SIMULTANEOUS_CALLING_INDICATIONS)
    @SystemApi
    public interface SimultaneousCellularCallingSupportListener {
        /**
         * Notify the Listener that the subscriptions available for simultaneous <b>cellular</b>
         * calling have changed.
         * <p>
         * If we have an ongoing <b>cellular</b> call on one subscription in this Set, a
         * simultaneous incoming or outgoing <b>cellular</b> call is possible on any of the
         * subscriptions in this Set. On a traditional Dual Sim Dual Standby device, simultaneous
         * calling is not possible between subscriptions, where on a Dual Sim Dual Active device,
         * simultaneous calling may be possible between subscriptions in certain network conditions.
         * <p>
         * Note: This listener only tracks the capability of the modem to perform simultaneous
         * cellular calls and does not track the simultaneous calling state of scenarios based on
         * multiple IMS registration over multiple transports (WiFi/Internet calling).
         * <p>
         * Note: This listener fires for all changes to cellular calling subscriptions independent
         * of which subscription it is registered on.
         *
         * @param simultaneousCallingSubscriptionIds The Set of subscription IDs that support
         * simultaneous calling. If there is an ongoing call on a subscription in this Set, then a
         * simultaneous incoming or outgoing call is only possible for other subscriptions in this
         * Set. If there is an ongoing call on a subscription that is not in this Set, then
         * simultaneous calling is not possible at the current time.
         *
         */
        @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onSimultaneousCellularCallingSubscriptionsChanged(
                @NonNull Set<Integer> simultaneousCallingSubscriptionIds);
    }

    /**
     * Interface for call attributes listener.
     *
     * @hide
     */
    @SystemApi
    public interface CallAttributesListener {
        /**
         * Callback invoked when the call attributes changes on the active call on the registered
         * subscription. If the user swaps between a foreground and background call the call
         * attributes will be reported for the active call only.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}.
         *
         * @param callAttributes the call attributes
         * @deprecated Use onCallStatesChanged({@link List<CallState>}) to get each of call
         *          state for all ongoing calls on the subscription.
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        @Deprecated
        default void onCallAttributesChanged(@NonNull CallAttributes callAttributes) {
            Log.w(LOG_TAG, "onCallAttributesChanged(List<CallState>) should be "
                    + "overridden.");
        }

        /**
         * Callback invoked when the call attributes changes on the ongoing calls on the registered
         * subscription. If there are 1 foreground and 1 background call, Two {@link CallState}
         * will be passed.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         * In the event that there are no active(state is not
         * {@link PreciseCallState#PRECISE_CALL_STATE_IDLE}) calls, this API will report empty list.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}.
         *
         * @param callStateList the list of call states for each ongoing call. If there are
         *                           a active call and a holding call, 1 call attributes for
         *                           {@link PreciseCallState#PRECISE_CALL_STATE_ACTIVE}  and another
         *                           for {@link PreciseCallState#PRECISE_CALL_STATE_HOLDING}
         *                           will be in this list.
         */
        // Added as default for backward compatibility
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        default void onCallStatesChanged(@NonNull List<CallState> callStateList) {
            if (callStateList.size() > 0) {
                int foregroundCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;
                int backgroundCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;
                int ringingCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;
                for (CallState cs : callStateList) {
                    switch (cs.getCallClassification()) {
                        case CallState.CALL_CLASSIFICATION_FOREGROUND:
                            foregroundCallState = cs.getCallState();
                            break;
                        case CallState.CALL_CLASSIFICATION_BACKGROUND:
                            backgroundCallState = cs.getCallState();
                            break;
                        case CallState.CALL_CLASSIFICATION_RINGING:
                            ringingCallState = cs.getCallState();
                            break;
                        default:
                            break;
                    }
                }
                onCallAttributesChanged(new CallAttributes(
                        new PreciseCallState(
                                ringingCallState, foregroundCallState, backgroundCallState,
                                DisconnectCause.NOT_VALID, PreciseDisconnectCause.NOT_VALID),
                        callStateList.get(0).getNetworkType(),
                        callStateList.get(0).getCallQuality()));
            } else {
                onCallAttributesChanged(new CallAttributes(
                        new PreciseCallState(PreciseCallState.PRECISE_CALL_STATE_IDLE,
                                PreciseCallState.PRECISE_CALL_STATE_IDLE,
                                PreciseCallState.PRECISE_CALL_STATE_IDLE,
                                DisconnectCause.NOT_VALID, PreciseDisconnectCause.NOT_VALID),
                        TelephonyManager.NETWORK_TYPE_UNKNOWN, new CallQuality()));
            }
        }
    }

    /**
     * Interface for barring information listener.
     */
    public interface BarringInfoListener {
        /**
         * Report updated barring information for the current camped/registered cell.
         *
         * <p>Barring info is provided for all services applicable to the current camped/registered
         * cell, for the registered PLMN and current access class/access category.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE} and
         * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
         *
         * If the calling app doesn't have {@link android.Manifest.permission#ACCESS_FINE_LOCATION},
         * it will receive {@link BarringInfo} including {@link CellIdentity} without
         * location-sensitive information included.
         *
         * @param barringInfo for all services on the current cell.
         * @see android.telephony.BarringInfo
         */
        @RequiresPermission(allOf = {
                Manifest.permission.READ_PRECISE_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION
        })
        void onBarringInfoChanged(@NonNull BarringInfo barringInfo);
    }

    /**
     * Interface for current physical channel configuration listener.
     */
    public interface PhysicalChannelConfigListener {
        /**
         * Callback invoked when the current physical channel configuration has changed
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}.
         *
         * @param configs List of the current {@link PhysicalChannelConfig}s
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onPhysicalChannelConfigChanged(@NonNull List<PhysicalChannelConfig> configs);
    }

    /**
     * Interface for data enabled listener.
     *
     * @hide
     */
    @SystemApi
    public interface DataEnabledListener {
        /**
         * Callback invoked when the data enabled changes.
         *
         * The calling app should have carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}) if it does not have the
         * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}.
         *
         * @param enabled {@code true} if data is enabled, otherwise disabled.
         * @param reason  Reason for data enabled/disabled.
         *                See {@link TelephonyManager.DataEnabledChangedReason}.
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onDataEnabledChanged(boolean enabled,
                @TelephonyManager.DataEnabledChangedReason int reason);
    }

    /**
     * Interface for link capacity estimate changed listener.
     *
     * @hide
     */
    @SystemApi
    public interface LinkCapacityEstimateChangedListener {
        /**
         * Callback invoked when the link capacity estimate (LCE) changes
         *
         * @param linkCapacityEstimateList a list of {@link LinkCapacityEstimate}
         * The list size is at least 1.
         * In case of a dual connected network, the list size could be 2.
         * Use {@link LinkCapacityEstimate#getType()} to get the type of each element.
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onLinkCapacityEstimateChanged(
                @NonNull List<LinkCapacityEstimate> linkCapacityEstimateList);
    }

    /**
     * Interface for media quality status changed listener.
     *
     * @hide
     */
    @SystemApi
    public interface MediaQualityStatusChangedListener {
        /**
         * Callback invoked when the media quality status of IMS call changes. This call back
         * means current media quality status crosses at least one of threshold values in {@link
         * MediaThreshold}. Listener needs to get quality information & check whether it crossed
         * listener's threshold.
         *
         * <p/> Currently thresholds for this indication can be configurable by CARRIER_CONFIG
         * {@link CarrierConfigManager#KEY_VOICE_RTP_THRESHOLDS_PACKET_LOSS_RATE_INT}
         * {@link CarrierConfigManager#KEY_VOICE_RTP_THRESHOLDS_INACTIVITY_TIME_IN_MILLIS_INT}
         * {@link CarrierConfigManager#KEY_VOICE_RTP_THRESHOLDS_JITTER_INT}
         *
         * @param mediaQualityStatus The media quality status currently measured.
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onMediaQualityStatusChanged(@NonNull MediaQualityStatus mediaQualityStatus);
    }

    /**
     * Interface for emergency callback mode listener.
     *
     * @hide
     */
    public interface EmergencyCallbackModeListener {
        /**
         * Indicates that Callback Mode has been started.
         * <p>
         * This method will be called when an emergency sms/emergency call is sent
         * and the callback mode is supported by the carrier.
         * If an emergency SMS is transmitted during callback mode for SMS, this API will be called
         * once again with TelephonyManager#EMERGENCY_CALLBACK_MODE_SMS.
         *
         * @param type for callback mode entry
         *             See {@link TelephonyManager.EmergencyCallbackModeType}.
         * @see TelephonyManager#EMERGENCY_CALLBACK_MODE_CALL
         * @see TelephonyManager#EMERGENCY_CALLBACK_MODE_SMS
         */
        @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onCallBackModeStarted(@TelephonyManager.EmergencyCallbackModeType int type);

        /**
         * Indicates that Callback Mode has been stopped.
         * <p>
         * This method will be called when the callback mode timer expires or when
         * a normal call/SMS is sent
         *
         * @param type for callback mode entry
         * @see TelephonyManager#EMERGENCY_CALLBACK_MODE_CALL
         * @see TelephonyManager#EMERGENCY_CALLBACK_MODE_SMS
         *
         * @param reason for changing callback mode
         *
         * @see TelephonyManager#STOP_REASON_UNKNOWN
         * @see TelephonyManager#STOP_REASON_OUTGOING_NORMAL_CALL_INITIATED
         * @see TelephonyManager#STOP_REASON_NORMAL_SMS_SENT
         * @see TelephonyManager#STOP_REASON_OUTGOING_EMERGENCY_CALL_INITIATED
         * @see TelephonyManager#STOP_REASON_EMERGENCY_SMS_SENT
         * @see TelephonyManager#STOP_REASON_TIMER_EXPIRED
         * @see TelephonyManager#STOP_REASON_USER_ACTION
         */
        @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onCallBackModeStopped(@TelephonyManager.EmergencyCallbackModeType int type,
                @TelephonyManager.EmergencyCallbackModeStopReason int reason);
    }

    /**
     * Interface for carrier roaming non-terrestrial network listener.
     *
     * @hide
     */
    public interface CarrierRoamingNtnModeListener {
        /**
         * Callback invoked when carrier roaming non-terrestrial network mode changes.
         *
         * @param active {@code true} If the device is connected to carrier roaming
         *                           non-terrestrial network or was connected within the
         *                           {CarrierConfigManager
         *                           #KEY_SATELLITE_CONNECTION_HYSTERESIS_SEC_INT} duration,
         *                           {code false} otherwise.
         */
        void onCarrierRoamingNtnModeChanged(boolean active);

        /**
         * Callback invoked when carrier roaming non-terrestrial network eligibility changes.
         *
         * @param eligible {@code true} when the device is eligible for satellite
         * communication if all the following conditions are met:
         * <ul>
         * <li>Any subscription on the device supports P2P satellite messaging which is defined by
         * {@link CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL} </li>
         * <li>{@link CarrierConfigManager#KEY_CARRIER_ROAMING_NTN_CONNECT_TYPE_INT} set to
         * {@link CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_MANUAL} </li>
         * <li>The device is in {@link ServiceState#STATE_OUT_OF_SERVICE}, not connected to Wi-Fi,
         * and the hysteresis timer defined by {@link CarrierConfigManager
         * #KEY_CARRIER_SUPPORTED_SATELLITE_NOTIFICATION_HYSTERESIS_SEC_INT} is expired. </li>
         * </ul>
         */
        default void onCarrierRoamingNtnEligibleStateChanged(boolean eligible) {}
    }

    /**
     * The callback methods need to be called on the handler thread where
     * this object was created.  If the binder did that for us it'd be nice.
     * <p>
     * Using a static class and weak reference here to avoid memory leak caused by the
     * IPhoneState.Stub callback retaining references to the outside TelephonyCallback:
     * even caller has been destroyed and "un-registered" the TelephonyCallback, it is still not
     * eligible for GC given the references coming from:
     * Native Stack --> TelephonyCallback --> Context (Activity).
     * memory of caller's context will be collected after GC from service side get triggered
     */
    private static class IPhoneStateListenerStub extends IPhoneStateListener.Stub {
        private WeakReference<TelephonyCallback> mTelephonyCallbackWeakRef;
        private Executor mExecutor;

        IPhoneStateListenerStub(TelephonyCallback telephonyCallback, Executor executor) {
            mTelephonyCallbackWeakRef = new WeakReference<TelephonyCallback>(telephonyCallback);
            mExecutor = executor;
        }

        public void onServiceStateChanged(ServiceState serviceState) {
            ServiceStateListener listener = (ServiceStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onServiceStateChanged(serviceState)));
        }

        public void onSignalStrengthChanged(int asu) {
            // default implementation empty
        }

        public void onMessageWaitingIndicatorChanged(boolean mwi) {
            MessageWaitingIndicatorListener listener =
                    (MessageWaitingIndicatorListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onMessageWaitingIndicatorChanged(mwi)));
        }

        public void onCallForwardingIndicatorChanged(boolean cfi) {
            CallForwardingIndicatorListener listener =
                    (CallForwardingIndicatorListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallForwardingIndicatorChanged(cfi)));
        }

        public void onCellLocationChanged(CellIdentity cellIdentity) {
            // There is no system/public API to create an CellIdentity in system server,
            // so the server pass a null to indicate an empty initial location.
            CellLocation location =
                    cellIdentity == null ? CellLocation.getEmpty() : cellIdentity.asCellLocation();
            CellLocationListener listener = (CellLocationListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCellLocationChanged(location)));
        }

        public void onLegacyCallStateChanged(int state, String incomingNumber) {
            // Not used for TelephonyCallback; part of the AIDL which is used by both the legacy
            // PhoneStateListener and TelephonyCallback.
        }

        public void onCallStateChanged(int state) {
            CallStateListener listener = (CallStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallStateChanged(state)));
        }

        public void onDataConnectionStateChanged(int state, int networkType) {
            DataConnectionStateListener listener =
                    (DataConnectionStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            if (state == TelephonyManager.DATA_DISCONNECTING
                    && VMRuntime.getRuntime().getTargetSdkVersion() < Build.VERSION_CODES.R) {
                Binder.withCleanCallingIdentity(
                        () -> mExecutor.execute(() ->
                                listener.onDataConnectionStateChanged(
                                        TelephonyManager.DATA_CONNECTED, networkType)));
            } else {
                Binder.withCleanCallingIdentity(
                        () -> mExecutor.execute(() ->
                                listener.onDataConnectionStateChanged(state, networkType)));
            }
        }

        public void onDataActivity(int direction) {
            DataActivityListener listener = (DataActivityListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onDataActivity(direction)));
        }

        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            SignalStrengthsListener listener =
                    (SignalStrengthsListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onSignalStrengthsChanged(
                            signalStrength)));
        }

        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            CellInfoListener listener = (CellInfoListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCellInfoChanged(cellInfo)));
        }

        public void onPreciseCallStateChanged(PreciseCallState callState) {
            PreciseCallStateListener listener =
                    (PreciseCallStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onPreciseCallStateChanged(callState)));
        }

        public void onCallDisconnectCauseChanged(int disconnectCause, int preciseDisconnectCause) {
            CallDisconnectCauseListener listener =
                    (CallDisconnectCauseListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallDisconnectCauseChanged(
                            disconnectCause, preciseDisconnectCause)));
        }

        public void onPreciseDataConnectionStateChanged(
                PreciseDataConnectionState dataConnectionState) {
            PreciseDataConnectionStateListener listener =
                    (PreciseDataConnectionStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onPreciseDataConnectionStateChanged(
                                    dataConnectionState)));
        }

        public void onDataConnectionRealTimeInfoChanged(DataConnectionRealTimeInfo dcRtInfo) {
            // default implementation empty
        }

        public void onSrvccStateChanged(int state) {
            SrvccStateListener listener = (SrvccStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onSrvccStateChanged(state)));
        }

        public void onVoiceActivationStateChanged(int activationState) {
            VoiceActivationStateListener listener =
                    (VoiceActivationStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onVoiceActivationStateChanged(activationState)));
        }

        public void onDataActivationStateChanged(int activationState) {
            DataActivationStateListener listener =
                    (DataActivationStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onDataActivationStateChanged(activationState)));
        }

        public void onUserMobileDataStateChanged(boolean enabled) {
            UserMobileDataStateListener listener =
                    (UserMobileDataStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onUserMobileDataStateChanged(enabled)));
        }

        public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
            DisplayInfoListener listener = (DisplayInfoListener)mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onDisplayInfoChanged(telephonyDisplayInfo)));
        }

        public void onOemHookRawEvent(byte[] rawData) {
            // default implementation empty
        }

        public void onCarrierNetworkChange(boolean active) {
            CarrierNetworkListener listener =
                    (CarrierNetworkListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCarrierNetworkChange(active)));
        }

        public void onEmergencyNumberListChanged(Map emergencyNumberList) {
            EmergencyNumberListListener listener =
                    (EmergencyNumberListListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onEmergencyNumberListChanged(emergencyNumberList)));
        }

        public void onOutgoingEmergencyCall(@NonNull EmergencyNumber placedEmergencyNumber,
            int subscriptionId) {
            OutgoingEmergencyCallListener listener =
                    (OutgoingEmergencyCallListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onOutgoingEmergencyCall(placedEmergencyNumber,
                                    subscriptionId)));
        }

        public void onOutgoingEmergencySms(@NonNull EmergencyNumber sentEmergencyNumber,
            int subscriptionId) {
            OutgoingEmergencySmsListener listener =
                    (OutgoingEmergencySmsListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onOutgoingEmergencySms(sentEmergencyNumber,
                                    subscriptionId)));
        }

        public void onPhoneCapabilityChanged(PhoneCapability capability) {
            PhoneCapabilityListener listener =
                    (PhoneCapabilityListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onPhoneCapabilityChanged(capability)));
        }

        public void onRadioPowerStateChanged(@Annotation.RadioPowerState int state) {
            RadioPowerStateListener listener =
                    (RadioPowerStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onRadioPowerStateChanged(state)));
        }

        public void onCallStatesChanged(List<CallState> callStateList) {
            CallAttributesListener listener =
                    (CallAttributesListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallStatesChanged(callStateList)));
        }

        public void onActiveDataSubIdChanged(int subId) {
            ActiveDataSubscriptionIdListener listener =
                    (ActiveDataSubscriptionIdListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onActiveDataSubscriptionIdChanged(
                            subId)));
        }

        public void onImsCallDisconnectCauseChanged(ImsReasonInfo disconnectCause) {
            ImsCallDisconnectCauseListener listener =
                    (ImsCallDisconnectCauseListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onImsCallDisconnectCauseChanged(disconnectCause)));
        }

        public void onRegistrationFailed(@NonNull CellIdentity cellIdentity,
            @NonNull String chosenPlmn, int domain, int causeCode, int additionalCauseCode) {
            RegistrationFailedListener listener =
                    (RegistrationFailedListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onRegistrationFailed(
                            cellIdentity, chosenPlmn, domain, causeCode, additionalCauseCode)));
            // default implementation empty
        }

        public void onBarringInfoChanged(BarringInfo barringInfo) {
            BarringInfoListener listener = (BarringInfoListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onBarringInfoChanged(barringInfo)));
        }

        public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> configs) {
            PhysicalChannelConfigListener listener =
                    (PhysicalChannelConfigListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onPhysicalChannelConfigChanged(
                            configs)));
        }

        public void onDataEnabledChanged(boolean enabled,
            @TelephonyManager.DataEnabledReason int reason) {
            DataEnabledListener listener =
                    (DataEnabledListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onDataEnabledChanged(
                            enabled, reason)));
        }

        public void onAllowedNetworkTypesChanged(int reason, long allowedNetworkType) {
            AllowedNetworkTypesListener listener =
                    (AllowedNetworkTypesListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onAllowedNetworkTypesChanged(reason,
                                    allowedNetworkType)));
        }

        public void onSimultaneousCallingStateChanged(int[] subIds) {
            SimultaneousCellularCallingSupportListener listener =
                    (SimultaneousCellularCallingSupportListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onSimultaneousCellularCallingSubscriptionsChanged(
                                    Arrays.stream(subIds).boxed().collect(Collectors.toSet()))));
        }

        public void onLinkCapacityEstimateChanged(
                List<LinkCapacityEstimate> linkCapacityEstimateList) {
            LinkCapacityEstimateChangedListener listener =
                    (LinkCapacityEstimateChangedListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onLinkCapacityEstimateChanged(
                            linkCapacityEstimateList)));
        }

        public void onMediaQualityStatusChanged(
                MediaQualityStatus mediaQualityStatus) {
            MediaQualityStatusChangedListener listener =
                    (MediaQualityStatusChangedListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onMediaQualityStatusChanged(
                            mediaQualityStatus)));
        }

        public void onCallBackModeStarted(@TelephonyManager.EmergencyCallbackModeType int type) {
            EmergencyCallbackModeListener listener =
                    (EmergencyCallbackModeListener) mTelephonyCallbackWeakRef.get();
            Log.d(LOG_TAG, "onCallBackModeStarted:type=" + type + ", listener=" + listener);
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallBackModeStarted(type)));
        }

        public void onCallBackModeStopped(@TelephonyManager.EmergencyCallbackModeType int type,
                @TelephonyManager.EmergencyCallbackModeStopReason int reason) {
            EmergencyCallbackModeListener listener =
                    (EmergencyCallbackModeListener) mTelephonyCallbackWeakRef.get();
            Log.d(LOG_TAG, "onCallBackModeStopped:type=" + type
                    + ", reason=" + reason + ", listener=" + listener);
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallBackModeStopped(type, reason)));
        }

        public void onCarrierRoamingNtnModeChanged(boolean active) {
            if (!Flags.carrierEnabledSatelliteFlag()) return;

            CarrierRoamingNtnModeListener listener =
                    (CarrierRoamingNtnModeListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCarrierRoamingNtnModeChanged(active)));
        }

        public void onCarrierRoamingNtnEligibleStateChanged(boolean eligible) {
            if (!Flags.carrierRoamingNbIotNtn()) return;

            CarrierRoamingNtnModeListener listener =
                    (CarrierRoamingNtnModeListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> listener.onCarrierRoamingNtnEligibleStateChanged(eligible)));
        }
    }
}
