/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.emergency;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A service that listens to connectivity and SIM card changes and determines if the emergency mode
 * should be enabled
 */
public class EmergencyAffordanceService extends SystemService {

    private static final String TAG = "EmergencyAffordanceService";

    private static final int NUM_SCANS_UNTIL_ABORT = 4;

    private static final int INITIALIZE_STATE = 1;
    private static final int CELL_INFO_STATE_CHANGED = 2;
    private static final int SUBSCRIPTION_CHANGED = 3;

    /**
     * Global setting, whether the last scan of the sim cards reveal that a sim was inserted that
     * requires the emergency affordance. The value is a boolean (1 or 0).
     * @hide
     */
    private static final String EMERGENCY_SIM_INSERTED_SETTING = "emergency_sim_inserted_before";

    private final Context mContext;
    private final ArrayList<Integer> mEmergencyCallMccNumbers;

    private final Object mLock = new Object();

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;
    private boolean mEmergencyAffordanceNeeded;
    private MyHandler mHandler;
    private int mScansCompleted;
    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            if (!isEmergencyAffordanceNeeded()) {
                requestCellScan();
            }
        }

        @Override
        public void onCellLocationChanged(CellLocation location) {
            if (!isEmergencyAffordanceNeeded()) {
                requestCellScan();
            }
        }
    };
    private BroadcastReceiver mAirplaneModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) == 0) {
                startScanning();
                requestCellScan();
            }
        }
    };
    private boolean mSimNeedsEmergencyAffordance;
    private boolean mNetworkNeedsEmergencyAffordance;
    private boolean mVoiceCapable;

    private void requestCellScan() {
        mHandler.obtainMessage(CELL_INFO_STATE_CHANGED).sendToTarget();
    }

    private SubscriptionManager.OnSubscriptionsChangedListener mSubscriptionChangedListener
            = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            mHandler.obtainMessage(SUBSCRIPTION_CHANGED).sendToTarget();
        }
    };

    public EmergencyAffordanceService(Context context) {
        super(context);
        mContext = context;
        int[] numbers = context.getResources().getIntArray(
                com.android.internal.R.array.config_emergency_mcc_codes);
        mEmergencyCallMccNumbers = new ArrayList<>(numbers.length);
        for (int i = 0; i < numbers.length; i++) {
            mEmergencyCallMccNumbers.add(numbers[i]);
        }
    }

    private void updateEmergencyAffordanceNeeded() {
        synchronized (mLock) {
            mEmergencyAffordanceNeeded = mVoiceCapable && (mSimNeedsEmergencyAffordance ||
                    mNetworkNeedsEmergencyAffordance);
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.EMERGENCY_AFFORDANCE_NEEDED,
                    mEmergencyAffordanceNeeded ? 1 : 0);
            if (mEmergencyAffordanceNeeded) {
                stopScanning();
            }
        }
    }

    private void stopScanning() {
        synchronized (mLock) {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mScansCompleted = 0;
        }
    }

    private boolean isEmergencyAffordanceNeeded() {
        synchronized (mLock) {
            return mEmergencyAffordanceNeeded;
        }
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
            mVoiceCapable = mTelephonyManager.isVoiceCapable();
            if (!mVoiceCapable) {
                updateEmergencyAffordanceNeeded();
                return;
            }
            mSubscriptionManager = SubscriptionManager.from(mContext);
            HandlerThread thread = new HandlerThread(TAG);
            thread.start();
            mHandler = new MyHandler(thread.getLooper());
            mHandler.obtainMessage(INITIALIZE_STATE).sendToTarget();
            startScanning();
            IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            mContext.registerReceiver(mAirplaneModeReceiver, filter);
            mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionChangedListener);
        }
    }

    private void startScanning() {
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CELL_INFO
                | PhoneStateListener.LISTEN_CELL_LOCATION);
    }

    /** Handler to do the heavier work on */
    private class MyHandler extends Handler {

        public MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case INITIALIZE_STATE:
                    handleInitializeState();
                    break;
                case CELL_INFO_STATE_CHANGED:
                    handleUpdateCellInfo();
                    break;
                case SUBSCRIPTION_CHANGED:
                    handleUpdateSimSubscriptionInfo();
                    break;
            }
        }
    }

    private void handleInitializeState() {
        if (handleUpdateSimSubscriptionInfo()) {
            return;
        }
        if (handleUpdateCellInfo()) {
            return;
        }
        updateEmergencyAffordanceNeeded();
    }

    private boolean handleUpdateSimSubscriptionInfo() {
        boolean neededBefore = simNeededAffordanceBefore();
        boolean neededNow = neededBefore;
        List<SubscriptionInfo> activeSubscriptionInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList();
        if (activeSubscriptionInfoList == null) {
            return neededNow;
        }
        for (SubscriptionInfo info : activeSubscriptionInfoList) {
            int mcc = info.getMcc();
            if (mccRequiresEmergencyAffordance(mcc)) {
                neededNow = true;
                break;
            } else if (mcc != 0 && mcc != Integer.MAX_VALUE){
                // a Sim with a different mcc code was found
                neededNow = false;
            }
            String simOperator  = mTelephonyManager.getSimOperator(info.getSubscriptionId());
            mcc = 0;
            if (simOperator != null && simOperator.length() >= 3) {
                mcc = Integer.parseInt(simOperator.substring(0, 3));
            }
            if (mcc != 0) {
                if (mccRequiresEmergencyAffordance(mcc)) {
                    neededNow = true;
                    break;
                } else {
                    // a Sim with a different mcc code was found
                    neededNow = false;
                }
            }
        }
        setSimNeedsEmergencyAffordance(neededNow);
        return neededNow;
    }

    private void setSimNeedsEmergencyAffordance(boolean simNeedsEmergencyAffordance) {
        if (simNeededAffordanceBefore() != simNeedsEmergencyAffordance) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    EMERGENCY_SIM_INSERTED_SETTING,
                    simNeedsEmergencyAffordance ? 1 : 0);
        }
        if (simNeedsEmergencyAffordance != mSimNeedsEmergencyAffordance) {
            mSimNeedsEmergencyAffordance = simNeedsEmergencyAffordance;
            updateEmergencyAffordanceNeeded();
        }
    }

    private boolean simNeededAffordanceBefore() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                EMERGENCY_SIM_INSERTED_SETTING, 0) != 0;
    }

    private boolean handleUpdateCellInfo() {
        List<CellInfo> cellInfos = mTelephonyManager.getAllCellInfo();
        if (cellInfos == null) {
            return false;
        }
        boolean stopScanningAfterScan = false;
        for (CellInfo cellInfo : cellInfos) {
            int mcc = 0;
            if (cellInfo instanceof CellInfoGsm) {
                mcc = ((CellInfoGsm) cellInfo).getCellIdentity().getMcc();
            } else if (cellInfo instanceof CellInfoLte) {
                mcc = ((CellInfoLte) cellInfo).getCellIdentity().getMcc();
            } else if (cellInfo instanceof CellInfoWcdma) {
                mcc = ((CellInfoWcdma) cellInfo).getCellIdentity().getMcc();
            }
            if (mccRequiresEmergencyAffordance(mcc)) {
                setNetworkNeedsEmergencyAffordance(true);
                return true;
            } else if (mcc != 0 && mcc != Integer.MAX_VALUE) {
                // we found an mcc that isn't in the list, abort
                stopScanningAfterScan = true;
            }
        }
        if (stopScanningAfterScan) {
            stopScanning();
        } else {
            onCellScanFinishedUnsuccessful();
        }
        setNetworkNeedsEmergencyAffordance(false);
        return false;
    }

    private void setNetworkNeedsEmergencyAffordance(boolean needsAffordance) {
        synchronized (mLock) {
            mNetworkNeedsEmergencyAffordance = needsAffordance;
            updateEmergencyAffordanceNeeded();
        }
    }

    private void onCellScanFinishedUnsuccessful() {
        synchronized (mLock) {
            mScansCompleted++;
            if (mScansCompleted >= NUM_SCANS_UNTIL_ABORT) {
                stopScanning();
            }
        }
    }

    private boolean mccRequiresEmergencyAffordance(int mcc) {
        return mEmergencyCallMccNumbers.contains(mcc);
    }
}
