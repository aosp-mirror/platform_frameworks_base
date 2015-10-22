/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telecom.PhoneAccount;
import android.util.Log;

import com.android.internal.telecom.ITelecomService;
import com.android.internal.telephony.CellNetworkScanResult;
import com.android.internal.telephony.IPhoneSubInfo;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides access to information about the telephony services on
 * the device. Applications can use the methods in this class to
 * determine telephony services and states, as well as to access some
 * types of subscriber information. Applications can also register
 * a listener to receive notification of telephony state changes.
 * <p>
 * You do not instantiate this class directly; instead, you retrieve
 * a reference to an instance through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.TELEPHONY_SERVICE)}.
 * <p>
 * Note that access to some telephony information is
 * permission-protected. Your application cannot access the protected
 * information unless it has the appropriate permissions declared in
 * its manifest file. Where permissions apply, they are noted in the
 * the methods through which you access the protected information.
 */
public class TelephonyManager {
    private static final String TAG = "TelephonyManager";

    private static ITelephonyRegistry sRegistry;

    /**
     * The allowed states of Wi-Fi calling.
     *
     * @hide
     */
    public interface WifiCallingChoices {
        /** Always use Wi-Fi calling */
        static final int ALWAYS_USE = 0;
        /** Ask the user whether to use Wi-Fi on every call */
        static final int ASK_EVERY_TIME = 1;
        /** Never use Wi-Fi calling */
        static final int NEVER_USE = 2;
    }

    private final Context mContext;
    private SubscriptionManager mSubscriptionManager;

    private static String multiSimConfig =
            SystemProperties.get(TelephonyProperties.PROPERTY_MULTI_SIM_CONFIG);

    /** Enum indicating multisim variants
     *  DSDS - Dual SIM Dual Standby
     *  DSDA - Dual SIM Dual Active
     *  TSTS - Triple SIM Triple Standby
     **/
    /** @hide */
    public enum MultiSimVariants {
        DSDS,
        DSDA,
        TSTS,
        UNKNOWN
    };

    /** @hide */
    public TelephonyManager(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
        mSubscriptionManager = SubscriptionManager.from(mContext);

        if (sRegistry == null) {
            sRegistry = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
        }
    }

    /** @hide */
    private TelephonyManager() {
        mContext = null;
    }

    private static TelephonyManager sInstance = new TelephonyManager();

    /** @hide
    /* @deprecated - use getSystemService as described above */
    public static TelephonyManager getDefault() {
        return sInstance;
    }

    private String getOpPackageName() {
        // For legacy reasons the TelephonyManager has API for getting
        // a static instance with no context set preventing us from
        // getting the op package name. As a workaround we do a best
        // effort and get the context from the current activity thread.
        if (mContext != null) {
            return mContext.getOpPackageName();
        }
        return ActivityThread.currentOpPackageName();
    }

    /**
     * Returns the multi SIM variant
     * Returns DSDS for Dual SIM Dual Standby
     * Returns DSDA for Dual SIM Dual Active
     * Returns TSTS for Triple SIM Triple Standby
     * Returns UNKNOWN for others
     */
    /** {@hide} */
    public MultiSimVariants getMultiSimConfiguration() {
        String mSimConfig =
            SystemProperties.get(TelephonyProperties.PROPERTY_MULTI_SIM_CONFIG);
        if (mSimConfig.equals("dsds")) {
            return MultiSimVariants.DSDS;
        } else if (mSimConfig.equals("dsda")) {
            return MultiSimVariants.DSDA;
        } else if (mSimConfig.equals("tsts")) {
            return MultiSimVariants.TSTS;
        } else {
            return MultiSimVariants.UNKNOWN;
        }
    }


    /**
     * Returns the number of phones available.
     * Returns 1 for Single standby mode (Single SIM functionality)
     * Returns 2 for Dual standby mode.(Dual SIM functionality)
     */
    public int getPhoneCount() {
        int phoneCount = 1;
        switch (getMultiSimConfiguration()) {
            case UNKNOWN:
                phoneCount = 1;
                break;
            case DSDS:
            case DSDA:
                phoneCount = PhoneConstants.MAX_PHONE_COUNT_DUAL_SIM;
                break;
            case TSTS:
                phoneCount = PhoneConstants.MAX_PHONE_COUNT_TRI_SIM;
                break;
        }
        return phoneCount;
    }

    /** {@hide} */
    public static TelephonyManager from(Context context) {
        return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /** {@hide} */
    public boolean isMultiSimEnabled() {
        return (multiSimConfig.equals("dsds") || multiSimConfig.equals("dsda") ||
            multiSimConfig.equals("tsts"));
    }

    //
    // Broadcast Intent actions
    //

    /**
     * Broadcast intent action indicating that the call state
     * on the device has changed.
     *
     * <p>
     * The {@link #EXTRA_STATE} extra indicates the new call state.
     * If the new state is RINGING, a second extra
     * {@link #EXTRA_INCOMING_NUMBER} provides the incoming phone number as
     * a String.
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">
     * This was a {@link android.content.Context#sendStickyBroadcast sticky}
     * broadcast in version 1.0, but it is no longer sticky.
     * Instead, use {@link #getCallState} to synchronously query the current call state.
     *
     * @see #EXTRA_STATE
     * @see #EXTRA_INCOMING_NUMBER
     * @see #getCallState
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PHONE_STATE_CHANGED =
            "android.intent.action.PHONE_STATE";

    /**
     * The Phone app sends this intent when a user opts to respond-via-message during an incoming
     * call. By default, the device's default SMS app consumes this message and sends a text message
     * to the caller. A third party app can also provide this functionality by consuming this Intent
     * with a {@link android.app.Service} and sending the message using its own messaging system.
     * <p>The intent contains a URI (available from {@link android.content.Intent#getData})
     * describing the recipient, using either the {@code sms:}, {@code smsto:}, {@code mms:},
     * or {@code mmsto:} URI schema. Each of these URI schema carry the recipient information the
     * same way: the path part of the URI contains the recipient's phone number or a comma-separated
     * set of phone numbers if there are multiple recipients. For example, {@code
     * smsto:2065551234}.</p>
     *
     * <p>The intent may also contain extras for the message text (in {@link
     * android.content.Intent#EXTRA_TEXT}) and a message subject
     * (in {@link android.content.Intent#EXTRA_SUBJECT}).</p>
     *
     * <p class="note"><strong>Note:</strong>
     * The intent-filter that consumes this Intent needs to be in a {@link android.app.Service}
     * that requires the
     * permission {@link android.Manifest.permission#SEND_RESPOND_VIA_MESSAGE}.</p>
     * <p>For example, the service that receives this intent can be declared in the manifest file
     * with an intent filter like this:</p>
     * <pre>
     * &lt;!-- Service that delivers SMS messages received from the phone "quick response" -->
     * &lt;service android:name=".HeadlessSmsSendService"
     *          android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE"
     *          android:exported="true" >
     *   &lt;intent-filter>
     *     &lt;action android:name="android.intent.action.RESPOND_VIA_MESSAGE" />
     *     &lt;category android:name="android.intent.category.DEFAULT" />
     *     &lt;data android:scheme="sms" />
     *     &lt;data android:scheme="smsto" />
     *     &lt;data android:scheme="mms" />
     *     &lt;data android:scheme="mmsto" />
     *   &lt;/intent-filter>
     * &lt;/service></pre>
     * <p>
     * Output: nothing.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_RESPOND_VIA_MESSAGE =
            "android.intent.action.RESPOND_VIA_MESSAGE";

    /**
     * The emergency dialer may choose to present activities with intent filters for this
     * action as emergency assistance buttons that launch the activity when clicked.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_EMERGENCY_ASSISTANCE =
            "android.telephony.action.EMERGENCY_ASSISTANCE";

    /**
     * Open the voicemail settings activity to make changes to voicemail configuration.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CONFIGURE_VOICEMAIL =
            "android.telephony.action.CONFIGURE_VOICEMAIL";

    /**
     * @hide
     */
    public static final boolean EMERGENCY_ASSISTANCE_ENABLED = false;

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the new call state.
     *
     * @see #EXTRA_STATE_IDLE
     * @see #EXTRA_STATE_RINGING
     * @see #EXTRA_STATE_OFFHOOK
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_STATE = PhoneConstants.STATE_KEY;

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_IDLE}.
     */
    public static final String EXTRA_STATE_IDLE = PhoneConstants.State.IDLE.toString();

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_RINGING}.
     */
    public static final String EXTRA_STATE_RINGING = PhoneConstants.State.RINGING.toString();

    /**
     * Value used with {@link #EXTRA_STATE} corresponding to
     * {@link #CALL_STATE_OFFHOOK}.
     */
    public static final String EXTRA_STATE_OFFHOOK = PhoneConstants.State.OFFHOOK.toString();

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the incoming phone number.
     * Only valid when the new call state is RINGING.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";

    /**
     * Broadcast intent action indicating that a precise call state
     * (cellular) on the device has changed.
     *
     * <p>
     * The {@link #EXTRA_RINGING_CALL_STATE} extra indicates the ringing call state.
     * The {@link #EXTRA_FOREGROUND_CALL_STATE} extra indicates the foreground call state.
     * The {@link #EXTRA_BACKGROUND_CALL_STATE} extra indicates the background call state.
     * The {@link #EXTRA_DISCONNECT_CAUSE} extra indicates the disconnect cause.
     * The {@link #EXTRA_PRECISE_DISCONNECT_CAUSE} extra indicates the precise disconnect cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_RINGING_CALL_STATE
     * @see #EXTRA_FOREGROUND_CALL_STATE
     * @see #EXTRA_BACKGROUND_CALL_STATE
     * @see #EXTRA_DISCONNECT_CAUSE
     * @see #EXTRA_PRECISE_DISCONNECT_CAUSE
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_CALL_STATE_CHANGED =
            "android.intent.action.PRECISE_CALL_STATE";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the state of the current ringing call.
     *
     * @see PreciseCallState#PRECISE_CALL_STATE_NOT_VALID
     * @see PreciseCallState#PRECISE_CALL_STATE_IDLE
     * @see PreciseCallState#PRECISE_CALL_STATE_ACTIVE
     * @see PreciseCallState#PRECISE_CALL_STATE_HOLDING
     * @see PreciseCallState#PRECISE_CALL_STATE_DIALING
     * @see PreciseCallState#PRECISE_CALL_STATE_ALERTING
     * @see PreciseCallState#PRECISE_CALL_STATE_INCOMING
     * @see PreciseCallState#PRECISE_CALL_STATE_WAITING
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTED
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_RINGING_CALL_STATE = "ringing_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the state of the current foreground call.
     *
     * @see PreciseCallState#PRECISE_CALL_STATE_NOT_VALID
     * @see PreciseCallState#PRECISE_CALL_STATE_IDLE
     * @see PreciseCallState#PRECISE_CALL_STATE_ACTIVE
     * @see PreciseCallState#PRECISE_CALL_STATE_HOLDING
     * @see PreciseCallState#PRECISE_CALL_STATE_DIALING
     * @see PreciseCallState#PRECISE_CALL_STATE_ALERTING
     * @see PreciseCallState#PRECISE_CALL_STATE_INCOMING
     * @see PreciseCallState#PRECISE_CALL_STATE_WAITING
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTED
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_FOREGROUND_CALL_STATE = "foreground_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the state of the current background call.
     *
     * @see PreciseCallState#PRECISE_CALL_STATE_NOT_VALID
     * @see PreciseCallState#PRECISE_CALL_STATE_IDLE
     * @see PreciseCallState#PRECISE_CALL_STATE_ACTIVE
     * @see PreciseCallState#PRECISE_CALL_STATE_HOLDING
     * @see PreciseCallState#PRECISE_CALL_STATE_DIALING
     * @see PreciseCallState#PRECISE_CALL_STATE_ALERTING
     * @see PreciseCallState#PRECISE_CALL_STATE_INCOMING
     * @see PreciseCallState#PRECISE_CALL_STATE_WAITING
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTED
     * @see PreciseCallState#PRECISE_CALL_STATE_DISCONNECTING
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_BACKGROUND_CALL_STATE = "background_state";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the disconnect cause.
     *
     * @see DisconnectCause
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_DISCONNECT_CAUSE = "disconnect_cause";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast
     * for an integer containing the disconnect cause provided by the RIL.
     *
     * @see PreciseDisconnectCause
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_PRECISE_DISCONNECT_CAUSE = "precise_disconnect_cause";

    /**
     * Broadcast intent action indicating a data connection has changed,
     * providing precise information about the connection.
     *
     * <p>
     * The {@link #EXTRA_DATA_STATE} extra indicates the connection state.
     * The {@link #EXTRA_DATA_NETWORK_TYPE} extra indicates the connection network type.
     * The {@link #EXTRA_DATA_APN_TYPE} extra indicates the APN type.
     * The {@link #EXTRA_DATA_APN} extra indicates the APN.
     * The {@link #EXTRA_DATA_CHANGE_REASON} extra indicates the connection change reason.
     * The {@link #EXTRA_DATA_IFACE_PROPERTIES} extra indicates the connection interface.
     * The {@link #EXTRA_DATA_FAILURE_CAUSE} extra indicates the connection fail cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_DATA_STATE
     * @see #EXTRA_DATA_NETWORK_TYPE
     * @see #EXTRA_DATA_APN_TYPE
     * @see #EXTRA_DATA_APN
     * @see #EXTRA_DATA_CHANGE_REASON
     * @see #EXTRA_DATA_IFACE
     * @see #EXTRA_DATA_FAILURE_CAUSE
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED =
            "android.intent.action.PRECISE_DATA_CONNECTION_STATE_CHANGED";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an integer containing the state of the current data connection.
     *
     * @see TelephonyManager#DATA_UNKNOWN
     * @see TelephonyManager#DATA_DISCONNECTED
     * @see TelephonyManager#DATA_CONNECTING
     * @see TelephonyManager#DATA_CONNECTED
     * @see TelephonyManager#DATA_SUSPENDED
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_STATE = PhoneConstants.STATE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an integer containing the network type.
     *
     * @see TelephonyManager#NETWORK_TYPE_UNKNOWN
     * @see TelephonyManager#NETWORK_TYPE_GPRS
     * @see TelephonyManager#NETWORK_TYPE_EDGE
     * @see TelephonyManager#NETWORK_TYPE_UMTS
     * @see TelephonyManager#NETWORK_TYPE_CDMA
     * @see TelephonyManager#NETWORK_TYPE_EVDO_0
     * @see TelephonyManager#NETWORK_TYPE_EVDO_A
     * @see TelephonyManager#NETWORK_TYPE_1xRTT
     * @see TelephonyManager#NETWORK_TYPE_HSDPA
     * @see TelephonyManager#NETWORK_TYPE_HSUPA
     * @see TelephonyManager#NETWORK_TYPE_HSPA
     * @see TelephonyManager#NETWORK_TYPE_IDEN
     * @see TelephonyManager#NETWORK_TYPE_EVDO_B
     * @see TelephonyManager#NETWORK_TYPE_LTE
     * @see TelephonyManager#NETWORK_TYPE_EHRPD
     * @see TelephonyManager#NETWORK_TYPE_HSPAP
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_NETWORK_TYPE = PhoneConstants.DATA_NETWORK_TYPE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String containing the data APN type.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_APN_TYPE = PhoneConstants.DATA_APN_TYPE_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String containing the data APN.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_APN = PhoneConstants.DATA_APN_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String representation of the change reason.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_CHANGE_REASON = PhoneConstants.STATE_CHANGE_REASON_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for an String representation of the data interface.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_LINK_PROPERTIES_KEY = PhoneConstants.DATA_LINK_PROPERTIES_KEY;

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED} broadcast
     * for the data connection fail cause.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String name)}.
     *
     * @hide
     */
    public static final String EXTRA_DATA_FAILURE_CAUSE = PhoneConstants.DATA_FAILURE_CAUSE_KEY;

    /**
     * Response codes for sim activation. Activation completed successfully.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_RESULT_COMPLETE = 0;
    /**
     * Response codes for sim activation. Activation not supported (device has no SIM).
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_RESULT_NOT_SUPPORTED = 1;
    /**
     * Response codes for sim activation. Activation is in progress.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_RESULT_IN_PROGRESS = 2;
    /**
     * Response codes for sim activation. Activation failed to complete.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_RESULT_FAILED = 3;
    /**
     * Response codes for sim activation. Activation canceled by user.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_RESULT_CANCELED = 4;

    /* Visual voicemail protocols */

    /**
     * The OMTP protocol.
     */
    public static final String VVM_TYPE_OMTP = "vvm_type_omtp";

    /**
     * A flavor of OMTP protocol with a different mobile originated (MO) format
     */
    public static final String VVM_TYPE_CVVM = "vvm_type_cvvm";

    //
    //
    // Device Info
    //
    //

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getDeviceSoftwareVersion() {
        return getDeviceSoftwareVersion(getDefaultSim());
    }

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param slotId of which deviceID is returned
     */
    /** {@hide} */
    public String getDeviceSoftwareVersion(int slotId) {
        // FIXME methods taking slot id should not use subscription, instead us Uicc directly
        int[] subId = SubscriptionManager.getSubId(slotId);
        if (subId == null || subId.length == 0) {
            return null;
        }
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getDeviceSvnUsingSubId(subId[0], mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the unique device ID, for example, the IMEI for GSM and the MEID
     * or ESN for CDMA phones. Return null if device ID is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getDeviceId() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getDeviceId(mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the unique device ID of a subscription, for example, the IMEI for
     * GSM and the MEID for CDMA phones. Return null if device ID is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param slotId of which deviceID is returned
     */
    public String getDeviceId(int slotId) {
        // FIXME this assumes phoneId == slotId
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getDeviceIdForPhone(slotId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the IMEI. Return null if IMEI is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public String getImei() {
        return getImei(getDefaultSim());
    }

    /**
     * Returns the IMEI. Return null if IMEI is not available.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param slotId of which deviceID is returned
     */
    /** {@hide} */
    public String getImei(int slotId) {
        int[] subId = SubscriptionManager.getSubId(slotId);
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getImeiForSubscriber(subId[0], mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the NAI. Return null if NAI is not available.
     *
     */
    /** {@hide}*/
    public String getNai() {
        return getNai(getDefaultSim());
    }

    /**
     * Returns the NAI. Return null if NAI is not available.
     *
     *  @param slotId of which Nai is returned
     */
    /** {@hide}*/
    public String getNai(int slotId) {
        int[] subId = SubscriptionManager.getSubId(slotId);
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            String nai = info.getNaiForSubscriber(subId[0], mContext.getOpPackageName());
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Rlog.v(TAG, "Nai = " + nai);
            }
            return nai;
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the current location of the device.
     *<p>
     * If there is only one radio in the device and that radio has an LTE connection,
     * this method will return null. The implementation must not to try add LTE
     * identifiers into the existing cdma/gsm classes.
     *<p>
     * In the future this call will be deprecated.
     *<p>
     * @return Current location of the device or null if not available.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION}.
     */
    public CellLocation getCellLocation() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                Rlog.d(TAG, "getCellLocation returning null because telephony is null");
                return null;
            }
            Bundle bundle = telephony.getCellLocation(mContext.getOpPackageName());
            if (bundle.isEmpty()) {
                Rlog.d(TAG, "getCellLocation returning null because bundle is empty");
                return null;
            }
            CellLocation cl = CellLocation.newFromBundle(bundle);
            if (cl.isEmpty()) {
                Rlog.d(TAG, "getCellLocation returning null because CellLocation is empty");
                return null;
            }
            return cl;
        } catch (RemoteException ex) {
            Rlog.d(TAG, "getCellLocation returning null due to RemoteException " + ex);
            return null;
        } catch (NullPointerException ex) {
            Rlog.d(TAG, "getCellLocation returning null due to NullPointerException " + ex);
            return null;
        }
    }

    /**
     * Enables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @hide
     */
    public void enableLocationUpdates() {
            enableLocationUpdates(getDefaultSubscription());
    }

    /**
     * Enables location update notifications for a subscription.
     * {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @param subId for which the location updates are enabled
     */
    /** @hide */
    public void enableLocationUpdates(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.enableLocationUpdatesForSubscriber(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Disables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#CONTROL_LOCATION_UPDATES
     * CONTROL_LOCATION_UPDATES}
     *
     * @hide
     */
    public void disableLocationUpdates() {
            disableLocationUpdates(getDefaultSubscription());
    }

    /** @hide */
    public void disableLocationUpdates(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.disableLocationUpdatesForSubscriber(subId);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the neighboring cell information of the device.
     *
     * @return List of NeighboringCellInfo or null if info unavailable.
     *
     * <p>Requires Permission:
     * (@link android.Manifest.permission#ACCESS_COARSE_UPDATES}
     *
     * @deprecated Use (@link getAllCellInfo} which returns a superset of the information
     *             from NeighboringCellInfo.
     */
    @Deprecated
    public List<NeighboringCellInfo> getNeighboringCellInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getNeighboringCellInfo(mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** No phone radio. */
    public static final int PHONE_TYPE_NONE = PhoneConstants.PHONE_TYPE_NONE;
    /** Phone radio is GSM. */
    public static final int PHONE_TYPE_GSM = PhoneConstants.PHONE_TYPE_GSM;
    /** Phone radio is CDMA. */
    public static final int PHONE_TYPE_CDMA = PhoneConstants.PHONE_TYPE_CDMA;
    /** Phone is via SIP. */
    public static final int PHONE_TYPE_SIP = PhoneConstants.PHONE_TYPE_SIP;

    /**
     * Returns the current phone type.
     * TODO: This is a last minute change and hence hidden.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     * @see #PHONE_TYPE_SIP
     *
     * {@hide}
     */
    @SystemApi
    public int getCurrentPhoneType() {
        return getCurrentPhoneType(getDefaultSubscription());
    }

    /**
     * Returns a constant indicating the device phone type for a subscription.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     *
     * @param subId for which phone type is returned
     */
    /** {@hide} */
    @SystemApi
    public int getCurrentPhoneType(int subId) {
        int phoneId;
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // if we don't have any sims, we don't have subscriptions, but we
            // still may want to know what type of phone we've got.
            phoneId = 0;
        } else {
            phoneId = SubscriptionManager.getPhoneId(subId);
        }
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return telephony.getActivePhoneTypeForSubscriber(subId);
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return getPhoneTypeFromProperty(phoneId);
            }
        } catch (RemoteException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(phoneId);
        } catch (NullPointerException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(phoneId);
        }
    }

    /**
     * Returns a constant indicating the device phone type.  This
     * indicates the type of radio used to transmit voice calls.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     * @see #PHONE_TYPE_SIP
     */
    public int getPhoneType() {
        if (!isVoiceCapable()) {
            return PHONE_TYPE_NONE;
        }
        return getCurrentPhoneType();
    }

    private int getPhoneTypeFromProperty() {
        return getPhoneTypeFromProperty(getDefaultPhone());
    }

    /** {@hide} */
    private int getPhoneTypeFromProperty(int phoneId) {
        String type = getTelephonyProperty(phoneId,
                TelephonyProperties.CURRENT_ACTIVE_PHONE, null);
        if (type == null || type.equals("")) {
            return getPhoneTypeFromNetworkType(phoneId);
        }
        return Integer.parseInt(type);
    }

    private int getPhoneTypeFromNetworkType() {
        return getPhoneTypeFromNetworkType(getDefaultPhone());
    }

    /** {@hide} */
    private int getPhoneTypeFromNetworkType(int phoneId) {
        // When the system property CURRENT_ACTIVE_PHONE, has not been set,
        // use the system property for default network type.
        // This is a fail safe, and can only happen at first boot.
        String mode = getTelephonyProperty(phoneId, "ro.telephony.default_network", null);
        if (mode != null) {
            return TelephonyManager.getPhoneType(Integer.parseInt(mode));
        }
        return TelephonyManager.PHONE_TYPE_NONE;
    }

    /**
     * This function returns the type of the phone, depending
     * on the network mode.
     *
     * @param networkMode
     * @return Phone Type
     *
     * @hide
     */
    public static int getPhoneType(int networkMode) {
        switch(networkMode) {
        case RILConstants.NETWORK_MODE_CDMA:
        case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
        case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
            return PhoneConstants.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_WCDMA_PREF:
        case RILConstants.NETWORK_MODE_GSM_ONLY:
        case RILConstants.NETWORK_MODE_WCDMA_ONLY:
        case RILConstants.NETWORK_MODE_GSM_UMTS:
        case RILConstants.NETWORK_MODE_LTE_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_TDSCDMA_ONLY:
        case RILConstants.NETWORK_MODE_TDSCDMA_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA:
        case RILConstants.NETWORK_MODE_TDSCDMA_GSM:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM:
        case RILConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
            return PhoneConstants.PHONE_TYPE_GSM;

        // Use CDMA Phone for the global mode including CDMA
        case RILConstants.NETWORK_MODE_GLOBAL:
        case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
        case RILConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
            return PhoneConstants.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_LTE_ONLY:
            if (getLteOnCdmaModeStatic() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                return PhoneConstants.PHONE_TYPE_CDMA;
            } else {
                return PhoneConstants.PHONE_TYPE_GSM;
            }
        default:
            return PhoneConstants.PHONE_TYPE_GSM;
        }
    }

    /**
     * The contents of the /proc/cmdline file
     */
    private static String getProcCmdLine()
    {
        String cmdline = "";
        FileInputStream is = null;
        try {
            is = new FileInputStream("/proc/cmdline");
            byte [] buffer = new byte[2048];
            int count = is.read(buffer);
            if (count > 0) {
                cmdline = new String(buffer, 0, count);
            }
        } catch (IOException e) {
            Rlog.d(TAG, "No /proc/cmdline exception=" + e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
        }
        Rlog.d(TAG, "/proc/cmdline=" + cmdline);
        return cmdline;
    }

    /** Kernel command line */
    private static final String sKernelCmdLine = getProcCmdLine();

    /** Pattern for selecting the product type from the kernel command line */
    private static final Pattern sProductTypePattern =
        Pattern.compile("\\sproduct_type\\s*=\\s*(\\w+)");

    /** The ProductType used for LTE on CDMA devices */
    private static final String sLteOnCdmaProductType =
        SystemProperties.get(TelephonyProperties.PROPERTY_LTE_ON_CDMA_PRODUCT_TYPE, "");

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     * @hide
     */
    public static int getLteOnCdmaModeStatic() {
        int retVal;
        int curVal;
        String productType = "";

        curVal = SystemProperties.getInt(TelephonyProperties.PROPERTY_LTE_ON_CDMA_DEVICE,
                    PhoneConstants.LTE_ON_CDMA_UNKNOWN);
        retVal = curVal;
        if (retVal == PhoneConstants.LTE_ON_CDMA_UNKNOWN) {
            Matcher matcher = sProductTypePattern.matcher(sKernelCmdLine);
            if (matcher.find()) {
                productType = matcher.group(1);
                if (sLteOnCdmaProductType.equals(productType)) {
                    retVal = PhoneConstants.LTE_ON_CDMA_TRUE;
                } else {
                    retVal = PhoneConstants.LTE_ON_CDMA_FALSE;
                }
            } else {
                retVal = PhoneConstants.LTE_ON_CDMA_FALSE;
            }
        }

        Rlog.d(TAG, "getLteOnCdmaMode=" + retVal + " curVal=" + curVal +
                " product_type='" + productType +
                "' lteOnCdmaProductType='" + sLteOnCdmaProductType + "'");
        return retVal;
    }

    //
    //
    // Current Network
    //
    //

    /**
     * Returns the alphabetic name of current registered operator.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkOperatorName() {
        return getNetworkOperatorName(getDefaultSubscription());
    }

    /**
     * Returns the alphabetic name of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     * @param subId
     */
    /** {@hide} */
    public String getNetworkOperatorName(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ALPHA, "");
    }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkOperator() {
        return getNetworkOperatorForPhone(getDefaultPhone());
    }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param subId
     */
    /** {@hide} */
   public String getNetworkOperatorForSubscription(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getNetworkOperatorForPhone(phoneId);
     }

    /**
     * Returns the numeric name (MCC+MNC) of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param phoneId
     * @hide
     **/
   public String getNetworkOperatorForPhone(int phoneId) {
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, "");
     }

    /**
     * Returns true if the device is considered roaming on the current
     * network, for GSM purposes.
     * <p>
     * Availability: Only when user registered to a network.
     */
    public boolean isNetworkRoaming() {
        return isNetworkRoaming(getDefaultSubscription());
    }

    /**
     * Returns true if the device is considered roaming on the current
     * network for a subscription.
     * <p>
     * Availability: Only when user registered to a network.
     *
     * @param subId
     */
    /** {@hide} */
    public boolean isNetworkRoaming(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return Boolean.parseBoolean(getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_OPERATOR_ISROAMING, null));
    }

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     */
    public String getNetworkCountryIso() {
        return getNetworkCountryIsoForPhone(getDefaultPhone());
    }

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code) of a subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param subId for which Network CountryIso is returned
     */
    /** {@hide} */
    public String getNetworkCountryIsoForSubscription(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getNetworkCountryIsoForPhone(phoneId);
    }

    /**
     * Returns the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code) of a subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     *
     * @param phoneId for which Network CountryIso is returned
     */
    /** {@hide} */
    public String getNetworkCountryIsoForPhone(int phoneId) {
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
    }

    /** Network type is unknown */
    public static final int NETWORK_TYPE_UNKNOWN = 0;
    /** Current network is GPRS */
    public static final int NETWORK_TYPE_GPRS = 1;
    /** Current network is EDGE */
    public static final int NETWORK_TYPE_EDGE = 2;
    /** Current network is UMTS */
    public static final int NETWORK_TYPE_UMTS = 3;
    /** Current network is CDMA: Either IS95A or IS95B*/
    public static final int NETWORK_TYPE_CDMA = 4;
    /** Current network is EVDO revision 0*/
    public static final int NETWORK_TYPE_EVDO_0 = 5;
    /** Current network is EVDO revision A*/
    public static final int NETWORK_TYPE_EVDO_A = 6;
    /** Current network is 1xRTT*/
    public static final int NETWORK_TYPE_1xRTT = 7;
    /** Current network is HSDPA */
    public static final int NETWORK_TYPE_HSDPA = 8;
    /** Current network is HSUPA */
    public static final int NETWORK_TYPE_HSUPA = 9;
    /** Current network is HSPA */
    public static final int NETWORK_TYPE_HSPA = 10;
    /** Current network is iDen */
    public static final int NETWORK_TYPE_IDEN = 11;
    /** Current network is EVDO revision B*/
    public static final int NETWORK_TYPE_EVDO_B = 12;
    /** Current network is LTE */
    public static final int NETWORK_TYPE_LTE = 13;
    /** Current network is eHRPD */
    public static final int NETWORK_TYPE_EHRPD = 14;
    /** Current network is HSPA+ */
    public static final int NETWORK_TYPE_HSPAP = 15;
    /** Current network is GSM {@hide} */
    public static final int NETWORK_TYPE_GSM = 16;
     /** Current network is TD_SCDMA {@hide} */
    public static final int NETWORK_TYPE_TD_SCDMA = 17;
   /** Current network is IWLAN {@hide} */
    public static final int NETWORK_TYPE_IWLAN = 18;

    /**
     * @return the NETWORK_TYPE_xxxx for current data connection.
     */
    public int getNetworkType() {
       try {
           ITelephony telephony = getITelephony();
           if (telephony != null) {
               return telephony.getNetworkType();
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_UNKNOWN;
            }
        } catch(RemoteException ex) {
            // This shouldn't happen in the normal case
            return NETWORK_TYPE_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return NETWORK_TYPE_UNKNOWN;
        }
    }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for a subscription.
     * @return the network type
     *
     * @param subId for which network type is returned
     *
     * @see #NETWORK_TYPE_UNKNOWN
     * @see #NETWORK_TYPE_GPRS
     * @see #NETWORK_TYPE_EDGE
     * @see #NETWORK_TYPE_UMTS
     * @see #NETWORK_TYPE_HSDPA
     * @see #NETWORK_TYPE_HSUPA
     * @see #NETWORK_TYPE_HSPA
     * @see #NETWORK_TYPE_CDMA
     * @see #NETWORK_TYPE_EVDO_0
     * @see #NETWORK_TYPE_EVDO_A
     * @see #NETWORK_TYPE_EVDO_B
     * @see #NETWORK_TYPE_1xRTT
     * @see #NETWORK_TYPE_IDEN
     * @see #NETWORK_TYPE_LTE
     * @see #NETWORK_TYPE_EHRPD
     * @see #NETWORK_TYPE_HSPAP
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
   public int getNetworkType(int subId) {
       try {
           ITelephony telephony = getITelephony();
           if (telephony != null) {
               return telephony.getNetworkTypeForSubscriber(subId, getOpPackageName());
           } else {
               // This can happen when the ITelephony interface is not up yet.
               return NETWORK_TYPE_UNKNOWN;
           }
       } catch(RemoteException ex) {
           // This shouldn't happen in the normal case
           return NETWORK_TYPE_UNKNOWN;
       } catch (NullPointerException ex) {
           // This could happen before phone restarts due to crashing
           return NETWORK_TYPE_UNKNOWN;
       }
   }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for data transmission.
     * @return the network type
     *
     * @see #NETWORK_TYPE_UNKNOWN
     * @see #NETWORK_TYPE_GPRS
     * @see #NETWORK_TYPE_EDGE
     * @see #NETWORK_TYPE_UMTS
     * @see #NETWORK_TYPE_HSDPA
     * @see #NETWORK_TYPE_HSUPA
     * @see #NETWORK_TYPE_HSPA
     * @see #NETWORK_TYPE_CDMA
     * @see #NETWORK_TYPE_EVDO_0
     * @see #NETWORK_TYPE_EVDO_A
     * @see #NETWORK_TYPE_EVDO_B
     * @see #NETWORK_TYPE_1xRTT
     * @see #NETWORK_TYPE_IDEN
     * @see #NETWORK_TYPE_LTE
     * @see #NETWORK_TYPE_EHRPD
     * @see #NETWORK_TYPE_HSPAP
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public int getDataNetworkType() {
        return getDataNetworkType(getDefaultSubscription());
    }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for data transmission for a subscription
     * @return the network type
     *
     * @param subId for which network type is returned
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public int getDataNetworkType(int subId) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getDataNetworkTypeForSubscriber(subId, getOpPackageName());
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_UNKNOWN;
            }
        } catch(RemoteException ex) {
            // This shouldn't happen in the normal case
            return NETWORK_TYPE_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return NETWORK_TYPE_UNKNOWN;
        }
    }

    /**
     * Returns the NETWORK_TYPE_xxxx for voice
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public int getVoiceNetworkType() {
        return getVoiceNetworkType(getDefaultSubscription());
    }

    /**
     * Returns the NETWORK_TYPE_xxxx for voice for a subId
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public int getVoiceNetworkType(int subId) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getVoiceNetworkTypeForSubscriber(subId, getOpPackageName());
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_UNKNOWN;
            }
        } catch(RemoteException ex) {
            // This shouldn't happen in the normal case
            return NETWORK_TYPE_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return NETWORK_TYPE_UNKNOWN;
        }
    }

    /** Unknown network class. {@hide} */
    public static final int NETWORK_CLASS_UNKNOWN = 0;
    /** Class of broadly defined "2G" networks. {@hide} */
    public static final int NETWORK_CLASS_2_G = 1;
    /** Class of broadly defined "3G" networks. {@hide} */
    public static final int NETWORK_CLASS_3_G = 2;
    /** Class of broadly defined "4G" networks. {@hide} */
    public static final int NETWORK_CLASS_4_G = 3;

    /**
     * Return general class of network type, such as "3G" or "4G". In cases
     * where classification is contentious, this method is conservative.
     *
     * @hide
     */
    public static int getNetworkClass(int networkType) {
        switch (networkType) {
            case NETWORK_TYPE_GPRS:
            case NETWORK_TYPE_GSM:
            case NETWORK_TYPE_EDGE:
            case NETWORK_TYPE_CDMA:
            case NETWORK_TYPE_1xRTT:
            case NETWORK_TYPE_IDEN:
                return NETWORK_CLASS_2_G;
            case NETWORK_TYPE_UMTS:
            case NETWORK_TYPE_EVDO_0:
            case NETWORK_TYPE_EVDO_A:
            case NETWORK_TYPE_HSDPA:
            case NETWORK_TYPE_HSUPA:
            case NETWORK_TYPE_HSPA:
            case NETWORK_TYPE_EVDO_B:
            case NETWORK_TYPE_EHRPD:
            case NETWORK_TYPE_HSPAP:
            case NETWORK_TYPE_TD_SCDMA:
                return NETWORK_CLASS_3_G;
            case NETWORK_TYPE_LTE:
            case NETWORK_TYPE_IWLAN:
                return NETWORK_CLASS_4_G;
            default:
                return NETWORK_CLASS_UNKNOWN;
        }
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @return the name of the radio technology
     *
     * @hide pending API council review
     */
    public String getNetworkTypeName() {
        return getNetworkTypeName(getNetworkType());
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @param subId for which network type is returned
     * @return the name of the radio technology
     *
     */
    /** {@hide} */
    public static String getNetworkTypeName(int type) {
        switch (type) {
            case NETWORK_TYPE_GPRS:
                return "GPRS";
            case NETWORK_TYPE_EDGE:
                return "EDGE";
            case NETWORK_TYPE_UMTS:
                return "UMTS";
            case NETWORK_TYPE_HSDPA:
                return "HSDPA";
            case NETWORK_TYPE_HSUPA:
                return "HSUPA";
            case NETWORK_TYPE_HSPA:
                return "HSPA";
            case NETWORK_TYPE_CDMA:
                return "CDMA";
            case NETWORK_TYPE_EVDO_0:
                return "CDMA - EvDo rev. 0";
            case NETWORK_TYPE_EVDO_A:
                return "CDMA - EvDo rev. A";
            case NETWORK_TYPE_EVDO_B:
                return "CDMA - EvDo rev. B";
            case NETWORK_TYPE_1xRTT:
                return "CDMA - 1xRTT";
            case NETWORK_TYPE_LTE:
                return "LTE";
            case NETWORK_TYPE_EHRPD:
                return "CDMA - eHRPD";
            case NETWORK_TYPE_IDEN:
                return "iDEN";
            case NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case NETWORK_TYPE_GSM:
                return "GSM";
            case NETWORK_TYPE_TD_SCDMA:
                return "TD_SCDMA";
            case NETWORK_TYPE_IWLAN:
                return "IWLAN";
            default:
                return "UNKNOWN";
        }
    }

    //
    //
    // SIM Card
    //
    //

    /**
     * SIM card state: Unknown. Signifies that the SIM is in transition
     * between states. For example, when the user inputs the SIM pin
     * under PIN_REQUIRED state, a query for sim status returns
     * this state before turning to SIM_STATE_READY.
     *
     * These are the ordinal value of IccCardConstants.State.
     */
    public static final int SIM_STATE_UNKNOWN = 0;
    /** SIM card state: no SIM card is available in the device */
    public static final int SIM_STATE_ABSENT = 1;
    /** SIM card state: Locked: requires the user's SIM PIN to unlock */
    public static final int SIM_STATE_PIN_REQUIRED = 2;
    /** SIM card state: Locked: requires the user's SIM PUK to unlock */
    public static final int SIM_STATE_PUK_REQUIRED = 3;
    /** SIM card state: Locked: requires a network PIN to unlock */
    public static final int SIM_STATE_NETWORK_LOCKED = 4;
    /** SIM card state: Ready */
    public static final int SIM_STATE_READY = 5;
    /** SIM card state: SIM Card is NOT READY
     *@hide
     */
    public static final int SIM_STATE_NOT_READY = 6;
    /** SIM card state: SIM Card Error, permanently disabled
     *@hide
     */
    public static final int SIM_STATE_PERM_DISABLED = 7;
    /** SIM card state: SIM Card Error, present but faulty
     *@hide
     */
    public static final int SIM_STATE_CARD_IO_ERROR = 8;

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        return hasIccCard(getDefaultSim());
    }

    /**
     * @return true if a ICC card is present for a subscription
     *
     * @param slotId for which icc card presence is checked
     */
    /** {@hide} */
    // FIXME Input argument slotId should be of type int
    public boolean hasIccCard(int slotId) {

        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return false;
            return telephony.hasIccCardUsingSlotId(slotId);
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return false;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return false;
        }
    }

    /**
     * Returns a constant indicating the state of the default SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_READY
     * @see #SIM_STATE_NOT_READY
     * @see #SIM_STATE_PERM_DISABLED
     * @see #SIM_STATE_CARD_IO_ERROR
     */
    public int getSimState() {
        int slotIdx = getDefaultSim();
        // slotIdx may be invalid due to sim being absent. In that case query all slots to get
        // sim state
        if (slotIdx < 0) {
            // query for all slots and return absent if all sim states are absent, otherwise
            // return unknown
            for (int i = 0; i < getPhoneCount(); i++) {
                int simState = getSimState(i);
                if (simState != SIM_STATE_ABSENT) {
                    Rlog.d(TAG, "getSimState: default sim:" + slotIdx + ", sim state for " +
                            "slotIdx=" + i + " is " + simState + ", return state as unknown");
                    return SIM_STATE_UNKNOWN;
                }
            }
            Rlog.d(TAG, "getSimState: default sim:" + slotIdx + ", all SIMs absent, return " +
                    "state as absent");
            return SIM_STATE_ABSENT;
        }
        return getSimState(slotIdx);
    }

    /**
     * Returns a constant indicating the state of the device SIM card in a slot.
     *
     * @param slotIdx
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_READY
     * @see #SIM_STATE_NOT_READY
     * @see #SIM_STATE_PERM_DISABLED
     * @see #SIM_STATE_CARD_IO_ERROR
     */
    /** {@hide} */
    public int getSimState(int slotIdx) {
        int simState = SubscriptionManager.getSimStateForSlotIdx(slotIdx);
        return simState;
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     */
    public String getSimOperator() {
        return getSimOperatorNumeric();
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     *
     * @param subId for which SimOperator is returned
     * @hide
     */
    public String getSimOperator(int subId) {
        return getSimOperatorNumericForSubscription(subId);
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     * @hide
     */
    public String getSimOperatorNumeric() {
        int subId = SubscriptionManager.getDefaultDataSubId();
        if (!SubscriptionManager.isUsableSubIdValue(subId)) {
            subId = SubscriptionManager.getDefaultSmsSubId();
            if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                subId = SubscriptionManager.getDefaultVoiceSubId();
                if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                    subId = SubscriptionManager.getDefaultSubId();
                }
            }
        }
        return getSimOperatorNumericForSubscription(subId);
    }

    /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM for a particular subscription. 5 or 6 decimal digits.
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     *
     * @param subId for which SimOperator is returned
     * @hide
     */
    public String getSimOperatorNumericForSubscription(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimOperatorNumericForPhone(phoneId);
    }

   /**
     * Returns the MCC+MNC (mobile country code + mobile network code) of the
     * provider of the SIM for a particular subscription. 5 or 6 decimal digits.
     * <p>
     *
     * @param phoneId for which SimOperator is returned
     * @hide
     */
    public String getSimOperatorNumericForPhone(int phoneId) {
        return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
    }

    /**
     * Returns the Service Provider Name (SPN).
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     */
    public String getSimOperatorName() {
        return getSimOperatorNameForPhone(getDefaultPhone());
    }

    /**
     * Returns the Service Provider Name (SPN).
     * <p>
     * Availability: SIM state must be {@link #SIM_STATE_READY}
     *
     * @see #getSimState
     *
     * @param subId for which SimOperatorName is returned
     * @hide
     */
    public String getSimOperatorNameForSubscription(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimOperatorNameForPhone(phoneId);
    }

    /**
     * Returns the Service Provider Name (SPN).
     *
     * @hide
     */
    public String getSimOperatorNameForPhone(int phoneId) {
         return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "");
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     */
    public String getSimCountryIso() {
        return getSimCountryIsoForPhone(getDefaultPhone());
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @param subId for which SimCountryIso is returned
     *
     * @hide
     */
    public String getSimCountryIso(int subId) {
        return getSimCountryIsoForSubscription(subId);
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @param subId for which SimCountryIso is returned
     *
     * @hide
     */
    public String getSimCountryIsoForSubscription(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimCountryIsoForPhone(phoneId);
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @hide
     */
    public String getSimCountryIsoForPhone(int phoneId) {
        return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
    }

    /**
     * Returns the serial number of the SIM, if applicable. Return null if it is
     * unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getSimSerialNumber() {
         return getSimSerialNumber(getDefaultSubscription());
    }

    /**
     * Returns the serial number for the given subscription, if applicable. Return null if it is
     * unavailable.
     * <p>
     * @param subId for which Sim Serial number is returned
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public String getSimSerialNumber(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIccSerialNumberForSubscriber(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @hide
     */
    public int getLteOnCdmaMode() {
        return getLteOnCdmaMode(getDefaultSubscription());
    }

    /**
     * Return if the current radio is LTE on CDMA for Subscription. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @param subId for which radio is LTE on CDMA is returned
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public int getLteOnCdmaMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
            return telephony.getLteOnCdmaModeForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            // Assume no ICC card if remote exception which shouldn't happen
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
        }
    }

    //
    //
    // Subscriber Info
    //
    //

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getSubscriberId() {
        return getSubscriberId(getDefaultSubscription());
    }

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone
     * for a subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subId whose subscriber id is returned
     */
    /** {@hide} */
    public String getSubscriberId(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getSubscriberIdForSubscriber(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the Group Identifier Level1 for a GSM phone.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getGroupIdLevel1() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getGroupIdLevel1(mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the Group Identifier Level1 for a GSM phone for a particular subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subscription whose subscriber id is returned
     */
    /** {@hide} */
    public String getGroupIdLevel1(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getGroupIdLevel1ForSubscriber(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *   OR
     *   {@link android.Manifest.permission#READ_SMS}
     * <p>
     * The default SMS app can also use this.
     */
    public String getLine1Number() {
        return getLine1NumberForSubscriber(getDefaultSubscription());
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone for a particular subscription. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *   OR
     *   {@link android.Manifest.permission#READ_SMS}
     * <p>
     * The default SMS app can also use this.
     *
     * @param subId whose phone number for line 1 is returned
     */
    /** {@hide} */
    public String getLine1NumberForSubscriber(int subId) {
        String number = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                number = telephony.getLine1NumberForDisplay(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (number != null) {
            return number;
        }
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getLine1NumberForSubscriber(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Set the line 1 phone number string and its alphatag for the current ICCID
     * for display purpose only, for example, displayed in Phone Status. It won't
     * change the actual MSISDN/MDN. To unset alphatag or number, pass in a null
     * value.
     *
     * <p>Requires that the calling app has carrier privileges.
     * @see #hasCarrierPrivileges
     *
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     * @return true if the operation was executed correctly.
     */
    public boolean setLine1NumberForDisplay(String alphaTag, String number) {
        return setLine1NumberForDisplayForSubscriber(getDefaultSubscription(), alphaTag, number);
    }

    /**
     * Set the line 1 phone number string and its alphatag for the current ICCID
     * for display purpose only, for example, displayed in Phone Status. It won't
     * change the actual MSISDN/MDN. To unset alphatag or number, pass in a null
     * value.
     *
     * <p>Requires that the calling app has carrier privileges.
     * @see #hasCarrierPrivileges
     *
     * @param subId the subscriber that the alphatag and dialing number belongs to.
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     * @return true if the operation was executed correctly.
     * @hide
     */
    public boolean setLine1NumberForDisplayForSubscriber(int subId, String alphaTag, String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setLine1NumberForDisplayForSubscriber(subId, alphaTag, number);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     * nobody seems to call this.
     */
    public String getLine1AlphaTag() {
        return getLine1AlphaTagForSubscriber(getDefaultSubscription());
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number
     * for a subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose alphabetic identifier associated with line 1 is returned
     * nobody seems to call this.
     */
    /** {@hide} */
    public String getLine1AlphaTagForSubscriber(int subId) {
        String alphaTag = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                alphaTag = telephony.getLine1AlphaTagForDisplay(subId,
                        getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (alphaTag != null) {
            return alphaTag;
        }
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getLine1AlphaTagForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Return the set of subscriber IDs that should be considered as "merged
     * together" for data usage purposes. This is commonly {@code null} to
     * indicate no merging is required. Any returned subscribers are sorted in a
     * deterministic order.
     *
     * @hide
     */
    public @Nullable String[] getMergedSubscriberIds() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getMergedSubscriberIds(getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @hide
     */
    public String getMsisdn() {
        return getMsisdn(getDefaultSubscription());
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subId for which msisdn is returned
     */
    /** {@hide} */
    public String getMsisdn(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getMsisdnForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getVoiceMailNumber() {
        return getVoiceMailNumber(getDefaultSubscription());
    }

    /**
     * Returns the voice mail number for a subscription.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose voice mail number is returned
     */
    /** {@hide} */
    public String getVoiceMailNumber(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getVoiceMailNumberForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#CALL_PRIVILEGED CALL_PRIVILEGED}
     *
     * @hide
     */
    public String getCompleteVoiceMailNumber() {
        return getCompleteVoiceMailNumber(getDefaultSubscription());
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#CALL_PRIVILEGED CALL_PRIVILEGED}
     *
     * @param subId
     */
    /** {@hide} */
    public String getCompleteVoiceMailNumber(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getCompleteVoiceMailNumberForSubscriber(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Sets the voice mail number.
     *
     * <p>Requires that the calling app has carrier privileges.
     * @see #hasCarrierPrivileges
     *
     * @param alphaTag The alpha tag to display.
     * @param number The voicemail number.
     */
    public boolean setVoiceMailNumber(String alphaTag, String number) {
        return setVoiceMailNumber(getDefaultSubscription(), alphaTag, number);
    }

    /**
     * Sets the voicemail number for the given subscriber.
     *
     * <p>Requires that the calling app has carrier privileges.
     * @see #hasCarrierPrivileges
     *
     * @param subId The subscription id.
     * @param alphaTag The alpha tag to display.
     * @param number The voicemail number.
     */
    /** {@hide} */
    public boolean setVoiceMailNumber(int subId, String alphaTag, String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setVoiceMailNumber(subId, alphaTag, number);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
    }

    /**
     * Returns the voice mail count. Return 0 if unavailable, -1 if there are unread voice messages
     * but the count is unknown.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public int getVoiceMessageCount() {
        return getVoiceMessageCount(getDefaultSubscription());
    }

    /**
     * Returns the voice mail count for a subscription. Return 0 if unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose voice message count is returned
     */
    /** {@hide} */
    public int getVoiceMessageCount(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return 0;
            return telephony.getVoiceMessageCountForSubscriber(subId);
        } catch (RemoteException ex) {
            return 0;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return 0;
        }
    }

    /**
     * Retrieves the alphabetic identifier associated with the voice
     * mail number.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    public String getVoiceMailAlphaTag() {
        return getVoiceMailAlphaTag(getDefaultSubscription());
    }

    /**
     * Retrieves the alphabetic identifier associated with the voice
     * mail number for a subscription.
     * <p>
     * Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param subId whose alphabetic identifier associated with the
     * voice mail number is returned
     */
    /** {@hide} */
    public String getVoiceMailAlphaTag(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getVoiceMailAlphaTagForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS private user identity (IMPI) that was loaded from the ISIM.
     * @return the IMPI, or null if not present or not loaded
     * @hide
     */
    public String getIsimImpi() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIsimImpi();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS home network domain name that was loaded from the ISIM.
     * @return the IMS domain name, or null if not present or not loaded
     * @hide
     */
    public String getIsimDomain() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIsimDomain();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS public user identities (IMPU) that were loaded from the ISIM.
     * @return an array of IMPU strings, with one IMPU per string, or null if
     *      not present or not loaded
     * @hide
     */
    public String[] getIsimImpu() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIsimImpu();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

   /**
    * @hide
    */
    private IPhoneSubInfo getSubscriberInfo() {
        // get it each time because that process crashes a lot
        return IPhoneSubInfo.Stub.asInterface(ServiceManager.getService("iphonesubinfo"));
    }

    /** Device call state: No activity. */
    public static final int CALL_STATE_IDLE = 0;
    /** Device call state: Ringing. A new call arrived and is
     *  ringing or waiting. In the latter case, another call is
     *  already active. */
    public static final int CALL_STATE_RINGING = 1;
    /** Device call state: Off-hook. At least one call exists
      * that is dialing, active, or on hold, and no calls are ringing
      * or waiting. */
    public static final int CALL_STATE_OFFHOOK = 2;

    /**
     * Returns one of the following constants that represents the current state of all
     * phone calls.
     *
     * {@link TelephonyManager#CALL_STATE_RINGING}
     * {@link TelephonyManager#CALL_STATE_OFFHOOK}
     * {@link TelephonyManager#CALL_STATE_IDLE}
     */
    public int getCallState() {
        try {
            ITelecomService telecom = getTelecomService();
            if (telecom != null) {
                return telecom.getCallState();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#getCallState", e);
        }
        return CALL_STATE_IDLE;
    }

    /**
     * Returns a constant indicating the call state (cellular) on the device
     * for a subscription.
     *
     * @param subId whose call state is returned
     */
    /** {@hide} */
    public int getCallState(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return CALL_STATE_IDLE;
            return telephony.getCallStateForSubscriber(subId);
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return CALL_STATE_IDLE;
        } catch (NullPointerException ex) {
          // the phone process is restarting.
          return CALL_STATE_IDLE;
      }
    }

    /** Data connection activity: No traffic. */
    public static final int DATA_ACTIVITY_NONE = 0x00000000;
    /** Data connection activity: Currently receiving IP PPP traffic. */
    public static final int DATA_ACTIVITY_IN = 0x00000001;
    /** Data connection activity: Currently sending IP PPP traffic. */
    public static final int DATA_ACTIVITY_OUT = 0x00000002;
    /** Data connection activity: Currently both sending and receiving
     *  IP PPP traffic. */
    public static final int DATA_ACTIVITY_INOUT = DATA_ACTIVITY_IN | DATA_ACTIVITY_OUT;
    /**
     * Data connection is active, but physical link is down
     */
    public static final int DATA_ACTIVITY_DORMANT = 0x00000004;

    /**
     * Returns a constant indicating the type of activity on a data connection
     * (cellular).
     *
     * @see #DATA_ACTIVITY_NONE
     * @see #DATA_ACTIVITY_IN
     * @see #DATA_ACTIVITY_OUT
     * @see #DATA_ACTIVITY_INOUT
     * @see #DATA_ACTIVITY_DORMANT
     */
    public int getDataActivity() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return DATA_ACTIVITY_NONE;
            return telephony.getDataActivity();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_ACTIVITY_NONE;
        } catch (NullPointerException ex) {
          // the phone process is restarting.
          return DATA_ACTIVITY_NONE;
      }
    }

    /** Data connection state: Unknown.  Used before we know the state.
     * @hide
     */
    public static final int DATA_UNKNOWN        = -1;
    /** Data connection state: Disconnected. IP traffic not available. */
    public static final int DATA_DISCONNECTED   = 0;
    /** Data connection state: Currently setting up a data connection. */
    public static final int DATA_CONNECTING     = 1;
    /** Data connection state: Connected. IP traffic should be available. */
    public static final int DATA_CONNECTED      = 2;
    /** Data connection state: Suspended. The connection is up, but IP
     * traffic is temporarily unavailable. For example, in a 2G network,
     * data activity may be suspended when a voice call arrives. */
    public static final int DATA_SUSPENDED      = 3;

    /**
     * Returns a constant indicating the current data connection state
     * (cellular).
     *
     * @see #DATA_DISCONNECTED
     * @see #DATA_CONNECTING
     * @see #DATA_CONNECTED
     * @see #DATA_SUSPENDED
     */
    public int getDataState() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return DATA_DISCONNECTED;
            return telephony.getDataState();
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_DISCONNECTED;
        } catch (NullPointerException ex) {
            return DATA_DISCONNECTED;
        }
    }

   /**
    * @hide
    */
    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE));
    }

    /**
    * @hide
    */
    private ITelecomService getTelecomService() {
        return ITelecomService.Stub.asInterface(ServiceManager.getService(Context.TELECOM_SERVICE));
    }

    //
    //
    // PhoneStateListener
    //
    //

    /**
     * Registers a listener object to receive notification of changes
     * in specified telephony states.
     * <p>
     * To register a listener, pass a {@link PhoneStateListener}
     * and specify at least one telephony state of interest in
     * the events argument.
     *
     * At registration, and when a specified telephony state
     * changes, the telephony manager invokes the appropriate
     * callback method on the listener object and passes the
     * current (updated) values.
     * <p>
     * To unregister a listener, pass the listener object and set the
     * events argument to
     * {@link PhoneStateListener#LISTEN_NONE LISTEN_NONE} (0).
     *
     * @param listener The {@link PhoneStateListener} object to register
     *                 (or unregister)
     * @param events The telephony state(s) of interest to the listener,
     *               as a bitwise-OR combination of {@link PhoneStateListener}
     *               LISTEN_ flags.
     */
    public void listen(PhoneStateListener listener, int events) {
        if (mContext == null) return;
        try {
            Boolean notifyNow = (getITelephony() != null);
            sRegistry.listenForSubscriber(listener.mSubId, getOpPackageName(),
                    listener.callback, events, notifyNow);
        } catch (RemoteException ex) {
            // system process dead
        } catch (NullPointerException ex) {
            // system process dead
        }
    }

    /**
     * Returns the CDMA ERI icon index to display
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public int getCdmaEriIconIndex() {
        return getCdmaEriIconIndex(getDefaultSubscription());
    }

    /**
     * Returns the CDMA ERI icon index to display for a subscription
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public int getCdmaEriIconIndex(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return -1;
            return telephony.getCdmaEriIconIndexForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return -1;
        } catch (NullPointerException ex) {
            return -1;
        }
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public int getCdmaEriIconMode() {
        return getCdmaEriIconMode(getDefaultSubscription());
    }

    /**
     * Returns the CDMA ERI icon mode for a subscription.
     * 0 - ON
     * 1 - FLASHING
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public int getCdmaEriIconMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return -1;
            return telephony.getCdmaEriIconModeForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return -1;
        } catch (NullPointerException ex) {
            return -1;
        }
    }

    /**
     * Returns the CDMA ERI text,
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @hide
     */
    public String getCdmaEriText() {
        return getCdmaEriText(getDefaultSubscription());
    }

    /**
     * Returns the CDMA ERI text, of a subscription
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     */
    /** {@hide} */
    public String getCdmaEriText(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getCdmaEriTextForSubscriber(subId, getOpPackageName());
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * @return true if the current device is "voice capable".
     * <p>
     * "Voice capable" means that this device supports circuit-switched
     * (i.e. voice) phone calls over the telephony network, and is allowed
     * to display the in-call UI while a cellular voice call is active.
     * This will be false on "data only" devices which can't make voice
     * calls and don't support any in-call UI.
     * <p>
     * Note: the meaning of this flag is subtly different from the
     * PackageManager.FEATURE_TELEPHONY system feature, which is available
     * on any device with a telephony radio, even if the device is
     * data-only.
     */
    public boolean isVoiceCapable() {
        if (mContext == null) return true;
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
    }

    /**
     * @return true if the current device supports sms service.
     * <p>
     * If true, this means that the device supports both sending and
     * receiving sms via the telephony network.
     * <p>
     * Note: Voicemail waiting sms, cell broadcasting sms, and MMS are
     *       disabled when device doesn't support sms.
     */
    public boolean isSmsCapable() {
        if (mContext == null) return true;
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sms_capable);
    }

    /**
     * Returns all observed cell information from all radios on the
     * device including the primary and neighboring cells. This does
     * not cause or change the rate of PhoneStateListner#onCellInfoChanged.
     *<p>
     * The list can include one or more of {@link android.telephony.CellInfoGsm CellInfoGsm},
     * {@link android.telephony.CellInfoCdma CellInfoCdma},
     * {@link android.telephony.CellInfoLte CellInfoLte} and
     * {@link android.telephony.CellInfoWcdma CellInfoWcdma} in any combination.
     * Specifically on devices with multiple radios it is typical to see instances of
     * one or more of any these in the list. In addition 0, 1 or more CellInfo
     * objects may return isRegistered() true.
     *<p>
     * This is preferred over using getCellLocation although for older
     * devices this may return null in which case getCellLocation should
     * be called.
     *<p>
     * @return List of CellInfo or null if info unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}
     */
    public List<CellInfo> getAllCellInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getAllCellInfo(getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Sets the minimum time in milli-seconds between {@link PhoneStateListener#onCellInfoChanged
     * PhoneStateListener.onCellInfoChanged} will be invoked.
     *<p>
     * The default, 0, means invoke onCellInfoChanged when any of the reported
     * information changes. Setting the value to INT_MAX(0x7fffffff) means never issue
     * A onCellInfoChanged.
     *<p>
     * @param rateInMillis the rate
     *
     * @hide
     */
    public void setCellInfoListRate(int rateInMillis) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setCellInfoListRate(rateInMillis);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the MMS user agent.
     */
    public String getMmsUserAgent() {
        if (mContext == null) return null;
        return mContext.getResources().getString(
                com.android.internal.R.string.config_mms_user_agent);
    }

    /**
     * Returns the MMS user agent profile URL.
     */
    public String getMmsUAProfUrl() {
        if (mContext == null) return null;
        return mContext.getResources().getString(
                com.android.internal.R.string.config_mms_user_agent_profile_url);
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @return an IccOpenLogicalChannelResponse object.
     */
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String AID) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccOpenLogicalChannel(AID);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Closes a previously opened logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHC command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param channel is the channel id to be closed as retruned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     */
    public boolean iccCloseLogicalChannel(int channel) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccCloseLogicalChannel(channel);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
    }

    /**
     * Transmit an APDU to the ICC card over a logical channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param channel is the channel id to be closed as returned by a successful
     *            iccOpenLogicalChannel.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    public String iccTransmitApduLogicalChannel(int channel, int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccTransmitApduLogicalChannel(channel, cla,
                    instruction, p1, p2, p3, data);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Transmit an APDU to the ICC card over the basic channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CSIM command.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     */
    public String iccTransmitApduBasicChannel(int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccTransmitApduBasicChannel(cla,
                    instruction, p1, p2, p3, data);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Returns the response APDU for a command APDU sent through SIM_IO.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param fileID
     * @param command
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command.
     * @param filePath
     * @return The APDU response.
     */
    public byte[] iccExchangeSimIO(int fileID, int command, int p1, int p2, int p3,
            String filePath) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccExchangeSimIO(fileID, command, p1, p2, p3, filePath);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Send ENVELOPE to the SIM and return the response.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param content String containing SAT/USAT response in hexadecimal
     *                format starting with command tag. See TS 102 223 for
     *                details.
     * @return The APDU response from the ICC card in hexadecimal format
     *         with the last 4 bytes being the status word. If the command fails,
     *         returns an empty string.
     */
    public String sendEnvelopeWithStatus(String content) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.sendEnvelopeWithStatus(content);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Read one of the NV items defined in com.android.internal.telephony.RadioNVItems.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param itemID the ID of the item to read.
     * @return the NV item as a String, or null on any failure.
     *
     * @hide
     */
    public String nvReadItem(int itemID) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvReadItem(itemID);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvReadItem RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvReadItem NPE", ex);
        }
        return "";
    }

    /**
     * Write one of the NV items defined in com.android.internal.telephony.RadioNVItems.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param itemID the ID of the item to read.
     * @param itemValue the value to write, as a String.
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvWriteItem(int itemID, String itemValue) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvWriteItem(itemID, itemValue);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvWriteItem RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvWriteItem NPE", ex);
        }
        return false;
    }

    /**
     * Update the CDMA Preferred Roaming List (PRL) in the radio NV storage.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param preferredRoamingList byte array containing the new PRL.
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvWriteCdmaPrl(byte[] preferredRoamingList) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvWriteCdmaPrl(preferredRoamingList);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvWriteCdmaPrl RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvWriteCdmaPrl NPE", ex);
        }
        return false;
    }

    /**
     * Perform the specified type of NV config reset. The radio will be taken offline
     * and the device must be rebooted after the operation. Used for device
     * configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param resetType reset type: 1: reload NV reset, 2: erase NV reset, 3: factory NV reset
     * @return true on success; false on any failure.
     *
     * @hide
     */
    public boolean nvResetConfig(int resetType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.nvResetConfig(resetType);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvResetConfig RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvResetConfig NPE", ex);
        }
        return false;
    }

    /**
     * Returns Default subscription.
     */
    private static int getDefaultSubscription() {
        return SubscriptionManager.getDefaultSubId();
    }

    /**
     * Returns Default phone.
     */
    private static int getDefaultPhone() {
        return SubscriptionManager.getPhoneId(SubscriptionManager.getDefaultSubId());
    }

    /** {@hide} */
    public int getDefaultSim() {
        return SubscriptionManager.getSlotId(SubscriptionManager.getDefaultSubId());
    }

    /**
     * Sets the telephony property with the value specified.
     *
     * @hide
     */
    public static void setTelephonyProperty(int phoneId, String property, String value) {
        String propVal = "";
        String p[] = null;
        String prop = SystemProperties.get(property);

        if (value == null) {
            value = "";
        }

        if (prop != null) {
            p = prop.split(",");
        }

        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            Rlog.d(TAG, "setTelephonyProperty: invalid phoneId=" + phoneId +
                    " property=" + property + " value: " + value + " prop=" + prop);
            return;
        }

        for (int i = 0; i < phoneId; i++) {
            String str = "";
            if ((p != null) && (i < p.length)) {
                str = p[i];
            }
            propVal = propVal + str + ",";
        }

        propVal = propVal + value;
        if (p != null) {
            for (int i = phoneId + 1; i < p.length; i++) {
                propVal = propVal + "," + p[i];
            }
        }

        if (property.length() > SystemProperties.PROP_NAME_MAX
                || propVal.length() > SystemProperties.PROP_VALUE_MAX) {
            Rlog.d(TAG, "setTelephonyProperty: property to long phoneId=" + phoneId +
                    " property=" + property + " value: " + value + " propVal=" + propVal);
            return;
        }

        Rlog.d(TAG, "setTelephonyProperty: success phoneId=" + phoneId +
                " property=" + property + " value: " + value + " propVal=" + propVal);
        SystemProperties.set(property, propVal);
    }

    /**
     * Convenience function for retrieving a value from the secure settings
     * value list as an integer.  Note that internally setting values are
     * always stored as strings; this function converts the string to an
     * integer for you.
     * <p>
     * This version does not take a default value.  If the setting has not
     * been set, or the string value is not a number,
     * it throws {@link SettingNotFoundException}.
     *
     * @param cr The ContentResolver to access.
     * @param name The name of the setting to retrieve.
     * @param index The index of the list
     *
     * @throws SettingNotFoundException Thrown if a setting by the given
     * name can't be found or the setting value is not an integer.
     *
     * @return The value at the given index of settings.
     * @hide
     */
    public static int getIntAtIndex(android.content.ContentResolver cr,
            String name, int index)
            throws android.provider.Settings.SettingNotFoundException {
        String v = android.provider.Settings.Global.getString(cr, name);
        if (v != null) {
            String valArray[] = v.split(",");
            if ((index >= 0) && (index < valArray.length) && (valArray[index] != null)) {
                try {
                    return Integer.parseInt(valArray[index]);
                } catch (NumberFormatException e) {
                    //Log.e(TAG, "Exception while parsing Integer: ", e);
                }
            }
        }
        throw new android.provider.Settings.SettingNotFoundException(name);
    }

    /**
     * Convenience function for updating settings value as coma separated
     * integer values. This will either create a new entry in the table if the
     * given name does not exist, or modify the value of the existing row
     * with that name.  Note that internally setting values are always
     * stored as strings, so this function converts the given value to a
     * string before storing it.
     *
     * @param cr The ContentResolver to access.
     * @param name The name of the setting to modify.
     * @param index The index of the list
     * @param value The new value for the setting to be added to the list.
     * @return true if the value was set, false on database errors
     * @hide
     */
    public static boolean putIntAtIndex(android.content.ContentResolver cr,
            String name, int index, int value) {
        String data = "";
        String valArray[] = null;
        String v = android.provider.Settings.Global.getString(cr, name);

        if (index == Integer.MAX_VALUE) {
            throw new RuntimeException("putIntAtIndex index == MAX_VALUE index=" + index);
        }
        if (index < 0) {
            throw new RuntimeException("putIntAtIndex index < 0 index=" + index);
        }
        if (v != null) {
            valArray = v.split(",");
        }

        // Copy the elements from valArray till index
        for (int i = 0; i < index; i++) {
            String str = "";
            if ((valArray != null) && (i < valArray.length)) {
                str = valArray[i];
            }
            data = data + str + ",";
        }

        data = data + value;

        // Copy the remaining elements from valArray if any.
        if (valArray != null) {
            for (int i = index+1; i < valArray.length; i++) {
                data = data + "," + valArray[i];
            }
        }
        return android.provider.Settings.Global.putString(cr, name, data);
    }

    /**
     * Gets the telephony property.
     *
     * @hide
     */
    public static String getTelephonyProperty(int phoneId, String property, String defaultVal) {
        String propVal = null;
        String prop = SystemProperties.get(property);
        if ((prop != null) && (prop.length() > 0)) {
            String values[] = prop.split(",");
            if ((phoneId >= 0) && (phoneId < values.length) && (values[phoneId] != null)) {
                propVal = values[phoneId];
            }
        }
        return propVal == null ? defaultVal : propVal;
    }

    /** @hide */
    public int getSimCount() {
        // FIXME Need to get it from Telephony Dev Controller when that gets implemented!
        // and then this method shouldn't be used at all!
        if(isMultiSimEnabled()) {
            return 2;
        } else {
            return 1;
        }
    }

    /**
     * Returns the IMS Service Table (IST) that was loaded from the ISIM.
     * @return IMS Service Table or null if not present or not loaded
     * @hide
     */
    public String getIsimIst() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIsimIst();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS Proxy Call Session Control Function(PCSCF) that were loaded from the ISIM.
     * @return an array of PCSCF strings with one PCSCF per string, or null if
     *         not present or not loaded
     * @hide
     */
    public String[] getIsimPcscf() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIsimPcscf();
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the response of ISIM Authetification through RIL.
     * Returns null if the Authentification hasn't been successed or isn't present iphonesubinfo.
     * @return the response of ISIM Authetification, or null if not available
     * @hide
     * @deprecated
     * @see getIccSimChallengeResponse with appType=PhoneConstants.APPTYPE_ISIM
     */
    public String getIsimChallengeResponse(String nonce){
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIsimChallengeResponse(nonce);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the response of SIM Authentication through RIL.
     * Returns null if the Authentication hasn't been successful
     * @param subId subscription ID to be queried
     * @param appType ICC application type (@see com.android.internal.telephony.PhoneConstants#APPTYPE_xxx)
     * @param data authentication challenge data
     * @return the response of SIM Authentication, or null if not available
     * @hide
     */
    public String getIccSimChallengeResponse(int subId, int appType, String data) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIccSimChallengeResponse(subId, appType, data);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            return null;
        }
    }

    /**
     * Returns the response of SIM Authentication through RIL for the default subscription.
     * Returns null if the Authentication hasn't been successful
     * @param appType ICC application type (@see com.android.internal.telephony.PhoneConstants#APPTYPE_xxx)
     * @param data authentication challenge data
     * @return the response of SIM Authentication, or null if not available
     * @hide
     */
    public String getIccSimChallengeResponse(int appType, String data) {
        return getIccSimChallengeResponse(getDefaultSubscription(), appType, data);
    }

    /**
     * Get P-CSCF address from PCO after data connection is established or modified.
     * @param apnType the apnType, "ims" for IMS APN, "emergency" for EMERGENCY APN
     * @return array of P-CSCF address
     * @hide
     */
    public String[] getPcscfAddress(String apnType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return new String[0];
            return telephony.getPcscfAddress(apnType, getOpPackageName());
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * Set IMS registration state
     *
     * @param Registration state
     * @hide
     */
    public void setImsRegistrationState(boolean registered) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setImsRegistrationState(registered);
        } catch (RemoteException e) {
        }
    }

    /**
     * Get the preferred network type.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @return the preferred network type, defined in RILConstants.java.
     * @hide
     */
    public int getPreferredNetworkType(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getPreferredNetworkType(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPreferredNetworkType NPE", ex);
        }
        return -1;
    }

    /**
     * Sets the network selection mode to automatic.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @hide
     */
    public void setNetworkSelectionModeAutomatic(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setNetworkSelectionModeAutomatic(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeAutomatic RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeAutomatic NPE", ex);
        }
    }

    /**
     * Perform a radio scan and return the list of avialble networks.
     *
     * The return value is a list of the OperatorInfo of the networks found. Note that this
     * scan can take a long time (sometimes minutes) to happen.
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @hide
     */
    public CellNetworkScanResult getCellNetworkScanResults(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getCellNetworkScanResults(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCellNetworkScanResults RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCellNetworkScanResults NPE", ex);
        }
        return null;
    }

    /**
     * Ask the radio to connect to the input network and change selection mode to manual.
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @hide
     */
    public boolean setNetworkSelectionModeManual(int subId, OperatorInfo operator,
            boolean persistSelection) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setNetworkSelectionModeManual(subId, operator, persistSelection);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeManual RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeManual NPE", ex);
        }
        return false;
    }

    /**
     * Set the preferred network type.
     * Used for device configuration by some CDMA operators.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
     *
     * @param subId the id of the subscription to set the preferred network type for.
     * @param networkType the preferred network type, defined in RILConstants.java.
     * @return true on success; false on any failure.
     * @hide
     */
    public boolean setPreferredNetworkType(int subId, int networkType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setPreferredNetworkType(subId, networkType);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setPreferredNetworkType NPE", ex);
        }
        return false;
    }

    /**
     * Set the preferred network type to global mode which includes LTE, CDMA, EvDo and GSM/WCDMA.
     *
     * <p>
     * Requires that the calling app has carrier privileges.
     * @see #hasCarrierPrivileges
     *
     * @return true on success; false on any failure.
     */
    public boolean setPreferredNetworkTypeToGlobal() {
        return setPreferredNetworkType(getDefaultSubscription(),
                RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA);
    }

    /**
     * Check TETHER_DUN_REQUIRED and TETHER_DUN_APN settings, net.tethering.noprovisioning
     * SystemProperty, and config_tether_apndata to decide whether DUN APN is required for
     * tethering.
     *
     * @return 0: Not required. 1: required. 2: Not set.
     * @hide
     */
    public int getTetherApnRequired() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getTetherApnRequired();
        } catch (RemoteException ex) {
            Rlog.e(TAG, "hasMatchedTetherApnSetting RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "hasMatchedTetherApnSetting NPE", ex);
        }
        return 2;
    }


    /**
     * Values used to return status for hasCarrierPrivileges call.
     */
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_HAS_ACCESS = 1;
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_NO_ACCESS = 0;
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED = -1;
    /** @hide */ @SystemApi
    public static final int CARRIER_PRIVILEGE_STATUS_ERROR_LOADING_RULES = -2;

    /**
     * Has the calling application been granted carrier privileges by the carrier.
     *
     * If any of the packages in the calling UID has carrier privileges, the
     * call will return true. This access is granted by the owner of the UICC
     * card and does not depend on the registered carrier.
     *
     * @return true if the app has carrier privileges.
     */
    public boolean hasCarrierPrivileges() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getCarrierPrivilegeStatus() == CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
        } catch (RemoteException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "hasCarrierPrivileges NPE", ex);
        }
        return false;
    }

    /**
     * Override the branding for the current ICCID.
     *
     * Once set, whenever the SIM is present in the device, the service
     * provider name (SPN) and the operator name will both be replaced by the
     * brand value input. To unset the value, the same function should be
     * called with a null brand value.
     *
     * <p>Requires that the calling app has carrier privileges.
     * @see #hasCarrierPrivileges
     *
     * @param brand The brand name to display/set.
     * @return true if the operation was executed correctly.
     */
    public boolean setOperatorBrandOverride(String brand) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setOperatorBrandOverride(brand);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setOperatorBrandOverride RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setOperatorBrandOverride NPE", ex);
        }
        return false;
    }

    /**
     * Override the roaming preference for the current ICCID.
     *
     * Using this call, the carrier app (see #hasCarrierPrivileges) can override
     * the platform's notion of a network operator being considered roaming or not.
     * The change only affects the ICCID that was active when this call was made.
     *
     * If null is passed as any of the input, the corresponding value is deleted.
     *
     * <p>Requires that the caller have carrier privilege. See #hasCarrierPrivileges.
     *
     * @param gsmRoamingList - List of MCCMNCs to be considered roaming for 3GPP RATs.
     * @param gsmNonRoamingList - List of MCCMNCs to be considered not roaming for 3GPP RATs.
     * @param cdmaRoamingList - List of SIDs to be considered roaming for 3GPP2 RATs.
     * @param cdmaNonRoamingList - List of SIDs to be considered not roaming for 3GPP2 RATs.
     * @return true if the operation was executed correctly.
     *
     * @hide
     */
    public boolean setRoamingOverride(List<String> gsmRoamingList,
            List<String> gsmNonRoamingList, List<String> cdmaRoamingList,
            List<String> cdmaNonRoamingList) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setRoamingOverride(gsmRoamingList, gsmNonRoamingList,
                        cdmaRoamingList, cdmaNonRoamingList);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setRoamingOverride RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setRoamingOverride NPE", ex);
        }
        return false;
    }

    /**
     * Expose the rest of ITelephony to @SystemApi
     */

    /** @hide */
    @SystemApi
    public String getCdmaMdn() {
        return getCdmaMdn(getDefaultSubscription());
    }

    /** @hide */
    @SystemApi
    public String getCdmaMdn(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getCdmaMdn(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** @hide */
    @SystemApi
    public String getCdmaMin() {
        return getCdmaMin(getDefaultSubscription());
    }

    /** @hide */
    @SystemApi
    public String getCdmaMin(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getCdmaMin(subId);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /** @hide */
    @SystemApi
    public int checkCarrierPrivilegesForPackage(String pkgName) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.checkCarrierPrivilegesForPackage(pkgName);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackage RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackage NPE", ex);
        }
        return CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }

    /** @hide */
    @SystemApi
    public int checkCarrierPrivilegesForPackageAnyPhone(String pkgName) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.checkCarrierPrivilegesForPackageAnyPhone(pkgName);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackageAnyPhone RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "checkCarrierPrivilegesForPackageAnyPhone NPE", ex);
        }
        return CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }

    /** @hide */
    @SystemApi
    public List<String> getCarrierPackageNamesForIntent(Intent intent) {
        return getCarrierPackageNamesForIntentAndPhone(intent, getDefaultPhone());
    }

    /** @hide */
    @SystemApi
    public List<String> getCarrierPackageNamesForIntentAndPhone(Intent intent, int phoneId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getCarrierPackageNamesForIntentAndPhone(intent, phoneId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierPackageNamesForIntentAndPhone RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCarrierPackageNamesForIntentAndPhone NPE", ex);
        }
        return null;
    }

    /** @hide */
    @SystemApi
    public void dial(String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.dial(number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#dial", e);
        }
    }

    /** @hide */
    @SystemApi
    public void call(String callingPackage, String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.call(callingPackage, number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#call", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean endCall() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.endCall();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#endCall", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void answerRingingCall() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.answerRingingCall();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#answerRingingCall", e);
        }
    }

    /** @hide */
    @SystemApi
    public void silenceRinger() {
        try {
            getTelecomService().silenceRinger(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecomService#silenceRinger", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean isOffhook() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isOffhook(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isOffhook", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isRinging() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isRinging(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isRinging", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isIdle() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isIdle(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isIdle", e);
        }
        return true;
    }

    /** @hide */
    @SystemApi
    public boolean isRadioOn() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isRadioOn(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isRadioOn", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isSimPinEnabled() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isSimPinEnabled(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isSimPinEnabled", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean supplyPin(String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPin(pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPin", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean supplyPuk(String puk, String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPuk(puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPuk", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public int[] supplyPinReportResult(String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPinReportResult(pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPinReportResult", e);
        }
        return new int[0];
    }

    /** @hide */
    @SystemApi
    public int[] supplyPukReportResult(String puk, String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.supplyPukReportResult(puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#]", e);
        }
        return new int[0];
    }

    /** @hide */
    @SystemApi
    public boolean handlePinMmi(String dialString) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.handlePinMmi(dialString);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#handlePinMmi", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean handlePinMmiForSubscriber(int subId, String dialString) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.handlePinMmiForSubscriber(subId, dialString);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#handlePinMmi", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void toggleRadioOnOff() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.toggleRadioOnOff();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#toggleRadioOnOff", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean setRadio(boolean turnOn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setRadio(turnOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setRadio", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean setRadioPower(boolean turnOn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setRadioPower(turnOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setRadioPower", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void updateServiceLocation() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.updateServiceLocation();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#updateServiceLocation", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean enableDataConnectivity() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.enableDataConnectivity();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#enableDataConnectivity", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean disableDataConnectivity() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.disableDataConnectivity();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#disableDataConnectivity", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean isDataConnectivityPossible() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isDataConnectivityPossible();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isDataConnectivityPossible", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public boolean needsOtaServiceProvisioning() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.needsOtaServiceProvisioning();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#needsOtaServiceProvisioning", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    public void setDataEnabled(boolean enable) {
        setDataEnabled(SubscriptionManager.getDefaultDataSubId(), enable);
    }

    /** @hide */
    @SystemApi
    public void setDataEnabled(int subId, boolean enable) {
        try {
            Log.d(TAG, "setDataEnabled: enabled=" + enable);
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setDataEnabled(subId, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setDataEnabled", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean getDataEnabled() {
        return getDataEnabled(SubscriptionManager.getDefaultDataSubId());
    }

    /** @hide */
    @SystemApi
    public boolean getDataEnabled(int subId) {
        boolean retVal = false;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                retVal = telephony.getDataEnabled(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getDataEnabled", e);
        } catch (NullPointerException e) {
        }
        Log.d(TAG, "getDataEnabled: retVal=" + retVal);
        return retVal;
    }

    /**
     * Returns the result and response from RIL for oem request
     *
     * @param oemReq the data is sent to ril.
     * @param oemResp the respose data from RIL.
     * @return negative value request was not handled or get error
     *         0 request was handled succesfully, but no response data
     *         positive value success, data length of response
     * @hide
     */
    public int invokeOemRilRequestRaw(byte[] oemReq, byte[] oemResp) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.invokeOemRilRequestRaw(oemReq, oemResp);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return -1;
    }

    /** @hide */
    @SystemApi
    public void enableVideoCalling(boolean enable) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.enableVideoCalling(enable);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#enableVideoCalling", e);
        }
    }

    /** @hide */
    @SystemApi
    public boolean isVideoCallingEnabled() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isVideoCallingEnabled(getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isVideoCallingEnabled", e);
        }
        return false;
    }

    /**
     * Whether the device supports configuring the DTMF tone length.
     *
     * @return {@code true} if the DTMF tone length can be changed, and {@code false} otherwise.
     */
    public boolean canChangeDtmfToneLength() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.canChangeDtmfToneLength();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#canChangeDtmfToneLength", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#canChangeDtmfToneLength", e);
        }
        return false;
    }

    /**
     * Whether the device is a world phone.
     *
     * @return {@code true} if the device is a world phone, and {@code false} otherwise.
     */
    public boolean isWorldPhone() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isWorldPhone();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isWorldPhone", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isWorldPhone", e);
        }
        return false;
    }

    /**
     * Whether the phone supports TTY mode.
     *
     * @return {@code true} if the device supports TTY mode, and {@code false} otherwise.
     */
    public boolean isTtyModeSupported() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isTtyModeSupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isTtyModeSupported", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isTtyModeSupported", e);
        }
        return false;
    }

    /**
     * Whether the phone supports hearing aid compatibility.
     *
     * @return {@code true} if the device supports hearing aid compatibility, and {@code false}
     * otherwise.
     */
    public boolean isHearingAidCompatibilitySupported() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isHearingAidCompatibilitySupported();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isHearingAidCompatibilitySupported", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isHearingAidCompatibilitySupported", e);
        }
        return false;
    }

    /**
     * This function retrieves value for setting "name+subId", and if that is not found
     * retrieves value for setting "name", and if that is not found throws
     * SettingNotFoundException
     *
     * @hide */
    public static int getIntWithSubId(ContentResolver cr, String name, int subId)
            throws SettingNotFoundException {
        try {
            return Settings.Global.getInt(cr, name + subId);
        } catch (SettingNotFoundException e) {
            try {
                int val = Settings.Global.getInt(cr, name);
                Settings.Global.putInt(cr, name + subId, val);

                /* We are now moving from 'setting' to 'setting+subId', and using the value stored
                 * for 'setting' as default. Reset the default (since it may have a user set
                 * value). */
                int default_val = val;
                if (name.equals(Settings.Global.MOBILE_DATA)) {
                    default_val = "true".equalsIgnoreCase(
                            SystemProperties.get("ro.com.android.mobiledata", "true")) ? 1 : 0;
                } else if (name.equals(Settings.Global.DATA_ROAMING)) {
                    default_val = "true".equalsIgnoreCase(
                            SystemProperties.get("ro.com.android.dataroaming", "false")) ? 1 : 0;
                }

                if (default_val != val) {
                    Settings.Global.putInt(cr, name, default_val);
                }

                return val;
            } catch (SettingNotFoundException exc) {
                throw new SettingNotFoundException(name);
            }
        }
    }

   /**
    * Returns the IMS Registration Status
    * @hide
    */
   public boolean isImsRegistered() {
       try {
           ITelephony telephony = getITelephony();
           if (telephony == null)
               return false;
           return telephony.isImsRegistered();
       } catch (RemoteException ex) {
           return false;
       } catch (NullPointerException ex) {
           return false;
       }
   }

    /**
     * Returns the Status of Volte
     * @hide
     */
    public boolean isVolteAvailable() {
       try {
           return getITelephony().isVolteAvailable();
       } catch (RemoteException ex) {
           return false;
       } catch (NullPointerException ex) {
           return false;
       }
   }

    /**
     * Returns the Status of video telephony (VT)
     * @hide
     */
    public boolean isVideoTelephonyAvailable() {
        try {
            return getITelephony().isVideoTelephonyAvailable();
        } catch (RemoteException ex) {
            return false;
        } catch (NullPointerException ex) {
            return false;
        }
    }

    /**
     * Returns the Status of Wi-Fi Calling
     * @hide
     */
    public boolean isWifiCallingAvailable() {
       try {
           return getITelephony().isWifiCallingAvailable();
       } catch (RemoteException ex) {
           return false;
       } catch (NullPointerException ex) {
           return false;
       }
   }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the default phone.
    *
    * @hide
    */
    public void setSimOperatorNumeric(String numeric) {
        int phoneId = getDefaultPhone();
        setSimOperatorNumericForPhone(phoneId, numeric);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the given phone.
    *
    * @hide
    */
    public void setSimOperatorNumericForPhone(int phoneId, String numeric) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, numeric);
    }

    /**
     * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the default phone.
     *
     * @hide
     */
    public void setSimOperatorName(String name) {
        int phoneId = getDefaultPhone();
        setSimOperatorNameForPhone(phoneId, name);
    }

    /**
     * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the given phone.
     *
     * @hide
     */
    public void setSimOperatorNameForPhone(int phoneId, String name) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, name);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY for the default phone.
    *
    * @hide
    */
    public void setSimCountryIso(String iso) {
        int phoneId = getDefaultPhone();
        setSimCountryIsoForPhone(phoneId, iso);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY for the given phone.
    *
    * @hide
    */
    public void setSimCountryIsoForPhone(int phoneId, String iso) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, iso);
    }

    /**
     * Set TelephonyProperties.PROPERTY_SIM_STATE for the default phone.
     *
     * @hide
     */
    public void setSimState(String state) {
        int phoneId = getDefaultPhone();
        setSimStateForPhone(phoneId, state);
    }

    /**
     * Set TelephonyProperties.PROPERTY_SIM_STATE for the given phone.
     *
     * @hide
     */
    public void setSimStateForPhone(int phoneId, String state) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_SIM_STATE, state);
    }

    /**
     * Set baseband version for the default phone.
     *
     * @param version baseband version
     * @hide
     */
    public void setBasebandVersion(String version) {
        int phoneId = getDefaultPhone();
        setBasebandVersionForPhone(phoneId, version);
    }

    /**
     * Set baseband version by phone id.
     *
     * @param phoneId for which baseband version is set
     * @param version baseband version
     * @hide
     */
    public void setBasebandVersionForPhone(int phoneId, String version) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            String prop = TelephonyProperties.PROPERTY_BASEBAND_VERSION +
                    ((phoneId == 0) ? "" : Integer.toString(phoneId));
            SystemProperties.set(prop, version);
        }
    }

    /**
     * Set phone type for the default phone.
     *
     * @param type phone type
     *
     * @hide
     */
    public void setPhoneType(int type) {
        int phoneId = getDefaultPhone();
        setPhoneType(phoneId, type);
    }

    /**
     * Set phone type by phone id.
     *
     * @param phoneId for which phone type is set
     * @param type phone type
     *
     * @hide
     */
    public void setPhoneType(int phoneId, int type) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            TelephonyManager.setTelephonyProperty(phoneId,
                    TelephonyProperties.CURRENT_ACTIVE_PHONE, String.valueOf(type));
        }
    }

    /**
     * Get OTASP number schema for the default phone.
     *
     * @param defaultValue default value
     * @return OTA SP number schema
     *
     * @hide
     */
    public String getOtaSpNumberSchema(String defaultValue) {
        int phoneId = getDefaultPhone();
        return getOtaSpNumberSchemaForPhone(phoneId, defaultValue);
    }

    /**
     * Get OTASP number schema by phone id.
     *
     * @param phoneId for which OTA SP number schema is get
     * @param defaultValue default value
     * @return OTA SP number schema
     *
     * @hide
     */
    public String getOtaSpNumberSchemaForPhone(int phoneId, String defaultValue) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return TelephonyManager.getTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_OTASP_NUM_SCHEMA, defaultValue);
        }

        return defaultValue;
    }

    /**
     * Get SMS receive capable from system property for the default phone.
     *
     * @param defaultValue default value
     * @return SMS receive capable
     *
     * @hide
     */
    public boolean getSmsReceiveCapable(boolean defaultValue) {
        int phoneId = getDefaultPhone();
        return getSmsReceiveCapableForPhone(phoneId, defaultValue);
    }

    /**
     * Get SMS receive capable from system property by phone id.
     *
     * @param phoneId for which SMS receive capable is get
     * @param defaultValue default value
     * @return SMS receive capable
     *
     * @hide
     */
    public boolean getSmsReceiveCapableForPhone(int phoneId, boolean defaultValue) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return Boolean.valueOf(TelephonyManager.getTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_SMS_RECEIVE, String.valueOf(defaultValue)));
        }

        return defaultValue;
    }

    /**
     * Get SMS send capable from system property for the default phone.
     *
     * @param defaultValue default value
     * @return SMS send capable
     *
     * @hide
     */
    public boolean getSmsSendCapable(boolean defaultValue) {
        int phoneId = getDefaultPhone();
        return getSmsSendCapableForPhone(phoneId, defaultValue);
    }

    /**
     * Get SMS send capable from system property by phone id.
     *
     * @param phoneId for which SMS send capable is get
     * @param defaultValue default value
     * @return SMS send capable
     *
     * @hide
     */
    public boolean getSmsSendCapableForPhone(int phoneId, boolean defaultValue) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return Boolean.valueOf(TelephonyManager.getTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_SMS_SEND, String.valueOf(defaultValue)));
        }

        return defaultValue;
    }

    /**
     * Set the alphabetic name of current registered operator.
     * @param name the alphabetic name of current registered operator.
     * @hide
     */
    public void setNetworkOperatorName(String name) {
        int phoneId = getDefaultPhone();
        setNetworkOperatorNameForPhone(phoneId, name);
    }

    /**
     * Set the alphabetic name of current registered operator.
     * @param phoneId which phone you want to set
     * @param name the alphabetic name of current registered operator.
     * @hide
     */
    public void setNetworkOperatorNameForPhone(int phoneId, String name) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ALPHA, name);
        }
    }

    /**
     * Set the numeric name (MCC+MNC) of current registered operator.
     * @param operator the numeric name (MCC+MNC) of current registered operator
     * @hide
     */
    public void setNetworkOperatorNumeric(String numeric) {
        int phoneId = getDefaultPhone();
        setNetworkOperatorNumericForPhone(phoneId, numeric);
    }

    /**
     * Set the numeric name (MCC+MNC) of current registered operator.
     * @param phoneId for which phone type is set
     * @param operator the numeric name (MCC+MNC) of current registered operator
     * @hide
     */
    public void setNetworkOperatorNumericForPhone(int phoneId, String numeric) {
        setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, numeric);
    }

    /**
     * Set roaming state of the current network, for GSM purposes.
     * @param isRoaming is network in romaing state or not
     * @hide
     */
    public void setNetworkRoaming(boolean isRoaming) {
        int phoneId = getDefaultPhone();
        setNetworkRoamingForPhone(phoneId, isRoaming);
    }

    /**
     * Set roaming state of the current network, for GSM purposes.
     * @param phoneId which phone you want to set
     * @param isRoaming is network in romaing state or not
     * @hide
     */
    public void setNetworkRoamingForPhone(int phoneId, boolean isRoaming) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                    isRoaming ? "true" : "false");
        }
    }

    /**
     * Set the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * @param iso the ISO country code equivalent of the current registered
     * @hide
     */
    public void setNetworkCountryIso(String iso) {
        int phoneId = getDefaultPhone();
        setNetworkCountryIsoForPhone(phoneId, iso);
    }

    /**
     * Set the ISO country code equivalent of the current registered
     * operator's MCC (Mobile Country Code).
     * @param phoneId which phone you want to set
     * @param iso the ISO country code equivalent of the current registered
     * @hide
     */
    public void setNetworkCountryIsoForPhone(int phoneId, String iso) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, iso);
        }
    }

    /**
     * Set the network type currently in use on the device for data transmission.
     * @param type the network type currently in use on the device for data transmission
     * @hide
     */
    public void setDataNetworkType(int type) {
        int phoneId = getDefaultPhone();
        setDataNetworkTypeForPhone(phoneId, type);
    }

    /**
     * Set the network type currently in use on the device for data transmission.
     * @param phoneId which phone you want to set
     * @param type the network type currently in use on the device for data transmission
     * @hide
     */
    public void setDataNetworkTypeForPhone(int phoneId, int type) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId,
                    TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.rilRadioTechnologyToString(type));
        }
    }

    /**
     * Returns the subscription ID for the given phone account.
     * @hide
     */
    public int getSubIdForPhoneAccount(PhoneAccount phoneAccount) {
        int retval = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                retval = service.getSubIdForPhoneAccount(phoneAccount);
            }
        } catch (RemoteException e) {
        }

        return retval;
    }

    /**
     * Resets telephony manager settings back to factory defaults.
     *
     * @hide
     */
    public void factoryReset(int subId) {
        try {
            Log.d(TAG, "factoryReset: subId=" + subId);
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.factoryReset(subId);
        } catch (RemoteException e) {
        }
    }


    /** @hide */
    public String getLocaleFromDefaultSim() {
        try {
            final ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getLocaleFromDefaultSim();
            }
        } catch (RemoteException ex) {
        }
        return null;
    }

    /**
     * Returns the modem activity info.
     * @hide
     */
    public ModemActivityInfo getModemActivityInfo() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getModemActivityInfo();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getModemActivityInfo", e);
        }
        return null;
    }
}
