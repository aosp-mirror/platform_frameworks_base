/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telephony.satellite;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;

import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.IVoidConsumer;
import com.android.internal.telephony.flags.Flags;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Manages satellite states such as monitoring enabled state and operations such as provisioning,
 * pointing, messaging, location sharing, etc.
 *
 * <p>To get the object, call {@link Context#getSystemService(String)} with
 * {@link Context#SATELLITE_SERVICE}.
 *
 * <p>SatelliteManager is intended for use on devices with feature
 * {@link PackageManager#FEATURE_TELEPHONY_SATELLITE}. On devices without the feature, the behavior
 * is not reliable.
 */
@SystemService(Context.SATELLITE_SERVICE)
@FlaggedApi(Flags.FLAG_SATELLITE_STATE_CHANGE_LISTENER)
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_SATELLITE)
public final class SatelliteManager {
    private static final String TAG = "SatelliteManager";

    private static final ConcurrentHashMap<SatelliteDatagramCallback, ISatelliteDatagramCallback>
            sSatelliteDatagramCallbackMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteProvisionStateCallback,
            ISatelliteProvisionStateCallback> sSatelliteProvisionStateCallbackMap =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteModemStateCallback,
            ISatelliteModemStateCallback>
            sSatelliteModemStateCallbackMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteTransmissionUpdateCallback,
            ISatelliteTransmissionUpdateCallback> sSatelliteTransmissionUpdateCallbackMap =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<NtnSignalStrengthCallback, INtnSignalStrengthCallback>
            sNtnSignalStrengthCallbackMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteCapabilitiesCallback,
            ISatelliteCapabilitiesCallback>
            sSatelliteCapabilitiesCallbackMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteSupportedStateCallback,
            ISatelliteSupportedStateCallback> sSatelliteSupportedStateCallbackMap =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteCommunicationAllowedStateCallback,
            ISatelliteCommunicationAllowedStateCallback>
            sSatelliteCommunicationAllowedStateCallbackMap =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteDisallowedReasonsCallback,
            ISatelliteDisallowedReasonsCallback>
            sSatelliteDisallowedReasonsCallbackMap =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SelectedNbIotSatelliteSubscriptionCallback,
            ISelectedNbIotSatelliteSubscriptionCallback>
            sSelectedNbIotSatelliteSubscriptionCallbackMap =
            new ConcurrentHashMap<>();

    private final int mSubId;

    /**
     * Context this SatelliteManager is for.
     */
    @Nullable private final Context mContext;

    private TelephonyRegistryManager mTelephonyRegistryMgr;

    /**
     * Create an instance of the SatelliteManager.
     *
     * @param context The context the SatelliteManager belongs to.
     * @hide
     */
    public SatelliteManager(@Nullable Context context) {
        this(context, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
    }

    /**
     * Create an instance of the SatelliteManager associated with a particular subscription.
     *
     * @param context The context the SatelliteManager belongs to.
     * @param subId The subscription ID associated with the SatelliteManager.
     */
    private SatelliteManager(@Nullable Context context, int subId) {
        mContext = context;
        mSubId = subId;
    }

    /**
     * Exception from the satellite service containing the {@link SatelliteResult} error code.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static class SatelliteException extends Exception {
        @SatelliteResult private final int mErrorCode;

        /**
         * Create a SatelliteException with a given error code.
         *
         * @param errorCode The {@link SatelliteResult}.
         */
        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        public SatelliteException(@SatelliteResult int errorCode) {
            mErrorCode = errorCode;
        }

        /**
         * Get the error code returned from the satellite service.
         *
         * @return The {@link SatelliteResult}.
         */
        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        @SatelliteResult public int getErrorCode() {
            return mErrorCode;
        }
    }

    /**
     * Bundle key to get the response from
     * {@link #requestIsEnabled(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_ENABLED = "satellite_enabled";

    /**
     * Bundle key to get the response from
     * {@link #requestIsDemoModeEnabled(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_DEMO_MODE_ENABLED = "demo_mode_enabled";

    /**
     * Bundle key to get the response from
     * {@link #requestIsEmergencyModeEnabled(Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_EMERGENCY_MODE_ENABLED = "emergency_mode_enabled";

    /**
     * Bundle key to get the response from
     * {@link #requestIsSupported(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_SUPPORTED = "satellite_supported";

    /**
     * Bundle key to get the response from
     * {@link #requestCapabilities(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_CAPABILITIES = "satellite_capabilities";

    /**
     * Bundle key to get the response from
     * {@link #requestSessionStats(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SESSION_STATS = "session_stats";

    /**
     * Bundle key to get the response from
     * {@link #requestSessionStats(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SESSION_STATS_V2 = "session_stats_v2";

    /**
     * Bundle key to get the response from
     * {@link #requestIsProvisioned(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_PROVISIONED = "satellite_provisioned";

    /**
     * Bundle key to get the response from
     * {@link #requestIsCommunicationAllowedForCurrentLocation(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_COMMUNICATION_ALLOWED =
            "satellite_communication_allowed";

    /**
     * Bundle key to get the response from
     * {@link #requestTimeForNextSatelliteVisibility(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_NEXT_VISIBILITY = "satellite_next_visibility";

    /**
     * Bundle key to get the response from
     * {@link #requestNtnSignalStrength(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_NTN_SIGNAL_STRENGTH = "ntn_signal_strength";

    /**
     * Bundle key to get the response from
     * {@link #requestSatelliteSubscriberProvisionStatus(Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_REQUEST_PROVISION_SUBSCRIBER_ID_TOKEN =
            "request_provision_subscriber_id";

    /**
     * Bundle key to get the response from
     * {@link #provisionSatellite(List, Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_PROVISION_SATELLITE_TOKENS = "provision_satellite";

    /**
     * Bundle key to get the response from
     * {@link #deprovisionSatellite(List, Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_DEPROVISION_SATELLITE_TOKENS = "deprovision_satellite";

    /**
     * Bundle key to get the response from
     * {@link #requestSatelliteAccessConfigurationForCurrentLocation(Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_SATELLITE_ACCESS_CONFIGURATION =
            "satellite_access_configuration";

    /**
     * Bundle key to get the response from
     * {@link #requestSatelliteDisplayName(Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_SATELLITE_DISPLAY_NAME = "satellite_display_name";

    /**
     * Bundle key to get the response from
     * {@link #requestSelectedNbIotSatelliteSubscriptionId(Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID =
            "selected_nb_iot_satellite_subscription_id";

    /**
     * The request was successfully processed.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SUCCESS = 0;

    /**
     * A generic error which should be used only when other specific errors cannot be used.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_ERROR = 1;

    /**
     * Error received from the satellite server.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVER_ERROR = 2;

    /**
     * Error received from the vendor service. This generic error code should be used
     * only when the error cannot be mapped to other specific service error codes.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVICE_ERROR = 3;

    /**
     * Error received from satellite modem. This generic error code should be used only when
     * the error cannot be mapped to other specific modem error codes.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_MODEM_ERROR = 4;

    /**
     * Error received from the satellite network. This generic error code should be used only when
     * the error cannot be mapped to other specific network error codes.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NETWORK_ERROR = 5;

    /**
     * Telephony is not in a valid state to receive requests from clients.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_INVALID_TELEPHONY_STATE = 6;

    /**
     * Satellite modem is not in a valid state to receive requests from clients.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_INVALID_MODEM_STATE = 7;

    /**
     * Either vendor service, or modem, or Telephony framework has received a request with
     * invalid arguments from its clients.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_INVALID_ARGUMENTS = 8;

    /**
     * Telephony framework failed to send a request or receive a response from the vendor service
     * or satellite modem due to internal error.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_FAILED = 9;

    /**
     * Radio did not start or is resetting.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_RADIO_NOT_AVAILABLE = 10;

    /**
     * The request is not supported by either the satellite modem or the network.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_NOT_SUPPORTED = 11;

    /**
     * Satellite modem or network has no resources available to handle requests from clients.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NO_RESOURCES = 12;

    /**
     * Satellite service is not provisioned yet.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVICE_NOT_PROVISIONED = 13;

    /**
     * Satellite service provision is already in progress.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS = 14;

    /**
     * The ongoing request was aborted by either the satellite modem or the network.
     * This error is also returned when framework decides to abort current send request as one
     * of the previous send request failed.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_ABORTED = 15;

    /**
     * The device/subscriber is barred from accessing the satellite service.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_ACCESS_BARRED = 16;

    /**
     * Satellite modem timeout to receive ACK or response from the satellite network after
     * sending a request to the network.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NETWORK_TIMEOUT = 17;

    /**
     * Satellite network is not reachable from the modem.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NOT_REACHABLE = 18;

    /**
     * The device/subscriber is not authorized to register with the satellite service provider.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NOT_AUTHORIZED = 19;

    /**
     * The device does not support satellite.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NOT_SUPPORTED = 20;

    /**
     * The current request is already in-progress.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_IN_PROGRESS = 21;

    /**
     * Satellite modem is currently busy due to which current request cannot be processed.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_MODEM_BUSY = 22;

    /**
     * Telephony process is not currently available or satellite is not supported.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_ILLEGAL_STATE = 23;

    /**
     * Telephony framework timeout to receive ACK or response from the satellite modem after
     * sending a request to the modem.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_MODEM_TIMEOUT = 24;

    /**
     * Telephony framework needs to access the current location of the device to perform the
     * request. However, location in the settings is disabled by users.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_RESULT_LOCATION_DISABLED = 25;

    /**
     * Telephony framework needs to access the current location of the device to perform the
     * request. However, Telephony fails to fetch the current location from location service.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_RESULT_LOCATION_NOT_AVAILABLE = 26;

    /**
     * Emergency call is in progress.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_RESULT_EMERGENCY_CALL_IN_PROGRESS = 27;

    /**
     * Disabling satellite is in progress.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_RESULT_DISABLE_IN_PROGRESS = 28;

    /**
     * Enabling satellite is in progress.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_RESULT_ENABLE_IN_PROGRESS = 29;

    /**
     * There is no valid satellite subscription selected.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_RESULT_NO_VALID_SATELLITE_SUBSCRIPTION = 30;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_RESULT_"}, value = {
            SATELLITE_RESULT_SUCCESS,
            SATELLITE_RESULT_ERROR,
            SATELLITE_RESULT_SERVER_ERROR,
            SATELLITE_RESULT_SERVICE_ERROR,
            SATELLITE_RESULT_MODEM_ERROR,
            SATELLITE_RESULT_NETWORK_ERROR,
            SATELLITE_RESULT_INVALID_TELEPHONY_STATE,
            SATELLITE_RESULT_INVALID_MODEM_STATE,
            SATELLITE_RESULT_INVALID_ARGUMENTS,
            SATELLITE_RESULT_REQUEST_FAILED,
            SATELLITE_RESULT_RADIO_NOT_AVAILABLE,
            SATELLITE_RESULT_REQUEST_NOT_SUPPORTED,
            SATELLITE_RESULT_NO_RESOURCES,
            SATELLITE_RESULT_SERVICE_NOT_PROVISIONED,
            SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS,
            SATELLITE_RESULT_REQUEST_ABORTED,
            SATELLITE_RESULT_ACCESS_BARRED,
            SATELLITE_RESULT_NETWORK_TIMEOUT,
            SATELLITE_RESULT_NOT_REACHABLE,
            SATELLITE_RESULT_NOT_AUTHORIZED,
            SATELLITE_RESULT_NOT_SUPPORTED,
            SATELLITE_RESULT_REQUEST_IN_PROGRESS,
            SATELLITE_RESULT_MODEM_BUSY,
            SATELLITE_RESULT_ILLEGAL_STATE,
            SATELLITE_RESULT_MODEM_TIMEOUT,
            SATELLITE_RESULT_LOCATION_DISABLED,
            SATELLITE_RESULT_LOCATION_NOT_AVAILABLE,
            SATELLITE_RESULT_EMERGENCY_CALL_IN_PROGRESS,
            SATELLITE_RESULT_DISABLE_IN_PROGRESS,
            SATELLITE_RESULT_ENABLE_IN_PROGRESS,
            SATELLITE_RESULT_NO_VALID_SATELLITE_SUBSCRIPTION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteResult {}

    /**
     * Unknown Non-Terrestrial radio technology. This generic radio technology should be used
     * only when the radio technology cannot be mapped to other specific radio technologies.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_UNKNOWN = 0;

    /**
     * 3GPP NB-IoT (Narrowband Internet of Things) over Non-Terrestrial-Networks technology.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_NB_IOT_NTN = 1;

    /**
     * 3GPP 5G NR over Non-Terrestrial-Networks technology.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_NR_NTN = 2;

    /**
     * 3GPP eMTC (enhanced Machine-Type Communication) over Non-Terrestrial-Networks technology.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_EMTC_NTN = 3;

    /**
     * Proprietary technology.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_PROPRIETARY = 4;

    /** @hide */
    @IntDef(prefix = "NT_RADIO_TECHNOLOGY_", value = {
            NT_RADIO_TECHNOLOGY_UNKNOWN,
            NT_RADIO_TECHNOLOGY_NB_IOT_NTN,
            NT_RADIO_TECHNOLOGY_NR_NTN,
            NT_RADIO_TECHNOLOGY_EMTC_NTN,
            NT_RADIO_TECHNOLOGY_PROPRIETARY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NTRadioTechnology {}

    /**
     * Suggested device hold position is unknown.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_UNKNOWN = 0;

    /**
     * User is suggested to hold the device in portrait mode.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_PORTRAIT = 1;

    /**
     * User is suggested to hold the device in landscape mode with left hand.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_LANDSCAPE_LEFT = 2;

    /**
     * User is suggested to hold the device in landscape mode with right hand.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_LANDSCAPE_RIGHT = 3;

    /** @hide */
    @IntDef(prefix = {"DEVICE_HOLD_POSITION_"}, value = {
            DEVICE_HOLD_POSITION_UNKNOWN,
            DEVICE_HOLD_POSITION_PORTRAIT,
            DEVICE_HOLD_POSITION_LANDSCAPE_LEFT,
            DEVICE_HOLD_POSITION_LANDSCAPE_RIGHT
       })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceHoldPosition {}

    /**
     *  Display mode is unknown.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_UNKNOWN = 0;

    /**
     * Display mode of the device used for satellite communication for non-foldable phones.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_FIXED = 1;

    /**
     * Display mode of the device used for satellite communication for foldabale phones when the
     * device is opened.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_OPENED = 2;

    /**
     * Display mode of the device used for satellite communication for foldabable phones when the
     * device is closed.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_CLOSED = 3;

    /** @hide */
    @IntDef(prefix = {"ANTENNA_POSITION_"}, value = {
            DISPLAY_MODE_UNKNOWN,
            DISPLAY_MODE_FIXED,
            DISPLAY_MODE_OPENED,
            DISPLAY_MODE_CLOSED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisplayMode {}

    /**
     * The emergency call is handed over to oem-enabled satellite SOS messaging. SOS messages are
     * sent to SOS providers, which will then forward the messages to emergency providers.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS = 1;

    /**
     * The emergency call is handed over to carrier-enabled satellite T911 messaging. T911 messages
     * are sent directly to local emergency providers.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public static final int EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911 = 2;

    /**
     * This intent will be broadcasted if there are any change to list of subscriber information.
     * This intent will be sent only to the app with component defined in
     * config_satellite_carrier_roaming_esos_provisioned_class and package defined in
     * config_satellite_gateway_service_package
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final String ACTION_SATELLITE_SUBSCRIBER_ID_LIST_CHANGED =
            "android.telephony.satellite.action.SATELLITE_SUBSCRIBER_ID_LIST_CHANGED";


    /**
     * This intent will be broadcasted to start a non-emergency session.
     * This intent will be sent only to the app with component defined in
     * config_satellite_carrier_roaming_non_emergency_session_class and package defined in
     * config_satellite_gateway_service_package
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final String ACTION_SATELLITE_START_NON_EMERGENCY_SESSION =
            "android.telephony.satellite.action.SATELLITE_START_NON_EMERGENCY_SESSION";
    /**
     * Meta-data represents whether the application supports P2P SMS over carrier roaming satellite
     * which needs manual trigger to connect to satellite. The messaging applications that supports
     * P2P SMS over carrier roaming satellites should add the following in their AndroidManifest.
     * {@code
     * <application
     *   <meta-data
     *     android:name="android.telephony.METADATA_SATELLITE_MANUAL_CONNECT_P2P_SUPPORT"
     *     android:value="true"/>
     * </application>
     * }
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final String METADATA_SATELLITE_MANUAL_CONNECT_P2P_SUPPORT =
            "android.telephony.METADATA_SATELLITE_MANUAL_CONNECT_P2P_SUPPORT";

    /**
     * Registers a {@link SatelliteStateChangeListener} to receive callbacks when the satellite
     * state may have changed.
     *
     * <p>The callback method is immediately triggered with latest state on invoking this method if
     * the state change has been notified before.
     *
     * @param executor The {@link Executor} where the {@code listener} will be invoked
     * @param listener The listener to monitor the satellite state change
     *
     * @see SatelliteStateChangeListener
     * @see TelephonyManager#hasCarrierPrivileges()
     */
    @FlaggedApi(Flags.FLAG_SATELLITE_STATE_CHANGE_LISTENER)
    @RequiresPermission(anyOf = {android.Manifest.permission.READ_BASIC_PHONE_STATE,
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE,
            "carrier privileges"})
    public void registerStateChangeListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteStateChangeListener listener) {
        if (mContext == null) {
            throw new IllegalStateException("Telephony service is null");
        }

        mTelephonyRegistryMgr = mContext.getSystemService(TelephonyRegistryManager.class);
        if (mTelephonyRegistryMgr == null) {
            throw new IllegalStateException("Telephony registry service is null");
        }
        mTelephonyRegistryMgr.addSatelliteStateChangeListener(executor, listener);
    }

    /**
     * Unregisters the {@link SatelliteStateChangeListener} previously registered with
     * {@link #registerStateChangeListener(Executor, SatelliteStateChangeListener)}.
     *
     * <p>It will be a no-op if the {@code listener} is not currently registered.
     *
     * @param listener The listener to unregister
     *
     * @see SatelliteStateChangeListener
     * @see TelephonyManager#hasCarrierPrivileges()
     */
    @FlaggedApi(Flags.FLAG_SATELLITE_STATE_CHANGE_LISTENER)
    @RequiresPermission(anyOf = {android.Manifest.permission.READ_BASIC_PHONE_STATE,
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE,
            "carrier privileges"})
    public void unregisterStateChangeListener(@NonNull SatelliteStateChangeListener listener) {
        if (mContext == null) {
            throw new IllegalStateException("Telephony service is null");
        }

        mTelephonyRegistryMgr = mContext.getSystemService(TelephonyRegistryManager.class);
        if (mTelephonyRegistryMgr == null) {
            throw new IllegalStateException("Telephony registry service is null");
        }
        mTelephonyRegistryMgr.removeSatelliteStateChangeListener(listener);
    }

    /**
     * Request to enable or disable the satellite modem and demo mode.
     * If satellite modem and cellular modem cannot work concurrently,
     * then this will disable the cellular modem if satellite modem is enabled,
     * and will re-enable the cellular modem if satellite modem is disabled.
     *
     * Demo mode is created to simulate the experience of sending and receiving messages over
     * satellite. If user enters demo mode, a request should be sent to framework to enable
     * satellite with enableDemoMode set to {code true}. Once satellite is enabled and device is
     * aligned with the satellite, user can send a message and also receive a reply in demo mode.
     * If enableSatellite is {@code false}, enableDemoMode has no impact on the behavior.
     *
     * @param attributes The attributes of the enable request.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestEnabled(@NonNull EnableRequestAttributes attributes,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(attributes);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.requestSatelliteEnabled(attributes.isEnabled(),
                        attributes.isDemoMode(), attributes.isEmergencyMode(), errorCallback);
            } else {
                Rlog.e(TAG, "requestEnabled() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "requestEnabled() exception: ", ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the satellite modem
     *                 is enabled and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsEnabled(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_ENABLED)) {
                                boolean isSatelliteEnabled =
                                        resultData.getBoolean(KEY_SATELLITE_ENABLED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteEnabled)));
                            } else {
                                loge("KEY_SATELLITE_ENABLED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsSatelliteEnabled(receiver);
            } else {
                loge("requestIsEnabled() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestIsEnabled() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get whether the satellite service demo mode is enabled.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if demo mode is enabled
     *                 and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsDemoModeEnabled(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_DEMO_MODE_ENABLED)) {
                                boolean isDemoModeEnabled =
                                        resultData.getBoolean(KEY_DEMO_MODE_ENABLED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isDemoModeEnabled)));
                            } else {
                                loge("KEY_DEMO_MODE_ENABLED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsDemoModeEnabled(receiver);
            } else {
                loge("requestIsDemoModeEnabled() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestIsDemoModeEnabled() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get whether the satellite service is enabled for emergency mode.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if satellite is enabled
     *                 for emergency mode and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsEmergencyModeEnabled(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_EMERGENCY_MODE_ENABLED)) {
                                boolean isEmergencyModeEnabled =
                                        resultData.getBoolean(KEY_EMERGENCY_MODE_ENABLED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isEmergencyModeEnabled)));
                            } else {
                                loge("KEY_EMERGENCY_MODE_ENABLED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsEmergencyModeEnabled(receiver);
            } else {
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestIsEmergencyModeEnabled() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * <p>
     * Note: This API only checks whether the device supports the satellite feature. The result will
     * not be affected by whether the device is provisioned.
     * </p>
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the satellite
     *                 service is supported on the device and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsSupported(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_SUPPORTED)) {
                                boolean isSatelliteSupported =
                                        resultData.getBoolean(KEY_SATELLITE_SUPPORTED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteSupported)));
                            } else {
                                loge("KEY_SATELLITE_SUPPORTED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsSatelliteSupported(receiver);
            } else {
                loge("requestIsSupported() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestIsSupported() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get the {@link SatelliteCapabilities} of the satellite service.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return the {@link SatelliteCapabilities} of the satellite service.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestCapabilities(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<SatelliteCapabilities, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_CAPABILITIES)) {
                                SatelliteCapabilities capabilities =
                                        resultData.getParcelable(KEY_SATELLITE_CAPABILITIES,
                                                SatelliteCapabilities.class);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(capabilities)));
                            } else {
                                loge("KEY_SATELLITE_CAPABILITIES does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestSatelliteCapabilities(receiver);
            } else {
                loge("requestCapabilities() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestCapabilities() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * The default state indicating that datagram transfer is idle.
     * This should be sent if there are no message transfer activity happening.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE = 0;

    /**
     * A transition state indicating that a datagram is being sent.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING = 1;

    /**
     * An end state indicating that datagram sending completed successfully.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS = 2;

    /**
     * An end state indicating that datagram sending completed with a failure.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * must be sent before reporting any additional datagram transfer state changes. All pending
     * messages will be reported as failed, to the corresponding applications.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED = 3;

    /**
     * A transition state indicating that a datagram is being received.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING = 4;

    /**
     * An end state indicating that datagram receiving completed successfully.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS = 5;

    /**
     * An end state indicating that datagram receive operation found that there are no
     * messages to be retrieved from the satellite.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_NONE = 6;

    /**
     * An end state indicating that datagram receive completed with a failure.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED = 7;

    /**
     * A transition state indicating that Telephony is waiting for satellite modem to connect to a
     * satellite network before sending a datagram or polling for datagrams. If the satellite modem
     * successfully connects to a satellite network, either
     * {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING} or
     * {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING} will be sent. Otherwise,
     * either {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED} or
     * {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED} will be sent.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_WAITING_TO_CONNECT = 8;

    /**
     * The datagram transfer state is unknown. This generic datagram transfer state should be used
     * only when the datagram transfer state cannot be mapped to other specific datagram transfer
     * states.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_UNKNOWN = -1;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_DATAGRAM_TRANSFER_STATE_"}, value = {
            SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
            SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
            SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
            SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_NONE,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED,
            SATELLITE_DATAGRAM_TRANSFER_STATE_WAITING_TO_CONNECT,
            SATELLITE_DATAGRAM_TRANSFER_STATE_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteDatagramTransferState {}
    // TODO: Split into two enums for sending and receiving states

    /**
     * Satellite modem is in idle state.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_IDLE = 0;

    /**
     * Satellite modem is listening for incoming datagrams.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_LISTENING = 1;

    /**
     * Satellite modem is sending and/or receiving datagrams.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING = 2;

    /**
     * Satellite modem is retrying to send and/or receive datagrams.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_DATAGRAM_RETRYING = 3;

    /**
     * Satellite modem is powered off.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_OFF = 4;

    /**
     * Satellite modem is unavailable.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_UNAVAILABLE = 5;

    /**
     * The satellite modem is powered on but the device is not registered to a satellite cell.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_NOT_CONNECTED = 6;

    /**
     * The satellite modem is powered on and the device is registered to a satellite cell.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_CONNECTED = 7;

    /**
     * The satellite modem is being powered on.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_MODEM_STATE_ENABLING_SATELLITE = 8;

    /**
     * The satellite modem is being powered off.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_MODEM_STATE_DISABLING_SATELLITE = 9;

    /**
     * Satellite modem state is unknown. This generic modem state should be used only when the
     * modem state cannot be mapped to other specific modem states.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_UNKNOWN = -1;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_MODEM_STATE_"}, value = {
            SATELLITE_MODEM_STATE_IDLE,
            SATELLITE_MODEM_STATE_LISTENING,
            SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
            SATELLITE_MODEM_STATE_DATAGRAM_RETRYING,
            SATELLITE_MODEM_STATE_OFF,
            SATELLITE_MODEM_STATE_UNAVAILABLE,
            SATELLITE_MODEM_STATE_NOT_CONNECTED,
            SATELLITE_MODEM_STATE_CONNECTED,
            SATELLITE_MODEM_STATE_ENABLING_SATELLITE,
            SATELLITE_MODEM_STATE_DISABLING_SATELLITE,
            SATELLITE_MODEM_STATE_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteModemState {}

    /**
     * Datagram type is unknown. This generic datagram type should be used only when the
     * datagram type cannot be mapped to other specific datagram types.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DATAGRAM_TYPE_UNKNOWN = 0;

    /**
     * Datagram type indicating that the datagram to be sent or received is of type SOS message.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DATAGRAM_TYPE_SOS_MESSAGE = 1;

    /**
     * Datagram type indicating that the datagram to be sent or received is of type
     * location sharing.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DATAGRAM_TYPE_LOCATION_SHARING = 2;

    /**
     * This type of datagram is used to keep the device in satellite connected state or check if
     * there is any incoming message.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int DATAGRAM_TYPE_KEEP_ALIVE = 3;

    /**
     * Datagram type indicating that the datagram to be sent or received is of type SOS message and
     * is the last message to emergency service provider indicating still needs help.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int DATAGRAM_TYPE_LAST_SOS_MESSAGE_STILL_NEED_HELP = 4;

    /**
     * Datagram type indicating that the datagram to be sent or received is of type SOS message and
     * is the last message to emergency service provider indicating no more help is needed.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int DATAGRAM_TYPE_LAST_SOS_MESSAGE_NO_HELP_NEEDED = 5;

    /**
     * Datagram type indicating that the message to be sent or received is of type SMS.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int DATAGRAM_TYPE_SMS = 6;

    /**
     * Datagram type indicating that the message to be sent is an SMS checking
     * for pending incoming SMS.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int DATAGRAM_TYPE_CHECK_PENDING_INCOMING_SMS = 7;

    /** @hide */
    @IntDef(prefix = "DATAGRAM_TYPE_", value = {
            DATAGRAM_TYPE_UNKNOWN,
            DATAGRAM_TYPE_SOS_MESSAGE,
            DATAGRAM_TYPE_LOCATION_SHARING,
            DATAGRAM_TYPE_KEEP_ALIVE,
            DATAGRAM_TYPE_LAST_SOS_MESSAGE_STILL_NEED_HELP,
            DATAGRAM_TYPE_LAST_SOS_MESSAGE_NO_HELP_NEEDED,
            DATAGRAM_TYPE_SMS,
            DATAGRAM_TYPE_CHECK_PENDING_INCOMING_SMS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DatagramType {}

    /**
     * Satellite communication restricted by user.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public static final int SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER = 0;

    /**
     * Satellite communication restricted by geolocation. This can be
     * triggered based upon geofence input provided by carrier to enable or disable satellite.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_COMMUNICATION_RESTRICTION_REASON_GEOLOCATION = 1;

    /**
     * Satellite communication restricted by entitlement server. This can be triggered based on
     * the EntitlementStatus value received from the entitlement server to enable or disable
     * satellite.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT = 2;

    /** @hide */
    @IntDef(prefix = "SATELLITE_COMMUNICATION_RESTRICTION_REASON_", value = {
            SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER,
            SATELLITE_COMMUNICATION_RESTRICTION_REASON_GEOLOCATION,
            SATELLITE_COMMUNICATION_RESTRICTION_REASON_ENTITLEMENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteCommunicationRestrictionReason {}

    /**
     * Satellite is disallowed because it is not supported.
     * @hide
     */
    public static final int SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED = 0;

    /**
     * Satellite is disallowed because it has not been provisioned.
     * @hide
     */
    public static final int SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED = 1;

    /**
     * Satellite is disallowed because it is currently outside an allowed region.
     * @hide
     */
    public static final int SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION = 2;

    /**
     * Satellite is disallowed because an unsupported default message application is being used.
     * @hide
     */
    public static final int SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP = 3;

    /**
     * Satellite is disallowed because location settings have been disabled.
     * @hide
     */
    public static final int SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED = 4;

    /** @hide */
    @IntDef(prefix = "SATELLITE_DISALLOWED_REASON_", value = {
            SATELLITE_DISALLOWED_REASON_NOT_SUPPORTED,
            SATELLITE_DISALLOWED_REASON_NOT_PROVISIONED,
            SATELLITE_DISALLOWED_REASON_NOT_IN_ALLOWED_REGION,
            SATELLITE_DISALLOWED_REASON_UNSUPPORTED_DEFAULT_MSG_APP,
            SATELLITE_DISALLOWED_REASON_LOCATION_DISABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteDisallowedReason {}

    /**
     * Start receiving satellite transmission updates.
     * This can be called by the pointing UI when the user starts pointing to the satellite.
     * Modem should continue to report the pointing input as the device or satellite moves.
     * Satellite transmission updates are started only on {@link #SATELLITE_RESULT_SUCCESS}.
     * All other results indicate that this operation failed.
     * Once satellite transmission updates begin, position and datagram transfer state updates
     * will be sent through {@link SatelliteTransmissionUpdateCallback}.
     *
     * @param executor The executor on which the callback and error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     * @param callback The callback to notify of satellite transmission updates.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SuppressWarnings("SamShouldBeLast")
    public void startTransmissionUpdates(@NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener,
            @NonNull SatelliteTransmissionUpdateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                ISatelliteTransmissionUpdateCallback internalCallback =
                        new ISatelliteTransmissionUpdateCallback.Stub() {

                            @Override
                            public void onSatellitePositionChanged(PointingInfo pointingInfo) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatellitePositionChanged(pointingInfo)));
                            }

                            @Override
                            public void onSendDatagramStateChanged(int datagramType, int state,
                                    int sendPendingCount, int errorCode) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSendDatagramStateChanged(datagramType,
                                                state, sendPendingCount, errorCode)));

                                // For backward compatibility
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSendDatagramStateChanged(
                                                state, sendPendingCount, errorCode)));
                            }

                            @Override
                            public void onReceiveDatagramStateChanged(int state,
                                    int receivePendingCount, int errorCode) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onReceiveDatagramStateChanged(
                                                state, receivePendingCount, errorCode)));
                            }

                            @Override
                            public void onSendDatagramRequested(int datagramType) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSendDatagramRequested(datagramType)));
                            }
                        };
                sSatelliteTransmissionUpdateCallbackMap.put(callback, internalCallback);
                telephony.startSatelliteTransmissionUpdates(errorCallback, internalCallback);
            } else {
                loge("startTransmissionUpdates() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("startTransmissionUpdates() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Stop receiving satellite transmission updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     * Satellite transmission updates are stopped and the callback is unregistered only on
     * {@link #SATELLITE_RESULT_SUCCESS}. All other results that this operation failed.
     *
     * @param callback The callback that was passed to {@link
     * #startTransmissionUpdates(Executor, Consumer, SatelliteTransmissionUpdateCallback)}.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void stopTransmissionUpdates(@NonNull SatelliteTransmissionUpdateCallback callback,
            @SuppressWarnings("ListenerLast") @NonNull @CallbackExecutor Executor executor,
            @SuppressWarnings("ListenerLast") @SatelliteResult @NonNull
            Consumer<Integer> resultListener) {
        Objects.requireNonNull(callback);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        ISatelliteTransmissionUpdateCallback internalCallback =
                sSatelliteTransmissionUpdateCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            executor.execute(() -> Binder.withCleanCallingIdentity(
                                    () -> resultListener.accept(result)));
                        }
                    };
                    telephony.stopSatelliteTransmissionUpdates(errorCallback, internalCallback);
                    // TODO: Notify SmsHandler that pointing UI stopped
                } else {
                    loge("stopSatelliteTransmissionUpdates: No internal callback.");
                    executor.execute(() -> Binder.withCleanCallingIdentity(
                            () -> resultListener.accept(SATELLITE_RESULT_INVALID_ARGUMENTS)));
                }
            } else {
                loge("stopTransmissionUpdates() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("stopTransmissionUpdates() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     *
     * @param token The token is generated by the user which is used as a unique identifier for
     *              provisioning with satellite gateway.
     * @param provisionData Data from the provisioning app that can be used by provisioning server
     * @param cancellationSignal The optional signal used by the caller to cancel the provision
     *                           request. Even when the cancellation is signaled, Telephony will
     *                           still trigger the callback to return the result of this request.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void provisionService(@NonNull String token, @NonNull byte[] provisionData,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);
        Objects.requireNonNull(provisionData);

        ICancellationSignal cancelRemote = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                cancelRemote = telephony.provisionSatelliteService(token, provisionData,
                        errorCallback);
            } else {
                loge("provisionService() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("provisionService() RemoteException=" + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
        if (cancellationSignal != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Deprovision the device with the satellite provider.
     * This is needed if the provider allows dynamic registration. Once deprovisioned,
     * {@link SatelliteProvisionStateCallback#onSatelliteProvisionStateChanged(boolean)}
     * should report as deprovisioned.
     * For provisioning satellite service, refer to
     * {@link #provisionService(String, byte[], CancellationSignal, Executor, Consumer)}
     *
     * @param token The token of the device/subscription to be deprovisioned.
     *              This should match with the token passed as input in
     *              {@link #provisionService(String, byte[], CancellationSignal, Executor,
     *              Consumer)}
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void deprovisionService(@NonNull String token,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.deprovisionSatelliteService(token, errorCallback);
            } else {
                loge("deprovisionService() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("deprovisionService() RemoteException ex=" + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Registers for the satellite provision state changed.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the satellite provision state changed event.
     *
     * @return The {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForProvisionStateChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteProvisionStateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteProvisionStateCallback internalCallback =
                        new ISatelliteProvisionStateCallback.Stub() {
                            @Override
                            public void onSatelliteProvisionStateChanged(boolean provisioned) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteProvisionStateChanged(
                                                provisioned)));
                            }

                            @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
                            @Override
                            public void onSatelliteSubscriptionProvisionStateChanged(
                                    @NonNull List<SatelliteSubscriberProvisionStatus>
                                            satelliteSubscriberProvisionStatus) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onSatelliteSubscriptionProvisionStateChanged(
                                                satelliteSubscriberProvisionStatus)));
                            }
                        };
                sSatelliteProvisionStateCallbackMap.put(callback, internalCallback);
                return telephony.registerForSatelliteProvisionStateChanged(internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForProvisionStateChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for the satellite provision state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForProvisionStateChanged(Executor, SatelliteProvisionStateCallback)}
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForProvisionStateChanged(
            @NonNull SatelliteProvisionStateCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteProvisionStateCallback internalCallback =
                sSatelliteProvisionStateCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSatelliteProvisionStateChanged(internalCallback);
                } else {
                    loge("unregisterForProvisionStateChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForProvisionStateChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Request to get whether this device is provisioned with a satellite provider.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the device is
     *                 provisioned with a satellite provider and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsProvisioned(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_PROVISIONED)) {
                                boolean isSatelliteProvisioned =
                                        resultData.getBoolean(KEY_SATELLITE_PROVISIONED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteProvisioned)));
                            } else {
                                loge("KEY_SATELLITE_PROVISIONED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsSatelliteProvisioned(receiver);
            } else {
                loge("requestIsProvisioned() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestIsProvisioned() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Registers for modem state changed from satellite modem.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the satellite modem state changed event.
     *
     * @return The {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForModemStateChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteModemStateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteModemStateCallback internalCallback =
                        new ISatelliteModemStateCallback.Stub() {
                    @Override
                    public void onSatelliteModemStateChanged(int state) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                callback.onSatelliteModemStateChanged(state)));
                    }

                    @Override
                    public void onEmergencyModeChanged(boolean isEmergency) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                callback.onEmergencyModeChanged(isEmergency)));
                    }

                    @Hide
                    @Override
                    public void onRegistrationFailure(int causeCode) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                callback.onRegistrationFailure(causeCode)));
                    }

                    @Override
                    public void onTerrestrialNetworkAvailableChanged(boolean isAvailable) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                callback.onTerrestrialNetworkAvailableChanged(isAvailable)));
                    }
                };
                sSatelliteModemStateCallbackMap.put(callback, internalCallback);
                return telephony.registerForSatelliteModemStateChanged(internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForModemStateChanged() RemoteException:" + ex);
            ex.rethrowAsRuntimeException();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for modem state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForModemStateChanged(Executor, SatelliteModemStateCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForModemStateChanged(
            @NonNull SatelliteModemStateCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteModemStateCallback internalCallback = sSatelliteModemStateCallbackMap.remove(
                callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForModemStateChanged(internalCallback);
                } else {
                    loge("unregisterForModemStateChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForModemStateChanged() RemoteException:" + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Register to receive incoming datagrams over satellite.
     *
     * To poll for pending satellite datagrams, refer to
     * {@link #pollPendingDatagrams(Executor, Consumer)}
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle incoming datagrams over satellite.
     *                 This callback with be invoked when a new datagram is received from satellite.
     *
     * @return The {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForIncomingDatagram(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteDatagramCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteDatagramCallback internalCallback =
                        new ISatelliteDatagramCallback.Stub() {
                            @Override
                            public void onSatelliteDatagramReceived(long datagramId,
                                    @NonNull SatelliteDatagram datagram, int pendingCount,
                                    @NonNull IVoidConsumer internalAck) {
                                Consumer<Void> externalAck = new Consumer<Void>() {
                                    @Override
                                    public void accept(Void result) {
                                        try {
                                            internalAck.accept();
                                        }  catch (RemoteException e) {
                                              logd("onSatelliteDatagramReceived "
                                                      + "RemoteException: " + e);
                                        }
                                    }
                                };

                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteDatagramReceived(
                                                datagramId, datagram, pendingCount, externalAck)));
                            }
                        };
                sSatelliteDatagramCallbackMap.put(callback, internalCallback);
                return telephony.registerForIncomingDatagram(internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForIncomingDatagram() RemoteException:" + ex);
            ex.rethrowAsRuntimeException();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregister to stop receiving incoming datagrams over satellite.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForIncomingDatagram(Executor, SatelliteDatagramCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForIncomingDatagram(@NonNull SatelliteDatagramCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteDatagramCallback internalCallback =
                sSatelliteDatagramCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForIncomingDatagram(internalCallback);
                } else {
                    loge("unregisterForIncomingDatagram: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForIncomingDatagram() RemoteException:" + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Poll pending satellite datagrams over satellite.
     *
     * This method should be called when user specifies to check incoming messages over satellite.
     * This method requests modem to check if there are any pending datagrams to be received over
     * satellite. If there are any incoming datagrams, they will be received via
     * {@link SatelliteDatagramCallback#onSatelliteDatagramReceived(long, SatelliteDatagram, int,
     * Consumer)} )}
     *
     * @param executor The executor on which the result listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void pollPendingDatagrams(@NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer internalCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.pollPendingDatagrams(internalCallback);
            } else {
                loge("pollPendingDatagrams() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("pollPendingDatagrams() RemoteException:" + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Send datagram over satellite.
     *
     * Gateway encodes SOS message or location sharing message into a datagram and passes it as
     * input to this method. Datagram received here will be passed down to modem without any
     * encoding or encryption.
     *
     * @param datagramType datagram type indicating whether the datagram is of type
     *                     SOS_SMS or LOCATION_SHARING.
     * @param datagram encoded gateway datagram which is encrypted by the caller.
     *                 Datagram will be passed down to modem without any encoding or encryption.
     * @param needFullScreenPointingUI If set to true, this indicates pointingUI app to open in full
     *                                 screen mode if satellite communication needs pointingUI.
     *                                 If this is set to false, pointingUI may be presented to the
     *                                 user in collapsed view. Application may decide to mark this
     *                                 flag as true when the user is sending data for the first time
     *                                 or whenever there is a considerable idle time between
     *                                 satellite activity. This decision should be done based upon
     *                                 user activity and the application's ability to determine the
     *                                 best possible UX experience for the user.
     * @param executor The executor on which the result listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void sendDatagram(@DatagramType int datagramType,
            @NonNull SatelliteDatagram datagram, boolean needFullScreenPointingUI,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(datagram);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer internalCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.sendDatagram(datagramType, datagram,
                        needFullScreenPointingUI, internalCallback);
            } else {
                loge("sendDatagram() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("sendDatagram() RemoteException:" + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if satellite
     *                 communication is allowed for the current location and
     *                 {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsCommunicationAllowedForCurrentLocation(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_COMMUNICATION_ALLOWED)) {
                                boolean isSatelliteCommunicationAllowed =
                                        resultData.getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteCommunicationAllowed)));
                            } else {
                                loge("KEY_SATELLITE_COMMUNICATION_ALLOWED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsCommunicationAllowedForCurrentLocation(mSubId, receiver);
            } else {
                loge("requestIsCommunicationAllowedForCurrentLocation() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestIsCommunicationAllowedForCurrentLocation() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get satellite access configuration for the current location.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code SatelliteAccessConfiguration} with value the regional
     *                 satellite access configuration at the current location.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void requestSatelliteAccessConfigurationForCurrentLocation(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<SatelliteAccessConfiguration, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_ACCESS_CONFIGURATION)) {
                                SatelliteAccessConfiguration satelliteAccessConfiguration =
                                        resultData.getParcelable(KEY_SATELLITE_ACCESS_CONFIGURATION,
                                                SatelliteAccessConfiguration.class);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(satelliteAccessConfiguration)));
                            } else {
                                loge("KEY_SATELLITE_ACCESS_CONFIGURATION does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestSatelliteAccessConfigurationForCurrentLocation(receiver);
            } else {
                loge("requestSatelliteAccessConfigurationForCurrentLocation() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestSatelliteAccessConfigurationForCurrentLocation() RemoteException: "
                    + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get the duration in seconds after which the satellite will be visible.
     * This will be {@link Duration#ZERO} if the satellite is currently visible.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return the time after which the satellite will be visible.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestTimeForNextSatelliteVisibility(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Duration, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_NEXT_VISIBILITY)) {
                                int nextVisibilityDuration =
                                        resultData.getInt(KEY_SATELLITE_NEXT_VISIBILITY);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(
                                                Duration.ofSeconds(nextVisibilityDuration))));
                            } else {
                                loge("KEY_SATELLITE_NEXT_VISIBILITY does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestTimeForNextSatelliteVisibility(receiver);
            } else {
                loge("requestTimeForNextSatelliteVisibility() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestTimeForNextSatelliteVisibility() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get the currently selected satellite subscription id as an {@link Integer}.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return the selected NB IOT satellite subscription ID.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void requestSelectedNbIotSatelliteSubscriptionId(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Integer, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData
                                    .containsKey(KEY_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID)) {
                                int selectedSatelliteSubscriptionId =
                                        resultData
                                            .getInt(KEY_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(selectedSatelliteSubscriptionId)));
                            } else {
                                loge(
                                    "KEY_SELECTED_NB_IOT_SATELLITE_SUBSCRIPTION_ID does not exist."
                                    );
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestSelectedNbIotSatelliteSubscriptionId(receiver);
            } else {
                loge("requestSelectedNbIotSatelliteSubscriptionId() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestSelectedNbIotSatelliteSubscriptionId() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Registers for selected satellite subscription changed event from the satellite service.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the selected satellite subscription changed event.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteResult public int registerForSelectedNbIotSatelliteSubscriptionChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SelectedNbIotSatelliteSubscriptionCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISelectedNbIotSatelliteSubscriptionCallback internalCallback =
                        new ISelectedNbIotSatelliteSubscriptionCallback.Stub() {
                            @Override
                            public void onSelectedNbIotSatelliteSubscriptionChanged(
                                    int selectedSubId) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSelectedNbIotSatelliteSubscriptionChanged(
                                                selectedSubId)));
                            }
                        };
                sSelectedNbIotSatelliteSubscriptionCallbackMap.put(callback, internalCallback);
                return telephony.registerForSelectedNbIotSatelliteSubscriptionChanged(
                        internalCallback);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSelectedNbIotSatelliteSubscriptionChanged() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for selected satellite subscription changed event from the satellite service. If
     * callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to {@link
     *     #registerForSelectedNbIotSatelliteSubscriptionChanged(Executor,
     *     SelectedNbIotSatelliteSubscriptionCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void unregisterForSelectedNbIotSatelliteSubscriptionChanged(
            @NonNull SelectedNbIotSatelliteSubscriptionCallback callback) {
        Objects.requireNonNull(callback);
        ISelectedNbIotSatelliteSubscriptionCallback internalCallback =
                sSelectedNbIotSatelliteSubscriptionCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSelectedNbIotSatelliteSubscriptionChanged(
                            internalCallback);
                } else {
                    loge("unregisterForSelectedNbIotSatelliteSubscriptionChanged: " +
                            "No internal callback.");
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSelectedNbIotSatelliteSubscriptionChanged() RemoteException: " +
                    ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Inform whether the device is aligned with the satellite in both real and demo mode.
     *
     * In demo mode, framework will send datagram to modem only when device is aligned with
     * the satellite. This method helps framework to simulate the experience of sending datagram
     * over satellite.
     *
     * @param isAligned {code @true} Device is aligned with the satellite
     *                  {code @false} Device is not aligned with the satellite
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void setDeviceAlignedWithSatellite(boolean isAligned) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setDeviceAlignedWithSatellite(isAligned);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("setDeviceAlignedWithSatellite() RemoteException:" + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * User request to enable or disable carrier supported satellite plmn scan and attach by modem.
     * <p>
     * This API should be called by only settings app to pass down the user input for
     * enabling/disabling satellite. This user input will be persisted across device reboots.
     * <p>
     * Satellite will be enabled only when the following conditions are met:
     * <ul>
     * <li>Users want to enable it.</li>
     * <li>There is no satellite communication restriction, which is added by
     * {@link #addAttachRestrictionForCarrier(int, int, Executor, Consumer)}</li>
     * <li>The carrier config {@link
     * CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL} is set to
     * {@code true}.</li>
     * </ul>
     *
     * @param subId The subscription ID of the carrier.
     * @param enableSatellite {@code true} to enable the satellite and {@code false} to disable.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalArgumentException if the subscription is invalid.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void requestAttachEnabledForCarrier(int subId, boolean enableSatellite,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        if (enableSatellite) {
            removeAttachRestrictionForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, executor, resultListener);
        } else {
            addAttachRestrictionForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, executor, resultListener);
        }
    }

    /**
     * Request to get whether the carrier supported satellite plmn scan and attach by modem is
     * enabled by user.
     *
     * @param subId The subscription ID of the carrier.
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the satellite
     *                 is enabled and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if the subscription is invalid.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void requestIsAttachEnabledForCarrier(int subId,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        Set<Integer> restrictionReason = getAttachRestrictionReasonsForCarrier(subId);
        executor.execute(() -> callback.onResult(
                !restrictionReason.contains(SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER)));
    }

    /**
     * Add a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem.
     *
     * @param subId The subscription ID of the carrier.
     * @param reason Reason for disallowing satellite communication.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalArgumentException if the subscription is invalid.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void addAttachRestrictionForCarrier(int subId,
            @SatelliteCommunicationRestrictionReason int reason,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.addAttachRestrictionForCarrier(subId, reason, errorCallback);
            } else {
                loge("addAttachRestrictionForCarrier() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("addAttachRestrictionForCarrier() RemoteException:" + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Remove a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem.
     *
     * @param subId The subscription ID of the carrier.
     * @param reason Reason for disallowing satellite communication.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalArgumentException if the subscription is invalid.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void removeAttachRestrictionForCarrier(int subId,
            @SatelliteCommunicationRestrictionReason int reason,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.removeAttachRestrictionForCarrier(subId, reason, errorCallback);
            } else {
                loge("removeAttachRestrictionForCarrier() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(
                        () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
            }
        } catch (RemoteException ex) {
            loge("removeAttachRestrictionForCarrier() RemoteException:" + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> resultListener.accept(SATELLITE_RESULT_ILLEGAL_STATE)));
        }
    }

    /**
     * Get reasons for disallowing satellite attach, as requested by
     * {@link #addAttachRestrictionForCarrier(int, int, Executor, Consumer)}
     *
     * @param subId The subscription ID of the carrier.
     * @return Set of reasons for disallowing satellite communication.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if the subscription is invalid.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteCommunicationRestrictionReason
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @NonNull public Set<Integer> getAttachRestrictionReasonsForCarrier(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int[] receivedArray =
                        telephony.getAttachRestrictionReasonsForCarrier(subId);
                if (receivedArray.length == 0) {
                    logd("receivedArray is empty, create empty set");
                    return new HashSet<>();
                } else {
                    return Arrays.stream(receivedArray).boxed().collect(Collectors.toSet());
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getAttachRestrictionReasonsForCarrier() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
        return new HashSet<>();
    }

    /**
     * Returns list of disallowed reasons of satellite.
     *
     * @return Integer array of disallowed reasons.
     *
     * @throws SecurityException     if caller doesn't have required permission.
     * @throws IllegalStateException if Telephony process isn't available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteDisallowedReason
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    @NonNull
    public int[] getSatelliteDisallowedReasons() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getSatelliteDisallowedReasons();
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getSatelliteDisallowedReasons() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
        return new int[0];
    }

    /**
     * Registers for disallowed reasons change event from satellite service.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle disallowed reasons changed event.
     *
     * @throws SecurityException     if caller doesn't have required permission.
     * @throws IllegalStateException if Telephony process is not available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void registerForSatelliteDisallowedReasonsChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteDisallowedReasonsCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteDisallowedReasonsCallback internalCallback =
                        new ISatelliteDisallowedReasonsCallback.Stub() {
                            @Override
                            public void onSatelliteDisallowedReasonsChanged(
                                    int[] disallowedReasons) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteDisallowedReasonsChanged(
                                                disallowedReasons)));
                            }
                        };
                telephony.registerForSatelliteDisallowedReasonsChanged(internalCallback);
                sSatelliteDisallowedReasonsCallbackMap.put(callback, internalCallback);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSatelliteDisallowedReasonsChanged() RemoteException" + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Unregisters for disallowed reasons change event from satellite service.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteDisallowedReasonsChanged(
     * Executor, SatelliteDisallowedReasonsCallback)}
     *
     * @throws SecurityException     if caller doesn't have required permission.
     * @throws IllegalStateException if Telephony process is not available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void unregisterForSatelliteDisallowedReasonsChanged(
            @NonNull SatelliteDisallowedReasonsCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteDisallowedReasonsCallback internalCallback =
                sSatelliteDisallowedReasonsCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSatelliteDisallowedReasonsChanged(internalCallback);
                } else {
                    loge("unregisterForSatelliteDisallowedReasonsChanged: No internal callback.");
                    throw new IllegalArgumentException("callback is not valid");
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSatelliteDisallowedReasonsChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Request to get the signal strength of the satellite connection.
     *
     * <p>
     * Note: This API is specifically designed for OEM enabled satellite connectivity only.
     * For satellite connectivity enabled using carrier roaming, please refer to
     * {@link TelephonyCallback.SignalStrengthsListener}, and
     * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
     * </p>
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered. If the request is
     * successful, {@link OutcomeReceiver#onResult(Object)} will return an instance of
     * {@link NtnSignalStrength} with a value of {@link NtnSignalStrength.NtnSignalStrengthLevel}
     * The {@link NtnSignalStrength#NTN_SIGNAL_STRENGTH_NONE} will be returned if there is no
     * signal strength data available.
     * If the request is not successful, {@link OutcomeReceiver#onError(Throwable)} will return a
     * {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestNtnSignalStrength(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<NtnSignalStrength, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_NTN_SIGNAL_STRENGTH)) {
                                NtnSignalStrength ntnSignalStrength =
                                        resultData.getParcelable(KEY_NTN_SIGNAL_STRENGTH,
                                                NtnSignalStrength.class);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(ntnSignalStrength)));
                            } else {
                                loge("KEY_NTN_SIGNAL_STRENGTH does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestNtnSignalStrength(receiver);
            } else {
                loge("requestNtnSignalStrength() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestNtnSignalStrength() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Registers for NTN signal strength changed from satellite modem.
     * If the registration operation is not successful, a {@link SatelliteException} that contains
     * {@link SatelliteResult} will be thrown.
     *
     * <p>
     * Note: This API is specifically designed for OEM enabled satellite connectivity only.
     * For satellite connectivity enabled using carrier roaming, please refer to
     * {@link TelephonyCallback.SignalStrengthsListener}, and
     * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
     * </p>
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the NTN signal strength changed event.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void registerForNtnSignalStrengthChanged(@NonNull @CallbackExecutor Executor executor,
            @NonNull NtnSignalStrengthCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                INtnSignalStrengthCallback internalCallback =
                        new INtnSignalStrengthCallback.Stub() {
                            @Override
                            public void onNtnSignalStrengthChanged(
                                    NtnSignalStrength ntnSignalStrength) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onNtnSignalStrengthChanged(
                                                ntnSignalStrength)));
                            }
                        };
                telephony.registerForNtnSignalStrengthChanged(internalCallback);
                sNtnSignalStrengthCallbackMap.put(callback, internalCallback);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForNtnSignalStrengthChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Unregisters for NTN signal strength changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * <p>
     * Note: This API is specifically designed for OEM enabled satellite connectivity only.
     * For satellite connectivity enabled using carrier roaming, please refer to
     * {@link TelephonyManager#unregisterTelephonyCallback(TelephonyCallback)}..
     * </p>
     *
     * @param callback The callback that was passed to.
     * {@link #registerForNtnSignalStrengthChanged(Executor, NtnSignalStrengthCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalArgumentException if the callback is not valid or has already been
     * unregistered.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForNtnSignalStrengthChanged(@NonNull NtnSignalStrengthCallback callback) {
        Objects.requireNonNull(callback);
        INtnSignalStrengthCallback internalCallback =
                sNtnSignalStrengthCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForNtnSignalStrengthChanged(internalCallback);
                } else {
                    loge("unregisterForNtnSignalStrengthChanged: No internal callback.");
                    throw new IllegalArgumentException("callback is not valid");
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForNtnSignalStrengthChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Registers for satellite capabilities change event from the satellite service.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the satellite capabilities changed event.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForCapabilitiesChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteCapabilitiesCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteCapabilitiesCallback internalCallback =
                        new ISatelliteCapabilitiesCallback.Stub() {
                            @Override
                            public void onSatelliteCapabilitiesChanged(
                                    SatelliteCapabilities capabilities) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteCapabilitiesChanged(
                                                capabilities)));
                            }
                        };
                sSatelliteCapabilitiesCallbackMap.put(callback, internalCallback);
                return telephony.registerForCapabilitiesChanged(internalCallback);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForCapabilitiesChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for satellite capabilities change event from the satellite service.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to.
     * {@link #registerForCapabilitiesChanged(Executor, SatelliteCapabilitiesCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForCapabilitiesChanged(
            @NonNull SatelliteCapabilitiesCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteCapabilitiesCallback internalCallback =
                sSatelliteCapabilitiesCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForCapabilitiesChanged(internalCallback);
                } else {
                    loge("unregisterForCapabilitiesChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForCapabilitiesChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Get all satellite PLMNs for which attach is enable for carrier.
     *
     * @param subId subId The subscription ID of the carrier.
     *
     * @return List of plmn for carrier satellite service. If no plmn is available, empty list will
     * be returned.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @NonNull public List<String> getSatellitePlmnsForCarrier(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getSatellitePlmnsForCarrier(subId);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getSatellitePlmnsForCarrier() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
        return new ArrayList<>();
    }

    /**
     * Registers for the satellite supported state changed.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the satellite supoprted state changed event.
     *
     * @return The result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteResult public int registerForSupportedStateChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteSupportedStateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteSupportedStateCallback internalCallback =
                        new ISatelliteSupportedStateCallback.Stub() {
                            @Override
                            public void onSatelliteSupportedStateChanged(boolean supported) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteSupportedStateChanged(
                                                supported)));
                            }
                        };
                sSatelliteSupportedStateCallbackMap.put(callback, internalCallback);
                return telephony.registerForSatelliteSupportedStateChanged(
                        internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSupportedStateChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for the satellite supported state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSupportedStateChanged(Executor, SatelliteSupportedStateCallback)}
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void unregisterForSupportedStateChanged(
            @NonNull SatelliteSupportedStateCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteSupportedStateCallback internalCallback =
                sSatelliteSupportedStateCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSatelliteSupportedStateChanged(internalCallback);
                } else {
                    loge("unregisterForSupportedStateChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSupportedStateChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Registers for the satellite communication allowed state changed.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle satellite communication allowed state changed event.
     * @return The result of the operation.
     * @throws SecurityException     if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteResult
    public int registerForCommunicationAllowedStateChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteCommunicationAllowedStateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteCommunicationAllowedStateCallback internalCallback =
                        new ISatelliteCommunicationAllowedStateCallback.Stub() {
                            @Override
                            public void onSatelliteCommunicationAllowedStateChanged(
                                    boolean isAllowed) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteCommunicationAllowedStateChanged(
                                                isAllowed)));
                            }

                            @Override
                            public void onSatelliteAccessConfigurationChanged(
                                    @Nullable SatelliteAccessConfiguration
                                            satelliteAccessConfiguration) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteAccessConfigurationChanged(
                                                satelliteAccessConfiguration)));
                            }
                        };
                sSatelliteCommunicationAllowedStateCallbackMap.put(callback, internalCallback);
                return telephony.registerForCommunicationAllowedStateChanged(
                        mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForCommunicationAllowedStateChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for the satellite communication allowed state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     *                 {@link #registerForCommunicationAllowedStateChanged(Executor,
     *                 SatelliteCommunicationAllowedStateCallback)}
     * @throws SecurityException     if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void unregisterForCommunicationAllowedStateChanged(
            @NonNull SatelliteCommunicationAllowedStateCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteCommunicationAllowedStateCallback internalCallback =
                sSatelliteCommunicationAllowedStateCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForCommunicationAllowedStateChanged(mSubId,
                            internalCallback);
                } else {
                    loge("unregisterForCommunicationAllowedStateChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForCommunicationAllowedStateChanged() RemoteException: " + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Request to get the {@link SatelliteSessionStats} of the satellite service.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return the {@link SatelliteSessionStats} of the satellite service.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @hide
     */
    @RequiresPermission(allOf = {Manifest.permission.PACKAGE_USAGE_STATS,
            Manifest.permission.MODIFY_PHONE_STATE})
    public void requestSessionStats(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<SatelliteSessionStats, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            SatelliteSessionStats stats;
                            if (resultData.containsKey(KEY_SESSION_STATS)) {
                                stats = resultData.getParcelable(KEY_SESSION_STATS,
                                        SatelliteSessionStats.class);
                                if (resultData.containsKey(KEY_SESSION_STATS_V2)) {
                                    SatelliteSessionStats stats1 = resultData.getParcelable(
                                            KEY_SESSION_STATS_V2, SatelliteSessionStats.class);
                                    if (stats != null && stats1 != null) {
                                        stats.setSatelliteSessionStats(
                                                stats1.getSatelliteSessionStats());
                                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                                () -> callback.onResult(stats)));
                                        return;
                                    }
                                } else {
                                    loge("KEY_SESSION_STATS_V2 does not exist.");
                                }
                            } else {
                                loge("KEY_SESSION_STATS does not exist.");
                            }
                            executor.execute(() -> Binder.withCleanCallingIdentity(
                                    () -> callback.onError(new SatelliteException(
                                            SATELLITE_RESULT_REQUEST_FAILED))));

                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(
                                    () -> callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestSatelliteSessionStats(mSubId, receiver);
            } else {
                loge("requestSessionStats() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestSessionStats() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get list of prioritized satellite subscriber ids to be used for provision.
     *
     * Satellite Gateway client will use these subscriber ids to register with satellite gateway
     * service which identify user subscription with unique subscriber ids. These subscriber ids
     * can be any unique value like iccid, imsi or msisdn which is decided based upon carrier
     * requirements.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     * If successful, the callback returns a list of tokens sorted in ascending priority order index
     * 0 has the highest priority. Otherwise, it returns an error with a SatelliteException.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void requestSatelliteSubscriberProvisionStatus(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<List<SatelliteSubscriberProvisionStatus>,
                    SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_REQUEST_PROVISION_SUBSCRIBER_ID_TOKEN)) {
                                List<SatelliteSubscriberProvisionStatus> list =
                                        resultData.getParcelableArrayList(
                                                KEY_REQUEST_PROVISION_SUBSCRIBER_ID_TOKEN,
                                                SatelliteSubscriberProvisionStatus.class);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(list)));
                            } else {
                                loge("KEY_REQUEST_PROVISION_SUBSCRIBER_ID_TOKEN does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestSatelliteSubscriberProvisionStatus(receiver);
            } else {
                loge("requestSatelliteSubscriberProvisionStatus() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestSatelliteSubscriberProvisionStatus() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Request to get the display name of satellite feature in the UI.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return display name of the satellite feature in string format. Default
     *                 display name is "Satellite". If the request is not successful,
     *                 {@link OutcomeReceiver#onError(Throwable)} will return an error with
     *                 a SatelliteException.
     *
     * @throws SecurityException     if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void requestSatelliteDisplayName(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<CharSequence, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_DISPLAY_NAME)) {
                                CharSequence satelliteDisplayName =
                                        resultData.getString(KEY_SATELLITE_DISPLAY_NAME);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(satelliteDisplayName)));
                            } else {
                                loge("KEY_SATELLITE_DISPLAY_NAME does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestSatelliteDisplayName(receiver);
            } else {
                loge("requestSatelliteDisplayName() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("requestSatelliteDisplayName() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Deliver the list of provisioned satellite subscriber infos.
     *
     * @param list The list of provisioned satellite subscriber infos.
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void provisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_PROVISION_SATELLITE_TOKENS)) {
                                boolean isUpdated =
                                        resultData.getBoolean(KEY_PROVISION_SATELLITE_TOKENS);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isUpdated)));
                            } else {
                                loge("KEY_REQUEST_PROVISION_TOKENS does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.provisionSatellite(list, receiver);
            } else {
                loge("provisionSatellite() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("provisionSatellite() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Deliver the list of deprovisioned satellite subscriber infos.
     *
     * @param list The list of deprovisioned satellite subscriber infos.
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
    public void deprovisionSatellite(@NonNull List<SatelliteSubscriberInfo> list,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_DEPROVISION_SATELLITE_TOKENS)) {
                                boolean isUpdated =
                                        resultData.getBoolean(KEY_DEPROVISION_SATELLITE_TOKENS);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isUpdated)));
                            } else {
                                loge("KEY_DEPROVISION_SATELLITE_TOKENS does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.deprovisionSatellite(list, receiver);
            } else {
                loge("deprovisionSatellite() invalid telephony");
                executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                        new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
            }
        } catch (RemoteException ex) {
            loge("deprovisionSatellite() RemoteException: " + ex);
            executor.execute(() -> Binder.withCleanCallingIdentity(() -> callback.onError(
                    new SatelliteException(SATELLITE_RESULT_ILLEGAL_STATE))));
        }
    }

    /**
     * Inform whether application supports NTN SMS in satellite mode.
     *
     * This method is used by default messaging application to inform framework whether it supports
     * NTN SMS or not.
     *
     * Invoking this API will internally result in triggering
     * {@link android.telephony.TelephonyCallback.CarrierRoamingNtnModeListener
     * #onCarrierRoamingNtnAvailableServicesChanged(List)} and
     * {@link android.telephony.TelephonyCallback.CarrierRoamingNtnModeListener
     * #onCarrierRoamingNtnEligibleStateChanged(boolean)} callbacks.
     *
     * @param ntnSmsSupported {@code true} If application supports NTN SMS, else {@code false}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @RequiresPermission(allOf = {Manifest.permission.SATELLITE_COMMUNICATION,
            Manifest.permission.SEND_SMS})
    public void setNtnSmsSupported(boolean ntnSmsSupported) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setNtnSmsSupported(ntnSmsSupported);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("setNtnSmsSupported() RemoteException:" + ex);
            ex.rethrowAsRuntimeException();
        }
    }

    @Nullable
    private static ITelephony getITelephony() {
        ITelephony binder = ITelephony.Stub.asInterface(TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .getTelephonyServiceRegisterer()
                .get());
        return binder;
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }
}
