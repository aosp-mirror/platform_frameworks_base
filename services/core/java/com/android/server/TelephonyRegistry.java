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

import android.Manifest;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.CellLocation;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.CellInfo;
import android.telephony.VoLteServiceState;
import android.telephony.DisconnectCause;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.PreciseDisconnectCause;
import android.text.TextUtils;
import android.text.format.Time;

import java.util.ArrayList;
import java.util.List;
import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.DefaultPhoneNotifier;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.server.am.BatteryStatsService;

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
class TelephonyRegistry extends ITelephonyRegistry.Stub {
    private static final String TAG = "TelephonyRegistry";
    private static final boolean DBG = false; // STOPSHIP if true
    private static final boolean DBG_LOC = false; // STOPSHIP if true
    private static final boolean VDBG = false; // STOPSHIP if true

    private static class Record {
        String callingPackage;

        IBinder binder;

        IPhoneStateListener callback;
        IOnSubscriptionsChangedListener onSubscriptionsChangedListenerCallback;

        int callerUserId;

        int events;

        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;

        boolean canReadPhoneState;

        boolean matchPhoneStateListenerEvent(int events) {
            return (callback != null) && ((events & this.events) != 0);
        }

        boolean matchOnSubscriptionsChangedListener() {
            return (onSubscriptionsChangedListenerCallback != null);
        }

        @Override
        public String toString() {
            return "{callingPackage=" + callingPackage + " binder=" + binder
                    + " callback=" + callback
                    + " onSubscriptionsChangedListenererCallback="
                                            + onSubscriptionsChangedListenerCallback
                    + " callerUserId=" + callerUserId + " subId=" + subId + " phoneId=" + phoneId
                    + " events=" + Integer.toHexString(events)
                    + " canReadPhoneState=" + canReadPhoneState + "}";
        }
    }

    private final Context mContext;

    // access should be inside synchronized (mRecords) for these two fields
    private final ArrayList<IBinder> mRemoveList = new ArrayList<IBinder>();
    private final ArrayList<Record> mRecords = new ArrayList<Record>();

    private final IBatteryStats mBatteryStats;

    private final AppOpsManager mAppOps;

    private boolean hasNotifySubscriptionInfoChangedOccurred = false;

    private int mNumPhones;

    private int[] mCallState;

    private String[] mCallIncomingNumber;

    private ServiceState[] mServiceState;

    private SignalStrength[] mSignalStrength;

    private boolean[] mMessageWaiting;

    private boolean[] mCallForwarding;

    private int[] mDataActivity;

    private int[] mDataConnectionState;

    private boolean[] mDataConnectionPossible;

    private String[] mDataConnectionReason;

    private String[] mDataConnectionApn;

    private ArrayList<String> mConnectedApns;

    private LinkProperties[] mDataConnectionLinkProperties;

    private NetworkCapabilities[] mDataConnectionNetworkCapabilities;

    private Bundle[] mCellLocation;

    private int[] mDataConnectionNetworkType;

    private int mOtaspMode = ServiceStateTracker.OTASP_UNKNOWN;

    private ArrayList<List<CellInfo>> mCellInfo = null;

    private VoLteServiceState mVoLteServiceState = new VoLteServiceState();

    private int mDefaultSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private int mDefaultPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;

    private DataConnectionRealTimeInfo mDcRtInfo = new DataConnectionRealTimeInfo();

    private int mRingingCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;

    private int mForegroundCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;

    private int mBackgroundCallState = PreciseCallState.PRECISE_CALL_STATE_IDLE;

    private PreciseCallState mPreciseCallState = new PreciseCallState();

    private boolean mCarrierNetworkChangeState = false;

    private PreciseDataConnectionState mPreciseDataConnectionState =
                new PreciseDataConnectionState();

    static final int ENFORCE_PHONE_STATE_PERMISSION_MASK =
                PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR |
                PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR |
                PhoneStateListener.LISTEN_VOLTE_STATE;

    static final int CHECK_PHONE_STATE_PERMISSION_MASK =
                PhoneStateListener.LISTEN_CALL_STATE |
                PhoneStateListener.LISTEN_DATA_ACTIVITY |
                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE;

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
                        PhoneConstants.SUBSCRIPTION_KEY, SubscriptionManager.getDefaultSubId()));
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

    TelephonyRegistry(Context context) {
        CellLocation  location = CellLocation.getEmpty();

        mContext = context;
        mBatteryStats = BatteryStatsService.getService();
        mConnectedApns = new ArrayList<String>();

        int numPhones = TelephonyManager.getDefault().getPhoneCount();
        if (DBG) log("TelephonyRegistor: ctor numPhones=" + numPhones);
        mNumPhones = numPhones;
        mCallState = new int[numPhones];
        mDataActivity = new int[numPhones];
        mDataConnectionState = new int[numPhones];
        mDataConnectionNetworkType = new int[numPhones];
        mCallIncomingNumber = new String[numPhones];
        mServiceState = new ServiceState[numPhones];
        mSignalStrength = new SignalStrength[numPhones];
        mMessageWaiting = new boolean[numPhones];
        mDataConnectionPossible = new boolean[numPhones];
        mDataConnectionReason = new String[numPhones];
        mDataConnectionApn = new String[numPhones];
        mCallForwarding = new boolean[numPhones];
        mCellLocation = new Bundle[numPhones];
        mDataConnectionLinkProperties = new LinkProperties[numPhones];
        mDataConnectionNetworkCapabilities = new NetworkCapabilities[numPhones];
        mCellInfo = new ArrayList<List<CellInfo>>();
        for (int i = 0; i < numPhones; i++) {
            mCallState[i] =  TelephonyManager.CALL_STATE_IDLE;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
            mCallIncomingNumber[i] =  "";
            mServiceState[i] =  new ServiceState();
            mSignalStrength[i] =  new SignalStrength();
            mMessageWaiting[i] =  false;
            mCallForwarding[i] =  false;
            mDataConnectionPossible[i] = false;
            mDataConnectionReason[i] =  "";
            mDataConnectionApn[i] =  "";
            mCellLocation[i] = new Bundle();
            mCellInfo.add(i, null);
        }

        // Note that location can be null for non-phone builds like
        // like the generic one.
        if (location != null) {
            for (int i = 0; i < numPhones; i++) {
                location.fillInNotifierBundle(mCellLocation[i]);
            }
        }
        mConnectedApns = new ArrayList<String>();

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
        if (VDBG) {
            log("listen oscl: E pkg=" + callingPackage + " myUserId=" + UserHandle.myUserId()
                + " callerUserId="  + callerUserId + " callback=" + callback
                + " callback.asBinder=" + callback.asBinder());
        }

        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                    "addOnSubscriptionsChangedListener");
            // SKIP checking for run-time permission since caller or self has PRIVILEGED permission
        } catch (SecurityException e) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PHONE_STATE,
                    "addOnSubscriptionsChangedListener");

            if (mAppOps.noteOp(AppOpsManager.OP_READ_PHONE_STATE, Binder.getCallingUid(),
                    callingPackage) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
        }

        Record r;

        synchronized (mRecords) {
            // register
            find_and_add: {
                IBinder b = callback.asBinder();
                final int N = mRecords.size();
                for (int i = 0; i < N; i++) {
                    r = mRecords.get(i);
                    if (b == r.binder) {
                        break find_and_add;
                    }
                }
                r = new Record();
                r.binder = b;
                mRecords.add(r);
                if (DBG) log("listen oscl: add new record");
            }

            r.onSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callerUserId = callerUserId;
            r.events = 0;
            r.canReadPhoneState = true; // permission has been enforced above
            if (DBG) {
                log("listen oscl:  Register r=" + r);
            }
            // Always notify when registration occurs if there has been a notification.
            if (hasNotifySubscriptionInfoChangedOccurred) {
                try {
                    if (VDBG) log("listen oscl: send to r=" + r);
                    r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    if (VDBG) log("listen oscl: sent to r=" + r);
                } catch (RemoteException e) {
                    if (VDBG) log("listen oscl: remote exception sending to r=" + r + " e=" + e);
                    remove(r.binder);
                }
            } else {
                log("listen oscl: hasNotifySubscriptionInfoChangedOccurred==false no callback");
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
    public void notifySubscriptionInfoChanged() {
        if (VDBG) log("notifySubscriptionInfoChanged:");
        synchronized (mRecords) {
            if (!hasNotifySubscriptionInfoChangedOccurred) {
                log("notifySubscriptionInfoChanged: first invocation mRecords.size="
                        + mRecords.size());
            }
            hasNotifySubscriptionInfoChangedOccurred = true;
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
        if (VDBG) {
            log("listen: E pkg=" + callingPackage + " events=0x" + Integer.toHexString(events)
                + " notifyNow=" + notifyNow + " subId=" + subId + " myUserId="
                + UserHandle.myUserId() + " callerUserId=" + callerUserId);
        }

        if (events != PhoneStateListener.LISTEN_NONE) {
            /* Checks permission and throws Security exception */
            checkListenerPermission(events);

            if ((events & ENFORCE_PHONE_STATE_PERMISSION_MASK) != 0) {
                try {
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
                    // SKIP checking for run-time permission since caller or self has PRIVILEGED
                    // permission
                } catch (SecurityException e) {
                    if (mAppOps.noteOp(AppOpsManager.OP_READ_PHONE_STATE, Binder.getCallingUid(),
                            callingPackage) != AppOpsManager.MODE_ALLOWED) {
                        return;
                    }
                }
            }

            synchronized (mRecords) {
                // register
                Record r;
                find_and_add: {
                    IBinder b = callback.asBinder();
                    final int N = mRecords.size();
                    for (int i = 0; i < N; i++) {
                        r = mRecords.get(i);
                        if (b == r.binder) {
                            break find_and_add;
                        }
                    }
                    r = new Record();
                    r.binder = b;
                    mRecords.add(r);
                    if (DBG) log("listen: add new record");
                }

                r.callback = callback;
                r.callingPackage = callingPackage;
                r.callerUserId = callerUserId;
                boolean isPhoneStateEvent = (events & (CHECK_PHONE_STATE_PERMISSION_MASK
                        | ENFORCE_PHONE_STATE_PERMISSION_MASK)) != 0;
                r.canReadPhoneState = isPhoneStateEvent && canReadPhoneState(callingPackage);
                // Legacy applications pass SubscriptionManager.DEFAULT_SUB_ID,
                // force all illegal subId to SubscriptionManager.DEFAULT_SUB_ID
                if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                    r.subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
                 } else {//APP specify subID
                    r.subId = subId;
                }
                r.phoneId = SubscriptionManager.getPhoneId(r.subId);

                int phoneId = r.phoneId;
                r.events = events;
                if (DBG) {
                    log("listen:  Register r=" + r + " r.subId=" + r.subId + " phoneId=" + phoneId);
                }
                if (VDBG) toStringLogSSC("listen");
                if (notifyNow && validatePhoneId(phoneId)) {
                    if ((events & PhoneStateListener.LISTEN_SERVICE_STATE) != 0) {
                        try {
                            if (VDBG) log("listen: call onSSC state=" + mServiceState[phoneId]);
                            r.callback.onServiceStateChanged(
                                    new ServiceState(mServiceState[phoneId]));
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
                            r.callback.onCellLocationChanged(
                                    new Bundle(mCellLocation[phoneId]));
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
                            r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                        } catch (RemoteException ex) {
                            remove(r.binder);
                        }
                    }
                    if ((events & PhoneStateListener.LISTEN_DATA_CONNECTION_REAL_TIME_INFO) != 0) {
                        try {
                            r.callback.onDataConnectionRealTimeInfoChanged(mDcRtInfo);
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
                }
            }
        } else {
            if(DBG) log("listen: Unregister");
            remove(callback.asBinder());
        }
    }

    private boolean canReadPhoneState(String callingPackage) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED) {
            // SKIP checking for run-time permission since caller or self has PRIVILEGED permission
            return true;
        }
        boolean canReadPhoneState = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        if (canReadPhoneState &&
                mAppOps.noteOp(AppOpsManager.OP_READ_PHONE_STATE, Binder.getCallingUid(),
                        callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return false;
        }
        return canReadPhoneState;
    }

    private String getCallIncomingNumber(Record record, int phoneId) {
        // Hide the number if record's process has no READ_PHONE_STATE permission
        return record.canReadPhoneState ? mCallIncomingNumber[phoneId] : "";
    }

    private void remove(IBinder binder) {
        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            for (int i = 0; i < recordCount; i++) {
                if (mRecords.get(i).binder == binder) {
                    if (DBG) {
                        Record r = mRecords.get(i);
                        log("remove: binder=" + binder + "r.callingPackage" + r.callingPackage
                                + "r.callback" + r.callback);
                    }
                    mRecords.remove(i);
                    return;
                }
            }
        }
    }

    public void notifyCallState(int state, String incomingNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }

        if (VDBG) {
            log("notifyCallState: state=" + state + " incomingNumber=" + incomingNumber);
        }

        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_CALL_STATE) &&
                        (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                    try {
                        String incomingNumberOrEmpty = r.canReadPhoneState ? incomingNumber : "";
                        r.callback.onCallStateChanged(state, incomingNumberOrEmpty);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastCallStateChanged(state, incomingNumber,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    public void notifyCallStateForSubscriber(int subId, int state, String incomingNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallStateForSubscriber: subId=" + subId
                + " state=" + state + " incomingNumber=" + incomingNumber);
        }
        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
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
        broadcastCallStateChanged(state, incomingNumber, subId);
    }

    public void notifyServiceStateForPhoneId(int phoneId, int subId, ServiceState state) {
        if (!checkNotifyPermission("notifyServiceState()")){
            return;
        }

        synchronized (mRecords) {
            if (VDBG) {
                log("notifyServiceStateForSubscriber: subId=" + subId + " phoneId=" + phoneId
                    + " state=" + state);
            }
            if (validatePhoneId(phoneId)) {
                mServiceState[phoneId] = state;
                logServiceStateChanged("notifyServiceStateForSubscriber", subId, phoneId, state);
                if (VDBG) toStringLogSSC("notifyServiceStateForSubscriber");

                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifyServiceStateForSubscriber: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " state=" + state);
                    }
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_SERVICE_STATE) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG) {
                                log("notifyServiceStateForSubscriber: callback.onSSC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + state);
                            }
                            r.callback.onServiceStateChanged(new ServiceState(state));
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
        broadcastServiceStateChanged(state, subId);
    }

    public void notifySignalStrength(SignalStrength signalStrength) {
        notifySignalStrengthForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                signalStrength);
    }

    public void notifySignalStrengthForSubscriber(int subId, SignalStrength signalStrength) {
        if (!checkNotifyPermission("notifySignalStrength()")) {
            return;
        }
        if (VDBG) {
            log("notifySignalStrengthForSubscriber: subId=" + subId
                + " signalStrength=" + signalStrength);
            toStringLogSSC("notifySignalStrengthForSubscriber");
        }
        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
            if (validatePhoneId(phoneId)) {
                if (VDBG) log("notifySignalStrengthForSubscriber: valid phoneId=" + phoneId);
                mSignalStrength[phoneId] = signalStrength;
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifySignalStrengthForSubscriber: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " ss=" + signalStrength);
                    }
                    if (r.matchPhoneStateListenerEvent(
                                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) &&
                            idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG) {
                                log("notifySignalStrengthForSubscriber: callback.onSsS r=" + r
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
                                log("notifySignalStrengthForSubscriber: callback.onSS r=" + r
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
                log("notifySignalStrengthForSubscriber: invalid phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
        broadcastSignalStrengthChanged(signalStrength, subId);
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

        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
            if (validatePhoneId(phoneId)) {
                mCellInfo.set(phoneId, cellInfo);
                for (Record r : mRecords) {
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_INFO) &&
                            idMatch(r.subId, subId, phoneId)) {
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

    public void notifyDataConnectionRealTimeInfo(DataConnectionRealTimeInfo dcRtInfo) {
        if (!checkNotifyPermission("notifyDataConnectionRealTimeInfo()")) {
            return;
        }

        synchronized (mRecords) {
            mDcRtInfo = dcRtInfo;
            for (Record r : mRecords) {
                if (validateEventsAndUserLocked(r,
                        PhoneStateListener.LISTEN_DATA_CONNECTION_REAL_TIME_INFO)) {
                    try {
                        if (DBG_LOC) {
                            log("notifyDataConnectionRealTimeInfo: mDcRtInfo="
                                    + mDcRtInfo + " r=" + r);
                        }
                        r.callback.onDataConnectionRealTimeInfoChanged(mDcRtInfo);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
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
        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
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
        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
            if (validatePhoneId(phoneId)) {
                mDataActivity[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_DATA_ACTIVITY)) {
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

    public void notifyDataConnection(int state, boolean isDataConnectivityPossible,
            String reason, String apn, String apnType, LinkProperties linkProperties,
            NetworkCapabilities networkCapabilities, int networkType, boolean roaming) {
        notifyDataConnectionForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, state,
            isDataConnectivityPossible,reason, apn, apnType, linkProperties,
            networkCapabilities, networkType, roaming);
    }

    public void notifyDataConnectionForSubscriber(int subId, int state,
            boolean isDataConnectivityPossible, String reason, String apn, String apnType,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int networkType, boolean roaming) {
        if (!checkNotifyPermission("notifyDataConnection()" )) {
            return;
        }
        if (VDBG) {
            log("notifyDataConnectionForSubscriber: subId=" + subId
                + " state=" + state + " isDataConnectivityPossible=" + isDataConnectivityPossible
                + " reason='" + reason
                + "' apn='" + apn + "' apnType=" + apnType + " networkType=" + networkType
                + " mRecords.size()=" + mRecords.size());
        }
        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
            if (validatePhoneId(phoneId)) {
                boolean modified = false;
                if (state == TelephonyManager.DATA_CONNECTED) {
                    if (!mConnectedApns.contains(apnType)) {
                        mConnectedApns.add(apnType);
                        if (mDataConnectionState[phoneId] != state) {
                            mDataConnectionState[phoneId] = state;
                            modified = true;
                        }
                    }
                } else {
                    if (mConnectedApns.remove(apnType)) {
                        if (mConnectedApns.isEmpty()) {
                            mDataConnectionState[phoneId] = state;
                            modified = true;
                        } else {
                            // leave mDataConnectionState as is and
                            // send out the new status for the APN in question.
                        }
                    }
                }
                mDataConnectionPossible[phoneId] = isDataConnectivityPossible;
                mDataConnectionReason[phoneId] = reason;
                mDataConnectionLinkProperties[phoneId] = linkProperties;
                mDataConnectionNetworkCapabilities[phoneId] = networkCapabilities;
                if (mDataConnectionNetworkType[phoneId] != networkType) {
                    mDataConnectionNetworkType[phoneId] = networkType;
                    // need to tell registered listeners about the new network type
                    modified = true;
                }
                if (modified) {
                    if (DBG) {
                        log("onDataConnectionStateChanged(" + mDataConnectionState[phoneId]
                            + ", " + mDataConnectionNetworkType[phoneId] + ")");
                    }
                    for (Record r : mRecords) {
                        if (r.matchPhoneStateListenerEvent(
                                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) &&
                                idMatch(r.subId, subId, phoneId)) {
                            try {
                                log("Notify data connection state changed on sub: " +
                                        subId);
                                r.callback.onDataConnectionStateChanged(mDataConnectionState[phoneId],
                                        mDataConnectionNetworkType[phoneId]);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                    handleRemoveListLocked();
                }
                mPreciseDataConnectionState = new PreciseDataConnectionState(state, networkType,
                        apnType, apn, reason, linkProperties, "");
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
            }
            handleRemoveListLocked();
        }
        broadcastDataConnectionStateChanged(state, isDataConnectivityPossible, reason, apn,
                apnType, linkProperties, networkCapabilities, roaming, subId);
        broadcastPreciseDataConnectionStateChanged(state, networkType, apnType, apn, reason,
                linkProperties, "");
    }

    public void notifyDataConnectionFailed(String reason, String apnType) {
         notifyDataConnectionFailedForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                 reason, apnType);
    }

    public void notifyDataConnectionFailedForSubscriber(int subId,
            String reason, String apnType) {
        if (!checkNotifyPermission("notifyDataConnectionFailed()")) {
            return;
        }
        if (VDBG) {
            log("notifyDataConnectionFailedForSubscriber: subId=" + subId
                + " reason=" + reason + " apnType=" + apnType);
        }
        synchronized (mRecords) {
            mPreciseDataConnectionState = new PreciseDataConnectionState(
                    TelephonyManager.DATA_UNKNOWN,TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    apnType, "", reason, null, "");
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
        broadcastDataConnectionFailed(reason, apnType, subId);
        broadcastPreciseDataConnectionStateChanged(TelephonyManager.DATA_UNKNOWN,
                TelephonyManager.NETWORK_TYPE_UNKNOWN, apnType, "", reason, null, "");
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
        synchronized (mRecords) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
            if (validatePhoneId(phoneId)) {
                mCellLocation[phoneId] = cellLocation;
                for (Record r : mRecords) {
                    if (validateEventsAndUserLocked(r, PhoneStateListener.LISTEN_CELL_LOCATION) &&
                            idMatch(r.subId, subId, phoneId)) {
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
            int backgroundCallState) {
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
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_PRECISE_CALL_STATE)) {
                    try {
                        r.callback.onPreciseCallStateChanged(mPreciseCallState);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastPreciseCallStateChanged(ringingCallState, foregroundCallState, backgroundCallState,
                DisconnectCause.NOT_VALID,
                PreciseDisconnectCause.NOT_VALID);
    }

    public void notifyDisconnectCause(int disconnectCause, int preciseDisconnectCause) {
        if (!checkNotifyPermission("notifyDisconnectCause()")) {
            return;
        }
        synchronized (mRecords) {
            mPreciseCallState = new PreciseCallState(mRingingCallState, mForegroundCallState,
                    mBackgroundCallState, disconnectCause, preciseDisconnectCause);
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_PRECISE_CALL_STATE)) {
                    try {
                        r.callback.onPreciseCallStateChanged(mPreciseCallState);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastPreciseCallStateChanged(mRingingCallState, mForegroundCallState,
                mBackgroundCallState, disconnectCause, preciseDisconnectCause);
    }

    public void notifyPreciseDataConnectionFailed(String reason, String apnType,
            String apn, String failCause) {
        if (!checkNotifyPermission("notifyPreciseDataConnectionFailed()")) {
            return;
        }
        synchronized (mRecords) {
            mPreciseDataConnectionState = new PreciseDataConnectionState(
                    TelephonyManager.DATA_UNKNOWN, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    apnType, apn, reason, null, failCause);
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
                TelephonyManager.NETWORK_TYPE_UNKNOWN, apnType, apn, reason, null, failCause);
    }

    public void notifyVoLteServiceStateChanged(VoLteServiceState lteState) {
        if (!checkNotifyPermission("notifyVoLteServiceStateChanged()")) {
            return;
        }
        synchronized (mRecords) {
            mVoLteServiceState = lteState;
            for (Record r : mRecords) {
                if (r.matchPhoneStateListenerEvent(PhoneStateListener.LISTEN_VOLTE_STATE)) {
                    try {
                        r.callback.onVoLteServiceStateChanged(
                                new VoLteServiceState(mVoLteServiceState));
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
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

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump telephony.registry from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            pw.println("last known state:");
            for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
                pw.println("  Phone Id=" + i);
                pw.println("  mCallState=" + mCallState[i]);
                pw.println("  mCallIncomingNumber=" + mCallIncomingNumber[i]);
                pw.println("  mServiceState=" + mServiceState[i]);
                pw.println("  mSignalStrength=" + mSignalStrength[i]);
                pw.println("  mMessageWaiting=" + mMessageWaiting[i]);
                pw.println("  mCallForwarding=" + mCallForwarding[i]);
                pw.println("  mDataActivity=" + mDataActivity[i]);
                pw.println("  mDataConnectionState=" + mDataConnectionState[i]);
                pw.println("  mDataConnectionPossible=" + mDataConnectionPossible[i]);
                pw.println("  mDataConnectionReason=" + mDataConnectionReason[i]);
                pw.println("  mDataConnectionApn=" + mDataConnectionApn[i]);
                pw.println("  mDataConnectionLinkProperties=" + mDataConnectionLinkProperties[i]);
                pw.println("  mDataConnectionNetworkCapabilities=" +
                        mDataConnectionNetworkCapabilities[i]);
                pw.println("  mCellLocation=" + mCellLocation[i]);
                pw.println("  mCellInfo=" + mCellInfo.get(i));
            }
            pw.println("  mDcRtInfo=" + mDcRtInfo);
            pw.println("registrations: count=" + recordCount);
            for (Record r : mRecords) {
                pw.println("  " + r);
            }
        }
    }

    //
    // the legacy intent broadcasting
    //

    private void broadcastServiceStateChanged(ServiceState state, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneState(state.getState());
        } catch (RemoteException re) {
            // Can't do much
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        Bundle data = new Bundle();
        state.fillInNotifierBundle(data);
        intent.putExtras(data);
        // Pass the subscription along with the intent.
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastSignalStrengthChanged(SignalStrength signalStrength, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneSignalStrength(signalStrength);
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        Bundle data = new Bundle();
        signalStrength.fillInNotifierBundle(data);
        intent.putExtras(data);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Broadcasts an intent notifying apps of a phone state change. {@code subId} can be
     * a valid subId, in which case this function fires a subId-specific intent, or it
     * can be {@code SubscriptionManager.INVALID_SUBSCRIPTION_ID}, in which case we send
     * a global state change broadcast ({@code TelephonyManager.ACTION_PHONE_STATE_CHANGED}).
     */
    private void broadcastCallStateChanged(int state, String incomingNumber, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                mBatteryStats.notePhoneOff();
            } else {
                mBatteryStats.notePhoneOn();
            }
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        intent.putExtra(PhoneConstants.STATE_KEY,
                DefaultPhoneNotifier.convertCallState(state).toString());
        if (!TextUtils.isEmpty(incomingNumber)) {
            intent.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, incomingNumber);
        }

        // If a valid subId was specified, we should fire off a subId-specific state
        // change intent and include the subId.
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.setAction(PhoneConstants.ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        }

        // Send broadcast twice, once for apps that have PRIVILEGED permission and once for those
        // that have the runtime one
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PHONE_STATE,
                AppOpsManager.OP_READ_PHONE_STATE);
    }

    private void broadcastDataConnectionStateChanged(int state,
            boolean isDataConnectivityPossible,
            String reason, String apn, String apnType, LinkProperties linkProperties,
            NetworkCapabilities networkCapabilities, boolean roaming, int subId) {
        // Note: not reporting to the battery stats service here, because the
        // status bar takes care of that after taking into account all of the
        // required info.
        Intent intent = new Intent(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(PhoneConstants.STATE_KEY,
                DefaultPhoneNotifier.convertDataState(state).toString());
        if (!isDataConnectivityPossible) {
            intent.putExtra(PhoneConstants.NETWORK_UNAVAILABLE_KEY, true);
        }
        if (reason != null) {
            intent.putExtra(PhoneConstants.STATE_CHANGE_REASON_KEY, reason);
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

    private void broadcastDataConnectionFailed(String reason, String apnType,
            int subId) {
        Intent intent = new Intent(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        intent.putExtra(PhoneConstants.FAILURE_REASON_KEY, reason);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastPreciseCallStateChanged(int ringingCallState, int foregroundCallState,
            int backgroundCallState, int disconnectCause, int preciseDisconnectCause) {
        Intent intent = new Intent(TelephonyManager.ACTION_PRECISE_CALL_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_RINGING_CALL_STATE, ringingCallState);
        intent.putExtra(TelephonyManager.EXTRA_FOREGROUND_CALL_STATE, foregroundCallState);
        intent.putExtra(TelephonyManager.EXTRA_BACKGROUND_CALL_STATE, backgroundCallState);
        intent.putExtra(TelephonyManager.EXTRA_DISCONNECT_CAUSE, disconnectCause);
        intent.putExtra(TelephonyManager.EXTRA_PRECISE_DISCONNECT_CAUSE, preciseDisconnectCause);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PRECISE_PHONE_STATE);
    }

    private void broadcastPreciseDataConnectionStateChanged(int state, int networkType,
            String apnType, String apn, String reason, LinkProperties linkProperties,
            String failCause) {
        Intent intent = new Intent(TelephonyManager.ACTION_PRECISE_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(PhoneConstants.STATE_KEY, state);
        intent.putExtra(PhoneConstants.DATA_NETWORK_TYPE_KEY, networkType);
        if (reason != null) intent.putExtra(PhoneConstants.STATE_CHANGE_REASON_KEY, reason);
        if (apnType != null) intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        if (apn != null) intent.putExtra(PhoneConstants.DATA_APN_KEY, apn);
        if (linkProperties != null) {
            intent.putExtra(PhoneConstants.DATA_LINK_PROPERTIES_KEY,linkProperties);
        }
        if (failCause != null) intent.putExtra(PhoneConstants.DATA_FAILURE_CAUSE_KEY, failCause);

        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PRECISE_PHONE_STATE);
    }

    private void enforceNotifyPermissionOrCarrierPrivilege(String method) {
        if  (checkNotifyPermission()) {
            return;
        }

        enforceCarrierPrivilege();
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

    private void enforceCarrierPrivilege() {
        TelephonyManager tm = TelephonyManager.getDefault();
        String[] pkgs = mContext.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        for (String pkg : pkgs) {
            if (tm.checkCarrierPrivilegesForPackage(pkg) ==
                    TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        }

        String msg = "Carrier Privilege Permission Denial: from pid=" + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid();
        if (DBG) log(msg);
        throw new SecurityException(msg);
    }

    private void checkListenerPermission(int events) {
        if ((events & PhoneStateListener.LISTEN_CELL_LOCATION) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION, null);

        }

        if ((events & PhoneStateListener.LISTEN_CELL_INFO) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION, null);

        }

        if ((events & ENFORCE_PHONE_STATE_PERMISSION_MASK) != 0) {
            try {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
                // SKIP checking for run-time permission since caller or self has PRIVILEGED
                // permission
            } catch (SecurityException e) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.READ_PHONE_STATE, null);
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
            valid = r.callerUserId ==  foregroundUser && r.matchPhoneStateListenerEvent(events);
            if (DBG | DBG_LOC) {
                log("validateEventsAndUserLocked: valid=" + valid
                        + " r.callerUserId=" + r.callerUserId + " foregroundUser=" + foregroundUser
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

    private static class LogSSC {
        private Time mTime;
        private String mS;
        private int mSubId;
        private int mPhoneId;
        private ServiceState mState;

        public void set(Time t, String s, int subId, int phoneId, ServiceState state) {
            mTime = t; mS = s; mSubId = subId; mPhoneId = phoneId; mState = state;
        }

        @Override
        public String toString() {
            return mS + " Time " + mTime.toString() + " mSubId " + mSubId + " mPhoneId "
                    + mPhoneId + "  mState " + mState;
        }
    }

    private LogSSC logSSC [] = new LogSSC[10];
    private int next = 0;

    private void logServiceStateChanged(String s, int subId, int phoneId, ServiceState state) {
        if (logSSC == null || logSSC.length == 0) {
            return;
        }
        if (logSSC[next] == null) {
            logSSC[next] = new LogSSC();
        }
        Time t = new Time();
        t.setToNow();
        logSSC[next].set(t, s, subId, phoneId, state);
        if (++next >= logSSC.length) {
            next = 0;
        }
    }

    private void toStringLogSSC(String prompt) {
        if (logSSC == null || logSSC.length == 0 || (next == 0 && logSSC[next] == null)) {
            log(prompt + ": logSSC is empty");
        } else {
            // There is at least one element
            log(prompt + ": logSSC.length=" + logSSC.length + " next=" + next);
            int i = next;
            if (logSSC[i] == null) {
                // logSSC is not full so back to the beginning
                i = 0;
            }
            do {
                log(logSSC[i].toString());
                if (++i >= logSSC.length) {
                    i = 0;
                }
            } while (i != next);
            log(prompt + ": ----------------");
        }
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
                r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
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
                r.callback.onCellLocationChanged(new Bundle(mCellLocation[phoneId]));
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
