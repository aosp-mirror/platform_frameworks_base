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
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A service that listens to connectivity and SIM card changes and determines if the emergency
 * affordance should be enabled.
 */
public class EmergencyAffordanceService extends SystemService {

    private static final String TAG = "EmergencyAffordanceService";
    private static final boolean DBG = false;

    private static final String SERVICE_NAME = "emergency_affordance";

    private static final int INITIALIZE_STATE = 1;
    /**
     * @param arg1 slot Index
     * @param arg2 0
     * @param obj ISO country code
     */
    private static final int NETWORK_COUNTRY_CHANGED = 2;
    private static final int SUBSCRIPTION_CHANGED = 3;
    private static final int UPDATE_AIRPLANE_MODE_STATUS = 4;

    // Global Settings to override emergency affordance country ISO for debugging.
    // Available only on debug build. The value is a country ISO string in lower case (eg. "us").
    private static final String EMERGENCY_AFFORDANCE_OVERRIDE_ISO =
            "emergency_affordance_override_iso";

    private final Context mContext;
    // Country ISOs that require affordance
    private final ArrayList<String> mEmergencyCallCountryIsos;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private MyHandler mHandler;
    private boolean mAnySimNeedsEmergencyAffordance;
    private boolean mAnyNetworkNeedsEmergencyAffordance;
    private boolean mEmergencyAffordanceNeeded;
    private boolean mAirplaneModeEnabled;
    private boolean mVoiceCapable;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED.equals(intent.getAction())) {
                String countryCode = intent.getStringExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY);
                int slotId = intent.getIntExtra(SubscriptionManager.EXTRA_SLOT_INDEX,
                        SubscriptionManager.INVALID_SIM_SLOT_INDEX);
                mHandler.obtainMessage(
                        NETWORK_COUNTRY_CHANGED, slotId, 0, countryCode).sendToTarget();
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
                mHandler.obtainMessage(UPDATE_AIRPLANE_MODE_STATUS).sendToTarget();
            }
        }
    };

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
        String[] isos = context.getResources().getStringArray(
                com.android.internal.R.array.config_emergency_iso_country_codes);
        mEmergencyCallCountryIsos = new ArrayList<>(isos.length);
        for (String iso : isos) {
            mEmergencyCallCountryIsos.add(iso);
        }

        if (Build.IS_DEBUGGABLE) {
            String overrideIso = Settings.Global.getString(
                    mContext.getContentResolver(), EMERGENCY_AFFORDANCE_OVERRIDE_ISO);
            if (!TextUtils.isEmpty(overrideIso)) {
                if (DBG) Slog.d(TAG, "Override ISO to " + overrideIso);
                mEmergencyCallCountryIsos.clear();
                mEmergencyCallCountryIsos.add(overrideIso);
            }
        }
    }

    @Override
    public void onStart() {
        if (DBG) Slog.i(TAG, "onStart");
        publishBinderService(SERVICE_NAME, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            if (DBG) Slog.i(TAG, "onBootPhase");
            handleThirdPartyBootPhase();
        }
    }

    /** Handler to do the heavier work on */
    private class MyHandler extends Handler {
        public MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            if (DBG) Slog.d(TAG, "handleMessage: " + msg.what);
            switch (msg.what) {
                case INITIALIZE_STATE:
                    handleInitializeState();
                    break;
                case NETWORK_COUNTRY_CHANGED:
                    final String countryIso = (String) msg.obj;
                    final int slotId = msg.arg1;
                    handleNetworkCountryChanged(countryIso, slotId);
                    break;
                case SUBSCRIPTION_CHANGED:
                    handleUpdateSimSubscriptionInfo();
                    break;
                case UPDATE_AIRPLANE_MODE_STATUS:
                    handleUpdateAirplaneModeStatus();
                    break;
                default:
                    Slog.e(TAG, "Unexpected message received: " + msg.what);
            }
        }
    }

    private void handleInitializeState() {
        if (DBG) Slog.d(TAG, "handleInitializeState");
        handleUpdateAirplaneModeStatus();
        handleUpdateSimSubscriptionInfo();
        updateNetworkCountry();
        updateEmergencyAffordanceNeeded();
    }

    private void handleThirdPartyBootPhase() {
        if (DBG) Slog.d(TAG, "handleThirdPartyBootPhase");
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mVoiceCapable = mTelephonyManager.isVoiceCapable();
        if (!mVoiceCapable) {
            updateEmergencyAffordanceNeeded();
            return;
        }

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new MyHandler(thread.getLooper());

        mSubscriptionManager = SubscriptionManager.from(mContext);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionChangedListener);

        IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mHandler.obtainMessage(INITIALIZE_STATE).sendToTarget();
    }

    private void handleUpdateAirplaneModeStatus() {
        mAirplaneModeEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        if (DBG) Slog.d(TAG, "APM status updated to " + mAirplaneModeEnabled);
    }

    private void handleUpdateSimSubscriptionInfo() {
        List<SubscriptionInfo> activeSubscriptionInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList();
        if (DBG) Slog.d(TAG, "handleUpdateSimSubscriptionInfo: " + activeSubscriptionInfoList);
        if (activeSubscriptionInfoList == null) {
            return;
        }

        boolean needsAffordance = false;
        for (SubscriptionInfo info : activeSubscriptionInfoList) {
            if (isoRequiresEmergencyAffordance(info.getCountryIso())) {
                needsAffordance = true;
                break;
            }
        }

        mAnySimNeedsEmergencyAffordance = needsAffordance;
        updateEmergencyAffordanceNeeded();
    }

    private void handleNetworkCountryChanged(String countryIso, int slotId) {
        if (DBG) {
            Slog.d(TAG, "handleNetworkCountryChanged: countryIso=" + countryIso
                    + ", slotId=" + slotId);
        }

        if (TextUtils.isEmpty(countryIso) && mAirplaneModeEnabled) {
            Slog.w(TAG, "Ignore empty countryIso report when APM is on.");
            return;
        }

        updateNetworkCountry();

        updateEmergencyAffordanceNeeded();
    }

    private void updateNetworkCountry() {
        boolean needsAffordance = false;

        final int activeModems = mTelephonyManager.getActiveModemCount();
        for (int i = 0; i < activeModems; i++) {
            String countryIso = mTelephonyManager.getNetworkCountryIso(i);
            if (DBG) Slog.d(TAG, "UpdateNetworkCountry: slotId=" + i + " countryIso=" + countryIso);
            if (isoRequiresEmergencyAffordance(countryIso)) {
                needsAffordance = true;
                break;
            }
        }

        mAnyNetworkNeedsEmergencyAffordance = needsAffordance;

        updateEmergencyAffordanceNeeded();
    }

    private boolean isoRequiresEmergencyAffordance(String iso) {
        return mEmergencyCallCountryIsos.contains(iso);
    }

    private void updateEmergencyAffordanceNeeded() {
        if (DBG) {
            Slog.d(TAG, "updateEmergencyAffordanceNeeded: mEmergencyAffordanceNeeded="
                    + mEmergencyAffordanceNeeded + ", mVoiceCapable=" + mVoiceCapable
                    + ", mAnySimNeedsEmergencyAffordance=" + mAnySimNeedsEmergencyAffordance
                    + ", mAnyNetworkNeedsEmergencyAffordance="
                    + mAnyNetworkNeedsEmergencyAffordance);
        }
        boolean lastAffordanceNeeded = mEmergencyAffordanceNeeded;

        mEmergencyAffordanceNeeded = mVoiceCapable
                && (mAnySimNeedsEmergencyAffordance || mAnyNetworkNeedsEmergencyAffordance);

        if (lastAffordanceNeeded != mEmergencyAffordanceNeeded) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.EMERGENCY_AFFORDANCE_NEEDED,
                    mEmergencyAffordanceNeeded ? 1 : 0);
        }
    }

    private void dumpInternal(IndentingPrintWriter ipw) {
        ipw.println("EmergencyAffordanceService (dumpsys emergency_affordance) state:\n");
        ipw.println("mEmergencyAffordanceNeeded=" + mEmergencyAffordanceNeeded);
        ipw.println("mVoiceCapable=" + mVoiceCapable);
        ipw.println("mAnySimNeedsEmergencyAffordance=" + mAnySimNeedsEmergencyAffordance);
        ipw.println("mAnyNetworkNeedsEmergencyAffordance=" + mAnyNetworkNeedsEmergencyAffordance);
        ipw.println("mEmergencyCallCountryIsos=" + String.join(",", mEmergencyCallCountryIsos));
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
                return;
            }

            dumpInternal(new IndentingPrintWriter(pw, "  "));
        }
    }
}
