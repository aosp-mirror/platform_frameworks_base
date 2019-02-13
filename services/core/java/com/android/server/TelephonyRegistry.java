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

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.CallAttributes;
import android.telephony.CallQuality;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.DataFailCause;
import android.telephony.DisconnectCause;
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
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;
import android.util.LocalLog;
import android.util.StatsLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstantConversions;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.am.BatteryStatsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Since phone process can be restarted, this class provides a centralized place
 * that applications can register and be called back from.
 *
 * Change-Id: I450c968bda93767554b5188ee63e10c9f43c5aa4 fixes bugs 16148026
 * and 15973975 by saving the phoneId of the registrant and then using the
 * phoneId when deciding to to make a callback. This is necessary because
 * a subId changes from to a dummy value when a SIM is removed and thus won't
 * compare properly. Because SubscriptionManager.getPhoneId(int subId) handles
 * the dummy value conversion we properly do the callbacks.
 *
 * Eventually we may want to remove the notion of dummy value but for now this
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

        IBinder binder;

        TelephonyRegistryDeathRecipient deathRecipient;

        IPhoneStateListener callback;
        IOnSubscriptionsChangedListener onSubscriptionsChangedListenerCallback;
        IOnSubscriptionsChangedListener onOpportunisticSubscriptionsChangedListenerCallback;

        int callerUid;
        int callerPid;

        int events;

        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;

        boolean matchPhoneStateListenerEvent(int events) {
            return (callback != null) && ((events & this.events) != 0);
        }

        boolean matchOnSubscriptionsChangedListener() {
            return (onSubscriptionsChangedListenerCallback != null);
        }

        boolean matchOnOpportunisticSubscriptionsChangedListener() {
            return (onOpportunisticSubscriptionsChangedListenerCallback != null);
        }

        boolean canReadCallLog() {
            try {
                return TelephonyPermissions.checkReadCallLog(
                        context, subId, callerPid, callerUid, callingPackage);
            } catch (SecurityException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "{callingPackage=" + callingPackage + " binder=" + binder
                    + " callback=" + callback
                    + " onSubscriptionsChangedListenererCallback="
                    + onSubscriptionsChangedListenerCallback
                    + " onOpportunisticSubscriptionsChangedListenererCallback="
                    + onOpportunisticSubscriptionsChangedListenerCallback
                    + " callerUid=" + callerUid + " subId=" + subId + " phoneId=" + phoneId
                    + " events=" + Integer.toHexString(events) + "}";
        }
    }

    private final Context mContext;

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

    private int[] mNetworkType;

    private int[] mVoiceActivationState;

    private int[] mDataActivationState;

    private boolean[] mUserMobileDataState;

    private SignalStrength[] mSignalStrength;

    private boolean[] mMessageWaiting;

    private boolean[] mCallForwarding;

    private int[] mDataActivity;

    // Connection state of default APN type data (i.e. internet) of phones
    private int[] mDataConnectionState;

    private Bundle[] mCellLocation;

    private int[] mDataConnectionNetworkType;

    private int mOtaspMode = TelephonyManager.OTASP_UNKNOWN;

    private ArrayList<List<CellInfo>> mCellInfo = null;

    private ArrayList<List<PhysicalChannelConfig>> mPhysicalChannelConfigs;

    private Map<Integer, List<EmergencyNumber>> mEmergencyNumberList;

    private CallQuality mCallQuality = new CallQuality();

    private CallAttributes mCallAttributes = new CallAttributes(new PreciseCallState(),
            TelephonyManager.NETWORK_TYPE_UNKNOWN, new CallQuality());

    private int[] mSrvccState;

    private int mDefaultSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private int mDefaultPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;

    private int mRingingCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;

    private int mForegroundCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;

    private int mBackgroundCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;

    private PreciseCallState mPreciseCallState = new PreciseCallState();

    private int mCallDisconnectCause = DisconnectCause.NOT_VALID;

    private List<ImsReasonInfo> mImsReasonInfo = null;

    private int mCallPreciseDisconnectCause = PreciseDisconnectCause.NOT_VALID;

    private boolean mCarrierNetworkChangeState = false;

    private PhoneCapability mPhoneCapability = null;

    @TelephonyManager.RadioPowerState
    private int mRadioPowerState = TelephonyManager.RADIO_POWER_UNAVAILABLE;

    private int mPreferredDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private final LocalLog mLocalLog = new LocalLog(100);

    private PreciseDataConnectionState mPreciseDataConnectionState =
                new PreciseDataConnectionState();

    // Nothing here yet, but putting it here in case we want to add more in the future.
    static final int ENFORCE_COARSE_LOCATION_PERMISSION_MASK = 0;

    static final int ENFORCE_FINE_LOCATION_PERMISSION_MASK =
            PhoneStateListener.LISTEN_CELL_LOCATION
                    | PhoneStateListener.LISTEN_CELL_INFO;

    static final int ENFORCE_PHONE_STATE_PERMISSION_MASK =
                PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR
                        | PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR
                        | PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST;

    static final int PRECISE_PHONE_STATE_PERMISSION_MASK =
                PhoneStateListener.LISTEN_PRECISE_CALL_STATE |
                PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE;

    private static final int MSG_USER_SWITCHED = 1;
    private static final int MSG_UPDATE_DEFAULT_SUB = 2;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_SWITCHED: {
                    if (VDBG) log("MSG_USER_SWITCHED userId=" + msg.arg1);
                    int numPhones = TelephonyManager.getDefault().getPhoneCount();
                    for (int sub = 0; sub < numPhones; sub++) {
                        TelephonyRegistry.this.notifyCellLocationForSubscriber(sub,
                                mCellLocation[sub]);
                    }
                    break;
                }
                case MSG_UPDATE_DEFAULT_SUB: {
                    int newDefaultPhoneId = msg.arg1;
                    int newDefaultSubId = (Integer)(msg.obj);
                    if (VDBG) {
                        log("MSG_UPDATE_DEFAULT_SUB:current mDefaultSubId=" + mDefaultSubId
                            + " current mDefaultPhoneId=" + mDefaultPhoneId + " newDefaultSubId= "
                            + newDefaultSubId + " newDefaultPhoneId=" + newDefaultPhoneId);
                    }

                    //Due to possible risk condition,(notify call back using the new
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
            } else if (action.equals(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)) {
                Integer newDefaultSubIdObj = new Integer(intent.getIntExtra(
                        PhoneConstants.SUBSCRIPTION_KEY,
                        SubscriptionManager.getDefaultSubscriptionId()));
                int newDefaultPhoneId = intent.getIntExtra(PhoneConstants.SLOT_KEY,
                    SubscriptionManager.getPhoneId(mDefaultSubId));
                if (DBG) {
                    log("onReceive:current mDefaultSubId=" + mDefaultSubId
                        + " current mDefaultPhoneId=" + mDefaultPhoneId + " newDefaultSubId= "
                        + newDefaultSubIdObj + " newDefaultPhoneId=" + newDefaultPhoneId);
                }

                if(validatePhoneId(newDefaultPhoneId) && (!newDefaultSubIdObj.equals(mDefaultSubId)
                        || (newDefaultPhoneId != mDefaultPhoneId))) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_DEFAULT_SUB,
                            newDefaultPhoneId, 0, newDefaultSubIdObj));
                }
            }
        }
    };

    // we keep a copy of all of the state so we can send it out when folks
    // register for it
    //
    // In these calls we call with the lock held. This is safe becasuse remote
    // calls go through a oneway interface and local calls going through a
    // handler before they get to app code.

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public TelephonyRegistry(Context context) {
        CellLocation  location = CellLocation.getEmpty();

        mContext = context;
        mBatteryStats = BatteryStatsService.getService();

        int numPhones = TelephonyManager.getDefault().getPhoneCount();
        if (DBG) log("TelephonyRegistry: ctor numPhones=" + numPhones);
        mNumPhones = numPhones;
        mCallState = new int[numPhones];
        mDataActivity = new int[numPhones];
        mDataConnectionState = new int[numPhones];
        mDataConnectionNetworkType = new int[numPhones];
        mCallIncomingNumber = new String[numPhones];
        mServiceState = new ServiceState[numPhones];
        mNetworkType = new int[numPhones];
        mVoiceActivationState = new int[numPhones];
        mDataActivationState = new int[numPhones];
        mUserMobileDataState = new boolean[numPhones];
        mSignalStrength = new SignalStrength[numPhones];
        mMessageWaiting = new boolean[numPhones];
        mCallForwarding = new boolean[numPhones];
        mCellLocation = new Bundle[numPhones];
        mCellInfo = new ArrayList<List<CellInfo>>();
        mSrvccState = new int[numPhones];
        mImsReasonInfo = new ArrayList<ImsReasonInfo>();
        mPhysicalChannelConfigs = new ArrayList<List<PhysicalChannelConfig>>();
        mEmergencyNumberList = new HashMap<>();
        for (int i = 0; i < numPhones; i++) {
            mCallState[i] =  TelephonyManager.CALL_STATE_IDLE;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
            mVoiceActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mDataActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mCallIncomingNumber[i] =  "";
            mServiceState[i] =  new ServiceState();
            mNetworkType[i] = mServiceState[i].getVoiceNetworkType();
            mSignalStrength[i] =  new SignalStrength();
            mUserMobileDataState[i] = false;
            mMessageWaiting[i] =  false;
            mCallForwarding[i] =  false;
            mCellLocation[i] = new Bundle();
            mCellInfo.add(i, null);
            mImsReasonInfo.add(i, null);
            mSrvccState[i] = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;
            mPhysicalChannelConfigs.add(i, new ArrayList<PhysicalChannelConfig>());
        }

        // Note that location can be null for non-phone builds like
        // like the generic one.
        if (location != null) {
            for (int i = 0; i < numPhones; i++) {
                location.fillInNotifierBundle(mCellLocation[i]);
            }
        }

        mAppOps = mContext.getSystemService(AppOpsManager.class);
    }

    public void systemRunning() {
        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        log("systemRunning register for intents");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void addOnSubscriptionsChangedListener(String callingPackage,
            IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen oscl: E pkg=" + callingPackage + " myUserId=" + UserHandle.myUserId()
                + " callerUserId="  + callerUserId + " callback=" + callback
                + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            Record r = add(b);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.events = 0;
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
            IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen ooscl: E pkg=" + callingPackage + " myUserId=" + UserHandle.myUserId()
                    + " callerUserId="  + callerUserId + " callback=" + callback
                    + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            Record r = add(b);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onOpportunisticSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.events = 0;
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
    public void listen(String pkgForDebug, IPhoneStateListener callback, int events,
            boolean notifyNow) {
        listenForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, pkgForDebug, callback,
                events, notifyNow);
    }

    @Override
    public void listenForSubscriber(int subId, String pkgForDebug, IPhoneStateListener callback,
            int events, boolean notifyNow) {
        listen(pkgForDebug, callback, events, notifyNow, subId);
    }

    private void listen(String callingPackage, IPhoneStateListener callback, int events,
            boolean notifyNow, int subId) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen: E pkg=" + callingPackage + " events=0x" + Integer.toHexString(events)
                + " notifyNow=" + notifyNow + " subId=" + subId + " myUserId="
                + UserHandle.myUserId() + " callerUserId=" + callerUserId);
        }

        if (events != PhoneStateListener.LISTEN_NONE) {
            // Checks permission and throws SecurityException for disallowed operations. For pre-M
            // apps whose runtime permission has been revoked, we return immediately to skip sending
            // events to the app without crashing it.
            if (!checkListenerPermission(events, subId, callingPackage, "listen")) {
                return;
            }

            int phoneId = SubscriptionManager.getPhoneId(subId);
            synchronized (mRecords) {
                // register
                IBinder b = callback.asBinder();
                Record r = add(b);

                if (r == null) {
                    return;
                }

                r.context = mContext;
                r.callback = callback;
                r.callingPackage = callingPackage;
                r.callerUid = Binder.getCallingUid();
                r.callerPid = Binder.getCallingPid();
                // Legacy applications pass SubscriptionManager.DEFAULT_SUB_ID,
                // force all illegal subId to SubscriptionManager.DEFAULT_SUB_ID
                if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                    r.subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
                 } else {//APP specify subID
                    r.subId = subId;
                }
                r.phoneId = phoneId;
                r.events = events;
                if (DBG) {
                    log("listen:  Register r=" + r + " r.subId=" + r.subId + " phoneId=" + phoneId);
                }
                if (notifyNow && validatePhoneId(phoneId)) {
                    if ((events & PhoneStateListener.LISTEN_SERVICE_STATE) != 0) {
                        try {
                            if (VDBG) log("listen: call onSSC state=" + mServiceState[phoneId]);
                            ServiceState rawSs = new ServiceState(mServiceState[phoneId]);
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onServiceStateChanged(rawSs);
                            } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onServiceStateChanged(rawSs.sanitizeLocationInfo(false));
                            } else {
                                r.callback.onServiceStateChanged(rawSs.sanitizeLocationInfo(true));
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTH) != 0) {
                        try {
                            int gsmSignalStrength = mSignalStrength[phoneId]
                                    .getGsmSignalStrength();
                            r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                                    : gsmSignalStrength));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) != 0) {
                        try {
                            r.callback.onMessageWaitingIndicatorChanged(
                                    mMessageWaiting[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) != 0) {
                        try {
                            r.callback.onCallForwardingIndicatorChanged(
                                    mCallForwarding[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_LOCATION)) {
                        try {
                            if (DBG_LOC) log("listen: mCellLocation = "
                                    + mCellLocation[phoneId]);
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onCellLocationChanged(
                                        new Bundle(mCellLocation[phoneId]));
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_STATE) != 0) {
                        try {
                            r.callback.onCallStateChanged(mCallState[phoneId],
                                     getCallIncomingNumber(r, phoneId));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) != 0) {
                        try {
                            r.callback.onDataConnectionStateChanged(mDataConnectionState[phoneId],
                                mDataConnectionNetworkType[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_DATA_ACTIVITY) != 0) {
                        try {
                            r.callback.onDataActivity(mDataActivity[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
                        try {
                            r.callback.onSignalStrengthsChanged(mSignalStrength[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_OTASP_CHANGED) != 0) {
                        try {
                            r.callback.onOtaspChanged(mOtaspMode);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_INFO)) {
                        try {
                            if (DBG_LOC) log("listen: mCellInfo[" + phoneId + "] = "
                                    + mCellInfo.get(phoneId));
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                            }
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PRECISE_CALL_STATE) != 0) {
                        try {
                            r.callback.onPreciseCallStateChanged(mPreciseCallState);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_DISCONNECT_CAUSES) != 0) {
                        try {
                            r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause,
                                    mCallPreciseDisconnectCause);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES) != 0) {
                        try {
                            r.callback.onImsCallDisconnectCauseChanged(mImsReasonInfo.get(phoneId));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE) != 0) {
                        try {
                            r.callback.onPreciseDataConnectionStateChanged(
                                    mPreciseDataConnectionState);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE) != 0) {
                        try {
                            r.callback.onCarrierNetworkChange(mCarrierNetworkChangeState);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE) !=0) {
                        try {
                            r.callback.onVoiceActivationStateChanged(mVoiceActivationState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_DATA_ACTIVATION_STATE) !=0) {
                        try {
                            r.callback.onDataActivationStateChanged(mDataActivationState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) != 0) {
                        try {
                            r.callback.onUserMobileDataStateChanged(mUserMobileDataState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PHYSICAL_CHANNEL_CONFIGURATION) != 0) {
                        try {
                            r.callback.onPhysicalChannelConfigurationChanged(
                                    mPhysicalChannelConfigs.get(phoneId));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST) != 0) {
                        try {
                            r.callback.onEmergencyNumberListChanged(mEmergencyNumberList);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PHONE_CAPABILITY_CHANGE) != 0) {
                        try {
                            r.callback.onPhoneCapabilityChanged(mPhoneCapability);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_PREFERRED_DATA_SUBID_CHANGE) != 0) {
                        try {
                            r.callback.onPreferredDataSubIdChanged(mPreferredDataSubId);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED) != 0) {
                        try {
                            r.callback.onRadioPowerStateChanged(mRadioPowerState);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED) != 0) {
                        try {
                            r.callback.onSrvccStateChanged(mSrvccState[phoneId]);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED) != 0) {
                        try {
                            r.callback.onCallAttributesChanged(mCallAttributes);
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                }
            }
        } else {
            if(DBG) log("listen: Unregister");
            remove(callback.asBinder());
        }
    }

    private String getCallIncomingNumber(Record record, int phoneId) {
        // Only reveal the incoming number if the record has read call log permission.
        return record.canReadCallLog() ? mCallIncomingNumber[phoneId] : "";
    }

    private Record add(IBinder binder) {
        Record r;

        synchronized (mRecords) {
            final int N = mRecords.size();
            for (int i = 0; i < N; i++) {
                r = mRecords.get(i);
                if (binder == r.binder) {
                    // Already existed.
                    return r;
                }
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

    public void notifyCallState(int state, String phoneNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }

        if (VDBG) {
            log("notifyCallState: state=" + state + " phoneNumber=" + phoneNumber);
        }

        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_CALL_STATE) &&
                        (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                    try {
                        // Ensure the listener has read call log permission; if they do not return
                        // an empty phone number.
                        String phoneNumberOrEmpty = r.canReadCallLog() ? phoneNumber : "";
                        r.callback.onCallStateChanged(state, phoneNumberOrEmpty);
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
                SubscriptionManager.INVALID_PHONE_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    public void notifyCallStateForPhoneId(int phoneId, int subId, int state,
                String incomingNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallStateForPhoneId: subId=" + subId
                + " state=" + state + " incomingNumber=" + incomingNumber);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallState[phoneId] = state;
                mCallIncomingNumber[phoneId] = incomingNumber;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_CALL_STATE) &&
                            (r.subId == subId) &&
                            (r.subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                        try {
                            String incomingNumberOrEmpty = getCallIncomingNumber(r, phoneId);
                            r.callback.onCallStateChanged(state, incomingNumberOrEmpty);
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

        synchronized (mRecords) {
            String str = "notifyServiceStateForSubscriber: subId=" + subId + " phoneId=" + phoneId
                    + " state=" + state;
            if (VDBG) {
                log(str);
            }
            mLocalLog.log(str);
            if (validatePhoneId(phoneId)) {
                mServiceState[phoneId] = state;

                boolean notifyCallAttributes = true;
                if (mNetworkType[phoneId] != mServiceState[phoneId].getVoiceNetworkType()) {
                    mNetworkType[phoneId] = state.getVoiceNetworkType();
                    mCallAttributes = new CallAttributes(mPreciseCallState, mNetworkType[phoneId],
                            mCallQuality);
                } else {
                    // No change to network type, so no need to notify call attributes
                    notifyCallAttributes = false;
                }

                if (mCallQuality == null) {
                    // No call quality reported yet, so no need to notify call attributes
                    notifyCallAttributes = false;
                }

                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifyServiceStateForSubscriber: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " state=" + state);
                    }
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_SERVICE_STATE) &&
                            idMatch(r.subId, subId, phoneId)) {

                        try {
                            ServiceState stateToSend;
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                stateToSend = new ServiceState(state);
                            } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                                stateToSend = state.sanitizeLocationInfo(false);
                            } else {
                                stateToSend = state.sanitizeLocationInfo(true);
                            }
                            if (DBG) {
                                log("notifyServiceStateForSubscriber: callback.onSSC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + state);
                            }
                            r.callback.onServiceStateChanged(stateToSend);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (notifyCallAttributes && r.matchPhoneStateListenerEvent(
                                    PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED)) {
                        try {
                            r.callback.onCallAttributesChanged(mCallAttributes);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifyServiceStateForSubscriber: INVALID phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
        broadcastServiceStateChanged(state, phoneId, subId);
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
                    case PhoneConstants.SIM_ACTIVATION_TYPE_VOICE:
                        mVoiceActivationState[phoneId] = activationState;
                        break;
                    case PhoneConstants.SIM_ACTIVATION_TYPE_DATA:
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
                        if ((activationType == PhoneConstants.SIM_ACTIVATION_TYPE_VOICE) &&
                                r.matchPhoneStateListenerEvent(
                                        PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE) &&
                                idMatch(r.subId, subId, phoneId)) {
                            if (DBG) {
                                log("notifyVoiceActivationStateForPhoneId: callback.onVASC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + activationState);
                            }
                            r.callback.onVoiceActivationStateChanged(activationState);
                        }
                        if ((activationType == PhoneConstants.SIM_ACTIVATION_TYPE_DATA) &&
                                r.matchPhoneStateListenerEvent(
                                        PhoneStateListener.LISTEN_DATA_ACTIVATION_STATE) &&
                                idMatch(r.subId, subId, phoneId)) {
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
                    if (r.matchPhoneStateListenerEvent(
                                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) &&
                            idMatch(r.subId, subId, phoneId)) {
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
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_SIGNAL_STRENGTH) &&
                            idMatch(r.subId, subId, phoneId)){
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
        enforceNotifyPermissionOrCarrierPrivilege("notifyCarrierNetworkChange()");

        if (VDBG) {
            log("notifyCarrierNetworkChange: active=" + active);
        }

        synchronized (mRecords) {
            mCarrierNetworkChangeState = active;
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE)) {
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
        if (!checkNotifyPermission("notifyCellInfo()")) {
            return;
        }
        if (VDBG) {
            log("notifyCellInfoForSubscriber: subId=" + subId
                + " cellInfo=" + cellInfo);
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCellInfo.set(phoneId, cellInfo);
                for (Record r : mRecords) {
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_INFO) &&
                            idMatch(r.subId, subId, phoneId) &&
                            checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellInfo: mCellInfo=" + cellInfo + " r=" + r);
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

    public void notifyPhysicalChannelConfiguration(List<PhysicalChannelConfig> configs) {
        notifyPhysicalChannelConfigurationForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                configs);
    }

    public void notifyPhysicalChannelConfigurationForSubscriber(int subId,
            List<PhysicalChannelConfig> configs) {
        if (!checkNotifyPermission("notifyPhysicalChannelConfiguration()")) {
            return;
        }

        if (VDBG) {
            log("notifyPhysicalChannelConfiguration: subId=" + subId + " configs=" + configs);
        }

        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
            if (validatePhoneId(phoneId)) {
                mPhysicalChannelConfigs.set(phoneId, configs);
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_PHYSICAL_CHANNEL_CONFIGURATION)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyPhysicalChannelConfiguration: mPhysicalChannelConfigs="
                                        + configs + " r=" + r);
                            }
                            r.callback.onPhysicalChannelConfigurationChanged(configs);
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
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) &&
                            idMatch(r.subId, subId, phoneId)) {
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
            log("notifyUserMobileDataStateChangedForSubscriberPhoneID: subId=" + phoneId
                    + " state=" + state);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mMessageWaiting[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) &&
                            idMatch(r.subId, subId, phoneId)) {
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
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallForwarding[phoneId] = cfi;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) &&
                            idMatch(r.subId, subId, phoneId)) {
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

    public void notifyDataActivity(int state) {
        notifyDataActivityForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, state);
    }

    public void notifyDataActivityForSubscriber(int subId, int state) {
        if (!checkNotifyPermission("notifyDataActivity()" )) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mDataActivity[phoneId] = state;
                for (Record r : mRecords) {
                    // Notify by correct subId.
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_DATA_ACTIVITY) &&
                            idMatch(r.subId, subId, phoneId)) {
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

    public void notifyDataConnection(int state, boolean isDataAllowed, String apn, String apnType,
                                     LinkProperties linkProperties,
                                     NetworkCapabilities networkCapabilities, int networkType,
                                     boolean roaming) {
        notifyDataConnectionForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, state,
                isDataAllowed, apn, apnType, linkProperties,
                networkCapabilities, networkType, roaming);
    }

    public void notifyDataConnectionForSubscriber(int subId, int state, boolean isDataAllowed,
                                                  String apn, String apnType,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int networkType, boolean roaming) {
        if (!checkNotifyPermission("notifyDataConnection()" )) {
            return;
        }
        if (VDBG) {
            log("notifyDataConnectionForSubscriber: subId=" + subId
                + " state=" + state + " isDataAllowed=" + isDataAllowed
                + "' apn='" + apn + "' apnType=" + apnType + " networkType=" + networkType
                + " mRecords.size()=" + mRecords.size());
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                // We only call the callback when the change is for default APN type.
                if (PhoneConstants.APN_TYPE_DEFAULT.equals(apnType)
                        && (mDataConnectionState[phoneId] != state
                        || mDataConnectionNetworkType[phoneId] != networkType)) {
                    String str = "onDataConnectionStateChanged(" + state
                            + ", " + networkType + ")";
                    log(str);
                    mLocalLog.log(str);
                    for (Record r : mRecords) {
                        if (r.matchPhoneStateListenerEvent(
                                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) &&
                                idMatch(r.subId, subId, phoneId)) {
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
                    handleRemoveListLocked();

                    mDataConnectionState[phoneId] = state;
                    mDataConnectionNetworkType[phoneId] = networkType;
                }
                mPreciseDataConnectionState = new PreciseDataConnectionState(state, networkType,
                        ApnSetting.getApnTypesBitmaskFromString(apnType), apn,
                        linkProperties, DataFailCause.NONE);
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE)) {
                        try {
                            r.callback.onPreciseDataConnectionStateChanged(
                                    mPreciseDataConnectionState);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastDataConnectionStateChanged(state, isDataAllowed, apn, apnType, linkProperties,
                networkCapabilities, roaming, subId);
        broadcastPreciseDataConnectionStateChanged(state, networkType, apnType, apn,
                linkProperties, DataFailCause.NONE);
    }

    public void notifyDataConnectionFailed(String apnType) {
         notifyDataConnectionFailedForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                 apnType);
    }

    public void notifyDataConnectionFailedForSubscriber(int subId, String apnType) {
        if (!checkNotifyPermission("notifyDataConnectionFailed()")) {
            return;
        }
        if (VDBG) {
            log("notifyDataConnectionFailedForSubscriber: subId=" + subId
                    + " apnType=" + apnType);
        }
        synchronized (mRecords) {
            mPreciseDataConnectionState = new PreciseDataConnectionState(
                    TelephonyManager.DATA_UNKNOWN,TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    ApnSetting.getApnTypesBitmaskFromString(apnType), "", null,
                    DataFailCause.NONE);
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE)) {
                    try {
                        r.callback.onPreciseDataConnectionStateChanged(mPreciseDataConnectionState);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastDataConnectionFailed(apnType, subId);
        broadcastPreciseDataConnectionStateChanged(TelephonyManager.DATA_UNKNOWN,
                TelephonyManager.NETWORK_TYPE_UNKNOWN, apnType, "", null,
                DataFailCause.NONE);
    }

    public void notifyCellLocation(Bundle cellLocation) {
         notifyCellLocationForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cellLocation);
    }

    public void notifyCellLocationForSubscriber(int subId, Bundle cellLocation) {
        log("notifyCellLocationForSubscriber: subId=" + subId
                + " cellLocation=" + cellLocation);
        if (!checkNotifyPermission("notifyCellLocation()")) {
            return;
        }
        if (VDBG) {
            log("notifyCellLocationForSubscriber: subId=" + subId
                + " cellLocation=" + cellLocation);
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCellLocation[phoneId] = cellLocation;
                for (Record r : mRecords) {
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_LOCATION) &&
                            idMatch(r.subId, subId, phoneId) &&
                            checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellLocation: cellLocation=" + cellLocation
                                        + " r=" + r);
                            }
                            r.callback.onCellLocationChanged(new Bundle(cellLocation));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyOtaspChanged(int otaspMode) {
        if (!checkNotifyPermission("notifyOtaspChanged()" )) {
            return;
        }
        synchronized (mRecords) {
            mOtaspMode = otaspMode;
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_OTASP_CHANGED)) {
                    try {
                        r.callback.onOtaspChanged(otaspMode);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPreciseCallState(int ringingCallState, int foregroundCallState,
            int backgroundCallState, int phoneId) {
        if (!checkNotifyPermission("notifyPreciseCallState()")) {
            return;
        }
        synchronized (mRecords) {
            mRingingCallState = ringingCallState;
            mForegroundCallState = foregroundCallState;
            mBackgroundCallState = backgroundCallState;
            mPreciseCallState = new PreciseCallState(ringingCallState, foregroundCallState,
                    backgroundCallState,
                    DisconnectCause.NOT_VALID,
                    PreciseDisconnectCause.NOT_VALID);
            boolean notifyCallAttributes = true;
            if (mCallQuality == null) {
                log("notifyPreciseCallState: mCallQuality is null, skipping call attributes");
                notifyCallAttributes = false;
            } else {
                mCallAttributes = new CallAttributes(mPreciseCallState, mNetworkType[phoneId],
                        mCallQuality);
            }

            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_PRECISE_CALL_STATE)) {
                    try {
                        r.callback.onPreciseCallStateChanged(mPreciseCallState);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
                if (notifyCallAttributes && r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED)) {
                    try {
                        r.callback.onCallAttributesChanged(mCallAttributes);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastPreciseCallStateChanged(ringingCallState, foregroundCallState,
                backgroundCallState);
    }

    public void notifyDisconnectCause(int disconnectCause, int preciseDisconnectCause) {
        if (!checkNotifyPermission("notifyDisconnectCause()")) {
            return;
        }
        synchronized (mRecords) {
            mCallDisconnectCause = disconnectCause;
            mCallPreciseDisconnectCause = preciseDisconnectCause;
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener
                        .LISTEN_CALL_DISCONNECT_CAUSES)) {
                    try {
                        r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause,
                                mCallPreciseDisconnectCause);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
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
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mImsReasonInfo.set(phoneId, imsReasonInfo);
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES)
                            && idMatch(r.subId, subId, phoneId)) {
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

    public void notifyPreciseDataConnectionFailed(String apnType,
            String apn, @DataFailCause.FailCause int failCause) {
        if (!checkNotifyPermission("notifyPreciseDataConnectionFailed()")) {
            return;
        }
        synchronized (mRecords) {
            mPreciseDataConnectionState = new PreciseDataConnectionState(
                    TelephonyManager.DATA_UNKNOWN, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    ApnSetting.getApnTypesBitmaskFromString(apnType), apn, null, failCause);
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE)) {
                    try {
                        r.callback.onPreciseDataConnectionStateChanged(mPreciseDataConnectionState);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastPreciseDataConnectionStateChanged(TelephonyManager.DATA_UNKNOWN,
                TelephonyManager.NETWORK_TYPE_UNKNOWN, apnType, apn, null, failCause);
    }

    @Override
    public void notifySrvccStateChanged(int subId, @TelephonyManager.SrvccState int state) {
        if (!checkNotifyPermission("notifySrvccStateChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifySrvccStateChanged: subId=" + subId + " srvccState=" + state);
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mSrvccState[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(
                            PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED) &&
                            idMatch(r.subId, subId, phoneId)) {
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

    public void notifyOemHookRawEventForSubscriber(int subId, byte[] rawData) {
        if (!checkNotifyPermission("notifyOemHookRawEventForSubscriber")) {
            return;
        }

        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (VDBG) {
                    log("notifyOemHookRawEventForSubscriber:  r=" + r + " subId=" + subId);
                }
                if ((r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_OEM_HOOK_RAW_EVENT)) &&
                        ((r.subId == subId) ||
                        (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID))) {
                    try {
                        r.callback.onOemHookRawEvent(rawData);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
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
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_PHONE_CAPABILITY_CHANGE)) {
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

    public void notifyPreferredDataSubIdChanged(int preferredSubId) {
        if (!checkNotifyPermission("notifyPreferredDataSubIdChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyPreferredDataSubIdChanged: preferredSubId=" + preferredSubId);
        }

        synchronized (mRecords) {
            mPreferredDataSubId = preferredSubId;

            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_PREFERRED_DATA_SUBID_CHANGE)) {
                    try {
                        r.callback.onPreferredDataSubIdChanged(preferredSubId);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyRadioPowerStateChanged(@TelephonyManager.RadioPowerState int state) {
        if (!checkNotifyPermission("notifyRadioPowerStateChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyRadioPowerStateChanged: state= " + state);
        }

        synchronized (mRecords) {
            mRadioPowerState = state;

            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED)) {
                    try {
                        r.callback.onRadioPowerStateChanged(state);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }


    @Override
    public void notifyEmergencyNumberList() {
        if (!checkNotifyPermission("notifyEmergencyNumberList()")) {
            return;
        }

        synchronized (mRecords) {
            TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
            mEmergencyNumberList = tm.getCurrentEmergencyNumberList();

            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST)) {
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
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyCallQualityChanged(CallQuality callQuality, int phoneId) {
        if (!checkNotifyPermission("notifyCallQualityChanged()")) {
            return;
        }

        // merge CallQuality with PreciseCallState and network type
        mCallQuality = callQuality;
        mCallAttributes = new CallAttributes(mPreciseCallState,
                mNetworkType[phoneId],
                callQuality);

        synchronized (mRecords) {
            TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);

            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(
                        PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED)) {
                    try {
                        r.callback.onCallAttributesChanged(mCallAttributes);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }


    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            pw.println("last known state:");
            pw.increaseIndent();
            for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
                pw.println("Phone Id=" + i);
                pw.increaseIndent();
                pw.println("mCallState=" + mCallState[i]);
                pw.println("mCallIncomingNumber=" + mCallIncomingNumber[i]);
                pw.println("mServiceState=" + mServiceState[i]);
                pw.println("mNetworkType=" + mNetworkType[i]);
                pw.println("mVoiceActivationState= " + mVoiceActivationState[i]);
                pw.println("mDataActivationState= " + mDataActivationState[i]);
                pw.println("mUserMobileDataState= " + mUserMobileDataState[i]);
                pw.println("mSignalStrength=" + mSignalStrength[i]);
                pw.println("mMessageWaiting=" + mMessageWaiting[i]);
                pw.println("mCallForwarding=" + mCallForwarding[i]);
                pw.println("mDataActivity=" + mDataActivity[i]);
                pw.println("mDataConnectionState=" + mDataConnectionState[i]);
                pw.println("mCellLocation=" + mCellLocation[i]);
                pw.println("mCellInfo=" + mCellInfo.get(i));
                pw.println("mImsCallDisconnectCause=" + mImsReasonInfo.get(i).toString());
                pw.decreaseIndent();
            }
            pw.println("mPreciseDataConnectionState=" + mPreciseDataConnectionState);
            pw.println("mPreciseCallState=" + mPreciseCallState);
            pw.println("mCallDisconnectCause=" + mCallDisconnectCause);
            pw.println("mCallPreciseDisconnectCause=" + mCallPreciseDisconnectCause);
            pw.println("mCarrierNetworkChangeState=" + mCarrierNetworkChangeState);
            pw.println("mRingingCallState=" + mRingingCallState);
            pw.println("mForegroundCallState=" + mForegroundCallState);
            pw.println("mBackgroundCallState=" + mBackgroundCallState);
            pw.println("mSrvccState=" + mSrvccState);
            pw.println("mPhoneCapability=" + mPhoneCapability);
            pw.println("mPreferredDataSubId=" + mPreferredDataSubId);
            pw.println("mRadioPowerState=" + mRadioPowerState);
            pw.println("mEmergencyNumberList=" + mEmergencyNumberList);
            pw.println("mCallQuality=" + mCallQuality);
            pw.println("mCallAttributes=" + mCallAttributes);

            pw.decreaseIndent();

            pw.println("local logs:");
            pw.increaseIndent();
            mLocalLog.dump(fd, pw, args);
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

    private void broadcastServiceStateChanged(ServiceState state, int phoneId, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneState(state.getState());
        } catch (RemoteException re) {
            // Can't do much
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        Bundle data = new Bundle();
        state.fillInNotifierBundle(data);
        intent.putExtras(data);
        // Pass the subscription along with the intent.
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        intent.putExtra(PhoneConstants.SLOT_KEY, phoneId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastSignalStrengthChanged(SignalStrength signalStrength, int phoneId,
            int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneSignalStrength(signalStrength);
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED);
        Bundle data = new Bundle();
        signalStrength.fillInNotifierBundle(data);
        intent.putExtras(data);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(PhoneConstants.SLOT_KEY, phoneId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Broadcasts an intent notifying apps of a phone state change. {@code subId} can be
     * a valid subId, in which case this function fires a subId-specific intent, or it
     * can be {@code SubscriptionManager.INVALID_SUBSCRIPTION_ID}, in which case we send
     * a global state change broadcast ({@code TelephonyManager.ACTION_PHONE_STATE_CHANGED}).
     */
    private void broadcastCallStateChanged(int state, String incomingNumber, int phoneId,
                int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                mBatteryStats.notePhoneOff();
                StatsLog.write(StatsLog.PHONE_STATE_CHANGED,
                        StatsLog.PHONE_STATE_CHANGED__STATE__OFF);
            } else {
                mBatteryStats.notePhoneOn();
                StatsLog.write(StatsLog.PHONE_STATE_CHANGED,
                        StatsLog.PHONE_STATE_CHANGED__STATE__ON);
            }
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        intent.putExtra(PhoneConstants.STATE_KEY,
                PhoneConstantConversions.convertCallState(state).toString());

        // If a valid subId was specified, we should fire off a subId-specific state
        // change intent and include the subId.
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.setAction(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        }
        // If the phoneId is invalid, the broadcast is for overall call state.
        if (phoneId != SubscriptionManager.INVALID_PHONE_INDEX) {
            intent.putExtra(PhoneConstants.SLOT_KEY, phoneId);
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

    private void broadcastDataConnectionStateChanged(int state, boolean isDataAllowed, String apn,
                                                     String apnType, LinkProperties linkProperties,
                                                     NetworkCapabilities networkCapabilities,
                                                     boolean roaming, int subId) {
        // Note: not reporting to the battery stats service here, because the
        // status bar takes care of that after taking into account all of the
        // required info.
        Intent intent = new Intent(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(PhoneConstants.STATE_KEY,
                PhoneConstantConversions.convertDataState(state).toString());
        if (!isDataAllowed) {
            intent.putExtra(PhoneConstants.NETWORK_UNAVAILABLE_KEY, true);
        }
        if (linkProperties != null) {
            intent.putExtra(PhoneConstants.DATA_LINK_PROPERTIES_KEY, linkProperties);
            String iface = linkProperties.getInterfaceName();
            if (iface != null) {
                intent.putExtra(PhoneConstants.DATA_IFACE_NAME_KEY, iface);
            }
        }
        if (networkCapabilities != null) {
            intent.putExtra(PhoneConstants.DATA_NETWORK_CAPABILITIES_KEY, networkCapabilities);
        }
        if (roaming) intent.putExtra(PhoneConstants.DATA_NETWORK_ROAMING_KEY, true);

        intent.putExtra(PhoneConstants.DATA_APN_KEY, apn);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastDataConnectionFailed(String apnType, int subId) {
        Intent intent = new Intent(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastPreciseCallStateChanged(int ringingCallState, int foregroundCallState,
                                                  int backgroundCallState) {
        Intent intent = new Intent(TelephonyManager.ACTION_PRECISE_CALL_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_RINGING_CALL_STATE, ringingCallState);
        intent.putExtra(TelephonyManager.EXTRA_FOREGROUND_CALL_STATE, foregroundCallState);
        intent.putExtra(TelephonyManager.EXTRA_BACKGROUND_CALL_STATE, backgroundCallState);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PRECISE_PHONE_STATE);
    }

    private void broadcastPreciseDataConnectionStateChanged(int state, int networkType,
            String apnType, String apn, LinkProperties linkProperties,
            @DataFailCause.FailCause int failCause) {
        Intent intent = new Intent(TelephonyManager.ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(PhoneConstants.STATE_KEY, state);
        intent.putExtra(PhoneConstants.DATA_NETWORK_TYPE_KEY, networkType);
        if (apnType != null) intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        if (apn != null) intent.putExtra(PhoneConstants.DATA_APN_KEY, apn);
        if (linkProperties != null) {
            intent.putExtra(PhoneConstants.DATA_LINK_PROPERTIES_KEY, linkProperties);
        }
        intent.putExtra(PhoneConstants.DATA_FAILURE_CAUSE_KEY, failCause);

        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PRECISE_PHONE_STATE);
    }

    private void enforceNotifyPermissionOrCarrierPrivilege(String method) {
        if (checkNotifyPermission()) {
            return;
        }

        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(
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

    private boolean checkListenerPermission(
            int events, int subId, String callingPackage, String message) {
        LocationAccessPolicy.LocationPermissionQuery.Builder locationQueryBuilder =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                .setCallingPackage(callingPackage)
                .setMethod(message + " events: " + events)
                .setCallingPid(Binder.getCallingPid())
                .setCallingUid(Binder.getCallingUid());

        boolean shouldCheckLocationPermissions = false;
        if ((events & ENFORCE_COARSE_LOCATION_PERMISSION_MASK) != 0) {
            locationQueryBuilder.setMinSdkVersionForCoarse(0);
            shouldCheckLocationPermissions = true;
        }

        if ((events & ENFORCE_FINE_LOCATION_PERMISSION_MASK) != 0) {
            // Everything that requires fine location started in Q. So far...
            locationQueryBuilder.setMinSdkVersionForFine(Build.VERSION_CODES.Q);
            shouldCheckLocationPermissions = true;
        }

        if (shouldCheckLocationPermissions) {
            LocationAccessPolicy.LocationPermissionResult result =
                    LocationAccessPolicy.checkLocationPermission(
                            mContext, locationQueryBuilder.build());
            switch (result) {
                case DENIED_HARD:
                    throw new SecurityException("Unable to listen for events " + events + " due to "
                            + "insufficient location permissions.");
                case DENIED_SOFT:
                    return false;
            }
        }

        if ((events & ENFORCE_PHONE_STATE_PERMISSION_MASK) != 0) {
            if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                    mContext, subId, callingPackage, message)) {
                return false;
            }
        }

        if ((events & PRECISE_PHONE_STATE_PERMISSION_MASK) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_OEM_HOOK_RAW_EVENT) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_PREFERRED_DATA_SUBID_CHANGE) != 0) {
            // It can have either READ_PHONE_STATE or READ_PRIVILEGED_PHONE_STATE.
            TelephonyPermissions.checkReadPhoneState(mContext,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID, Binder.getCallingPid(),
                    Binder.getCallingUid(), callingPackage, "listen to "
                            + "LISTEN_PREFERRED_DATA_SUBID_CHANGE");
        }

        if ((events & PhoneStateListener.LISTEN_CALL_DISCONNECT_CAUSES) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }

        if ((events & PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
        }

        return true;
    }

    private void handleRemoveListLocked() {
        int size = mRemoveList.size();
        if (VDBG) log("handleRemoveListLocked: mRemoveList.size()=" + size);
        if (size > 0) {
            for (IBinder b: mRemoveList) {
                remove(b);
            }
            mRemoveList.clear();
        }
    }

    private boolean validateEventsAndUserLocked(Record r, int events) {
        int foregroundUser;
        long callingIdentity = Binder.clearCallingIdentity();
        boolean valid = false;
        try {
            foregroundUser = ActivityManager.getCurrentUser();
            valid = UserHandle.getUserId(r.callerUid) == foregroundUser
                    && r.matchPhoneStateListenerEvent(events);
            if (DBG | DBG_LOC) {
                log("validateEventsAndUserLocked: valid=" + valid
                        + " r.callerUid=" + r.callerUid + " foregroundUser=" + foregroundUser
                        + " r.events=" + r.events + " events=" + events);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return valid;
    }

    private boolean validatePhoneId(int phoneId) {
        boolean valid = (phoneId >= 0) && (phoneId < mNumPhones);
        if (VDBG) log("validatePhoneId: " + valid);
        return valid;
    }

    private static void log(String s) {
        Rlog.d(TAG, s);
    }

    boolean idMatch(int rSubId, int subId, int phoneId) {

        if(subId < 0) {
            // Invalid case, we need compare phoneId with default one.
            return (mDefaultPhoneId == phoneId);
        }
        if(rSubId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            return (subId == mDefaultSubId);
        } else {
            return (rSubId == subId);
        }
    }

    private boolean checkFineLocationAccess(Record r, int minSdk) {
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setMinSdkVersionForFine(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    private boolean checkCoarseLocationAccess(Record r, int minSdk) {
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setMinSdkVersionForCoarse(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    private void checkPossibleMissNotify(Record r, int phoneId) {
        int events = r.events;

        if ((events & PhoneStateListener.LISTEN_SERVICE_STATE) != 0) {
            try {
                if (VDBG) log("checkPossibleMissNotify: onServiceStateChanged state=" +
                        mServiceState[phoneId]);
                r.callback.onServiceStateChanged(
                        new ServiceState(mServiceState[phoneId]));
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
            try {
                SignalStrength signalStrength = mSignalStrength[phoneId];
                if (DBG) {
                    log("checkPossibleMissNotify: onSignalStrengthsChanged SS=" + signalStrength);
                }
                r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTH) != 0) {
            try {
                int gsmSignalStrength = mSignalStrength[phoneId]
                        .getGsmSignalStrength();
                if (DBG) {
                    log("checkPossibleMissNotify: onSignalStrengthChanged SS=" +
                            gsmSignalStrength);
                }
                r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                        : gsmSignalStrength));
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_INFO)) {
            try {
                if (DBG_LOC) {
                    log("checkPossibleMissNotify: onCellInfoChanged[" + phoneId + "] = "
                            + mCellInfo.get(phoneId));
                }
                if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) != 0) {
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

        if ((events & PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) != 0) {
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

        if ((events & PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) != 0) {
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

        if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_LOCATION)) {
            try {
                if (DBG_LOC) log("checkPossibleMissNotify: onCellLocationChanged mCellLocation = "
                        + mCellLocation[phoneId]);
                if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onCellLocationChanged(new Bundle(mCellLocation[phoneId]));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if ((events & PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) != 0) {
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
}
