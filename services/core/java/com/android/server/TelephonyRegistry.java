/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import static android.telephony.SubscriptionManager.INVALID_SIM_SLOT_INDEX;
import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;
import static android.telephony.TelephonyRegistryManager.SIM_ACTIVATION_TYPE_DATA;
import static android.telephony.TelephonyRegistryManager.SIM_ACTIVATION_TYPE_VOICE;

import static java.util.Arrays.copyOf;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SrvccState;
import android.telephony.BarringInfo;
import android.telephony.CallQuality;
import android.telephony.CallState;
import android.telephony.CellIdentity;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.DisconnectCause;
import android.telephony.LinkCapacityEstimate;
import android.telephony.LocationAccessPolicy;
import android.telephony.PhoneCapability;
import android.telephony.PhoneStateListener;
import android.telephony.PhysicalChannelConfig;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.PreciseDisconnectCause;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsCallSession;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.MediaQualityStatus;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.ICarrierConfigChangeListener;
import com.android.internal.telephony.ICarrierPrivilegesCallback;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.am.BatteryStatsService;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Since phone process can be restarted, this class provides a centralized place
 * that applications can register and be called back from.
 *
 * Change-Id: I450c968bda93767554b5188ee63e10c9f43c5aa4 fixes bugs 16148026
 * and 15973975 by saving the phoneId of the registrant and then using the
 * phoneId when deciding to to make a callback. This is necessary because
 * a subId changes from to a placeholder value when a SIM is removed and thus won't
 * compare properly. Because getPhoneIdFromSubId(int subId) handles
 * the placeholder value conversion we properly do the callbacks.
 *
 * Eventually we may want to remove the notion of placeholder value but for now this
 * looks like the best approach.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class TelephonyRegistry extends ITelephonyRegistry.Stub {
    private static final String TAG = "TelephonyRegistry";
    private static final boolean DBG = false; // STOPSHIP if true
    private static final boolean DBG_LOC = false; // STOPSHIP if true
    private static final boolean VDBG = false; // STOPSHIP if true

    private static class Record {
        Context context;

        String callingPackage;
        String callingFeatureId;

        IBinder binder;

        TelephonyRegistryDeathRecipient deathRecipient;

        IPhoneStateListener callback;
        IOnSubscriptionsChangedListener onSubscriptionsChangedListenerCallback;
        IOnSubscriptionsChangedListener onOpportunisticSubscriptionsChangedListenerCallback;
        ICarrierPrivilegesCallback carrierPrivilegesCallback;
        ICarrierConfigChangeListener carrierConfigChangeListener;

        int callerUid;
        int callerPid;
        boolean renounceFineLocationAccess;
        boolean renounceCoarseLocationAccess;

        Set<Integer> eventList;

        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        int phoneId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;

        boolean matchTelephonyCallbackEvent(int event) {
            return (callback != null) && (this.eventList.contains(event));
        }

        boolean matchOnSubscriptionsChangedListener() {
            return (onSubscriptionsChangedListenerCallback != null);
        }

        boolean matchOnOpportunisticSubscriptionsChangedListener() {
            return (onOpportunisticSubscriptionsChangedListenerCallback != null);
        }

        boolean matchCarrierPrivilegesCallback() {
            return carrierPrivilegesCallback != null;
        }

        boolean matchCarrierConfigChangeListener() {
            return carrierConfigChangeListener != null;
        }

        boolean canReadCallLog() {
            try {
                return TelephonyPermissions.checkReadCallLog(
                        context, subId, callerPid, callerUid, callingPackage, callingFeatureId);
            } catch (SecurityException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "{callingPackage=" + pii(callingPackage) + " callerUid=" + callerUid + " binder="
                    + binder + " callback=" + callback
                    + " onSubscriptionsChangedListenererCallback="
                    + onSubscriptionsChangedListenerCallback
                    + " onOpportunisticSubscriptionsChangedListenererCallback="
                    + onOpportunisticSubscriptionsChangedListenerCallback
                    + " carrierPrivilegesCallback=" + carrierPrivilegesCallback
                    + " carrierConfigChangeListener=" + carrierConfigChangeListener
                    + " subId=" + subId + " phoneId=" + phoneId + " events=" + eventList + "}";
        }
    }

    /**
     * Wrapper class to facilitate testing -- encapsulates bits of configuration that are
     * normally fetched from static methods with many dependencies.
     */
    public static class ConfigurationProvider {
        /**
         * @return The per-pid registration limit for PhoneStateListeners, as set from DeviceConfig
         * @noinspection ConstantConditions
         */
        public int getRegistrationLimit() {
            return Binder.withCleanCallingIdentity(() ->
                    DeviceConfig.getInt(DeviceConfig.NAMESPACE_TELEPHONY,
                            TelephonyCallback.FLAG_PER_PID_REGISTRATION_LIMIT,
                            TelephonyCallback.DEFAULT_PER_PID_REGISTRATION_LIMIT));
        }

        /**
         * @param uid uid to check
         * @return Whether enforcement of the per-pid registation limit for PhoneStateListeners is
         *         enabled in PlatformCompat for the given uid.
         * @noinspection ConstantConditions
         */
        public boolean isRegistrationLimitEnabledInPlatformCompat(int uid) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    TelephonyCallback.PHONE_STATE_LISTENER_LIMIT_CHANGE_ID, uid));
        }

        /**
         * See {@link TelecomManager#ENABLE_GET_CALL_STATE_PERMISSION_PROTECTION} for more
         * information.
         * @noinspection ConstantConditions
         */
        public boolean isCallStateReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    TelecomManager.ENABLE_GET_CALL_STATE_PERMISSION_PROTECTION, packageName,
                    userHandle));
        }

        /**
         * To check the SDK version for
         * {@link android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener} should add
         * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
         * @noinspection ConstantConditions
         */
        public boolean isActiveDataSubIdReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_ACTIVE_DATA_SUB_ID, packageName,
                    userHandle));
        }

        /**
         * To check the SDK version for
         * {@link android.telephony.TelephonyCallback.CellInfoListener} should add
         * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
         * @noinspection ConstantConditions
         */
        public boolean isCellInfoReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_CELL_INFO, packageName, userHandle));
        }

        /**
         * To check the SDK version for
         * {@link android.telephony.TelephonyCallback.DisplayInfoListener} should remove
         * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
         * @noinspection ConstantConditions
         */
        public boolean isDisplayInfoReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_DISPLAY_INFO, packageName, userHandle));
        }

        /**
         * Support backward compatibility for {@link android.telephony.TelephonyDisplayInfo}.
         *
         * @noinspection ConstantConditions
         */
        public boolean isDisplayInfoNrAdvancedSupported(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    DISPLAY_INFO_NR_ADVANCED_SUPPORTED, packageName, userHandle));
        }
    }

    private final Context mContext;

    private ConfigurationProvider mConfigurationProvider;

    // access should be inside synchronized (mRecords) for these two fields
    private final ArrayList<IBinder> mRemoveList = new ArrayList<IBinder>();
    private final ArrayList<Record> mRecords = new ArrayList<Record>();

    private final IBatteryStats mBatteryStats;

    private final AppOpsManager mAppOps;

    private boolean mHasNotifySubscriptionInfoChangedOccurred = false;

    private boolean mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = false;

    private int mNumPhones;

    private int[] mCallState;

    private String[] mCallIncomingNumber;

    private ServiceState[] mServiceState;

    private int[] mVoiceActivationState;

    private int[] mDataActivationState;

    private boolean[] mUserMobileDataState;

    private TelephonyDisplayInfo[] mTelephonyDisplayInfos;

    private SignalStrength[] mSignalStrength;

    private boolean[] mMessageWaiting;

    private boolean[] mCallForwarding;

    private int[] mDataActivity;

    // Connection state of default APN type data (i.e. internet) of phones
    private int[] mDataConnectionState;

    private CellIdentity[] mCellIdentity;

    private int[] mDataConnectionNetworkType;

    private ArrayList<List<CellInfo>> mCellInfo;

    private Map<Integer, List<EmergencyNumber>> mEmergencyNumberList;

    private EmergencyNumber[] mOutgoingSmsEmergencyNumber;

    private EmergencyNumber[] mOutgoingCallEmergencyNumber;

    private CallQuality[] mCallQuality;

    private List<SparseArray<MediaQualityStatus>> mMediaQualityStatus;

    private ArrayList<List<CallState>> mCallStateLists;

    // network type of the call associated with the mCallStateLists and mCallQuality
    private int[] mCallNetworkType;

    private int[] mSrvccState;

    private int mDefaultSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private int mDefaultPhoneId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;

    private int[] mRingingCallState;

    private int[] mForegroundCallState;

    private int[] mBackgroundCallState;

    private PreciseCallState[] mPreciseCallState;

    private int[] mCallDisconnectCause;

    private List<ImsReasonInfo> mImsReasonInfo = null;

    private int[] mCallPreciseDisconnectCause;

    private List<BarringInfo> mBarringInfo = null;

    private boolean[] mCarrierNetworkChangeState = null;

    private PhoneCapability mPhoneCapability = null;

    private int mActiveDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    @RadioPowerState
    private int mRadioPowerState = TelephonyManager.RADIO_POWER_UNAVAILABLE;

    private final LocalLog mLocalLog = new LocalLog(200);

    private final LocalLog mListenLog = new LocalLog(200);

    private List<List<PhysicalChannelConfig>> mPhysicalChannelConfigs;

    private boolean[] mIsDataEnabled;

    private int[] mDataEnabledReason;

    private int[] mAllowedNetworkTypeReason;
    private long[] mAllowedNetworkTypeValue;

    private static final List<LinkCapacityEstimate> INVALID_LCE_LIST =
            new ArrayList<LinkCapacityEstimate>(Arrays.asList(new LinkCapacityEstimate(
            LinkCapacityEstimate.LCE_TYPE_COMBINED,
            LinkCapacityEstimate.INVALID, LinkCapacityEstimate.INVALID)));
    private List<List<LinkCapacityEstimate>> mLinkCapacityEstimateLists;

    private int[] mECBMReason;
    private boolean[] mECBMStarted;
    private int[] mSCBMReason;
    private boolean[] mSCBMStarted;

    /**
     * Per-phone map of precise data connection state. The key of the map is the pair of transport
     * type and APN setting. This is the cache to prevent redundant callbacks to the listeners.
     * A precise data connection with state {@link TelephonyManager#DATA_DISCONNECTED} removes
     * its entry from the map.
     */
    private List<Map<Pair<Integer, ApnSetting>, PreciseDataConnectionState>>
            mPreciseDataConnectionStates;

    /** Per-phoneId snapshot of privileged packages (names + UIDs). */
    @NonNull private List<Pair<List<String>, int[]>> mCarrierPrivilegeStates;
    /** Per-phoneId of CarrierService (PackageName, UID) pair. */
    @NonNull private List<Pair<String, Integer>> mCarrierServiceStates;

    /**
     * Support backward compatibility for {@link android.telephony.TelephonyDisplayInfo}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long DISPLAY_INFO_NR_ADVANCED_SUPPORTED = 181658987L;

    /**
     * To check the SDK version for
     * {@link android.telephony.TelephonyCallback.DisplayInfoListener} should remove
     * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_DISPLAY_INFO = 183164979L;

    /**
     * To check the SDK version for
     * {@link android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener} should add
     * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_ACTIVE_DATA_SUB_ID
            = 182478738L;

    /**
     * To check the SDK version for {@link android.telephony.TelephonyCallback.CellInfoListener}
     * should add {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_CELL_INFO = 184323934L;

    private static final Set<Integer> REQUIRE_PRECISE_PHONE_STATE_PERMISSION;
    static {
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION = new HashSet<Integer>();
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_DATA_CONNECTION_REAL_TIME_INFO_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(TelephonyCallback.EVENT_REGISTRATION_FAILURE);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(TelephonyCallback.EVENT_BARRING_INFO_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_DATA_ENABLED_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_MEDIA_QUALITY_STATUS_CHANGED);
    }

    private boolean isLocationPermissionRequired(Set<Integer> events) {
        return events.contains(TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)
                || events.contains(TelephonyCallback.EVENT_CELL_INFO_CHANGED);
    }

    private boolean isPhoneStatePermissionRequired(Set<Integer> events, String callingPackage,
            UserHandle userHandle) {
        if (events.contains(TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)
                || events.contains(TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)
                || events.contains(TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED)) {
            return true;
        }

        // Only check READ_PHONE_STATE for CALL_STATE_CHANGED for Android 12 or above.
        if ((events.contains(TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_CALL_STATE_CHANGED))
                && mConfigurationProvider.isCallStateReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        // Only check READ_PHONE_STATE for ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED for Android 12
        // or above.
        if (events.contains(TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED)
                && mConfigurationProvider.isActiveDataSubIdReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        // Only check READ_PHONE_STATE for CELL_INFO_CHANGED for Android 12 or above.
        if (events.contains(TelephonyCallback.EVENT_CELL_INFO_CHANGED)
                && mConfigurationProvider.isCellInfoReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        // Only check READ_PHONE_STATE for DISPLAY_INFO_CHANGED for Android 11 or older.
        // READ_PHONE_STATE is not required anymore after Android 12.
        if (events.contains(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)
                && !mConfigurationProvider.isDisplayInfoReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        return false;
    }

    private boolean isPrecisePhoneStatePermissionRequired(Set<Integer> events) {
        for (Integer requireEvent : REQUIRE_PRECISE_PHONE_STATE_PERMISSION) {
            if (events.contains(requireEvent)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveEmergencySessionPermissionRequired(Set<Integer> events) {
        return events.contains(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL)
                || events.contains(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS);
    }

    private boolean isPrivilegedPhoneStatePermissionRequired(Set<Integer> events) {
        return events.contains(TelephonyCallback.EVENT_SRVCC_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED)
                || events.contains(TelephonyCallback.EVENT_EMERGENCY_CALLBACK_MODE_CHANGED);
    }

    private static final int MSG_USER_SWITCHED = 1;
    private static final int MSG_UPDATE_DEFAULT_SUB = 2;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_SWITCHED: {
                    if (VDBG) log("MSG_USER_SWITCHED userId=" + msg.arg1);
                    int numPhones = getTelephonyManager().getActiveModemCount();
                    for (int phoneId = 0; phoneId < numPhones; phoneId++) {
                        int subId = SubscriptionManager.getSubscriptionId(phoneId);
                        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                            subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
                        }
                        TelephonyRegistry.this.notifyCellLocationForSubscriber(
                                subId, mCellIdentity[phoneId], true /* hasUserSwitched */);
                    }
                    break;
                }
                case MSG_UPDATE_DEFAULT_SUB: {
                    int newDefaultPhoneId = msg.arg1;
                    int newDefaultSubId = msg.arg2;
                    if (VDBG) {
                        log("MSG_UPDATE_DEFAULT_SUB:current mDefaultSubId=" + mDefaultSubId
                                + " current mDefaultPhoneId=" + mDefaultPhoneId
                                + " newDefaultSubId=" + newDefaultSubId
                                + " newDefaultPhoneId=" + newDefaultPhoneId);
                    }

                    //Due to possible race condition,(notify call back using the new
                    //defaultSubId comes before new defaultSubId update) we need to recall all
                    //possible missed notify callback
                    synchronized (mRecords) {
                        for (Record r : mRecords) {
                            if(r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                                checkPossibleMissNotify(r, newDefaultPhoneId);
                            }
                        }
                        handleRemoveListLocked();
                    }
                    mDefaultSubId = newDefaultSubId;
                    mDefaultPhoneId = newDefaultPhoneId;
                    mLocalLog.log("Default subscription updated: mDefaultPhoneId="
                            + mDefaultPhoneId + ", mDefaultSubId=" + mDefaultSubId);
                }
            }
        }
    };

    private class TelephonyRegistryDeathRecipient implements IBinder.DeathRecipient {

        private final IBinder binder;

        TelephonyRegistryDeathRecipient(IBinder binder) {
            this.binder = binder;
        }

        @Override
        public void binderDied() {
            if (DBG) log("binderDied " + binder);
            remove(binder);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (VDBG) log("mBroadcastReceiver: action=" + action);
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (DBG) log("onReceive: userHandle=" + userHandle);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHED, userHandle, 0));
            } else if (action.equals(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)) {
                int newDefaultSubId = intent.getIntExtra(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.getDefaultSubscriptionId());
                int newDefaultPhoneId = intent.getIntExtra(
                        SubscriptionManager.EXTRA_SLOT_INDEX,
                        getPhoneIdFromSubId(newDefaultSubId));
                if (DBG) {
                    log("onReceive:current mDefaultSubId=" + mDefaultSubId
                            + " current mDefaultPhoneId=" + mDefaultPhoneId
                            + " newDefaultSubId=" + newDefaultSubId
                            + " newDefaultPhoneId=" + newDefaultPhoneId);
                }

                if (validatePhoneId(newDefaultPhoneId)
                        && (newDefaultSubId != mDefaultSubId
                                || newDefaultPhoneId != mDefaultPhoneId)) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_DEFAULT_SUB,
                            newDefaultPhoneId, newDefaultSubId));
                }
            } else if (action.equals(ACTION_MULTI_SIM_CONFIG_CHANGED)) {
                onMultiSimConfigChanged();
            }
        }
    };

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    private void onMultiSimConfigChanged() {
        synchronized (mRecords) {
            int oldNumPhones = mNumPhones;
            mNumPhones = getTelephonyManager().getActiveModemCount();
            if (oldNumPhones == mNumPhones) return;

            if (DBG) {
                log("TelephonyRegistry: activeModemCount changed from " + oldNumPhones
                        + " to " + mNumPhones);
            }
            mCallState = copyOf(mCallState, mNumPhones);
            mDataActivity = copyOf(mCallState, mNumPhones);
            mDataConnectionState = copyOf(mCallState, mNumPhones);
            mDataConnectionNetworkType = copyOf(mCallState, mNumPhones);
            mCallIncomingNumber = copyOf(mCallIncomingNumber, mNumPhones);
            mServiceState = copyOf(mServiceState, mNumPhones);
            mVoiceActivationState = copyOf(mVoiceActivationState, mNumPhones);
            mDataActivationState = copyOf(mDataActivationState, mNumPhones);
            mUserMobileDataState = copyOf(mUserMobileDataState, mNumPhones);
            if (mSignalStrength != null) {
                mSignalStrength = copyOf(mSignalStrength, mNumPhones);
            } else {
                mSignalStrength = new SignalStrength[mNumPhones];
            }
            mMessageWaiting = copyOf(mMessageWaiting, mNumPhones);
            mCallForwarding = copyOf(mCallForwarding, mNumPhones);
            mCellIdentity = copyOf(mCellIdentity, mNumPhones);
            mSrvccState = copyOf(mSrvccState, mNumPhones);
            mPreciseCallState = copyOf(mPreciseCallState, mNumPhones);
            mForegroundCallState = copyOf(mForegroundCallState, mNumPhones);
            mBackgroundCallState = copyOf(mBackgroundCallState, mNumPhones);
            mRingingCallState = copyOf(mRingingCallState, mNumPhones);
            mCallDisconnectCause = copyOf(mCallDisconnectCause, mNumPhones);
            mCallPreciseDisconnectCause = copyOf(mCallPreciseDisconnectCause, mNumPhones);
            mCallQuality = copyOf(mCallQuality, mNumPhones);
            mCallNetworkType = copyOf(mCallNetworkType, mNumPhones);
            mOutgoingCallEmergencyNumber = copyOf(mOutgoingCallEmergencyNumber, mNumPhones);
            mOutgoingSmsEmergencyNumber = copyOf(mOutgoingSmsEmergencyNumber, mNumPhones);
            mTelephonyDisplayInfos = copyOf(mTelephonyDisplayInfos, mNumPhones);
            mCarrierNetworkChangeState = copyOf(mCarrierNetworkChangeState, mNumPhones);
            mIsDataEnabled = copyOf(mIsDataEnabled, mNumPhones);
            mDataEnabledReason = copyOf(mDataEnabledReason, mNumPhones);
            mAllowedNetworkTypeReason = copyOf(mAllowedNetworkTypeReason, mNumPhones);
            mAllowedNetworkTypeValue = copyOf(mAllowedNetworkTypeValue, mNumPhones);
            mECBMReason = copyOf(mECBMReason, mNumPhones);
            mECBMStarted = copyOf(mECBMStarted, mNumPhones);
            mSCBMReason = copyOf(mSCBMReason, mNumPhones);
            mSCBMStarted = copyOf(mSCBMStarted, mNumPhones);
            // ds -> ss switch.
            if (mNumPhones < oldNumPhones) {
                cutListToSize(mCellInfo, mNumPhones);
                cutListToSize(mImsReasonInfo, mNumPhones);
                cutListToSize(mPreciseDataConnectionStates, mNumPhones);
                cutListToSize(mBarringInfo, mNumPhones);
                cutListToSize(mPhysicalChannelConfigs, mNumPhones);
                cutListToSize(mLinkCapacityEstimateLists, mNumPhones);
                cutListToSize(mCarrierPrivilegeStates, mNumPhones);
                cutListToSize(mCarrierServiceStates, mNumPhones);
                cutListToSize(mCallStateLists, mNumPhones);
                cutListToSize(mMediaQualityStatus, mNumPhones);
                return;
            }

            // mNumPhones > oldNumPhones: ss -> ds switch
            for (int i = oldNumPhones; i < mNumPhones; i++) {
                mCallState[i] = TelephonyManager.CALL_STATE_IDLE;
                mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
                mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
                mVoiceActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
                mDataActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
                mCallIncomingNumber[i] = "";
                mServiceState[i] = new ServiceState();
                mSignalStrength[i] = null;
                mUserMobileDataState[i] = false;
                mMessageWaiting[i] = false;
                mCallForwarding[i] = false;
                mCellIdentity[i] = null;
                mCellInfo.add(i, Collections.EMPTY_LIST);
                mImsReasonInfo.add(i, null);
                mSrvccState[i] = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;
                mCallDisconnectCause[i] = DisconnectCause.NOT_VALID;
                mCallPreciseDisconnectCause[i] = PreciseDisconnectCause.NOT_VALID;
                mCallQuality[i] = createCallQuality();
                mMediaQualityStatus.add(i, new SparseArray<>());
                mCallStateLists.add(i, new ArrayList<>());
                mCallNetworkType[i] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                mPreciseCallState[i] = createPreciseCallState();
                mRingingCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
                mForegroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
                mBackgroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
                mPreciseDataConnectionStates.add(new ArrayMap<>());
                mBarringInfo.add(i, new BarringInfo());
                mCarrierNetworkChangeState[i] = false;
                mTelephonyDisplayInfos[i] = null;
                mIsDataEnabled[i] = false;
                mDataEnabledReason[i] = TelephonyManager.DATA_ENABLED_REASON_USER;
                mPhysicalChannelConfigs.add(i, new ArrayList<>());
                mAllowedNetworkTypeReason[i] = -1;
                mAllowedNetworkTypeValue[i] = -1;
                mLinkCapacityEstimateLists.add(i, INVALID_LCE_LIST);
                mCarrierPrivilegeStates.add(i, new Pair<>(Collections.emptyList(), new int[0]));
                mCarrierServiceStates.add(i, new Pair<>(null, Process.INVALID_UID));
                mECBMReason[i] = TelephonyManager.STOP_REASON_UNKNOWN;
                mECBMStarted[i] = false;
                mSCBMReason[i] = TelephonyManager.STOP_REASON_UNKNOWN;
                mSCBMStarted[i] = false;
            }
        }
    }

    private void cutListToSize(List list, int size) {
        if (list == null) return;

        while (list.size() > size) {
            list.remove(list.size() - 1);
        }
    }

    // we keep a copy of all of the state so we can send it out when folks
    // register for it
    //
    // In these calls we call with the lock held. This is safe becasuse remote
    // calls go through a oneway interface and local calls going through a
    // handler before they get to app code.

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public TelephonyRegistry(Context context, ConfigurationProvider configurationProvider) {
        mContext = context;
        mConfigurationProvider = configurationProvider;
        mBatteryStats = BatteryStatsService.getService();

        int numPhones = getTelephonyManager().getActiveModemCount();
        if (DBG) log("TelephonyRegistry: ctor numPhones=" + numPhones);
        mNumPhones = numPhones;
        mCallState = new int[numPhones];
        mDataActivity = new int[numPhones];
        mDataConnectionState = new int[numPhones];
        mDataConnectionNetworkType = new int[numPhones];
        mCallIncomingNumber = new String[numPhones];
        mServiceState = new ServiceState[numPhones];
        mVoiceActivationState = new int[numPhones];
        mDataActivationState = new int[numPhones];
        mUserMobileDataState = new boolean[numPhones];
        mSignalStrength = new SignalStrength[numPhones];
        mMessageWaiting = new boolean[numPhones];
        mCallForwarding = new boolean[numPhones];
        mCellIdentity = new CellIdentity[numPhones];
        mSrvccState = new int[numPhones];
        mPreciseCallState = new PreciseCallState[numPhones];
        mForegroundCallState = new int[numPhones];
        mBackgroundCallState = new int[numPhones];
        mRingingCallState = new int[numPhones];
        mCallDisconnectCause = new int[numPhones];
        mCallPreciseDisconnectCause = new int[numPhones];
        mCallQuality = new CallQuality[numPhones];
        mMediaQualityStatus = new ArrayList<>();
        mCallNetworkType = new int[numPhones];
        mCallStateLists = new ArrayList<>();
        mPreciseDataConnectionStates = new ArrayList<>();
        mCellInfo = new ArrayList<>(numPhones);
        mImsReasonInfo = new ArrayList<>();
        mEmergencyNumberList = new HashMap<>();
        mOutgoingCallEmergencyNumber = new EmergencyNumber[numPhones];
        mOutgoingSmsEmergencyNumber = new EmergencyNumber[numPhones];
        mBarringInfo = new ArrayList<>();
        mCarrierNetworkChangeState = new boolean[numPhones];
        mTelephonyDisplayInfos = new TelephonyDisplayInfo[numPhones];
        mPhysicalChannelConfigs = new ArrayList<>();
        mAllowedNetworkTypeReason = new int[numPhones];
        mAllowedNetworkTypeValue = new long[numPhones];
        mIsDataEnabled = new boolean[numPhones];
        mDataEnabledReason = new int[numPhones];
        mLinkCapacityEstimateLists = new ArrayList<>();
        mCarrierPrivilegeStates = new ArrayList<>();
        mCarrierServiceStates = new ArrayList<>();
        mECBMReason = new int[numPhones];
        mECBMStarted = new boolean[numPhones];
        mSCBMReason = new int[numPhones];
        mSCBMStarted = new boolean[numPhones];

        for (int i = 0; i < numPhones; i++) {
            mCallState[i] =  TelephonyManager.CALL_STATE_IDLE;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
            mVoiceActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mDataActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mCallIncomingNumber[i] =  "";
            mServiceState[i] =  new ServiceState();
            mSignalStrength[i] =  null;
            mUserMobileDataState[i] = false;
            mMessageWaiting[i] =  false;
            mCallForwarding[i] =  false;
            mCellIdentity[i] = null;
            mCellInfo.add(i, Collections.EMPTY_LIST);
            mImsReasonInfo.add(i, new ImsReasonInfo());
            mSrvccState[i] = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;
            mCallDisconnectCause[i] = DisconnectCause.NOT_VALID;
            mCallPreciseDisconnectCause[i] = PreciseDisconnectCause.NOT_VALID;
            mCallQuality[i] = createCallQuality();
            mMediaQualityStatus.add(i, new SparseArray<>());
            mCallStateLists.add(i, new ArrayList<>());
            mCallNetworkType[i] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            mPreciseCallState[i] = createPreciseCallState();
            mRingingCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mForegroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mBackgroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mPreciseDataConnectionStates.add(new ArrayMap<>());
            mBarringInfo.add(i, new BarringInfo());
            mCarrierNetworkChangeState[i] = false;
            mTelephonyDisplayInfos[i] = null;
            mIsDataEnabled[i] = false;
            mDataEnabledReason[i] = TelephonyManager.DATA_ENABLED_REASON_USER;
            mPhysicalChannelConfigs.add(i, new ArrayList<>());
            mAllowedNetworkTypeReason[i] = -1;
            mAllowedNetworkTypeValue[i] = -1;
            mLinkCapacityEstimateLists.add(i, INVALID_LCE_LIST);
            mCarrierPrivilegeStates.add(i, new Pair<>(Collections.emptyList(), new int[0]));
            mCarrierServiceStates.add(i, new Pair<>(null, Process.INVALID_UID));
            mECBMReason[i] = TelephonyManager.STOP_REASON_UNKNOWN;
            mECBMStarted[i] = false;
            mSCBMReason[i] = TelephonyManager.STOP_REASON_UNKNOWN;
            mSCBMStarted[i] = false;
        }

        mAppOps = mContext.getSystemService(AppOpsManager.class);
    }

    public void systemRunning() {
        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        filter.addAction(ACTION_MULTI_SIM_CONFIG_CHANGED);
        log("systemRunning register for intents");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    //helper function to determine if limit on num listeners applies to callingUid
    private boolean doesLimitApplyForListeners(int callingUid, int exemptUid) {
        return (callingUid != Process.SYSTEM_UID
                && callingUid != Process.PHONE_UID
                && callingUid != exemptUid);
    }

    @Override
    public void addOnSubscriptionsChangedListener(String callingPackage, String callingFeatureId,
            IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen oscl: E pkg=" + pii(callingPackage) + " uid=" + Binder.getCallingUid()
                    + " myUserId=" + UserHandle.myUserId() + " callerUserId=" + callerUserId
                    + " callback=" + callback + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            boolean doesLimitApply = doesLimitApplyForListeners(Binder.getCallingUid(),
                    Process.myUid());
            Record r = add(b, Binder.getCallingUid(), Binder.getCallingPid(), doesLimitApply); //

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.eventList = new ArraySet<>();
            if (DBG) {
                log("listen oscl:  Register r=" + r);
            }
            // Always notify when registration occurs if there has been a notification.
            if (mHasNotifySubscriptionInfoChangedOccurred) {
                try {
                    if (VDBG) log("listen oscl: send to r=" + r);
                    r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    if (VDBG) log("listen oscl: sent to r=" + r);
                } catch (RemoteException e) {
                    if (VDBG) log("listen oscl: remote exception sending to r=" + r + " e=" + e);
                    remove(r.binder);
                }
            } else {
                log("listen oscl: mHasNotifySubscriptionInfoChangedOccurred==false no callback");
            }
        }
    }

    @Override
    public void removeOnSubscriptionsChangedListener(String pkgForDebug,
            IOnSubscriptionsChangedListener callback) {
        if (DBG) log("listen oscl: Unregister");
        remove(callback.asBinder());
    }


    @Override
    public void addOnOpportunisticSubscriptionsChangedListener(String callingPackage,
            String callingFeatureId, IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen ooscl: E pkg=" + pii(callingPackage) + " uid=" + Binder.getCallingUid()
                    + " myUserId=" + UserHandle.myUserId() + " callerUserId=" + callerUserId
                    + " callback=" + callback + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            boolean doesLimitApply = doesLimitApplyForListeners(Binder.getCallingUid(),
                    Process.myUid());
            Record r = add(b, Binder.getCallingUid(), Binder.getCallingPid(), doesLimitApply); //

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onOpportunisticSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.eventList = new ArraySet<>();
            if (DBG) {
                log("listen ooscl:  Register r=" + r);
            }
            // Always notify when registration occurs if there has been a notification.
            if (mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                try {
                    if (VDBG) log("listen ooscl: send to r=" + r);
                    r.onOpportunisticSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    if (VDBG) log("listen ooscl: sent to r=" + r);
                } catch (RemoteException e) {
                    if (VDBG) log("listen ooscl: remote exception sending to r=" + r + " e=" + e);
                    remove(r.binder);
                }
            } else {
                log("listen ooscl: hasNotifyOpptSubInfoChangedOccurred==false no callback");
            }
        }
    }

    @Override
    public void notifySubscriptionInfoChanged() {
        if (VDBG) log("notifySubscriptionInfoChanged:");
        if (!checkNotifyPermission("notifySubscriptionInfoChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (!mHasNotifySubscriptionInfoChangedOccurred) {
                log("notifySubscriptionInfoChanged: first invocation mRecords.size="
                        + mRecords.size());
            }
            mHasNotifySubscriptionInfoChangedOccurred = true;
            mRemoveList.clear();
            for (Record r : mRecords) {
                if (r.matchOnSubscriptionsChangedListener()) {
                    try {
                        if (VDBG) log("notifySubscriptionInfoChanged: call osc to r=" + r);
                        r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                        if (VDBG) log("notifySubscriptionInfoChanged: done osc to r=" + r);
                    } catch (RemoteException ex) {
                        if (VDBG) log("notifySubscriptionInfoChanged: RemoteException r=" + r);
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyOpportunisticSubscriptionInfoChanged() {
        if (VDBG) log("notifyOpptSubscriptionInfoChanged:");
        if (!checkNotifyPermission("notifyOpportunisticSubscriptionInfoChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (!mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                log("notifyOpptSubscriptionInfoChanged: first invocation mRecords.size="
                        + mRecords.size());
            }
            mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = true;
            mRemoveList.clear();
            for (Record r : mRecords) {
                if (r.matchOnOpportunisticSubscriptionsChangedListener()) {
                    try {
                        if (VDBG) log("notifyOpptSubChanged: call oosc to r=" + r);
                        r.onOpportunisticSubscriptionsChangedListenerCallback
                                .onSubscriptionsChanged();
                        if (VDBG) log("notifyOpptSubChanged: done oosc to r=" + r);
                    } catch (RemoteException ex) {
                        if (VDBG) log("notifyOpptSubChanged: RemoteException r=" + r);
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void listenWithEventList(boolean renounceFineLocationAccess,
            boolean renounceCoarseLocationAccess, int subId, String callingPackage,
            String callingFeatureId, IPhoneStateListener callback,
            int[] events, boolean notifyNow) {
        Set<Integer> eventList = Arrays.stream(events).boxed().collect(Collectors.toSet());
        listen(renounceFineLocationAccess, renounceCoarseLocationAccess, callingPackage,
                callingFeatureId, callback, eventList, notifyNow, subId);
    }

    private void listen(boolean renounceFineLocationAccess,
            boolean renounceCoarseLocationAccess, String callingPackage,
            @Nullable String callingFeatureId, IPhoneStateListener callback,
            Set<Integer> events, boolean notifyNow, int subId) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        String str = "listen: E pkg=" + pii(callingPackage) + " uid=" + Binder.getCallingUid()
                + " events=" + events + " notifyNow=" + notifyNow
                + " subId=" + subId + " myUserId=" + UserHandle.myUserId()
                + " callerUserId=" + callerUserId;
        mListenLog.log(str);
        if (VDBG) {
            log(str);
        }

        if (events.isEmpty()) {
            if (DBG) {
                log("listen: Unregister");
            }
            events.clear();
            remove(callback.asBinder());
            return;
        }

        // Checks permission and throws SecurityException for disallowed operations. For pre-M
        // apps whose runtime permission has been revoked, we return immediately to skip sending
        // events to the app without crashing it.
        if (!checkListenerPermission(events, subId, callingPackage, callingFeatureId, "listen")) {
            return;
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            boolean doesLimitApply = doesLimitApplyForListeners(Binder.getCallingUid(),
                    Process.myUid());
            Record r = add(b, Binder.getCallingUid(), Binder.getCallingPid(), doesLimitApply);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.callback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.renounceCoarseLocationAccess = renounceCoarseLocationAccess;
            r.renounceFineLocationAccess = renounceFineLocationAccess;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            // Legacy applications pass SubscriptionManager.DEFAULT_SUB_ID,
            // force all illegal subId to SubscriptionManager.DEFAULT_SUB_ID
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                if (DBG) {
                    log("invalid subscription id, use default id");
                }
                r.subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
            } else {//APP specify subID
                r.subId = subId;
            }
            r.phoneId = getPhoneIdFromSubId(r.subId);
            r.eventList = events;

            if (DBG) {
                log("listen:  Register r=" + r + " r.subId=" + r.subId + " r.phoneId=" + r.phoneId);
            }
            if (notifyNow && validatePhoneId(r.phoneId)) {
                if (events.contains(TelephonyCallback.EVENT_SERVICE_STATE_CHANGED)){
                    try {
                        if (VDBG) log("listen: call onSSC state=" + mServiceState[r.phoneId]);
                        ServiceState rawSs = new ServiceState(mServiceState[r.phoneId]);
                        if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                            r.callback.onServiceStateChanged(rawSs);
                        } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                            r.callback.onServiceStateChanged(
                                    rawSs.createLocationInfoSanitizedCopy(false));
                        } else {
                            r.callback.onServiceStateChanged(
                                    rawSs.createLocationInfoSanitizedCopy(true));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED)) {
                    try {
                        if (mSignalStrength[r.phoneId] != null) {
                            int gsmSignalStrength = mSignalStrength[r.phoneId]
                                    .getGsmSignalStrength();
                            r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                                    : gsmSignalStrength));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)) {
                    try {
                        r.callback.onMessageWaitingIndicatorChanged(
                                mMessageWaiting[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)) {
                    try {
                        r.callback.onCallForwardingIndicatorChanged(
                                mCallForwarding[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (validateEventAndUserLocked(
                        r, TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)) {
                    try {
                        if (DBG_LOC) log("listen: mCellIdentity = " + mCellIdentity[r.phoneId]);
                        if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                            // null will be translated to empty CellLocation object in client.
                            r.callback.onCellLocationChanged(mCellIdentity[r.phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)) {
                    try {
                        r.callback.onLegacyCallStateChanged(mCallState[r.phoneId],
                                getCallIncomingNumber(r, r.phoneId));
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CALL_STATE_CHANGED)) {
                    try {
                        r.callback.onCallStateChanged(mCallState[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED)) {
                    try {
                        r.callback.onDataConnectionStateChanged(mDataConnectionState[r.phoneId],
                                mDataConnectionNetworkType[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED)) {
                    try {
                        r.callback.onDataActivity(mDataActivity[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED)) {
                    try {
                        if (mSignalStrength[r.phoneId] != null) {
                            r.callback.onSignalStrengthsChanged(mSignalStrength[r.phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (validateEventAndUserLocked(
                        r, TelephonyCallback.EVENT_CELL_INFO_CHANGED)) {
                    try {
                        if (DBG_LOC) {
                            log("listen: mCellInfo[" + r.phoneId + "] = "
                                    + mCellInfo.get(r.phoneId));
                        }
                        if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                            r.callback.onCellInfoChanged(mCellInfo.get(r.phoneId));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED)) {
                    try {
                        r.callback.onPreciseCallStateChanged(mPreciseCallState[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED)) {
                    try {
                        r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause[r.phoneId],
                                mCallPreciseDisconnectCause[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED)) {
                    ImsReasonInfo imsReasonInfo = mImsReasonInfo.get(r.phoneId);
                    if (imsReasonInfo != null) {
                        try {
                            r.callback.onImsCallDisconnectCauseChanged(imsReasonInfo);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED)) {
                    try {
                        for (PreciseDataConnectionState pdcs
                                : mPreciseDataConnectionStates.get(r.phoneId).values()) {
                            r.callback.onPreciseDataConnectionStateChanged(pdcs);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED)) {
                    try {
                        r.callback.onCarrierNetworkChange(mCarrierNetworkChangeState[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED)) {
                    try {
                        r.callback.onVoiceActivationStateChanged(
                                mVoiceActivationState[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED)) {
                    try {
                        r.callback.onDataActivationStateChanged(mDataActivationState[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED)) {
                    try {
                        r.callback.onUserMobileDataStateChanged(mUserMobileDataState[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)) {
                    try {
                        if (mTelephonyDisplayInfos[r.phoneId] != null) {
                            r.callback.onDisplayInfoChanged(mTelephonyDisplayInfos[r.phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED)) {
                    try {
                        r.callback.onEmergencyNumberListChanged(mEmergencyNumberList);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_PHONE_CAPABILITY_CHANGED)) {
                    try {
                        r.callback.onPhoneCapabilityChanged(mPhoneCapability);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED)) {
                    try {
                        r.callback.onActiveDataSubIdChanged(mActiveDataSubId);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED)) {
                    try {
                        r.callback.onRadioPowerStateChanged(mRadioPowerState);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_SRVCC_STATE_CHANGED)) {
                    try {
                        r.callback.onSrvccStateChanged(mSrvccState[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED)) {
                    try {
                        r.callback.onCallStatesChanged(mCallStateLists.get(r.phoneId));
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_BARRING_INFO_CHANGED)) {
                    BarringInfo barringInfo = mBarringInfo.get(r.phoneId);
                    if (VDBG) {
                        log("listen: call onBarringInfoChanged=" + barringInfo);
                    }
                    if (barringInfo != null) {
                        BarringInfo biNoLocation = barringInfo.createLocationInfoSanitizedCopy();

                        try {
                            r.callback.onBarringInfoChanged(
                                    checkFineLocationAccess(r, Build.VERSION_CODES.BASE)
                                            ? barringInfo : biNoLocation);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED)) {
                    try {
                        r.callback.onPhysicalChannelConfigChanged(
                                shouldSanitizeLocationForPhysicalChannelConfig(r)
                                        ? getLocationSanitizedConfigs(
                                                mPhysicalChannelConfigs.get(r.phoneId))
                                        : mPhysicalChannelConfigs.get(r.phoneId));
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_DATA_ENABLED_CHANGED)) {
                    try {
                        r.callback.onDataEnabledChanged(
                                mIsDataEnabled[r.phoneId], mDataEnabledReason[r.phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED)) {
                    try {
                        if (mLinkCapacityEstimateLists.get(r.phoneId) != null) {
                            r.callback.onLinkCapacityEstimateChanged(mLinkCapacityEstimateLists
                                    .get(r.phoneId));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_MEDIA_QUALITY_STATUS_CHANGED)) {
                    CallState callState = null;
                    for (CallState cs : mCallStateLists.get(r.phoneId)) {
                        if (cs.getCallState() == PreciseCallState.PRECISE_CALL_STATE_ACTIVE) {
                            callState = cs;
                            break;
                        }
                    }
                    if (callState != null) {
                        String callId = callState.getImsCallSessionId();
                        try {
                            MediaQualityStatus status = mMediaQualityStatus.get(r.phoneId).get(
                                    MediaQualityStatus.MEDIA_SESSION_TYPE_AUDIO);
                            if (status != null && status.getCallSessionId().equals(callId)) {
                                r.callback.onMediaQualityStatusChanged(status);
                            }
                            status = mMediaQualityStatus.get(r.phoneId).get(
                                    MediaQualityStatus.MEDIA_SESSION_TYPE_VIDEO);
                            if (status != null && status.getCallSessionId().equals(callId)) {
                                r.callback.onMediaQualityStatusChanged(status);
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                    try {
                        boolean ecbmStarted = mECBMStarted[r.phoneId];
                        if (ecbmStarted) {
                            r.callback.onCallBackModeStarted(
                                    TelephonyManager.EMERGENCY_CALLBACK_MODE_CALL);
                        } else {
                            r.callback.onCallBackModeStopped(
                                    TelephonyManager.EMERGENCY_CALLBACK_MODE_CALL,
                                    mECBMReason[r.phoneId]);
                        }

                        boolean scbmStarted = mSCBMStarted[r.phoneId];
                        if (scbmStarted) {
                            r.callback.onCallBackModeStarted(
                                    TelephonyManager.EMERGENCY_CALLBACK_MODE_SMS);
                        } else {
                            r.callback.onCallBackModeStopped(
                                    TelephonyManager.EMERGENCY_CALLBACK_MODE_SMS,
                                    mSCBMReason[r.phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
            }
        }
    }

    private String getCallIncomingNumber(Record record, int phoneId) {
        // Only reveal the incoming number if the record has read call log permission.
        return record.canReadCallLog() ? mCallIncomingNumber[phoneId] : "";
    }

    private Record add(IBinder binder, int callingUid, int callingPid, boolean doesLimitApply) {
        Record r;

        synchronized (mRecords) {
            final int N = mRecords.size();
            // While iterating through the records, keep track of how many we have from this pid.
            int numRecordsForPid = 0;
            for (int i = 0; i < N; i++) {
                r = mRecords.get(i);
                if (binder == r.binder) {
                    // Already existed.
                    return r;
                }
                if (r.callerPid == callingPid) {
                    numRecordsForPid++;
                }
            }
            // If we've exceeded the limit for registrations, log an error and quit.
            int registrationLimit = mConfigurationProvider.getRegistrationLimit();

            if (doesLimitApply
                    && registrationLimit >= 1
                    && numRecordsForPid >= registrationLimit) {
                String errorMsg = "Pid " + callingPid + " has exceeded the number of permissible"
                        + " registered listeners. Ignoring request to add.";
                loge(errorMsg);
                if (mConfigurationProvider
                        .isRegistrationLimitEnabledInPlatformCompat(callingUid)) {
                    throw new IllegalStateException(errorMsg);
                }
            } else if (numRecordsForPid
                    >= TelephonyCallback.DEFAULT_PER_PID_REGISTRATION_LIMIT / 2) {
                // Log the warning independently of the dynamically set limit -- apps shouldn't be
                // doing this regardless of whether we're throwing them an exception for it.
                Rlog.w(TAG, "Pid " + callingPid + " has exceeded half the number of permissible"
                        + " registered listeners. Now at " + numRecordsForPid);
            }

            r = new Record();
            r.binder = binder;
            r.deathRecipient = new TelephonyRegistryDeathRecipient(binder);

            try {
                binder.linkToDeath(r.deathRecipient, 0);
            } catch (RemoteException e) {
                if (VDBG) log("LinkToDeath remote exception sending to r=" + r + " e=" + e);
                // Binder already died. Return null.
                return null;
            }

            mRecords.add(r);
            if (DBG) log("add new record");
        }

        return r;
    }

    private void remove(IBinder binder) {
        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            for (int i = 0; i < recordCount; i++) {
                Record r = mRecords.get(i);
                if (r.binder == binder) {
                    if (DBG) {
                        log("remove: binder=" + binder + " r.callingPackage " + r.callingPackage
                                + " r.callback " + r.callback);
                    }

                    if (r.deathRecipient != null) {
                        try {
                            binder.unlinkToDeath(r.deathRecipient, 0);
                        } catch (NoSuchElementException e) {
                            if (VDBG) log("UnlinkToDeath NoSuchElementException sending to r="
                                    + r + " e=" + e);
                        }
                    }

                    mRecords.remove(i);
                    return;
                }
            }
        }
    }

    public void notifyCallStateForAllSubs(int state, String phoneNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }

        if (VDBG) {
            log("notifyCallStateForAllSubs: state=" + state + " phoneNumber=" + phoneNumber);
        }

        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)
                        && (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                    try {
                        // Ensure the listener has read call log permission; if they do not return
                        // an empty phone number.
                        // This is ONLY for legacy onCallStateChanged in PhoneStateListener.
                        String phoneNumberOrEmpty = r.canReadCallLog() ? phoneNumber : "";
                        r.callback.onLegacyCallStateChanged(state, phoneNumberOrEmpty);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }

                if (r.matchTelephonyCallbackEvent(TelephonyCallback.EVENT_CALL_STATE_CHANGED)
                        && (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                    try {
                        // The new callback does NOT provide the phone number.
                        r.callback.onCallStateChanged(state);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }

        // Called only by Telecomm to communicate call state across different phone accounts. So
        // there is no need to add a valid subId or slotId.
        broadcastCallStateChanged(state, phoneNumber,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    public void notifyCallState(int phoneId, int subId, int state, String incomingNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallState: subId=" + subId
                + " state=" + state + " incomingNumber=" + incomingNumber);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallState[phoneId] = state;
                mCallIncomingNumber[phoneId] = incomingNumber;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)
                            && (r.subId == subId)
                            && (r.subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                        try {
                            // Only the legacy PhoneStateListener receives the phone number.
                            String incomingNumberOrEmpty = getCallIncomingNumber(r, phoneId);
                            r.callback.onLegacyCallStateChanged(state, incomingNumberOrEmpty);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (r.matchTelephonyCallbackEvent(TelephonyCallback.EVENT_CALL_STATE_CHANGED)
                            && (r.subId == subId)
                            && (r.subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                        try {
                            // The phone number is not included in the new call state changed
                            // listener.
                            r.callback.onCallStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastCallStateChanged(state, incomingNumber, phoneId, subId);
    }

    public void notifyServiceStateForPhoneId(int phoneId, int subId, ServiceState state) {
        if (!checkNotifyPermission("notifyServiceState()")){
            return;
        }

        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            synchronized (mRecords) {
                String str = "notifyServiceStateForSubscriber: subId=" + subId + " phoneId="
                        + phoneId + " state=" + state;
                if (VDBG) {
                    log(str);
                }
                mLocalLog.log(str);
                // for service state updates, don't notify clients when subId is invalid. This
                // prevents us from sending incorrect notifications like b/133140128
                // In the future, we can remove this logic for every notification here and add a
                // callback so listeners know when their PhoneStateListener's subId becomes invalid,
                // but for now we use the simplest fix.
                if (validatePhoneId(phoneId) && SubscriptionManager.isValidSubscriptionId(subId)) {
                    mServiceState[phoneId] = state;

                    for (Record r : mRecords) {
                        if (VDBG) {
                            log("notifyServiceStateForSubscriber: r=" + r + " subId=" + subId
                                    + " phoneId=" + phoneId + " state=" + state);
                        }
                        if (r.matchTelephonyCallbackEvent(
                                TelephonyCallback.EVENT_SERVICE_STATE_CHANGED)
                                && idMatch(r, subId, phoneId)) {

                            try {
                                ServiceState stateToSend;
                                if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                    stateToSend = new ServiceState(state);
                                } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                                    stateToSend = state.createLocationInfoSanitizedCopy(false);
                                } else {
                                    stateToSend = state.createLocationInfoSanitizedCopy(true);
                                }
                                if (DBG) {
                                    log("notifyServiceStateForSubscriber: callback.onSSC r=" + r
                                            + " subId=" + subId + " phoneId=" + phoneId
                                            + " state=" + stateToSend);
                                }
                                r.callback.onServiceStateChanged(stateToSend);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                } else {
                    log("notifyServiceStateForSubscriber: INVALID phoneId=" + phoneId
                            + " or subId=" + subId);
                }
                handleRemoveListLocked();
            }
            broadcastServiceStateChanged(state, phoneId, subId);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    public void notifySimActivationStateChangedForPhoneId(int phoneId, int subId,
            int activationType, int activationState) {
        if (!checkNotifyPermission("notifySimActivationState()")){
            return;
        }
        if (VDBG) {
            log("notifySimActivationStateForPhoneId: subId=" + subId + " phoneId=" + phoneId
                    + "type=" + activationType + " state=" + activationState);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                switch (activationType) {
                    case SIM_ACTIVATION_TYPE_VOICE:
                        mVoiceActivationState[phoneId] = activationState;
                        break;
                    case SIM_ACTIVATION_TYPE_DATA:
                        mDataActivationState[phoneId] = activationState;
                        break;
                    default:
                        return;
                }
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifySimActivationStateForPhoneId: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + "type=" + activationType
                                + " state=" + activationState);
                    }
                    try {
                        if ((activationType == SIM_ACTIVATION_TYPE_VOICE)
                                && r.matchTelephonyCallbackEvent(
                                        TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED)
                                && idMatch(r, subId, phoneId)) {
                            if (DBG) {
                                log("notifyVoiceActivationStateForPhoneId: callback.onVASC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + activationState);
                            }
                            r.callback.onVoiceActivationStateChanged(activationState);
                        }
                        if ((activationType == SIM_ACTIVATION_TYPE_DATA)
                                && r.matchTelephonyCallbackEvent(
                                        TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED)
                                && idMatch(r, subId, phoneId)) {
                            if (DBG) {
                                log("notifyDataActivationStateForPhoneId: callback.onDASC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + activationState);
                            }
                            r.callback.onDataActivationStateChanged(activationState);
                        }
                    }  catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            } else {
                log("notifySimActivationStateForPhoneId: INVALID phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
    }

    public void notifySignalStrengthForPhoneId(int phoneId, int subId,
                SignalStrength signalStrength) {
        if (!checkNotifyPermission("notifySignalStrength()")) {
            return;
        }
        if (VDBG) {
            log("notifySignalStrengthForPhoneId: subId=" + subId
                +" phoneId=" + phoneId + " signalStrength=" + signalStrength);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                if (VDBG) log("notifySignalStrengthForPhoneId: valid phoneId=" + phoneId);
                mSignalStrength[phoneId] = signalStrength;
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifySignalStrengthForPhoneId: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " ss=" + signalStrength);
                    }
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            if (DBG) {
                                log("notifySignalStrengthForPhoneId: callback.onSsS r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " ss=" + signalStrength);
                            }
                            r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            int gsmSignalStrength = signalStrength.getGsmSignalStrength();
                            int ss = (gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
                            if (DBG) {
                                log("notifySignalStrengthForPhoneId: callback.onSS r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " gsmSS=" + gsmSignalStrength + " ss=" + ss);
                            }
                            r.callback.onSignalStrengthChanged(ss);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifySignalStrengthForPhoneId: invalid phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
        broadcastSignalStrengthChanged(signalStrength, phoneId, subId);
    }

    @Override
    public void notifyCarrierNetworkChange(boolean active) {
        // only CarrierService with carrier privilege rule should have the permission
        int[] subIds = Arrays.stream(SubscriptionManager.from(mContext)
                    .getCompleteActiveSubscriptionIdList())
                    .filter(i -> TelephonyPermissions.checkCarrierPrivilegeForSubId(mContext,
                            i)).toArray();
        if (ArrayUtils.isEmpty(subIds)) {
            loge("notifyCarrierNetworkChange without carrier privilege");
            // the active subId does not have carrier privilege.
            throw new SecurityException("notifyCarrierNetworkChange without carrier privilege");
        }

        for (int subId : subIds) {
            notifyCarrierNetworkChangeWithPermission(subId, active);
        }
    }

    @Override
    public void notifyCarrierNetworkChangeWithSubId(int subId, boolean active) {
        if (!TelephonyPermissions.checkCarrierPrivilegeForSubId(mContext, subId)) {
            throw new SecurityException(
                    "notifyCarrierNetworkChange without carrier privilege on subId " + subId);
        }

        notifyCarrierNetworkChangeWithPermission(subId, active);
    }

    private void notifyCarrierNetworkChangeWithPermission(int subId, boolean active) {
        synchronized (mRecords) {
            int phoneId = getPhoneIdFromSubId(subId);
            mCarrierNetworkChangeState[phoneId] = active;

            if (VDBG) {
                log("notifyCarrierNetworkChange: active=" + active + "subId: " + subId);
            }
            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED)
                        && idMatch(r, subId, phoneId)) {
                    try {
                        r.callback.onCarrierNetworkChange(active);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyCellInfo(List<CellInfo> cellInfo) {
         notifyCellInfoForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cellInfo);
    }

    public void notifyCellInfoForSubscriber(int subId, List<CellInfo> cellInfo) {
        if (!checkNotifyPermission("notifyCellInfoForSubscriber()")) {
            return;
        }

        if (VDBG) {
            log("notifyCellInfoForSubscriber: subId=" + subId
                + " cellInfo=" + cellInfo);
        }

        if (cellInfo == null) {
            loge("notifyCellInfoForSubscriber() received a null list");
            cellInfo = Collections.EMPTY_LIST;
        }

        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCellInfo.set(phoneId, cellInfo);
                for (Record r : mRecords) {
                    if (validateEventAndUserLocked(
                            r, TelephonyCallback.EVENT_CELL_INFO_CHANGED)
                            && idMatch(r, subId, phoneId)
                            && (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                    && checkFineLocationAccess(r, Build.VERSION_CODES.Q))) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellInfoForSubscriber: mCellInfo=" + cellInfo
                                    + " r=" + r);
                            }
                            r.callback.onCellInfoChanged(cellInfo);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyMessageWaitingChangedForPhoneId(int phoneId, int subId, boolean mwi) {
        if (!checkNotifyPermission("notifyMessageWaitingChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyMessageWaitingChangedForSubscriberPhoneID: subId=" + phoneId
                + " mwi=" + mwi);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mMessageWaiting[phoneId] = mwi;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onMessageWaitingIndicatorChanged(mwi);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyUserMobileDataStateChangedForPhoneId(int phoneId, int subId, boolean state) {
        if (!checkNotifyPermission("notifyUserMobileDataStateChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyUserMobileDataStateChangedForSubscriberPhoneID: PhoneId=" + phoneId
                    + " subId=" + subId + " state=" + state);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mUserMobileDataState[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onUserMobileDataStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Notify display network info changed.
     *
     * @param phoneId Phone id
     * @param subId Subscription id
     * @param telephonyDisplayInfo Display network info
     *
     * @see PhoneStateListener#onDisplayInfoChanged(TelephonyDisplayInfo)
     */
    public void notifyDisplayInfoChanged(int phoneId, int subId,
                                         @NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
        if (!checkNotifyPermission("notifyDisplayInfoChanged()")) {
            return;
        }
        String str = "notifyDisplayInfoChanged: PhoneId=" + phoneId + " subId=" + subId
                + " telephonyDisplayInfo=" + telephonyDisplayInfo;
        if (VDBG) {
            log(str);
        }
        mLocalLog.log(str);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mTelephonyDisplayInfos[phoneId] = telephonyDisplayInfo;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            if (!mConfigurationProvider.isDisplayInfoNrAdvancedSupported(
                                    r.callingPackage, Binder.getCallingUserHandle())) {
                                r.callback.onDisplayInfoChanged(
                                        getBackwardCompatibleTelephonyDisplayInfo(
                                                telephonyDisplayInfo));
                            } else {
                                r.callback.onDisplayInfoChanged(telephonyDisplayInfo);
                            }
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    private TelephonyDisplayInfo getBackwardCompatibleTelephonyDisplayInfo(
            @NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
        int networkType = telephonyDisplayInfo.getNetworkType();
        int overrideNetworkType = telephonyDisplayInfo.getOverrideNetworkType();
        if (networkType == TelephonyManager.NETWORK_TYPE_NR) {
            overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
        } else if (networkType == TelephonyManager.NETWORK_TYPE_LTE
                && overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
            overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE;
        }
        boolean isRoaming = telephonyDisplayInfo.isRoaming();
        return new TelephonyDisplayInfo(networkType, overrideNetworkType, isRoaming);
    }

    public void notifyCallForwardingChanged(boolean cfi) {
        notifyCallForwardingChangedForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cfi);
    }

    public void notifyCallForwardingChangedForSubscriber(int subId, boolean cfi) {
        if (!checkNotifyPermission("notifyCallForwardingChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallForwardingChangedForSubscriber: subId=" + subId
                + " cfi=" + cfi);
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallForwarding[phoneId] = cfi;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onCallForwardingIndicatorChanged(cfi);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification to registrants about the data activity state.
     *
     * @param phoneId the phoneId carrying the data connection
     * @param subId the subscriptionId for the data connection
     * @param state indicates the latest data activity type
     * e.g.,{@link TelephonyManager#DATA_ACTIVITY_IN}
     *
     */
    public void notifyDataActivityForSubscriber(int phoneId, int subId, int state) {
        if (!checkNotifyPermission("notifyDataActivity()" )) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mDataActivity[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onDataActivity(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification to registrants that the data connection state has changed.
     *
     * @param phoneId the phoneId carrying the data connection
     * @param subId the subscriptionId for the data connection
     * @param preciseState a PreciseDataConnectionState that has info about the data connection
     */
    @Override
    public void notifyDataConnectionForSubscriber(int phoneId, int subId,
            @NonNull PreciseDataConnectionState preciseState) {
        if (!checkNotifyPermission("notifyDataConnection()" )) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId) && preciseState.getApnSetting() != null) {
                Pair<Integer, ApnSetting> key = Pair.create(preciseState.getTransportType(),
                        preciseState.getApnSetting());
                PreciseDataConnectionState oldState = mPreciseDataConnectionStates.get(phoneId)
                        .remove(key);
                if (!Objects.equals(oldState, preciseState)) {
                    for (Record r : mRecords) {
                        if (r.matchTelephonyCallbackEvent(
                                TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED)
                                && idMatch(r, subId, phoneId)) {
                            try {
                                r.callback.onPreciseDataConnectionStateChanged(preciseState);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                    handleRemoveListLocked();

                    broadcastDataConnectionStateChanged(phoneId, subId, preciseState);

                    String str = "notifyDataConnectionForSubscriber: phoneId=" + phoneId + " subId="
                            + subId + " " + preciseState;
                    log(str);
                    mLocalLog.log(str);
                }

                // If the state is disconnected, it would be the end of life cycle of a data
                // connection, so remove it from the cache.
                if (preciseState.getState() != TelephonyManager.DATA_DISCONNECTED) {
                    mPreciseDataConnectionStates.get(phoneId).put(key, preciseState);
                }

                // Note that below is just the workaround for reporting the correct data connection
                // state. The actual fix should be put in the new data stack in T.
                // TODO: Remove the code below in T.

                // Collect all possible candidate data connection state for internet. Key is the
                // data connection state, value is the precise data connection state.
                Map<Integer, PreciseDataConnectionState> internetConnections = new ArrayMap<>();
                if (preciseState.getState() == TelephonyManager.DATA_DISCONNECTED
                        && preciseState.getApnSetting().getApnTypes()
                        .contains(ApnSetting.TYPE_DEFAULT)) {
                    internetConnections.put(TelephonyManager.DATA_DISCONNECTED, preciseState);
                }
                for (Map.Entry<Pair<Integer, ApnSetting>, PreciseDataConnectionState> entry :
                        mPreciseDataConnectionStates.get(phoneId).entrySet()) {
                    if (entry.getKey().first == AccessNetworkConstants.TRANSPORT_TYPE_WWAN
                            && entry.getKey().second.getApnTypes()
                            .contains(ApnSetting.TYPE_DEFAULT)) {
                        internetConnections.put(entry.getValue().getState(), entry.getValue());
                    }
                }

                // If any internet data is in connected state, then report connected, then check
                // suspended, connecting, disconnecting, and disconnected. The order is very
                // important.
                int[] statesInPriority = new int[]{TelephonyManager.DATA_CONNECTED,
                        TelephonyManager.DATA_SUSPENDED, TelephonyManager.DATA_CONNECTING,
                        TelephonyManager.DATA_DISCONNECTING,
                        TelephonyManager.DATA_DISCONNECTED};
                int state = TelephonyManager.DATA_DISCONNECTED;
                int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                for (int s : statesInPriority) {
                    if (internetConnections.containsKey(s)) {
                        state = s;
                        networkType = internetConnections.get(s).getNetworkType();
                        break;
                    }
                }

                if (mDataConnectionState[phoneId] != state
                        || mDataConnectionNetworkType[phoneId] != networkType) {
                    String str = "onDataConnectionStateChanged("
                            + TelephonyUtils.dataStateToString(state)
                            + ", " + TelephonyManager.getNetworkTypeName(networkType)
                            + ") subId=" + subId + ", phoneId=" + phoneId;
                    log(str);
                    mLocalLog.log(str);
                    for (Record r : mRecords) {
                        if (r.matchTelephonyCallbackEvent(
                                TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED)
                                && idMatch(r, subId, phoneId)) {
                            try {
                                if (DBG) {
                                    log("Notify data connection state changed on sub: " + subId);
                                }
                                r.callback.onDataConnectionStateChanged(state, networkType);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }

                    mDataConnectionState[phoneId] = state;
                    mDataConnectionNetworkType[phoneId] = networkType;

                    handleRemoveListLocked();
                }
            }
        }
    }

    @Override
    public void notifyCellLocationForSubscriber(int subId, CellIdentity cellIdentity) {
        notifyCellLocationForSubscriber(subId, cellIdentity, false /* hasUserSwitched */);
    }

    private void notifyCellLocationForSubscriber(int subId, CellIdentity cellIdentity,
            boolean hasUserSwitched) {
        log("notifyCellLocationForSubscriber: subId=" + subId + " cellIdentity="
                + Rlog.pii(DBG || VDBG || DBG_LOC, cellIdentity));
        if (!checkNotifyPermission("notifyCellLocation()")) {
            return;
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)
                    && (hasUserSwitched || !Objects.equals(cellIdentity, mCellIdentity[phoneId]))) {
                mCellIdentity[phoneId] = cellIdentity;
                for (Record r : mRecords) {
                    if (validateEventAndUserLocked(
                            r, TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)
                            && idMatch(r, subId, phoneId)
                            && (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                    && checkFineLocationAccess(r, Build.VERSION_CODES.Q))) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellLocation: cellIdentity=" + cellIdentity
                                        + " r=" + r);
                            }
                            r.callback.onCellLocationChanged(cellIdentity);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification to registrants that the precise call state has changed.
     *
     * @param phoneId the phoneId carrying the data connection
     * @param subId the subscriptionId for the data connection
     * @param callStates Array of PreciseCallState of foreground, background & ringing calls.
     * @param imsCallIds Array of IMS call session ID{@link ImsCallSession#getCallId()} for
     *                   ringing, foreground & background calls.
     * @param imsServiceTypes Array of IMS call service type for ringing, foreground &
     *                        background calls.
     * @param imsCallTypes Array of IMS call type for ringing, foreground & background calls.
     */
    public void notifyPreciseCallState(int phoneId, int subId,
            @Annotation.PreciseCallStates int[] callStates, String[] imsCallIds,
            @Annotation.ImsCallServiceType int[] imsServiceTypes,
            @Annotation.ImsCallType int[] imsCallTypes) {
        if (!checkNotifyPermission("notifyPreciseCallState()")) {
            return;
        }

        int ringingCallState = callStates[CallState.CALL_CLASSIFICATION_RINGING];
        int foregroundCallState = callStates[CallState.CALL_CLASSIFICATION_FOREGROUND];
        int backgroundCallState = callStates[CallState.CALL_CLASSIFICATION_BACKGROUND];

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                boolean preciseCallStateChanged = false;
                mRingingCallState[phoneId] = ringingCallState;
                mForegroundCallState[phoneId] = foregroundCallState;
                mBackgroundCallState[phoneId] = backgroundCallState;
                PreciseCallState preciseCallState = new PreciseCallState(
                        ringingCallState, foregroundCallState,
                        backgroundCallState,
                        DisconnectCause.NOT_VALID,
                        PreciseDisconnectCause.NOT_VALID);
                if (!preciseCallState.equals(mPreciseCallState[phoneId])) {
                    preciseCallStateChanged = true;
                    mPreciseCallState[phoneId] = preciseCallState;
                }
                boolean notifyCallState = true;
                if (mCallQuality == null) {
                    log("notifyPreciseCallState: mCallQuality is null, "
                            + "skipping call attributes");
                    notifyCallState = false;
                } else {
                    // If the precise call state is no longer active, reset the call network type
                    // and call quality.
                    if (mPreciseCallState[phoneId].getForegroundCallState()
                            != PreciseCallState.PRECISE_CALL_STATE_ACTIVE) {
                        mCallNetworkType[phoneId] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                        mCallQuality[phoneId] = createCallQuality();
                    }
                    List<CallState> prevCallStateList = new ArrayList<>();
                    prevCallStateList.addAll(mCallStateLists.get(phoneId));
                    mCallStateLists.get(phoneId).clear();
                    if (foregroundCallState != PreciseCallState.PRECISE_CALL_STATE_NOT_VALID
                            && foregroundCallState != PreciseCallState.PRECISE_CALL_STATE_IDLE) {
                        CallQuality callQuality = mCallQuality[phoneId];
                        CallState.Builder builder = new CallState.Builder(
                                callStates[CallState.CALL_CLASSIFICATION_FOREGROUND])
                                .setNetworkType(mCallNetworkType[phoneId])
                                .setCallQuality(callQuality)
                                .setCallClassification(
                                        CallState.CALL_CLASSIFICATION_FOREGROUND);
                        if (imsCallIds != null && imsServiceTypes != null && imsCallTypes != null) {
                            builder = builder
                                    .setImsCallSessionId(imsCallIds[
                                            CallState.CALL_CLASSIFICATION_FOREGROUND])
                                    .setImsCallServiceType(imsServiceTypes[
                                            CallState.CALL_CLASSIFICATION_FOREGROUND])
                                    .setImsCallType(imsCallTypes[
                                            CallState.CALL_CLASSIFICATION_FOREGROUND]);
                        }
                        mCallStateLists.get(phoneId).add(builder.build());
                    }
                    if (backgroundCallState != PreciseCallState.PRECISE_CALL_STATE_NOT_VALID
                            && backgroundCallState != PreciseCallState.PRECISE_CALL_STATE_IDLE) {
                        CallState.Builder builder = new CallState.Builder(
                                callStates[CallState.CALL_CLASSIFICATION_BACKGROUND])
                                .setNetworkType(mCallNetworkType[phoneId])
                                .setCallQuality(createCallQuality())
                                .setCallClassification(
                                        CallState.CALL_CLASSIFICATION_BACKGROUND);
                        if (imsCallIds != null && imsServiceTypes != null && imsCallTypes != null) {
                            builder = builder
                                    .setImsCallSessionId(imsCallIds[
                                            CallState.CALL_CLASSIFICATION_BACKGROUND])
                                    .setImsCallServiceType(imsServiceTypes[
                                            CallState.CALL_CLASSIFICATION_BACKGROUND])
                                    .setImsCallType(imsCallTypes[
                                            CallState.CALL_CLASSIFICATION_BACKGROUND]);
                        }
                        mCallStateLists.get(phoneId).add(builder.build());
                    }
                    if (ringingCallState != PreciseCallState.PRECISE_CALL_STATE_NOT_VALID
                            && ringingCallState != PreciseCallState.PRECISE_CALL_STATE_IDLE) {
                        CallState.Builder builder = new CallState.Builder(
                                callStates[CallState.CALL_CLASSIFICATION_RINGING])
                                .setNetworkType(mCallNetworkType[phoneId])
                                .setCallQuality(createCallQuality())
                                .setCallClassification(
                                        CallState.CALL_CLASSIFICATION_RINGING);
                        if (imsCallIds != null && imsServiceTypes != null && imsCallTypes != null) {
                            builder = builder
                                    .setImsCallSessionId(imsCallIds[
                                            CallState.CALL_CLASSIFICATION_RINGING])
                                    .setImsCallServiceType(imsServiceTypes[
                                            CallState.CALL_CLASSIFICATION_RINGING])
                                    .setImsCallType(imsCallTypes[
                                            CallState.CALL_CLASSIFICATION_RINGING]);
                        }
                        mCallStateLists.get(phoneId).add(builder.build());
                    }
                    if (prevCallStateList.equals(mCallStateLists.get(phoneId))) {
                        notifyCallState = false;
                    }
                    boolean hasOngoingCall = false;
                    for (CallState cs : mCallStateLists.get(phoneId)) {
                        if (cs.getCallState() != PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED) {
                            hasOngoingCall = true;
                            break;
                        }
                    }
                    if (!hasOngoingCall) {
                        //no ongoing call. clear media quality status cached.
                        mMediaQualityStatus.get(phoneId).clear();
                    }
                }

                if (preciseCallStateChanged) {
                    for (Record r : mRecords) {
                        if (r.matchTelephonyCallbackEvent(
                                TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED)
                                && idMatch(r, subId, phoneId)) {
                            try {
                                r.callback.onPreciseCallStateChanged(mPreciseCallState[phoneId]);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                }

                if (notifyCallState) {
                    for (Record r : mRecords) {
                        if (r.matchTelephonyCallbackEvent(
                                TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED)
                                && idMatch(r, subId, phoneId)) {
                            try {
                                r.callback.onCallStatesChanged(mCallStateLists.get(phoneId));
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyDisconnectCause(int phoneId, int subId, int disconnectCause,
                                      int preciseDisconnectCause) {
        if (!checkNotifyPermission("notifyDisconnectCause()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallDisconnectCause[phoneId] = disconnectCause;
                mCallPreciseDisconnectCause[phoneId] = preciseDisconnectCause;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause[phoneId],
                                    mCallPreciseDisconnectCause[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyImsDisconnectCause(int subId, ImsReasonInfo imsReasonInfo) {
        if (!checkNotifyPermission("notifyImsCallDisconnectCause()")) {
            return;
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                if (imsReasonInfo == null) {
                    loge("ImsReasonInfo is null, subId=" + subId + ", phoneId=" + phoneId);
                    mImsReasonInfo.set(phoneId, new ImsReasonInfo());
                    return;
                }
                mImsReasonInfo.set(phoneId, imsReasonInfo);
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyImsCallDisconnectCause: mImsReasonInfo="
                                        + imsReasonInfo + " r=" + r);
                            }
                            r.callback.onImsCallDisconnectCauseChanged(mImsReasonInfo.get(phoneId));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifySrvccStateChanged(int subId, @SrvccState int state) {
        if (!checkNotifyPermission("notifySrvccStateChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifySrvccStateChanged: subId=" + subId + " srvccState=" + state);
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mSrvccState[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_SRVCC_STATE_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifySrvccStateChanged: mSrvccState=" + state + " r=" + r);
                            }
                            r.callback.onSrvccStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyOemHookRawEventForSubscriber(int phoneId, int subId, byte[] rawData) {
        if (!checkNotifyPermission("notifyOemHookRawEventForSubscriber")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifyOemHookRawEventForSubscriber:  r=" + r + " subId=" + subId);
                    }
                    if ((r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_OEM_HOOK_RAW))
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onOemHookRawEvent(rawData);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPhoneCapabilityChanged(PhoneCapability capability) {
        if (!checkNotifyPermission("notifyPhoneCapabilityChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyPhoneCapabilityChanged: capability=" + capability);
        }

        synchronized (mRecords) {
            mPhoneCapability = capability;

            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_PHONE_CAPABILITY_CHANGED)) {
                    try {
                        r.callback.onPhoneCapabilityChanged(capability);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyActiveDataSubIdChanged(int activeDataSubId) {
        if (!checkNotifyPermission("notifyActiveDataSubIdChanged()")) {
            return;
        }

        log("notifyActiveDataSubIdChanged: activeDataSubId=" + activeDataSubId);
        mLocalLog.log("notifyActiveDataSubIdChanged: activeDataSubId=" + activeDataSubId);

        mActiveDataSubId = activeDataSubId;
        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED)) {
                    try {
                        r.callback.onActiveDataSubIdChanged(activeDataSubId);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyRadioPowerStateChanged(int phoneId, int subId, @RadioPowerState int state) {
        if (!checkNotifyPermission("notifyRadioPowerStateChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyRadioPowerStateChanged: state= " + state + " subId=" + subId);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mRadioPowerState = state;

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onRadioPowerStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }

            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyEmergencyNumberList(int phoneId, int subId) {
        if (!checkNotifyPermission("notifyEmergencyNumberList()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                        Context.TELEPHONY_SERVICE);
                mEmergencyNumberList = tm.getEmergencyNumberList();

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onEmergencyNumberListChanged(mEmergencyNumberList);
                            if (VDBG) {
                                log("notifyEmergencyNumberList: emergencyNumberList= "
                                        + mEmergencyNumberList);
                            }
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }

            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyOutgoingEmergencyCall(int phoneId, int subId,
            EmergencyNumber emergencyNumber) {
        if (!checkNotifyPermission("notifyOutgoingEmergencyCall()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mOutgoingCallEmergencyNumber[phoneId] = emergencyNumber;
            }
            for (Record r : mRecords) {
                // Send to all listeners regardless of subscription
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL)) {
                    try {
                        r.callback.onOutgoingEmergencyCall(emergencyNumber, subId);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
        }
        handleRemoveListLocked();
    }

    @Override
    public void notifyOutgoingEmergencySms(int phoneId, int subId,
            EmergencyNumber emergencyNumber) {
        if (!checkNotifyPermission("notifyOutgoingEmergencySms()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mOutgoingSmsEmergencyNumber[phoneId] = emergencyNumber;
                for (Record r : mRecords) {
                    // Send to all listeners regardless of subscription
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS)) {
                        try {
                            r.callback.onOutgoingEmergencySms(emergencyNumber, subId);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyCallQualityChanged(CallQuality callQuality, int phoneId, int subId,
            int callNetworkType) {
        if (!checkNotifyPermission("notifyCallQualityChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                // merge CallQuality with PreciseCallState and network type
                mCallQuality[phoneId] = callQuality;
                mCallNetworkType[phoneId] = callNetworkType;
                if (mCallStateLists.get(phoneId).size() > 0
                        && mCallStateLists.get(phoneId).get(0).getCallState()
                        == PreciseCallState.PRECISE_CALL_STATE_ACTIVE) {
                    CallState prev = mCallStateLists.get(phoneId).remove(0);
                    mCallStateLists.get(phoneId).add(
                            0, new CallState.Builder(prev.getCallState())
                                    .setNetworkType(callNetworkType)
                                    .setCallQuality(callQuality)
                                    .setCallClassification(prev.getCallClassification())
                                    .setImsCallSessionId(prev.getImsCallSessionId())
                                    .setImsCallServiceType(prev.getImsCallServiceType())
                                    .setImsCallType(prev.getImsCallType()).build());
                } else {
                    log("There is no active call to report CallQuality");
                    return;
                }

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onCallStatesChanged(mCallStateLists.get(phoneId));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyRegistrationFailed(int phoneId, int subId, @NonNull CellIdentity cellIdentity,
            @NonNull String chosenPlmn, int domain, int causeCode, int additionalCauseCode) {
        if (!checkNotifyPermission("notifyRegistrationFailed()")) {
            return;
        }

        // In case callers don't have fine location access, pre-construct a location-free version
        // of the CellIdentity. This will still have the PLMN ID, which should be sufficient for
        // most purposes.
        final CellIdentity noLocationCi = cellIdentity.sanitizeLocationInfo();


        // This shouldn't be necessary, but better to not take the chance
        final String primaryPlmn = (cellIdentity != null) ? cellIdentity.getPlmn() : "<UNKNOWN>";

        final String logStr = "Registration Failed for phoneId=" + phoneId
                + " subId=" + subId + "primaryPlmn=" + primaryPlmn
                + " chosenPlmn=" + chosenPlmn + " domain=" + domain
                + " causeCode=" + causeCode + " additionalCauseCode=" + additionalCauseCode;

        mLocalLog.log(logStr);
        if (DBG) log(logStr);

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_REGISTRATION_FAILURE)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onRegistrationFailed(
                                    checkFineLocationAccess(r, Build.VERSION_CODES.BASE)
                                            ? cellIdentity : noLocationCi,
                                    chosenPlmn, domain, causeCode,
                                    additionalCauseCode);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification of changes to barring status to PhoneStateListener registrants.
     *
     * @param phoneId the phoneId
     * @param subId the subId
     * @param barringInfo a structure containing the complete updated barring info.
     */
    public void notifyBarringInfoChanged(int phoneId, int subId, @NonNull BarringInfo barringInfo) {
        if (!checkNotifyPermission("notifyBarringInfo()")) {
            return;
        }
        if (!validatePhoneId(phoneId)) {
            loge("Received invalid phoneId for BarringInfo = " + phoneId);
            return;
        }

        synchronized (mRecords) {
            if (barringInfo == null) {
                loge("Received null BarringInfo for subId=" + subId + ", phoneId=" + phoneId);
                mBarringInfo.set(phoneId, new BarringInfo());
                return;
            }
            if (barringInfo.equals(mBarringInfo.get(phoneId))) {
                if (VDBG) log("Ignoring duplicate barring info.");
                return;
            }
            mBarringInfo.set(phoneId, barringInfo);
            // Barring info is non-null
            BarringInfo biNoLocation = barringInfo.createLocationInfoSanitizedCopy();
            if (VDBG) log("listen: call onBarringInfoChanged=" + barringInfo);
            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_BARRING_INFO_CHANGED)
                        && idMatch(r, subId, phoneId)) {
                    try {
                        if (DBG_LOC) {
                            log("notifyBarringInfo: mBarringInfo="
                                    + barringInfo + " r=" + r);
                        }
                        r.callback.onBarringInfoChanged(
                                checkFineLocationAccess(r, Build.VERSION_CODES.BASE)
                                    ? barringInfo : biNoLocation);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification to registrants that the configs of physical channel has changed for
     * a particular subscription.
     *
     * @param phoneId the phone id.
     * @param subId the subId
     * @param configs a list of {@link PhysicalChannelConfig}, the configs of physical channel.
     */
    public void notifyPhysicalChannelConfigForSubscriber(int phoneId, int subId,
            List<PhysicalChannelConfig> configs) {
        if (!checkNotifyPermission("notifyPhysicalChannelConfig()")) {
            return;
        }

        List<PhysicalChannelConfig> sanitizedConfigs = getLocationSanitizedConfigs(configs);
        if (VDBG) {
            log("notifyPhysicalChannelConfig: subId=" + subId + " configs=" + configs
                    + " sanitizedConfigs=" + sanitizedConfigs);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mPhysicalChannelConfigs.set(phoneId, configs);
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyPhysicalChannelConfig: mPhysicalChannelConfigs="
                                        + (shouldSanitizeLocationForPhysicalChannelConfig(r)
                                                ? sanitizedConfigs : configs)
                                        + " r=" + r);
                            }
                            r.callback.onPhysicalChannelConfigChanged(
                                    shouldSanitizeLocationForPhysicalChannelConfig(r)
                                            ? sanitizedConfigs : configs);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    private static boolean shouldSanitizeLocationForPhysicalChannelConfig(Record record) {
        // Always redact location info from PhysicalChannelConfig if the registrant is from neither
        // PHONE nor SYSTEM process. There is no user case that the registrant needs the location
        // info (e.g. physicalCellId). This also remove the need for the location permissions check.
        return record.callerUid != Process.PHONE_UID && record.callerUid != Process.SYSTEM_UID;
    }

    /**
     * Return a copy of the PhysicalChannelConfig list but with location info removed.
     */
    private static List<PhysicalChannelConfig> getLocationSanitizedConfigs(
            List<PhysicalChannelConfig> configs) {
        List<PhysicalChannelConfig> sanitizedConfigs = new ArrayList<>(configs.size());
        for (PhysicalChannelConfig config : configs) {
            sanitizedConfigs.add(config.createLocationInfoSanitizedCopy());
        }
        return sanitizedConfigs;
    }

    /**
     * Notify that the data enabled has changed.
     *
     * @param phoneId the phone id.
     * @param subId the subId.
     * @param enabled True if data is enabled, otherwise disabled.
     * @param reason  Reason for data enabled/disabled. See {@code DATA_*} in
     *                {@link TelephonyManager}.
     */
    public void notifyDataEnabled(int phoneId, int subId, boolean enabled,
            @TelephonyManager.DataEnabledReason int reason) {
        if (!checkNotifyPermission("notifyDataEnabled()")) {
            return;
        }

        if (VDBG) {
            log("notifyDataEnabled: PhoneId=" + phoneId + " subId=" + subId +
                    " enabled=" + enabled + " reason=" + reason);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mIsDataEnabled[phoneId] = enabled;
                mDataEnabledReason[phoneId] = reason;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_DATA_ENABLED_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onDataEnabledChanged(enabled, reason);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Notify that the allowed network type has changed.
     *
     * @param phoneId the phone id.
     * @param subId the subId.
     * @param reason the allowed network type reason.
     * @param allowedNetworkType the allowed network type value.
     */
    public void notifyAllowedNetworkTypesChanged(int phoneId, int subId, int reason,
            long allowedNetworkType) {
        if (!checkNotifyPermission("notifyAllowedNetworkTypesChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mAllowedNetworkTypeReason[phoneId] = reason;
                mAllowedNetworkTypeValue[phoneId] = allowedNetworkType;

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            if (VDBG) {
                                log("notifyAllowedNetworkTypesChanged: reason= " + reason
                                        + ", allowed network type:"
                                        + TelephonyManager.convertNetworkTypeBitmaskToString(
                                        allowedNetworkType));
                            }
                            r.callback.onAllowedNetworkTypesChanged(reason, allowedNetworkType);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Notify that the link capacity estimate has changed.
     * @param phoneId the phone id.
     * @param subId the subscription id.
     * @param linkCapacityEstimateList a list of {@link LinkCapacityEstimate}
     */
    public void notifyLinkCapacityEstimateChanged(int phoneId, int subId,
            List<LinkCapacityEstimate> linkCapacityEstimateList) {
        if (!checkNotifyPermission("notifyLinkCapacityEstimateChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyLinkCapacityEstimateChanged: linkCapacityEstimateList ="
                    + linkCapacityEstimateList);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mLinkCapacityEstimateLists.set(phoneId, linkCapacityEstimateList);
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onLinkCapacityEstimateChanged(linkCapacityEstimateList);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void addCarrierPrivilegesCallback(
            int phoneId,
            @NonNull ICarrierPrivilegesCallback callback,
            @NonNull String callingPackage,
            @NonNull String callingFeatureId) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                "addCarrierPrivilegesCallback");
        if (VDBG) {
            log(
                    "listen carrier privs: E pkg=" + pii(callingPackage) + " phoneId=" + phoneId
                            + " uid=" + Binder.getCallingUid()
                            + " myUserId=" + UserHandle.myUserId() + " callerUserId=" + callerUserId
                            + " callback=" + callback
                            + " callback.asBinder=" + callback.asBinder());
        }

        // In case this is triggered from the caller who has handled multiple SIM config change
        // firstly, we need to update the status (mNumPhone and mCarrierPrivilegeStates) firstly.
        // This is almost a no-op if there is no multiple SIM config change in advance.
        onMultiSimConfigChanged();

        synchronized (mRecords) {
            if (!validatePhoneId(phoneId)) {
                throw new IllegalArgumentException("Invalid slot index: " + phoneId);
            }
            Record r = add(
                    callback.asBinder(), Binder.getCallingUid(), Binder.getCallingPid(), false);

            if (r == null) return;

            r.context = mContext;
            r.carrierPrivilegesCallback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.phoneId = phoneId;
            r.eventList = new ArraySet<>();
            if (DBG) {
                log("listen carrier privs: Register r=" + r);
            }

            Pair<List<String>, int[]> state = mCarrierPrivilegeStates.get(phoneId);
            Pair<String, Integer> carrierServiceState = mCarrierServiceStates.get(phoneId);
            try {
                if (r.matchCarrierPrivilegesCallback()) {
                    // Here, two callbacks are triggered in quick succession on the same binder.
                    // In typical case, we expect the callers to care about only one or the other.
                    r.carrierPrivilegesCallback.onCarrierPrivilegesChanged(
                            Collections.unmodifiableList(state.first),
                            Arrays.copyOf(state.second, state.second.length));

                    r.carrierPrivilegesCallback.onCarrierServiceChanged(carrierServiceState.first,
                            carrierServiceState.second);
                }
            } catch (RemoteException ex) {
                remove(r.binder);
            }
        }
    }

    @Override
    public void removeCarrierPrivilegesCallback(
            @NonNull ICarrierPrivilegesCallback callback, @NonNull String callingPackage) {
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                "removeCarrierPrivilegesCallback");
        remove(callback.asBinder());
    }

    @Override
    public void notifyCarrierPrivilegesChanged(
            int phoneId, List<String> privilegedPackageNames, int[] privilegedUids) {
        if (!checkNotifyPermission("notifyCarrierPrivilegesChanged")) {
            return;
        }
        if (VDBG) {
            log(
                    "notifyCarrierPrivilegesChanged: phoneId=" + phoneId
                            + ", <packages=" + pii(privilegedPackageNames)
                            + ", uids=" + Arrays.toString(privilegedUids) + ">");
        }

        // In case this is triggered from the caller who has handled multiple SIM config change
        // firstly, we need to update the status (mNumPhone and mCarrierPrivilegeStates) firstly.
        // This is almost a no-op if there is no multiple SIM config change in advance.
        onMultiSimConfigChanged();

        synchronized (mRecords) {
            if (!validatePhoneId(phoneId)) {
                throw new IllegalArgumentException("Invalid slot index: " + phoneId);
            }
            mCarrierPrivilegeStates.set(
                    phoneId, new Pair<>(privilegedPackageNames, privilegedUids));
            for (Record r : mRecords) {
                // Listeners are per-slot, not per-subscription. This is to provide a stable
                // view across SIM profile switches.
                if (!r.matchCarrierPrivilegesCallback()
                        || !idMatch(r, SubscriptionManager.INVALID_SUBSCRIPTION_ID, phoneId)) {
                    continue;
                }
                try {
                    // Make sure even in-process listeners can't modify the values.
                    r.carrierPrivilegesCallback.onCarrierPrivilegesChanged(
                            Collections.unmodifiableList(privilegedPackageNames),
                            Arrays.copyOf(privilegedUids, privilegedUids.length));
                } catch (RemoteException ex) {
                    mRemoveList.add(r.binder);
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyCarrierServiceChanged(int phoneId, @Nullable String packageName, int uid) {
        if (!checkNotifyPermission("notifyCarrierServiceChanged")) return;
        if (!validatePhoneId(phoneId)) return;
        if (VDBG) {
            log("notifyCarrierServiceChanged: phoneId=" + phoneId
                    + ", package=" + pii(packageName) + ", uid=" + uid);
        }

        // In case this is triggered from the caller who has handled multiple SIM config change
        // firstly, we need to update the status (mNumPhone and mCarrierServiceStates) firstly.
        // This is almost a no-op if there is no multiple SIM config change in advance.
        onMultiSimConfigChanged();

        synchronized (mRecords) {
            mCarrierServiceStates.set(
                    phoneId, new Pair<>(packageName, uid));
            for (Record r : mRecords) {
                // Listeners are per-slot, not per-subscription.
                if (!r.matchCarrierPrivilegesCallback()
                        || !idMatch(r, SubscriptionManager.INVALID_SUBSCRIPTION_ID, phoneId)) {
                    continue;
                }
                try {
                    r.carrierPrivilegesCallback.onCarrierServiceChanged(packageName, uid);
                } catch (RemoteException ex) {
                    mRemoveList.add(r.binder);
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void addCarrierConfigChangeListener(ICarrierConfigChangeListener listener,
            String pkg, String featureId) {
        final int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), pkg);
        if (VDBG) {
            log("addCarrierConfigChangeListener pkg=" + pii(pkg) + " uid=" + Binder.getCallingUid()
                    + " myUserId=" + UserHandle.myUserId() + " callerUerId" + callerUserId
                    + " listener=" + listener + " listener.asBinder=" + listener.asBinder());
        }

        synchronized (mRecords) {
            IBinder b = listener.asBinder();
            boolean doesLimitApply = doesLimitApplyForListeners(Binder.getCallingUid(),
                    Process.myUid());
            Record r = add(b, Binder.getCallingUid(), Binder.getCallingPid(), doesLimitApply);

            if (r == null) {
                loge("Can not create Record instance!");
                return;
            }

            r.context = mContext;
            r.carrierConfigChangeListener = listener;
            r.callingPackage = pkg;
            r.callingFeatureId = featureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.eventList = new ArraySet<>();
            if (DBG) {
                log("addCarrierConfigChangeListener:  Register r=" + r);
            }
        }
    }

    @Override
    public void removeCarrierConfigChangeListener(ICarrierConfigChangeListener listener,
            String pkg) {
        if (DBG) log("removeCarrierConfigChangeListener listener=" + listener + ", pkg=" + pkg);
        mAppOps.checkPackage(Binder.getCallingUid(), pkg);
        remove(listener.asBinder());
    }

    @Override
    public void notifyCarrierConfigChanged(int phoneId, int subId, int carrierId,
            int specificCarrierId) {
        if (!validatePhoneId(phoneId)) {
            throw new IllegalArgumentException("Invalid phoneId: " + phoneId);
        }
        if (!checkNotifyPermission("notifyCarrierConfigChanged")) {
            loge("Caller has no notify permission!");
            return;
        }
        if (VDBG) {
            log("notifyCarrierConfigChanged: phoneId=" + phoneId + ", subId=" + subId
                    + ", carrierId=" + carrierId + ", specificCarrierId=" + specificCarrierId);
        }

        synchronized (mRecords) {
            mRemoveList.clear();
            for (Record r : mRecords) {
                // Listeners are "global", neither per-slot nor per-sub, so no idMatch check here
                if (!r.matchCarrierConfigChangeListener()) {
                    continue;
                }
                try {
                    r.carrierConfigChangeListener.onCarrierConfigChanged(phoneId, subId, carrierId,
                            specificCarrierId);
                } catch (RemoteException re) {
                    mRemoveList.add(r.binder);
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyMediaQualityStatusChanged(int phoneId, int subId, MediaQualityStatus status) {
        if (!checkNotifyPermission("notifyMediaQualityStatusChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                if (mCallStateLists.get(phoneId).size() > 0) {
                    CallState callState = null;
                    for (CallState cs : mCallStateLists.get(phoneId)) {
                        if (cs.getCallState() == PreciseCallState.PRECISE_CALL_STATE_ACTIVE) {
                            callState = cs;
                            break;
                        }
                    }
                    if (callState != null) {
                        String callSessionId = callState.getImsCallSessionId();
                        if (callSessionId != null
                                && callSessionId.equals(status.getCallSessionId())) {
                            mMediaQualityStatus.get(phoneId)
                                    .put(status.getMediaSessionType(), status);
                        } else {
                            log("SessionId mismatch active call:" + callSessionId
                                    + " media quality:" + status.getCallSessionId());
                            return;
                        }
                    } else {
                        log("There is no active call to report CallQaulity");
                        return;
                    }
                }

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_MEDIA_QUALITY_STATUS_CHANGED)
                            && idMatch(r, subId, phoneId)) {
                        try {
                            r.callback.onMediaQualityStatusChanged(status);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyCallbackModeStarted(int phoneId, int subId, int type) {
        if (!checkNotifyPermission("notifyCallbackModeStarted()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallbackModeStarted: phoneId=" + phoneId + ", subId=" + subId
                    + ", type=" + type);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                if (type == TelephonyManager.EMERGENCY_CALLBACK_MODE_CALL) {
                    mECBMStarted[phoneId] = true;
                } else if (type == TelephonyManager.EMERGENCY_CALLBACK_MODE_SMS) {
                    mSCBMStarted[phoneId] = true;
                }
            }
            for (Record r : mRecords) {
                // Send to all listeners regardless of subscription
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                    try {
                        r.callback.onCallBackModeStarted(type);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
        }
        handleRemoveListLocked();
    }

    @Override
    public void notifyCallbackModeStopped(int phoneId, int subId, int type, int reason) {
        if (!checkNotifyPermission("notifyCallbackModeStopped()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallbackModeStopped: phoneId=" + phoneId + ", subId=" + subId
                    + ", type=" + type + ", reason=" + reason);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                if (type == TelephonyManager.EMERGENCY_CALLBACK_MODE_CALL) {
                    mECBMStarted[phoneId] = false;
                    mECBMReason[phoneId] = reason;
                } else if (type == TelephonyManager.EMERGENCY_CALLBACK_MODE_SMS) {
                    mSCBMStarted[phoneId] = false;
                    mSCBMReason[phoneId] = reason;
                }
            }
            for (Record r : mRecords) {
                // Send to all listeners regardless of subscription
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                    try {
                        r.callback.onCallBackModeStopped(type, reason);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
        }
        handleRemoveListLocked();
    }

    @NeverCompile // Avoid size overhead of debugging code.
    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            pw.println("last known state:");
            pw.increaseIndent();
            for (int i = 0; i < getTelephonyManager().getActiveModemCount(); i++) {
                pw.println("Phone Id=" + i);
                pw.increaseIndent();
                pw.println("mCallState=" + mCallState[i]);
                pw.println("mRingingCallState=" + mRingingCallState[i]);
                pw.println("mForegroundCallState=" + mForegroundCallState[i]);
                pw.println("mBackgroundCallState=" + mBackgroundCallState[i]);
                pw.println("mPreciseCallState=" + mPreciseCallState[i]);
                pw.println("mCallDisconnectCause=" + mCallDisconnectCause[i]);
                pw.println("mCallIncomingNumber=" + mCallIncomingNumber[i]);
                pw.println("mServiceState=" + mServiceState[i]);
                pw.println("mVoiceActivationState= " + mVoiceActivationState[i]);
                pw.println("mDataActivationState= " + mDataActivationState[i]);
                pw.println("mUserMobileDataState= " + mUserMobileDataState[i]);
                pw.println("mSignalStrength=" + mSignalStrength[i]);
                pw.println("mMessageWaiting=" + mMessageWaiting[i]);
                pw.println("mCallForwarding=" + mCallForwarding[i]);
                pw.println("mDataActivity=" + mDataActivity[i]);
                pw.println("mDataConnectionState=" + mDataConnectionState[i]);
                pw.println("mCellIdentity=" + mCellIdentity[i]);
                pw.println("mCellInfo=" + mCellInfo.get(i));
                pw.println("mImsCallDisconnectCause=" + mImsReasonInfo.get(i));
                pw.println("mSrvccState=" + mSrvccState[i]);
                pw.println("mCallPreciseDisconnectCause=" + mCallPreciseDisconnectCause[i]);
                pw.println("mCallQuality=" + mCallQuality[i]);
                pw.println("mCallNetworkType=" + mCallNetworkType[i]);
                pw.println("mPreciseDataConnectionStates=" + mPreciseDataConnectionStates.get(i));
                pw.println("mOutgoingCallEmergencyNumber=" + mOutgoingCallEmergencyNumber[i]);
                pw.println("mOutgoingSmsEmergencyNumber=" + mOutgoingSmsEmergencyNumber[i]);
                pw.println("mBarringInfo=" + mBarringInfo.get(i));
                pw.println("mCarrierNetworkChangeState=" + mCarrierNetworkChangeState[i]);
                pw.println("mTelephonyDisplayInfo=" + mTelephonyDisplayInfos[i]);
                pw.println("mIsDataEnabled=" + mIsDataEnabled[i]);
                pw.println("mDataEnabledReason=" + mDataEnabledReason[i]);
                pw.println("mAllowedNetworkTypeReason=" + mAllowedNetworkTypeReason[i]);
                pw.println("mAllowedNetworkTypeValue=" + mAllowedNetworkTypeValue[i]);
                pw.println("mPhysicalChannelConfigs=" + mPhysicalChannelConfigs.get(i));
                pw.println("mLinkCapacityEstimateList=" + mLinkCapacityEstimateLists.get(i));
                pw.println("mECBMReason=" + mECBMReason[i]);
                pw.println("mECBMStarted=" + mECBMStarted[i]);
                pw.println("mSCBMReason=" + mSCBMReason[i]);
                pw.println("mSCBMStarted=" + mSCBMStarted[i]);

                // We need to obfuscate package names, and primitive arrays' native toString is ugly
                Pair<List<String>, int[]> carrierPrivilegeState = mCarrierPrivilegeStates.get(i);
                pw.println(
                        "mCarrierPrivilegeState=<packages=" + pii(carrierPrivilegeState.first)
                                + ", uids=" + Arrays.toString(carrierPrivilegeState.second) + ">");
                Pair<String, Integer> carrierServiceState = mCarrierServiceStates.get(i);
                pw.println("mCarrierServiceState=<package=" + pii(carrierServiceState.first)
                        + ", uid=" + carrierServiceState.second + ">");
                pw.decreaseIndent();
            }

            pw.println("mPhoneCapability=" + mPhoneCapability);
            pw.println("mActiveDataSubId=" + mActiveDataSubId);
            pw.println("mRadioPowerState=" + mRadioPowerState);
            pw.println("mEmergencyNumberList=" + mEmergencyNumberList);
            pw.println("mDefaultPhoneId=" + mDefaultPhoneId);
            pw.println("mDefaultSubId=" + mDefaultSubId);

            pw.decreaseIndent();

            pw.println("local logs:");
            pw.increaseIndent();
            mLocalLog.dump(fd, pw, args);
            pw.decreaseIndent();
            pw.println("listen logs:");
            pw.increaseIndent();
            mListenLog.dump(fd, pw, args);
            pw.decreaseIndent();
            pw.println("registrations: count=" + recordCount);
            pw.increaseIndent();
            for (Record r : mRecords) {
                pw.println(r);
            }
            pw.decreaseIndent();
        }
    }

    //
    // the legacy intent broadcasting
    //

    // Legacy intent action.
    /** Fired when a subscription's phone state changes. */
    private static final String ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED =
            "android.intent.action.SUBSCRIPTION_PHONE_STATE";
    /**
     * Broadcast Action: The data connection state has changed for any one of the
     * phone's mobile data connections (eg, default, MMS or GPS specific connection).
     */
    private static final String ACTION_ANY_DATA_CONNECTION_STATE_CHANGED =
            "android.intent.action.ANY_DATA_STATE";

    // Legacy intent extra keys, copied from PhoneConstants.
    // Used in legacy intents sent here, for backward compatibility.
    private static final String PHONE_CONSTANTS_DATA_APN_TYPE_KEY = "apnType";
    private static final String PHONE_CONSTANTS_DATA_APN_KEY = "apn";
    private static final String PHONE_CONSTANTS_SLOT_KEY = "slot";
    private static final String PHONE_CONSTANTS_STATE_KEY = "state";
    private static final String PHONE_CONSTANTS_SUBSCRIPTION_KEY = "subscription";

    /**
     * Broadcast Action: The phone's signal strength has changed. The intent will have the
     * following extra values:
     *   phoneName - A string version of the phone name.
     *   asu - A numeric value for the signal strength.
     *         An ASU is 0-31 or -1 if unknown (for GSM, dBm = -113 - 2 * asu).
     *         The following special values are defined:
     *         0 means "-113 dBm or less".31 means "-51 dBm or greater".
     */
    public static final String ACTION_SIGNAL_STRENGTH_CHANGED = "android.intent.action.SIG_STR";

    private void broadcastServiceStateChanged(ServiceState state, int phoneId, int subId) {
        try {
            mBatteryStats.notePhoneState(state.getState());
        } catch (RemoteException re) {
            // Can't do much
        }

        // Send the broadcast exactly once to all possible disjoint sets of apps.
        // If the location master switch is on, broadcast the ServiceState 4 times:
        // - Full ServiceState sent to apps with ACCESS_FINE_LOCATION and READ_PHONE_STATE
        // - Full ServiceState sent to apps with ACCESS_FINE_LOCATION and
        //   READ_PRIVILEGED_PHONE_STATE but not READ_PHONE_STATE
        // - Sanitized ServiceState sent to apps with READ_PHONE_STATE but not ACCESS_FINE_LOCATION
        // - Sanitized ServiceState sent to apps with READ_PRIVILEGED_PHONE_STATE but neither
        //   READ_PHONE_STATE nor ACCESS_FINE_LOCATION
        // If the location master switch is off, broadcast the ServiceState multiple times:
        // - Full ServiceState sent to all apps permitted to bypass the location master switch if
        //   they have either READ_PHONE_STATE or READ_PRIVILEGED_PHONE_STATE
        // - Sanitized ServiceState sent to all other apps with READ_PHONE_STATE
        // - Sanitized ServiceState sent to all other apps with READ_PRIVILEGED_PHONE_STATE but not
        //   READ_PHONE_STATE
        //
        // Create a unique delivery group key for each variant for SERVICE_STATE broadcast so
        // that a new broadcast only replaces the pending broadcasts of the same variant.
        // In order to create a unique delivery group key, append tag of the form
        // "I:Included-permissions[,E:Excluded-permissions][,lbp]"
        // Note: Given that location-bypass-packages are static, we can just append "lbp" to the
        // tag to create a unique delivery group but if location-bypass-packages become dynamic
        // in the future, we would need to create a unique key for each group of
        // location-bypass-packages.
        if (LocationAccessPolicy.isLocationModeEnabled(mContext, mContext.getUserId())) {
            Intent fullIntent = createServiceStateIntent(state, subId, phoneId, false);
            mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                    fullIntent,
                    new String[]{Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    createServiceStateBroadcastOptions(subId, phoneId, "I:RA"));
            mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                    fullIntent,
                    new String[]{Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    new String[]{Manifest.permission.READ_PHONE_STATE},
                    null,
                    createServiceStateBroadcastOptions(subId, phoneId, "I:RPA,E:R"));

            Intent sanitizedIntent = createServiceStateIntent(state, subId, phoneId, true);
            mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                    sanitizedIntent,
                    new String[]{Manifest.permission.READ_PHONE_STATE},
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    null,
                    createServiceStateBroadcastOptions(subId, phoneId, "I:R,E:A"));
            mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                    sanitizedIntent,
                    new String[]{Manifest.permission.READ_PRIVILEGED_PHONE_STATE},
                    new String[]{Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    null,
                    createServiceStateBroadcastOptions(subId, phoneId, "I:RP,E:RA"));
        } else {
            String[] locationBypassPackages = Binder.withCleanCallingIdentity(() ->
                    LocationAccessPolicy.getLocationBypassPackages(mContext));
            for (String locationBypassPackage : locationBypassPackages) {
                Intent fullIntent = createServiceStateIntent(state, subId, phoneId, false);
                fullIntent.setPackage(locationBypassPackage);
                mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                        fullIntent,
                        new String[]{Manifest.permission.READ_PHONE_STATE},
                        createServiceStateBroadcastOptions(subId, phoneId, "I:R"));
                mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                        fullIntent,
                        new String[]{Manifest.permission.READ_PRIVILEGED_PHONE_STATE},
                        new String[]{Manifest.permission.READ_PHONE_STATE},
                        null,
                        createServiceStateBroadcastOptions(subId, phoneId, "I:RP,E:R"));
            }

            Intent sanitizedIntent = createServiceStateIntent(state, subId, phoneId, true);
            mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                    sanitizedIntent,
                    new String[]{Manifest.permission.READ_PHONE_STATE},
                    new String[]{/* no excluded permissions */},
                    locationBypassPackages,
                    createServiceStateBroadcastOptions(subId, phoneId, "I:R,lbp"));
            mContext.createContextAsUser(UserHandle.ALL, 0).sendBroadcastMultiplePermissions(
                    sanitizedIntent,
                    new String[]{Manifest.permission.READ_PRIVILEGED_PHONE_STATE},
                    new String[]{Manifest.permission.READ_PHONE_STATE},
                    locationBypassPackages,
                    createServiceStateBroadcastOptions(subId, phoneId, "I:RP,E:R,lbp"));
        }
    }

    private Intent createServiceStateIntent(ServiceState state, int subId, int phoneId,
            boolean sanitizeLocation) {
        Intent intent = new Intent(Intent.ACTION_SERVICE_STATE);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        Bundle data = new Bundle();
        if (sanitizeLocation) {
            state.createLocationInfoSanitizedCopy(true).fillInNotifierBundle(data);
        } else {
            state.fillInNotifierBundle(data);
        }
        intent.putExtras(data);
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
        return intent;
    }

    private BroadcastOptions createServiceStateBroadcastOptions(int subId, int phoneId,
            String tag) {
        return new BroadcastOptions()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                // Use a combination of subId and phoneId as the key so that older broadcasts
                // with same subId and phoneId will get discarded.
                .setDeliveryGroupMatchingKey(Intent.ACTION_SERVICE_STATE,
                        subId + "-" + phoneId + "-" + tag)
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
    }

    private void broadcastSignalStrengthChanged(SignalStrength signalStrength, int phoneId,
            int subId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneSignalStrength(signalStrength);
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(ACTION_SIGNAL_STRENGTH_CHANGED);
        Bundle data = new Bundle();
        fillInSignalStrengthNotifierBundle(signalStrength, data);
        intent.putExtras(data);
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void fillInSignalStrengthNotifierBundle(SignalStrength signalStrength, Bundle bundle) {
        List<CellSignalStrength> cellSignalStrengths = signalStrength.getCellSignalStrengths();
        for (CellSignalStrength cellSignalStrength : cellSignalStrengths) {
            if (cellSignalStrength instanceof CellSignalStrengthLte) {
                bundle.putParcelable("Lte", (CellSignalStrengthLte) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthCdma) {
                bundle.putParcelable("Cdma", (CellSignalStrengthCdma) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthGsm) {
                bundle.putParcelable("Gsm", (CellSignalStrengthGsm) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthWcdma) {
                bundle.putParcelable("Wcdma", (CellSignalStrengthWcdma) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthTdscdma) {
                bundle.putParcelable("Tdscdma", (CellSignalStrengthTdscdma) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthNr) {
                bundle.putParcelable("Nr", (CellSignalStrengthNr) cellSignalStrength);
            }
        }
    }

    /**
     * Broadcasts an intent notifying apps of a phone state change. {@code subId} can be
     * a valid subId, in which case this function fires a subId-specific intent, or it
     * can be {@code SubscriptionManager.INVALID_SUBSCRIPTION_ID}, in which case we send
     * a global state change broadcast ({@code TelephonyManager.ACTION_PHONE_STATE_CHANGED}).
     */
    private void broadcastCallStateChanged(int state, String incomingNumber, int phoneId,
                int subId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                mBatteryStats.notePhoneOff();
                FrameworkStatsLog.write(FrameworkStatsLog.PHONE_STATE_CHANGED,
                        FrameworkStatsLog.PHONE_STATE_CHANGED__STATE__OFF);
            } else {
                mBatteryStats.notePhoneOn();
                FrameworkStatsLog.write(FrameworkStatsLog.PHONE_STATE_CHANGED,
                        FrameworkStatsLog.PHONE_STATE_CHANGED__STATE__ON);
            }
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, callStateToString(state));

        // If a valid subId was specified, we should fire off a subId-specific state
        // change intent and include the subId.
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.setAction(ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
            intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        }
        // If the phoneId is invalid, the broadcast is for overall call state.
        if (phoneId != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
            intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
        }

        // Wakeup apps for the (SUBSCRIPTION_)PHONE_STATE broadcast.
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

        // Create a version of the intent with the number always populated.
        Intent intentWithPhoneNumber = new Intent(intent);
        intentWithPhoneNumber.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, incomingNumber);

        // Send broadcast twice, once for apps that have PRIVILEGED permission and once for those
        // that have the runtime one
        mContext.sendBroadcastAsUser(intentWithPhoneNumber, UserHandle.ALL,
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PHONE_STATE,
                AppOpsManager.OP_READ_PHONE_STATE);
        mContext.sendBroadcastAsUserMultiplePermissions(intentWithPhoneNumber, UserHandle.ALL,
                new String[] { android.Manifest.permission.READ_PHONE_STATE,
                        android.Manifest.permission.READ_CALL_LOG});
    }

    /** Converts TelephonyManager#CALL_STATE_* to TelephonyManager#EXTRA_STATE_*. */
    private static String callStateToString(int callState) {
        switch (callState) {
            case TelephonyManager.CALL_STATE_RINGING:
                return TelephonyManager.EXTRA_STATE_RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return TelephonyManager.EXTRA_STATE_OFFHOOK;
            default:
                return TelephonyManager.EXTRA_STATE_IDLE;
        }
    }

    private void broadcastDataConnectionStateChanged(int slotIndex, int subId,
            @NonNull PreciseDataConnectionState pdcs) {
        // Note: not reporting to the battery stats service here, because the
        // status bar takes care of that after taking into account all of the
        // required info.
        Intent intent = new Intent(ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(PHONE_CONSTANTS_STATE_KEY,
                TelephonyUtils.dataStateToString(pdcs.getState()));
        intent.putExtra(PHONE_CONSTANTS_DATA_APN_KEY, pdcs.getApnSetting().getApnName());
        intent.putExtra(PHONE_CONSTANTS_DATA_APN_TYPE_KEY,
                getApnTypesStringFromBitmask(pdcs.getApnSetting().getApnTypeBitmask()));
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, slotIndex);
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        // Send the broadcast twice -- once for all apps with READ_PHONE_STATE, then again
        // for all apps with READ_PRIV but not READ_PHONE_STATE. This ensures that any app holding
        // either READ_PRIV or READ_PHONE get this broadcast exactly once.
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL, Manifest.permission.READ_PHONE_STATE);
        mContext.createContextAsUser(UserHandle.ALL, 0)
                .sendBroadcastMultiplePermissions(intent,
                        new String[] { Manifest.permission.READ_PRIVILEGED_PHONE_STATE },
                        new String[] { Manifest.permission.READ_PHONE_STATE });
    }

    /**
     * Reimplementation of {@link ApnSetting#getApnTypesStringFromBitmask}.
     */
    @VisibleForTesting
    public static String getApnTypesStringFromBitmask(int apnTypeBitmask) {
        List<String> types = new ArrayList<>();
        int remainingApnTypes = apnTypeBitmask;
        // special case for DEFAULT since it's not a pure bit
        if ((remainingApnTypes & ApnSetting.TYPE_DEFAULT) == ApnSetting.TYPE_DEFAULT) {
            types.add(ApnSetting.TYPE_DEFAULT_STRING);
            remainingApnTypes &= ~ApnSetting.TYPE_DEFAULT;
        }
        while (remainingApnTypes != 0) {
            int highestApnTypeBit = Integer.highestOneBit(remainingApnTypes);
            String apnString = ApnSetting.getApnTypeString(highestApnTypeBit);
            if (!TextUtils.isEmpty(apnString)) types.add(apnString);
            remainingApnTypes &= ~highestApnTypeBit;
        }
        return TextUtils.join(",", types);
    }

    private void enforceNotifyPermissionOrCarrierPrivilege(String method) {
        if (checkNotifyPermission()) {
            return;
        }

        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(mContext,
                SubscriptionManager.getDefaultSubscriptionId(), method);
    }

    private boolean checkNotifyPermission(String method) {
        if (checkNotifyPermission()) {
            return true;
        }
        String msg = "Modify Phone State Permission Denial: " + method + " from pid="
                + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
        if (DBG) log(msg);
        return false;
    }

    private boolean checkNotifyPermission() {
        return mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkListenerPermission(Set<Integer> events, int subId, String callingPackage,
            @Nullable String callingFeatureId, String message) {
        boolean isPermissionCheckSuccessful = true;
        if (isLocationPermissionRequired(events)) {
            LocationAccessPolicy.LocationPermissionQuery.Builder locationQueryBuilder =
                    new LocationAccessPolicy.LocationPermissionQuery.Builder()
                            .setCallingPackage(callingPackage)
                            .setCallingFeatureId(callingFeatureId)
                            .setMethod(message + " events: " + events)
                            .setCallingPid(Binder.getCallingPid())
                            .setCallingUid(Binder.getCallingUid());
            // Everything that requires fine location started in Q. So far...
            locationQueryBuilder.setMinSdkVersionForFine(Build.VERSION_CODES.Q);
            // If we're enforcing fine starting in Q, we also want to enforce coarse even for
            // older SDK versions.
            locationQueryBuilder.setMinSdkVersionForCoarse(0);
            locationQueryBuilder.setMinSdkVersionForEnforcement(0);
            LocationAccessPolicy.LocationPermissionResult result =
                    LocationAccessPolicy.checkLocationPermission(
                            mContext, locationQueryBuilder.build());
            switch (result) {
                case DENIED_HARD:
                    throw new SecurityException("Unable to listen for events " + events + " due to "
                            + "insufficient location permissions.");
                case DENIED_SOFT:
                    isPermissionCheckSuccessful = false;
            }
        }

        if (isPhoneStatePermissionRequired(events, callingPackage, Binder.getCallingUserHandle())) {
            if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                    mContext, subId, callingPackage, callingFeatureId, message)) {
                isPermissionCheckSuccessful = false;
            }
        }

        if (isPrecisePhoneStatePermissionRequired(events)) {
            // check if calling app has either permission READ_PRECISE_PHONE_STATE
            // or with carrier privileges
            try {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
            } catch (SecurityException se) {
                TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(mContext, subId, message);
            }
        }

        if (isActiveEmergencySessionPermissionRequired(events)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION, null);
        }

        if (isPrivilegedPhoneStatePermissionRequired(events)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }
        return isPermissionCheckSuccessful;
    }

    private void handleRemoveListLocked() {
        int size = mRemoveList.size();
        if (VDBG) log("handleRemoveListLocked: mRemoveList.size()=" + size);
        if (size > 0) {
            for (IBinder b : mRemoveList) {
                remove(b);
            }
            mRemoveList.clear();
        }
    }

    private boolean validateEventAndUserLocked(Record r, int event) {
        int foregroundUser;
        final long callingIdentity = Binder.clearCallingIdentity();
        boolean valid = false;
        try {
            foregroundUser = ActivityManager.getCurrentUser();
            valid = UserHandle.getUserId(r.callerUid) == foregroundUser
                    && r.matchTelephonyCallbackEvent(event);
            if (DBG | DBG_LOC) {
                log("validateEventAndUserLocked: valid=" + valid
                        + " r.callerUid=" + r.callerUid + " foregroundUser=" + foregroundUser
                        + " r.eventList=" + r.eventList + " event=" + event);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return valid;
    }

    private boolean validatePhoneId(int phoneId) {
        // Call getActiveModemCount to get the latest value instead of depending on mNumPhone
        boolean valid = (phoneId >= 0) && (phoneId < getTelephonyManager().getActiveModemCount());
        if (VDBG) log("validatePhoneId: " + valid);
        return valid;
    }

    private static void log(String s) {
        Rlog.d(TAG, s);
    }

    private static void loge(String s) {
        Rlog.e(TAG, s);
    }

    /**
     * Match the sub id or phone id of the event to the record
     *
     * We follow the rules below:
     * 1) If sub id of the event is invalid, phone id should be used.
     * 2) The event on default sub should be notified to the records
     * which register the default sub id.
     * 3) Sub id should be exactly matched for all other cases.
     */
    boolean idMatch(Record r, int subId, int phoneId) {

        if (subId < 0) {
            // Invalid case, we need compare phoneId.
            return (r.phoneId == phoneId);
        }
        if (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            return (subId == mDefaultSubId);
        } else {
            return (r.subId == subId);
        }
    }

    private boolean checkFineLocationAccess(Record r) {
        return checkFineLocationAccess(r, Build.VERSION_CODES.BASE);
    }

    private boolean checkCoarseLocationAccess(Record r) {
        return checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE);
    }

    /**
     * Note -- this method should only be used at the site of a permission check if you need to
     * explicitly allow apps below a certain SDK level access regardless of location permissions.
     * If you don't need app compat logic, use {@link #checkFineLocationAccess(Record)}.
     */
    private boolean checkFineLocationAccess(Record r, int minSdk) {
        if (r.renounceFineLocationAccess) {
            return false;
        }
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingFeatureId(r.callingFeatureId)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setLogAsInfo(true) // we don't need to log an error every time we push
                        .setMinSdkVersionForFine(minSdk)
                        .setMinSdkVersionForCoarse(minSdk)
                        .setMinSdkVersionForEnforcement(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    /**
     * Note -- this method should only be used at the site of a permission check if you need to
     * explicitly allow apps below a certain SDK level access regardless of location permissions.
     * If you don't need app compat logic, use {@link #checkCoarseLocationAccess(Record)}.
     */
    private boolean checkCoarseLocationAccess(Record r, int minSdk) {
        if (r.renounceCoarseLocationAccess) {
            return false;
        }
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingFeatureId(r.callingFeatureId)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setLogAsInfo(true) // we don't need to log an error every time we push
                        .setMinSdkVersionForCoarse(minSdk)
                        .setMinSdkVersionForFine(Integer.MAX_VALUE)
                        .setMinSdkVersionForEnforcement(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    private void checkPossibleMissNotify(Record r, int phoneId) {
        Set<Integer> events = r.eventList;

        if (events == null || events.isEmpty()) {
            log("checkPossibleMissNotify: events = null.");
            return;
        }

        if ((events.contains(TelephonyCallback.EVENT_SERVICE_STATE_CHANGED))) {
            try {
                if (VDBG) log("checkPossibleMissNotify: onServiceStateChanged state=" +
                        mServiceState[phoneId]);
                ServiceState ss = new ServiceState(mServiceState[phoneId]);
                if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onServiceStateChanged(ss);
                } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onServiceStateChanged(
                            ss.createLocationInfoSanitizedCopy(false));
                } else {
                    r.callback.onServiceStateChanged(
                            ss.createLocationInfoSanitizedCopy(true));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED)) {
            try {
                if (mSignalStrength[phoneId] != null) {
                    SignalStrength signalStrength = mSignalStrength[phoneId];
                    if (DBG) {
                        log("checkPossibleMissNotify: onSignalStrengthsChanged SS="
                                + signalStrength);
                    }
                    r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED)) {
            try {
                if (mSignalStrength[phoneId] != null) {
                    int gsmSignalStrength = mSignalStrength[phoneId]
                            .getGsmSignalStrength();
                    if (DBG) {
                        log("checkPossibleMissNotify: onSignalStrengthChanged SS="
                                + gsmSignalStrength);
                    }
                    r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                            : gsmSignalStrength));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (validateEventAndUserLocked(r, TelephonyCallback.EVENT_CELL_INFO_CHANGED)) {
            try {
                if (DBG_LOC) {
                    log("checkPossibleMissNotify: onCellInfoChanged[" + phoneId + "] = "
                            + mCellInfo.get(phoneId));
                }
                if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                        && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onUserMobileDataStateChanged phoneId="
                            + phoneId + " umds=" + mUserMobileDataState[phoneId]);
                }
                r.callback.onUserMobileDataStateChanged(mUserMobileDataState[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onDisplayInfoChanged phoneId="
                            + phoneId + " dpi=" + mTelephonyDisplayInfos[phoneId]);
                }
                if (mTelephonyDisplayInfos[phoneId] != null) {
                    r.callback.onDisplayInfoChanged(mTelephonyDisplayInfos[phoneId]);
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onMessageWaitingIndicatorChanged phoneId="
                            + phoneId + " mwi=" + mMessageWaiting[phoneId]);
                }
                r.callback.onMessageWaitingIndicatorChanged(
                        mMessageWaiting[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onCallForwardingIndicatorChanged phoneId="
                        + phoneId + " cfi=" + mCallForwarding[phoneId]);
                }
                r.callback.onCallForwardingIndicatorChanged(
                        mCallForwarding[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (validateEventAndUserLocked(r, TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)) {
            try {
                if (DBG_LOC) {
                    log("checkPossibleMissNotify: onCellLocationChanged mCellIdentity = "
                            + mCellIdentity[phoneId]);
                }
                if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                        && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    // null will be translated to empty CellLocation object in client.
                    r.callback.onCellLocationChanged(mCellIdentity[phoneId]);
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED)) {
            try {
                if (DBG) {
                    log("checkPossibleMissNotify: onDataConnectionStateChanged(mDataConnectionState"
                            + "=" + mDataConnectionState[phoneId]
                            + ", mDataConnectionNetworkType=" + mDataConnectionNetworkType[phoneId]
                            + ")");
                }
                r.callback.onDataConnectionStateChanged(mDataConnectionState[phoneId],
                        mDataConnectionNetworkType[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @param type for which network type is returned
     * @return the name of the radio technology
     *
     */
    private String getNetworkTypeName(@Annotation.NetworkType int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
                return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return "CDMA - EvDo rev. 0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return "CDMA - EvDo rev. A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "CDMA - EvDo rev. B";
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "CDMA - 1xRTT";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "CDMA - eHRPD";
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "iDEN";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_GSM:
                return "GSM";
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "TD_SCDMA";
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "IWLAN";

            //TODO: This network type is marked as hidden because it is not a
            // true network type and we are looking to remove it completely from the available list
            // of network types.  Since this method is only used for logging, in the event that this
            // network type is selected, the log will read as "Unknown."
            //case TelephonyManager.NETWORK_TYPE_LTE_CA:
            //    return "LTE_CA";

            case TelephonyManager.NETWORK_TYPE_NR:
                return "NR";
            default:
                return "UNKNOWN";
        }
    }

    /** Returns a new PreciseCallState object with default values. */
    private static PreciseCallState createPreciseCallState() {
        return new PreciseCallState(PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            DisconnectCause.NOT_VALID,
            PreciseDisconnectCause.NOT_VALID);
    }

    /** Returns a new CallQuality object with default values. */
    private static CallQuality createCallQuality() {
        return new CallQuality(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private int getPhoneIdFromSubId(int subId) {
        SubscriptionManager subManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subManager == null) return INVALID_SIM_SLOT_INDEX;

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }

        SubscriptionInfo info = subManager.getActiveSubscriptionInfo(subId);
        if (info == null) return INVALID_SIM_SLOT_INDEX;
        return info.getSimSlotIndex();
    }

    /**
     * On certain build types, we should redact information by default. UID information will be
     * preserved in the same log line, so no debugging capability is lost in full bug reports.
     * However, privacy-constrained bug report types (e.g. connectivity) cannot display raw
     * package names on user builds as it's considered an information leak.
     */
    private static String pii(String packageName) {
        return Build.IS_DEBUGGABLE ? packageName : "***";
    }

    /** Redacts an entire list of package names if necessary. */
    private static String pii(List<String> packageNames) {
        if (packageNames.isEmpty() || Build.IS_DEBUGGABLE) return packageNames.toString();
        return "[***, size=" + packageNames.size() + "]";
    }
}
