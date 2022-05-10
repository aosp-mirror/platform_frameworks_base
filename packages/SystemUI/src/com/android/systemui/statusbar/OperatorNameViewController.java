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

package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.View;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link OperatorNameView}. */
public class OperatorNameViewController extends ViewController<OperatorNameView> {
    private static final String KEY_SHOW_OPERATOR_NAME = "show_operator_name";

    private final DarkIconDispatcher mDarkIconDispatcher;
    private final NetworkController mNetworkController;
    private final TunerService mTunerService;
    private final TelephonyManager mTelephonyManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final CarrierConfigTracker mCarrierConfigTracker;

    private OperatorNameViewController(OperatorNameView view,
            DarkIconDispatcher darkIconDispatcher,
            NetworkController networkController,
            TunerService tunerService,
            TelephonyManager telephonyManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            CarrierConfigTracker carrierConfigTracker) {
        super(view);
        mDarkIconDispatcher = darkIconDispatcher;
        mNetworkController = networkController;
        mTunerService = tunerService;
        mTelephonyManager = telephonyManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mCarrierConfigTracker = carrierConfigTracker;
    }

    @Override
    protected void onViewAttached() {
        mDarkIconDispatcher.addDarkReceiver(mDarkReceiver);
        mNetworkController.addCallback(mSignalCallback);
        mTunerService.addTunable(mTunable, KEY_SHOW_OPERATOR_NAME);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
    }

    @Override
    protected void onViewDetached() {
        mDarkIconDispatcher.removeDarkReceiver(mDarkReceiver);
        mNetworkController.removeCallback(mSignalCallback);
        mTunerService.removeTunable(mTunable);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
    }

    private void update() {
        SubInfo defaultSubInfo = getDefaultSubInfo();
        boolean showOperatorName =
                mCarrierConfigTracker
                        .getShowOperatorNameInStatusBarConfig(defaultSubInfo.getSubId())
                        && (mTunerService.getValue(KEY_SHOW_OPERATOR_NAME, 1) != 0);
        mView.update(showOperatorName, mTelephonyManager.isDataCapable(), getDefaultSubInfo());
    }

    private SubInfo getDefaultSubInfo() {
        int defaultSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        SubscriptionInfo sI = mKeyguardUpdateMonitor.getSubscriptionInfoForSubId(defaultSubId);
        return new SubInfo(
                sI.getSubscriptionId(),
                sI.getCarrierName(),
                mKeyguardUpdateMonitor.getSimState(defaultSubId),
                mKeyguardUpdateMonitor.getServiceState(defaultSubId));
    }

    /** Factory for constructing an {@link OperatorNameViewController}. */
    public static class Factory {
        private final DarkIconDispatcher mDarkIconDispatcher;
        private final NetworkController mNetworkController;
        private final TunerService mTunerService;
        private final TelephonyManager mTelephonyManager;
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final CarrierConfigTracker mCarrierConfigTracker;

        @Inject
        public Factory(DarkIconDispatcher darkIconDispatcher,
                NetworkController networkController,
                TunerService tunerService,
                TelephonyManager telephonyManager,
                KeyguardUpdateMonitor keyguardUpdateMonitor,
                CarrierConfigTracker carrierConfigTracker) {
            mDarkIconDispatcher = darkIconDispatcher;
            mNetworkController = networkController;
            mTunerService = tunerService;
            mTelephonyManager = telephonyManager;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mCarrierConfigTracker = carrierConfigTracker;
        }

        /** Create an {@link OperatorNameViewController}. */
        public OperatorNameViewController create(OperatorNameView view) {
            return new OperatorNameViewController(view,
                    mDarkIconDispatcher,
                    mNetworkController,
                    mTunerService,
                    mTelephonyManager,
                    mKeyguardUpdateMonitor,
                    mCarrierConfigTracker);
        }
    }

    /**
     * Needed because of how {@link CollapsedStatusBarFragment} works.
     *
     * Ideally this can be done internally.
     **/
    public View getView() {
        return mView;
    }

    private final DarkIconDispatcher.DarkReceiver mDarkReceiver =
            (area, darkIntensity, tint) ->
                    mView.setTextColor(DarkIconDispatcher.getTint(area, mView, tint));

    private final SignalCallback mSignalCallback = new SignalCallback() {
        @Override
        public void setIsAirplaneMode(@NonNull IconState icon) {
            update();
        }
    };

    private final TunerService.Tunable mTunable = (key, newValue) -> update();


    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshCarrierInfo() {
            mView.updateText(getDefaultSubInfo());
        }
    };

    // TODO: do we even register this anywhere?
    private final DemoModeCommandReceiver mDemoModeCommandReceiver = new DemoModeCommandReceiver() {
        @Override
        public void onDemoModeStarted() {
            mView.setDemoMode(true);
        }

        @Override
        public void onDemoModeFinished() {
            mView.setDemoMode(false);
            update();
        }

        @Override
        public void dispatchDemoCommand(String command, Bundle args) {
            mView.setText(args.getString("name"));
        }
    };

    static class SubInfo {
        private final int mSubId;
        private final CharSequence mCarrierName;
        private final int mSimState;
        private final ServiceState mServiceState;

        private SubInfo(
                int subId,
                CharSequence carrierName,
                int simState,
                ServiceState serviceState) {
            mSubId = subId;
            mCarrierName = carrierName;
            mSimState = simState;
            mServiceState = serviceState;
        }

        int getSubId() {
            return mSubId;
        }

        boolean simReady() {
            return mSimState == TelephonyManager.SIM_STATE_READY;
        }

        CharSequence getCarrierName() {
            return mCarrierName;
        }

        boolean stateInService() {
            return mServiceState != null
                    && mServiceState.getState() == ServiceState.STATE_IN_SERVICE;
        }
    }
}
