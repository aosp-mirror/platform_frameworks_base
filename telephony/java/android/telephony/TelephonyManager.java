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

import static android.content.Context.TELECOM_SERVICE;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressAutoDoc;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.annotation.WorkerThread;
import android.app.ActivityThread;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkStats;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.WorkSource;
import android.provider.Settings.SettingNotFoundException;
import android.service.carrier.CarrierIdentifier;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.VisualVoicemailService.VisualVoicemailTask;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.emergency.EmergencyNumber.EmergencyServiceCategories;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.ims.internal.IImsServiceFeatureCallback;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telecom.ITelecomService;
import com.android.internal.telephony.CellNetworkScanResult;
import com.android.internal.telephony.INumberVerificationCallback;
import com.android.internal.telephony.IOns;
import com.android.internal.telephony.IPhoneSubInfo;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides access to information about the telephony services on
 * the device. Applications can use the methods in this class to
 * determine telephony services and states, as well as to access some
 * types of subscriber information. Applications can also register
 * a listener to receive notification of telephony state changes.
 * <p>
 * The returned TelephonyManager will use the default subscription for all calls.
 * To call an API for a specific subscription, use {@link #createForSubscriptionId(int)}. e.g.
 * <code>
 *   telephonyManager = defaultSubTelephonyManager.createForSubscriptionId(subId);
 * </code>
 * <p>
 * Note that access to some telephony information is
 * permission-protected. Your application cannot access the protected
 * information unless it has the appropriate permissions declared in
 * its manifest file. Where permissions apply, they are noted in the
 * the methods through which you access the protected information.
 */
@SystemService(Context.TELEPHONY_SERVICE)
public class TelephonyManager {
    private static final String TAG = "TelephonyManager";

    /**
     * The key to use when placing the result of {@link #requestModemActivityInfo(ResultReceiver)}
     * into the ResultReceiver Bundle.
     * @hide
     */
    public static final String MODEM_ACTIVITY_RESULT_KEY =
            BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY;

    /**
     * The process name of the Phone app as well as many other apps that use this process name, such
     * as settings and vendor components.
     * @hide
     */
    public static final String PHONE_PROCESS_NAME = "com.android.phone";

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

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NETWORK_SELECTION_MODE_"},
            value = {
                    NETWORK_SELECTION_MODE_UNKNOWN,
                    NETWORK_SELECTION_MODE_AUTO,
                    NETWORK_SELECTION_MODE_MANUAL})
    public @interface NetworkSelectionMode {}

    /** @hide */
    public static final int NETWORK_SELECTION_MODE_UNKNOWN = 0;
    /** @hide */
    public static final int NETWORK_SELECTION_MODE_AUTO = 1;
    /** @hide */
    public static final int NETWORK_SELECTION_MODE_MANUAL = 2;

    /** The otaspMode passed to PhoneStateListener#onOtaspChanged */
    /** @hide */
    static public final int OTASP_UNINITIALIZED = 0;
    /** @hide */
    static public final int OTASP_UNKNOWN = 1;
    /** @hide */
    static public final int OTASP_NEEDED = 2;
    /** @hide */
    static public final int OTASP_NOT_NEEDED = 3;
    /* OtaUtil has conflict enum 4: OtaUtils.OTASP_FAILURE_SPC_RETRIES */
    /** @hide */
    static public final int OTASP_SIM_UNPROVISIONED = 5;

    /** @hide */
    static public final int KEY_TYPE_EPDG = 1;

    /** @hide */
    static public final int KEY_TYPE_WLAN = 2;

    /**
     * No Single Radio Voice Call Continuity (SRVCC) handover is active.
     * See TS 23.216 for more information.
     * @hide
     */
    @SystemApi
    public static final int SRVCC_STATE_HANDOVER_NONE  = -1;

    /**
     * Single Radio Voice Call Continuity (SRVCC) handover has been started on the network.
     * See TS 23.216 for more information.
     * @hide
     */
    @SystemApi
    public static final int SRVCC_STATE_HANDOVER_STARTED  = 0;

    /**
     * Ongoing Single Radio Voice Call Continuity (SRVCC) handover has successfully completed.
     * See TS 23.216 for more information.
     * @hide
     */
    @SystemApi
    public static final int SRVCC_STATE_HANDOVER_COMPLETED = 1;

    /**
     * Ongoing Single Radio Voice Call Continuity (SRVCC) handover has failed.
     * See TS 23.216 for more information.
     * @hide
     */
    @SystemApi
    public static final int SRVCC_STATE_HANDOVER_FAILED   = 2;

    /**
     * Ongoing Single Radio Voice Call Continuity (SRVCC) handover has been canceled.
     * See TS 23.216 for more information.
     * @hide
     */
    @SystemApi
    public static final int SRVCC_STATE_HANDOVER_CANCELED  = 3;

    /**
     * A UICC card identifier used if the device does not support the operation.
     * For example, {@link #getCardIdForDefaultEuicc()} returns this value if the device has no
     * eUICC, or the eUICC cannot be read.
     */
    public static final int UNSUPPORTED_CARD_ID = -1;

    /**
     * A UICC card identifier used before the UICC card is loaded. See
     * {@link #getCardIdForDefaultEuicc()} and {@link UiccCardInfo#getCardId()}.
     * <p>
     * Note that once the UICC card is loaded, the card ID may become {@link #UNSUPPORTED_CARD_ID}.
     */
    public static final int UNINITIALIZED_CARD_ID = -2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SRVCC_STATE_"},
            value = {
                    SRVCC_STATE_HANDOVER_NONE,
                    SRVCC_STATE_HANDOVER_STARTED,
                    SRVCC_STATE_HANDOVER_COMPLETED,
                    SRVCC_STATE_HANDOVER_FAILED,
                    SRVCC_STATE_HANDOVER_CANCELED})
    public @interface SrvccState {}

    private final Context mContext;
    private final int mSubId;
    @UnsupportedAppUsage
    private SubscriptionManager mSubscriptionManager;
    private TelephonyScanManager mTelephonyScanManager;

    private static String multiSimConfig =
            SystemProperties.get(TelephonyProperties.PROPERTY_MULTI_SIM_CONFIG);

    /** Enum indicating multisim variants
     *  DSDS - Dual SIM Dual Standby
     *  DSDA - Dual SIM Dual Active
     *  TSTS - Triple SIM Triple Standby
     **/
    /** @hide */
    public enum MultiSimVariants {
        @UnsupportedAppUsage
        DSDS,
        @UnsupportedAppUsage
        DSDA,
        @UnsupportedAppUsage
        TSTS,
        @UnsupportedAppUsage
        UNKNOWN
    };

    /** @hide */
    @UnsupportedAppUsage
    public TelephonyManager(Context context) {
      this(context, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
    }

    /** @hide */
    @UnsupportedAppUsage
    public TelephonyManager(Context context, int subId) {
        mSubId = subId;
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
        mSubscriptionManager = SubscriptionManager.from(mContext);
    }

    /** @hide */
    @UnsupportedAppUsage
    private TelephonyManager() {
        mContext = null;
        mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private static TelephonyManager sInstance = new TelephonyManager();

    /** @hide
    /* @deprecated - use getSystemService as described above */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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

    private boolean isSystemProcess() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    /**
     * Returns the multi SIM variant
     * Returns DSDS for Dual SIM Dual Standby
     * Returns DSDA for Dual SIM Dual Active
     * Returns TSTS for Triple SIM Triple Standby
     * Returns UNKNOWN for others
     */
    /** {@hide} */
    @UnsupportedAppUsage
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
     * Returns 0 if none of voice, sms, data is not supported
     * Returns 1 for Single standby mode (Single SIM functionality)
     * Returns 2 for Dual standby mode.(Dual SIM functionality)
     * Returns 3 for Tri standby mode.(Tri SIM functionality)
     */
    public int getPhoneCount() {
        int phoneCount = 1;
        switch (getMultiSimConfiguration()) {
            case UNKNOWN:
                ConnectivityManager cm = mContext == null ? null : (ConnectivityManager) mContext
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                // check for voice and data support, 0 if not supported
                if (!isVoiceCapable() && !isSmsCapable() && cm != null
                        && !cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)) {
                    phoneCount = 0;
                } else {
                    phoneCount = 1;
                }
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static TelephonyManager from(Context context) {
        return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Create a new TelephonyManager object pinned to the given subscription ID.
     *
     * @return a TelephonyManager that uses the given subId for all calls.
     */
    public TelephonyManager createForSubscriptionId(int subId) {
      // Don't reuse any TelephonyManager objects.
      return new TelephonyManager(mContext, subId);
    }

    /**
     * Create a new TelephonyManager object pinned to the subscription ID associated with the given
     * phone account.
     *
     * @return a TelephonyManager that uses the given phone account for all calls, or {@code null}
     * if the phone account does not correspond to a valid subscription ID.
     */
    @Nullable
    public TelephonyManager createForPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
        int subId = getSubIdForPhoneAccountHandle(phoneAccountHandle);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return null;
        }
        return new TelephonyManager(mContext, subId);
    }

    /** {@hide} */
    @UnsupportedAppUsage
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
     * If a receiving app has {@link android.Manifest.permission#READ_CALL_LOG} permission, a second
     * extra {@link #EXTRA_INCOMING_NUMBER} provides the phone number for incoming and outgoing
     * calls as a String.
     * <p>
     * If the receiving app has
     * {@link android.Manifest.permission#READ_CALL_LOG} and
     * {@link android.Manifest.permission#READ_PHONE_STATE} permission, it will receive the
     * broadcast twice; one with the {@link #EXTRA_INCOMING_NUMBER} populated with the phone number,
     * and another with it blank.  Due to the nature of broadcasts, you cannot assume the order
     * in which these broadcasts will arrive, however you are guaranteed to receive two in this
     * case.  Apps which are interested in the {@link #EXTRA_INCOMING_NUMBER} can ignore the
     * broadcasts where {@link #EXTRA_INCOMING_NUMBER} is not present in the extras (e.g. where
     * {@link Intent#hasExtra(String)} returns {@code false}).
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
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
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
     * A boolean meta-data value indicating whether the voicemail settings should be hidden in the
     * call settings page launched by
     * {@link android.telecom.TelecomManager#ACTION_SHOW_CALL_SETTINGS}.
     * Dialer implementations (see {@link android.telecom.TelecomManager#getDefaultDialerPackage()})
     * which would also like to manage voicemail settings should set this meta-data to {@code true}
     * in the manifest registration of their application.
     *
     * @see android.telecom.TelecomManager#ACTION_SHOW_CALL_SETTINGS
     * @see #ACTION_CONFIGURE_VOICEMAIL
     * @see #EXTRA_HIDE_PUBLIC_SETTINGS
     */
    public static final String METADATA_HIDE_VOICEMAIL_SETTINGS_MENU =
            "android.telephony.HIDE_VOICEMAIL_SETTINGS_MENU";

    /**
     * Open the voicemail settings activity to make changes to voicemail configuration.
     *
     * <p>
     * The {@link #EXTRA_PHONE_ACCOUNT_HANDLE} extra indicates which {@link PhoneAccountHandle} to
     * configure voicemail.
     * The {@link #EXTRA_HIDE_PUBLIC_SETTINGS} hides settings the dialer will modify through public
     * API if set.
     *
     * @see #EXTRA_PHONE_ACCOUNT_HANDLE
     * @see #EXTRA_HIDE_PUBLIC_SETTINGS
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CONFIGURE_VOICEMAIL =
            "android.telephony.action.CONFIGURE_VOICEMAIL";

    /**
     * The boolean value indicating whether the voicemail settings activity launched by {@link
     * #ACTION_CONFIGURE_VOICEMAIL} should hide settings accessible through public API. This is
     * used by dialer implementations which provides their own voicemail settings UI, but still
     * needs to expose device specific voicemail settings to the user.
     *
     * @see #ACTION_CONFIGURE_VOICEMAIL
     * @see #METADATA_HIDE_VOICEMAIL_SETTINGS_MENU
     */
    public static final String EXTRA_HIDE_PUBLIC_SETTINGS =
            "android.telephony.extra.HIDE_PUBLIC_SETTINGS";

    /**
     * @hide
     */
    public static final boolean EMERGENCY_ASSISTANCE_ENABLED = true;

    /**
     * The lookup key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the new call state.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     *
     * @see #EXTRA_STATE_IDLE
     * @see #EXTRA_STATE_RINGING
     * @see #EXTRA_STATE_OFFHOOK
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
     * Extra key used with the {@link #ACTION_PHONE_STATE_CHANGED} broadcast
     * for a String containing the incoming or outgoing phone number.
     * <p>
     * This extra is only populated for receivers of the {@link #ACTION_PHONE_STATE_CHANGED}
     * broadcast which have been granted the {@link android.Manifest.permission#READ_CALL_LOG} and
     * {@link android.Manifest.permission#READ_PHONE_STATE} permissions.
     * <p>
     * For incoming calls, the phone number is only guaranteed to be populated when the
     * {@link #EXTRA_STATE} changes from {@link #EXTRA_STATE_IDLE} to {@link #EXTRA_STATE_RINGING}.
     * If the incoming caller is from an unknown number, the extra will be populated with an empty
     * string.
     * For outgoing calls, the phone number is only guaranteed to be populated when the
     * {@link #EXTRA_STATE} changes from {@link #EXTRA_STATE_IDLE} to {@link #EXTRA_STATE_OFFHOOK}.
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
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_RINGING_CALL_STATE
     * @see #EXTRA_FOREGROUND_CALL_STATE
     * @see #EXTRA_BACKGROUND_CALL_STATE
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     * @deprecated use {@link PhoneStateListener#LISTEN_PRECISE_CALL_STATE} instead
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRECISE_CALL_STATE_CHANGED =
            "android.intent.action.PRECISE_CALL_STATE";

    /**
     * Broadcast intent action indicating that call disconnect cause has changed.
     *
     * <p>
     * The {@link #EXTRA_DISCONNECT_CAUSE} extra indicates the disconnect cause.
     * The {@link #EXTRA_PRECISE_DISCONNECT_CAUSE} extra indicates the precise disconnect cause.
     *
     * <p class="note">
     * Requires the READ_PRECISE_PHONE_STATE permission.
     *
     * @see #EXTRA_DISCONNECT_CAUSE
     * @see #EXTRA_PRECISE_DISCONNECT_CAUSE
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CALL_DISCONNECT_CAUSE_CHANGED =
            "android.intent.action.CALL_DISCONNECT_CAUSE";

    /**
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast and
     * {@link PhoneStateListener#onPreciseCallStateChanged(PreciseCallState)} for an integer
     * containing the state of the current ringing call.
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
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast and
     * {@link PhoneStateListener#onPreciseCallStateChanged(PreciseCallState)} for an integer
     * containing the state of the current foreground call.
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
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast and
     * {@link PhoneStateListener#onPreciseCallStateChanged(PreciseCallState)} for an integer
     * containing the state of the current background call.
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
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast and
     * {@link PhoneStateListener#onPreciseCallStateChanged(PreciseCallState)} for an integer
     * containing the disconnect cause.
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
     * The lookup key used with the {@link #ACTION_PRECISE_CALL_STATE_CHANGED} broadcast and
     * {@link PhoneStateListener#onPreciseCallStateChanged(PreciseCallState)} for an integer
     * containing the disconnect cause provided by the RIL.
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
     * @see #EXTRA_DATA_IFACE
     * @see #EXTRA_DATA_FAILURE_CAUSE
     * @hide
     *
     * @deprecated If the app is running in the background, it won't be able to receive this
     * broadcast. Apps should use ConnectivityManager {@link #registerNetworkCallback(
     * android.net.NetworkRequest, ConnectivityManager.NetworkCallback)} to listen for network
     * changes.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * @see TelephonyManager#NETWORK_TYPE_NR
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
     * Broadcast intent action for letting the default dialer to know to show voicemail
     * notification.
     *
     * <p>
     * The {@link #EXTRA_PHONE_ACCOUNT_HANDLE} extra indicates which {@link PhoneAccountHandle} the
     * voicemail is received on.
     * The {@link #EXTRA_NOTIFICATION_COUNT} extra indicates the total numbers of unheard
     * voicemails.
     * The {@link #EXTRA_VOICEMAIL_NUMBER} extra indicates the voicemail number if available.
     * The {@link #EXTRA_CALL_VOICEMAIL_INTENT} extra is a {@link android.app.PendingIntent} that
     * will call the voicemail number when sent. This extra will be empty if the voicemail number
     * is not set, and {@link #EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT} will be set instead.
     * The {@link #EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT} extra is a
     * {@link android.app.PendingIntent} that will launch the voicemail settings. This extra is only
     * available when the voicemail number is not set.
     * The {@link #EXTRA_IS_REFRESH} extra indicates whether the notification is a refresh or a new
     * notification.
     *
     * @see #EXTRA_PHONE_ACCOUNT_HANDLE
     * @see #EXTRA_NOTIFICATION_COUNT
     * @see #EXTRA_VOICEMAIL_NUMBER
     * @see #EXTRA_CALL_VOICEMAIL_INTENT
     * @see #EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT
     * @see #EXTRA_IS_REFRESH
     */
    public static final String ACTION_SHOW_VOICEMAIL_NOTIFICATION =
            "android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION";

    /**
     * The extra used with an {@link #ACTION_CONFIGURE_VOICEMAIL} and
     * {@link #ACTION_SHOW_VOICEMAIL_NOTIFICATION} {@code Intent} to specify the
     * {@link PhoneAccountHandle} the configuration or notification is for.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE =
            "android.telephony.extra.PHONE_ACCOUNT_HANDLE";

    /**
     * The number of voice messages associated with the notification.
     */
    public static final String EXTRA_NOTIFICATION_COUNT =
            "android.telephony.extra.NOTIFICATION_COUNT";

    /**
     * The voicemail number.
     */
    public static final String EXTRA_VOICEMAIL_NUMBER =
            "android.telephony.extra.VOICEMAIL_NUMBER";

    /**
     * The intent to call voicemail.
     */
    public static final String EXTRA_CALL_VOICEMAIL_INTENT =
            "android.telephony.extra.CALL_VOICEMAIL_INTENT";

    /**
     * The intent to launch voicemail settings.
     */
    public static final String EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT =
            "android.telephony.extra.LAUNCH_VOICEMAIL_SETTINGS_INTENT";

    /**
     * Boolean value representing whether the {@link
     * TelephonyManager#ACTION_SHOW_VOICEMAIL_NOTIFICATION} is new or a refresh of an existing
     * notification. Notification refresh happens after reboot or connectivity changes. The user has
     * already been notified for the voicemail so it should not alert the user, and should not be
     * shown again if the user has dismissed it.
     */
    public static final String EXTRA_IS_REFRESH = "android.telephony.extra.IS_REFRESH";

    /**
     * {@link android.telecom.Connection} event used to indicate that an IMS call has be
     * successfully handed over from WIFI to LTE.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_HANDOVER_VIDEO_FROM_WIFI_TO_LTE =
            "android.telephony.event.EVENT_HANDOVER_VIDEO_FROM_WIFI_TO_LTE";

    /**
     * {@link android.telecom.Connection} event used to indicate that an IMS call has be
     * successfully handed over from LTE to WIFI.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_HANDOVER_VIDEO_FROM_LTE_TO_WIFI =
            "android.telephony.event.EVENT_HANDOVER_VIDEO_FROM_LTE_TO_WIFI";

    /**
     * {@link android.telecom.Connection} event used to indicate that an IMS call failed to be
     * handed over from LTE to WIFI.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_HANDOVER_TO_WIFI_FAILED =
            "android.telephony.event.EVENT_HANDOVER_TO_WIFI_FAILED";

    /**
     * {@link android.telecom.Connection} event used to indicate that a video call was downgraded to
     * audio because the data limit was reached.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_DOWNGRADE_DATA_LIMIT_REACHED =
            "android.telephony.event.EVENT_DOWNGRADE_DATA_LIMIT_REACHED";

    /**
     * {@link android.telecom.Connection} event used to indicate that a video call was downgraded to
     * audio because the data was disabled.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_DOWNGRADE_DATA_DISABLED =
            "android.telephony.event.EVENT_DOWNGRADE_DATA_DISABLED";

    /**
     * {@link android.telecom.Connection} event used to indicate that the InCall UI should notify
     * the user when an international call is placed while on WFC only.
     * <p>
     * Used when the carrier config value
     * {@link CarrierConfigManager#KEY_NOTIFY_INTERNATIONAL_CALL_ON_WFC_BOOL} is true, the device
     * is on WFC (VoLTE not available) and an international number is dialed.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_NOTIFY_INTERNATIONAL_CALL_ON_WFC =
            "android.telephony.event.EVENT_NOTIFY_INTERNATIONAL_CALL_ON_WFC";

    /**
     * {@link android.telecom.Connection} event used to indicate that an outgoing call has been
     * forwarded to another number.
     * <p>
     * Sent in response to an IMS supplementary service notification indicating the call has been
     * forwarded.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to be null when this connection event is used.
     * @hide
     */
    public static final String EVENT_CALL_FORWARDED =
            "android.telephony.event.EVENT_CALL_FORWARDED";

    /**
     * {@link android.telecom.Connection} event used to indicate that a supplementary service
     * notification has been received.
     * <p>
     * Sent via {@link android.telecom.Connection#sendConnectionEvent(String, Bundle)}.
     * The {@link Bundle} parameter is expected to include the following extras:
     * <ul>
     *     <li>{@link #EXTRA_NOTIFICATION_TYPE} - the notification type.</li>
     *     <li>{@link #EXTRA_NOTIFICATION_CODE} - the notification code.</li>
     *     <li>{@link #EXTRA_NOTIFICATION_MESSAGE} - human-readable message associated with the
     *     supplementary service notification.</li>
     * </ul>
     * @hide
     */
    public static final String EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION =
            "android.telephony.event.EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION";

    /**
     * Integer extra key used with {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} which indicates
     * the type of supplementary service notification which occurred.
     * Will be either
     * {@link com.android.internal.telephony.gsm.SuppServiceNotification#NOTIFICATION_TYPE_CODE_1}
     * or
     * {@link com.android.internal.telephony.gsm.SuppServiceNotification#NOTIFICATION_TYPE_CODE_2}
     * <p>
     * Set in the extras for the {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} connection event.
     * @hide
     */
    public static final String EXTRA_NOTIFICATION_TYPE =
            "android.telephony.extra.NOTIFICATION_TYPE";

    /**
     * Integer extra key used with {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} which indicates
     * the supplementary service notification which occurred.
     * <p>
     * Depending on the {@link #EXTRA_NOTIFICATION_TYPE}, the code will be one of the {@code CODE_*}
     * codes defined in {@link com.android.internal.telephony.gsm.SuppServiceNotification}.
     * <p>
     * Set in the extras for the {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} connection event.
     * @hide
     */
    public static final String EXTRA_NOTIFICATION_CODE =
            "android.telephony.extra.NOTIFICATION_CODE";

    /**
     * {@link CharSequence} extra key used with {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION}
     * which contains a human-readable message which can be displayed to the user for the
     * supplementary service notification.
     * <p>
     * Set in the extras for the {@link #EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION} connection event.
     * @hide
     */
    public static final String EXTRA_NOTIFICATION_MESSAGE =
            "android.telephony.extra.NOTIFICATION_MESSAGE";

    /* Visual voicemail protocols */

    /**
     * The OMTP protocol.
     */
    public static final String VVM_TYPE_OMTP = "vvm_type_omtp";

    /**
     * A flavor of OMTP protocol with a different mobile originated (MO) format
     */
    public static final String VVM_TYPE_CVVM = "vvm_type_cvvm";

    /**
     * Key in bundle returned by {@link #getVisualVoicemailPackageName()}, indicating whether visual
     * voicemail was enabled or disabled by the user. If the user never explicitly changed this
     * setting, this key will not exist.
     *
     * @see #getVisualVoicemailSettings()
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VISUAL_VOICEMAIL_ENABLED_BY_USER_BOOL =
            "android.telephony.extra.VISUAL_VOICEMAIL_ENABLED_BY_USER_BOOL";

    /**
     * Key in bundle returned by {@link #getVisualVoicemailPackageName()}, indicating the voicemail
     * access PIN scrambled during the auto provisioning process. The user is expected to reset
     * their PIN if this value is not {@code null}.
     *
     * @see #getVisualVoicemailSettings()
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VOICEMAIL_SCRAMBLED_PIN_STRING =
            "android.telephony.extra.VOICEMAIL_SCRAMBLED_PIN_STRING";

    /**
     * @hide
     */
    public static final String USSD_RESPONSE = "USSD_RESPONSE";

    /**
     * USSD return code success.
     * @hide
     */
    public static final int USSD_RETURN_SUCCESS = 100;

    /**
     * Failed code returned when the mobile network has failed to complete a USSD request.
     * <p>
     * Returned via {@link TelephonyManager.UssdResponseCallback#onReceiveUssdResponseFailed(
     * TelephonyManager, String, int)}.
     */
    public static final int USSD_RETURN_FAILURE = -1;

    /**
     * Failure code returned when a USSD request has failed to execute because the Telephony
     * service is unavailable.
     * <p>
     * Returned via {@link TelephonyManager.UssdResponseCallback#onReceiveUssdResponseFailed(
     * TelephonyManager, String, int)}.
     */
    public static final int USSD_ERROR_SERVICE_UNAVAIL = -2;

    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which leaves the roaming
     * mode set to the radio default or to the user's preference if they've indicated one.
     */
    public static final int CDMA_ROAMING_MODE_RADIO_DEFAULT = -1;
    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which only permits
     * connections on home networks.
     */
    public static final int CDMA_ROAMING_MODE_HOME = 0;
    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which permits roaming on
     * affiliated networks.
     */
    public static final int CDMA_ROAMING_MODE_AFFILIATED = 1;
    /**
     * Value for {@link CarrierConfigManager#KEY_CDMA_ROAMING_MODE_INT} which permits roaming on
     * any network.
     */
    public static final int CDMA_ROAMING_MODE_ANY = 2;

    /**
     * An unknown carrier id. It could either be subscription unavailable or the subscription
     * carrier cannot be recognized. Unrecognized carriers here means
     * {@link #getSimOperator() MCC+MNC} cannot be identified.
     */
    public static final int UNKNOWN_CARRIER_ID = -1;

    /**
     * An unknown carrier id list version.
     * @hide
     */
    @TestApi
    public static final int UNKNOWN_CARRIER_ID_LIST_VERSION = -1;

    /**
     * Broadcast Action: The subscription carrier identity has changed.
     * This intent could be sent on the following events:
     * <ul>
     *   <li>Subscription absent. Carrier identity could change from a valid id to
     *   {@link TelephonyManager#UNKNOWN_CARRIER_ID}.</li>
     *   <li>Subscription loaded. Carrier identity could change from
     *   {@link TelephonyManager#UNKNOWN_CARRIER_ID} to a valid id.</li>
     *   <li>The subscription carrier is recognized after a remote update.</li>
     * </ul>
     * The intent will have the following extra values:
     * <ul>
     *   <li>{@link #EXTRA_CARRIER_ID} The up-to-date carrier id of the current subscription id.
     *   </li>
     *   <li>{@link #EXTRA_CARRIER_NAME} The up-to-date carrier name of the current subscription.
     *   </li>
     *   <li>{@link #EXTRA_SUBSCRIPTION_ID} The subscription id associated with the changed carrier
     *   identity.
     *   </li>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED =
            "android.telephony.action.SUBSCRIPTION_CARRIER_IDENTITY_CHANGED";

    /**
     * An int extra used with {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} which indicates
     * the updated carrier id returned by {@link TelephonyManager#getSimCarrierId()}.
     * <p>Will be {@link TelephonyManager#UNKNOWN_CARRIER_ID} if the subscription is unavailable or
     * the carrier cannot be identified.
     */
    public static final String EXTRA_CARRIER_ID = "android.telephony.extra.CARRIER_ID";

    /**
     * An string extra used with {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} which
     * indicates the updated carrier name of the current subscription.
     * @see TelephonyManager#getSimCarrierIdName()
     * <p>Carrier name is a user-facing name of the carrier id {@link #EXTRA_CARRIER_ID},
     * usually the brand name of the subsidiary (e.g. T-Mobile).
     */
    public static final String EXTRA_CARRIER_NAME = "android.telephony.extra.CARRIER_NAME";

    /**
     * Broadcast Action: The subscription precise carrier identity has changed.
     * The precise carrier id can be used to further differentiate a carrier by different
     * networks, by prepaid v.s.postpaid or even by 4G v.s.3G plan. Each carrier has a unique
     * carrier id returned by {@link #getSimCarrierId()} but could have multiple precise carrier id.
     * e.g, {@link #getSimCarrierId()} will always return Tracfone (id 2022) for a Tracfone SIM,
     * while {@link #getSimPreciseCarrierId()} can return Tracfone AT&T or Tracfone T-Mobile based
     * on the current subscription IMSI. For carriers without any fine-grained ids, precise carrier
     * id is same as carrier id.
     *
     * <p>Similar like {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED}, this intent will be
     * sent on the event of {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} while its also
     * possible to be sent without {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} when
     * precise carrier id changes with the same carrier id.
     * e.g, the same subscription switches to different IMSI could potentially change its
     * precise carrier id while carrier id remains the same.
     * @see #getSimPreciseCarrierId()
     * @see #getSimCarrierId()
     *
     * The intent will have the following extra values:
     * <ul>
     *   <li>{@link #EXTRA_PRECISE_CARRIER_ID} The up-to-date precise carrier id of the
     *   current subscription.
     *   </li>
     *   <li>{@link #EXTRA_PRECISE_CARRIER_NAME} The up-to-date name of the precise carrier id.
     *   </li>
     *   <li>{@link #EXTRA_SUBSCRIPTION_ID} The subscription id associated with the changed carrier
     *   identity.
     *   </li>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SUBSCRIPTION_PRECISE_CARRIER_IDENTITY_CHANGED =
            "android.telephony.action.SUBSCRIPTION_PRECISE_CARRIER_IDENTITY_CHANGED";

    /**
     * An int extra used with {@link #ACTION_SUBSCRIPTION_PRECISE_CARRIER_IDENTITY_CHANGED} which
     * indicates the updated precise carrier id returned by
     * {@link TelephonyManager#getSimPreciseCarrierId()}. Note, its possible precise carrier id
     * changes while {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} remains the same
     * e.g, when subscription switch to different IMSIs.
     * <p>Will be {@link TelephonyManager#UNKNOWN_CARRIER_ID} if the subscription is unavailable or
     * the carrier cannot be identified.
     */
    public static final String EXTRA_PRECISE_CARRIER_ID =
            "android.telephony.extra.PRECISE_CARRIER_ID";

    /**
     * An string extra used with {@link #ACTION_SUBSCRIPTION_PRECISE_CARRIER_IDENTITY_CHANGED} which
     * indicates the updated precise carrier name returned by
     * {@link TelephonyManager#getSimPreciseCarrierIdName()}.
     * <p>it's a user-facing name of the precise carrier id {@link #EXTRA_PRECISE_CARRIER_ID}, e.g,
     * Tracfone-AT&T.
     */
    public static final String EXTRA_PRECISE_CARRIER_NAME =
            "android.telephony.extra.PRECISE_CARRIER_NAME";

    /**
     * An int extra used with {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} to indicate the
     * subscription which has changed.
     */
    public static final String EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_ID";

    /**
     * Broadcast intent action indicating that when data stall recovery is attempted by Telephony,
     * intended for report every data stall recovery step attempted.
     *
     * <p>
     * The {@link #EXTRA_RECOVERY_ACTION} extra indicates the action associated with the data
     * stall recovery.
     * The phone id where the data stall recovery is attempted.
     *
     * <p class="note">
     * Requires the READ_PHONE_STATE permission.
     *
     * <p class="note">
     * This is a protected intent that can only be sent by the system.
     *
     * @see #EXTRA_RECOVERY_ACTION
     *
     * @hide
     */
    // TODO(b/78370030) : Restrict this to system applications only
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public static final String ACTION_DATA_STALL_DETECTED =
            "android.intent.action.DATA_STALL_DETECTED";

    /**
     * An int extra used with {@link #ACTION_DATA_STALL_DETECTED} to indicate the
     * action associated with the data stall recovery.
     *
     * @see #ACTION_DATA_STALL_DETECTED
     *
     * @hide
     */
    public static final String EXTRA_RECOVERY_ACTION = "recoveryAction";

    /**
     * The max value for the timeout passed in {@link #requestNumberVerification}.
     * @hide
     */
    @SystemApi
    public static final long MAX_NUMBER_VERIFICATION_TIMEOUT_MILLIS = 60000;

    /**
     * Intent sent when an error occurs that debug tools should log and possibly take further
     * action such as capturing vendor-specific logs.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final String ACTION_DEBUG_EVENT = "android.telephony.action.DEBUG_EVENT";

    /**
     * An arbitrary ParcelUuid which should be consistent for each occurrence of the same event.
     *
     * This field must be included in all events.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_DEBUG_EVENT_ID = "android.telephony.extra.DEBUG_EVENT_ID";

    /**
     * A freeform string description of the event.
     *
     * This field is optional for all events and as a guideline should not exceed 80 characters
     * and should be as short as possible to convey the essence of the event.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_DEBUG_EVENT_DESCRIPTION =
            "android.telephony.extra.DEBUG_EVENT_DESCRIPTION";

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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getDeviceSoftwareVersion() {
        return getDeviceSoftwareVersion(getSlotIndex());
    }

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     *
     * @param slotIndex of which deviceID is returned
     */
    /** {@hide} */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
    public String getDeviceSoftwareVersion(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getDeviceSoftwareVersionForSlot(slotIndex, getOpPackageName());
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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @deprecated Use (@link getImei} which returns IMEI for GSM or (@link getMeid} which returns
     * MEID for CDMA.
     */
    @Deprecated
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex of which deviceID is returned
     *
     * @deprecated Use (@link getImei} which returns IMEI for GSM or (@link getMeid} which returns
     * MEID for CDMA.
     */
    @Deprecated
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getDeviceId(int slotIndex) {
        // FIXME this assumes phoneId == slotIndex
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getDeviceIdForPhone(slotIndex, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the IMEI (International Mobile Equipment Identity). Return null if IMEI is not
     * available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getImei() {
        return getImei(getSlotIndex());
    }

    /**
     * Returns the IMEI (International Mobile Equipment Identity). Return null if IMEI is not
     * available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex of which IMEI is returned
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getImei(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getImeiForSlot(slotIndex, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the Type Allocation Code from the IMEI. Return null if Type Allocation Code is not
     * available.
     */
    public String getTypeAllocationCode() {
        return getTypeAllocationCode(getSlotIndex());
    }

    /**
     * Returns the Type Allocation Code from the IMEI. Return null if Type Allocation Code is not
     * available.
     *
     * @param slotIndex of which Type Allocation Code is returned
     */
    public String getTypeAllocationCode(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getTypeAllocationCodeForSlot(slotIndex);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the MEID (Mobile Equipment Identifier). Return null if MEID is not available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getMeid() {
        return getMeid(getSlotIndex());
    }

    /**
     * Returns the MEID (Mobile Equipment Identifier). Return null if MEID is not available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param slotIndex of which MEID is returned
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getMeid(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getMeidForSlot(slotIndex, getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the Manufacturer Code from the MEID. Return null if Manufacturer Code is not
     * available.
     */
    public String getManufacturerCode() {
        return getManufacturerCode(getSlotIndex());
    }

    /**
     * Returns the Manufacturer Code from the MEID. Return null if Manufacturer Code is not
     * available.
     *
     * @param slotIndex of which Type Allocation Code is returned
     */
    public String getManufacturerCode(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getManufacturerCodeForSlot(slotIndex);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            return null;
        }
    }

    /**
     * Returns the Network Access Identifier (NAI). Return null if NAI is not available.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getNai() {
        return getNaiBySubscriberId(getSubId());
    }

    /**
     * Returns the NAI. Return null if NAI is not available.
     *
     *  @param slotIndex of which Nai is returned
     */
    /** {@hide}*/
    @UnsupportedAppUsage
    public String getNai(int slotIndex) {
        int[] subId = SubscriptionManager.getSubId(slotIndex);
        if (subId == null) {
            return null;
        }
        return getNaiBySubscriberId(subId[0]);
    }

    private String getNaiBySubscriberId(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            String nai = info.getNaiForSubscriber(subId, mContext.getOpPackageName());
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
     * @return Current location of the device or null if not available.
     *
     * @deprecated use {@link #getAllCellInfo} instead, which returns a superset of this API.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public CellLocation getCellLocation() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                Rlog.d(TAG, "getCellLocation returning null because telephony is null");
                return null;
            }

            Bundle bundle = telephony.getCellLocation(mContext.getOpPackageName());
            if (bundle == null || bundle.isEmpty()) {
                Rlog.d(TAG, "getCellLocation returning null because CellLocation is unavailable");
                return null;
            }

            CellLocation cl = CellLocation.newFromBundle(bundle);
            if (cl == null || cl.isEmpty()) {
                Rlog.d(TAG, "getCellLocation returning null because CellLocation is empty or"
                        + " phone type doesn't match CellLocation type");
                return null;
            }

            return cl;
        } catch (RemoteException ex) {
            Rlog.d(TAG, "getCellLocation returning null due to RemoteException " + ex);
            return null;
        }
    }

    /**
     * Enables location update notifications.  {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_LOCATION_UPDATES)
    public void enableLocationUpdates() {
        enableLocationUpdates(getSubId());
    }

    /**
     * Enables location update notifications for a subscription.
     * {@link PhoneStateListener#onCellLocationChanged
     * PhoneStateListener.onCellLocationChanged} will be called on location updates.
     *
     * @param subId for which the location updates are enabled
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_LOCATION_UPDATES)
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
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_LOCATION_UPDATES)
    public void disableLocationUpdates() {
        disableLocationUpdates(getSubId());
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
     * @removed
     * @deprecated Use {@link #getAllCellInfo} which returns a superset of the information
     *             from NeighboringCellInfo, including LTE cell information.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
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
        return getCurrentPhoneType(getSubId());
    }

    /**
     * Returns a constant indicating the device phone type for a subscription.
     *
     * @see #PHONE_TYPE_NONE
     * @see #PHONE_TYPE_GSM
     * @see #PHONE_TYPE_CDMA
     *
     * @param subId for which phone type is returned
     * @hide
     */
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

        return getCurrentPhoneTypeForSlot(phoneId);
    }

    /**
     * See getCurrentPhoneType.
     *
     * @hide
     */
    public int getCurrentPhoneTypeForSlot(int slotIndex) {
        try{
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getActivePhoneTypeForSlot(slotIndex);
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return getPhoneTypeFromProperty(slotIndex);
            }
        } catch (RemoteException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(slotIndex);
        } catch (NullPointerException ex) {
            // This shouldn't happen in the normal case, as a backup we
            // read from the system property.
            return getPhoneTypeFromProperty(slotIndex);
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
        return getPhoneTypeFromProperty(getPhoneId());
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int getPhoneTypeFromProperty(int phoneId) {
        String type = getTelephonyProperty(phoneId,
                TelephonyProperties.CURRENT_ACTIVE_PHONE, null);
        if (type == null || type.isEmpty()) {
            return getPhoneTypeFromNetworkType(phoneId);
        }
        return Integer.parseInt(type);
    }

    private int getPhoneTypeFromNetworkType() {
        return getPhoneTypeFromNetworkType(getPhoneId());
    }

    /** {@hide} */
    private int getPhoneTypeFromNetworkType(int phoneId) {
        // When the system property CURRENT_ACTIVE_PHONE, has not been set,
        // use the system property for default network type.
        // This is a fail safe, and can only happen at first boot.
        String mode = getTelephonyProperty(phoneId, "ro.telephony.default_network", null);
        if (mode != null && !mode.isEmpty()) {
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
        return getNetworkOperatorName(getSubId());
    }

    /**
     * Returns the alphabetic name of current registered operator
     * for a particular subscription.
     * <p>
     * Availability: Only when user is registered to a network. Result may be
     * unreliable on CDMA networks (use {@link #getPhoneType()} to determine if
     * on a CDMA network).
     * @param subId
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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
        return getNetworkOperatorForPhone(getPhoneId());
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
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getNetworkOperator(int subId) {
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
    @UnsupportedAppUsage
    public String getNetworkOperatorForPhone(int phoneId) {
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, "");
     }


    /**
     * Returns the network specifier of the subscription ID pinned to the TelephonyManager. The
     * network specifier is used by {@link
     * android.net.NetworkRequest.Builder#setNetworkSpecifier(String)} to create a {@link
     * android.net.NetworkRequest} that connects through the subscription.
     *
     * @see android.net.NetworkRequest.Builder#setNetworkSpecifier(String)
     * @see #createForSubscriptionId(int)
     * @see #createForPhoneAccountHandle(PhoneAccountHandle)
     */
    public String getNetworkSpecifier() {
        return String.valueOf(getSubId());
    }

    /**
     * Returns the carrier config of the subscription ID pinned to the TelephonyManager. If an
     * invalid subscription ID is pinned to the TelephonyManager, the returned config will contain
     * default values.
     *
     * <p>This method may take several seconds to complete, so it should only be called from a
     * worker thread.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @see CarrierConfigManager#getConfigForSubId(int)
     * @see #createForSubscriptionId(int)
     * @see #createForPhoneAccountHandle(PhoneAccountHandle)
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @WorkerThread
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public PersistableBundle getCarrierConfig() {
        CarrierConfigManager carrierConfigManager = mContext
                .getSystemService(CarrierConfigManager.class);
        return carrierConfigManager.getConfigForSubId(getSubId());
    }

    /**
     * Returns true if the device is considered roaming on the current
     * network, for GSM purposes.
     * <p>
     * Availability: Only when user registered to a network.
     */
    public boolean isNetworkRoaming() {
        return isNetworkRoaming(getSubId());
    }

    /**
     * Returns true if the device is considered roaming on the current
     * network for a subscription.
     * <p>
     * Availability: Only when user registered to a network.
     *
     * @param subId
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isNetworkRoaming(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return Boolean.parseBoolean(getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_OPERATOR_ISROAMING, null));
    }

    /**
     * Returns the ISO country code equivalent of the MCC (Mobile Country Code) of the current
     * registered operator or the cell nearby, if available.
     * .
     * <p>
     * Note: Result may be unreliable on CDMA networks (use {@link #getPhoneType()} to determine
     * if on a CDMA network).
     */
    public String getNetworkCountryIso() {
        return getNetworkCountryIsoForPhone(getPhoneId());
    }

    /**
     * Returns the ISO country code equivalent of the MCC (Mobile Country Code) of the current
     * registered operator or the cell nearby, if available.
     * <p>
     * Note: Result may be unreliable on CDMA networks (use {@link #getPhoneType()} to determine
     * if on a CDMA network).
     *
     * @param subId for which Network CountryIso is returned
     * @hide
     */
    @UnsupportedAppUsage
    public String getNetworkCountryIso(int subId) {
        return getNetworkCountryIsoForPhone(getPhoneId(subId));
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
    @UnsupportedAppUsage
    public String getNetworkCountryIsoForPhone(int phoneId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) return "";
            return telephony.getNetworkCountryIsoForPhone(phoneId);
        } catch (RemoteException ex) {
            return "";
        }
    }

    /*
     * When adding a network type to the list below, make sure to add the correct icon to
     * MobileSignalController.mapIconSets().
     * Do not add negative types.
     */
    /** Network type is unknown */
    public static final int NETWORK_TYPE_UNKNOWN = TelephonyProtoEnums.NETWORK_TYPE_UNKNOWN; // = 0.
    /** Current network is GPRS */
    public static final int NETWORK_TYPE_GPRS = TelephonyProtoEnums.NETWORK_TYPE_GPRS; // = 1.
    /** Current network is EDGE */
    public static final int NETWORK_TYPE_EDGE = TelephonyProtoEnums.NETWORK_TYPE_EDGE; // = 2.
    /** Current network is UMTS */
    public static final int NETWORK_TYPE_UMTS = TelephonyProtoEnums.NETWORK_TYPE_UMTS; // = 3.
    /** Current network is CDMA: Either IS95A or IS95B*/
    public static final int NETWORK_TYPE_CDMA = TelephonyProtoEnums.NETWORK_TYPE_CDMA; // = 4.
    /** Current network is EVDO revision 0*/
    public static final int NETWORK_TYPE_EVDO_0 = TelephonyProtoEnums.NETWORK_TYPE_EVDO_0; // = 5.
    /** Current network is EVDO revision A*/
    public static final int NETWORK_TYPE_EVDO_A = TelephonyProtoEnums.NETWORK_TYPE_EVDO_A; // = 6.
    /** Current network is 1xRTT*/
    public static final int NETWORK_TYPE_1xRTT = TelephonyProtoEnums.NETWORK_TYPE_1XRTT; // = 7.
    /** Current network is HSDPA */
    public static final int NETWORK_TYPE_HSDPA = TelephonyProtoEnums.NETWORK_TYPE_HSDPA; // = 8.
    /** Current network is HSUPA */
    public static final int NETWORK_TYPE_HSUPA = TelephonyProtoEnums.NETWORK_TYPE_HSUPA; // = 9.
    /** Current network is HSPA */
    public static final int NETWORK_TYPE_HSPA = TelephonyProtoEnums.NETWORK_TYPE_HSPA; // = 10.
    /** Current network is iDen */
    public static final int NETWORK_TYPE_IDEN = TelephonyProtoEnums.NETWORK_TYPE_IDEN; // = 11.
    /** Current network is EVDO revision B*/
    public static final int NETWORK_TYPE_EVDO_B = TelephonyProtoEnums.NETWORK_TYPE_EVDO_B; // = 12.
    /** Current network is LTE */
    public static final int NETWORK_TYPE_LTE = TelephonyProtoEnums.NETWORK_TYPE_LTE; // = 13.
    /** Current network is eHRPD */
    public static final int NETWORK_TYPE_EHRPD = TelephonyProtoEnums.NETWORK_TYPE_EHRPD; // = 14.
    /** Current network is HSPA+ */
    public static final int NETWORK_TYPE_HSPAP = TelephonyProtoEnums.NETWORK_TYPE_HSPAP; // = 15.
    /** Current network is GSM */
    public static final int NETWORK_TYPE_GSM = TelephonyProtoEnums.NETWORK_TYPE_GSM; // = 16.
    /** Current network is TD_SCDMA */
    public static final int NETWORK_TYPE_TD_SCDMA =
            TelephonyProtoEnums.NETWORK_TYPE_TD_SCDMA; // = 17.
    /** Current network is IWLAN */
    public static final int NETWORK_TYPE_IWLAN = TelephonyProtoEnums.NETWORK_TYPE_IWLAN; // = 18.
    /** Current network is LTE_CA {@hide} */
    @UnsupportedAppUsage
    public static final int NETWORK_TYPE_LTE_CA = TelephonyProtoEnums.NETWORK_TYPE_LTE_CA; // = 19.
    /** Current network is NR(New Radio) 5G. */
    public static final int NETWORK_TYPE_NR = TelephonyProtoEnums.NETWORK_TYPE_NR; // 20.

    /** Max network type number. Update as new types are added. Don't add negative types. {@hide} */
    public static final int MAX_NETWORK_TYPE = NETWORK_TYPE_NR;

    /** @hide */
    @IntDef({
            NETWORK_TYPE_UNKNOWN,
            NETWORK_TYPE_GPRS,
            NETWORK_TYPE_EDGE,
            NETWORK_TYPE_UMTS,
            NETWORK_TYPE_CDMA,
            NETWORK_TYPE_EVDO_0,
            NETWORK_TYPE_EVDO_A,
            NETWORK_TYPE_1xRTT,
            NETWORK_TYPE_HSDPA,
            NETWORK_TYPE_HSUPA,
            NETWORK_TYPE_HSPA,
            NETWORK_TYPE_IDEN,
            NETWORK_TYPE_EVDO_B,
            NETWORK_TYPE_LTE,
            NETWORK_TYPE_EHRPD,
            NETWORK_TYPE_HSPAP,
            NETWORK_TYPE_GSM,
            NETWORK_TYPE_TD_SCDMA,
            NETWORK_TYPE_IWLAN,
            NETWORK_TYPE_LTE_CA,
            NETWORK_TYPE_NR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkType{}

    /**
     * @return the NETWORK_TYPE_xxxx for current data connection.
     */
    public @NetworkType int getNetworkType() {
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
     * @see #NETWORK_TYPE_NR
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
    public int getNetworkType(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getNetworkTypeForSubscriber(subId, getOpPackageName());
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_UNKNOWN;
            }
        } catch (RemoteException ex) {
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
     *
     * If this object has been created with {@link #createForSubscriptionId}, applies to the given
     * subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
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
     * @see #NETWORK_TYPE_NR
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public @NetworkType int getDataNetworkType() {
        return getDataNetworkType(getSubId(SubscriptionManager.getDefaultDataSubscriptionId()));
    }

    /**
     * Returns a constant indicating the radio technology (network type)
     * currently in use on the device for data transmission for a subscription
     * @return the network type
     *
     * @param subId for which network type is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public @NetworkType int getVoiceNetworkType() {
        return getVoiceNetworkType(getSubId());
    }

    /**
     * Returns the NETWORK_TYPE_xxxx for voice for a subId
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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

    /**
     * Network Class Definitions.
     * Do not change this order, it is used for sorting during emergency calling in
     * {@link TelephonyConnectionService#getFirstPhoneForEmergencyCall()}. Any newer technologies
     * should be added after the current definitions.
     */
    /** Unknown network class. {@hide} */
    public static final int NETWORK_CLASS_UNKNOWN = 0;
    /** Class of broadly defined "2G" networks. {@hide} */
    @UnsupportedAppUsage
    public static final int NETWORK_CLASS_2_G = 1;
    /** Class of broadly defined "3G" networks. {@hide} */
    @UnsupportedAppUsage
    public static final int NETWORK_CLASS_3_G = 2;
    /** Class of broadly defined "4G" networks. {@hide} */
    @UnsupportedAppUsage
    public static final int NETWORK_CLASS_4_G = 3;

    /**
     * Return general class of network type, such as "3G" or "4G". In cases
     * where classification is contentious, this method is conservative.
     *
     * @hide
     */
    @UnsupportedAppUsage
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
            case NETWORK_TYPE_LTE_CA:
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
            case NETWORK_TYPE_LTE_CA:
                return "LTE_CA";
            case NETWORK_TYPE_NR:
                return "NR";
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

    public static final int SIM_STATE_UNKNOWN = TelephonyProtoEnums.SIM_STATE_UNKNOWN;  // 0
    /** SIM card state: no SIM card is available in the device */
    public static final int SIM_STATE_ABSENT = TelephonyProtoEnums.SIM_STATE_ABSENT;  // 1
    /** SIM card state: Locked: requires the user's SIM PIN to unlock */
    public static final int SIM_STATE_PIN_REQUIRED =
            TelephonyProtoEnums.SIM_STATE_PIN_REQUIRED;  // 2
    /** SIM card state: Locked: requires the user's SIM PUK to unlock */
    public static final int SIM_STATE_PUK_REQUIRED =
            TelephonyProtoEnums.SIM_STATE_PUK_REQUIRED;  // 3
    /** SIM card state: Locked: requires a network PIN to unlock */
    public static final int SIM_STATE_NETWORK_LOCKED =
            TelephonyProtoEnums.SIM_STATE_NETWORK_LOCKED;  // 4
    /** SIM card state: Ready */
    public static final int SIM_STATE_READY = TelephonyProtoEnums.SIM_STATE_READY;  // 5
    /** SIM card state: SIM Card is NOT READY */
    public static final int SIM_STATE_NOT_READY = TelephonyProtoEnums.SIM_STATE_NOT_READY;  // 6
    /** SIM card state: SIM Card Error, permanently disabled */
    public static final int SIM_STATE_PERM_DISABLED =
            TelephonyProtoEnums.SIM_STATE_PERM_DISABLED;  // 7
    /** SIM card state: SIM Card Error, present but faulty */
    public static final int SIM_STATE_CARD_IO_ERROR =
            TelephonyProtoEnums.SIM_STATE_CARD_IO_ERROR;  // 8
    /** SIM card state: SIM Card restricted, present but not usable due to
     * carrier restrictions.
     */
    public static final int SIM_STATE_CARD_RESTRICTED =
            TelephonyProtoEnums.SIM_STATE_CARD_RESTRICTED;  // 9
    /**
     * SIM card state: Loaded: SIM card applications have been loaded
     * @hide
     */
    @SystemApi
    public static final int SIM_STATE_LOADED = TelephonyProtoEnums.SIM_STATE_LOADED;  // 10
    /**
     * SIM card state: SIM Card is present
     * @hide
     */
    @SystemApi
    public static final int SIM_STATE_PRESENT = TelephonyProtoEnums.SIM_STATE_PRESENT;  // 11

    /**
     * Extra included in {@link #ACTION_SIM_CARD_STATE_CHANGED} and
     * {@link #ACTION_SIM_APPLICATION_STATE_CHANGED} to indicate the card/application state.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SIM_STATE = "android.telephony.extra.SIM_STATE";

    /**
     * Broadcast Action: The sim card state has changed.
     * The intent will have the following extra values:</p>
     * <dl>
     *   <dt>{@link #EXTRA_SIM_STATE}</dt>
     *   <dd>The sim card state. One of:
     *     <dl>
     *       <dt>{@link #SIM_STATE_ABSENT}</dt>
     *       <dd>SIM card not found</dd>
     *       <dt>{@link #SIM_STATE_CARD_IO_ERROR}</dt>
     *       <dd>SIM card IO error</dd>
     *       <dt>{@link #SIM_STATE_CARD_RESTRICTED}</dt>
     *       <dd>SIM card is restricted</dd>
     *       <dt>{@link #SIM_STATE_PRESENT}</dt>
     *       <dd>SIM card is present</dd>
     *     </dl>
     *   </dd>
     * </dl>
     *
     * <p class="note">Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * <p class="note">The current state can also be queried using {@link #getSimCardState()}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SIM_CARD_STATE_CHANGED =
            "android.telephony.action.SIM_CARD_STATE_CHANGED";

    /**
     * Broadcast Action: The sim application state has changed.
     * The intent will have the following extra values:</p>
     * <dl>
     *   <dt>{@link #EXTRA_SIM_STATE}</dt>
     *   <dd>The sim application state. One of:
     *     <dl>
     *       <dt>{@link #SIM_STATE_NOT_READY}</dt>
     *       <dd>SIM card applications not ready</dd>
     *       <dt>{@link #SIM_STATE_PIN_REQUIRED}</dt>
     *       <dd>SIM card PIN locked</dd>
     *       <dt>{@link #SIM_STATE_PUK_REQUIRED}</dt>
     *       <dd>SIM card PUK locked</dd>
     *       <dt>{@link #SIM_STATE_NETWORK_LOCKED}</dt>
     *       <dd>SIM card network locked</dd>
     *       <dt>{@link #SIM_STATE_PERM_DISABLED}</dt>
     *       <dd>SIM card permanently disabled due to PUK failures</dd>
     *       <dt>{@link #SIM_STATE_LOADED}</dt>
     *       <dd>SIM card data loaded</dd>
     *     </dl>
     *   </dd>
     * </dl>
     *
     * <p class="note">Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * <p class="note">The current state can also be queried using
     * {@link #getSimApplicationState()}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SIM_APPLICATION_STATE_CHANGED =
            "android.telephony.action.SIM_APPLICATION_STATE_CHANGED";

    /**
     * Broadcast Action: Status of the SIM slots on the device has changed.
     *
     * <p class="note">Requires the READ_PRIVILEGED_PHONE_STATE permission.
     *
     * <p class="note">The status can be queried using
     * {@link #getUiccSlotsInfo()}
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SIM_SLOT_STATUS_CHANGED =
            "android.telephony.action.SIM_SLOT_STATUS_CHANGED";

    /**
     * Broadcast Action: A debug code has been entered in the dialer.
     * <p>
     * This intent is broadcast by the system and OEM telephony apps may need to receive these
     * broadcasts. And it requires the sender to be default dialer or has carrier privileges
     * (see {@link #hasCarrierPrivileges}).
     * <p>
     * These "secret codes" are used to activate developer menus by dialing certain codes.
     * And they are of the form {@code *#*#&lt;code&gt;#*#*}. The intent will have the data
     * URI: {@code android_secret_code://&lt;code&gt;}. It is possible that a manifest
     * receiver would be woken up even if it is not currently running.
     * <p>
     * It is supposed to replace {@link android.provider.Telephony.Sms.Intents#SECRET_CODE_ACTION}
     * in the next Android version.
     * Before that both of these two actions will be broadcast.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SECRET_CODE = "android.telephony.action.SECRET_CODE";

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        return hasIccCard(getSlotIndex());
    }

    /**
     * @return true if a ICC card is present for a subscription
     *
     * @param slotIndex for which icc card presence is checked
     */
    /** {@hide} */
    // FIXME Input argument slotIndex should be of type int
    @UnsupportedAppUsage
    public boolean hasIccCard(int slotIndex) {

        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return false;
            return telephony.hasIccCardUsingSlotIndex(slotIndex);
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
     * @see #SIM_STATE_CARD_RESTRICTED
     */
    public int getSimState() {
        int simState = getSimStateIncludingLoaded();
        if (simState == SIM_STATE_LOADED) {
            simState = SIM_STATE_READY;
        }
        return simState;
    }

    private int getSimStateIncludingLoaded() {
        int slotIndex = getSlotIndex();
        // slotIndex may be invalid due to sim being absent. In that case query all slots to get
        // sim state
        if (slotIndex < 0) {
            // query for all slots and return absent if all sim states are absent, otherwise
            // return unknown
            for (int i = 0; i < getPhoneCount(); i++) {
                int simState = getSimState(i);
                if (simState != SIM_STATE_ABSENT) {
                    Rlog.d(TAG, "getSimState: default sim:" + slotIndex + ", sim state for " +
                            "slotIndex=" + i + " is " + simState + ", return state as unknown");
                    return SIM_STATE_UNKNOWN;
                }
            }
            Rlog.d(TAG, "getSimState: default sim:" + slotIndex + ", all SIMs absent, return " +
                    "state as absent");
            return SIM_STATE_ABSENT;
        }
        return SubscriptionManager.getSimStateForSlotIndex(slotIndex);
    }

    /**
     * Returns a constant indicating the state of the default SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_ABSENT
     * @see #SIM_STATE_CARD_IO_ERROR
     * @see #SIM_STATE_CARD_RESTRICTED
     * @see #SIM_STATE_PRESENT
     *
     * @hide
     */
    @SystemApi
    public int getSimCardState() {
        int simCardState = getSimState();
        switch (simCardState) {
            case SIM_STATE_UNKNOWN:
            case SIM_STATE_ABSENT:
            case SIM_STATE_CARD_IO_ERROR:
            case SIM_STATE_CARD_RESTRICTED:
                return simCardState;
            default:
                return SIM_STATE_PRESENT;
        }
    }

    /**
     * Returns a constant indicating the state of the card applications on the default SIM card.
     *
     * @see #SIM_STATE_UNKNOWN
     * @see #SIM_STATE_PIN_REQUIRED
     * @see #SIM_STATE_PUK_REQUIRED
     * @see #SIM_STATE_NETWORK_LOCKED
     * @see #SIM_STATE_NOT_READY
     * @see #SIM_STATE_PERM_DISABLED
     * @see #SIM_STATE_LOADED
     *
     * @hide
     */
    @SystemApi
    public int getSimApplicationState() {
        int simApplicationState = getSimStateIncludingLoaded();
        switch (simApplicationState) {
            case SIM_STATE_UNKNOWN:
            case SIM_STATE_ABSENT:
            case SIM_STATE_CARD_IO_ERROR:
            case SIM_STATE_CARD_RESTRICTED:
                return SIM_STATE_UNKNOWN;
            case SIM_STATE_READY:
                // Ready is not a valid state anymore. The state that is broadcast goes from
                // NOT_READY to either LOCKED or LOADED.
                return SIM_STATE_NOT_READY;
            default:
                return simApplicationState;
        }
    }

    /**
     * Returns a constant indicating the state of the device SIM card in a slot.
     *
     * @param slotIndex
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
     * @see #SIM_STATE_CARD_RESTRICTED
     */
    public int getSimState(int slotIndex) {
        int simState = SubscriptionManager.getSimStateForSlotIndex(slotIndex);
        if (simState == SIM_STATE_LOADED) {
            simState = SIM_STATE_READY;
        }
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getSimOperator(int subId) {
        return getSimOperatorNumeric(subId);
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getSimOperatorNumeric() {
        int subId = mSubId;
        if (!SubscriptionManager.isUsableSubIdValue(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                subId = SubscriptionManager.getDefaultSmsSubscriptionId();
                if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                    subId = SubscriptionManager.getDefaultVoiceSubscriptionId();
                    if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                        subId = SubscriptionManager.getDefaultSubscriptionId();
                    }
                }
            }
        }
        return getSimOperatorNumeric(subId);
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getSimOperatorNumeric(int subId) {
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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
        return getSimOperatorNameForPhone(getPhoneId());
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getSimOperatorName(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimOperatorNameForPhone(phoneId);
    }

    /**
     * Returns the Service Provider Name (SPN).
     *
     * @hide
     */
    @UnsupportedAppUsage
    public String getSimOperatorNameForPhone(int phoneId) {
         return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "");
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     */
    public String getSimCountryIso() {
        return getSimCountryIsoForPhone(getPhoneId());
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @param subId for which SimCountryIso is returned
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getSimCountryIso(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimCountryIsoForPhone(phoneId);
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public String getSimCountryIsoForPhone(int phoneId) {
        return getTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
    }

    /**
     * Returns the serial number of the SIM, if applicable. Return null if it is
     * unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getSimSerialNumber() {
         return getSimSerialNumber(getSubId());
    }

    /**
     * Returns the serial number for the given subscription, if applicable. Return null if it is
     * unavailable.
     * <p>
     * @param subId for which Sim Serial number is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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
     * Return if the current radio is LTE on CDMA. This is a tri-state return value as for a period
     * of time the mode may be unknown.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
    public int getLteOnCdmaMode() {
        return getLteOnCdmaMode(getSubId());
    }

    /**
     * Return if the current radio is LTE on CDMA for Subscription. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @param subId for which radio is LTE on CDMA is returned
     * @return {@link PhoneConstants#LTE_ON_CDMA_UNKNOWN}, {@link PhoneConstants#LTE_ON_CDMA_FALSE}
     * or {@link PhoneConstants#LTE_ON_CDMA_TRUE}
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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

    /**
     * Get the card ID of the default eUICC card. If the eUICCs have not yet been loaded, returns
     * {@link #UNINITIALIZED_CARD_ID}. If there is no eUICC or the device does not support card IDs
     * for eUICCs, returns {@link #UNSUPPORTED_CARD_ID}.
     *
     * <p>The card ID is a unique identifier associated with a UICC or eUICC card. Card IDs are
     * unique to a device, and always refer to the same UICC or eUICC card unless the device goes
     * through a factory reset.
     *
     * @return card ID of the default eUICC card, if loaded.
     */
    public int getCardIdForDefaultEuicc() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return UNINITIALIZED_CARD_ID;
            }
            return telephony.getCardIdForDefaultEuicc(mSubId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            return UNINITIALIZED_CARD_ID;
        }
    }

    /**
     * Gets information about currently inserted UICCs and enabled eUICCs.
     * <p>
     * Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     * <p>
     * If the caller has carrier priviliges on any active subscription, then they have permission to
     * get simple information like the card ID ({@link UiccCardInfo#getCardId()}), whether the card
     * is an eUICC ({@link UiccCardInfo#isEuicc()}), and the slot index where the card is inserted
     * ({@link UiccCardInfo#getSlotIndex()}).
     * <p>
     * To get private information such as the EID ({@link UiccCardInfo#getEid()}) or ICCID
     * ({@link UiccCardInfo#getIccId()}), the caller must have carrier priviliges on that specific
     * UICC or eUICC card.
     * <p>
     * See {@link UiccCardInfo} for more details on the kind of information available.
     *
     * @return a list of UiccCardInfo objects, representing information on the currently inserted
     * UICCs and eUICCs. Each UiccCardInfo in the list will have private information filtered out if
     * the caller does not have adequate permissions for that card.
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public List<UiccCardInfo> getUiccCardsInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                Log.e(TAG, "Error in getUiccCardsInfo: unable to connect to Telephony service.");
                return new ArrayList<UiccCardInfo>();
            }
            return telephony.getUiccCardsInfo(mContext.getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Error in getUiccCardsInfo: " + e);
            return new ArrayList<UiccCardInfo>();
        }
    }

    /**
     * Gets all the UICC slots. The objects in the array can be null if the slot info is not
     * available, which is possible between phone process starting and getting slot info from modem.
     *
     * @return UiccSlotInfo array.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public UiccSlotInfo[] getUiccSlotsInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return null;
            }
            return telephony.getUiccSlotsInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Test method to reload the UICC profile.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void refreshUiccProfile() {
        try {
            ITelephony telephony = getITelephony();
            telephony.refreshUiccProfile(mSubId);
        } catch (RemoteException ex) {
            Rlog.w(TAG, "RemoteException", ex);
        }
    }

    /**
     * Map logicalSlot to physicalSlot, and activate the physicalSlot if it is inactive. For
     * example, passing the physicalSlots array [1, 0] means mapping the first item 1, which is
     * physical slot index 1, to the logical slot 0; and mapping the second item 0, which is
     * physical slot index 0, to the logical slot 1. The index of the array means the index of the
     * logical slots.
     *
     * @param physicalSlots The content of the array represents the physical slot index. The array
     *        size should be same as {@link #getUiccSlotsInfo()}.
     * @return boolean Return true if the switch succeeds, false if the switch fails.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean switchSlots(int[] physicalSlots) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return false;
            }
            return telephony.switchSlots(physicalSlots);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Get the mapping from logical slots to physical slots. The mapping represent by a pair list.
     * The key of the piar is the logical slot id and the value of the pair is the physical
     * slots id mapped to this logical slot id.
     *
     * @return an pair list indicates the mapping from logical slots to physical slots. The size of
     * the list should be {@link #getPhoneCount()} if success, otherwise return an empty list.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull
    public List<Pair<Integer, Integer>> getLogicalToPhysicalSlotMapping() {
        List<Pair<Integer, Integer>> slotMapping = new ArrayList<>();
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int[] slotMappingArray = telephony.getSlotsMapping();
                for (int i = 0; i < slotMappingArray.length; i++) {
                    slotMapping.add(new Pair(i, slotMappingArray[i]));
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getSlotsMapping RemoteException", e);
        }
        return slotMapping;
    }

    //
    //
    // Subscriber Info
    //
    //

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone.
     * Return null if it is unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getSubscriberId() {
        return getSubscriberId(getSubId());
    }

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone
     * for a subscription.
     * Return null if it is unavailable.
     *
     * @param subId whose subscriber id is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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
     * Returns Carrier specific information that will be used to encrypt the IMSI and IMPI.
     * This includes the public key and the key identifier. For multi-sim devices, if no subId
     * has been specified, we will return the value for the dafault data sim.
     * Return null if it is unavailable.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param keyType whether the key is being used for wlan or epdg. Valid key types are
     *        {@link TelephonyManager#KEY_TYPE_EPDG} or
     *        {@link TelephonyManager#KEY_TYPE_WLAN}.
     * @return ImsiEncryptionInfo Carrier specific information that will be used to encrypt the
     *         IMSI and IMPI. This includes the public key and the key identifier. This information
     *         will be stored in the device keystore. The system will return a null when no key was
     *         found, and the carrier does not require a key. The system will throw
     *         IllegalArgumentException when an invalid key is sent or when key is required but
     *         not found.
     * @hide
     */
    public ImsiEncryptionInfo getCarrierInfoForImsiEncryption(int keyType) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null) {
                Rlog.e(TAG,"IMSI error: Subscriber Info is null");
                return null;
            }
            int subId = getSubId(SubscriptionManager.getDefaultDataSubscriptionId());
            if (keyType != KEY_TYPE_EPDG && keyType != KEY_TYPE_WLAN) {
                throw new IllegalArgumentException("IMSI error: Invalid key type");
            }
            ImsiEncryptionInfo imsiEncryptionInfo = info.getCarrierInfoForImsiEncryption(
                    subId, keyType, mContext.getOpPackageName());
            if (imsiEncryptionInfo == null && isImsiEncryptionRequired(subId, keyType)) {
                Rlog.e(TAG, "IMSI error: key is required but not found");
                throw new IllegalArgumentException("IMSI error: key is required but not found");
            }
            return imsiEncryptionInfo;
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierInfoForImsiEncryption RemoteException" + ex);
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            Rlog.e(TAG, "getCarrierInfoForImsiEncryption NullPointerException" + ex);
        }
        return null;
    }

    /**
     * Resets the Carrier Keys in the database. This involves 2 steps:
     *  1. Delete the keys from the database.
     *  2. Send an intent to download new Certificates.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * @hide
     */
    public void resetCarrierKeysForImsiEncryption() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null) {
                Rlog.e(TAG, "IMSI error: Subscriber Info is null");
                if (!isSystemProcess()) {
                    throw new RuntimeException("IMSI error: Subscriber Info is null");
                }
                return;
            }
            int subId = getSubId(SubscriptionManager.getDefaultDataSubscriptionId());
            info.resetCarrierKeysForImsiEncryption(subId, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierInfoForImsiEncryption RemoteException" + ex);
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

   /**
     * @param keyAvailability bitmask that defines the availabilty of keys for a type.
     * @param keyType the key type which is being checked. (WLAN, EPDG)
     * @return true if the digit at position keyType is 1, else false.
     * @hide
     */
    private static boolean isKeyEnabled(int keyAvailability, int keyType) {
        int returnValue = (keyAvailability >> (keyType - 1)) & 1;
        return (returnValue == 1) ? true : false;
    }

    /**
     * If Carrier requires Imsi to be encrypted.
     * @hide
     */
    private boolean isImsiEncryptionRequired(int subId, int keyType) {
        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            return false;
        }
        PersistableBundle pb = configManager.getConfigForSubId(subId);
        if (pb == null) {
            return false;
        }
        int keyAvailability = pb.getInt(CarrierConfigManager.IMSI_KEY_AVAILABILITY_INT);
        return isKeyEnabled(keyAvailability, keyType);
    }

    /**
     * Sets the Carrier specific information that will be used to encrypt the IMSI and IMPI.
     * This includes the public key and the key identifier. This information will be stored in the
     * device keystore.
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * @param imsiEncryptionInfo which includes the Key Type, the Public Key
     *        (java.security.PublicKey) and the Key Identifier.and the Key Identifier.
     *        The keyIdentifier Attribute value pair that helps a server locate
     *        the private key to decrypt the permanent identity. This field is
     *        optional and if it is present then it’s always separated from encrypted
     *        permanent identity with “,”. Key identifier AVP is presented in ASCII string
     *        with “name=value” format.
     * @hide
     */
    public void setCarrierInfoForImsiEncryption(ImsiEncryptionInfo imsiEncryptionInfo) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null) return;
            info.setCarrierInfoForImsiEncryption(mSubId, mContext.getOpPackageName(),
                    imsiEncryptionInfo);
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return;
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setCarrierInfoForImsiEncryption RemoteException", ex);
            return;
        }
    }

    /**
     * Returns the Group Identifier Level1 for a GSM phone.
     * Return null if it is unavailable.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getGroupIdLevel1() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getGroupIdLevel1ForSubscriber(getSubId(), mContext.getOpPackageName());
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
     *
     * @param subId whose subscriber id is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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
     *
     * <p>Requires Permission:
     *     {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE},
     *     {@link android.Manifest.permission#READ_SMS READ_SMS},
     *     {@link android.Manifest.permission#READ_PHONE_NUMBERS READ_PHONE_NUMBERS},
     *     that the caller is the default SMS app,
     *     or that the caller has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges or default SMS app
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_PHONE_NUMBERS
    })
    public String getLine1Number() {
        return getLine1Number(getSubId());
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone for a particular subscription. Return null if it is unavailable.
     * <p>
     * The default SMS app can also use this.
     *
     * @param subId whose phone number for line 1 is returned
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_PHONE_NUMBERS
    })
    @UnsupportedAppUsage
    public String getLine1Number(int subId) {
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
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     * @return true if the operation was executed correctly.
     */
    public boolean setLine1NumberForDisplay(String alphaTag, String number) {
        return setLine1NumberForDisplay(getSubId(), alphaTag, number);
    }

    /**
     * Set the line 1 phone number string and its alphatag for the current ICCID
     * for display purpose only, for example, displayed in Phone Status. It won't
     * change the actual MSISDN/MDN. To unset alphatag or number, pass in a null
     * value.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId the subscriber that the alphatag and dialing number belongs to.
     * @param alphaTag alpha-tagging of the dailing nubmer
     * @param number The dialing number
     * @return true if the operation was executed correctly.
     * @hide
     */
    public boolean setLine1NumberForDisplay(int subId, String alphaTag, String number) {
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
     * @hide
     * nobody seems to call this.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getLine1AlphaTag() {
        return getLine1AlphaTag(getSubId());
    }

    /**
     * Returns the alphabetic identifier associated with the line 1 number
     * for a subscription.
     * Return null if it is unavailable.
     * @param subId whose alphabetic identifier associated with line 1 is returned
     * nobody seems to call this.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
    public String getLine1AlphaTag(int subId) {
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
    @UnsupportedAppUsage
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
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
    public String getMsisdn() {
        return getMsisdn(getSubId());
    }

    /**
     * Returns the MSISDN string.
     * for a GSM phone. Return null if it is unavailable.
     *
     * @param subId for which msisdn is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVoiceMailNumber() {
        return getVoiceMailNumber(getSubId());
    }

    /**
     * Returns the voice mail number for a subscription.
     * Return null if it is unavailable.
     * @param subId whose voice mail number is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CALL_PRIVILEGED)
    @UnsupportedAppUsage
    public String getCompleteVoiceMailNumber() {
        return getCompleteVoiceMailNumber(getSubId());
    }

    /**
     * Returns the complete voice mail number. Return null if it is unavailable.
     *
     * @param subId
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CALL_PRIVILEGED)
    @UnsupportedAppUsage
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
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param alphaTag The alpha tag to display.
     * @param number The voicemail number.
     */
    public boolean setVoiceMailNumber(String alphaTag, String number) {
        return setVoiceMailNumber(getSubId(), alphaTag, number);
    }

    /**
     * Sets the voicemail number for the given subscriber.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     * @param alphaTag The alpha tag to display.
     * @param number The voicemail number.
     * @hide
     */
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
     * Enables or disables the visual voicemail client for a phone account.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges (see
     * {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle the phone account to change the client state
     * @param enabled the new state of the client
     * @hide
     * @deprecated Visual voicemail no longer in telephony. {@link VisualVoicemailService} should
     * be implemented instead.
     */
    @SystemApi
    @SuppressLint("Doclava125")
    public void setVisualVoicemailEnabled(PhoneAccountHandle phoneAccountHandle, boolean enabled){
    }

    /**
     * Returns whether the visual voicemail client is enabled.
     *
     * @param phoneAccountHandle the phone account to check for.
     * @return {@code true} when the visual voicemail client is enabled for this client
     * @hide
     * @deprecated Visual voicemail no longer in telephony. {@link VisualVoicemailService} should
     * be implemented instead.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @SuppressLint("Doclava125")
    public boolean isVisualVoicemailEnabled(PhoneAccountHandle phoneAccountHandle){
        return false;
    }

    /**
     * Returns an opaque bundle of settings formerly used by the visual voicemail client for the
     * subscription ID pinned to the TelephonyManager, or {@code null} if the subscription ID is
     * invalid. This method allows the system dialer to migrate settings out of the pre-O visual
     * voicemail client in telephony.
     *
     * <p>Requires the caller to be the system dialer.
     *
     * @see #KEY_VISUAL_VOICEMAIL_ENABLED_BY_USER_BOOL
     * @see #KEY_VOICEMAIL_SCRAMBLED_PIN_STRING
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("Doclava125")
    @Nullable
    public Bundle getVisualVoicemailSettings(){
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony
                        .getVisualVoicemailSettings(mContext.getOpPackageName(), mSubId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Returns the package responsible of processing visual voicemail for the subscription ID pinned
     * to the TelephonyManager. Returns {@code null} when there is no package responsible for
     * processing visual voicemail for the subscription.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @see #createForSubscriptionId(int)
     * @see #createForPhoneAccountHandle(PhoneAccountHandle)
     * @see VisualVoicemailService
     */
    @Nullable
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVisualVoicemailPackageName() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony
                        .getVisualVoicemailPackageName(mContext.getOpPackageName(), getSubId());
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Set the visual voicemail SMS filter settings for the subscription ID pinned
     * to the TelephonyManager.
     * When the filter is enabled, {@link
     * VisualVoicemailService#onSmsReceived(VisualVoicemailTask, VisualVoicemailSms)} will be
     * called when a SMS matching the settings is received. Caller must be the default dialer,
     * system dialer, or carrier visual voicemail app.
     *
     * @param settings The settings for the filter, or {@code null} to disable the filter.
     *
     * @see {@link TelecomManager#getDefaultDialerPackage()}
     * @see {@link CarrierConfigManager#KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY}
     */
    public void setVisualVoicemailSmsFilterSettings(VisualVoicemailSmsFilterSettings settings) {
        if (settings == null) {
            disableVisualVoicemailSmsFilter(mSubId);
        } else {
            enableVisualVoicemailSmsFilter(mSubId, settings);
        }
    }

    /**
     * Send a visual voicemail SMS. The caller must be the current default dialer.
     * A {@link VisualVoicemailService} uses this method to send a command via SMS to the carrier's
     * visual voicemail server.  Some examples for carriers using the OMTP standard include
     * activating and deactivating visual voicemail, or requesting the current visual voicemail
     * provisioning status.  See the OMTP Visual Voicemail specification for more information on the
     * format of these SMS messages.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#SEND_SMS SEND_SMS}
     *
     * @param number The destination number.
     * @param port The destination port for data SMS, or 0 for text SMS.
     * @param text The message content. For data sms, it will be encoded as a UTF-8 byte stream.
     * @param sentIntent The sent intent passed to the {@link SmsManager}
     *
     * @throws SecurityException if the caller is not the current default dialer
     *
     * @see SmsManager#sendDataMessage(String, String, short, byte[], PendingIntent, PendingIntent)
     * @see SmsManager#sendTextMessage(String, String, String, PendingIntent, PendingIntent)
     */
    public void sendVisualVoicemailSms(String number, int port, String text,
            PendingIntent sentIntent) {
        sendVisualVoicemailSmsForSubscriber(mSubId, number, port, text, sentIntent);
    }

    /**
     * Enables the visual voicemail SMS filter for a phone account. When the filter is
     * enabled, Incoming SMS messages matching the OMTP VVM SMS interface will be redirected to the
     * visual voicemail client with
     * {@link android.provider.VoicemailContract.ACTION_VOICEMAIL_SMS_RECEIVED}.
     *
     * <p>This takes effect only when the caller is the default dialer. The enabled status and
     * settings persist through default dialer changes, but the filter will only honor the setting
     * set by the current default dialer.
     *
     *
     * @param subId The subscription id of the phone account.
     * @param settings The settings for the filter.
     */
    /** @hide */
    public void enableVisualVoicemailSmsFilter(int subId,
            VisualVoicemailSmsFilterSettings settings) {
        if(settings == null){
            throw new IllegalArgumentException("Settings cannot be null");
        }
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.enableVisualVoicemailSmsFilter(mContext.getOpPackageName(), subId,
                        settings);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Disables the visual voicemail SMS filter for a phone account.
     *
     * <p>This takes effect only when the caller is the default dialer. The enabled status and
     * settings persist through default dialer changes, but the filter will only honor the setting
     * set by the current default dialer.
     */
    /** @hide */
    public void disableVisualVoicemailSmsFilter(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.disableVisualVoicemailSmsFilter(mContext.getOpPackageName(), subId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * @returns the settings of the visual voicemail SMS filter for a phone account, or {@code null}
     * if the filter is disabled.
     *
     * <p>This takes effect only when the caller is the default dialer. The enabled status and
     * settings persist through default dialer changes, but the filter will only honor the setting
     * set by the current default dialer.
     */
    /** @hide */
    @Nullable
    public VisualVoicemailSmsFilterSettings getVisualVoicemailSmsFilterSettings(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony
                        .getVisualVoicemailSmsFilterSettings(mContext.getOpPackageName(), subId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }

        return null;
    }

    /**
     * @returns the settings of the visual voicemail SMS filter for a phone account set by the
     * current active visual voicemail client, or {@code null} if the filter is disabled.
     *
     * <p>Requires the calling app to have READ_PRIVILEGED_PHONE_STATE permission.
     */
    /** @hide */
    @Nullable
    public VisualVoicemailSmsFilterSettings getActiveVisualVoicemailSmsFilterSettings(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getActiveVisualVoicemailSmsFilterSettings(subId);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }

        return null;
    }

    /**
     * Send a visual voicemail SMS. The IPC caller must be the current default dialer.
     *
     * @param phoneAccountHandle The account to send the SMS with.
     * @param number The destination number.
     * @param port The destination port for data SMS, or 0 for text SMS.
     * @param text The message content. For data sms, it will be encoded as a UTF-8 byte stream.
     * @param sentIntent The sent intent passed to the {@link SmsManager}
     *
     * @see SmsManager#sendDataMessage(String, String, short, byte[], PendingIntent, PendingIntent)
     * @see SmsManager#sendTextMessage(String, String, String, PendingIntent, PendingIntent)
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SEND_SMS)
    public void sendVisualVoicemailSmsForSubscriber(int subId, String number, int port,
            String text, PendingIntent sentIntent) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.sendVisualVoicemailSmsForSubscriber(
                        mContext.getOpPackageName(), subId, number, port, text, sentIntent);
            }
        } catch (RemoteException ex) {
        }
    }

    /**
     * Initial SIM activation state, unknown. Not set by any carrier apps.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_UNKNOWN = 0;

    /**
     * indicate SIM is under activation procedure now.
     * intermediate state followed by another state update with activation procedure result:
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_ACTIVATING = 1;

    /**
     * Indicate SIM has been successfully activated with full service
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_ACTIVATED = 2;

    /**
     * Indicate SIM has been deactivated by the carrier so that service is not available
     * and requires activation service to enable services.
     * Carrier apps could be signalled to set activation state to deactivated if detected
     * deactivated sim state and set it back to activated after successfully run activation service.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_DEACTIVATED = 3;

    /**
     * Restricted state indicate SIM has been activated but service are restricted.
     * note this is currently available for data activation state. For example out of byte sim.
     * @hide
     */
    @SystemApi
    public static final int SIM_ACTIVATION_STATE_RESTRICTED = 4;

    /** @hide */
    @IntDef({
            SIM_ACTIVATION_STATE_UNKNOWN,
            SIM_ACTIVATION_STATE_ACTIVATING,
            SIM_ACTIVATION_STATE_ACTIVATED,
            SIM_ACTIVATION_STATE_DEACTIVATED,
            SIM_ACTIVATION_STATE_RESTRICTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SimActivationState{}

     /**
      * Sets the voice activation state
      *
      * <p>Requires Permission:
      * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
      * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
      *
      * @param activationState The voice activation state
      * @see #SIM_ACTIVATION_STATE_UNKNOWN
      * @see #SIM_ACTIVATION_STATE_ACTIVATING
      * @see #SIM_ACTIVATION_STATE_ACTIVATED
      * @see #SIM_ACTIVATION_STATE_DEACTIVATED
      * @hide
      */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoiceActivationState(@SimActivationState int activationState) {
        setVoiceActivationState(getSubId(), activationState);
    }

    /**
     * Sets the voice activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     * @param activationState The voice activation state of the given subscriber.
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoiceActivationState(int subId, @SimActivationState int activationState) {
        try {
           ITelephony telephony = getITelephony();
           if (telephony != null)
               telephony.setVoiceActivationState(subId, activationState);
       } catch (RemoteException ex) {
       } catch (NullPointerException ex) {
       }
    }

    /**
     * Sets the data activation state
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param activationState The data activation state
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataActivationState(@SimActivationState int activationState) {
        setDataActivationState(getSubId(), activationState);
    }

    /**
     * Sets the data activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     * @param activationState The data activation state of the given subscriber.
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataActivationState(int subId, @SimActivationState int activationState) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setDataActivationState(subId, activationState);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
    }

    /**
     * Returns the voice activation state
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return voiceActivationState
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getVoiceActivationState() {
        return getVoiceActivationState(getSubId());
    }

    /**
     * Returns the voice activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     *
     * @return voiceActivationState for the given subscriber
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getVoiceActivationState(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getVoiceActivationState(subId, getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return SIM_ACTIVATION_STATE_UNKNOWN;
    }

    /**
     * Returns the data activation state
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return dataActivationState for the given subscriber
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getDataActivationState() {
        return getDataActivationState(getSubId());
    }

    /**
     * Returns the data activation state for the given subscriber.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription id.
     *
     * @return dataActivationState for the given subscriber
     * @see #SIM_ACTIVATION_STATE_UNKNOWN
     * @see #SIM_ACTIVATION_STATE_ACTIVATING
     * @see #SIM_ACTIVATION_STATE_ACTIVATED
     * @see #SIM_ACTIVATION_STATE_DEACTIVATED
     * @see #SIM_ACTIVATION_STATE_RESTRICTED
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimActivationState int getDataActivationState(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getDataActivationState(subId, getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return SIM_ACTIVATION_STATE_UNKNOWN;
    }

    /**
     * Returns the voice mail count. Return 0 if unavailable, -1 if there are unread voice messages
     * but the count is unknown.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
    public int getVoiceMessageCount() {
        return getVoiceMessageCount(getSubId());
    }

    /**
     * Returns the voice mail count for a subscription. Return 0 if unavailable or the caller does
     * not have the READ_PHONE_STATE permission.
     * @param subId whose voice message count is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
    public int getVoiceMessageCount(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return 0;
            return telephony.getVoiceMessageCountForSubscriber(subId, getOpPackageName());
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
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getVoiceMailAlphaTag() {
        return getVoiceMailAlphaTag(getSubId());
    }

    /**
     * Retrieves the alphabetic identifier associated with the voice
     * mail number for a subscription.
     * @param subId whose alphabetic identifier associated with the
     * voice mail number is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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
     * Send the special dialer code. The IPC caller must be the current default dialer or have
     * carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param inputCode The special dialer code to send
     *
     * @throws SecurityException if the caller does not have carrier privileges or is not the
     *         current default dialer
     */
    public void sendDialerSpecialCode(String inputCode) {
        try {
            final ITelephony telephony = getITelephony();
            if (telephony == null) {
                if (!isSystemProcess()) {
                    throw new RuntimeException("Telephony service unavailable");
                }
                return;
            }
            telephony.sendDialerSpecialCode(mContext.getOpPackageName(), inputCode);
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Returns the IMS private user identity (IMPI) that was loaded from the ISIM.
     * @return the IMPI, or null if not present or not loaded
     * @hide
     */
    @UnsupportedAppUsage
    public String getIsimImpi() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Impi based on subId
            return info.getIsimImpi(getSubId());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the IMS home network domain name that was loaded from the ISIM {@see #APPTYPE_ISIM}.
     * @return the IMS domain name. Returns {@code null} if ISIM hasn't been loaded or IMS domain
     * hasn't been loaded or isn't present on the ISIM.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getIsimDomain() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Domain based on subId
            return info.getIsimDomain(getSubId());
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
    @UnsupportedAppUsage
    public String[] getIsimImpu() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Impu based on subId
            return info.getIsimImpu(getSubId());
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
    @UnsupportedAppUsage
    private IPhoneSubInfo getSubscriberInfo() {
        // get it each time because that process crashes a lot
        return IPhoneSubInfo.Stub.asInterface(ServiceManager.getService("iphonesubinfo"));
    }

    /**
     * Device call state: No activity.
     */
    public static final int CALL_STATE_IDLE = 0;
    /**
     * Device call state: Ringing. A new call arrived and is
     *  ringing or waiting. In the latter case, another call is
     *  already active.
     */
    public static final int CALL_STATE_RINGING = 1;
    /**
     * Device call state: Off-hook. At least one call exists
     * that is dialing, active, or on hold, and no calls are ringing
     * or waiting.
     */
    public static final int CALL_STATE_OFFHOOK = 2;

    /** @hide */
    @IntDef(prefix = { "CALL_STATE_" }, value = {
            CALL_STATE_IDLE,
            CALL_STATE_RINGING,
            CALL_STATE_OFFHOOK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallState{}

    /**
     * Returns the state of all calls on the device.
     * <p>
     * This method considers not only calls in the Telephony stack, but also calls via other
     * {@link android.telecom.ConnectionService} implementations.
     * <p>
     * Note: The call state returned via this method may differ from what is reported by
     * {@link PhoneStateListener#onCallStateChanged(int, String)}, as that callback only considers
     * Telephony (mobile) calls.
     *
     * @return the current call state.
     */
    public @CallState int getCallState() {
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
     * Returns the Telephony call state for calls on a specific subscription.
     * <p>
     * Note: This method considers ONLY telephony/mobile calls, where {@link #getCallState()}
     * considers the state of calls from other {@link android.telecom.ConnectionService}
     * implementations.
     *
     * @param subId the subscription to check call state for.
     * @hide
     */
    @UnsupportedAppUsage
    public @CallState int getCallState(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getCallStateForSlot(phoneId);
    }

    /**
     * Returns the Telephony call state for calls on a specific SIM slot.
     * <p>
     * Note: This method considers ONLY telephony/mobile calls, where {@link #getCallState()}
     * considers the state of calls from other {@link android.telecom.ConnectionService}
     * implementations.
     *
     * @param slotIndex the SIM slot index to check call state for.
     * @hide
     */
    public @CallState int getCallStateForSlot(int slotIndex) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return CALL_STATE_IDLE;
            return telephony.getCallStateForSlot(slotIndex);
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

    /** @hide */
    @IntDef(prefix = {"DATA_"}, value = {
            DATA_UNKNOWN,
            DATA_DISCONNECTED,
            DATA_CONNECTING,
            DATA_CONNECTED,
            DATA_SUSPENDED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataState{}

    /** Data connection state: Unknown.  Used before we know the state. */
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE));
    }

    /**
    * @hide
    */
    private ITelecomService getTelecomService() {
        return ITelecomService.Stub.asInterface(ServiceManager.getService(TELECOM_SERVICE));
    }

    private ITelephonyRegistry getTelephonyRegistry() {
        return ITelephonyRegistry.Stub.asInterface(ServiceManager.getService("telephony.registry"));
    }

    private IOns getIOns() {
        return IOns.Stub.asInterface(ServiceManager.getService("ions"));
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
     * Note: if you call this method while in the middle of a binder transaction, you <b>must</b>
     * call {@link android.os.Binder#clearCallingIdentity()} before calling this method. A
     * {@link SecurityException} will be thrown otherwise.
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
            boolean notifyNow = (getITelephony() != null);
            // If the listener has not explicitly set the subId (for example, created with the
            // default constructor), replace the subId so it will listen to the account the
            // telephony manager is created with.
            if (listener.mSubId == null) {
                listener.mSubId = mSubId;
            }

            ITelephonyRegistry registry = getTelephonyRegistry();
            if (registry != null) {
                registry.listenForSubscriber(listener.mSubId, getOpPackageName(),
                        listener.callback, events, notifyNow);
            } else {
                Rlog.w(TAG, "telephony registry not ready.");
            }
        } catch (RemoteException ex) {
            // system process dead
        }
    }

    /**
     * Returns the CDMA ERI icon index to display
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getCdmaEriIconIndex() {
        return getCdmaEriIconIndex(getSubId());
    }

    /**
     * Returns the CDMA ERI icon index to display for a subscription
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getCdmaEriIconMode() {
        return getCdmaEriIconMode(getSubId());
    }

    /**
     * Returns the CDMA ERI icon mode for a subscription.
     * 0 - ON
     * 1 - FLASHING
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String getCdmaEriText() {
        return getCdmaEriText(getSubId());
    }

    /**
     * Returns the CDMA ERI text, of a subscription
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @UnsupportedAppUsage
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
     * Requests all available cell information from all radios on the device including the
     * camped/registered, serving, and neighboring cells.
     *
     * <p>The response can include one or more {@link android.telephony.CellInfoGsm CellInfoGsm},
     * {@link android.telephony.CellInfoCdma CellInfoCdma},
     * {@link android.telephony.CellInfoTdscdma CellInfoTdscdma},
     * {@link android.telephony.CellInfoLte CellInfoLte}, and
     * {@link android.telephony.CellInfoWcdma CellInfoWcdma} objects, in any combination.
     * It is typical to see instances of one or more of any these in the list. In addition, zero
     * or more of the returned objects may be considered registered; that is, their
     * {@link android.telephony.CellInfo#isRegistered CellInfo.isRegistered()}
     * methods may return true, indicating that the cell is being used or would be used for
     * signaling communication if necessary.
     *
     * <p>Beginning with {@link android.os.Build.VERSION_CODES#Q Android Q},
     * if this API results in a change of the cached CellInfo, that change will be reported via
     * {@link android.telephony.PhoneStateListener#onCellInfoChanged onCellInfoChanged()}.
     *
     * <p>Apps targeting {@link android.os.Build.VERSION_CODES#Q Android Q} or higher will no
     * longer trigger a refresh of the cached CellInfo by invoking this API. Instead, those apps
     * will receive the latest cached results, which may not be current. Apps targeting
     * {@link android.os.Build.VERSION_CODES#Q Android Q} or higher that wish to request updated
     * CellInfo should call
     * {@link android.telephony.TelephonyManager#requestCellInfoUpdate requestCellInfoUpdate()};
     * however, in all cases, updates will be rate-limited and are not guaranteed. To determine the
     * recency of CellInfo data, callers should check
     * {@link android.telephony.CellInfo#getTimeStamp CellInfo#getTimeStamp()}.
     *
     * <p>This method returns valid data for devices with
     * {@link android.content.pm.PackageManager#FEATURE_TELEPHONY FEATURE_TELEPHONY}. In cases
     * where only partial information is available for a particular CellInfo entry, unavailable
     * fields will be reported as {@link android.telephony.CellInfo#UNAVAILABLE}. All reported
     * cells will include at least a valid set of technology-specific identification info and a
     * power level measurement.
     *
     * <p>This method is preferred over using {@link
     * android.telephony.TelephonyManager#getCellLocation getCellLocation()}.
     *
     * @return List of {@link android.telephony.CellInfo}; null if cell
     * information is unavailable.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public List<CellInfo> getAllCellInfo() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getAllCellInfo(
                    getOpPackageName());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /** Callback for providing asynchronous {@link CellInfo} on request */
    public abstract static class CellInfoCallback {
        /**
         * Success response to
         * {@link android.telephony.TelephonyManager#requestCellInfoUpdate requestCellInfoUpdate()}.
         *
         * Invoked when there is a response to
         * {@link android.telephony.TelephonyManager#requestCellInfoUpdate requestCellInfoUpdate()}
         * to provide a list of {@link CellInfo}. If no {@link CellInfo} is available then an empty
         * list will be provided. If an error occurs, null will be provided unless the onError
         * callback is overridden.
         *
         * @param cellInfo a list of {@link CellInfo}, an empty list, or null.
         *
         * {@see android.telephony.TelephonyManager#getAllCellInfo getAllCellInfo()}
         */
        public abstract void onCellInfo(@NonNull List<CellInfo> cellInfo);

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"ERROR_"}, value = {ERROR_TIMEOUT, ERROR_MODEM_ERROR})
        public @interface CellInfoCallbackError {}

        /**
         * The system timed out waiting for a response from the Radio.
         */
        public static final int ERROR_TIMEOUT = 1;

        /**
         * The modem returned a failure.
         */
        public static final int ERROR_MODEM_ERROR = 2;

        /**
         * Error response to
         * {@link android.telephony.TelephonyManager#requestCellInfoUpdate requestCellInfoUpdate()}.
         *
         * Invoked when an error condition prevents updated {@link CellInfo} from being fetched
         * and returned from the modem. Callers of requestCellInfoUpdate() should override this
         * function to receive detailed status information in the event of an error. By default,
         * this function will invoke onCellInfo() with null.
         *
         * @param errorCode an error code indicating the type of failure.
         * @param detail a Throwable object with additional detail regarding the failure if
         *     available, otherwise null.
         */
        public void onError(@CellInfoCallbackError int errorCode, @Nullable Throwable detail) {
            // By default, simply invoke the success callback with an empty list.
            onCellInfo(new ArrayList<CellInfo>());
        }
    };

    /**
     * Requests all available cell information from the current subscription for observed
     * camped/registered, serving, and neighboring cells.
     *
     * <p>Any available results from this request will be provided by calls to
     * {@link android.telephony.PhoneStateListener#onCellInfoChanged onCellInfoChanged()}
     * for each active subscription.
     *
     * @param executor the executor on which callback will be invoked.
     * @param callback a callback to receive CellInfo.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void requestCellInfoUpdate(
            @NonNull @CallbackExecutor Executor executor, @NonNull CellInfoCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) return;
            telephony.requestCellInfoUpdate(
                    getSubId(),
                    new ICellInfoCallback.Stub() {
                        public void onCellInfo(List<CellInfo> cellInfo) {
                            Binder.withCleanCallingIdentity(() ->
                                    executor.execute(() -> callback.onCellInfo(cellInfo)));
                        }

                        public void onError(int errorCode, android.os.ParcelableException detail) {
                            Binder.withCleanCallingIdentity(() ->
                                    executor.execute(() -> callback.onError(
                                            errorCode, detail.getCause())));
                        }
                    }, getOpPackageName());

        } catch (RemoteException ex) {
        }
    }

    /**
     * Requests all available cell information from the current subscription for observed
     * camped/registered, serving, and neighboring cells.
     *
     * <p>Any available results from this request will be provided by calls to
     * {@link android.telephony.PhoneStateListener#onCellInfoChanged onCellInfoChanged()}
     * for each active subscription.
     *
     * @param workSource the requestor to whom the power consumption for this should be attributed.
     * @param executor the executor on which callback will be invoked.
     * @param callback a callback to receive CellInfo.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.MODIFY_PHONE_STATE})
    public void requestCellInfoUpdate(@NonNull WorkSource workSource,
            @NonNull @CallbackExecutor Executor executor, @NonNull CellInfoCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) return;
            telephony.requestCellInfoUpdateWithWorkSource(
                    getSubId(),
                    new ICellInfoCallback.Stub() {
                        public void onCellInfo(List<CellInfo> cellInfo) {
                            Binder.withCleanCallingIdentity(() ->
                                    executor.execute(() -> callback.onCellInfo(cellInfo)));
                        }

                        public void onError(int errorCode, android.os.ParcelableException detail) {
                            Binder.withCleanCallingIdentity(() ->
                                    executor.execute(() -> callback.onError(
                                            errorCode, detail.getCause())));
                        }
                    }, getOpPackageName(), workSource);
        } catch (RemoteException ex) {
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @return an IccOpenLogicalChannelResponse object.
     * @deprecated Replaced by {@link #iccOpenLogicalChannel(String, int)}
     */
    @Deprecated
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String AID) {
        return iccOpenLogicalChannel(getSubId(), AID, -1);
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @param p2 P2 parameter (described in ISO 7816-4).
     * @return an IccOpenLogicalChannelResponse object.
     */
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String AID, int p2) {
        return iccOpenLogicalChannel(getSubId(), AID, p2);
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param AID Application id. See ETSI 102.221 and 101.220.
     * @param p2 P2 parameter (described in ISO 7816-4).
     * @return an IccOpenLogicalChannelResponse object.
     * @hide
     */
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(int subId, String AID, int p2) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccOpenLogicalChannel(subId, getOpPackageName(), AID, p2);
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param channel is the channel id to be closed as retruned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     */
    public boolean iccCloseLogicalChannel(int channel) {
        return iccCloseLogicalChannel(getSubId(), channel);
    }

    /**
     * Closes a previously opened logical channel to the ICC card.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHC command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param channel is the channel id to be closed as retruned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     * @hide
     */
    public boolean iccCloseLogicalChannel(int subId, int channel) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccCloseLogicalChannel(subId, channel);
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
        return iccTransmitApduLogicalChannel(getSubId(), channel, cla,
                    instruction, p1, p2, p3, data);
    }

    /**
     * Transmit an APDU to the ICC card over a logical channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
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
     * @hide
     */
    public String iccTransmitApduLogicalChannel(int subId, int channel, int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccTransmitApduLogicalChannel(subId, channel, cla,
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
        return iccTransmitApduBasicChannel(getSubId(), cla,
                    instruction, p1, p2, p3, data);
    }

    /**
     * Transmit an APDU to the ICC card over the basic channel.
     *
     * Input parameters equivalent to TS 27.007 AT+CSIM command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at
     *            the end.
     * @hide
     */
    public String iccTransmitApduBasicChannel(int subId, int cla,
            int instruction, int p1, int p2, int p3, String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccTransmitApduBasicChannel(subId, getOpPackageName(), cla,
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
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
        return iccExchangeSimIO(getSubId(), fileID, command, p1, p2, p3, filePath);
    }

    /**
     * Returns the response APDU for a command APDU sent through SIM_IO.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param fileID
     * @param command
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command.
     * @param filePath
     * @return The APDU response.
     * @hide
     */
    public byte[] iccExchangeSimIO(int subId, int fileID, int command, int p1, int p2,
            int p3, String filePath) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.iccExchangeSimIO(subId, fileID, command, p1, p2, p3, filePath);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Send ENVELOPE to the SIM and return the response.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param content String containing SAT/USAT response in hexadecimal
     *                format starting with command tag. See TS 102 223 for
     *                details.
     * @return The APDU response from the ICC card in hexadecimal format
     *         with the last 4 bytes being the status word. If the command fails,
     *         returns an empty string.
     */
    public String sendEnvelopeWithStatus(String content) {
        return sendEnvelopeWithStatus(getSubId(), content);
    }

    /**
     * Send ENVELOPE to the SIM and return the response.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param content String containing SAT/USAT response in hexadecimal
     *                format starting with command tag. See TS 102 223 for
     *                details.
     * @return The APDU response from the ICC card in hexadecimal format
     *         with the last 4 bytes being the status word. If the command fails,
     *         returns an empty string.
     * @hide
     */
    public String sendEnvelopeWithStatus(int subId, String content) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.sendEnvelopeWithStatus(subId, content);
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return "";
    }

    /**
     * Read one of the NV items defined in com.android.internal.telephony.RadioNVItems.
     * Used for device configuration by some CDMA operators.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param itemID the ID of the item to read.
     * @return the NV item as a String, or null on any failure.
     *
     * @hide
     */
    @UnsupportedAppUsage
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
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
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
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * TODO: remove this one. use {@link #rebootRadio()} for reset type 1 and
     * {@link #resetRadioConfig()} for reset type 3
     *
     * @param resetType reset type: 1: reload NV reset, 2: erase NV reset, 3: factory NV reset
     * @return true on success; false on any failure.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean nvResetConfig(int resetType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (resetType == 1 /*1: reload NV reset */) {
                    return telephony.rebootModem(getSlotIndex());
                } else if (resetType == 3 /*3: factory NV reset */) {
                    return telephony.resetModemConfig(getSlotIndex());
                } else {
                    Rlog.e(TAG, "nvResetConfig unsupported reset type");
                }
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "nvResetConfig RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "nvResetConfig NPE", ex);
        }
        return false;
    }

    /**
     * Rollback modem configurations to factory default except some config which are in whitelist.
     * Used for device configuration by some carriers.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return {@code true} on success; {@code false} on any failure.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public boolean resetRadioConfig() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.resetModemConfig(getSlotIndex());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "resetRadioConfig RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "resetRadioConfig NPE", ex);
        }
        return false;
    }

    /**
     * Generate a radio modem reset. Used for device configuration by some carriers.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return {@code true} on success; {@code false} on any failure.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public boolean rebootRadio() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.rebootModem(getSlotIndex());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "rebootRadio RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "rebootRadio NPE", ex);
        }
        return false;
    }

    /**
     * Return an appropriate subscription ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the provided
     * subId is returned. Otherwise, the default subId will be returned.
     */
    private int getSubId() {
      if (SubscriptionManager.isUsableSubIdValue(mSubId)) {
        return mSubId;
      }
      return SubscriptionManager.getDefaultSubscriptionId();
    }

    /**
     * Return an appropriate subscription ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the provided
     * subId is returned. Otherwise, the preferred subId which is based on caller's context is
     * returned.
     * {@see SubscriptionManager#getDefaultDataSubscriptionId()}
     * {@see SubscriptionManager#getDefaultVoiceSubscriptionId()}
     * {@see SubscriptionManager#getDefaultSmsSubscriptionId()}
     */
    @UnsupportedAppUsage
    private int getSubId(int preferredSubId) {
        if (SubscriptionManager.isUsableSubIdValue(mSubId)) {
            return mSubId;
        }
        return preferredSubId;
    }

    /**
     * Return an appropriate phone ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the phoneId
     * associated with the provided subId is returned. Otherwise, the default phoneId associated
     * with the default subId will be returned.
     */
    private int getPhoneId() {
        return SubscriptionManager.getPhoneId(getSubId());
    }

    /**
     * Return an appropriate phone ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the phoneId
     * associated with the provided subId is returned. Otherwise, return the phoneId associated
     * with the preferred subId based on caller's context.
     * {@see SubscriptionManager#getDefaultDataSubscriptionId()}
     * {@see SubscriptionManager#getDefaultVoiceSubscriptionId()}
     * {@see SubscriptionManager#getDefaultSmsSubscriptionId()}
     */
    @UnsupportedAppUsage
    private int getPhoneId(int preferredSubId) {
        return SubscriptionManager.getPhoneId(getSubId(preferredSubId));
    }

    /**
     * Return an appropriate slot index for any situation.
     *
     * if this object has been created with {@link #createForSubscriptionId}, then the slot index
     * associated with the provided subId is returned. Otherwise, return the slot index associated
     * with the default subId.
     * If SIM is not inserted, return default SIM slot index.
     *
     * {@hide}
     */
    @VisibleForTesting
    @UnsupportedAppUsage
    public int getSlotIndex() {
        int slotIndex = SubscriptionManager.getSlotIndex(getSubId());
        if (slotIndex == SubscriptionManager.SIM_NOT_INSERTED) {
            slotIndex = SubscriptionManager.DEFAULT_SIM_SLOT_INDEX;
        }
        return slotIndex;
    }

    /**
     * Request that the next incoming call from a number matching {@code range} be intercepted.
     *
     * This API is intended for OEMs to provide a service for apps to verify the device's phone
     * number. When called, the Telephony stack will store the provided {@link PhoneNumberRange} and
     * intercept the next incoming call from a number that lies within the range, within a timeout
     * specified by {@code timeoutMillis}.
     *
     * If such a phone call is received, the caller will be notified via
     * {@link NumberVerificationCallback#onCallReceived(String)} on the provided {@link Executor}.
     * If verification fails for any reason, the caller will be notified via
     * {@link NumberVerificationCallback#onVerificationFailed(int)}
     * on the provided {@link Executor}.
     *
     * In addition to the {@link Manifest.permission#MODIFY_PHONE_STATE} permission, callers of this
     * API must also be listed in the device configuration as an authorized app in
     * {@code packages/services/Telephony/res/values/config.xml} under the
     * {@code config_number_verification_package_name} key.
     *
     * @hide
     * @param range The range of phone numbers the caller expects a phone call from.
     * @param timeoutMillis The amount of time to wait for such a call, or
     *                      {@link #MAX_NUMBER_VERIFICATION_TIMEOUT_MILLIS}, whichever is lesser.
     * @param executor The {@link Executor} that callbacks should be executed on.
     * @param callback The callback to use for delivering results.
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void requestNumberVerification(@NonNull PhoneNumberRange range, long timeoutMillis,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull NumberVerificationCallback callback) {
        if (executor == null) {
            throw new NullPointerException("Executor must be non-null");
        }
        if (callback == null) {
            throw new NullPointerException("Callback must be non-null");
        }

        INumberVerificationCallback internalCallback = new INumberVerificationCallback.Stub() {
            @Override
            public void onCallReceived(String phoneNumber) {
                Binder.withCleanCallingIdentity(() ->
                        executor.execute(() ->
                                callback.onCallReceived(phoneNumber)));
            }

            @Override
            public void onVerificationFailed(int reason) {
                Binder.withCleanCallingIdentity(() ->
                        executor.execute(() ->
                                callback.onVerificationFailed(reason)));
            }
        };

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.requestNumberVerification(range, timeoutMillis, internalCallback,
                        getOpPackageName());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "requestNumberVerification RemoteException", ex);
            executor.execute(() ->
                    callback.onVerificationFailed(NumberVerificationCallback.REASON_UNSPECIFIED));
        }
    }

    /**
     * Sets a per-phone telephony property with the value specified.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static void setTelephonyProperty(int phoneId, String property, String value) {
        String propVal = "";
        String p[] = null;
        String prop = SystemProperties.get(property);

        if (value == null) {
            value = "";
        }
        value.replace(',', ' ');
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

        int propValLen = propVal.length();
        try {
            propValLen = propVal.getBytes("utf-8").length;
        } catch (java.io.UnsupportedEncodingException e) {
            Rlog.d(TAG, "setTelephonyProperty: utf-8 not supported");
        }
        if (propValLen > SystemProperties.PROP_VALUE_MAX) {
            Rlog.d(TAG, "setTelephonyProperty: property too long phoneId=" + phoneId +
                    " property=" + property + " value: " + value + " propVal=" + propVal);
            return;
        }

        SystemProperties.set(property, propVal);
    }

    /**
     * Sets a global telephony property with the value specified.
     *
     * @hide
     */
    public static void setTelephonyProperty(String property, String value) {
        if (value == null) {
            value = "";
        }
        Rlog.d(TAG, "setTelephonyProperty: success" + " property=" +
                property + " value: " + value);
        SystemProperties.set(property, value);
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public static boolean putIntAtIndex(android.content.ContentResolver cr,
            String name, int index, int value) {
        String data = "";
        String valArray[] = null;
        String v = android.provider.Settings.Global.getString(cr, name);

        if (index == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("putIntAtIndex index == MAX_VALUE index=" + index);
        }
        if (index < 0) {
            throw new IllegalArgumentException("putIntAtIndex index < 0 index=" + index);
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
     * Gets a per-phone telephony property.
     *
     * @hide
     */
    @UnsupportedAppUsage
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

    /**
     * Gets a global telephony property.
     *
     * See also getTelephonyProperty(phoneId, property, defaultVal). Most telephony properties are
     * per-phone.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static String getTelephonyProperty(String property, String defaultVal) {
        String propVal = SystemProperties.get(property);
        return TextUtils.isEmpty(propVal) ? defaultVal : propVal;
    }

    /** @hide */
    @UnsupportedAppUsage
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
     *
     * See 3GPP TS 31.103 (Section 4.2.7) for the definition and more information on this table.
     *
     * @return IMS Service Table or null if not present or not loaded
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getIsimIst() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Ist based on subId
            return info.getIsimIst(getSubId());
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
    @UnsupportedAppUsage
    public String[] getIsimPcscf() {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            //get the Isim Pcscf based on subId
            return info.getIsimPcscf(getSubId());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * UICC SIM Application Types
     * @hide
     */
    @IntDef(prefix = { "APPTYPE_" }, value = {
            APPTYPE_SIM,
            APPTYPE_USIM,
            APPTYPE_RUIM,
            APPTYPE_CSIM,
            APPTYPE_ISIM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UiccAppType{}
    /** UICC application type is SIM */
    public static final int APPTYPE_SIM = PhoneConstants.APPTYPE_SIM;
    /** UICC application type is USIM */
    public static final int APPTYPE_USIM = PhoneConstants.APPTYPE_USIM;
    /** UICC application type is RUIM */
    public static final int APPTYPE_RUIM = PhoneConstants.APPTYPE_RUIM;
    /** UICC application type is CSIM */
    public static final int APPTYPE_CSIM = PhoneConstants.APPTYPE_CSIM;
    /** UICC application type is ISIM */
    public static final int APPTYPE_ISIM = PhoneConstants.APPTYPE_ISIM;

    // authContext (parameter P2) when doing UICC challenge,
    // per 3GPP TS 31.102 (Section 7.1.2)
    /** Authentication type for UICC challenge is EAP SIM. See RFC 4186 for details. */
    public static final int AUTHTYPE_EAP_SIM = PhoneConstants.AUTH_CONTEXT_EAP_SIM;
    /** Authentication type for UICC challenge is EAP AKA. See RFC 4187 for details. */
    public static final int AUTHTYPE_EAP_AKA = PhoneConstants.AUTH_CONTEXT_EAP_AKA;

    /**
     * Returns the response of authentication for the default subscription.
     * Returns null if the authentication hasn't been successful
     *
     * <p>Requires Permission: READ_PRIVILEGED_PHONE_STATE or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     * @param authType the authentication type, {@link #AUTHTYPE_EAP_AKA} or
     * {@link #AUTHTYPE_EAP_SIM}
     * @param data authentication challenge data, base64 encoded.
     * See 3GPP TS 31.102 7.1.2 for more details.
     * @return the response of authentication. This value will be null in the following cases:
     *   Authentication error, incorrect MAC
     *   Authentication error, security context not supported
     *   Key freshness failure
     *   Authentication error, no memory space available
     *   Authentication error, no memory space available in EFMUK
     */
    // TODO(b/73660190): This should probably require MODIFY_PHONE_STATE, not
    // READ_PRIVILEGED_PHONE_STATE. It certainly shouldn't reference the permission in Javadoc since
    // it's not public API.
    public String getIccAuthentication(int appType, int authType, String data) {
        return getIccAuthentication(getSubId(), appType, authType, data);
    }

    /**
     * Returns the response of USIM Authentication for specified subId.
     * Returns null if the authentication hasn't been successful
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId subscription ID used for authentication
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     * @param authType the authentication type, {@link #AUTHTYPE_EAP_AKA} or
     * {@link #AUTHTYPE_EAP_SIM}
     * @param data authentication challenge data, base64 encoded.
     * See 3GPP TS 31.102 7.1.2 for more details.
     * @return the response of authentication. This value will be null in the following cases only
     * (see 3GPP TS 31.102 7.3.1):
     *   Authentication error, incorrect MAC
     *   Authentication error, security context not supported
     *   Key freshness failure
     *   Authentication error, no memory space available
     *   Authentication error, no memory space available in EFMUK
     * @hide
     */
    @UnsupportedAppUsage
    public String getIccAuthentication(int subId, int appType, int authType, String data) {
        try {
            IPhoneSubInfo info = getSubscriberInfo();
            if (info == null)
                return null;
            return info.getIccSimChallengeResponse(subId, appType, authType, data);
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            return null;
        }
    }

    /**
     * Returns an array of Forbidden PLMNs from the USIM App
     * Returns null if the query fails.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return an array of forbidden PLMNs or null if not available
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String[] getForbiddenPlmns() {
      return getForbiddenPlmns(getSubId(), APPTYPE_USIM);
    }

    /**
     * Returns an array of Forbidden PLMNs from the specified SIM App
     * Returns null if the query fails.
     *
     * @param subId subscription ID used for authentication
     * @param appType the icc application type, like {@link #APPTYPE_USIM}
     * @return fplmns an array of forbidden PLMNs
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public String[] getForbiddenPlmns(int subId, int appType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getForbiddenPlmns(subId, appType, mContext.getOpPackageName());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            return null;
        }
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
     * Enables IMS for the framework. This will trigger IMS registration and ImsFeature capability
     * status updates, if not already enabled.
     * @hide
     */
    public void enableIms(int slotId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.enableIms(slotId);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "enableIms, RemoteException: "
                    + e.getMessage());
        }
    }

    /**
     * Disables IMS for the framework. This will trigger IMS de-registration and trigger ImsFeature
     * status updates to disabled.
     * @hide
     */
    public void disableIms(int slotId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.disableIms(slotId);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "disableIms, RemoteException: "
                    + e.getMessage());
        }
    }

    /**
     * Returns the {@link IImsMmTelFeature} that corresponds to the given slot Id and MMTel
     * feature or {@link null} if the service is not available. If an MMTelFeature is available, the
     * {@link IImsServiceFeatureCallback} callback is registered as a listener for feature updates.
     * @param slotIndex The SIM slot that we are requesting the {@link IImsMmTelFeature} for.
     * @param callback Listener that will send updates to ImsManager when there are updates to
     * ImsServiceController.
     * @return {@link IImsMmTelFeature} interface for the feature specified or {@code null} if
     * it is unavailable.
     * @hide
     */
    public @Nullable IImsMmTelFeature getImsMmTelFeatureAndListen(int slotIndex,
            IImsServiceFeatureCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getMmTelFeatureAndListen(slotIndex, callback);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsMmTelFeatureAndListen, RemoteException: "
                    + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the {@link IImsRcsFeature} that corresponds to the given slot Id and RCS
     * feature for emergency calling or {@link null} if the service is not available. If an
     * RcsFeature is available, the {@link IImsServiceFeatureCallback} callback is registered as a
     * listener for feature updates.
     * @param slotIndex The SIM slot that we are requesting the {@link IImsRcsFeature} for.
     * @param callback Listener that will send updates to ImsManager when there are updates to
     * ImsServiceController.
     * @return {@link IImsRcsFeature} interface for the feature specified or {@code null} if
     * it is unavailable.
     * @hide
     */
    public @Nullable IImsRcsFeature getImsRcsFeatureAndListen(int slotIndex,
            IImsServiceFeatureCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getRcsFeatureAndListen(slotIndex, callback);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsRcsFeatureAndListen, RemoteException: "
                    + e.getMessage());
        }
        return null;
    }

    /**
     * @return the {@IImsRegistration} interface that corresponds with the slot index and feature.
     * @param slotIndex The SIM slot corresponding to the ImsService ImsRegistration is active for.
     * @param feature An integer indicating the feature that we wish to get the ImsRegistration for.
     * Corresponds to features defined in ImsFeature.
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable IImsRegistration getImsRegistration(int slotIndex, int feature) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getImsRegistration(slotIndex, feature);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsRegistration, RemoteException: " + e.getMessage());
        }
        return null;
    }

    /**
     * @return the {@IImsConfig} interface that corresponds with the slot index and feature.
     * @param slotIndex The SIM slot corresponding to the ImsService ImsConfig is active for.
     * @param feature An integer indicating the feature that we wish to get the ImsConfig for.
     * Corresponds to features defined in ImsFeature.
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable IImsConfig getImsConfig(int slotIndex, int feature) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getImsConfig(slotIndex, feature);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "getImsRegistration, RemoteException: " + e.getMessage());
        }
        return null;
    }

    /**
     * Set IMS registration state
     *
     * @param Registration state
     * @hide
     */
    @UnsupportedAppUsage
    public void setImsRegistrationState(boolean registered) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setImsRegistrationState(registered);
        } catch (RemoteException e) {
        }
    }

    /** @hide */
    @IntDef(prefix = { "NETWORK_MODE_" }, value = {
            NETWORK_MODE_WCDMA_PREF,
            NETWORK_MODE_GSM_ONLY,
            NETWORK_MODE_WCDMA_ONLY,
            NETWORK_MODE_GSM_UMTS,
            NETWORK_MODE_CDMA_EVDO,
            NETWORK_MODE_CDMA_NO_EVDO,
            NETWORK_MODE_EVDO_NO_CDMA,
            NETWORK_MODE_GLOBAL,
            NETWORK_MODE_LTE_CDMA_EVDO,
            NETWORK_MODE_LTE_GSM_WCDMA,
            NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA,
            NETWORK_MODE_LTE_ONLY,
            NETWORK_MODE_LTE_WCDMA,
            NETWORK_MODE_TDSCDMA_ONLY,
            NETWORK_MODE_TDSCDMA_WCDMA,
            NETWORK_MODE_LTE_TDSCDMA,
            NETWORK_MODE_TDSCDMA_GSM,
            NETWORK_MODE_LTE_TDSCDMA_GSM,
            NETWORK_MODE_TDSCDMA_GSM_WCDMA,
            NETWORK_MODE_LTE_TDSCDMA_WCDMA,
            NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA,
            NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA,
            NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA,
            NETWORK_MODE_NR_ONLY,
            NETWORK_MODE_NR_LTE,
            NETWORK_MODE_NR_LTE_CDMA_EVDO,
            NETWORK_MODE_NR_LTE_GSM_WCDMA,
            NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA,
            NETWORK_MODE_NR_LTE_WCDMA,
            NETWORK_MODE_NR_LTE_TDSCDMA,
            NETWORK_MODE_NR_LTE_TDSCDMA_GSM,
            NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA,
            NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA,
            NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PrefNetworkMode{}

    /**
     * Preferred network mode is GSM/WCDMA (WCDMA preferred).
     * @hide
     */
    public static final int NETWORK_MODE_WCDMA_PREF = RILConstants.NETWORK_MODE_WCDMA_PREF;

    /**
     * Preferred network mode is GSM only.
     * @hide
     */
    public static final int NETWORK_MODE_GSM_ONLY = RILConstants.NETWORK_MODE_GSM_ONLY;

    /**
     * Preferred network mode is WCDMA only.
     * @hide
     */
    public static final int NETWORK_MODE_WCDMA_ONLY = RILConstants.NETWORK_MODE_WCDMA_ONLY;

    /**
     * Preferred network mode is GSM/WCDMA (auto mode, according to PRL).
     * @hide
     */
    public static final int NETWORK_MODE_GSM_UMTS = RILConstants.NETWORK_MODE_GSM_UMTS;

    /**
     * Preferred network mode is CDMA and EvDo (auto mode, according to PRL).
     * @hide
     */
    public static final int NETWORK_MODE_CDMA_EVDO = RILConstants.NETWORK_MODE_CDMA;

    /**
     * Preferred network mode is CDMA only.
     * @hide
     */
    public static final int NETWORK_MODE_CDMA_NO_EVDO = RILConstants.NETWORK_MODE_CDMA_NO_EVDO;

    /**
     * Preferred network mode is EvDo only.
     * @hide
     */
    public static final int NETWORK_MODE_EVDO_NO_CDMA = RILConstants.NETWORK_MODE_EVDO_NO_CDMA;

    /**
     * Preferred network mode is GSM/WCDMA, CDMA, and EvDo (auto mode, according to PRL).
     * @hide
     */
    public static final int NETWORK_MODE_GLOBAL = RILConstants.NETWORK_MODE_GLOBAL;

    /**
     * Preferred network mode is LTE, CDMA and EvDo.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_CDMA_EVDO = RILConstants.NETWORK_MODE_LTE_CDMA_EVDO;

    /**
     * Preferred network mode is LTE, GSM/WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_GSM_WCDMA = RILConstants.NETWORK_MODE_LTE_GSM_WCDMA;

    /**
     * Preferred network mode is LTE, CDMA, EvDo, GSM/WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA =
            RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA;

    /**
     * Preferred network mode is LTE Only.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_ONLY = RILConstants.NETWORK_MODE_LTE_ONLY;

    /**
     * Preferred network mode is LTE/WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_WCDMA = RILConstants.NETWORK_MODE_LTE_WCDMA;

    /**
     * Preferred network mode is TD-SCDMA only.
     * @hide
     */
    public static final int NETWORK_MODE_TDSCDMA_ONLY = RILConstants.NETWORK_MODE_TDSCDMA_ONLY;

    /**
     * Preferred network mode is TD-SCDMA and WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_TDSCDMA_WCDMA = RILConstants.NETWORK_MODE_TDSCDMA_WCDMA;

    /**
     * Preferred network mode is TD-SCDMA and LTE.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_TDSCDMA = RILConstants.NETWORK_MODE_LTE_TDSCDMA;

    /**
     * Preferred network mode is TD-SCDMA and GSM.
     * @hide
     */
    public static final int NETWORK_MODE_TDSCDMA_GSM = RILConstants.NETWORK_MODE_TDSCDMA_GSM;

    /**
     * Preferred network mode is TD-SCDMA,GSM and LTE.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_TDSCDMA_GSM =
            RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM;

    /**
     * Preferred network mode is TD-SCDMA, GSM/WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_TDSCDMA_GSM_WCDMA =
            RILConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA;

    /**
     * Preferred network mode is TD-SCDMA, WCDMA and LTE.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_TDSCDMA_WCDMA =
            RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA;

    /**
     * Preferred network mode is TD-SCDMA, GSM/WCDMA and LTE.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA =
            RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA;

    /**
     * Preferred network mode is TD-SCDMA,EvDo,CDMA,GSM/WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA =
            RILConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
    /**
     * Preferred network mode is TD-SCDMA/LTE/GSM/WCDMA, CDMA, and EvDo.
     * @hide
     */
    public static final int NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA =
            RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;

    /**
     * Preferred network mode is NR 5G only.
     * @hide
     */
    public static final int NETWORK_MODE_NR_ONLY = RILConstants.NETWORK_MODE_NR_ONLY;

    /**
     * Preferred network mode is NR 5G, LTE.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE = RILConstants.NETWORK_MODE_NR_LTE;

    /**
     * Preferred network mode is NR 5G, LTE, CDMA and EvDo.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_CDMA_EVDO =
            RILConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO;

    /**
     * Preferred network mode is NR 5G, LTE, GSM and WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_GSM_WCDMA =
            RILConstants.NETWORK_MODE_NR_LTE_GSM_WCDMA;

    /**
     * Preferred network mode is NR 5G, LTE, CDMA, EvDo, GSM and WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA =
            RILConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA;

    /**
     * Preferred network mode is NR 5G, LTE and WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_WCDMA = RILConstants.NETWORK_MODE_NR_LTE_WCDMA;

    /**
     * Preferred network mode is NR 5G, LTE and TDSCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_TDSCDMA = RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA;

    /**
     * Preferred network mode is NR 5G, LTE, TD-SCDMA and GSM.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_TDSCDMA_GSM =
            RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM;

    /**
     * Preferred network mode is NR 5G, LTE, TD-SCDMA, WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA =
            RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA;

    /**
     * Preferred network mode is NR 5G, LTE, TD-SCDMA, GSM and WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA =
            RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA;

    /**
     * Preferred network mode is NR 5G, LTE, TD-SCDMA, CDMA, EVDO, GSM and WCDMA.
     * @hide
     */
    public static final int NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA =
            RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;

    /**
     * Get the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return the preferred network type, defined in RILConstants.java.
     * @hide
     */
    @RequiresPermission((android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE))
    @UnsupportedAppUsage
    public @PrefNetworkMode int getPreferredNetworkType(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getPreferredNetworkType(subId);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPreferredNetworkType NPE", ex);
        }
        return -1;
    }

    /**
     * Get the preferred network type bitmap.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return The bitmap of preferred network types.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @SystemApi
    public @NetworkTypeBitMask long getPreferredNetworkTypeBitmap() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return (long) RadioAccessFamily.getRafFromNetworkType(
                        telephony.getPreferredNetworkType(getSubId()));
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPreferredNetworkTypeBitmap RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPreferredNetworkTypeBitmap NPE", ex);
        }
        return 0;
    }

    /**
     * Sets the network selection mode to automatic.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setNetworkSelectionModeAutomatic() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setNetworkSelectionModeAutomatic(getSubId());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeAutomatic RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeAutomatic NPE", ex);
        }
    }

    /**
     * Perform a radio scan and return the list of available networks.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p> Note that this scan can take a long time (sometimes minutes) to happen.
     *
     * <p>Requires Permissions:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE} or that the calling app has carrier
     * privileges (see {@link #hasCarrierPrivileges})
     * and {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}.
     *
     * @return {@link CellNetworkScanResult} with the status
     * {@link CellNetworkScanResult#STATUS_SUCCESS} and a list of
     * {@link com.android.internal.telephony.OperatorInfo} if it's available. Otherwise, the failure
     * caused will be included in the result.
     *
     * @hide
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION
    })
    public CellNetworkScanResult getAvailableNetworks() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCellNetworkScanResults(getSubId(), getOpPackageName());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getAvailableNetworks RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getAvailableNetworks NPE", ex);
        }
        return new CellNetworkScanResult(
                CellNetworkScanResult.STATUS_UNKNOWN_ERROR, null /* OperatorInfo */);
    }

    /**
     * Request a network scan.
     *
     * This method is asynchronous, so the network scan results will be returned by callback.
     * The returned NetworkScan will contain a callback method which can be used to stop the scan.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges})
     * and {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
     *
     * @param request Contains all the RAT with bands/channels that need to be scanned.
     * @param executor The executor through which the callback should be invoked. Since the scan
     *        request may trigger multiple callbacks and they must be invoked in the same order as
     *        they are received by the platform, the user should provide an executor which executes
     *        tasks one at a time in serial order. For example AsyncTask.SERIAL_EXECUTOR.
     * @param callback Returns network scan results or errors.
     * @return A NetworkScan obj which contains a callback which can be used to stop the scan.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(allOf = {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
    })
    public NetworkScan requestNetworkScan(
            NetworkScanRequest request, Executor executor,
            TelephonyScanManager.NetworkScanCallback callback) {
        synchronized (this) {
            if (mTelephonyScanManager == null) {
                mTelephonyScanManager = new TelephonyScanManager();
            }
        }
        return mTelephonyScanManager.requestNetworkScan(getSubId(), request, executor, callback,
                getOpPackageName());
    }

    /**
     * @deprecated
     * Use {@link
     * #requestNetworkScan(NetworkScanRequest, Executor, TelephonyScanManager.NetworkScanCallback)}
     * @removed
     */
    @Deprecated
    @RequiresPermission(allOf = {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
    })
    public NetworkScan requestNetworkScan(
        NetworkScanRequest request, TelephonyScanManager.NetworkScanCallback callback) {
        return requestNetworkScan(request, AsyncTask.SERIAL_EXECUTOR, callback);
    }

    /**
     * Ask the radio to connect to the input network and change selection mode to manual.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param operatorNumeric the PLMN ID of the network to select.
     * @param persistSelection whether the selection will persist until reboot. If true, only allows
     * attaching to the selected PLMN until reboot; otherwise, attach to the chosen PLMN and resume
     * normal network selection next time.
     * @return {@code true} on success; {@code false} on any failure.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setNetworkSelectionModeManual(String operatorNumeric, boolean persistSelection) {
        return setNetworkSelectionModeManual(
                new OperatorInfo(
                        "" /* operatorAlphaLong */, "" /* operatorAlphaShort */, operatorNumeric),
                persistSelection);
    }

    /**
     * Ask the radio to connect to the input network and change selection mode to manual.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param operatorInfo included the PLMN id, long name, short name of the operator to attach to.
     * @param persistSelection whether the selection will persist until reboot. If true, only allows
     * attaching to the selected PLMN until reboot; otherwise, attach to the chosen PLMN and resume
     * normal network selection next time.
     * @return {@code true} on success; {@code true} on any failure.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setNetworkSelectionModeManual(
            OperatorInfo operatorInfo, boolean persistSelection) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setNetworkSelectionModeManual(
                        getSubId(), operatorInfo, persistSelection);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setNetworkSelectionModeManual RemoteException", ex);
        }
        return false;
    }

   /**
     * Get the network selection mode.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}

     * @return the network selection mode.
     *
     * @hide
     */
    @NetworkSelectionMode
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getNetworkSelectionMode() {
        int mode = NETWORK_SELECTION_MODE_UNKNOWN;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                mode = telephony.getNetworkSelectionMode(getSubId());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getNetworkSelectionMode RemoteException", ex);
        }
        return mode;
    }

    /**
     * Set the preferred network type.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId the id of the subscription to set the preferred network type for.
     * @param networkType the preferred network type, defined in RILConstants.java.
     * @return true on success; false on any failure.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean setPreferredNetworkType(int subId, int networkType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setPreferredNetworkType(subId, networkType);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredNetworkType RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setPreferredNetworkType NPE", ex);
        }
        return false;
    }

    /**
     * Set the preferred network type bitmap.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param networkTypeBitmap The bitmap of preferred network types.
     * @return true on success; false on any failure.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public boolean setPreferredNetworkTypeBitmap(@NetworkTypeBitMask long networkTypeBitmap) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setPreferredNetworkType(
                        getSubId(), RadioAccessFamily.getNetworkTypeFromRaf(
                                (int) networkTypeBitmap));
            }
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
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return true on success; false on any failure.
     */
    public boolean setPreferredNetworkTypeToGlobal() {
        return setPreferredNetworkTypeToGlobal(getSubId());
    }

    /**
     * Set the preferred network type to global mode which includes LTE, CDMA, EvDo and GSM/WCDMA.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return true on success; false on any failure.
     * @hide
     */
    public boolean setPreferredNetworkTypeToGlobal(int subId) {
        return setPreferredNetworkType(subId, RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA);
    }

    /**
     * Check TETHER_DUN_REQUIRED and TETHER_DUN_APN settings, net.tethering.noprovisioning
     * SystemProperty to decide whether DUN APN is required for
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
        return hasCarrierPrivileges(getSubId());
    }

    /**
     * Has the calling application been granted carrier privileges by the carrier.
     *
     * If any of the packages in the calling UID has carrier privileges, the
     * call will return true. This access is granted by the owner of the UICC
     * card and does not depend on the registered carrier.
     *
     * @param subId The subscription to use.
     * @return true if the app has carrier privileges.
     * @hide
     */
    public boolean hasCarrierPrivileges(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCarrierPrivilegeStatus(subId)
                        == CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
            }
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
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param brand The brand name to display/set.
     * @return true if the operation was executed correctly.
     */
    public boolean setOperatorBrandOverride(String brand) {
        return setOperatorBrandOverride(getSubId(), brand);
    }

    /**
     * Override the branding for the current ICCID.
     *
     * Once set, whenever the SIM is present in the device, the service
     * provider name (SPN) and the operator name will both be replaced by the
     * brand value input. To unset the value, the same function should be
     * called with a null brand value.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId The subscription to use.
     * @param brand The brand name to display/set.
     * @return true if the operation was executed correctly.
     * @hide
     */
    public boolean setOperatorBrandOverride(int subId, String brand) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setOperatorBrandOverride(subId, brand);
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
    @UnsupportedAppUsage
    public boolean setRoamingOverride(List<String> gsmRoamingList,
            List<String> gsmNonRoamingList, List<String> cdmaRoamingList,
            List<String> cdmaNonRoamingList) {
        return setRoamingOverride(getSubId(), gsmRoamingList, gsmNonRoamingList,
                cdmaRoamingList, cdmaNonRoamingList);
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
     * @param subId for which the roaming overrides apply.
     * @param gsmRoamingList - List of MCCMNCs to be considered roaming for 3GPP RATs.
     * @param gsmNonRoamingList - List of MCCMNCs to be considered not roaming for 3GPP RATs.
     * @param cdmaRoamingList - List of SIDs to be considered roaming for 3GPP2 RATs.
     * @param cdmaNonRoamingList - List of SIDs to be considered not roaming for 3GPP2 RATs.
     * @return true if the operation was executed correctly.
     *
     * @hide
     */
    public boolean setRoamingOverride(int subId, List<String> gsmRoamingList,
            List<String> gsmNonRoamingList, List<String> cdmaRoamingList,
            List<String> cdmaNonRoamingList) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.setRoamingOverride(subId, gsmRoamingList, gsmNonRoamingList,
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public String getCdmaMdn() {
        return getCdmaMdn(getSubId());
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public String getCdmaMin() {
        return getCdmaMin(getSubId());
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @SuppressLint("Doclava125")
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
    @SuppressLint("Doclava125")
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
        return getCarrierPackageNamesForIntentAndPhone(intent, getPhoneId());
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
    public List<String> getPackagesWithCarrierPrivileges() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getPackagesWithCarrierPrivileges();
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPackagesWithCarrierPrivileges RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPackagesWithCarrierPrivileges NPE", ex);
        }
        return Collections.EMPTY_LIST;
    }

    /** @hide */
    @SystemApi
    @SuppressLint("Doclava125")
    public void dial(String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.dial(number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#dial", e);
        }
    }

    /**
     * @deprecated Use  {@link android.telecom.TelecomManager#placeCall(Uri address,
     * Bundle extras)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    public void call(String callingPackage, String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.call(callingPackage, number);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#call", e);
        }
    }

    /**
     * @removed Use {@link android.telecom.TelecomManager#endCall()} instead.
     * @hide
     * @removed
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    public boolean endCall() {
        return false;
    }

    /**
     * @removed Use {@link android.telecom.TelecomManager#acceptRingingCall} instead
     * @hide
     * @removed
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void answerRingingCall() {
        // No-op
    }

    /**
     * @removed Use {@link android.telecom.TelecomManager#silenceRinger} instead
     * @hide
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public void silenceRinger() {
        // No-op
    }

    /**
     * @deprecated Use {@link android.telecom.TelecomManager#isInCall} instead
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isOffhook() {
        TelecomManager tm = (TelecomManager) mContext.getSystemService(TELECOM_SERVICE);
        return tm.isInCall();
    }

    /**
     * @deprecated Use {@link android.telecom.TelecomManager#isRinging} instead
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isRinging() {
        TelecomManager tm = (TelecomManager) mContext.getSystemService(TELECOM_SERVICE);
        return tm.isRinging();
    }

    /**
     * @deprecated Use {@link android.telecom.TelecomManager#isInCall} instead
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isIdle() {
        TelecomManager tm = (TelecomManager) mContext.getSystemService(TELECOM_SERVICE);
        return !tm.isInCall();
    }

    /**
     * @deprecated Use {@link android.telephony.TelephonyManager#getServiceState} instead
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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

    /**
     * Used to notify callers of
     * {@link TelephonyManager#sendUssdRequest(String, UssdResponseCallback, Handler)} when the
     * network either successfully executes a USSD request, or if there was a failure while
     * executing the request.
     * <p>
     * {@link #onReceiveUssdResponse(TelephonyManager, String, CharSequence)} will be called if the
     * USSD request has succeeded.
     * {@link #onReceiveUssdResponseFailed(TelephonyManager, String, int)} will be called if the
     * USSD request has failed.
     */
    public static abstract class UssdResponseCallback {
       /**
        * Called when a USSD request has succeeded.  The {@code response} contains the USSD
        * response received from the network.  The calling app can choose to either display the
        * response to the user or perform some operation based on the response.
        * <p>
        * USSD responses are unstructured text and their content is determined by the mobile network
        * operator.
        *
        * @param telephonyManager the TelephonyManager the callback is registered to.
        * @param request the USSD request sent to the mobile network.
        * @param response the response to the USSD request provided by the mobile network.
        **/
       public void onReceiveUssdResponse(final TelephonyManager telephonyManager,
                                         String request, CharSequence response) {};

       /**
        * Called when a USSD request has failed to complete.
        *
        * @param telephonyManager the TelephonyManager the callback is registered to.
        * @param request the USSD request sent to the mobile network.
        * @param failureCode failure code indicating why the request failed.  Will be either
        *        {@link TelephonyManager#USSD_RETURN_FAILURE} or
        *        {@link TelephonyManager#USSD_ERROR_SERVICE_UNAVAIL}.
        **/
       public void onReceiveUssdResponseFailed(final TelephonyManager telephonyManager,
                                               String request, int failureCode) {};
    }

    /**
     * Sends an Unstructured Supplementary Service Data (USSD) request to the mobile network and
     * informs the caller of the response via the supplied {@code callback}.
     * <p>Carriers define USSD codes which can be sent by the user to request information such as
     * the user's current data balance or minutes balance.
     * <p>Requires permission:
     * {@link android.Manifest.permission#CALL_PHONE}
     * @param ussdRequest the USSD command to be executed.
     * @param callback called by the framework to inform the caller of the result of executing the
     *                 USSD request (see {@link UssdResponseCallback}).
     * @param handler the {@link Handler} to run the request on.
     */
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    public void sendUssdRequest(String ussdRequest,
                                final UssdResponseCallback callback, Handler handler) {
        checkNotNull(callback, "UssdResponseCallback cannot be null.");
        final TelephonyManager telephonyManager = this;

        ResultReceiver wrappedCallback = new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle ussdResponse) {
                Rlog.d(TAG, "USSD:" + resultCode);
                checkNotNull(ussdResponse, "ussdResponse cannot be null.");
                UssdResponse response = ussdResponse.getParcelable(USSD_RESPONSE);

                if (resultCode == USSD_RETURN_SUCCESS) {
                    callback.onReceiveUssdResponse(telephonyManager, response.getUssdRequest(),
                            response.getReturnMessage());
                } else {
                    callback.onReceiveUssdResponseFailed(telephonyManager,
                            response.getUssdRequest(), resultCode);
                }
            }
        };

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.handleUssdRequest(getSubId(), ussdRequest, wrappedCallback);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#sendUSSDCode", e);
            UssdResponse response = new UssdResponse(ussdRequest, "");
            Bundle returnData = new Bundle();
            returnData.putParcelable(USSD_RESPONSE, response);
            wrappedCallback.send(USSD_ERROR_SERVICE_UNAVAIL, returnData);
        }
    }

    /**
     * Whether the device is currently on a technology (e.g. UMTS or LTE) which can support
     * voice and data simultaneously. This can change based on location or network condition.
     *
     * @return {@code true} if simultaneous voice and data supported, and {@code false} otherwise.
     */
    public boolean isConcurrentVoiceAndDataSupported() {
        try {
            ITelephony telephony = getITelephony();
            return (telephony == null ? false : telephony.isConcurrentVoiceAndDataAllowed(
                    getSubId()));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isConcurrentVoiceAndDataAllowed", e);
        }
        return false;
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"RADIO_POWER_"},
            value = {RADIO_POWER_OFF,
                    RADIO_POWER_ON,
                    RADIO_POWER_UNAVAILABLE,
            })
    public @interface RadioPowerState {}

    /**
     * Radio explicitly powered off (e.g, airplane mode).
     * @hide
     */
    @SystemApi
    public static final int RADIO_POWER_OFF = 0;

    /**
     * Radio power is on.
     * @hide
     */
    @SystemApi
    public static final int RADIO_POWER_ON = 1;

    /**
     * Radio power unavailable (eg, modem resetting or not booted).
     * @hide
     */
    @SystemApi
    public static final int RADIO_POWER_UNAVAILABLE = 2;

    /**
     * @return current modem radio state.
     *
     * <p>Requires permission: {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE} or
     * {@link android.Manifest.permission#READ_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE})
    public @RadioPowerState int getRadioPowerState() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getRadioPowerState(getSlotIndex(), mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return RADIO_POWER_UNAVAILABLE;
    }

    /** @hide */
    @SystemApi
    @SuppressLint("Doclava125")
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
                return telephony.isDataConnectivityPossible(getSubId(SubscriptionManager
                        .getDefaultDataSubscriptionId()));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isDataAllowed", e);
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

    /**
     * Turns mobile data on or off.
     * If this object has been created with {@link #createForSubscriptionId}, applies to the given
     * subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param enable Whether to enable mobile data.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataEnabled(boolean enable) {
        setDataEnabled(getSubId(SubscriptionManager.getDefaultDataSubscriptionId()), enable);
    }

    /**
     * @hide
     * @deprecated use {@link #setDataEnabled(boolean)} instead.
    */
    @SystemApi
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataEnabled(int subId, boolean enable) {
        try {
            Log.d(TAG, "setDataEnabled: enabled=" + enable);
            ITelephony telephony = getITelephony();
            if (telephony != null)
                telephony.setUserDataEnabled(subId, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setUserDataEnabled", e);
        }
    }

    /**
     * @deprecated use {@link #isDataEnabled()} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public boolean getDataEnabled() {
        return isDataEnabled();
    }

    /**
     * Returns whether mobile data is enabled or not per user setting. There are other factors
     * that could disable mobile data, but they are not considered here.
     *
     * If this object has been created with {@link #createForSubscriptionId}, applies to the given
     * subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires one of the following permissions:
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE},
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE}, or that the calling app has carrier
     * privileges (see {@link #hasCarrierPrivileges}).
     *
     * <p>Note that this does not take into account any data restrictions that may be present on the
     * calling app. Such restrictions may be inspected with
     * {@link ConnectivityManager#getRestrictBackgroundStatus}.
     *
     * @return true if mobile data is enabled.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.MODIFY_PHONE_STATE})
    public boolean isDataEnabled() {
        return getDataEnabled(getSubId(SubscriptionManager.getDefaultDataSubscriptionId()));
    }

    /**
     * Returns whether mobile data roaming is enabled on the subscription.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires one of the following permissions:
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE},
     * {@link android.Manifest.permission#READ_PHONE_STATE} or that the calling app
     * has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return {@code true} if the data roaming is enabled on the subscription, otherwise return
     * {@code false}.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.READ_PHONE_STATE})
    public boolean isDataRoamingEnabled() {
        boolean isDataRoamingEnabled = false;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                isDataRoamingEnabled = telephony.isDataRoamingEnabled(
                        getSubId(SubscriptionManager.getDefaultDataSubscriptionId()));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isDataRoamingEnabled", e);
        }
        return isDataRoamingEnabled;
    }

    /**
     * Gets the roaming mode for CDMA phone.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * @return one of {@link #CDMA_ROAMING_MODE_RADIO_DEFAULT}, {@link #CDMA_ROAMING_MODE_HOME},
     * {@link #CDMA_ROAMING_MODE_AFFILIATED}, {@link #CDMA_ROAMING_MODE_ANY}.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getCdmaRoamingMode() {
        int mode = CDMA_ROAMING_MODE_RADIO_DEFAULT;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                mode = telephony.getCdmaRoamingMode(getSubId());
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Error calling ITelephony#getCdmaRoamingMode", ex);
        }
        return mode;
    }

    /**
     * Sets the roaming mode for CDMA phone to the given mode {@code mode}.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * @param mode should be one of {@link #CDMA_ROAMING_MODE_RADIO_DEFAULT},
     * {@link #CDMA_ROAMING_MODE_HOME}, {@link #CDMA_ROAMING_MODE_AFFILIATED},
     * {@link #CDMA_ROAMING_MODE_ANY}.
     *
     * @return {@code true} if successed.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setCdmaRoamingMode(int mode) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setCdmaRoamingMode(getSubId(), mode);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Error calling ITelephony#setCdmaRoamingMode", ex);
        }
        return false;
    }

    /**
     * Sets the subscription mode for CDMA phone to the given mode {@code mode}.
     *
     * @param mode CDMA subscription mode
     *
     * @return {@code true} if successed.
     *
     * @see Phone#CDMA_SUBSCRIPTION_UNKNOWN
     * @see Phone#CDMA_SUBSCRIPTION_RUIM_SIM
     * @see Phone#CDMA_SUBSCRIPTION_NV
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setCdmaSubscriptionMode(int mode) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setCdmaSubscriptionMode(getSubId(), mode);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Error calling ITelephony#setCdmaSubscriptionMode", ex);
        }
        return false;
    }

    /**
     * Enables/Disables the data roaming on the subscription.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p> Requires permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE} or that the calling app has carrier
     * privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param isEnabled {@code true} to enable mobile data roaming, otherwise disable it.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDataRoamingEnabled(boolean isEnabled) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setDataRoamingEnabled(
                        getSubId(SubscriptionManager.getDefaultDataSubscriptionId()), isEnabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setDataRoamingEnabled", e);
        }
    }

    /**
     * @deprecated use {@link #isDataEnabled()} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public boolean getDataEnabled(int subId) {
        boolean retVal = false;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                retVal = telephony.isUserDataEnabled(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isUserDataEnabled", e);
        } catch (NullPointerException e) {
        }
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
     * @deprecated OEM needs a vendor-extension hal and their apps should use that instead
     */
    @Deprecated
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
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
                return telephony.canChangeDtmfToneLength(mSubId, getOpPackageName());
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
                return telephony.isWorldPhone(mSubId, getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isWorldPhone", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isWorldPhone", e);
        }
        return false;
    }

    /**
     * @deprecated Use {@link TelecomManager#isTtySupported()} instead
     * Whether the phone supports TTY mode.
     *
     * @return {@code true} if the device supports TTY mode, and {@code false} otherwise.
     *
     */
    @Deprecated
    public boolean isTtyModeSupported() {
        try {
            TelecomManager telecomManager = TelecomManager.from(mContext);
            if (telecomManager != null) {
                return telecomManager.isTtySupported();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling TelecomManager#isTtySupported", e);
        }
        return false;
    }

    /**
     * Determines whether the device currently supports RTT (Real-time text). Based both on carrier
     * support for the feature and device firmware support.
     *
     * @return {@code true} if the device and carrier both support RTT, {@code false} otherwise.
     */
    public boolean isRttSupported() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isRttSupported(mSubId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isRttSupported", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#isWorldPhone", e);
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
     * Returns the IMS Registration Status for a particular Subscription ID.
     *
     * @param subId Subscription ID
     * @return true if IMS status is registered, false if the IMS status is not registered or a
     * RemoteException occurred.
     * @hide
     */
    public boolean isImsRegistered(int subId) {
        try {
            return getITelephony().isImsRegistered(subId);
        } catch (RemoteException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * Returns the IMS Registration Status for a particular Subscription ID, which is determined
     * when the TelephonyManager is created using {@link #createForSubscriptionId(int)}. If an
     * invalid subscription ID is used during creation, will the default subscription ID will be
     * used.
     *
     * @return true if IMS status is registered, false if the IMS status is not registered or a
     * RemoteException occurred.
     * @see SubscriptionManager#getDefaultSubscriptionId()
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean isImsRegistered() {
       try {
           return getITelephony().isImsRegistered(getSubId());
       } catch (RemoteException | NullPointerException ex) {
           return false;
       }
    }

    /**
     * The current status of Voice over LTE for the subscription associated with this instance when
     * it was created using {@link #createForSubscriptionId(int)}. If an invalid subscription ID was
     * used during creation, the default subscription ID will be used.
     * @return true if Voice over LTE is available or false if it is unavailable or unknown.
     * @see SubscriptionManager#getDefaultSubscriptionId()
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean isVolteAvailable() {
        try {
            return getITelephony().isAvailable(getSubId(),
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_LTE);
        } catch (RemoteException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * The availability of Video Telephony (VT) for the subscription ID specified when this instance
     * was created using {@link #createForSubscriptionId(int)}. If an invalid subscription ID was
     * used during creation, the default subscription ID will be used. To query the
     * underlying technology that VT is available on, use {@link #getImsRegTechnologyForMmTel}.
     * @return true if VT is available, or false if it is unavailable or unknown.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean isVideoTelephonyAvailable() {
        try {
            return getITelephony().isVideoTelephonyAvailable(getSubId());
        } catch (RemoteException | NullPointerException ex) {
            return false;
        }
    }

    /**
     * Returns the Status of Wi-Fi calling (Voice over WiFi) for the subscription ID specified.
     * @param subId the subscription ID.
     * @return true if VoWiFi is available, or false if it is unavailable or unknown.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean isWifiCallingAvailable() {
       try {
           return getITelephony().isWifiCallingAvailable(getSubId());
       } catch (RemoteException | NullPointerException ex) {
           return false;
       }
   }

    /**
     * The technology that IMS is registered for for the MMTEL feature.
     * @param subId subscription ID to get IMS registration technology for.
     * @return The IMS registration technology that IMS is registered to for the MMTEL feature.
     * Valid return results are:
     *  - {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE} for LTE registration,
     *  - {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN} for IWLAN registration, or
     *  - {@link ImsRegistrationImplBase#REGISTRATION_TECH_NONE} if we are not registered or the
     *  result is unavailable.
     *  @hide
     */
    public @ImsRegistrationImplBase.ImsRegistrationTech int getImsRegTechnologyForMmTel() {
        try {
            return getITelephony().getImsRegTechnologyForMmTel(getSubId());
        } catch (RemoteException ex) {
            return ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
        }
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the default phone.
    *
    * @hide
    */
    public void setSimOperatorNumeric(String numeric) {
        int phoneId = getPhoneId();
        setSimOperatorNumericForPhone(phoneId, numeric);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the given phone.
    *
    * @hide
    */
    @UnsupportedAppUsage
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
        int phoneId = getPhoneId();
        setSimOperatorNameForPhone(phoneId, name);
    }

    /**
     * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC for the given phone.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
        int phoneId = getPhoneId();
        setSimCountryIsoForPhone(phoneId, iso);
    }

   /**
    * Set TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY for the given phone.
    *
    * @hide
    */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
        int phoneId = getPhoneId();
        setSimStateForPhone(phoneId, state);
    }

    /**
     * Set TelephonyProperties.PROPERTY_SIM_STATE for the given phone.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setSimStateForPhone(int phoneId, String state) {
        setTelephonyProperty(phoneId,
                TelephonyProperties.PROPERTY_SIM_STATE, state);
    }

    /**
     * Requested state of SIM
     *
     * CARD_POWER_DOWN
     * Powers down the SIM. SIM must be up prior.
     *
     * CARD_POWER_UP
     * Powers up the SIM normally. SIM must be down prior.
     *
     * CARD_POWER_UP_PASS_THROUGH
     * Powers up the SIM in PASS_THROUGH mode. SIM must be down prior.
     * When SIM is powered up in PASS_THOUGH mode, the modem does not send
     * any command to it (for example SELECT of MF, or TERMINAL CAPABILITY),
     * and the SIM card is controlled completely by Telephony sending APDUs
     * directly. The SIM card state will be RIL_CARDSTATE_PRESENT and the
     * number of card apps will be 0.
     * No new error code is generated. Emergency calls are supported in the
     * same way as if the SIM card is absent.
     * The PASS_THROUGH mode is valid only for the specific card session where it
     * is activated, and normal behavior occurs at the next SIM initialization,
     * unless PASS_THROUGH mode is requested again. Hence, the last power-up mode
     * is NOT persistent across boots. On reboot, SIM will power up normally.
     */
    /** @hide */
    public static final int CARD_POWER_DOWN = 0;
    /** @hide */
    public static final int CARD_POWER_UP = 1;
    /** @hide */
    public static final int CARD_POWER_UP_PASS_THROUGH = 2;

    /**
     * Set SIM card power state.
     *
     * @param state  State of SIM (power down, power up, pass through)
     * @see #CARD_POWER_DOWN
     * @see #CARD_POWER_UP
     * @see #CARD_POWER_UP_PASS_THROUGH
     * Callers should monitor for {@link TelephonyIntents#ACTION_SIM_STATE_CHANGED}
     * broadcasts to determine success or failure and timeout if needed.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * {@hide}
     **/
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setSimPowerState(int state) {
        setSimPowerStateForSlot(getSlotIndex(), state);
    }

    /**
     * Set SIM card power state.
     *
     * @param slotIndex SIM slot id
     * @param state  State of SIM (power down, power up, pass through)
     * @see #CARD_POWER_DOWN
     * @see #CARD_POWER_UP
     * @see #CARD_POWER_UP_PASS_THROUGH
     * Callers should monitor for {@link TelephonyIntents#ACTION_SIM_STATE_CHANGED}
     * broadcasts to determine success or failure and timeout if needed.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * {@hide}
     **/
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setSimPowerStateForSlot(int slotIndex, int state) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setSimPowerStateForSlot(slotIndex, state);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setSimPowerStateForSlot", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error calling ITelephony#setSimPowerStateForSlot", e);
        }
    }

    /**
     * Set baseband version for the default phone.
     *
     * @param version baseband version
     * @hide
     */
    public void setBasebandVersion(String version) {
        int phoneId = getPhoneId();
        setBasebandVersionForPhone(phoneId, version);
    }

    /**
     * Set baseband version by phone id.
     *
     * @param phoneId for which baseband version is set
     * @param version baseband version
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setBasebandVersionForPhone(int phoneId, String version) {
        setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_BASEBAND_VERSION, version);
    }

    /**
     * Get baseband version for the default phone.
     *
     * @return baseband version.
     * @hide
     */
    public String getBasebandVersion() {
        int phoneId = getPhoneId();
        return getBasebandVersionForPhone(phoneId);
    }

    /**
     * Get baseband version for the default phone using the legacy approach.
     * This change was added in P, to ensure backward compatiblity.
     *
     * @return baseband version.
     * @hide
     */
    private String getBasebandVersionLegacy(int phoneId) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            String prop = TelephonyProperties.PROPERTY_BASEBAND_VERSION +
                    ((phoneId == 0) ? "" : Integer.toString(phoneId));
            return SystemProperties.get(prop);
        }
        return null;
    }

    /**
     * Get baseband version by phone id.
     *
     * @return baseband version.
     * @hide
     */
    public String getBasebandVersionForPhone(int phoneId) {
        String version = getBasebandVersionLegacy(phoneId);
        if (version != null && !version.isEmpty()) {
            setBasebandVersionForPhone(phoneId, version);
        }
        return getTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_BASEBAND_VERSION, "");
    }

    /**
     * Set phone type for the default phone.
     *
     * @param type phone type
     *
     * @hide
     */
    public void setPhoneType(int type) {
        int phoneId = getPhoneId();
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
        int phoneId = getPhoneId();
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
        int phoneId = getPhoneId();
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
            return Boolean.parseBoolean(TelephonyManager.getTelephonyProperty(phoneId,
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
        int phoneId = getPhoneId();
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
            return Boolean.parseBoolean(TelephonyManager.getTelephonyProperty(phoneId,
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
        int phoneId = getPhoneId();
        setNetworkOperatorNameForPhone(phoneId, name);
    }

    /**
     * Set the alphabetic name of current registered operator.
     * @param phoneId which phone you want to set
     * @param name the alphabetic name of current registered operator.
     * @hide
     */
    @UnsupportedAppUsage
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
        int phoneId = getPhoneId();
        setNetworkOperatorNumericForPhone(phoneId, numeric);
    }

    /**
     * Set the numeric name (MCC+MNC) of current registered operator.
     * @param phoneId for which phone type is set
     * @param operator the numeric name (MCC+MNC) of current registered operator
     * @hide
     */
    @UnsupportedAppUsage
    public void setNetworkOperatorNumericForPhone(int phoneId, String numeric) {
        setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, numeric);
    }

    /**
     * Set roaming state of the current network, for GSM purposes.
     * @param isRoaming is network in romaing state or not
     * @hide
     */
    public void setNetworkRoaming(boolean isRoaming) {
        int phoneId = getPhoneId();
        setNetworkRoamingForPhone(phoneId, isRoaming);
    }

    /**
     * Set roaming state of the current network, for GSM purposes.
     * @param phoneId which phone you want to set
     * @param isRoaming is network in romaing state or not
     * @hide
     */
    @UnsupportedAppUsage
    public void setNetworkRoamingForPhone(int phoneId, boolean isRoaming) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            setTelephonyProperty(phoneId, TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                    isRoaming ? "true" : "false");
        }
    }

    /**
     * Set the network type currently in use on the device for data transmission.
     *
     * If this object has been created with {@link #createForSubscriptionId}, applies to the
     * phoneId associated with the given subId. Otherwise, applies to the phoneId associated with
     * {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     * @param type the network type currently in use on the device for data transmission
     * @hide
     */
    public void setDataNetworkType(int type) {
        int phoneId = getPhoneId(SubscriptionManager.getDefaultDataSubscriptionId());
        setDataNetworkTypeForPhone(phoneId, type);
    }

    /**
     * Set the network type currently in use on the device for data transmission.
     * @param phoneId which phone you want to set
     * @param type the network type currently in use on the device for data transmission
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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

    private int getSubIdForPhoneAccountHandle(PhoneAccountHandle phoneAccountHandle) {
        int retval = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            ITelecomService service = getTelecomService();
            if (service != null) {
                retval = getSubIdForPhoneAccount(service.getPhoneAccount(phoneAccountHandle));
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


    /**
     * Returns a locale based on the country and language from the SIM. Returns {@code null} if
     * no locale could be derived from subscriptions.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     *
     * @see Locale#toLanguageTag()
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @Nullable public Locale getSimLocale() {
        try {
            final ITelephony telephony = getITelephony();
            if (telephony != null) {
                String languageTag = telephony.getSimLocaleForSubscriber(getSubId());
                if (!TextUtils.isEmpty(languageTag)) {
                    return Locale.forLanguageTag(languageTag);
                }
            }
        } catch (RemoteException ex) {
        }
        return null;
    }

    /**
     * TODO delete after SuW migrates to new API.
     * @hide
     */
    public String getLocaleFromDefaultSim() {
        try {
            final ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getSimLocaleForSubscriber(getSubId());
            }
        } catch (RemoteException ex) {
        }
        return null;
    }


    /**
     * Requests the modem activity info. The recipient will place the result
     * in `result`.
     * @param result The object on which the recipient will send the resulting
     * {@link android.telephony.ModemActivityInfo} object.
     * @hide
     */
    public void requestModemActivityInfo(ResultReceiver result) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.requestModemActivityInfo(result);
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getModemActivityInfo", e);
        }
        result.send(0, null);
    }

    /**
     * Returns the current {@link ServiceState} information.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges})
     * and {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(allOf = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION
    })
    public ServiceState getServiceState() {
        return getServiceStateForSubscriber(getSubId());
    }

    /**
     * Returns the service state information on specified subscription. Callers require
     * either READ_PRIVILEGED_PHONE_STATE or READ_PHONE_STATE to retrieve the information.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public ServiceState getServiceStateForSubscriber(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getServiceStateForSubscriber(subId, getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getServiceStateForSubscriber", e);
        }
        return null;
    }

    /**
     * Returns the URI for the per-account voicemail ringtone set in Phone settings.
     *
     * @param accountHandle The handle for the {@link PhoneAccount} for which to retrieve the
     * voicemail ringtone.
     * @return The URI for the ringtone to play when receiving a voicemail from a specific
     * PhoneAccount.
     */
    public Uri getVoicemailRingtoneUri(PhoneAccountHandle accountHandle) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getVoicemailRingtoneUri(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getVoicemailRingtoneUri", e);
        }
        return null;
    }

    /**
     * Sets the per-account voicemail ringtone.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges (see
     * {@link #hasCarrierPrivileges}, or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle The handle for the {@link PhoneAccount} for which to set the
     * voicemail ringtone.
     * @param uri The URI for the ringtone to play when receiving a voicemail from a specific
     * PhoneAccount.
     *
     * @deprecated Use {@link android.provider.Settings#ACTION_CHANNEL_NOTIFICATION_SETTINGS}
     * instead.
     */
    public void setVoicemailRingtoneUri(PhoneAccountHandle phoneAccountHandle, Uri uri) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setVoicemailRingtoneUri(getOpPackageName(), phoneAccountHandle, uri);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setVoicemailRingtoneUri", e);
        }
    }

    /**
     * Returns whether vibration is set for voicemail notification in Phone settings.
     *
     * @param accountHandle The handle for the {@link PhoneAccount} for which to retrieve the
     * voicemail vibration setting.
     * @return {@code true} if the vibration is set for this PhoneAccount, {@code false} otherwise.
     */
    public boolean isVoicemailVibrationEnabled(PhoneAccountHandle accountHandle) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isVoicemailVibrationEnabled(accountHandle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isVoicemailVibrationEnabled", e);
        }
        return false;
    }

    /**
     * Sets the per-account preference whether vibration is enabled for voicemail notifications.
     *
     * <p>Requires that the calling app is the default dialer, or has carrier privileges (see
     * {@link #hasCarrierPrivileges}, or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param phoneAccountHandle The handle for the {@link PhoneAccount} for which to set the
     * voicemail vibration setting.
     * @param enabled Whether to enable or disable vibration for voicemail notifications from a
     * specific PhoneAccount.
     *
     * @deprecated Use {@link android.provider.Settings#ACTION_CHANNEL_NOTIFICATION_SETTINGS}
     * instead.
     */
    public void setVoicemailVibrationEnabled(PhoneAccountHandle phoneAccountHandle,
            boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setVoicemailVibrationEnabled(getOpPackageName(), phoneAccountHandle,
                        enabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isVoicemailVibrationEnabled", e);
        }
    }

    /**
     * Returns carrier id of the current subscription.
     * <p>To recognize a carrier (including MVNO) as a first-class identity, Android assigns each
     * carrier with a canonical integer a.k.a. carrier id. The carrier ID is an Android
     * platform-wide identifier for a carrier. AOSP maintains carrier ID assignments in
     * <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/carrier_list.textpb">here</a>
     *
     * <p>Apps which have carrier-specific configurations or business logic can use the carrier id
     * as an Android platform-wide identifier for carriers.
     *
     * @return Carrier id of the current subscription. Return {@link #UNKNOWN_CARRIER_ID} if the
     * subscription is unavailable or the carrier cannot be identified.
     */
    public int getSimCarrierId() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionCarrierId(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID;
    }

    /**
     * Returns carrier id name of the current subscription.
     * <p>Carrier id name is a user-facing name of carrier id returned by
     * {@link #getSimCarrierId()}, usually the brand name of the subsidiary
     * (e.g. T-Mobile). Each carrier could configure multiple {@link #getSimOperatorName() SPN} but
     * should have a single carrier name. Carrier name is not a canonical identity,
     * use {@link #getSimCarrierId()} instead.
     * <p>The returned carrier name is unlocalized.
     *
     * @return Carrier name of the current subscription. Return {@code null} if the subscription is
     * unavailable or the carrier cannot be identified.
     */
    public @Nullable CharSequence getSimCarrierIdName() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionCarrierName(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return null;
    }

    /**
     * Returns fine-grained carrier id of the current subscription.
     *
     * <p>The precise carrier id can be used to further differentiate a carrier by different
     * networks, by prepaid v.s.postpaid or even by 4G v.s.3G plan. Each carrier has a unique
     * carrier id returned by {@link #getSimCarrierId()} but could have multiple precise carrier id.
     * e.g, {@link #getSimCarrierId()} will always return Tracfone (id 2022) for a Tracfone SIM,
     * while {@link #getSimPreciseCarrierId()} can return Tracfone AT&T or Tracfone T-Mobile based
     * on the current subscription IMSI.
     *
     * <p>For carriers without any fine-grained carrier ids, return {@link #getSimCarrierId()}
     * <p>Precise carrier ids are defined in the same way as carrier id
     * <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/carrier_list.textpb">here</a>
     * except each with a "parent" id linking to its top-level carrier id.
     *
     * @return Returns fine-grained carrier id of the current subscription.
     * Return {@link #UNKNOWN_CARRIER_ID} if the subscription is unavailable or the carrier cannot
     * be identified.
     */
    public int getSimPreciseCarrierId() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionPreciseCarrierId(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID;
    }

    /**
     * Similar like {@link #getSimCarrierIdName()}, returns user-facing name of the
     * precise carrier id returned by {@link #getSimPreciseCarrierId()}.
     *
     * <p>The returned name is unlocalized.
     *
     * @return user-facing name of the subscription precise carrier id. Return {@code null} if the
     * subscription is unavailable or the carrier cannot be identified.
     */
    public @Nullable CharSequence getSimPreciseCarrierIdName() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionPreciseCarrierName(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return null;
    }

    /**
     * Returns carrier id based on sim MCCMNC (returned by {@link #getSimOperator()}) only.
     * This is used for fallback when configurations/logic for exact carrier id
     * {@link #getSimCarrierId()} are not found.
     *
     * Android carrier id table <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/carrier_list.textpb">here</a>
     * can be updated out-of-band, its possible a MVNO (Mobile Virtual Network Operator) carrier
     * was not fully recognized and assigned to its MNO (Mobile Network Operator) carrier id
     * by default. After carrier id table update, a new carrier id was assigned. If apps don't
     * take the update with the new id, it might be helpful to always fallback by using carrier
     * id based on MCCMNC if there is no match.
     *
     * @return matching carrier id from sim MCCMNC. Return {@link #UNKNOWN_CARRIER_ID} if the
     * subscription is unavailable or the carrier cannot be identified.
     */
    public int getCarrierIdFromSimMccMnc() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getCarrierIdFromMccMnc(getSlotIndex(), getSimOperator(), true);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID;
    }

     /**
      * Returns carrier id based on MCCMNC (returned by {@link #getSimOperator()}) only. This is
      * used for fallback when configurations/logic for exact carrier id {@link #getSimCarrierId()}
      * are not found.
      *
      * Android carrier id table <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/carrier_list.textpb">here</a>
      * can be updated out-of-band, its possible a MVNO (Mobile Virtual Network Operator) carrier
      * was not fully recognized and assigned to its MNO (Mobile Network Operator) carrier id
      * by default. After carrier id table update, a new carrier id was assigned. If apps don't
      * take the update with the new id, it might be helpful to always fallback by using carrier
      * id based on MCCMNC if there is no match.
      *
      * @return matching carrier id from passing MCCMNC. Return {@link #UNKNOWN_CARRIER_ID} if the
      * subscription is unavailable or the carrier cannot be identified.
      * @hide
      */
     @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
     public int getCarrierIdFromMccMnc(String mccmnc) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getCarrierIdFromMccMnc(getSlotIndex(), mccmnc, false);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID;
    }

    /**
     * Return a list of certs in hex string from loaded carrier privileges access rules.
     *
     * @return a list of certificate in hex string. return {@code null} if there is no certs
     * or privilege rules are not loaded yet.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public List<String> getCertsFromCarrierPrivilegeAccessRules() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getCertsFromCarrierPrivilegeAccessRules(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return null;
    }

    /**
     * Return the application ID for the uicc application type like {@link #APPTYPE_CSIM}.
     * All uicc applications are uniquely identified by application ID, represented by the hex
     * string. e.g, A00000015141434C00. See ETSI 102.221 and 101.220
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
     * @param appType the uicc app type.
     * @return Application ID for specified app type or {@code null} if no uicc or error.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getAidForAppType(@UiccAppType int appType) {
        return getAidForAppType(getSubId(), appType);
    }

    /**
     * same as {@link #getAidForAppType(int)}
     * @hide
     */
    public String getAidForAppType(int subId, int appType) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getAidForAppType(subId, appType);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getAidForAppType", e);
        }
        return null;
    }

    /**
     * Return the Electronic Serial Number.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @return ESN or null if error.
     * @hide
     */
    public String getEsn() {
        return getEsn(getSubId());
    }

    /**
     * Return the Electronic Serial Number.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param subId the subscription ID that this request applies to.
     * @return ESN or null if error.
     * @hide
     */
    public String getEsn(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getEsn(subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getEsn", e);
        }
        return null;
    }

    /**
     * Return the Preferred Roaming List Version
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @return PRLVersion or null if error.
     * @hide
     */
    @SystemApi
    public String getCdmaPrlVersion() {
        return getCdmaPrlVersion(getSubId());
    }

    /**
     * Return the Preferred Roaming List Version
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param subId the subscription ID that this request applies to.
     * @return PRLVersion or null if error.
     * @hide
     */
    public String getCdmaPrlVersion(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getCdmaPrlVersion(subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getCdmaPrlVersion", e);
        }
        return null;
    }

    /**
     * Get snapshot of Telephony histograms
     * @return List of Telephony histograms
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     * Or the calling app has carrier privileges.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public List<TelephonyHistogram> getTelephonyHistograms() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getTelephonyHistograms();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getTelephonyHistograms", e);
        }
        return null;
    }

    /**
     * Set the allowed carrier list for slotIndex
     * Require system privileges. In the future we may add this to carrier APIs.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     *
     * <p>This method works only on devices with {@link
     * android.content.pm.PackageManager#FEATURE_TELEPHONY_CARRIERLOCK} enabled.
     *
     * @deprecated use setCarrierRestrictionRules instead
     *
     * @return The number of carriers set successfully. Should be length of
     * carrierList on success; -1 if carrierList null or on error.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public int setAllowedCarriers(int slotIndex, List<CarrierIdentifier> carriers) {
        if (carriers == null || !SubscriptionManager.isValidPhoneId(slotIndex)) {
            return -1;
        }
        // Execute the method setCarrierRestrictionRules with an empty excluded list and
        // indicating priority for the allowed list.
        CarrierRestrictionRules carrierRestrictionRules = CarrierRestrictionRules.newBuilder()
                .setAllowedCarriers(carriers)
                .setDefaultCarrierRestriction(
                    CarrierRestrictionRules.CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED)
                .build();

        int result = setCarrierRestrictionRules(carrierRestrictionRules);

        // Convert result into int, as required by this method.
        if (result == SET_CARRIER_RESTRICTION_SUCCESS) {
            return carriers.size();
        } else {
            return -1;
        }
    }

    /**
     * The carrier restrictions were successfully set.
     * @hide
     */
    @SystemApi
    public static final int SET_CARRIER_RESTRICTION_SUCCESS = 0;

    /**
     * The carrier restrictions were not set due to lack of support in the modem. This can happen
     * if the modem does not support setting the carrier restrictions or if the configuration
     * passed in the {@code setCarrierRestrictionRules} is not supported by the modem.
     * @hide
     */
    @SystemApi
    public static final int SET_CARRIER_RESTRICTION_NOT_SUPPORTED = 1;

    /**
     * The setting of carrier restrictions failed.
     * @hide
     */
    @SystemApi
    public static final int SET_CARRIER_RESTRICTION_ERROR = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SET_CARRIER_RESTRICTION_"},
            value = {
                    SET_CARRIER_RESTRICTION_SUCCESS,
                    SET_CARRIER_RESTRICTION_NOT_SUPPORTED,
                    SET_CARRIER_RESTRICTION_ERROR
            })
    public @interface SetCarrierRestrictionResult {}

    /**
     * Set the allowed carrier list and the excluded carrier list indicating the priority between
     * the two lists.
     * Requires system privileges.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     *
     * <p>This method works only on devices with {@link
     * android.content.pm.PackageManager#FEATURE_TELEPHONY_CARRIERLOCK} enabled.
     *
     * @return {@link #SET_CARRIER_RESTRICTION_SUCCESS} in case of success.
     * {@link #SET_CARRIER_RESTRICTION_NOT_SUPPORTED} if the modem does not support the
     * configuration. {@link #SET_CARRIER_RESTRICTION_ERROR} in all other error cases.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @SetCarrierRestrictionResult
    public int setCarrierRestrictionRules(@NonNull CarrierRestrictionRules rules) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.setAllowedCarriers(rules);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setAllowedCarriers", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error calling ITelephony#setAllowedCarriers", e);
        }
        return SET_CARRIER_RESTRICTION_ERROR;
    }

    /**
     * Get the allowed carrier list for slotIndex.
     * Requires system privileges.
     *
     * <p>This method returns valid data on devices with {@link
     * android.content.pm.PackageManager#FEATURE_TELEPHONY_CARRIERLOCK} enabled.
     *
     * @deprecated Apps should use {@link getCarriersRestrictionRules} to retrieve the list of
     * allowed and excliuded carriers, as the result of this API is valid only when the excluded
     * list is empty. This API could return an empty list, even if some restrictions are present.
     *
     * @return List of {@link android.telephony.CarrierIdentifier}; empty list
     * means all carriers are allowed.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public List<CarrierIdentifier> getAllowedCarriers(int slotIndex) {
        if (SubscriptionManager.isValidPhoneId(slotIndex)) {
            CarrierRestrictionRules carrierRestrictionRule = getCarrierRestrictionRules();
            if (carrierRestrictionRule != null) {
                return carrierRestrictionRule.getAllowedCarriers();
            }
        }
        return new ArrayList<CarrierIdentifier>(0);
    }

    /**
     * Get the allowed carrier list and the excluded carrier list indicating the priority between
     * the two lists.
     * Require system privileges. In the future we may add this to carrier APIs.
     *
     * <p>This method returns valid data on devices with {@link
     * android.content.pm.PackageManager#FEATURE_TELEPHONY_CARRIERLOCK} enabled.
     *
     * @return {@link CarrierRestrictionRules} which contains the allowed carrier list and the
     * excluded carrier list with the priority between the two lists. Returns {@code null}
     * in case of error.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @Nullable
    public CarrierRestrictionRules getCarrierRestrictionRules() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getAllowedCarriers();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getAllowedCarriers", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error calling ITelephony#getAllowedCarriers", e);
        }
        return null;
    }

    /**
     * Used to enable or disable carrier data by the system based on carrier signalling or
     * carrier privileged apps. Different from {@link #setDataEnabled(boolean)} which is linked to
     * user settings, carrier data on/off won't affect user settings but will bypass the
     * settings and turns off data internally if set to {@code false}.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param enabled control enable or disable carrier data.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setCarrierDataEnabled(boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionSetMeteredApnsEnabled(
                        getSubId(SubscriptionManager.getDefaultDataSubscriptionId()), enabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setCarrierDataEnabled", e);
        }
    }

    /**
     * Action set from carrier signalling broadcast receivers to enable/disable radio
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param enabled control enable or disable radio.
     * @hide
     */
    public void carrierActionSetRadioEnabled(int subId, boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionSetRadioEnabled(subId, enabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionSetRadioEnabled", e);
        }
    }

    /**
     * Action set from carrier signalling broadcast receivers to start/stop reporting default
     * network available events
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @param report control start/stop reporting network status.
     * @hide
     */
    public void carrierActionReportDefaultNetworkStatus(int subId, boolean report) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionReportDefaultNetworkStatus(subId, report);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionReportDefaultNetworkStatus", e);
        }
    }

    /**
     * Action set from carrier signalling broadcast receivers to reset all carrier actions
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     * @param subId the subscription ID that this action applies to.
     * @hide
     */
    public void carrierActionResetAll(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionResetAll(subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionResetAll", e);
        }
    }

    /**
     * Get aggregated video call data usage since boot.
     * Permissions android.Manifest.permission.READ_NETWORK_USAGE_HISTORY is required.
     *
     * @param how one of the NetworkStats.STATS_PER_* constants depending on whether the request is
     * for data usage per uid or overall usage.
     * @return Snapshot of video call data usage
     * @hide
     */
    public NetworkStats getVtDataUsage(int how) {
        boolean perUidStats = (how == NetworkStats.STATS_PER_UID);
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getVtDataUsage(getSubId(), perUidStats);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getVtDataUsage", e);
        }
        return null;
    }

    /**
     * Policy control of data connection. Usually used when data limit is passed.
     * @param enabled True if enabling the data, otherwise disabling.
     * @param subId sub id
     * @hide
     */
    public void setPolicyDataEnabled(boolean enabled, int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setPolicyDataEnabled(enabled, subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#setPolicyDataEnabled", e);
        }
    }

    /**
     * Get Client request stats which will contain statistical information
     * on each request made by client.
     * Callers require either READ_PRIVILEGED_PHONE_STATE or
     * READ_PHONE_STATE to retrieve the information.
     * @param subId sub id
     * @return List of Client Request Stats
     * @hide
     */
    public List<ClientRequestStats> getClientRequestStats(int subId) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getClientRequestStats(getOpPackageName(), subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getClientRequestStats", e);
        }

        return null;
    }

    /**
     * Checks if phone is in emergency callback mode.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * @return true if phone is in emergency callback mode.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean getEmergencyCallbackMode() {
        return getEmergencyCallbackMode(getSubId());
    }

    /**
     * Check if phone is in emergency callback mode
     * @return true if phone is in emergency callback mode
     * @param subId the subscription ID that this action applies to.
     * @hide
     */
    public boolean getEmergencyCallbackMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return false;
            }
            return telephony.getEmergencyCallbackMode(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getEmergencyCallbackMode", e);
        }
        return false;
    }

    /**
     * Checks if manual network selection is allowed.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @return {@code true} if manual network selection is allowed, otherwise return {@code false}.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isManualNetworkSelectionAllowed() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isManualNetworkSelectionAllowed(getSubId());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isManualNetworkSelectionAllowed", e);
        }
        return true;
    }

    /**
     * Get the most recently available signal strength information.
     *
     * Get the most recent SignalStrength information reported by the modem. Due
     * to power saving this information may not always be current.
     * @return the most recent cached signal strength info from the modem
     */
    @Nullable
    public SignalStrength getSignalStrength() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSignalStrength(getSubId());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getSignalStrength", e);
        }
        return null;
    }

    /**
     * @hide
     * It's similar to isDataEnabled, but unlike isDataEnabled, this API also evaluates
     * carrierDataEnabled, policyDataEnabled etc to give a final decision of whether mobile data is
     * capable of using.
     */
    public boolean isDataCapable() {
        boolean retVal = false;
        try {
            int subId = getSubId(SubscriptionManager.getDefaultDataSubscriptionId());
            ITelephony telephony = getITelephony();
            if (telephony != null)
                retVal = telephony.isDataEnabled(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isDataEnabled", e);
        } catch (NullPointerException e) {
        }
        return retVal;
    }

    /**
     * In this mode, modem will not send specified indications when screen is off.
     * @hide
     */
    public static final int INDICATION_UPDATE_MODE_NORMAL                   = 1;

    /**
     * In this mode, modem will still send specified indications when screen is off.
     * @hide
     */
    public static final int INDICATION_UPDATE_MODE_IGNORE_SCREEN_OFF        = 2;

    /** @hide */
    @IntDef(prefix = { "INDICATION_UPDATE_MODE_" }, value = {
            INDICATION_UPDATE_MODE_NORMAL,
            INDICATION_UPDATE_MODE_IGNORE_SCREEN_OFF
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndicationUpdateMode{}

    /**
     * The indication for signal strength update.
     * @hide
     */
    public static final int INDICATION_FILTER_SIGNAL_STRENGTH               = 0x1;

    /**
     * The indication for full network state update.
     * @hide
     */
    public static final int INDICATION_FILTER_FULL_NETWORK_STATE            = 0x2;

    /**
     * The indication for data call dormancy changed update.
     * @hide
     */
    public static final int INDICATION_FILTER_DATA_CALL_DORMANCY_CHANGED    = 0x4;

    /**
     * The indication for link capacity estimate update.
     * @hide
     */
    public static final int INDICATION_FILTER_LINK_CAPACITY_ESTIMATE        = 0x8;

    /**
     * The indication for physical channel config update.
     * @hide
     */
    public static final int INDICATION_FILTER_PHYSICAL_CHANNEL_CONFIG       = 0x10;

    /** @hide */
    @IntDef(flag = true, prefix = { "INDICATION_FILTER_" }, value = {
            INDICATION_FILTER_SIGNAL_STRENGTH,
            INDICATION_FILTER_FULL_NETWORK_STATE,
            INDICATION_FILTER_DATA_CALL_DORMANCY_CHANGED,
            INDICATION_FILTER_LINK_CAPACITY_ESTIMATE,
            INDICATION_FILTER_PHYSICAL_CHANNEL_CONFIG
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndicationFilters{}

    /**
     * Sets radio indication update mode. This can be used to control the behavior of indication
     * update from modem to Android frameworks. For example, by default several indication updates
     * are turned off when screen is off, but in some special cases (e.g. carkit is connected but
     * screen is off) we want to turn on those indications even when the screen is off.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @param filters Indication filters. Should be a bitmask of INDICATION_FILTER_XXX.
     * @see #INDICATION_FILTER_SIGNAL_STRENGTH
     * @see #INDICATION_FILTER_FULL_NETWORK_STATE
     * @see #INDICATION_FILTER_DATA_CALL_DORMANCY_CHANGED
     * @param updateMode The voice activation state
     * @see #INDICATION_UPDATE_MODE_NORMAL
     * @see #INDICATION_UPDATE_MODE_IGNORE_SCREEN_OFF
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setRadioIndicationUpdateMode(@IndicationFilters int filters,
                                             @IndicationUpdateMode int updateMode) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setRadioIndicationUpdateMode(getSubId(), filters, updateMode);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * A test API to override carrier information including mccmnc, imsi, iccid, gid1, gid2,
     * plmn and spn. This would be handy for, eg, forcing a particular carrier id, carrier's config
     * (also any country or carrier overlays) to be loaded when using a test SIM with a call box.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @hide
     */
    @TestApi
    public void setCarrierTestOverride(String mccmnc, String imsi, String iccid, String gid1,
            String gid2, String plmn, String spn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setCarrierTestOverride(
                        getSubId(), mccmnc, imsi, iccid, gid1, gid2, plmn, spn);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
    }

    /**
     * A test API to return installed carrier id list version
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     *
     * @hide
     */
    @TestApi
    public int getCarrierIdListVersion() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCarrierIdListVersion(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID_LIST_VERSION;
    }

    /**
     * How many modems can have simultaneous data connections.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getNumberOfModemsWithSimultaneousDataConnections() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getNumberOfModemsWithSimultaneousDataConnections(
                        getSubId(), mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return 0;
    }

    /**
     * Enable or disable OpportunisticNetworkService.
     *
     * This method should be called to enable or disable
     * OpportunisticNetwork service on the device.
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @param enable enable(True) or disable(False)
     * @return returns true if successfully set.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setOpportunisticNetworkState(boolean enable) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        boolean ret = false;
        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService != null) {
                ret = iOpportunisticNetworkService.setEnable(enable, pkgForDebug);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "enableOpportunisticNetwork RemoteException", ex);
        }

        return ret;
    }

    /**
     * is OpportunisticNetworkService enabled
     *
     * This method should be called to determine if the OpportunisticNetworkService is
     * enabled
     *
     * <p>
     * Requires Permission:
     *   {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isOpportunisticNetworkEnabled() {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        boolean isEnabled = false;

        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService != null) {
                isEnabled = iOpportunisticNetworkService.isEnabled(pkgForDebug);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "enableOpportunisticNetwork RemoteException", ex);
        }

        return isEnabled;
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @LongDef(flag = true, prefix = {"NETWORK_TYPE_BITMASK_"},
            value = {NETWORK_TYPE_BITMASK_UNKNOWN,
                    NETWORK_TYPE_BITMASK_GSM,
                    NETWORK_TYPE_BITMASK_GPRS,
                    NETWORK_TYPE_BITMASK_EDGE,
                    NETWORK_TYPE_BITMASK_CDMA,
                    NETWORK_TYPE_BITMASK_1xRTT,
                    NETWORK_TYPE_BITMASK_EVDO_0,
                    NETWORK_TYPE_BITMASK_EVDO_A,
                    NETWORK_TYPE_BITMASK_EVDO_B,
                    NETWORK_TYPE_BITMASK_EHRPD,
                    NETWORK_TYPE_BITMASK_HSUPA,
                    NETWORK_TYPE_BITMASK_HSDPA,
                    NETWORK_TYPE_BITMASK_HSPA,
                    NETWORK_TYPE_BITMASK_HSPAP,
                    NETWORK_TYPE_BITMASK_UMTS,
                    NETWORK_TYPE_BITMASK_TD_SCDMA,
                    NETWORK_TYPE_BITMASK_LTE,
                    NETWORK_TYPE_BITMASK_LTE_CA,
                    NETWORK_TYPE_BITMASK_NR,
            })
    public @interface NetworkTypeBitMask {}

    // 2G
    /**
     * network type bitmask unknown.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_UNKNOWN = 0L;
    /**
     * network type bitmask indicating the support of radio tech GSM.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_GSM = (1 << (NETWORK_TYPE_GSM -1));
    /**
     * network type bitmask indicating the support of radio tech GPRS.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_GPRS = (1 << (NETWORK_TYPE_GPRS -1));
    /**
     * network type bitmask indicating the support of radio tech EDGE.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_EDGE = (1 << (NETWORK_TYPE_EDGE -1));
    /**
     * network type bitmask indicating the support of radio tech CDMA(IS95A/IS95B).
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_CDMA = (1 << (NETWORK_TYPE_CDMA -1));
    /**
     * network type bitmask indicating the support of radio tech 1xRTT.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_1xRTT = (1 << (NETWORK_TYPE_1xRTT - 1));
    // 3G
    /**
     * network type bitmask indicating the support of radio tech EVDO 0.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_EVDO_0 = (1 << (NETWORK_TYPE_EVDO_0 -1));
    /**
     * network type bitmask indicating the support of radio tech EVDO A.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_EVDO_A = (1 << (NETWORK_TYPE_EVDO_A - 1));
    /**
     * network type bitmask indicating the support of radio tech EVDO B.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_EVDO_B = (1 << (NETWORK_TYPE_EVDO_B -1));
    /**
     * network type bitmask indicating the support of radio tech EHRPD.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_EHRPD = (1 << (NETWORK_TYPE_EHRPD -1));
    /**
     * network type bitmask indicating the support of radio tech HSUPA.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_HSUPA = (1 << (NETWORK_TYPE_HSUPA -1));
    /**
     * network type bitmask indicating the support of radio tech HSDPA.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_HSDPA = (1 << (NETWORK_TYPE_HSDPA -1));
    /**
     * network type bitmask indicating the support of radio tech HSPA.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_HSPA = (1 << (NETWORK_TYPE_HSPA -1));
    /**
     * network type bitmask indicating the support of radio tech HSPAP.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_HSPAP = (1 << (NETWORK_TYPE_HSPAP -1));
    /**
     * network type bitmask indicating the support of radio tech UMTS.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_UMTS = (1 << (NETWORK_TYPE_UMTS -1));
    /**
     * network type bitmask indicating the support of radio tech TD_SCDMA.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_TD_SCDMA = (1 << (NETWORK_TYPE_TD_SCDMA -1));
    // 4G
    /**
     * network type bitmask indicating the support of radio tech LTE.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_LTE = (1 << (NETWORK_TYPE_LTE -1));
    /**
     * network type bitmask indicating the support of radio tech LTE CA (carrier aggregation).
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_LTE_CA = (1 << (NETWORK_TYPE_LTE_CA -1));

    /**
     * network type bitmask indicating the support of radio tech NR(New Radio) 5G.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_NR = (1 << (NETWORK_TYPE_NR -1));

    /**
     * network type bitmask indicating the support of radio tech IWLAN.
     * @hide
     */
    @SystemApi
    public static final long NETWORK_TYPE_BITMASK_IWLAN = (1 << (NETWORK_TYPE_IWLAN -1));

    /**
     * @return Modem supported radio access family bitmask
     *
     * <p>Requires permission: {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE} or
     * that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @NetworkTypeBitMask long getSupportedRadioAccessFamily() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return (long) telephony.getRadioAccessFamily(getSlotIndex(), getOpPackageName());
            } else {
                // This can happen when the ITelephony interface is not up yet.
                return NETWORK_TYPE_BITMASK_UNKNOWN;
            }
        } catch (RemoteException ex) {
            // This shouldn't happen in the normal case
            return NETWORK_TYPE_BITMASK_UNKNOWN;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return NETWORK_TYPE_BITMASK_UNKNOWN;
        }
    }

    /**
     * Get the emergency number list based on current locale, sim, default, modem and network.
     *
     * <p>In each returned list, the emergency number {@link EmergencyNumber} coming from higher
     * priority sources will be located at the smaller index; the priority order of sources are:
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_SIM} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_DATABASE} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_DEFAULT} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG}
     *
     * <p>The subscriptions which the returned list would be based on, are all the active
     * subscriptions, no matter which subscription could be used to create TelephonyManager.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PHONE_STATE} or the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return Map including the key as the active subscription ID (Note: if there is no active
     * subscription, the key is {@link SubscriptionManager#getDefaultSubscriptionId}) and the value
     * as the list of {@link EmergencyNumber}; null if this information is not available; or throw
     * a SecurityException if the caller does not have the permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @Nullable
    public Map<Integer, List<EmergencyNumber>> getCurrentEmergencyNumberList() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return null;
            }
            return telephony.getCurrentEmergencyNumberList(mContext.getOpPackageName());
        } catch (RemoteException ex) {
            Log.e(TAG, "getCurrentEmergencyNumberList RemoteException", ex);
        }
        return null;
    }

    /**
     * Get the per-category emergency number list based on current locale, sim, default, modem
     * and network.
     *
     * <p>In each returned list, the emergency number {@link EmergencyNumber} coming from higher
     * priority sources will be located at the smaller index; the priority order of sources are:
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_SIM} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_DATABASE} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_DEFAULT} >
     * {@link EmergencyNumber#EMERGENCY_NUMBER_SOURCE_MODEM_CONFIG}
     *
     * <p>The subscriptions which the returned list would be based on, are all the active
     * subscriptions, no matter which subscription could be used to create TelephonyManager.
     *
     * <p>Requires permission {@link android.Manifest.permission#READ_PHONE_STATE} or the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param categories the emergency service categories which are the bitwise-OR combination of
     * the following constants:
     * <ol>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_POLICE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AMBULANCE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MIEC} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AIEC} </li>
     * </ol>
     * @return Map including the key as the active subscription ID (Note: if there is no active
     * subscription, the key is {@link SubscriptionManager#getDefaultSubscriptionId}) and the value
     * as the list of {@link EmergencyNumber}; null if this information is not available; or throw
     * a SecurityException if the caller does not have the permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @Nullable
    public Map<Integer, List<EmergencyNumber>> getCurrentEmergencyNumberList(
            @EmergencyServiceCategories int categories) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return null;
            }
            Map<Integer, List<EmergencyNumber>> numberMap = telephony
                    .getCurrentEmergencyNumberList(mContext.getOpPackageName());
            if (numberMap != null) {
                for (Integer subscriptionId : numberMap.keySet()) {
                    List<EmergencyNumber> numberList = numberMap.get(subscriptionId);
                    for (EmergencyNumber number : numberList) {
                        if (!number.isInEmergencyServiceCategories(categories)) {
                            numberList.remove(number);
                        }
                    }
                }
            }
            return numberMap;
        } catch (RemoteException ex) {
            Log.e(TAG, "getCurrentEmergencyNumberList with Categories RemoteException", ex);
        }
        return null;
    }

    /**
     * Checks if the supplied number is an emergency number based on current locale, sim, default,
     * modem and network.
     *
     * <p>The subscriptions which the identification would be based on, are all the active
     * subscriptions, no matter which subscription could be used to create TelephonyManager.
     *
     * @param number - the number to look up
     * @return {@code true} if the given number is an emergency number based on current locale,
     * sim, modem and network; {@code false} otherwise.
     */
    public boolean isCurrentEmergencyNumber(@NonNull String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return false;
            }
            return telephony.isCurrentEmergencyNumber(number, true);
        } catch (RemoteException ex) {
            Log.e(TAG, "isCurrentEmergencyNumber RemoteException", ex);
        }
        return false;
    }

    /**
     * Checks if the supplied number is an emergency number based on current locale, sim, default,
     * modem and network.
     *
     * <p> Specifically, this method will return {@code true} if the specified number is an
     * emergency number, *or* if the number simply starts with the same digits as any current
     * emergency number.
     *
     * <p>The subscriptions which the identification would be based on, are all the active
     * subscriptions, no matter which subscription could be used to create TelephonyManager.
     *
     * <p>Requires permission: {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE} or
     * that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param number - the number to look up
     * @return {@code true} if the given number is an emergency number or it simply starts with
     * the same digits of any current emergency number based on current locale, sim, modem and
     * network; {@code false} if it is not; or throw an SecurityException if the caller does not
     * have the required permission/privileges
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isCurrentPotentialEmergencyNumber(@NonNull String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) {
                return false;
            }
            return telephony.isCurrentEmergencyNumber(number, false);
        } catch (RemoteException ex) {
            Log.e(TAG, "isCurrentEmergencyNumber RemoteException", ex);
        }
        return false;
    }

    /**
     * Set preferred opportunistic data subscription id.
     *
     * <p>Requires that the calling app has carrier privileges on both primary and
     * secondary subscriptions (see
     * {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param subId which opportunistic subscription
     * {@link SubscriptionManager#getOpportunisticSubscriptions} is preferred for cellular data.
     * Pass {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID} to unset the preference
     * @return true if request is accepted, else false.
     *
     */
    public boolean setPreferredOpportunisticDataSubscription(int subId) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService != null) {
                return iOpportunisticNetworkService
                        .setPreferredDataSubscriptionId(subId, pkgForDebug);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredDataSubscriptionId RemoteException", ex);
        }
        return false;
    }

    /**
     * Get preferred opportunistic data subscription Id
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}),
     * or has either READ_PRIVILEGED_PHONE_STATE
     * or {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE} permission.
     * @return subId preferred opportunistic subscription id or
     * {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID} if there are no preferred
     * subscription id
     *
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public int getPreferredOpportunisticDataSubscription() {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService != null) {
                subId = iOpportunisticNetworkService.getPreferredDataSubscriptionId(pkgForDebug);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPreferredDataSubscriptionId RemoteException", ex);
        }
        return subId;
    }

    /**
     * Update availability of a list of networks in the current location.
     *
     * This api should be called to inform OpportunisticNetwork Service about the availability
     * of a network at the current location. This information will be used by OpportunisticNetwork
     * service to decide to attach to the network opportunistically. If an empty list is passed,
     * it is assumed that no network is available.
     * Requires that the calling app has carrier privileges on both primary and
     * secondary subscriptions (see {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     * @param availableNetworks is a list of available network information.
     * @return true if request is accepted
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    public boolean updateAvailableNetworks(List<AvailableNetworkInfo> availableNetworks) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        boolean ret = false;
        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService != null && availableNetworks != null) {
                ret = iOpportunisticNetworkService.updateAvailableNetworks(availableNetworks,
                        pkgForDebug);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "updateAvailableNetworks RemoteException", ex);
        }
        return ret;
    }

    /**
     * Enable or disable a logical modem stack. When a logical modem is disabled, the corresponding
     * SIM will still be visible to the user but its mapping modem will not have any radio activity.
     * For example, we will disable a modem when user or system believes the corresponding SIM
     * is temporarily not needed (e.g. out of coverage), and will enable it back on when needed.
     *
     * Requires that the calling app has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     * @param slotIndex which corresponding modem will operate on.
     * @param enable whether to enable or disable the modem stack.
     * @return whether the operation is successful.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean enableModemForSlot(int slotIndex, boolean enable) {
        boolean ret = false;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ret = telephony.enableModemForSlot(slotIndex, enable);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "enableModem RemoteException", ex);
        }
        return ret;
    }

    /**
     * Indicate if the user is allowed to use multiple SIM cards at the same time to register
     * on the network (e.g. Dual Standby or Dual Active) when the device supports it, or if the
     * usage is restricted. This API is used to prevent usage of multiple SIM card, based on
     * policies of the carrier.
     * <p>Note: the API does not prevent access to the SIM cards for operations that don't require
     * access to the network.
     *
     * @param isMultisimCarrierRestricted true if usage of multiple SIMs is restricted, false
     * otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setMultisimCarrierRestriction(boolean isMultisimCarrierRestricted) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setMultisimCarrierRestriction(isMultisimCarrierRestricted);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "setMultisimCarrierRestriction RemoteException", e);
        }
    }

    /**
     * Returns if the usage of multiple SIM cards at the same time to register on the network
     * (e.g. Dual Standby or Dual Active) is supported by the device and by the carrier.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return true if usage of multiple SIMs is supported, false otherwise.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isMultisimSupported() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isMultisimSupported(getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "isMultisimSupported RemoteException", e);
        }
        return false;
    }

    /**
     * Broadcast intent action for network country code changes.
     *
     * <p>
     * The {@link #EXTRA_NETWORK_COUNTRY} extra indicates the country code of the current
     * network returned by {@link #getNetworkCountryIso()}.
     *
     * @see #EXTRA_NETWORK_COUNTRY
     * @see #getNetworkCountryIso()
     */
    public static final String ACTION_NETWORK_COUNTRY_CHANGED =
            "android.telephony.action.NETWORK_COUNTRY_CHANGED";

    /**
     * The extra used with an {@link #ACTION_NETWORK_COUNTRY_CHANGED} to specify the
     * the country code in ISO 3166 format.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_NETWORK_COUNTRY =
            "android.telephony.extra.NETWORK_COUNTRY";
    /**
     * Switch configs to enable multi-sim or switch back to single-sim
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     * @param numOfSims number of live SIMs we want to switch to
     * @throws android.os.RemoteException
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void switchMultiSimConfig(int numOfSims) {
        //only proceed if multi-sim is not restricted
        if (!isMultisimSupported()) {
            Rlog.e(TAG, "switchMultiSimConfig not possible. It is restricted or not supported.");
            return;
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.switchMultiSimConfig(numOfSims);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "switchMultiSimConfig RemoteException", ex);
        }
    }

    /**
     * Get whether reboot is required or not after making changes to modem configurations.
     * The modem configuration change refers to switching from single SIM configuration to DSDS
     * or the other way around.
     * @Return {@code true} if reboot is required after making changes to modem configurations,
     * otherwise return {@code false}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isRebootRequiredForModemConfigChange() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isRebootRequiredForModemConfigChange();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "isRebootRequiredForModemConfigChange RemoteException", e);
        }
        return false;
    }

    /**
     * Retrieve the Radio HAL Version for this device.
     *
     * Get the HAL version for the IRadio interface for test purposes.
     *
     * @return a Pair of (major version, minor version) or (-1,-1) if unknown.
     *
     * @hide
     */
    @TestApi
    public Pair<Integer, Integer> getRadioHalVersion() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                int version = service.getRadioHalVersion();
                if (version == -1) return new Pair<Integer, Integer>(-1, -1);
                return new Pair<Integer, Integer>(version / 100, version % 100);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getRadioHalVersion() RemoteException", e);
        }
        return new Pair<Integer, Integer>(-1, -1);
    }
}
