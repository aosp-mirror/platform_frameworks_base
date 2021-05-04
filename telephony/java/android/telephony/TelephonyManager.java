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
import static android.provider.Telephony.Carriers.DPC_URI;
import static android.provider.Telephony.Carriers.INVALID_APN_ID;

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
import android.annotation.WorkerThread;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.WorkSource;
import android.provider.Settings.SettingNotFoundException;
import android.service.carrier.CarrierIdentifier;
import android.sysprop.TelephonyProperties;
import android.telecom.CallScreeningService;
import android.telecom.InCallService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.Annotation.ApnType;
import android.telephony.Annotation.CallState;
import android.telephony.Annotation.CarrierPrivilegeStatus;
import android.telephony.Annotation.NetworkType;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SimActivationState;
import android.telephony.Annotation.UiccAppType;
import android.telephony.CallForwardingInfo.CallForwardingReason;
import android.telephony.VisualVoicemailService.VisualVoicemailTask;
import android.telephony.data.ApnSetting;
import android.telephony.data.ApnSetting.MvnoType;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.emergency.EmergencyNumber.EmergencyServiceCategories;
import android.telephony.ims.ImsMmTelManager;
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
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CellNetworkScanResult;
import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.INumberVerificationCallback;
import com.android.internal.telephony.IOns;
import com.android.internal.telephony.IPhoneSubInfo;
import com.android.internal.telephony.ISetOpportunisticDataCallback;
import com.android.internal.telephony.ISms;
import com.android.internal.telephony.ISub;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.IUpdateAvailableNetworksCallback;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.SmsApplication;
import com.android.telephony.Rlog;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
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
 *
 * <p>TelephonyManager is intended for use on devices that implement
 * {@link android.content.pm.PackageManager#FEATURE_TELEPHONY FEATURE_TELEPHONY}. On devices
 * that do not implement this feature, the behavior is not reliable.
 */
@SystemService(Context.TELEPHONY_SERVICE)
public class TelephonyManager {
    private static final String TAG = "TelephonyManager";

    /**
     * To expand the error codes for {@link TelephonyManager#updateAvailableNetworks} and
     * {@link TelephonyManager#setPreferredOpportunisticDataSubscription}.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long CALLBACK_ON_MORE_ERROR_CODE_CHANGE = 130595455L;

    /**
     * The key to use when placing the result of {@link #requestModemActivityInfo(ResultReceiver)}
     * into the ResultReceiver Bundle.
     * @hide
     */
    public static final String MODEM_ACTIVITY_RESULT_KEY = "controller_activity";

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

    public static final int NETWORK_SELECTION_MODE_UNKNOWN = 0;
    public static final int NETWORK_SELECTION_MODE_AUTO = 1;
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

    /**
     * Used in carrier Wi-Fi for IMSI + IMPI encryption, this indicates a public key that's
     * available for use in ePDG links.
     *
     * @hide
     */
    @SystemApi
    static public final int KEY_TYPE_EPDG = 1;

    /**
     * Used in carrier Wi-Fi for IMSI + IMPI encryption, this indicates a public key that's
     * available for use in WLAN links.
     *
     * @hide
     */
    @SystemApi
    static public final int KEY_TYPE_WLAN = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"KEY_TYPE_"}, value = {KEY_TYPE_EPDG, KEY_TYPE_WLAN})
    public @interface KeyType {}

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

    private final Context mContext;
    private final int mSubId;
    @UnsupportedAppUsage
    private SubscriptionManager mSubscriptionManager;
    private TelephonyScanManager mTelephonyScanManager;

    /** Cached service handles, cleared by resetServiceHandles() at death */
    private static final Object sCacheLock = new Object();

    /** @hide */
    private static boolean sServiceHandleCacheEnabled = true;

    @GuardedBy("sCacheLock")
    private static IPhoneSubInfo sIPhoneSubInfo;
    @GuardedBy("sCacheLock")
    private static ISub sISub;
    @GuardedBy("sCacheLock")
    private static ISms sISms;
    @GuardedBy("sCacheLock")
    private static final DeathRecipient sServiceDeath = new DeathRecipient();

    /** Enum indicating multisim variants
     *  DSDS - Dual SIM Dual Standby
     *  DSDA - Dual SIM Dual Active
     *  TSTS - Triple SIM Triple Standby
     **/
    /** @hide */
    @UnsupportedAppUsage(implicitMember =
            "values()[Landroid/telephony/TelephonyManager$MultiSimVariants;")
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
            if (Objects.equals(context.getAttributionTag(), appContext.getAttributionTag())) {
                mContext = appContext;
            } else {
                mContext = appContext.createAttributionContext(context.getAttributionTag());
            }
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
        } else {
            ITelephony telephony = getITelephony();
            if (telephony == null) return null;
            try {
                return telephony.getCurrentPackageName();
            } catch (RemoteException ex) {
                return null;
            } catch (NullPointerException ex) {
                return null;
            }
        }
    }

    private String getAttributionTag() {
        // For legacy reasons the TelephonyManager has API for getting
        // a static instance with no context set preventing us from
        // getting the attribution tag.
        if (mContext != null) {
            return mContext.getAttributionTag();
        }
        return null;
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
                TelephonyProperties.multi_sim_config().orElse("");
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
     * Returns 1 for Single standby mode (Single SIM functionality).
     * Returns 2 for Dual standby mode (Dual SIM functionality).
     * Returns 3 for Tri standby mode (Tri SIM functionality).
     * @deprecated Use {@link #getActiveModemCount} instead.
     */
    @Deprecated
    public int getPhoneCount() {
        return getActiveModemCount();
    }

    /**
     * Returns the number of logical modems currently configured to be activated.
     *
     * Returns 0 if none of voice, sms, data is not supported
     * Returns 1 for Single standby mode (Single SIM functionality).
     * Returns 2 for Dual standby mode (Dual SIM functionality).
     * Returns 3 for Tri standby mode (Tri SIM functionality).
     */
    public int getActiveModemCount() {
        int modemCount = 1;
        switch (getMultiSimConfiguration()) {
            case UNKNOWN:
                modemCount = 1;
                // check for voice and data support, 0 if not supported
                if (!isVoiceCapable() && !isSmsCapable() && !isDataCapable()) {
                    modemCount = 0;
                }
                break;
            case DSDS:
            case DSDA:
                modemCount = 2;
                break;
            case TSTS:
                modemCount = 3;
                break;
        }
        return modemCount;
    }

    /**
     * Return how many logical modem can be potentially active simultaneously, in terms of hardware
     * capability.
     * It might return different value from {@link #getActiveModemCount}. For example, for a
     * dual-SIM capable device operating in single SIM mode (only one logical modem is turned on),
     * {@link #getActiveModemCount} returns 1 while this API returns 2.
     */
    public int getSupportedModemCount() {
        return TelephonyProperties.max_active_modems().orElse(getActiveModemCount());
    }

    /**
     * Gets the maximum number of SIMs that can be active, based on the device's multisim
     * configuration.
     * @return 1 for single-SIM, DSDS, and TSTS devices. 2 for DSDA devices.
     * @hide
     */
    @SystemApi
    public int getMaxNumberOfSimultaneouslyActiveSims() {
        switch (getMultiSimConfiguration()) {
            case UNKNOWN:
            case DSDS:
            case TSTS:
                return 1;
            case DSDA:
                return 2;
        }
        return 1;
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
        int subId = getSubscriptionId(phoneAccountHandle);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return null;
        }
        return new TelephonyManager(mContext, subId);
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public boolean isMultiSimEnabled() {
        return getPhoneCount() > 1;
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
    @SystemApi
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
     * <p>
     *
     * @deprecated Companion apps for wearable devices should use the {@link InCallService} API
     * to retrieve the phone number for calls instead.  Apps performing call screening should use
     * the {@link CallScreeningService} API instead.
     */
    @Deprecated
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";

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
     * containing the disconnect cause.
     *
     * @see DisconnectCause
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String name, int defaultValue)}.
     *
     * @deprecated Should use the {@link TelecomManager#EXTRA_DISCONNECT_CAUSE} instead.
     * @hide
     */
    @Deprecated
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
     * Broadcast action to be received by Broadcast receivers.
     *
     * Indicates multi-SIM configuration is changed. For example, it changed
     * from single SIM capable to dual-SIM capable (DSDS or DSDA) or triple-SIM mode.
     *
     * It doesn't indicate how many subscriptions are actually active, or which states SIMs are,
     * or that all steps during multi-SIM change are done. To know those information you still need
     * to listen to SIM_STATE changes or active subscription changes.
     *
     * See extra of {@link #EXTRA_ACTIVE_SIM_SUPPORTED_COUNT} for updated value.
     */
    public static final String ACTION_MULTI_SIM_CONFIG_CHANGED =
            "android.telephony.action.MULTI_SIM_CONFIG_CHANGED";


    /**
     * The number of active SIM supported by current multi-SIM config. It's not related to how many
     * SIM/subscriptions are currently active.
     *
     * Same value will be returned by {@link #getActiveModemCount()}.
     *
     * For single SIM mode, it's 1.
     * For DSDS or DSDA mode, it's 2.
     * For triple-SIM mode, it's 3.
     *
     * Extra of {@link #ACTION_MULTI_SIM_CONFIG_CHANGED}.
     *
     * type: integer
     */
    public static final String EXTRA_ACTIVE_SIM_SUPPORTED_COUNT =
            "android.telephony.extra.ACTIVE_SIM_SUPPORTED_COUNT";

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

    /** @hide */
    @IntDef(prefix = { "CDMA_ROAMING_MODE_" }, value = {
            CDMA_ROAMING_MODE_RADIO_DEFAULT,
            CDMA_ROAMING_MODE_HOME,
            CDMA_ROAMING_MODE_AFFILIATED,
            CDMA_ROAMING_MODE_ANY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CdmaRoamingMode{}

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
     * Broadcast Action: The subscription specific carrier identity has changed.
     *
     * A specific carrier ID returns the fine-grained carrier ID of the current subscription.
     * It can represent the fact that a carrier may be in effect an aggregation of other carriers
     * (ie in an MVNO type scenario) where each of these specific carriers which are used to make
     * up the actual carrier service may have different carrier configurations.
     * A specific carrier ID could also be used, for example, in a scenario where a carrier requires
     * different carrier configuration for different service offering such as a prepaid plan.
     *
     * the specific carrier ID would be used for configuration purposes, but apps wishing to know
     * about the carrier itself should use the regular carrier ID returned by
     * {@link #getSimCarrierId()}.
     *
     * <p>Similar like {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED}, this intent will be
     * sent on the event of {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} while its also
     * possible to be sent without {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} when
     * specific carrier ID changes while carrier ID remains the same.
     * e.g, the same subscription switches to different IMSI could potentially change its
     * specific carrier ID while carrier id remains the same.
     * @see #getSimSpecificCarrierId()
     * @see #getSimCarrierId()
     *
     * The intent will have the following extra values:
     * <ul>
     *   <li>{@link #EXTRA_SPECIFIC_CARRIER_ID} The up-to-date specific carrier id of the
     *   current subscription.
     *   </li>
     *   <li>{@link #EXTRA_SPECIFIC_CARRIER_NAME} The up-to-date name of the specific carrier id.
     *   </li>
     *   <li>{@link #EXTRA_SUBSCRIPTION_ID} The subscription id associated with the changed carrier
     *   identity.
     *   </li>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED =
            "android.telephony.action.SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED";

    /**
     * An int extra used with {@link #ACTION_SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED} which
     * indicates the updated specific carrier id returned by
     * {@link TelephonyManager#getSimSpecificCarrierId()}. Note, its possible specific carrier id
     * changes while {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} remains the same
     * e.g, when subscription switch to different IMSIs.
     * <p>Will be {@link TelephonyManager#UNKNOWN_CARRIER_ID} if the subscription is unavailable or
     * the carrier cannot be identified.
     */
    public static final String EXTRA_SPECIFIC_CARRIER_ID =
            "android.telephony.extra.SPECIFIC_CARRIER_ID";

    /**
     * An string extra used with {@link #ACTION_SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED}
     * which indicates the updated specific carrier name returned by
     * {@link TelephonyManager#getSimSpecificCarrierIdName()}.
     * <p>it's a user-facing name of the specific carrier id {@link #EXTRA_SPECIFIC_CARRIER_ID}
     * e.g, Tracfone-AT&T
     */
    public static final String EXTRA_SPECIFIC_CARRIER_NAME =
            "android.telephony.extra.SPECIFIC_CARRIER_NAME";

    /**
     * An int extra used with {@link #ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED} to indicate the
     * subscription which has changed; or in general whenever a subscription ID needs specified.
     */
    public static final String EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_ID";

    /**
     * Broadcast Action: The Service Provider string(s) have been updated. Activities or
     * services that use these strings should update their display.
     *
     * <p>The intent will have the following extra values:
     * <dl>
     *   <dt>{@link #EXTRA_SHOW_PLMN}</dt>
     *   <dd>Boolean that indicates whether the PLMN should be shown.</dd>
     *   <dt>{@link #EXTRA_PLMN}</dt>
     *   <dd>The operator name of the registered network, as a string.</dd>
     *   <dt>{@link #EXTRA_SHOW_SPN}</dt>
     *   <dd>Boolean that indicates whether the SPN should be shown.</dd>
     *   <dt>{@link #EXTRA_SPN}</dt>
     *   <dd>The service provider name, as a string.</dd>
     *   <dt>{@link #EXTRA_DATA_SPN}</dt>
     *   <dd>The service provider name for data service, as a string.</dd>
     * </dl>
     *
     * Note that {@link #EXTRA_SHOW_PLMN} may indicate that {@link #EXTRA_PLMN} should be displayed,
     * even though the value for {@link #EXTRA_PLMN} is null. This can happen, for example, if the
     * phone has not registered to a network yet. In this case the receiver may substitute an
     * appropriate placeholder string (eg, "No service").
     *
     * It is recommended to display {@link #EXTRA_PLMN} before / above {@link #EXTRA_SPN} if
     * both are displayed.
     *
     * <p>Note: this is a protected intent that can only be sent by the system.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SERVICE_PROVIDERS_UPDATED =
            "android.telephony.action.SERVICE_PROVIDERS_UPDATED";

    /**
     * String intent extra to be used with {@link ACTION_SERVICE_PROVIDERS_UPDATED} to indicate
     * whether the PLMN should be shown.
     * @hide
     */
    public static final String EXTRA_SHOW_PLMN = "android.telephony.extra.SHOW_PLMN";

    /**
     * String intent extra to be used with {@link ACTION_SERVICE_PROVIDERS_UPDATED} to indicate
     * the operator name of the registered network.
     * @hide
     */
    public static final String EXTRA_PLMN = "android.telephony.extra.PLMN";

    /**
     * String intent extra to be used with {@link ACTION_SERVICE_PROVIDERS_UPDATED} to indicate
     * whether the PLMN should be shown.
     * @hide
     */
    public static final String EXTRA_SHOW_SPN = "android.telephony.extra.SHOW_SPN";

    /**
     * String intent extra to be used with {@link ACTION_SERVICE_PROVIDERS_UPDATED} to indicate
     * the service provider name.
     * @hide
     */
    public static final String EXTRA_SPN = "android.telephony.extra.SPN";

    /**
     * String intent extra to be used with {@link ACTION_SERVICE_PROVIDERS_UPDATED} to indicate
     * the service provider name for data service.
     * @hide
     */
    public static final String EXTRA_DATA_SPN = "android.telephony.extra.DATA_SPN";

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
     * A service action that identifies
     * a {@link android.service.carrier.CarrierMessagingClientService} subclass in the
     * AndroidManifest.xml.
     *
     * <p>See {@link android.service.carrier.CarrierMessagingClientService} for the details.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_CARRIER_MESSAGING_CLIENT_SERVICE =
            "android.telephony.action.CARRIER_MESSAGING_CLIENT_SERVICE";

    /**
     * An int extra used with {@link #ACTION_DATA_STALL_DETECTED} to indicate the
     * action associated with the data stall recovery.
     *
     * @see #ACTION_DATA_STALL_DETECTED
     *
     * @hide
     */
    public static final String EXTRA_RECOVERY_ACTION = "recoveryAction";

    private static final long MAX_NUMBER_VERIFICATION_TIMEOUT_MILLIS = 60000;

    /**
     * Intent sent when an error occurs that debug tools should log and possibly take further
     * action such as capturing vendor-specific logs.
     *
     * A privileged application that reads these events should take appropriate vendor-specific
     * action to record the event and collect further information to assist in analysis, debugging,
     * and resolution of any associated issue.
     *
     * <p>This event should not be used for generic logging or diagnostic monitoring purposes and
     * should generally be sent at a low rate. Instead, this mechanism should be used for the
     * framework to notify a debugging application that an event (such as a bug) has occured
     * within the framework if that event should trigger the collection and preservation of other
     * more detailed device state for debugging.
     *
     * <p>At most one application can receive these events and should register a receiver in
     * in the application manifest. For performance reasons, if no application to receive these
     * events is detected at boot, then these events will not be sent.
     *
     * <p>Each event will include an {@link EXTRA_ANOMALY_ID} that will uniquely identify the
     * event that has occurred. Each event will be sent to the diagnostic monitor only once per
     * boot cycle (as another optimization).
     *
     * @see #EXTRA_ANOMALY_ID
     * @see #EXTRA_ANOMALY_DESCRIPTION
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public static final String ACTION_ANOMALY_REPORTED =
            "android.telephony.action.ANOMALY_REPORTED";

    /**
     * An arbitrary ParcelUuid which should be consistent for each occurrence of a DebugEvent.
     *
     * This field must be included in all {@link ACTION_ANOMALY_REPORTED} events.
     *
     * @see #ACTION_ANOMALY_REPORTED
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ANOMALY_ID = "android.telephony.extra.ANOMALY_ID";

    /**
     * A freeform string description of the Anomaly.
     *
     * This field is optional for all {@link ACTION_ANOMALY_REPORTED}s, as a guideline should not
     * exceed 80 characters, and should be as short as possible to convey the essence of the event.
     *
     * @see #ACTION_ANOMALY_REPORTED
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ANOMALY_DESCRIPTION =
            "android.telephony.extra.ANOMALY_DESCRIPTION";

    /**
     * Broadcast intent sent to indicate primary (non-opportunistic) subscription list has changed.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PRIMARY_SUBSCRIPTION_LIST_CHANGED =
            "android.telephony.action.PRIMARY_SUBSCRIPTION_LIST_CHANGED";

    /**
     * Integer intent extra to be used with {@link #ACTION_PRIMARY_SUBSCRIPTION_LIST_CHANGED}
     * to indicate what type of SIM selection is needed.
     *
     * @hide
     */
    public static final String EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE =
            "android.telephony.extra.DEFAULT_SUBSCRIPTION_SELECT_TYPE";

    /** @hide */
    @IntDef({
            EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_NONE,
            EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_DATA,
            EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_VOICE,
            EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_SMS,
            EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_ALL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DefaultSubscriptionSelectType{}

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate there's no need to re-select any default subscription.
     * @hide
     */
    public static final int EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_NONE = 0;

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate there's a need to select default data subscription.
     * @hide
     */
    public static final int EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_DATA = 1;

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate there's a need to select default voice call subscription.
     * @hide
     */
    public static final int EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_VOICE = 2;

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate there's a need to select default sms subscription.
     * @hide
     */
    public static final int EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_SMS = 3;

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate user to decide whether current SIM should be preferred for all
     * data / voice / sms. {@link #EXTRA_SUBSCRIPTION_ID} will specified to indicate
     * which subscription should be the default subscription.
     * @hide
     */
    public static final int EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_ALL = 4;

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate that default subscription for data/sms/voice is now determined, that
     * it should dismiss any dialog or pop-ups that is asking user to select default sub.
     * This is used when, for example, opportunistic subscription is configured. At that
     * time the primary becomes default sub there's no need to ask user to select anymore.
     * @hide
     */
    public static final int EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE_DISMISS = 5;

    /**
     * Integer intent extra to be used with {@link #ACTION_PRIMARY_SUBSCRIPTION_LIST_CHANGED}
     * to indicate if the SIM combination in DSDS has limitation or compatible issue.
     * e.g. two CDMA SIMs may disrupt each other's voice call in certain scenarios.
     *
     * @hide
     */
    public static final String EXTRA_SIM_COMBINATION_WARNING_TYPE =
            "android.telephony.extra.SIM_COMBINATION_WARNING_TYPE";

    /** @hide */
    @IntDef({
            EXTRA_SIM_COMBINATION_WARNING_TYPE_NONE,
            EXTRA_SIM_COMBINATION_WARNING_TYPE_DUAL_CDMA
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SimCombinationWarningType{}

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate there's no SIM combination warning.
     * @hide
     */
    public static final int EXTRA_SIM_COMBINATION_WARNING_TYPE_NONE = 0;

    /**
     * Used as an int value for {@link #EXTRA_DEFAULT_SUBSCRIPTION_SELECT_TYPE}
     * to indicate two active SIMs are both CDMA hence there might be functional limitation.
     * @hide
     */
    public static final int EXTRA_SIM_COMBINATION_WARNING_TYPE_DUAL_CDMA = 1;

    /**
     * String intent extra to be used with {@link #ACTION_PRIMARY_SUBSCRIPTION_LIST_CHANGED}
     * to indicate what's the name of SIM combination it has limitation or compatible issue.
     * e.g. two CDMA SIMs may disrupt each other's voice call in certain scenarios, and the
     * name will be "operator1 & operator2".
     *
     * @hide
     */
    public static final String EXTRA_SIM_COMBINATION_NAMES =
            "android.telephony.extra.SIM_COMBINATION_NAMES";

    /**
     * <p>Broadcast Action: The emergency callback mode is changed.
     * <ul>
     *   <li><em>EXTRA_PHONE_IN_ECM_STATE</em> - A boolean value,true=phone in ECM,
     *   false=ECM off</li>
     * </ul>
     * <p class="note">
     * You can <em>not</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver,
     * android.content.IntentFilter) Context.registerReceiver()}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     *
     * @see #EXTRA_PHONE_IN_ECM_STATE
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_EMERGENCY_CALLBACK_MODE_CHANGED =
            "android.intent.action.EMERGENCY_CALLBACK_MODE_CHANGED";


    /**
     * Extra included in {@link #ACTION_EMERGENCY_CALLBACK_MODE_CHANGED}.
     * Indicates whether the phone is in an emergency phone state.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PHONE_IN_ECM_STATE =
            "android.telephony.extra.PHONE_IN_ECM_STATE";

    /**
     * <p>Broadcast Action: when data connections get redirected with validation failure.
     * intended for sim/account status checks and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>{@link #EXTRA_APN_TYPE}</li><dd>A string with the apn type.</dd>
     *   <li>{@link #EXTRA_APN_TYPE_INT}</li><dd>A integer with the apn type.</dd>
     *   <li>{@link #EXTRA_REDIRECTION_URL}</li><dd>redirection url string</dd>
     *   <li>subId</li><dd>Sub Id which associated the data connection failure.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     * @hide
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_CARRIER_SIGNAL_REDIRECTED =
            "com.android.internal.telephony.CARRIER_SIGNAL_REDIRECTED";

    /**
     * <p>Broadcast Action: when data connections setup fails.
     * intended for sim/account status checks and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>{@link #EXTRA_APN_TYPE}</li><dd>A string with the apn type.</dd>
     *   <li>{@link #EXTRA_APN_TYPE_INT}</li><dd>A integer with the apn type.</dd>
     *   <li>{@link #EXTRA_ERROR_CODE}</li><dd>A integer with dataFailCause.</dd>
     *   <li>subId</li><dd>Sub Id which associated the data connection failure.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system. </p>
     * @hide
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED =
            "com.android.internal.telephony.CARRIER_SIGNAL_REQUEST_NETWORK_FAILED";

    /**
     * <p>Broadcast Action: when pco value is available.
     * intended for sim/account status checks and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>{@link #EXTRA_APN_TYPE}</li><dd>A string with the apn type.</dd>
     *   <li>{@link #EXTRA_APN_TYPE_INT}</li><dd>A integer with the apn type.</dd>
     *   <li>{@link #EXTRA_APN_PROTOCOL}</li><dd>A string with the protocol of the apn connection
     *      (IP,IPV6, IPV4V6)</dd>
     *   <li>{@link #EXTRA_APN_PROTOCOL_INT}</li><dd>A integer with the protocol of the apn
     *      connection (IP,IPV6, IPV4V6)</dd>
     *   <li>{@link #EXTRA_PCO_ID}</li><dd>An integer indicating the pco id for the data.</dd>
     *   <li>{@link #EXTRA_PCO_VALUE}</li><dd>A byte array of pco data read from modem.</dd>
     *   <li>subId</li><dd>Sub Id which associated the data connection.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system. </p>
     * @hide
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_CARRIER_SIGNAL_PCO_VALUE =
            "com.android.internal.telephony.CARRIER_SIGNAL_PCO_VALUE";

    /**
     * <p>Broadcast Action: when system default network available/unavailable with
     * carrier-disabled mobile data. Intended for carrier apps to set/reset carrier actions when
     * other network becomes system default network, Wi-Fi for example.
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>{@link #EXTRA_DEFAULT_NETWORK_AVAILABLE}</li>
     *   <dd>A boolean indicates default network available.</dd>
     *   <li>subId</li><dd>Sub Id which associated the default data.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system. </p>
     * @hide
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE =
            "com.android.internal.telephony.CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE";

    /**
     * <p>Broadcast Action: when framework reset all carrier actions on sim load or absent.
     * intended for carrier apps clean up (clear UI e.g.) and only sent to the specified carrier app
     * The intent will have the following extra values:</p>
     * <ul>
     *   <li>subId</li><dd>Sub Id which associated the data connection failure.</dd>
     * </ul>
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     * @hide
     */
    @SuppressLint("ActionValue")
    public static final String ACTION_CARRIER_SIGNAL_RESET =
            "com.android.internal.telephony.CARRIER_SIGNAL_RESET";

    // CARRIER_SIGNAL_ACTION extra keys
    /**
     *  An string extra of redirected url upon {@link #ACTION_CARRIER_SIGNAL_REDIRECTED}.
     *  @hide
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_REDIRECTION_URL = "redirectionUrl";

    /**
     *  An integer extra of error code upon {@link #ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED}.
     *  Check {@link DataFailCause} for all possible values.
     *  @hide
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_ERROR_CODE = "errorCode";

    /**
     *  An string extra of corresponding apn type upon
     *  {@link #ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED},
     *  {@link #ACTION_CARRIER_SIGNAL_REDIRECTED} and
     *  {@link #ACTION_CARRIER_SIGNAL_PCO_VALUE} broadcasts.
     *  @deprecated This is kept for backward compatibility reason. Use {@link #EXTRA_APN_TYPE_INT}
     *  instead.
     *
     *  @hide
     */
    @Deprecated
    @SuppressLint("ActionValue")
    public static final String EXTRA_APN_TYPE = "apnType";

    /**
     *  An string integer of corresponding apn type upon
     *  {@link #ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED},
     *  {@link #ACTION_CARRIER_SIGNAL_REDIRECTED} and
     *  {@link #ACTION_CARRIER_SIGNAL_PCO_VALUE} broadcasts.
     *  Check {@link ApnSetting} TYPE_* for its values.
     *  @hide
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_APN_TYPE_INT = "apnTypeInt";

    /**
     *  An string extra with the protocol of the apn connection (IP,IPV6, IPV4V6) upon
     *  {@link #ACTION_CARRIER_SIGNAL_PCO_VALUE} broadcasts.
     *  @deprecated This is kept for backward compatibility reason.
     *  Use {@link #EXTRA_APN_PROTOCOL_INT} instead.
     *
     *  @hide
     */
    @Deprecated
    @SuppressLint("ActionValue")
    public static final String EXTRA_APN_PROTOCOL = "apnProto";

    /**
     *  An integer extra with the protocol of the apn connection (IP,IPV6, IPV4V6) upon
     *  {@link #ACTION_CARRIER_SIGNAL_PCO_VALUE} broadcasts.
     *  Check {@link ApnSetting} PROTOCOL_* for its values.
     *  @hide
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_APN_PROTOCOL_INT = "apnProtoInt";

    /**
     *  An integer extra indicating the pco id for the data upon
     *  {@link #ACTION_CARRIER_SIGNAL_PCO_VALUE} broadcasts.
     *  @hide
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_PCO_ID = "pcoId";

    /**
     *  An extra of byte array of pco data read from modem upon
     *  {@link #ACTION_CARRIER_SIGNAL_PCO_VALUE} broadcasts.
     *  @hide
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_PCO_VALUE = "pcoValue";

    /**
     *  An boolean extra indicating default network available upon
     *  {@link #ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE} broadcasts.
     *  @hide
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_DEFAULT_NETWORK_AVAILABLE = "defaultNetworkAvailable";

    /**
     * <p>Broadcast Action: The emergency call state is changed.
     * <ul>
     *   <li><em>EXTRA_PHONE_IN_EMERGENCY_CALL</em> - A boolean value, true if phone in emergency
     *   call, false otherwise</li>
     * </ul>
     * <p class="note">
     * You can <em>not</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver,
     * android.content.IntentFilter) Context.registerReceiver()}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     *
     * @see #EXTRA_PHONE_IN_EMERGENCY_CALL
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_EMERGENCY_CALL_STATE_CHANGED =
            "android.intent.action.EMERGENCY_CALL_STATE_CHANGED";


    /**
     * Extra included in {@link #ACTION_EMERGENCY_CALL_STATE_CHANGED}.
     * It indicates whether the phone is making an emergency call.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PHONE_IN_EMERGENCY_CALL =
            "android.telephony.extra.PHONE_IN_EMERGENCY_CALL";

    /**
     * <p>Broadcast Action: It indicates the Emergency callback mode blocks datacall/sms
     * <p class="note">.
     * This is to pop up a notice to show user that the phone is in emergency callback mode
     * and data calls and outgoing sms are blocked.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS =
            "android.telephony.action.SHOW_NOTICE_ECM_BLOCK_OTHERS";

    /**
     * Broadcast Action: The default data subscription has changed in a multi-SIM device.
     * This has the following extra values:</p>
     * <ul>
     *   <li><em>subscription</em> - A int, the current data default subscription.</li>
     * </ul>
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED";

    /**
     * Broadcast Action: The default voice subscription has changed in a mult-SIm device.
     * This has the following extra values:</p>
     * <ul>
     *   <li><em>subscription</em> - A int, the current voice default subscription.</li>
     * </ul>
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED";

    /**
     * Broadcast Action: This triggers a client initiated OMA-DM session to the OMA server.
     * <p class="note">
     * Open Mobile Alliance (OMA) Device Management (DM).
     *
     * This intent is used by the system components to trigger OMA-DM
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("ActionValue")
    public static final String ACTION_REQUEST_OMADM_CONFIGURATION_UPDATE =
            "com.android.omadm.service.CONFIGURATION_UPDATE";

    //
    //
    // Device Info
    //
    //

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     * <p>
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @Nullable
    public String getDeviceSoftwareVersion() {
        return getDeviceSoftwareVersion(getSlotIndex());
    }

    /**
     * Returns the software version number for the device, for example,
     * the IMEI/SV for GSM phones. Return null if the software version is
     * not available.
     * <p>
     * Requires Permission: READ_PRIVILEGED_PHONE_STATE.
     *
     * @param slotIndex of which deviceID is returned
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @Nullable
    public String getDeviceSoftwareVersion(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getDeviceSoftwareVersionForSlot(slotIndex, getOpPackageName(),
                    getAttributionTag());
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
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}) on any
     *     active subscription.
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     *
     * @deprecated Use {@link #getImei} which returns IMEI for GSM or {@link #getMeid} which returns
     * MEID for CDMA.
     */
    @Deprecated
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getDeviceId() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return null;
            return telephony.getDeviceIdWithFeature(mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}) on any
     *     active subscription.
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     *
     * @param slotIndex of which deviceID is returned
     *
     * @deprecated Use {@link #getImei} which returns IMEI for GSM or {@link #getMeid} which returns
     * MEID for CDMA.
     */
    @Deprecated
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getDeviceId(int slotIndex) {
        // FIXME this assumes phoneId == slotIndex
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getDeviceIdForPhone(slotIndex, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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
     * See {@link #getImei(int)} for details on the required permissions and behavior
     * when the caller does not hold sufficient permissions.
     */
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getImei() {
        return getImei(getSlotIndex());
    }

    /**
     * Returns the IMEI (International Mobile Equipment Identity). Return null if IMEI is not
     * available.
     *
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}) on any
     *     active subscription.
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     *
     * @param slotIndex of which IMEI is returned
     */
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getImei(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            return telephony.getImeiForSlot(slotIndex, getOpPackageName(), getAttributionTag());
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
    @Nullable
    public String getTypeAllocationCode() {
        return getTypeAllocationCode(getSlotIndex());
    }

    /**
     * Returns the Type Allocation Code from the IMEI. Return null if Type Allocation Code is not
     * available.
     *
     * @param slotIndex of which Type Allocation Code is returned
     */
    @Nullable
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
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}) on any
     *     active subscription.
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     */
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getMeid() {
        return getMeid(getSlotIndex());
    }

    /**
     * Returns the MEID (Mobile Equipment Identifier). Return null if MEID is not available.
     *
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}) on any
     *     active subscription.
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     *
     * @param slotIndex of which MEID is returned
     */
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getMeid(int slotIndex) {
        ITelephony telephony = getITelephony();
        if (telephony == null) return null;

        try {
            String meid = telephony.getMeidForSlot(slotIndex, getOpPackageName(),
                    getAttributionTag());
            if (TextUtils.isEmpty(meid)) {
                Log.d(TAG, "getMeid: return null because MEID is not available");
                return null;
            }
            return meid;
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
    @Nullable
    public String getManufacturerCode() {
        return getManufacturerCode(getSlotIndex());
    }

    /**
     * Returns the Manufacturer Code from the MEID. Return null if Manufacturer Code is not
     * available.
     *
     * @param slotIndex of which Type Allocation Code is returned
     */
    @Nullable
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
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     */
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getNai() {
        return getNaiBySubscriberId(getSubId());
    }

    /**
     * Returns the NAI. Return null if NAI is not available.
     *
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
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
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            String nai = info.getNaiForSubscriber(subId, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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

            CellIdentity cellIdentity = telephony.getCellLocation(mContext.getOpPackageName(),
                    mContext.getAttributionTag());
            CellLocation cl = cellIdentity.asCellLocation();
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
            return telephony.getNeighboringCellInfo(mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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
     * Phone is via IMS.
     *
     * @hide
     */
    public static final int PHONE_TYPE_IMS = PhoneConstants.PHONE_TYPE_IMS;

    /**
     * Phone is via Third Party.
     *
     * @hide
     */
    public static final int PHONE_TYPE_THIRD_PARTY = PhoneConstants.PHONE_TYPE_THIRD_PARTY;

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
    @UnsupportedAppUsage
    private int getPhoneTypeFromProperty(int phoneId) {
        Integer type = getTelephonyProperty(
                phoneId, TelephonyProperties.current_active_phone(), null);
        if (type != null) return type;
        return getPhoneTypeFromNetworkType(phoneId);
    }

    private int getPhoneTypeFromNetworkType() {
        return getPhoneTypeFromNetworkType(getPhoneId());
    }

    /** {@hide} */
    private int getPhoneTypeFromNetworkType(int phoneId) {
        // When the system property CURRENT_ACTIVE_PHONE, has not been set,
        // use the system property for default network type.
        // This is a fail safe, and can only happen at first boot.
        Integer mode = getTelephonyProperty(phoneId, TelephonyProperties.default_network(), null);
        if (mode != null) {
            return TelephonyManager.getPhoneType(mode);
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

    /**
     * @return The max value for the timeout passed in {@link #requestNumberVerification}.
     * @hide
     */
    @SystemApi
    public static long getMaxNumberVerificationTimeoutMillis() {
        return MAX_NUMBER_VERIFICATION_TIMEOUT_MILLIS;
    }

    /** Kernel command line */
    private static final String sKernelCmdLine = getProcCmdLine();

    /** Pattern for selecting the product type from the kernel command line */
    private static final Pattern sProductTypePattern =
        Pattern.compile("\\sproduct_type\\s*=\\s*(\\w+)");

    /** The ProductType used for LTE on CDMA devices */
    private static final String sLteOnCdmaProductType =
            TelephonyProperties.lte_on_cdma_product_type().orElse("");

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

        curVal = TelephonyProperties.lte_on_cdma_device().orElse(
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
        return getTelephonyProperty(phoneId, TelephonyProperties.operator_alpha(), "");
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
        return getTelephonyProperty(phoneId, TelephonyProperties.operator_numeric(), "");
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
        return getTelephonyProperty(phoneId, TelephonyProperties.operator_is_roaming(), false);
    }

    /**
     * Returns the ISO-3166-1 alpha-2 country code equivalent of the MCC (Mobile Country Code) of
     * the current registered operator or the cell nearby, if available.
     *
     * Note: Result may be unreliable on CDMA networks (use {@link #getPhoneType()} to determine
     * if on a CDMA network).
     * <p>
     * @return the lowercase 2 character ISO-3166-1 alpha-2 country code, or empty string if not
     * available.
     */
    public String getNetworkCountryIso() {
        return getNetworkCountryIso(getSlotIndex());
    }

    /**
     * Returns the ISO-3166-1 alpha-2 country code equivalent of the MCC (Mobile Country Code) of
     * the current registered operator or the cell nearby, if available. This is same as
     * {@link #getNetworkCountryIso()} but allowing specifying the SIM slot index. This is used for
     * accessing network country info from the SIM slot that does not have SIM inserted.
     *
     * Note: Result may be unreliable on CDMA networks (use {@link #getPhoneType()} to determine
     * if on a CDMA network).
     * <p>
     *
     * @param slotIndex the SIM slot index to get network country ISO.
     *
     * @return the lowercase 2 character ISO-3166-1 alpha-2 country code, or empty string if not
     * available.
     *
     * @throws IllegalArgumentException when the slotIndex is invalid.
     *
     */
    @NonNull
    public String getNetworkCountryIso(int slotIndex) {
        try {
            if (slotIndex != SubscriptionManager.DEFAULT_SIM_SLOT_INDEX
                    && !SubscriptionManager.isValidSlotIndex(slotIndex)) {
                throw new IllegalArgumentException("invalid slot index " + slotIndex);
            }

            ITelephony telephony = getITelephony();
            if (telephony == null) return "";
            return telephony.getNetworkCountryIsoForPhone(slotIndex);
        } catch (RemoteException ex) {
            return "";
        }
    }

    /**
     * @hide
     * @deprecated Use {@link #getNetworkCountryIso(int)} instead.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@link #getNetworkCountryIso(int)} instead.")
    public String getNetworkCountryIsoForPhone(int phoneId) {
        return getNetworkCountryIso(phoneId);
    }

    /*
     * When adding a network type to the list below, make sure to add the correct icon to
     * MobileSignalController.mapIconSets() as well as NETWORK_TYPES
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

    private static final @NetworkType int[] NETWORK_TYPES = {
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
            NETWORK_TYPE_NR
    };

    /**
     * Return a collection of all network types
     * @return network types
     *
     * @hide
     */
    public static @NonNull @NetworkType int[] getAllNetworkTypes() {
        return NETWORK_TYPES;
    }

    /**
     * Return the current data network type.
     *
     * @deprecated use {@link #getDataNetworkType()}
     * @return the NETWORK_TYPE_xxxx for current data connection.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public @NetworkType int getNetworkType() {
        return getNetworkType(getSubId(SubscriptionManager.getActiveDataSubscriptionId()));
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public int getNetworkType(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getNetworkTypeForSubscriber(subId, getOpPackageName(),
                        getAttributionTag());
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
        return getDataNetworkType(getSubId(SubscriptionManager.getActiveDataSubscriptionId()));
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
                return telephony.getDataNetworkTypeForSubscriber(subId, getOpPackageName(),
                        getAttributionTag());
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
                return telephony.getVoiceNetworkTypeForSubscriber(subId, getOpPackageName(),
                        getAttributionTag());
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
    public static String getNetworkTypeName(@NetworkType int type) {
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

    /**
     * Returns the bitmask for a given technology (network type)
     * @param networkType for which bitmask is returned
     * @return the network type bitmask
     * {@hide}
     */
    public static @NetworkTypeBitMask long getBitMaskForNetworkType(@NetworkType int networkType) {
        switch(networkType) {
            case NETWORK_TYPE_GSM:
                return NETWORK_TYPE_BITMASK_GSM;
            case NETWORK_TYPE_GPRS:
                return NETWORK_TYPE_BITMASK_GPRS;
            case NETWORK_TYPE_EDGE:
                return NETWORK_TYPE_BITMASK_EDGE;
            case NETWORK_TYPE_CDMA:
                return NETWORK_TYPE_BITMASK_CDMA;
            case NETWORK_TYPE_1xRTT:
                return NETWORK_TYPE_BITMASK_1xRTT;
            case NETWORK_TYPE_EVDO_0:
                return NETWORK_TYPE_BITMASK_EVDO_0;
            case NETWORK_TYPE_EVDO_A:
                return NETWORK_TYPE_BITMASK_EVDO_A;
            case NETWORK_TYPE_EVDO_B:
                return NETWORK_TYPE_BITMASK_EVDO_B;
            case NETWORK_TYPE_EHRPD:
                return NETWORK_TYPE_BITMASK_EHRPD;
            case NETWORK_TYPE_HSUPA:
                return NETWORK_TYPE_BITMASK_HSUPA;
            case NETWORK_TYPE_HSDPA:
                return NETWORK_TYPE_BITMASK_HSDPA;
            case NETWORK_TYPE_HSPA:
                return NETWORK_TYPE_BITMASK_HSPA;
            case NETWORK_TYPE_HSPAP:
                return NETWORK_TYPE_BITMASK_HSPAP;
            case NETWORK_TYPE_UMTS:
                return NETWORK_TYPE_BITMASK_UMTS;
            case NETWORK_TYPE_TD_SCDMA:
                return NETWORK_TYPE_BITMASK_TD_SCDMA;
            case NETWORK_TYPE_LTE:
                return NETWORK_TYPE_BITMASK_LTE;
            case NETWORK_TYPE_LTE_CA:
                return NETWORK_TYPE_BITMASK_LTE_CA;
            case NETWORK_TYPE_NR:
                return NETWORK_TYPE_BITMASK_NR;
            default:
                return NETWORK_TYPE_BITMASK_UNKNOWN;
        }
    }

    //
    //
    // SIM Card
    //
    //

    /** @hide */
    @IntDef(prefix = {"SIM_STATE_"},
            value = {
                    SIM_STATE_UNKNOWN,
                    SIM_STATE_ABSENT,
                    SIM_STATE_PIN_REQUIRED,
                    SIM_STATE_PUK_REQUIRED,
                    SIM_STATE_NETWORK_LOCKED,
                    SIM_STATE_READY,
                    SIM_STATE_NOT_READY,
                    SIM_STATE_PERM_DISABLED,
                    SIM_STATE_CARD_IO_ERROR,
                    SIM_STATE_CARD_RESTRICTED,
                    SIM_STATE_LOADED,
                    SIM_STATE_PRESENT,
            })
    public @interface SimState {}

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
     * And they are of the form {@code *#*#<code>#*#*}. The intent will have the data
     * URI: {@code android_secret_code://<code>}. It is possible that a manifest
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
    public @SimState int getSimState() {
        int simState = getSimStateIncludingLoaded();
        if (simState == SIM_STATE_LOADED) {
            simState = SIM_STATE_READY;
        }
        return simState;
    }

    private @SimState int getSimStateIncludingLoaded() {
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
    public @SimState int getSimCardState() {
        int simState = getSimState();
        return getSimCardStateFromSimState(simState);
    }

    /**
     * Returns a constant indicating the state of the device SIM card in a physical slot.
     *
     * @param physicalSlotIndex physical slot index
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
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimState int getSimCardState(int physicalSlotIndex) {
        int simState = getSimState(getLogicalSlotIndex(physicalSlotIndex));
        return getSimCardStateFromSimState(simState);
    }

    /**
     * Converts SIM state to SIM card state.
     * @param simState
     * @return SIM card state
     */
    private @SimState int getSimCardStateFromSimState(int simState) {
        switch (simState) {
            case SIM_STATE_UNKNOWN:
            case SIM_STATE_ABSENT:
            case SIM_STATE_CARD_IO_ERROR:
            case SIM_STATE_CARD_RESTRICTED:
                return simState;
            default:
                return SIM_STATE_PRESENT;
        }
    }

    /**
     * Converts a physical slot index to logical slot index.
     * @param physicalSlotIndex physical slot index
     * @return logical slot index
     */
    private int getLogicalSlotIndex(int physicalSlotIndex) {
        UiccSlotInfo[] slotInfos = getUiccSlotsInfo();
        if (slotInfos != null && physicalSlotIndex >= 0 && physicalSlotIndex < slotInfos.length) {
            return slotInfos[physicalSlotIndex].getLogicalSlotIdx();
        }

        return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
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
    public @SimState int getSimApplicationState() {
        int simState = getSimStateIncludingLoaded();
        return getSimApplicationStateFromSimState(simState);
    }

    /**
     * Returns a constant indicating the state of the card applications on the device SIM card in
     * a physical slot.
     *
     * @param physicalSlotIndex physical slot index
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
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @SimState int getSimApplicationState(int physicalSlotIndex) {
        int simState =
                SubscriptionManager.getSimStateForSlotIndex(getLogicalSlotIndex(physicalSlotIndex));
        return getSimApplicationStateFromSimState(simState);
    }

    /**
     * Converts SIM state to SIM application state.
     * @param simState
     * @return SIM application state
     */
    private @SimState int getSimApplicationStateFromSimState(int simState) {
        switch (simState) {
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
                return simState;
        }
    }


    /**
     * Returns true if the specified type of application (e.g. {@link #APPTYPE_CSIM} is present
     * on the UICC card.
     *
     * Requires that the calling app has READ_PRIVILEGED_PHONE_STATE permission
     *
     * @param appType the uicc app type like {@link APPTYPE_CSIM}
     * @return true if the specified type of application in UICC CARD or false if no uicc or error.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isApplicationOnUicc(@UiccAppType int appType) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isApplicationOnUicc(getSubId(), appType);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isApplicationOnUicc", e);
        }
        return false;
    }

    /**
     * Returns a constant indicating the state of the device SIM card in a logical slot.
     *
     * @param slotIndex logical slot index
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
    public @SimState int getSimState(int slotIndex) {
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
        return getTelephonyProperty(phoneId, TelephonyProperties.icc_operator_numeric(), "");
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
        return getTelephonyProperty(phoneId, TelephonyProperties.icc_operator_alpha(), "");
    }

    /**
     * Returns the ISO-3166-1 alpha-2 country code equivalent for the SIM provider's country code.
     * <p>
     * The ISO-3166-1 alpha-2 country code is provided in lowercase 2 character format.
     * @return the lowercase 2 character ISO-3166-1 alpha-2 country code, or empty string is not
     * available.
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
    public static String getSimCountryIso(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        return getSimCountryIsoForPhone(phoneId);
    }

    /**
     * Returns the ISO country code equivalent for the SIM provider's country code.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static String getSimCountryIsoForPhone(int phoneId) {
        return getTelephonyProperty(phoneId, TelephonyProperties.icc_operator_iso_country(), "");
    }

    /**
     * Returns the serial number of the SIM, if applicable. Return null if it is
     * unavailable.
     *
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     */
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getSimSerialNumber() {
         return getSimSerialNumber(getSubId());
    }

    /**
     * Returns the serial number for the given subscription, if applicable. Return null if it is
     * unavailable.
     *
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     *
     * @param subId for which Sim Serial number is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @UnsupportedAppUsage
    public String getSimSerialNumber(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getIccSerialNumberForSubscriber(subId, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Return if the current radio can support both 3GPP and 3GPP2 radio technologies at the same
     * time. This is also known as global mode, which includes LTE, CDMA, EvDo and GSM/WCDMA.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @return {@code true} if 3GPP and 3GPP2 radio technologies can be supported at the same time
     *         {@code false} if not supported or unknown
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isLteCdmaEvdoGsmWcdmaEnabled() {
        return getLteOnCdmaMode(getSubId()) == PhoneConstants.LTE_ON_CDMA_TRUE;
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
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @UnsupportedAppUsage
    public int getLteOnCdmaMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return PhoneConstants.LTE_ON_CDMA_UNKNOWN;
            return telephony.getLteOnCdmaModeForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
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
     * Gets information about currently inserted UICCs and eUICCs.
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
    @NonNull
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
     * Get the mapping from logical slots to physical slots. The key of the map is the logical slot
     * id and the value is the physical slots id mapped to this logical slot id.
     *
     * @return a map indicates the mapping from logical slots to physical slots. The size of the map
     * should be {@link #getPhoneCount()} if success, otherwise return an empty map.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull
    public Map<Integer, Integer> getLogicalToPhysicalSlotMapping() {
        Map<Integer, Integer> slotMapping = new HashMap<>();
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int[] slotMappingArray = telephony.getSlotsMapping();
                for (int i = 0; i < slotMappingArray.length; i++) {
                    slotMapping.put(i, slotMappingArray[i]);
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
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     */
    @SuppressAutoDoc // No support for device / profile owner or carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getSubscriberId() {
        return getSubscriberId(getSubId());
    }

    /**
     * Returns the unique subscriber ID, for example, the IMSI for a GSM phone
     * for a subscription.
     * Return null if it is unavailable.
     *
     * <p>Starting with API level 29, persistent device identifiers are guarded behind additional
     * restrictions, and apps are recommended to use resettable identifiers (see <a
     * href="/training/articles/user-data-ids">Best practices for unique identifiers</a>). This
     * method can be invoked if one of the following requirements is met:
     * <ul>
     *     <li>If the calling app has been granted the READ_PRIVILEGED_PHONE_STATE permission; this
     *     is a privileged permission that can only be granted to apps preloaded on the device.
     *     <li>If the calling app is the device or profile owner and has been granted the
     *     {@link Manifest.permission#READ_PHONE_STATE} permission. The profile owner is an app that
     *     owns a managed profile on the device; for more details see <a
     *     href="https://developer.android.com/work/managed-profiles">Work profiles</a>.
     *     Profile owner access is deprecated and will be removed in a future release.
     *     <li>If the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *     <li>If the calling app is the default SMS role holder (see {@link
     *     RoleManager#isRoleHeld(String)}).
     * </ul>
     *
     * <p>If the calling app does not meet one of these requirements then this method will behave
     * as follows:
     *
     * <ul>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app has the
     *     READ_PHONE_STATE permission then null is returned.</li>
     *     <li>If the calling app's target SDK is API level 28 or lower and the app does not have
     *     the READ_PHONE_STATE permission, or if the calling app is targeting API level 29 or
     *     higher, then a SecurityException is thrown.</li>
     * </ul>
     *
     * @param subId whose subscriber id is returned
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getSubscriberId(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getSubscriberIdForSubscriber(subId, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns carrier specific information that will be used to encrypt the IMSI and IMPI,
     * including the public key and the key identifier; or {@code null} if not available.
     * <p>
     * For a multi-sim device, the dafault data sim is used if not specified.
     * <p>
     * Requires Permission: READ_PRIVILEGED_PHONE_STATE.
     *
     * @param keyType whether the key is being used for EPDG or WLAN. Valid values are
     *        {@link #KEY_TYPE_EPDG} or {@link #KEY_TYPE_WLAN}.
     * @return ImsiEncryptionInfo Carrier specific information that will be used to encrypt the
     *         IMSI and IMPI. This includes the public key and the key identifier. This information
     *         will be stored in the device keystore. {@code null} will be returned when no key is
     *         found, and the carrier does not require a key.
     * @throws IllegalArgumentException when an invalid key is found or when key is required but
     *         not found.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @SystemApi
    @Nullable
    public ImsiEncryptionInfo getCarrierInfoForImsiEncryption(@KeyType int keyType) {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
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
     * Resets the carrier keys used to encrypt the IMSI and IMPI.
     * <p>
     * This involves 2 steps:
     *  1. Delete the keys from the database.
     *  2. Send an intent to download new Certificates.
     * <p>
     * For a multi-sim device, the dafault data sim is used if not specified.
     * <p>
     * Requires Permission: MODIFY_PHONE_STATE.
     *
     * @see #getCarrierInfoForImsiEncryption
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public void resetCarrierKeysForImsiEncryption() {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
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
    private static boolean isKeyEnabled(int keyAvailability, @KeyType int keyType) {
        int returnValue = (keyAvailability >> (keyType - 1)) & 1;
        return (returnValue == 1) ? true : false;
    }

    /**
     * If Carrier requires Imsi to be encrypted.
     * @hide
     */
    private boolean isImsiEncryptionRequired(int subId, @KeyType int keyType) {
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
            IPhoneSubInfo info = getSubscriberInfoService();
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
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getGroupIdLevel1ForSubscriber(getSubId(), mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getGroupIdLevel1ForSubscriber(subId, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Returns the phone number string for line 1, for example, the MSISDN
     * for a GSM phone for a particular subscription. Return null if it is unavailable.
     * <p>
     * The default SMS app can also use this.
     *
     * <p>Requires Permission:
     *     {@link android.Manifest.permission#READ_SMS READ_SMS},
     *     {@link android.Manifest.permission#READ_PHONE_NUMBERS READ_PHONE_NUMBERS},
     *     that the caller is the default SMS app,
     *     or that the caller has carrier privileges (see {@link #hasCarrierPrivileges})
     *     for any API level.
     *     {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *     for apps targeting SDK API level 29 and below.
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
     * <p>Requires Permission:
     *     {@link android.Manifest.permission#READ_SMS READ_SMS},
     *     {@link android.Manifest.permission#READ_PHONE_NUMBERS READ_PHONE_NUMBERS},
     *     that the caller is the default SMS app,
     *     or that the caller has carrier privileges (see {@link #hasCarrierPrivileges})
     *     for any API level.
     *     {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *     for apps targeting SDK API level 29 and below.
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
                number = telephony.getLine1NumberForDisplay(subId, mContext.getOpPackageName(),
                         mContext.getAttributionTag());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (number != null) {
            return number;
        }
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getLine1NumberForSubscriber(subId, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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
    @UnsupportedAppUsage
    @TestApi
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
                        getOpPackageName(), getAttributionTag());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        if (alphaTag != null) {
            return alphaTag;
        }
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getLine1AlphaTagForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone restarts due to crashing
            return null;
        }
    }

    /**
     * Return the set of subscriber IDs that should be considered "merged together" for data usage
     * purposes. This is commonly {@code null} to indicate no merging is required. Any returned
     * subscribers are sorted in a deterministic order.
     * <p>
     * The returned set of subscriber IDs will include the subscriber ID corresponding to this
     * TelephonyManager's subId.
     *
     * This is deprecated and {@link #getMergedImsisFromGroup()} should be used for data
     * usage merging purpose.
     * TODO: remove this API.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @Deprecated
    public @Nullable String[] getMergedSubscriberIds() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.getMergedSubscriberIds(getSubId(), getOpPackageName(),
                        getAttributionTag());
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Return the set of IMSIs that should be considered "merged together" for data usage
     * purposes. Unlike {@link #getMergedSubscriberIds()} this API merge IMSIs based on
     * subscription grouping: IMSI of those in the same group will all be returned.
     * Return the current IMSI if there is no subscription group.
     *
     * <p>Requires the calling app to have READ_PRIVILEGED_PHONE_STATE permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @NonNull String[] getMergedImsisFromGroup() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getMergedImsisFromGroup(getSubId(), getOpPackageName());
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return new String[0];
    }

    /**
     * Returns the MSISDN string for a GSM phone. Return null if it is unavailable.
     *
     * <p>Requires Permission:
     *     {@link android.Manifest.permission#READ_SMS READ_SMS},
     *     {@link android.Manifest.permission#READ_PHONE_NUMBERS READ_PHONE_NUMBERS},
     *     that the caller is the default SMS app,
     *     or that the caller has carrier privileges (see {@link #hasCarrierPrivileges})
     *     for any API level.
     *     {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *     for apps targeting SDK API level 29 and below.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_PHONE_NUMBERS
    })
    @UnsupportedAppUsage
    public String getMsisdn() {
        return getMsisdn(getSubId());
    }

    /**
     * Returns the MSISDN string for a GSM phone. Return null if it is unavailable.
     *
     * @param subId for which msisdn is returned
     *
     * <p>Requires Permission:
     *     {@link android.Manifest.permission#READ_SMS READ_SMS},
     *     {@link android.Manifest.permission#READ_PHONE_NUMBERS READ_PHONE_NUMBERS},
     *     that the caller is the default SMS app,
     *     or that the caller has carrier privileges (see {@link #hasCarrierPrivileges})
     *     for any API level.
     *     {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *     for apps targeting SDK API level 29 and below.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_PHONE_NUMBERS
    })
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getMsisdn(int subId) {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getMsisdnForSubscriber(subId, getOpPackageName(), getAttributionTag());
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
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getVoiceMailNumberForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
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
                return telephony.getVisualVoicemailPackageName(mContext.getOpPackageName(),
                        getAttributionTag(), getSubId());
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
     * @see TelecomManager#getDefaultDialerPackage()
     * @see CarrierConfigManager#KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY
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
                        mContext.getOpPackageName(), mContext.getAttributionTag(), subId, number,
                        port, text, sentIntent);
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
            return telephony.getVoiceMessageCountForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
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
            IPhoneSubInfo info = getSubscriberInfoService();
            if (info == null)
                return null;
            return info.getVoiceMailAlphaTagForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
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
            IPhoneSubInfo info = getSubscriberInfoService();
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
    @Nullable
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getIsimDomain() {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
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
    @Nullable
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String[] getIsimImpu() {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
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
        if (mContext != null) {
            TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
            if (telecomManager != null) {
                return telecomManager.getCallState();
            }
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
    * @hide
    */
    @UnsupportedAppUsage
    private IPhoneSubInfo getSubscriberInfo() {
        return getSubscriberInfoService();
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
            return telephony.getDataActivityForSubId(
                    getSubId(SubscriptionManager.getActiveDataSubscriptionId()));
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
            DATA_DISCONNECTING,
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
     * Data connection state: Disconnecting.
     *
     * IP traffic may be available but will cease working imminently.
     */
    public static final int DATA_DISCONNECTING = 4;

    /**
     * Used for checking if the SDK version for {@link TelephonyManager#getDataState} is above Q.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long GET_DATA_STATE_R_VERSION = 148534348L;

    /**
     * Returns a constant indicating the current data connection state
     * (cellular).
     *
     * @see #DATA_DISCONNECTED
     * @see #DATA_CONNECTING
     * @see #DATA_CONNECTED
     * @see #DATA_SUSPENDED
     * @see #DATA_DISCONNECTING
     */
    public int getDataState() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return DATA_DISCONNECTED;
            int state = telephony.getDataStateForSubId(
                    getSubId(SubscriptionManager.getActiveDataSubscriptionId()));
            if (state == TelephonyManager.DATA_DISCONNECTING
                    && !Compatibility.isChangeEnabled(GET_DATA_STATE_R_VERSION)) {
                return TelephonyManager.DATA_CONNECTED;
            }

            return state;
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return DATA_DISCONNECTED;
        } catch (NullPointerException ex) {
            return DATA_DISCONNECTED;
        }
    }

    /**
     * Convert data state to string
     *
     * @return The data state in string format.
     * @hide
     */
    public static String dataStateToString(@DataState int state) {
        switch (state) {
            case DATA_DISCONNECTED: return "DISCONNECTED";
            case DATA_CONNECTING: return "CONNECTING";
            case DATA_CONNECTED: return "CONNECTED";
            case DATA_SUSPENDED: return "SUSPENDED";
            case DATA_DISCONNECTING: return "DISCONNECTING";
        }
        return "UNKNOWN(" + state + ")";
    }

   /**
    * @hide
    */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private ITelephony getITelephony() {
        return ITelephony.Stub.asInterface(TelephonyFrameworkInitializer
                .getTelephonyServiceManager().getTelephonyServiceRegisterer().get());
    }

    private IOns getIOns() {
        return IOns.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getOpportunisticNetworkServiceRegisterer()
                        .get());
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
     * To register a listener, pass a {@link PhoneStateListener} and specify at least one telephony
     * state of interest in the events argument.
     *
     * At registration, and when a specified telephony state changes, the telephony manager invokes
     * the appropriate callback method on the listener object and passes the current (updated)
     * values.
     * <p>
     * To un-register a listener, pass the listener object and set the events argument to
     * {@link PhoneStateListener#LISTEN_NONE LISTEN_NONE} (0).
     *
     * If this TelephonyManager object has been created with {@link #createForSubscriptionId},
     * applies to the given subId. Otherwise, applies to
     * {@link SubscriptionManager#getDefaultSubscriptionId()}. To listen events for multiple subIds,
     * pass a separate listener object to each TelephonyManager object created with
     * {@link #createForSubscriptionId}.
     *
     * Note: if you call this method while in the middle of a binder transaction, you <b>must</b>
     * call {@link android.os.Binder#clearCallingIdentity()} before calling this method. A
     * {@link SecurityException} will be thrown otherwise.
     *
     * This API should be used sparingly -- large numbers of listeners will cause system
     * instability. If a process has registered too many listeners without unregistering them, it
     * may encounter an {@link IllegalStateException} when trying to register more listeners.
     *
     * @param listener The {@link PhoneStateListener} object to register
     *                 (or unregister)
     * @param events The telephony state(s) of interest to the listener,
     *               as a bitwise-OR combination of {@link PhoneStateListener}
     *               LISTEN_ flags.
     */
    public void listen(PhoneStateListener listener, int events) {
        if (mContext == null) return;
        boolean notifyNow = (getITelephony() != null);
        TelephonyRegistryManager telephonyRegistry =
                (TelephonyRegistryManager)
                        mContext.getSystemService(Context.TELEPHONY_REGISTRY_SERVICE);
        if (telephonyRegistry != null) {
            telephonyRegistry.listenForSubscriber(mSubId, getOpPackageName(), getAttributionTag(),
                    listener, events, notifyNow);
        } else {
            Rlog.w(TAG, "telephony registry not ready.");
        }
    }

    /**
     * Get the CDMA ERI (Enhanced Roaming Indicator) information
     *
     * Returns {@link android.telephony#CdmaEriInformation}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull
    public CdmaEriInformation getCdmaEriInformation() {
        return new CdmaEriInformation(
               getCdmaEriIconIndex(getSubId()), getCdmaEriIconMode(getSubId()));
    }

    /**
     * Returns the CDMA ERI icon index to display for a subscription
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @UnsupportedAppUsage
    public int getCdmaEriIconIndex(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return -1;
            return telephony.getCdmaEriIconIndexForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
        } catch (RemoteException ex) {
            // the phone process is restarting.
            return -1;
        } catch (NullPointerException ex) {
            return -1;
        }
    }

    /**
     * Returns the CDMA ERI icon mode for a subscription.
     * 0 - ON
     * 1 - FLASHING
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @UnsupportedAppUsage
    public int getCdmaEriIconMode(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null)
                return -1;
            return telephony.getCdmaEriIconModeForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
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
            return telephony.getCdmaEriTextForSubscriber(subId, getOpPackageName(),
                    getAttributionTag());
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
            return telephony.getAllCellInfo(getOpPackageName(), getAttributionTag());
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
     * <p>This method returns valid data for devices with
     * {@link android.content.pm.PackageManager#FEATURE_TELEPHONY FEATURE_TELEPHONY}. On devices
     * that do not implement this feature, the behavior is not reliable.
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
                        @Override
                        public void onCellInfo(List<CellInfo> cellInfo) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onCellInfo(cellInfo));
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        }

                        @Override
                        public void onError(int errorCode, String exceptionName, String message) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onError(
                                        errorCode,
                                        createThrowableByClassName(exceptionName, message)));
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        }
                    }, getOpPackageName(), getAttributionTag());
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
     * <p>This method returns valid data for devices with
     * {@link android.content.pm.PackageManager#FEATURE_TELEPHONY FEATURE_TELEPHONY}. On devices
     * that do not implement this feature, the behavior is not reliable.
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
                        @Override
                        public void onCellInfo(List<CellInfo> cellInfo) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onCellInfo(cellInfo));
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }

                        }

                        @Override
                        public void onError(int errorCode, String exceptionName, String message) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onError(
                                        errorCode,
                                        createThrowableByClassName(exceptionName, message)));
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        }
                    }, getOpPackageName(), getAttributionTag(), workSource);
        } catch (RemoteException ex) {
        }
    }

    private static Throwable createThrowableByClassName(String className, String message) {
        if (className == null) {
            return null;
        }
        try {
            Class<?> c = Class.forName(className);
            return (Throwable) c.getConstructor(String.class).newInstance(message);
        } catch (ReflectiveOperationException | ClassCastException e) {
        }
        return new RuntimeException(className + ": " + message);
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
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getMmsUserAgent(getSubId());
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Returns the MMS user agent profile URL.
     */
    public String getMmsUAProfUrl() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getMmsUAProfUrl(getSubId());
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
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
     * Opens a logical channel to the ICC card using the physical slot index.
     *
     * Use this method when no subscriptions are available on the SIM and the operation must be
     * performed using the physical slot index.
     *
     * This operation wraps two APDU instructions:
     * <ul>
     *     <li>MANAGE CHANNEL to open a logical channel</li>
     *     <li>SELECT the given {@code AID} using the given {@code p2}</li>
     * </ul>
     *
     * Per Open Mobile API Specification v3.2 section 6.2.7.h, only p2 values of 0x00, 0x04, 0x08,
     * and 0x0C are guaranteed to be supported.
     *
     * If the SELECT command's status word is not '9000', '62xx', or '63xx', the status word will be
     * considered an error and the channel shall not be opened.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHO command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param slotIndex the physical slot index of the ICC card
     * @param aid Application id. See ETSI 102.221 and 101.220.
     * @param p2 P2 parameter (described in ISO 7816-4).
     * @return an IccOpenLogicalChannelResponse object.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    @Nullable
    public IccOpenLogicalChannelResponse iccOpenLogicalChannelBySlot(int slotIndex,
            @Nullable String aid, int p2) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.iccOpenLogicalChannelBySlot(slotIndex, getOpPackageName(), aid,
                        p2);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * This operation wraps two APDU instructions:
     * <ul>
     *     <li>MANAGE CHANNEL to open a logical channel</li>
     *     <li>SELECT the given {@code AID} using the given {@code p2}</li>
     * </ul>
     *
     * Per Open Mobile API Specification v3.2 section 6.2.7.h, only p2 values of 0x00, 0x04, 0x08,
     * and 0x0C are guaranteed to be supported.
     *
     * If the SELECT command's status word is not '9000', '62xx', or '63xx', the status word will be
     * considered an error and the channel shall not be opened.
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openLogicalChannel(byte[], byte)}.
     */
    @Deprecated
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String AID, int p2) {
        return iccOpenLogicalChannel(getSubId(), AID, p2);
    }

    /**
     * Opens a logical channel to the ICC card.
     *
     * This operation wraps two APDU instructions:
     * <ul>
     *     <li>MANAGE CHANNEL to open a logical channel</li>
     *     <li>SELECT the given {@code AID} using the given {@code p2}</li>
     * </ul>
     *
     * Per Open Mobile API Specification v3.2 section 6.2.7.h, only p2 values of 0x00, 0x04, 0x08,
     * and 0x0C are guaranteed to be supported.
     *
     * If the SELECT command's status word is not '9000', '62xx', or '63xx', the status word will be
     * considered an error and the channel shall not be opened.
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openLogicalChannel(byte[], byte)}.
     */
    @Deprecated
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
     * Closes a previously opened logical channel to the ICC card using the physical slot index.
     *
     * Use this method when no subscriptions are available on the SIM and the operation must be
     * performed using the physical slot index.
     *
     * Input parameters equivalent to TS 27.007 AT+CCHC command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param slotIndex the physical slot index of the ICC card
     * @param channel is the channel id to be closed as returned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     * @hide
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.Channel#close()}.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public boolean iccCloseLogicalChannelBySlot(int slotIndex, int channel) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.iccCloseLogicalChannelBySlot(slotIndex, channel);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return false;
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
     * @param channel is the channel id to be closed as returned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.Channel#close()}.
     */
    @Deprecated
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
     * @param channel is the channel id to be closed as returned by a successful
     *            iccOpenLogicalChannel.
     * @return true if the channel was closed successfully.
     * @hide
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.Channel#close()}.
     */
    @Deprecated
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
     * Transmit an APDU to the ICC card over a logical channel using the physical slot index.
     *
     * Use this method when no subscriptions are available on the SIM and the operation must be
     * performed using the physical slot index.
     *
     * Input parameters equivalent to TS 27.007 AT+CGLA command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param slotIndex the physical slot index of the ICC card
     * @param channel is the channel id to be closed as returned by a successful
     *            iccOpenLogicalChannel.
     * @param cla Class of the APDU command.
     * @param instruction Instruction of the APDU command.
     * @param p1 P1 value of the APDU command.
     * @param p2 P2 value of the APDU command.
     * @param p3 P3 value of the APDU command. If p3 is negative a 4 byte APDU
     *            is sent to the SIM.
     * @param data Data to be sent with the APDU.
     * @return The APDU response from the ICC card with the status appended at the end, or null if
     * there is an issue connecting to the Telephony service.
     * @hide
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    @Nullable
    public String iccTransmitApduLogicalChannelBySlot(int slotIndex, int channel, int cla,
            int instruction, int p1, int p2, int p3, @Nullable String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.iccTransmitApduLogicalChannelBySlot(slotIndex, channel, cla,
                        instruction, p1, p2, p3, data);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * Transmit an APDU to the ICC card over the basic channel using the physical slot index.
     *
     * Use this method when no subscriptions are available on the SIM and the operation must be
     * performed using the physical slot index.
     *
     * Input parameters equivalent to TS 27.007 AT+CSIM command.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param slotIndex the physical slot index of the ICC card to target
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openBasicChannel(byte[], byte)},
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    @NonNull
    public String iccTransmitApduBasicChannelBySlot(int slotIndex, int cla, int instruction, int p1,
            int p2, int p3, @Nullable String data) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.iccTransmitApduBasicChannelBySlot(slotIndex, getOpPackageName(),
                        cla, instruction, p1, p2, p3, data);
            }
        } catch (RemoteException ex) {
        } catch (NullPointerException ex) {
        }
        return null;
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openBasicChannel(byte[], byte)},
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openBasicChannel(byte[], byte)},
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openBasicChannel(byte[], byte)},
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openBasicChannel(byte[], byte)},
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openBasicChannel(byte[], byte)},
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * @deprecated Use {@link android.se.omapi.SEService} APIs instead. See
     *             {@link android.se.omapi.SEService#getUiccReader(int)},
     *             {@link android.se.omapi.Reader#openSession()},
     *             {@link android.se.omapi.Session#openBasicChannel(byte[], byte)},
     *             {@link android.se.omapi.Channel#transmit(byte[])}.
     */
    @Deprecated
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
     * subscription ID is returned. Otherwise, the default subscription ID will be returned.
     *
     */
    public int getSubscriptionId() {
        return getSubId();
    }

    /**
     * Return an appropriate subscription ID for any situation.
     *
     * If this object has been created with {@link #createForSubscriptionId}, then the provided
     * subscription ID is returned. Otherwise, the default subscription ID will be returned.
     *
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
     * @param timeoutMillis The amount of time to wait for such a call, or the value of
     *                      {@link #getMaxNumberVerificationTimeoutMillis()}, whichever is lesser.
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
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() ->
                            callback.onCallReceived(phoneNumber));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            @Override
            public void onVerificationFailed(int reason) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() ->
                            callback.onVerificationFailed(reason));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
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
     * Inserts or updates a list property. Expands the list if its length is not enough.
     */
    private static <T> List<T> updateTelephonyProperty(List<T> prop, int phoneId, T value) {
        List<T> ret = new ArrayList<>(prop);
        while (ret.size() <= phoneId) ret.add(null);
        ret.set(phoneId, value);
        return ret;
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
     * Gets a per-phone telephony property from a property name.
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
     * Gets a typed per-phone telephony property from a schematized list property.
     */
    private static <T> T getTelephonyProperty(int phoneId, List<T> prop, T defaultValue) {
        T ret = null;
        if (phoneId >= 0 && phoneId < prop.size()) ret = prop.get(phoneId);
        return ret != null ? ret : defaultValue;
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
        return getPhoneCount();
    }

    /**
     * Returns the IMS Service Table (IST) that was loaded from the ISIM.
     *
     * See 3GPP TS 31.103 (Section 4.2.7) for the definition and more information on this table.
     *
     * @return IMS Service Table or null if not present or not loaded
     * @hide
     */
    @Nullable
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getIsimIst() {
        try {
            IPhoneSubInfo info = getSubscriberInfoService();
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
            IPhoneSubInfo info = getSubscriberInfoService();
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
            IPhoneSubInfo info = getSubscriberInfoService();
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
            return telephony.getForbiddenPlmns(subId, appType, mContext.getOpPackageName(),
                    getAttributionTag());
        } catch (RemoteException ex) {
            return null;
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            return null;
        }
    }

    /**
     * Replace the contents of the forbidden PLMN SIM file with the provided values.
     * Passing an empty list will clear the contents of the EFfplmn file.
     * If the provided list is shorter than the size of EFfplmn, then the list will be padded
     * up to the file size with 'FFFFFF'. (required by 3GPP TS 31.102 spec 4.2.16)
     * If the list is longer than the size of EFfplmn, then the file will be written from the
     * beginning of the list up to the file size.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE
     * MODIFY_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param fplmns a list of PLMNs to be forbidden.
     *
     * @return number of PLMNs that were successfully written to the SIM FPLMN list.
     * This may be less than the number of PLMNs passed in where the SIM file does not have enough
     * room for all of the values passed in. Return -1 in the event of an unexpected failure
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public int setForbiddenPlmns(@NonNull List<String> fplmns) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony == null) return -1;
            return telephony.setForbiddenPlmns(
                    getSubId(), APPTYPE_USIM, fplmns, getOpPackageName(), getAttributionTag());
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setForbiddenPlmns RemoteException: " + ex.getMessage());
        } catch (NullPointerException ex) {
            // This could happen before phone starts
            Rlog.e(TAG, "setForbiddenPlmns NullPointerException: " + ex.getMessage());
        }
        return -1;
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
            return telephony.getPcscfAddress(apnType, getOpPackageName(), getAttributionTag());
        } catch (RemoteException e) {
            return new String[0];
        }
    }


    /**
     * Resets the {@link android.telephony.ims.ImsService} associated with the specified sim slot.
     * Used by diagnostic apps to force the IMS stack to be disabled and re-enabled in an effort to
     * recover from scenarios where the {@link android.telephony.ims.ImsService} gets in to a bad
     * state.
     *
     * @param slotIndex the sim slot to reset the IMS stack on.
     * @hide */
    @SystemApi
    @WorkerThread
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void resetIms(int slotIndex) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.resetIms(slotIndex);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "toggleImsOnOff, RemoteException: "
                    + e.getMessage());
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
     * Unregister a IImsServiceFeatureCallback previously associated with an ImsFeature through
     * {@link #getImsMmTelFeatureAndListen(int, IImsServiceFeatureCallback)} or
     * {@link #getImsRcsFeatureAndListen(int, IImsServiceFeatureCallback)}.
     * @param slotIndex The SIM slot associated with the callback.
     * @param featureType The {@link android.telephony.ims.feature.ImsFeature.FeatureType}
     *                    associated with the callback.
     * @param callback The callback to be unregistered.
     * @hide
     */
    public void unregisterImsFeatureCallback(int slotIndex, int featureType,
            IImsServiceFeatureCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.unregisterImsFeatureCallback(slotIndex, featureType, callback);
            }
        } catch (RemoteException e) {
            Rlog.e(TAG, "unregisterImsFeatureCallback, RemoteException: "
                    + e.getMessage());
        }
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
     * The default preferred network mode constant.
     *
     * <p> This constant is used in case of nothing is set in
     * TelephonyProperties#default_network().
     *
     * @hide
     */
    public static final int DEFAULT_PREFERRED_NETWORK_MODE =
            RILConstants.PREFERRED_NETWORK_MODE;

    /**
     * Get the preferred network type.
     * Used for device configuration by some CDMA operators.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return the preferred network type.
     * @hide
     * @deprecated Use {@link #getPreferredNetworkTypeBitmask} instead.
     */
    @Deprecated
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
        }
        return -1;
    }

    /**
     * Get the preferred network type bitmask.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return The bitmask of preferred network types.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @SystemApi
    public @NetworkTypeBitMask long getPreferredNetworkTypeBitmask() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return (long) RadioAccessFamily.getRafFromNetworkType(
                        telephony.getPreferredNetworkType(getSubId()));
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPreferredNetworkTypeBitmask RemoteException", ex);
        }
        return 0;
    }

    /**
     * Get the allowed network types.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return the allowed network type bitmask
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @SystemApi
    public @NetworkTypeBitMask long getAllowedNetworkTypes() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getAllowedNetworkTypes(getSubId());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getAllowedNetworkTypes RemoteException", ex);
        }
        return -1;
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
                return telephony.getCellNetworkScanResults(getSubId(), getOpPackageName(),
                        getAttributionTag());
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
     * If the system-wide location switch is off, apps may still call this API, with the
     * following constraints:
     * <ol>
     *     <li>The app must hold the {@code android.permission.NETWORK_SCAN} permission.</li>
     *     <li>The app must not supply any specific bands or channels to scan.</li>
     *     <li>The app must only specify MCC/MNC pairs that are
     *     associated to a SIM in the device.</li>
     *     <li>Returned results will have no meaningful info other than signal strength
     *     and MCC/MNC info.</li>
     * </ol>
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
                getOpPackageName(), getAttributionTag());
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
     * @param operatorNumeric the PLMN ID of the network to select.
     * @param persistSelection whether the selection will persist until reboot.
     *         If true, only allows attaching to the selected PLMN until reboot; otherwise,
     *         attach to the chosen PLMN and resume normal network selection next time.
     * @param ran the initial suggested radio access network type.
     *         If registration fails, the RAN is not available after, the RAN is not within the
     *         network types specified by the preferred network types, or the value is
     *         {@link AccessNetworkConstants.AccessNetworkType#UNKNOWN}, modem will select
     *         the next best RAN for network registration.
     * @return {@code true} on success; {@code false} on any failure.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setNetworkSelectionModeManual(@NonNull String operatorNumeric,
            boolean persistSelection, @AccessNetworkConstants.RadioAccessNetworkType int ran) {
        return setNetworkSelectionModeManual(new OperatorInfo("" /* operatorAlphaLong */,
                "" /* operatorAlphaShort */, operatorNumeric, ran), persistSelection);
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
     *  <p>Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return the network selection mode.
     */
    @SuppressAutoDoc // No support for carrier privileges (b/72967236).
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PRECISE_PHONE_STATE
    })
    public @NetworkSelectionMode int getNetworkSelectionMode() {
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
     * Get the PLMN chosen for Manual Network Selection if active.
     * Return empty string if in automatic selection.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE} or that the calling app has carrier privileges
     * (see {@link #hasCarrierPrivileges})
     *
     * @return manually selected network info on success or empty string on failure
     */
    @SuppressAutoDoc // No support carrier privileges (b/72967236).
    @RequiresPermission(android.Manifest.permission.READ_PRECISE_PHONE_STATE)
    public @NonNull String getManualNetworkSelectionPlmn() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null && isManualNetworkSelectionAllowed()) {
                return telephony.getManualNetworkSelectionPlmn(getSubId());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getManualNetworkSelectionPlmn RemoteException", ex);
        }
        return "";
    }

    /**
     * Query Telephony to see if there has recently been an emergency SMS sent to the network by the
     * user and we are still within the time interval after the emergency SMS was sent that we are
     * considered in Emergency SMS mode.
     *
     * <p>This mode is used by other applications to allow them to perform special functionality,
     * such as allow the GNSS service to provide user location to the carrier network for emergency
     * when an emergency SMS is sent. This interval is set by
     * {@link CarrierConfigManager#KEY_EMERGENCY_SMS_MODE_TIMER_MS_INT}. If
     * the carrier does not support this mode, this function will always return false.
     *
     * @return {@code true} if this device is in emergency SMS mode, {@code false} otherwise.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isInEmergencySmsMode() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isInEmergencySmsMode();
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "isInEmergencySmsMode RemoteException", ex);
        }
        return false;
    }

    /**
     * Set the preferred network type.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param subId the id of the subscription to set the preferred network type for.
     * @param networkType the preferred network type
     * @return true on success; false on any failure.
     * @hide
     * @deprecated Use {@link #setPreferredNetworkTypeBitmask} instead.
     */
    @Deprecated
    @UnsupportedAppUsage
    public boolean setPreferredNetworkType(int subId, @PrefNetworkMode int networkType) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setPreferredNetworkType(subId, networkType);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredNetworkType RemoteException", ex);
        }
        return false;
    }

    /**
     * Set the preferred network type bitmask but if {@link #setAllowedNetworkTypes} has been set,
     * only the allowed network type will set to the modem.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the calling
     * app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @param networkTypeBitmask The bitmask of preferred network types.
     * @return true on success; false on any failure.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public boolean setPreferredNetworkTypeBitmask(@NetworkTypeBitMask long networkTypeBitmask) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setPreferredNetworkType(
                        getSubId(), RadioAccessFamily.getNetworkTypeFromRaf(
                                (int) networkTypeBitmask));
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredNetworkTypeBitmask RemoteException", ex);
        }
        return false;
    }

    /**
     * Set the allowed network types of the device. This is for carrier or privileged apps to
     * enable/disable certain network types on the device. The user preferred network types should
     * be set through {@link #setPreferredNetworkTypeBitmask}.
     *
     * @param allowedNetworkTypes The bitmask of allowed network types.
     * @return true on success; false on any failure.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public boolean setAllowedNetworkTypes(@NetworkTypeBitMask long allowedNetworkTypes) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setAllowedNetworkTypes(getSubId(), allowedNetworkTypes);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setAllowedNetworkTypes RemoteException", ex);
        }
        return false;
    }

    /** @hide */
    @IntDef({
            ALLOWED_NETWORK_TYPES_REASON_POWER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AllowedNetworkTypesReason{}

    /**
     * To indicate allowed network type change is requested by power manager.
     * Power Manger configuration won't affect the settings configured through
     * {@link setAllowedNetworkTypes} and will result in allowing network types that are in both
     * configurations (i.e intersection of both sets).
     * @hide
     */
    public static final int ALLOWED_NETWORK_TYPES_REASON_POWER = 0;

    /**
     * Set the allowed network types of the device and
     * provide the reason triggering the allowed network change.
     * This can be called for following reasons
     * <ol>
     * <li>Allowed network types control by power manager
     * {@link #ALLOWED_NETWORK_TYPES_REASON_POWER}
     * </ol>
     * This API will result in allowing an intersection of allowed network types for all reasons,
     * including the configuration done through {@link setAllowedNetworkTypes}.
     * While this API and {@link setAllowedNetworkTypes} is controlling allowed network types
     * on device, user preference will still be set through {@link #setPreferredNetworkTypeBitmask}.
     * Thus resultant network type configured on modem will be an intersection of the network types
     * from setAllowedNetworkTypesForReason, {@link setAllowedNetworkTypes}
     * and {@link #setPreferredNetworkTypeBitmask}.
     *
     * @param reason the reason the allowed network type change is taking place
     * @param allowedNetworkTypes The bitmask of allowed network types.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if invalid AllowedNetworkTypesReason is passed.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setAllowedNetworkTypesForReason(@AllowedNetworkTypesReason int reason,
            @NetworkTypeBitMask long allowedNetworkTypes) {
        if (reason != ALLOWED_NETWORK_TYPES_REASON_POWER) {
            throw new IllegalArgumentException("invalid AllowedNetworkTypesReason.");
        }
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setAllowedNetworkTypesForReason(getSubId(), reason,
                        allowedNetworkTypes);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setAllowedNetworkTypesForReason RemoteException", ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the allowed network types for certain reason.
     *
     * {@link #getAllowedNetworkTypesForReason} returns allowed network type for a
     * specific reason. For effective allowed network types configured on device,
     * query {@link getEffectiveAllowedNetworkTypes}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *s
     * @param reason the reason the allowed network type change is taking place
     * @return the allowed network type bitmask
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if invalid AllowedNetworkTypesReason is passed.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @NetworkTypeBitMask long getAllowedNetworkTypesForReason(
            @AllowedNetworkTypesReason int reason) {
        if (reason != ALLOWED_NETWORK_TYPES_REASON_POWER) {
            throw new IllegalArgumentException("invalid AllowedNetworkTypesReason.");
        }
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getAllowedNetworkTypesForReason(getSubId(), reason);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getAllowedNetworkTypesForReason RemoteException", ex);
            ex.rethrowFromSystemServer();
        }
        return -1;
    }

    /**
     * Get bit mask of all network types.
     *
     * @return bit mask of all network types
     * @hide
     */
    public static @NetworkTypeBitMask long getAllNetworkTypesBitmask() {
        return NETWORK_STANDARDS_FAMILY_BITMASK_3GPP | NETWORK_STANDARDS_FAMILY_BITMASK_3GPP2;
    }

    /**
     * Get the allowed network types configured on the device.
     * This API will return an intersection of allowed network types for all reasons,
     * including the configuration done through setAllowedNetworkTypes
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return the allowed network type bitmask
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @NetworkTypeBitMask long getEffectiveAllowedNetworkTypes() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getEffectiveAllowedNetworkTypes(getSubId());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getEffectiveAllowedNetworkTypes RemoteException", ex);
            ex.rethrowFromSystemServer();
        }
        return -1;
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
     * Check whether DUN APN is required for tethering.
     * <p>
     * Requires Permission: MODIFY_PHONE_STATE.
     *
     * @return {@code true} if DUN APN is required for tethering.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public boolean isTetheringApnRequired() {
        return isTetheringApnRequired(getSubId(SubscriptionManager.getActiveDataSubscriptionId()));
    }

    /**
     * Check whether DUN APN is required for tethering with subId.
     *
     * @param subId the id of the subscription to require tethering.
     * @return {@code true} if DUN APN is required for tethering.
     * @hide
     */
    public boolean isTetheringApnRequired(int subId) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isTetheringApnRequiredForSubscriber(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "hasMatchedTetherApnSetting RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "hasMatchedTetherApnSetting NPE", ex);
        }
        return false;
    }


    /**
     * Values used to return status for hasCarrierPrivileges call.
     */
    /** @hide */ @SystemApi @TestApi
    public static final int CARRIER_PRIVILEGE_STATUS_HAS_ACCESS = 1;
    /** @hide */ @SystemApi @TestApi
    public static final int CARRIER_PRIVILEGE_STATUS_NO_ACCESS = 0;
    /** @hide */ @SystemApi @TestApi
    public static final int CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED = -1;
    /** @hide */ @SystemApi @TestApi
    public static final int CARRIER_PRIVILEGE_STATUS_ERROR_LOADING_RULES = -2;

    /**
     * Has the calling application been granted carrier privileges by the carrier.
     *
     * If any of the packages in the calling UID has carrier privileges, the
     * call will return true. This access is granted by the owner of the UICC
     * card and does not depend on the registered carrier.
     *
     * Note that this API applies to both physical and embedded subscriptions and
     * is a superset of the checks done in SubscriptionManager#canManageSubscription.
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
     * Note that this API applies to both physical and embedded subscriptions and
     * is a superset of the checks done in SubscriptionManager#canManageSubscription.
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
    @TestApi
    @SuppressLint("Doclava125")
    public int checkCarrierPrivilegesForPackage(String pkgName) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.checkCarrierPrivilegesForPackage(getSubId(), pkgName);
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
    @TestApi
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
                return telephony.getPackagesWithCarrierPrivileges(getPhoneId());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getPackagesWithCarrierPrivileges RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getPackagesWithCarrierPrivileges NPE", ex);
        }
        return Collections.EMPTY_LIST;
    }

    /**
     * Get the names of packages with carrier privileges for all the active subscriptions.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull
    public List<String> getCarrierPrivilegedPackagesForAllActiveSubscriptions() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getPackagesWithCarrierPrivilegesForAllPhones();
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCarrierPrivilegedPackagesForAllActiveSubscriptions RemoteException",
                    ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCarrierPrivilegedPackagesForAllActiveSubscriptions NPE", ex);
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
                return telephony.isRadioOnWithFeature(getOpPackageName(), getAttributionTag());
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
                return telephony.supplyPinForSubscriber(getSubId(), pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPinForSubscriber", e);
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
                return telephony.supplyPukForSubscriber(getSubId(), puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPukForSubscriber", e);
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
                return telephony.supplyPinReportResultForSubscriber(getSubId(), pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPinReportResultForSubscriber", e);
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
                return telephony.supplyPukReportResultForSubscriber(getSubId(), puk, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#]", e);
        }
        return new int[0];
    }

    /**
     * Used when the user attempts to enter their pin.
     *
     * @param pin The user entered pin.
     * @return The result of the pin.
     *
     * @hide
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public PinResult supplyPinReportPinResult(@NonNull String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int[] result = telephony.supplyPinReportResultForSubscriber(getSubId(), pin);
                return new PinResult(result[0], result[1]);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#supplyPinReportResultForSubscriber", e);
        }
        return null;
    }

    /**
     * Used when the user attempts to enter the puk or their pin.
     *
     * @param puk The product unblocking key.
     * @param pin The user entered pin.
     * @return The result of the pin.
     *
     * @hide
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public PinResult supplyPukReportPinResult(@NonNull String puk, @NonNull String pin) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int[] result = telephony.supplyPukReportResultForSubscriber(getSubId(), puk, pin);
                return new PinResult(result[0], result[1]);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#]", e);
        }
        return null;
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

    /**
     * Shut down all the live radios over all the slot indexes.
     *
     * <p>To know when the radio has completed powering off, use
     * {@link PhoneStateListener#LISTEN_SERVICE_STATE LISTEN_SERVICE_STATE}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void shutdownAllRadios() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.shutdownMobileRadios();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#shutdownAllRadios", e);
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Check if any radio is on over all the slot indexes.
     *
     * @return {@code true} if any radio is on over any slot index.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isAnyRadioPoweredOn() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.needMobileRadioShutdown();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#isAnyRadioPoweredOn", e);
            e.rethrowAsRuntimeException();
        }
        return false;
    }

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
                return telephony.getRadioPowerState(getSlotIndex(), mContext.getOpPackageName(),
                        mContext.getAttributionTag());
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
                        .getActiveDataSubscriptionId()));
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
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @CdmaRoamingMode int getCdmaRoamingMode() {
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
    public boolean setCdmaRoamingMode(@CdmaRoamingMode int mode) {
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

    /** @hide */
    @IntDef(flag = true, prefix = { "CDMA_SUBSCRIPTION_" }, value = {
            CDMA_SUBSCRIPTION_UNKNOWN,
            CDMA_SUBSCRIPTION_RUIM_SIM,
            CDMA_SUBSCRIPTION_NV
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CdmaSubscription{}

    /** Used for CDMA subscription mode, it'll be UNKNOWN if there is no Subscription source.
     * @hide
     */
    public static final int CDMA_SUBSCRIPTION_UNKNOWN  = -1;

    /** Used for CDMA subscription mode: RUIM/SIM (default)
     * @hide
     */
    public static final int CDMA_SUBSCRIPTION_RUIM_SIM = 0;

    /** Used for CDMA subscription mode: NV -> non-volatile memory
     * @hide
     */
    public static final int CDMA_SUBSCRIPTION_NV       = 1;

    /** @hide */
    public static final int PREFERRED_CDMA_SUBSCRIPTION = CDMA_SUBSCRIPTION_RUIM_SIM;

    /**
     * Sets the subscription mode for CDMA phone to the given mode {@code mode}.
     *
     * @param mode CDMA subscription mode
     *
     * @return {@code true} if successed.
     *
     * @see #CDMA_SUBSCRIPTION_UNKNOWN
     * @see #CDMA_SUBSCRIPTION_RUIM_SIM
     * @see #CDMA_SUBSCRIPTION_NV
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setCdmaSubscriptionMode(@CdmaSubscription int mode) {
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
        } catch (RemoteException | NullPointerException e) {
            Log.e(TAG, "Error calling ITelephony#isUserDataEnabled", e);
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

    /**
     * @deprecated Use {@link android.telephony.ims.ImsMmTelManager#setVtSettingEnabled(boolean)}
     * instead.
     * @hide
     */
    @Deprecated
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

    /**
     * @deprecated Use {@link ImsMmTelManager#isVtSettingEnabled()} instead to check if the user
     * has enabled the Video Calling setting, {@link ImsMmTelManager#isAvailable(int, int)} to
     * determine if video calling is available, or {@link ImsMmTelManager#isCapable(int, int)} to
     * determine if video calling is capable.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_STATE
    })
    public boolean isVideoCallingEnabled() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null)
                return telephony.isVideoCallingEnabled(getOpPackageName(), getAttributionTag());
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
                return telephony.canChangeDtmfToneLength(mSubId, getOpPackageName(),
                        getAttributionTag());
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
                return telephony.isWorldPhone(mSubId, getOpPackageName(), getAttributionTag());
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
            TelecomManager telecomManager = null;
            if (mContext != null) {
                telecomManager = mContext.getSystemService(TelecomManager.class);
            }
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
     * Use {@link ImsMmTelManager.RegistrationCallback} instead.
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
     * <p>
     * Use {@link ImsMmTelManager#isAvailable(int, int)} instead.
     * @hide
     */
    @UnsupportedAppUsage
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
     * Use {@link ImsMmTelManager#isAvailable(int, int)} instead.
     * @hide
     */
    @UnsupportedAppUsage
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
     * Use {@link ImsMmTelManager#isAvailable(int, int)} instead.
     * @hide
     */
    @UnsupportedAppUsage
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
     *  Use {@link ImsMmTelManager.RegistrationCallback} instead.
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
    * Set TelephonyProperties.icc_operator_numeric for the default phone.
    *
    * @hide
    */
    public void setSimOperatorNumeric(String numeric) {
        int phoneId = getPhoneId();
        setSimOperatorNumericForPhone(phoneId, numeric);
    }

   /**
    * Set TelephonyProperties.icc_operator_numeric for the given phone.
    *
    * @hide
    */
    @UnsupportedAppUsage
    public void setSimOperatorNumericForPhone(int phoneId, String numeric) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.icc_operator_numeric(), phoneId, numeric);
            TelephonyProperties.icc_operator_numeric(newList);
        }
    }

    /**
     * Set TelephonyProperties.icc_operator_alpha for the default phone.
     *
     * @hide
     */
    public void setSimOperatorName(String name) {
        int phoneId = getPhoneId();
        setSimOperatorNameForPhone(phoneId, name);
    }

    /**
     * Set TelephonyProperties.icc_operator_alpha for the given phone.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setSimOperatorNameForPhone(int phoneId, String name) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.icc_operator_alpha(), phoneId, name);
            TelephonyProperties.icc_operator_alpha(newList);
        }
    }

   /**
    * Set TelephonyProperties.icc_operator_iso_country for the default phone.
    *
    * @hide
    */
    public void setSimCountryIso(String iso) {
        int phoneId = getPhoneId();
        setSimCountryIsoForPhone(phoneId, iso);
    }

   /**
    * Set TelephonyProperties.icc_operator_iso_country for the given phone.
    *
    * @hide
    */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setSimCountryIsoForPhone(int phoneId, String iso) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.icc_operator_iso_country(), phoneId, iso);
            TelephonyProperties.icc_operator_iso_country(newList);
        }
    }

    /**
     * Set TelephonyProperties.sim_state for the default phone.
     *
     * @hide
     */
    public void setSimState(String state) {
        int phoneId = getPhoneId();
        setSimStateForPhone(phoneId, state);
    }

    /**
     * Set TelephonyProperties.sim_state for the given phone.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setSimStateForPhone(int phoneId, String state) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.sim_state(), phoneId, state);
            TelephonyProperties.sim_state(newList);
        }
    }

    /**
     * Powers down the SIM. SIM must be up prior.
     * @hide
     */
    public static final int CARD_POWER_DOWN = 0;

    /**
     * Powers up the SIM normally. SIM must be down prior.
     * @hide
     */
    public static final int CARD_POWER_UP = 1;

    /**
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
     * @hide
     */
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
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.baseband_version(), phoneId, version);
            TelephonyProperties.baseband_version(newList);
        }
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
     * Get baseband version by phone id.
     *
     * @return baseband version.
     * @hide
     */
    public String getBasebandVersionForPhone(int phoneId) {
        return getTelephonyProperty(phoneId, TelephonyProperties.baseband_version(), "");
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
            List<Integer> newList = updateTelephonyProperty(
                    TelephonyProperties.current_active_phone(), phoneId, type);
            TelephonyProperties.current_active_phone(newList);
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
            return getTelephonyProperty(
                    phoneId, TelephonyProperties.otasp_num_schema(), defaultValue);
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
            return getTelephonyProperty(phoneId, TelephonyProperties.sms_receive(), defaultValue);
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
            return getTelephonyProperty(phoneId, TelephonyProperties.sms_send(), defaultValue);
        }

        return defaultValue;
    }

    /**
     * Gets the default Respond Via Message application, updating the cache if there is no
     * respond-via-message application currently configured.
     * @return component name of the app and class to direct Respond Via Message intent to, or
     * {@code null} if the functionality is not supported.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    public @Nullable ComponentName getAndUpdateDefaultRespondViaMessageApplication() {
        return SmsApplication.getDefaultRespondViaMessageApplication(mContext, true);
    }

    /**
     * Gets the default Respond Via Message application.
     * @return component name of the app and class to direct Respond Via Message intent to, or
     * {@code null} if the functionality is not supported.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    public @Nullable ComponentName getDefaultRespondViaMessageApplication() {
        return SmsApplication.getDefaultRespondViaMessageApplication(mContext, false);
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
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.operator_alpha(), phoneId, name);
            TelephonyProperties.operator_alpha(newList);
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
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.operator_numeric(), phoneId, numeric);
            TelephonyProperties.operator_numeric(newList);
        }
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
            List<Boolean> newList = updateTelephonyProperty(
                    TelephonyProperties.operator_is_roaming(), phoneId, isRoaming);
            TelephonyProperties.operator_is_roaming(newList);
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
            List<String> newList = updateTelephonyProperty(
                    TelephonyProperties.data_network_type(), phoneId,
                    ServiceState.rilRadioTechnologyToString(type));
            TelephonyProperties.data_network_type(newList);
        }
    }

    /**
     * Returns the subscription ID for the given phone account.
     * @hide
     */
    @UnsupportedAppUsage
    public int getSubIdForPhoneAccount(@Nullable PhoneAccount phoneAccount) {
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
     * Determines the {@link PhoneAccountHandle} associated with a subscription Id.
     *
     * @param subscriptionId The subscription Id to check.
     * @return The {@link PhoneAccountHandle} associated with a subscription Id, or {@code null} if
     * there is no associated {@link PhoneAccountHandle}.
     * @hide
     */
    public @Nullable PhoneAccountHandle getPhoneAccountHandleForSubscriptionId(int subscriptionId) {
        PhoneAccountHandle returnValue = null;
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                returnValue = service.getPhoneAccountHandleForSubscriptionId(subscriptionId);
            }
        } catch (RemoteException e) {
        }

        return returnValue;
    }

    /**
     * Returns the subscription ID for the given phone account handle.
     *
     * @param phoneAccountHandle the phone account handle for outgoing calls
     * @return subscription ID for the given phone account handle; or
     *         {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}
     *         if not available; or throw a SecurityException if the caller doesn't have the
     *         permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getSubscriptionId(@NonNull PhoneAccountHandle phoneAccountHandle) {
        int retval = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                retval = service.getSubIdForPhoneAccountHandle(
                        phoneAccountHandle, mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "getSubscriptionId RemoteException", ex);
            ex.rethrowAsRuntimeException();
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
            if (telephony != null) {
                telephony.factoryReset(subId);
            }
        } catch (RemoteException e) {
        }
    }


    /**
     * Resets Telephony and IMS settings back to factory defaults only for the subscription
     * associated with this instance.
     * @see #createForSubscriptionId(int)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONNECTIVITY_INTERNAL)
    public void resetSettings() {
        try {
            Log.d(TAG, "resetSettings: subId=" + getSubId());
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.factoryReset(getSubId());
            }
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
     * {@link android.telephony.ModemActivityInfo} object with key of
     * {@link #MODEM_ACTIVITY_RESULT_KEY}.
     * @hide
     */
    public void requestModemActivityInfo(@NonNull ResultReceiver result) {
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
                return service.getServiceStateForSubscriber(subId, getOpPackageName(),
                        getAttributionTag());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#getServiceStateForSubscriber", e);
        } catch (NullPointerException e) {
            AnomalyReporter.reportAnomaly(
                    UUID.fromString("a3ab0b9d-f2aa-4baf-911d-7096c0d4645a"),
                    "getServiceStateForSubscriber " + subId + " NPE");
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
     * <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/latest_carrier_id/carrier_list.textpb">here</a>
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
     * Returns fine-grained carrier ID of the current subscription.
     *
     * A specific carrier ID can represent the fact that a carrier may be in effect an aggregation
     * of other carriers (ie in an MVNO type scenario) where each of these specific carriers which
     * are used to make up the actual carrier service may have different carrier configurations.
     * A specific carrier ID could also be used, for example, in a scenario where a carrier requires
     * different carrier configuration for different service offering such as a prepaid plan.
     *
     * the specific carrier ID would be used for configuration purposes, but apps wishing to know
     * about the carrier itself should use the regular carrier ID returned by
     * {@link #getSimCarrierId()}.
     *
     * e.g, Tracfone SIMs could return different specific carrier ID based on IMSI from current
     * subscription while carrier ID remains the same.
     *
     * <p>For carriers without fine-grained specific carrier ids, return {@link #getSimCarrierId()}
     * <p>Specific carrier ids are defined in the same way as carrier id
     * <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/latest_carrier_id/carrier_list.textpb">here</a>
     * except each with a "parent" id linking to its top-level carrier id.
     *
     * @return Returns fine-grained carrier id of the current subscription.
     * Return {@link #UNKNOWN_CARRIER_ID} if the subscription is unavailable or the carrier cannot
     * be identified.
     */
    public int getSimSpecificCarrierId() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionSpecificCarrierId(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
        return UNKNOWN_CARRIER_ID;
    }

    /**
     * Similar like {@link #getSimCarrierIdName()}, returns user-facing name of the
     * specific carrier id returned by {@link #getSimSpecificCarrierId()}.
     *
     * The specific carrier ID would be used for configuration purposes, but apps wishing to know
     * about the carrier itself should use the regular carrier ID returned by
     * {@link #getSimCarrierIdName()}.
     *
     * <p>The returned name is unlocalized.
     *
     * @return user-facing name of the subscription specific carrier id. Return {@code null} if the
     * subscription is unavailable or the carrier cannot be identified.
     */
    public @Nullable CharSequence getSimSpecificCarrierIdName() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.getSubscriptionSpecificCarrierName(getSubId());
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
     * Android carrier id table <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/latest_carrier_id/carrier_list.textpb">here</a>
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
      * Android carrier id table <a href="https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/master/assets/latest_carrier_id/carrier_list.textpb">here</a>
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
    @Nullable
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
     * @see #resetAllCarrierActions()
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
     * Carrier action to enable or disable the radio.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param enabled control enable or disable radio.
     * @see #resetAllCarrierActions()
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setRadioEnabled(boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionSetRadioEnabled(
                        getSubId(SubscriptionManager.getDefaultDataSubscriptionId()), enabled);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionSetRadioEnabled", e);
        }
    }

    /**
     * Carrier action to start or stop reporting default network available events.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param report control start/stop reporting network status.
     * @see #resetAllCarrierActions()
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void reportDefaultNetworkStatus(boolean report) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionReportDefaultNetworkStatus(
                        getSubId(SubscriptionManager.getDefaultDataSubscriptionId()), report);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionReportDefaultNetworkStatus", e);
        }
    }

    /**
     * Reset all carrier actions previously set by {@link #setRadioEnabled},
     * {@link #reportDefaultNetworkStatus} and {@link #setCarrierDataEnabled}.
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void resetAllCarrierActions() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.carrierActionResetAll(
                        getSubId(SubscriptionManager.getDefaultDataSubscriptionId()));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelephony#carrierActionResetAll", e);
        }
    }

    /**
     * Policy control of data connection. Usually used when data limit is passed.
     * @param enabled True if enabling the data, otherwise disabling.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setPolicyDataEnabled(boolean enabled) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setPolicyDataEnabled(enabled, getSubId());
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
                return service.getClientRequestStats(getOpPackageName(), getAttributionTag(),
                        subId);
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
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE} or that the calling app has carrier privileges
     * (see {@link #hasCarrierPrivileges})
     *
     * <p>If this object has been created with {@link #createForSubscriptionId}, applies to the
     * given subId. Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}.
     *
     * @return {@code true} if manual network selection is allowed, otherwise return {@code false}.
     */
    @SuppressAutoDoc // No support carrier privileges (b/72967236).
    @RequiresPermission(anyOf = {android.Manifest.permission.READ_PRECISE_PHONE_STATE,
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE})
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
     * Enable or disable signal strength changes from radio will always be reported in any
     * condition (e.g. screen is off). This is only allowed for System caller.
     *
     * @param isEnabled {@code true} for enabling; {@code false} for disabling.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setAlwaysReportSignalStrength(boolean isEnabled) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setAlwaysReportSignalStrength(getSubId(), isEnabled);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "setAlwaysReportSignalStrength RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
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
     * Checks whether cellular data connection is allowed in the device.
     *
     * <p>Whether cellular data connection is allowed considers all factors below:
     * <UL>
     *   <LI>User turned on data setting {@link #isDataEnabled}.</LI>
     *   <LI>Carrier allows data to be on.</LI>
     *   <LI>Network policy.</LI>
     *   <LI>And possibly others.</LI>
     * </UL>
     * @return {@code true} if the overall data connection is allowed; {@code false} if not.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isDataConnectionAllowed() {
        boolean retVal = false;
        try {
            int subId = getSubId(SubscriptionManager.getDefaultDataSubscriptionId());
            ITelephony telephony = getITelephony();
            if (telephony != null)
                retVal = telephony.isDataEnabled(subId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error isDataConnectionAllowed", e);
        }
        return retVal;
    }

    /**
     * @return true if the current device is "data capable" over a radio on the device.
     * <p>
     * "Data capable" means that this device supports packet-switched
     * data connections over the telephony network.
     * <p>
     * Note: the meaning of this flag is subtly different from the
     * PackageManager.FEATURE_TELEPHONY system feature, which is available
     * on any device with a telephony radio, even if the device is
     * voice-only.
     *
     * @hide
     */
    public boolean isDataCapable() {
        if (mContext == null) return true;
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_mobile_data_capable);
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

    /**
     * A test API to override carrier information including mccmnc, imsi, iccid, gid1, gid2,
     * plmn and spn. This would be handy for, eg, forcing a particular carrier id, carrier's config
     * (also any country or carrier overlays) to be loaded when using a test SIM with a call box.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     *
     * @deprecated
     * @hide
     */
    @Deprecated
    @TestApi
    public void setCarrierTestOverride(String mccmnc, String imsi, String iccid, String gid1,
            String gid2, String plmn, String spn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setCarrierTestOverride(
                        getSubId(), mccmnc, imsi, iccid, gid1, gid2, plmn, spn,
                        null, null);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
        }
    }

    /**
     * A test API to override carrier information including mccmnc, imsi, iccid, gid1, gid2,
     * plmn, spn, apn and carrier priviledge. This would be handy for, eg, forcing a particular
     * carrier id, carrier's config (also any country or carrier overlays) to be loaded when using
     * a test SIM with a call box.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @hide
     */
    @TestApi
    public void setCarrierTestOverride(String mccmnc, String imsi, String iccid, String gid1,
                                       String gid2, String plmn, String spn,
                                       String carrierPriviledgeRules, String apn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setCarrierTestOverride(
                        getSubId(), mccmnc, imsi, iccid, gid1, gid2, plmn, spn,
                        carrierPriviledgeRules, apn);
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
    @UnsupportedAppUsage
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
                        getSubId(), getOpPackageName(), getAttributionTag());
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
    @SystemApi
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
    @SystemApi
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

    /** @hide */
    public static final long NETWORK_CLASS_BITMASK_2G = NETWORK_TYPE_BITMASK_GSM
                | NETWORK_TYPE_BITMASK_GPRS
                | NETWORK_TYPE_BITMASK_EDGE
                | NETWORK_TYPE_BITMASK_CDMA
                | NETWORK_TYPE_BITMASK_1xRTT;

    /** @hide */
    public static final long NETWORK_CLASS_BITMASK_3G = NETWORK_TYPE_BITMASK_EVDO_0
            | NETWORK_TYPE_BITMASK_EVDO_A
            | NETWORK_TYPE_BITMASK_EVDO_B
            | NETWORK_TYPE_BITMASK_EHRPD
            | NETWORK_TYPE_BITMASK_HSUPA
            | NETWORK_TYPE_BITMASK_HSDPA
            | NETWORK_TYPE_BITMASK_HSPA
            | NETWORK_TYPE_BITMASK_HSPAP
            | NETWORK_TYPE_BITMASK_UMTS
            | NETWORK_TYPE_BITMASK_TD_SCDMA;

    /** @hide */
    public static final long NETWORK_CLASS_BITMASK_4G = NETWORK_TYPE_BITMASK_LTE
            | NETWORK_TYPE_BITMASK_LTE_CA
            | NETWORK_TYPE_BITMASK_IWLAN;

    /** @hide */
    public static final long NETWORK_CLASS_BITMASK_5G = NETWORK_TYPE_BITMASK_NR;

    /** @hide */
    public static final long NETWORK_STANDARDS_FAMILY_BITMASK_3GPP = NETWORK_TYPE_BITMASK_GSM
            | NETWORK_TYPE_BITMASK_GPRS
            | NETWORK_TYPE_BITMASK_EDGE
            | NETWORK_TYPE_BITMASK_HSUPA
            | NETWORK_TYPE_BITMASK_HSDPA
            | NETWORK_TYPE_BITMASK_HSPA
            | NETWORK_TYPE_BITMASK_HSPAP
            | NETWORK_TYPE_BITMASK_UMTS
            | NETWORK_TYPE_BITMASK_TD_SCDMA
            | NETWORK_TYPE_BITMASK_LTE
            | NETWORK_TYPE_BITMASK_LTE_CA
            | NETWORK_TYPE_BITMASK_NR;

    /** @hide */
    public static final long NETWORK_STANDARDS_FAMILY_BITMASK_3GPP2 = NETWORK_TYPE_BITMASK_CDMA
            | NETWORK_TYPE_BITMASK_1xRTT
            | NETWORK_TYPE_BITMASK_EVDO_0
            | NETWORK_TYPE_BITMASK_EVDO_A
            | NETWORK_TYPE_BITMASK_EVDO_B
            | NETWORK_TYPE_BITMASK_EHRPD;

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
     * Indicates Emergency number database version is invalid.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int INVALID_EMERGENCY_NUMBER_DB_VERSION = -1;

    /**
     * Notify Telephony for OTA emergency number database installation complete.
     *
     * <p> Requires permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    public void notifyOtaEmergencyNumberDbInstalled() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.notifyOtaEmergencyNumberDbInstalled();
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "notifyOtaEmergencyNumberDatabaseInstalled RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Override the file path for OTA emergency number database in a file partition.
     *
     * @param otaParcelFileDescriptor parcelable file descriptor for OTA emergency number database.
     *
     * <p> Requires permission:
     * {@link android.Manifest.permission#READ_ACTIVE_EMERGENCY_SESSION}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
    @SystemApi
    @TestApi
    public void updateOtaEmergencyNumberDbFilePath(
            @NonNull ParcelFileDescriptor otaParcelFileDescriptor) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.updateOtaEmergencyNumberDbFilePath(otaParcelFileDescriptor);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "updateOtaEmergencyNumberDbFilePath RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Reset the file path to default for OTA emergency number database in a file partition.
     *
     * <p> Requires permission:
     * {@link android.Manifest.permission#READ_ACTIVE_EMERGENCY_SESSION}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
    @SystemApi
    @TestApi
    public void resetOtaEmergencyNumberDbFilePath() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.resetOtaEmergencyNumberDbFilePath();
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "resetOtaEmergencyNumberDbFilePath RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns whether {@link TelephonyManager#ACTION_EMERGENCY_ASSISTANCE emergency assistance} is
     * available on the device.
     * <p>
     * Requires permission: {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
     * @return {@code true} if emergency assistance is available, {@code false} otherwise
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @SystemApi
    public boolean isEmergencyAssistanceEnabled() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                "isEmergencyAssistanceEnabled");
        return EMERGENCY_ASSISTANCE_ENABLED;
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
     * @return Map including the keys as the active subscription IDs (Note: if there is no active
     * subscription, the key is {@link SubscriptionManager#getDefaultSubscriptionId}) and the value
     * as the list of {@link EmergencyNumber}; empty Map if this information is not available;
     * or throw a SecurityException if the caller does not have the permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @NonNull
    public Map<Integer, List<EmergencyNumber>> getEmergencyNumberList() {
        Map<Integer, List<EmergencyNumber>> emergencyNumberList = new HashMap<>();
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getEmergencyNumberList(mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "getEmergencyNumberList RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
        return emergencyNumberList;
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
     * @return Map including the keys as the active subscription IDs (Note: if there is no active
     * subscription, the key is {@link SubscriptionManager#getDefaultSubscriptionId}) and the value
     * as the list of {@link EmergencyNumber}; empty Map if this information is not available;
     * or throw a SecurityException if the caller does not have the permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @NonNull
    public Map<Integer, List<EmergencyNumber>> getEmergencyNumberList(
            @EmergencyServiceCategories int categories) {
        Map<Integer, List<EmergencyNumber>> emergencyNumberList = new HashMap<>();
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                emergencyNumberList = telephony.getEmergencyNumberList(
                        mContext.getOpPackageName(), mContext.getAttributionTag());
                if (emergencyNumberList != null) {
                    for (Integer subscriptionId : emergencyNumberList.keySet()) {
                        List<EmergencyNumber> numberList = emergencyNumberList.get(subscriptionId);
                        for (EmergencyNumber number : numberList) {
                            if (!number.isInEmergencyServiceCategories(categories)) {
                                numberList.remove(number);
                            }
                        }
                    }
                }
                return emergencyNumberList;
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "getEmergencyNumberList with Categories RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
        return emergencyNumberList;
    }

    /**
     * Identifies if the supplied phone number is an emergency number that matches a known
     * emergency number based on current locale, SIM card(s), Android database, modem, network,
     * or defaults.
     *
     * <p>This method assumes that only dialable phone numbers are passed in; non-dialable
     * numbers are not considered emergency numbers. A dialable phone number consists only
     * of characters/digits identified by {@link PhoneNumberUtils#isDialable(char)}.
     *
     * <p>The subscriptions which the identification would be based on, are all the active
     * subscriptions, no matter which subscription could be used to create TelephonyManager.
     *
     * @param number - the number to look up
     * @return {@code true} if the given number is an emergency number based on current locale,
     * SIM card(s), Android database, modem, network or defaults; {@code false} otherwise.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    public boolean isEmergencyNumber(@NonNull String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isEmergencyNumber(number, true);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "isEmergencyNumber RemoteException", ex);
            ex.rethrowAsRuntimeException();
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
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isPotentialEmergencyNumber(@NonNull String number) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isEmergencyNumber(number, false);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "isEmergencyNumber RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
        return false;
    }

    /**
     * Returns the emergency number database version.
     *
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE READ_PRIVILEGED_PHONE_STATE}
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getEmergencyNumberDbVersion() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getEmergencyNumberDbVersion(getSubId());
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "getEmergencyNumberDbVersion RemoteException", ex);
            ex.rethrowAsRuntimeException();
        }
        return INVALID_EMERGENCY_NUMBER_DB_VERSION;
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SET_OPPORTUNISTIC_SUB"}, value = {
            SET_OPPORTUNISTIC_SUB_SUCCESS,
            SET_OPPORTUNISTIC_SUB_VALIDATION_FAILED,
            SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION,
            SET_OPPORTUNISTIC_SUB_NO_OPPORTUNISTIC_SUB_AVAILABLE,
            SET_OPPORTUNISTIC_SUB_REMOTE_SERVICE_EXCEPTION})
    public @interface SetOpportunisticSubscriptionResult {}

    /**
     * No error. Operation succeeded.
     */
    public static final int SET_OPPORTUNISTIC_SUB_SUCCESS = 0;

    /**
     * Validation failed when trying to switch to preferred subscription.
     */
    public static final int SET_OPPORTUNISTIC_SUB_VALIDATION_FAILED = 1;

    /**
     * The subscription is not valid. It must be an active opportunistic subscription.
     */
    public static final int SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION = 2;

    /**
     * The subscription is not valid. It must be an opportunistic subscription.
     */
    public static final int SET_OPPORTUNISTIC_SUB_NO_OPPORTUNISTIC_SUB_AVAILABLE = 3;

    /**
     * Subscription service happened remote exception.
     */
    public static final int SET_OPPORTUNISTIC_SUB_REMOTE_SERVICE_EXCEPTION = 4;


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"UPDATE_AVAILABLE_NETWORKS"}, value = {
            UPDATE_AVAILABLE_NETWORKS_SUCCESS,
            UPDATE_AVAILABLE_NETWORKS_UNKNOWN_FAILURE,
            UPDATE_AVAILABLE_NETWORKS_ABORTED,
            UPDATE_AVAILABLE_NETWORKS_INVALID_ARGUMENTS,
            UPDATE_AVAILABLE_NETWORKS_NO_CARRIER_PRIVILEGE,
            UPDATE_AVAILABLE_NETWORKS_DISABLE_MODEM_FAIL,
            UPDATE_AVAILABLE_NETWORKS_ENABLE_MODEM_FAIL,
            UPDATE_AVAILABLE_NETWORKS_MULTIPLE_NETWORKS_NOT_SUPPORTED,
            UPDATE_AVAILABLE_NETWORKS_NO_OPPORTUNISTIC_SUB_AVAILABLE,
            UPDATE_AVAILABLE_NETWORKS_REMOTE_SERVICE_EXCEPTION,
            UPDATE_AVAILABLE_NETWORKS_SERVICE_IS_DISABLED})
    public @interface UpdateAvailableNetworksResult {}

    /**
     * No error. Operation succeeded.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_SUCCESS = 0;

    /**
     * There is a unknown failure happened.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_UNKNOWN_FAILURE = 1;

    /**
     * The request is aborted.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_ABORTED = 2;

    /**
     * The parameter passed in is invalid.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_INVALID_ARGUMENTS = 3;

    /**
     * No carrier privilege.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_NO_CARRIER_PRIVILEGE = 4;

    /**
     * Disable modem fail.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_DISABLE_MODEM_FAIL = 5;

    /**
     * Enable modem fail.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_ENABLE_MODEM_FAIL = 6;

    /**
     * Carrier app does not support multiple available networks.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_MULTIPLE_NETWORKS_NOT_SUPPORTED = 7;

    /**
     * The subscription is not valid. It must be an opportunistic subscription.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_NO_OPPORTUNISTIC_SUB_AVAILABLE = 8;

    /**
     * There is no OpportunisticNetworkService.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_REMOTE_SERVICE_EXCEPTION = 9;

    /**
     * OpportunisticNetworkService is disabled.
     */
    public static final int UPDATE_AVAILABLE_NETWORKS_SERVICE_IS_DISABLED = 10;

    /**
     * Set preferred opportunistic data subscription id.
     *
     * Switch internet data to preferred opportunistic data subscription id. This api
     * can result in lose of internet connectivity for short period of time while internet data
     * is handed over.
     * <p>Requires that the calling app has carrier privileges on both primary and
     * secondary subscriptions (see
     * {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param subId which opportunistic subscription
     * {@link SubscriptionManager#getOpportunisticSubscriptions} is preferred for cellular data.
     * Pass {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID} to unset the preference
     * @param needValidation whether validation is needed before switch happens.
     * @param executor The executor of where the callback will execute.
     * @param callback Callback will be triggered once it succeeds or failed.
     *                 See {@link TelephonyManager.SetOpportunisticSubscriptionResult}
     *                 for more details. Pass null if don't care about the result.
     */
    public void setPreferredOpportunisticDataSubscription(int subId, boolean needValidation,
            @Nullable @CallbackExecutor Executor executor, @Nullable Consumer<Integer> callback) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService == null) {
                if (executor == null || callback == null) {
                    return;
                }
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> {
                        if (Compatibility.isChangeEnabled(CALLBACK_ON_MORE_ERROR_CODE_CHANGE)) {
                            callback.accept(SET_OPPORTUNISTIC_SUB_REMOTE_SERVICE_EXCEPTION);
                        } else {
                            callback.accept(SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION);
                        }
                    });
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                return;
            }
            ISetOpportunisticDataCallback callbackStub = new ISetOpportunisticDataCallback.Stub() {
                @Override
                public void onComplete(int result) {
                    if (executor == null || callback == null) {
                        return;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> {
                            callback.accept(result);
                        });
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            };

            iOpportunisticNetworkService
                    .setPreferredDataSubscriptionId(subId, needValidation, callbackStub,
                            pkgForDebug);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setPreferredDataSubscriptionId RemoteException", ex);
        }
        return;
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
        String packageName = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        String attributionTag = mContext != null ? mContext.getAttributionTag() : null;
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService != null) {
                subId = iOpportunisticNetworkService.getPreferredDataSubscriptionId(
                        packageName, attributionTag);
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
     * service to enable modem stack and to attach to the network. If an empty list is passed,
     * it is assumed that no network is available and will result in disabling the modem stack
     * to save power. This api do not switch internet data once network attach is completed.
     * Use {@link TelephonyManager#setPreferredOpportunisticDataSubscription}
     * to switch internet data after network attach is complete.
     * Requires that the calling app has carrier privileges on both primary and
     * secondary subscriptions (see {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     * @param availableNetworks is a list of available network information.
     * @param executor The executor of where the callback will execute.
     * @param callback Callback will be triggered once it succeeds or failed.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    public void updateAvailableNetworks(@NonNull List<AvailableNetworkInfo> availableNetworks,
            @Nullable @CallbackExecutor Executor executor,
            @UpdateAvailableNetworksResult @Nullable Consumer<Integer> callback) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        try {
            IOns iOpportunisticNetworkService = getIOns();
            if (iOpportunisticNetworkService == null || availableNetworks == null) {
                if (executor == null || callback == null) {
                    return;
                }
                if (iOpportunisticNetworkService == null) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> {
                            if (Compatibility.isChangeEnabled(CALLBACK_ON_MORE_ERROR_CODE_CHANGE)) {
                                callback.accept(UPDATE_AVAILABLE_NETWORKS_REMOTE_SERVICE_EXCEPTION);
                            } else {
                                callback.accept(UPDATE_AVAILABLE_NETWORKS_UNKNOWN_FAILURE);
                            }
                        });
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                } else {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> {
                            callback.accept(UPDATE_AVAILABLE_NETWORKS_INVALID_ARGUMENTS);
                        });
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
                return;
            }
            IUpdateAvailableNetworksCallback callbackStub =
                    new IUpdateAvailableNetworksCallback.Stub() {
                        @Override
                        public void onComplete(int result) {
                            if (executor == null || callback == null) {
                                return;
                            }
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> {
                                    callback.accept(result);
                                });
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        }
                    };
            iOpportunisticNetworkService.updateAvailableNetworks(availableNetworks, callbackStub,
                    pkgForDebug);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "updateAvailableNetworks RemoteException", ex);
        }
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
     * It indicates whether modem is enabled or not per slot.
     * It's the corresponding status of TelephonyManager.enableModemForSlot.
     *
     * <p>Requires Permission:
     * READ_PRIVILEGED_PHONE_STATE or
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * @param slotIndex which slot it's checking.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isModemEnabledForSlot(int slotIndex) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isModemEnabledForSlot(slotIndex, mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "enableModem RemoteException", ex);
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
     * <p>There may be a delay of several minutes before reporting that no country is detected.
     *
     * @see #EXTRA_NETWORK_COUNTRY
     * @see #getNetworkCountryIso()
     */
    public static final String ACTION_NETWORK_COUNTRY_CHANGED =
            "android.telephony.action.NETWORK_COUNTRY_CHANGED";

    /**
     * The extra used with an {@link #ACTION_NETWORK_COUNTRY_CHANGED} to specify the
     * the country code in ISO-3166-1 alpha-2 format.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_NETWORK_COUNTRY =
            "android.telephony.extra.NETWORK_COUNTRY";

    /**
     * The extra used with an {@link #ACTION_NETWORK_COUNTRY_CHANGED} to specify the
     * last known the country code in ISO-3166-1 alpha-2 format.
     * <p class="note">
     * Retrieve with {@link android.content.Intent#getStringExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_LAST_KNOWN_NETWORK_COUNTRY =
            "android.telephony.extra.LAST_KNOWN_NETWORK_COUNTRY";

    /**
     * Indicate if the user is allowed to use multiple SIM cards at the same time to register
     * on the network (e.g. Dual Standby or Dual Active) when the device supports it, or if the
     * usage is restricted. This API is used to prevent usage of multiple SIM card, based on
     * policies of the carrier.
     * <p>Note: the API does not prevent access to the SIM cards for operations that don't require
     * access to the network.
     *
     * @param isMultiSimCarrierRestricted true if usage of multiple SIMs is restricted, false
     * otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setMultiSimCarrierRestriction(boolean isMultiSimCarrierRestricted) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setMultiSimCarrierRestriction(isMultiSimCarrierRestricted);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "setMultiSimCarrierRestriction RemoteException", e);
        }
    }

    /**
     * The usage of multiple SIM cards at the same time to register on the network (e.g. Dual
     * Standby or Dual Active) is supported.
     */
    public static final int MULTISIM_ALLOWED = 0;

    /**
     * The usage of multiple SIM cards at the same time to register on the network (e.g. Dual
     * Standby or Dual Active) is not supported by the hardware.
     */
    public static final int MULTISIM_NOT_SUPPORTED_BY_HARDWARE = 1;

    /**
     * The usage of multiple SIM cards at the same time to register on the network (e.g. Dual
     * Standby or Dual Active) is supported by the hardware, but restricted by the carrier.
     */
    public static final int MULTISIM_NOT_SUPPORTED_BY_CARRIER = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"MULTISIM_"},
            value = {
                    MULTISIM_ALLOWED,
                    MULTISIM_NOT_SUPPORTED_BY_HARDWARE,
                    MULTISIM_NOT_SUPPORTED_BY_CARRIER
            })
    public @interface IsMultiSimSupportedResult {}

    /**
     * Returns if the usage of multiple SIM cards at the same time to register on the network
     * (e.g. Dual Standby or Dual Active) is supported by the device and by the carrier.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return {@link #MULTISIM_ALLOWED} if the device supports multiple SIMs.
     * {@link #MULTISIM_NOT_SUPPORTED_BY_HARDWARE} if the device does not support multiple SIMs.
     * {@link #MULTISIM_NOT_SUPPORTED_BY_CARRIER} in the device supports multiple SIMs, but the
     * functionality is restricted by the carrier.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @IsMultiSimSupportedResult
    public int isMultiSimSupported() {
        if (getSupportedModemCount() < 2) {
            return TelephonyManager.MULTISIM_NOT_SUPPORTED_BY_HARDWARE;
        }
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isMultiSimSupported(getOpPackageName(), getAttributionTag());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "isMultiSimSupported RemoteException", e);
        }
        return MULTISIM_NOT_SUPPORTED_BY_HARDWARE;
    }

    /**
     * Switch configs to enable multi-sim or switch back to single-sim
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * Note: with only carrier privileges, it is not allowed to switch from multi-sim
     * to single-sim
     *
     * @param numOfSims number of live SIMs we want to switch to
     * @throws android.os.RemoteException
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void switchMultiSimConfig(int numOfSims) {
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
     * Get whether making changes to modem configurations by {@link #switchMultiSimConfig(int)} will
     * trigger device reboot.
     * The modem configuration change refers to switching from single SIM configuration to DSDS
     * or the other way around.
     *
     *  <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE} or that the
     * calling app has carrier privileges (see {@link #hasCarrierPrivileges}).
     *
     * @return {@code true} if reboot will be triggered after making changes to modem
     * configurations, otherwise return {@code false}.
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public boolean doesSwitchMultiSimConfigTriggerReboot() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.doesSwitchMultiSimConfigTriggerReboot(getSubId(),
                        getOpPackageName(), getAttributionTag());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "doesSwitchMultiSimConfigTriggerReboot RemoteException", e);
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
    @UnsupportedAppUsage
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

    /**
     * Get the calling application status about carrier privileges for the subscription created
     * in TelephonyManager. Used by Telephony Module for permission checking.
     *
     * @param uid Uid to check.
     * @return any value of {@link #CARRIER_PRIVILEGE_STATUS_HAS_ACCESS},
     *         {@link #CARRIER_PRIVILEGE_STATUS_NO_ACCESS},
     *         {@link #CARRIER_PRIVILEGE_STATUS_RULES_NOT_LOADED}, or
     *         {@link #CARRIER_PRIVILEGE_STATUS_ERROR_LOADING_RULES}
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @CarrierPrivilegeStatus int getCarrierPrivilegeStatus(int uid) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCarrierPrivilegeStatusForUid(getSubId(), uid);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "getCarrierPrivilegeStatus RemoteException", ex);
        }
        return TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS;
    }

    /**
     * Returns a list of APNs set as overrides by the device policy manager via
     * {@link #addDevicePolicyOverrideApn}.
     * This method must only be called from the system or phone processes.
     *
     * @param context Context to use.
     * @return {@link List} of APNs that have been set as overrides.
     * @throws {@link SecurityException} if the caller is not the system or phone process.
     * @hide
     */
    @TestApi
    // TODO: add new permission tag indicating that this is system-only.
    public @NonNull List<ApnSetting> getDevicePolicyOverrideApns(@NonNull Context context) {
        try (Cursor cursor = context.getContentResolver().query(DPC_URI, null, null, null, null)) {
            if (cursor == null) {
                return Collections.emptyList();
            }
            List<ApnSetting> apnList = new ArrayList<ApnSetting>();
            cursor.moveToPosition(-1);
            while (cursor.moveToNext()) {
                ApnSetting apn = ApnSetting.makeApnSetting(cursor);
                apnList.add(apn);
            }
            return apnList;
        }
    }

    /**
     * Used by the device policy manager to add a new override APN.
     * This method must only be called from the system or phone processes.
     *
     * @param context Context to use.
     * @param apnSetting The {@link ApnSetting} describing the new APN.
     * @return An integer, corresponding to a primary key in a database, that allows the caller to
     *         modify the APN in the future via {@link #modifyDevicePolicyOverrideApn}, or
     *         {@link android.provider.Telephony.Carriers.INVALID_APN_ID} if the override operation
     *         failed.
     * @throws {@link SecurityException} if the caller is not the system or phone process.
     * @hide
     */
    @TestApi
    // TODO: add new permission tag indicating that this is system-only.
    public int addDevicePolicyOverrideApn(@NonNull Context context,
            @NonNull ApnSetting apnSetting) {
        Uri resultUri = context.getContentResolver().insert(DPC_URI, apnSetting.toContentValues());

        int resultId = INVALID_APN_ID;
        if (resultUri != null) {
            try {
                resultId = Integer.parseInt(resultUri.getLastPathSegment());
            } catch (NumberFormatException e) {
                Rlog.e(TAG, "Failed to parse inserted override APN id: "
                        + resultUri.getLastPathSegment());
            }
        }
        return resultId;
    }

    /**
     * Used by the device policy manager to modify an override APN.
     * This method must only be called from the system or phone processes.
     *
     * @param context Context to use.
     * @param apnId The integer key of the APN to modify, as returned by
     *              {@link #addDevicePolicyOverrideApn}
     * @param apnSetting The {@link ApnSetting} describing the updated APN.
     * @return {@code true} if successful, {@code false} otherwise.
     * @throws {@link SecurityException} if the caller is not the system or phone process.
     * @hide
     */
    @TestApi
    // TODO: add new permission tag indicating that this is system-only.
    public boolean modifyDevicePolicyOverrideApn(@NonNull Context context, int apnId,
            @NonNull ApnSetting apnSetting) {
        return context.getContentResolver().update(
                Uri.withAppendedPath(DPC_URI, Integer.toString(apnId)),
                apnSetting.toContentValues(), null, null) > 0;
    }

    /**
     * Return whether data is enabled for certain APN type. This will tell if framework will accept
     * corresponding network requests on a subId.
     *
     * {@link #isDataEnabled()} is directly associated with users' Mobile data toggle on / off. If
     * {@link #isDataEnabled()} returns false, it means in general all meter-ed data are disabled.
     *
     * This per APN type API gives a better idea whether data is allowed on a specific APN type.
     * It will return true if:
     *
     *  1) User data is turned on, or
     *  2) APN is un-metered for this subscription, or
     *  3) APN type is whitelisted. E.g. MMS is whitelisted if
     *  {@link #setAlwaysAllowMmsData(boolean)} is turned on.
     *
     * @param apnType Value indicating the apn type. Apn types are defined in {@link ApnSetting}.
     * @return whether data is enabled for a apn type.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isDataEnabledForApn(@ApnType int apnType) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isDataEnabledForApn(apnType, getSubId(), pkgForDebug);
            }
        } catch (RemoteException ex) {
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
        return false;
    }

    /**
     * Whether an APN type is metered or not. It will be evaluated with the subId associated
     * with the TelephonyManager instance.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isApnMetered(@ApnType int apnType) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isApnMetered(apnType, getSubId());
            }
        } catch (RemoteException ex) {
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
        return true;
    }

    /**
     * Specify which bands modem's background scan must act on.
     * If {@code specifiers} is non-empty, the scan will be restricted to the bands specified.
     * Otherwise, it scans all bands.
     *
     * For example, CBRS is only on LTE band 48. By specifying this band,
     * modem saves more power.
     *
     * @param specifiers which bands to scan.
     * @param executor The executor to execute the callback on
     * @param callback The callback that gets invoked when the radio responds to the request. Called
     *                 with {@code true} if the request succeeded, {@code false} otherwise.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setSystemSelectionChannels(@NonNull List<RadioAccessSpecifier> specifiers,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        Objects.requireNonNull(specifiers, "Specifiers must not be null.");
        Objects.requireNonNull(executor, "Executor must not be null.");
        Objects.requireNonNull(callback, "Callback must not be null.");
        setSystemSelectionChannelsInternal(specifiers, executor, callback);
    }

    /**
     * Same as {@link #setSystemSelectionChannels(List, Executor, Consumer<Boolean>)}, but to be
     * used when the caller does not need feedback on the results of the operation.
     * @param specifiers which bands to scan.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setSystemSelectionChannels(@NonNull List<RadioAccessSpecifier> specifiers) {
        Objects.requireNonNull(specifiers, "Specifiers must not be null.");
        setSystemSelectionChannelsInternal(specifiers, null, null);
    }


    private void setSystemSelectionChannelsInternal(@NonNull List<RadioAccessSpecifier> specifiers,
            @Nullable @CallbackExecutor Executor executor,
            @Nullable Consumer<Boolean> callback) {
        IBooleanConsumer aidlConsumer = callback == null ? null : new IBooleanConsumer.Stub() {
            @Override
            public void accept(boolean result) {
                executor.execute(() -> callback.accept(result));
            }
        };

        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.setSystemSelectionChannels(specifiers, getSubId(), aidlConsumer);
            }
        } catch (RemoteException ex) {
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Verifies whether the input MCC/MNC and MVNO correspond to the current carrier.
     *
     * @param mccmnc the carrier's mccmnc that you want to match
     * @param mvnoType the mvnoType that defined in {@link ApnSetting}
     * @param mvnoMatchData the MVNO match data
     * @return {@code true} if input mccmnc and mvno matches with data from sim operator.
     * {@code false} otherwise.
     *
     * {@hide}
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean matchesCurrentSimOperator(@NonNull String mccmnc, @MvnoType int mvnoType,
            @Nullable String mvnoMatchData) {
        try {
            if (!mccmnc.equals(getSimOperator())) {
                return false;
            }
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isMvnoMatched(getSubId(), mvnoType, mvnoMatchData);
            }
        } catch (RemoteException ex) {
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
        return false;
    }

    /**
     * Gets the voice call forwarding info {@link CallForwardingInfo}, given the call forward
     * reason.
     *
     * @param callForwardingReason the call forwarding reasons
     *
     * @throws IllegalArgumentException if callForwardingReason is not any of
     * {@link CallForwardingInfo.REASON_UNCONDITIONAL}, {@link CallForwardingInfo.REASON_BUSY},
     * {@link CallForwardingInfo.REASON_NO_REPLY}, {@link CallForwardingInfo.REASON_NOT_REACHABLE},
     * {@link CallForwardingInfo.REASON_ALL}, {@link CallForwardingInfo.REASON_ALL_CONDITIONAL}
     *
     * @return {@link CallForwardingInfo} with the status {@link CallForwardingInfo#STATUS_ACTIVE}
     * or {@link CallForwardingInfo#STATUS_INACTIVE} and the target phone number to forward calls
     * to, if it's available. Otherwise, it will return a {@link CallForwardingInfo} with status
     * {@link CallForwardingInfo#STATUS_UNKNOWN_ERROR},
     * {@link CallForwardingInfo#STATUS_NOT_SUPPORTED},
     * or {@link CallForwardingInfo#STATUS_FDN_CHECK_FAILURE} depending on the situation.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull
    public CallForwardingInfo getCallForwarding(@CallForwardingReason int callForwardingReason) {
        if (callForwardingReason < CallForwardingInfo.REASON_UNCONDITIONAL
                || callForwardingReason > CallForwardingInfo.REASON_ALL_CONDITIONAL) {
            throw new IllegalArgumentException("callForwardingReason is out of range");
        }
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCallForwarding(getSubId(), callForwardingReason);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCallForwarding RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCallForwarding NPE", ex);
        }
        return new CallForwardingInfo(
                CallForwardingInfo.STATUS_UNKNOWN_ERROR, 0 /* reason */, null /* number */,
                        0 /* timeout */);
    }

    /**
     * Sets the voice call forwarding info including status (enable/disable), call forwarding
     * reason, the number to forward, and the timeout before the forwarding is attempted.
     *
     * @param callForwardingInfo {@link CallForwardingInfo} to setup the call forwarding.
     * Enabling if {@link CallForwardingInfo#getStatus()} returns
     * {@link CallForwardingInfo#STATUS_ACTIVE}; Disabling if
     * {@link CallForwardingInfo#getStatus()} returns {@link CallForwardingInfo#STATUS_INACTIVE}.
     *
     * @throws IllegalArgumentException if any of the following for parameter callForwardingInfo:
     * 0) it is {@code null}.
     * 1) {@link CallForwardingInfo#getStatus()} returns neither
     * {@link CallForwardingInfo#STATUS_ACTIVE} nor {@link CallForwardingInfo#STATUS_INACTIVE}.
     * 2) {@link CallForwardingInfo#getReason()} is not any of
     * {@link CallForwardingInfo.REASON_UNCONDITIONAL}, {@link CallForwardingInfo.REASON_BUSY},
     * {@link CallForwardingInfo.REASON_NO_REPLY}, {@link CallForwardingInfo.REASON_NOT_REACHABLE},
     * {@link CallForwardingInfo.REASON_ALL}, {@link CallForwardingInfo.REASON_ALL_CONDITIONAL}
     * 3) {@link CallForwardingInfo#getNumber()} returns {@code null}.
     * 4) {@link CallForwardingInfo#getTimeoutSeconds()} doesn't return a positive value.
     *
     * @return {@code true} to indicate it was set successfully; {@code false} otherwise.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setCallForwarding(@NonNull CallForwardingInfo callForwardingInfo) {
        if (callForwardingInfo == null) {
            throw new IllegalArgumentException("callForwardingInfo is null");
        }
        int callForwardingStatus = callForwardingInfo.getStatus();
        if (callForwardingStatus != CallForwardingInfo.STATUS_ACTIVE
                && callForwardingStatus != CallForwardingInfo.STATUS_INACTIVE) {
            throw new IllegalArgumentException(
                    "callForwardingStatus is neither active nor inactive");
        }
        int callForwardingReason = callForwardingInfo.getReason();
        if (callForwardingReason < CallForwardingInfo.REASON_UNCONDITIONAL
                || callForwardingReason > CallForwardingInfo.REASON_ALL_CONDITIONAL) {
            throw new IllegalArgumentException("callForwardingReason is out of range");
        }
        if (callForwardingInfo.getNumber() == null) {
            throw new IllegalArgumentException("callForwarding number is null");
        }
        if (callForwardingInfo.getTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("callForwarding timeout isn't positive");
        }
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setCallForwarding(getSubId(), callForwardingInfo);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setCallForwarding RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setCallForwarding NPE", ex);
        }
        return false;
    }

    /**
     * Indicates the call waiting status is active.
     *
     * @hide
     */
    public static final int CALL_WAITING_STATUS_ACTIVE = 1;

    /**
     * Indicates the call waiting status is inactive.
     *
     * @hide
     */
    public static final int CALL_WAITING_STATUS_INACTIVE = 2;

    /**
     * Indicates the call waiting status is with an unknown error.
     *
     * @hide
     */
    public static final int CALL_WAITING_STATUS_UNKNOWN_ERROR = 3;

    /**
     * Indicates the call waiting is not supported (e.g. called via CDMA).
     *
     * @hide
     */
    public static final int CALL_WAITING_STATUS_NOT_SUPPORTED = 4;

    /**
     * Call waiting function status
     *
     * @hide
     */
    @IntDef(prefix = { "CALL_WAITING_STATUS_" }, value = {
        CALL_WAITING_STATUS_ACTIVE,
        CALL_WAITING_STATUS_INACTIVE,
        CALL_WAITING_STATUS_NOT_SUPPORTED,
        CALL_WAITING_STATUS_UNKNOWN_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallWaitingStatus {
    }

    /**
     * Gets the status of voice call waiting function. Call waiting function enables the waiting
     * for the incoming call when it reaches the user who is busy to make another call and allows
     * users to decide whether to switch to the incoming call.
     *
     * @return the status of call waiting function.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @CallWaitingStatus int getCallWaitingStatus() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getCallWaitingStatus(getSubId());
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getCallWaitingStatus RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "getCallWaitingStatus NPE", ex);
        }
        return CALL_WAITING_STATUS_UNKNOWN_ERROR;
    }

    /**
     * Sets the status for voice call waiting function. Call waiting function enables the waiting
     * for the incoming call when it reaches the user who is busy to make another call and allows
     * users to decide whether to switch to the incoming call.
     *
     * @param isEnable {@code true} to enable; {@code false} to disable.
     * @return {@code true} to indicate it was set successfully; {@code false} otherwise.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setCallWaitingStatus(boolean isEnable) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setCallWaitingStatus(getSubId(), isEnable);
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setCallWaitingStatus RemoteException", ex);
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "setCallWaitingStatus NPE", ex);
        }
        return false;
    }

    /**
     * Set allowing mobile data during voice call. This is used for allowing data on the non-default
     * data SIM. When a voice call is placed on the non-default data SIM on DSDS devices, users will
     * not be able to use mobile data. By calling this API, data will be temporarily enabled on the
     * non-default data SIM during the life cycle of the voice call.
     *
     * @param allow {@code true} if allowing using data during voice call, {@code false} if
     * disallowed.
     *
     * @return {@code true} if operation is successful. otherwise {@code false}.
     *
     * @throws SecurityException if the caller doesn't have the permission.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setDataAllowedDuringVoiceCall(boolean allow) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.setDataAllowedDuringVoiceCall(getSubId(), allow);
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
        return false;
    }

    /**
     * Check whether data is allowed during voice call. This is used for allowing data on the
     * non-default data SIM. When a voice call is placed on the non-default data SIM on DSDS
     * devices, users will not be able to use mobile data. By calling this API, data will be
     * temporarily enabled on the non-default data SIM during the life cycle of the voice call.
     *
     * @return {@code true} if data is allowed during voice call.
     *
     * @throws SecurityException if the caller doesn't have the permission.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isDataAllowedInVoiceCall() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.isDataAllowedInVoiceCall(getSubId());
            }
        } catch (RemoteException ex) {
            // This could happen if binder process crashes.
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
        return false;
    }

    /**
     * Set whether the specific sim card always allows MMS connection. If true, MMS network
     * request will be accepted by telephony even if user turns "mobile data" off
     * on this specific sim card.
     *
     * @param alwaysAllow whether Mms data is always allowed.
     * @return whether operation is successful.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setAlwaysAllowMmsData(boolean alwaysAllow) {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                return service.setAlwaysAllowMmsData(getSubId(), alwaysAllow);
            }
        } catch (RemoteException ex) {
            if (!isSystemProcess()) {
                ex.rethrowAsRuntimeException();
            }
        }
        return false;
    }

    /**
     * The IccLock state or password was changed successfully.
     * @hide
     */
    public static final int CHANGE_ICC_LOCK_SUCCESS = Integer.MAX_VALUE;

    /**
     * Check whether ICC pin lock is enabled.
     * This is a sync call which returns the cached pin enabled state.
     *
     * @return {@code true} if ICC lock enabled, {@code false} if ICC lock disabled.
     *
     * @throws SecurityException if the caller doesn't have the permission.
     *
     * @hide
     */
    @WorkerThread
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @SystemApi
    public boolean isIccLockEnabled() {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.isIccLockEnabled(getSubId());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "isIccLockEnabled RemoteException", e);
        }
        return false;
    }

    /**
     * Set the ICC pin lock enabled or disabled.
     *
     * If enable/disable ICC pin lock successfully, a value of {@link Integer#MAX_VALUE} is
     * returned.
     * If an incorrect old password is specified, the return value will indicate how many more
     * attempts the user can make to change the password before the SIM is locked.
     * Using PUK code to unlock SIM if enter the incorrect old password 3 times.
     *
     * @param enabled    "true" for locked, "false" for unlocked.
     * @param password   needed to change the ICC pin state, aka. Pin1
     * @return an integer representing the status of IccLock enabled or disabled in the following
     * three cases:
     *   - {@link TelephonyManager#CHANGE_ICC_LOCK_SUCCESS} if enabled or disabled IccLock
     *   successfully.
     *   - Positive number and zero for remaining password attempts.
     *   - Negative number for other failure cases (such like enabling/disabling PIN failed).
     *
     * @throws SecurityException if the caller doesn't have the permission.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public int setIccLockEnabled(boolean enabled, @NonNull String password) {
        checkNotNull(password, "setIccLockEnabled password can't be null.");
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setIccLockEnabled(getSubId(), enabled, password);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "setIccLockEnabled RemoteException", e);
        }
        return 0;
    }

    /**
     * Change the ICC password used in ICC pin lock.
     *
     * If the password was changed successfully, a value of {@link Integer#MAX_VALUE} is returned.
     * If an incorrect old password is specified, the return value will indicate how many more
     * attempts the user can make to change the password before the SIM is locked.
     * Using PUK code to unlock SIM if enter the incorrect old password 3 times.
     *
     * @param oldPassword is the old password
     * @param newPassword is the new password
     * @return an integer representing the status of IccLock changed in the following three cases:
     *   - {@link TelephonyManager#CHANGE_ICC_LOCK_SUCCESS} if changed IccLock successfully.
     *   - Positive number and zero for remaining password attempts.
     *   - Negative number for other failure cases (such like enabling/disabling PIN failed).
     *
     * @throws SecurityException if the caller doesn't have the permission.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public int changeIccLockPassword(@NonNull String oldPassword, @NonNull String newPassword) {
        checkNotNull(oldPassword, "changeIccLockPassword oldPassword can't be null.");
        checkNotNull(newPassword, "changeIccLockPassword newPassword can't be null.");
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.changeIccLockPassword(getSubId(), oldPassword, newPassword);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "changeIccLockPassword RemoteException", e);
        }
        return 0;
    }

    /**
     * Called when userActivity is signalled in the power manager.
     * This should only be called from system Uid.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void notifyUserActivity() {
        try {
            ITelephony service = getITelephony();
            if (service != null) {
                service.userActivity();
            }
        } catch (RemoteException e) {
            // one-way notification, if telephony is not available, it is okay to not throw
            // exception here.
            Log.w(TAG, "notifyUserActivity exception: " + e.getMessage());
        }
    }

    private static class DeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            resetServiceCache();
        }
    }

   /**
    * Reset everything in the service cache; if one handle died then they are
    * all probably broken.
    * @hide
    */
    private static void resetServiceCache() {
        synchronized (sCacheLock) {
            if (sISub != null) {
                sISub.asBinder().unlinkToDeath(sServiceDeath, 0);
                sISub = null;
                SubscriptionManager.clearCaches();
            }
            if (sISms != null) {
                sISms.asBinder().unlinkToDeath(sServiceDeath, 0);
                sISms = null;
            }
            if (sIPhoneSubInfo != null) {
                sIPhoneSubInfo.asBinder().unlinkToDeath(sServiceDeath, 0);
                sIPhoneSubInfo = null;
            }
        }
    }

   /**
    * @hide
    */
    static IPhoneSubInfo getSubscriberInfoService() {
        // Keeps cache disabled until test fixes are checked into AOSP.
        if (!sServiceHandleCacheEnabled) {
            return IPhoneSubInfo.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getPhoneSubServiceRegisterer()
                        .get());
        }

        if (sIPhoneSubInfo == null) {
            IPhoneSubInfo temp = IPhoneSubInfo.Stub.asInterface(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getPhoneSubServiceRegisterer()
                        .get());
            synchronized (sCacheLock) {
                if (sIPhoneSubInfo == null && temp != null) {
                    try {
                        sIPhoneSubInfo = temp;
                        sIPhoneSubInfo.asBinder().linkToDeath(sServiceDeath, 0);
                    } catch (Exception e) {
                        // something has gone horribly wrong
                        sIPhoneSubInfo = null;
                    }
                }
            }
        }
        return sIPhoneSubInfo;
    }

   /**
    * @hide
    */
    static ISub getSubscriptionService() {
        // Keeps cache disabled until test fixes are checked into AOSP.
        if (!sServiceHandleCacheEnabled) {
            return ISub.Stub.asInterface(
                    TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .getSubscriptionServiceRegisterer()
                            .get());
        }

        if (sISub == null) {
            ISub temp = ISub.Stub.asInterface(
                    TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .getSubscriptionServiceRegisterer()
                            .get());
            synchronized (sCacheLock) {
                if (sISub == null && temp != null) {
                    try {
                        sISub = temp;
                        sISub.asBinder().linkToDeath(sServiceDeath, 0);
                    } catch (Exception e) {
                        // something has gone horribly wrong
                        sISub = null;
                    }
                }
            }
        }
        return sISub;
    }

    /**
    * @hide
    */
    static ISms getSmsService() {
        // Keeps cache disabled until test fixes are checked into AOSP.
        if (!sServiceHandleCacheEnabled) {
            return ISms.Stub.asInterface(
                    TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .getSmsServiceRegisterer()
                            .get());
        }

        if (sISms == null) {
            ISms temp = ISms.Stub.asInterface(
                    TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .getSmsServiceRegisterer()
                            .get());
            synchronized (sCacheLock) {
                if (sISms == null && temp != null) {
                    try {
                        sISms = temp;
                        sISms.asBinder().linkToDeath(sServiceDeath, 0);
                    } catch (Exception e) {
                        // something has gone horribly wrong
                        sISms = null;
                    }
                }
            }
        }
        return sISms;
    }

    /**
     * Disables service handle caching for tests that utilize mock services.
     * @hide
     */
    @VisibleForTesting
    public static void disableServiceHandleCaching() {
        sServiceHandleCacheEnabled = false;
    }

    /**
     * Reenables service handle caching.
     * @hide
     */
    @VisibleForTesting
    public static void enableServiceHandleCaching() {
        sServiceHandleCacheEnabled = true;
    }

    /**
     * Whether device can connect to 5G network when two SIMs are active.
     * @hide
     * TODO b/153669716: remove or make system API.
     */
    public boolean canConnectTo5GInDsdsMode() {
        ITelephony telephony = getITelephony();
        if (telephony == null) return true;
        try {
            return telephony.canConnectTo5GInDsdsMode();
        } catch (RemoteException ex) {
            return true;
        } catch (NullPointerException ex) {
            return true;
        }
    }
}
