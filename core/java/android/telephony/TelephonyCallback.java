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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Binder;
import android.os.Build;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IPhoneStateListener;

import dalvik.system.VMRuntime;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

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
     *
     * @hide
     * @see CallStateListener#onCallStateChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_CALL_LOG)
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
     * @see AlwaysReportedSignalStrengthListener#onSignalStrengthsChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.LISTEN_ALWAYS_REPORTED_SIGNAL_STRENGTH)
    public static final int EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED = 10;

    /**
     * Event for changes to observed cell info.
     *
     * @hide
     * @see CellInfoListener#onCellInfoChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public static final int EVENT_CELL_INFO_CHANGED = 11;

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
     * the current subscription used to setup Cellular Internet data. For example,
     * it could be the current active opportunistic subscription in use, or the
     * subscription user selected as default data subscription in DSDS mode.
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
     * <p>Also requires the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission, regardless
     * of whether the calling app has carrier privileges.
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
     * <p>Also requires the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission, regardless
     * of whether the calling app has carrier privileges.
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
     * <p>Requires permission {@link android.Manifest.permission#READ_PHONE_STATE} or the calling
     * app has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @hide
     * @see AllowedNetworkTypesListener#onAllowedNetworkTypesChanged
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final int EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED = 35;

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
            EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED
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
         * receive all the information in {@link ServiceState}.
         *
         * @see ServiceState#STATE_EMERGENCY_ONLY
         * @see ServiceState#STATE_IN_SERVICE
         * @see ServiceState#STATE_OUT_OF_SERVICE
         * @see ServiceState#STATE_POWER_OFF
         */
        @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
        public void onServiceStateChanged(@NonNull ServiceState serviceState);
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
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        public void onMessageWaitingIndicatorChanged(boolean mwi);
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
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        public void onCallForwardingIndicatorChanged(boolean cfi);
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
        public void onCellLocationChanged(@NonNull CellLocation location);
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
         * @param state       call state
         * @param phoneNumber call phone number. If application does not have
         *                    {@link android.Manifest.permission#READ_CALL_LOG} permission or
         *                    carrier
         *                    privileges (see {@link TelephonyManager#hasCarrierPrivileges}), an
         *                    empty string will be
         *                    passed as an argument.
         */
        @RequiresPermission(android.Manifest.permission.READ_CALL_LOG)
        public void onCallStateChanged(@Annotation.CallState int state,
            @Nullable String phoneNumber);
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
         */
        @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
        public void onDataConnectionStateChanged(@TelephonyManager.DataState int state,
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
        @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
        public void onDataActivity(@Annotation.DataActivityType int direction);
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
        @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength);
    }

    /**
     * Interface for network signal strengths callback which always reported from modem.
     */
    public interface AlwaysReportedSignalStrengthListener {
        /**
         * Callback always invoked from modem when network signal strengths changes on the
         * registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         */
        @RequiresPermission(android.Manifest.permission.LISTEN_ALWAYS_REPORTED_SIGNAL_STRENGTH)
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength);
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
        @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        public void onCellInfoChanged(@NonNull List<CellInfo> cellInfo);
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
         * @param callState {@link PreciseCallState}
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        public void onPreciseCallStateChanged(@NonNull PreciseCallState callState);
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
         * @param disconnectCause        {@link DisconnectCause}.
         * @param preciseDisconnectCause {@link PreciseDisconnectCause}.
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        public void onCallDisconnectCauseChanged(@Annotation.DisconnectCauses int disconnectCause,
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
         * @param imsReasonInfo {@link ImsReasonInfo} contains details on why IMS call failed.
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        public void onImsCallDisconnectCauseChanged(@NonNull ImsReasonInfo imsReasonInfo);
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
         * <p>Requires permission {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE}
         * or the calling app has carrier privileges
         * (see {@link TelephonyManager#hasCarrierPrivileges}).
         *
         * @param dataConnectionState {@link PreciseDataConnectionState}
         */
        @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
        public void onPreciseDataConnectionStateChanged(
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
        public void onSrvccStateChanged(@Annotation.SrvccState int srvccState);
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
        public void onVoiceActivationStateChanged(@Annotation.SimActivationState int state);

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
        @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
        public void onDataActivationStateChanged(@Annotation.SimActivationState int state);
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
        @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
        public void onUserMobileDataStateChanged(boolean enabled);
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
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo telephonyDisplayInfo);
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
        public void onEmergencyNumberListChanged(
            @NonNull Map<Integer, List<EmergencyNumber>> emergencyNumberList);
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
        public void onOutgoingEmergencyCall(@NonNull EmergencyNumber placedEmergencyNumber,
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
        public void onOutgoingEmergencySms(@NonNull EmergencyNumber sentEmergencyNumber,
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
        @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
        public void onPhoneCapabilityChanged(@NonNull PhoneCapability capability);
    }

    /**
     * Interface for active data subscription ID listener.
     */
    public interface ActiveDataSubscriptionIdListener {
        /**
         * Callback invoked when active data subscription ID changes.
         * Note, this callback triggers regardless of registered subscription.
         *
         * @param subId current subscription used to setup Cellular Internet data.
         *              For example, it could be the current active opportunistic subscription
         *              in use, or the subscription user selected as default data subscription in
         *              DSDS mode.
         */
        @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
        public void onActiveDataSubscriptionIdChanged(int subId);
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
        public void onRadioPowerStateChanged(@Annotation.RadioPowerState int state);
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
        public void onCarrierNetworkChange(boolean active);
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
        public void onRegistrationFailed(@NonNull CellIdentity cellIdentity,
            @NonNull String chosenPlmn, @NetworkRegistrationInfo.Domain int domain, int causeCode,
            int additionalCauseCode);
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
         * registered subscription.
         * Note, the registered subscription is associated with {@link TelephonyManager} object
         * on which
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}
         * was called.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * given subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param allowedNetworkTypesList Map associating all allowed network type reasons
         * ({@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_USER},
         * {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_POWER},
         * {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_CARRIER}, and
         * {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G}) with reason's allowed
         * network type values.
         * For example:
         * map{{TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_USER, long type value},
         *     {TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_POWER, long type value},
         *     {TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_CARRIER, long type value},
         *     {TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G, long type value}}
         */
        @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
        void onAllowedNetworkTypesChanged(@NonNull Map<Integer, Long> allowedNetworkTypesList);
    }

    /**
     * Interface for call attributes listener.
     *
     * @hide
     */
    @SystemApi
    public interface CallAttributesListener {
        /**
         * Callback invoked when the call attributes changes on the registered subscription.
         * Note, the registration subscription ID comes from {@link TelephonyManager} object
         * which registers TelephonyCallback by
         * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
         * If this TelephonyManager object was created with
         * {@link TelephonyManager#createForSubscriptionId(int)}, then the callback applies to the
         * subscription ID. Otherwise, this callback applies to
         * {@link SubscriptionManager#getDefaultSubscriptionId()}.
         *
         * @param callAttributes the call attributes
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        void onCallAttributesChanged(@NonNull CallAttributes callAttributes);
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
         * @param barringInfo for all services on the current cell.
         * @see android.telephony.BarringInfo
         */
        @RequiresPermission(allOf = {
                Manifest.permission.READ_PRECISE_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION
        })
        public void onBarringInfoChanged(@NonNull BarringInfo barringInfo);
    }

    /**
     * Interface for current physical channel configuration listener.
     *
     * @hide
     */
    @SystemApi
    public interface PhysicalChannelConfigListener {
        /**
         * Callback invoked when the current physical channel configuration has changed
         *
         * @param configs List of the current {@link PhysicalChannelConfig}s
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        public void onPhysicalChannelConfigChanged(@NonNull List<PhysicalChannelConfig> configs);
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
         * @param enabled {@code true} if data is enabled, otherwise disabled.
         * @param reason  Reason for data enabled/disabled.
         *                See {@link TelephonyManager.DataEnabledReason}.
         */
        @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
        public void onDataEnabledChanged(boolean enabled,
            @TelephonyManager.DataEnabledReason int reason);
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

        public void onCallStateChanged(int state, String incomingNumber) {
            CallStateListener listener = (CallStateListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallStateChanged(state,
                            incomingNumber)));
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

        public void onCallAttributesChanged(CallAttributes callAttributes) {
            CallAttributesListener listener =
                    (CallAttributesListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> listener.onCallAttributesChanged(
                            callAttributes)));
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

        public void onAllowedNetworkTypesChanged(Map allowedNetworkTypesList) {
            AllowedNetworkTypesListener listener =
                    (AllowedNetworkTypesListener) mTelephonyCallbackWeakRef.get();
            if (listener == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(
                            () -> listener.onAllowedNetworkTypesChanged(allowedNetworkTypesList)));
        }
    }
}
