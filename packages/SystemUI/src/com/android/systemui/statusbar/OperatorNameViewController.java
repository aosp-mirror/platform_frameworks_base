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

import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.view.View;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** Controller for {@link OperatorNameView}. */
public class OperatorNameViewController extends ViewController<OperatorNameView> {
    private static final String KEY_SHOW_OPERATOR_NAME = "show_operator_name";

    private final DarkIconDispatcher mDarkIconDispatcher;
    private final NetworkController mNetworkController;
    private final TunerService mTunerService;
    private final TelephonyManager mTelephonyManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private OperatorNameViewController(OperatorNameView view,
            DarkIconDispatcher darkIconDispatcher,
            NetworkController networkController,
            TunerService tunerService,
            TelephonyManager telephonyManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        super(view);
        mDarkIconDispatcher = darkIconDispatcher;
        mNetworkController = networkController;
        mTunerService = tunerService;
        mTelephonyManager = telephonyManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
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
        mView.update(mTunerService.getValue(KEY_SHOW_OPERATOR_NAME, 1) != 0,
                mTelephonyManager.isDataCapable(), getSubInfos());
    }

    private List<SubInfo> getSubInfos() {
        List<SubInfo> result = new ArrayList<>();
        List<SubscriptionInfo> subscritionInfos =
                mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(false);

        for (SubscriptionInfo subscriptionInfo : subscritionInfos) {
            int subId = subscriptionInfo.getSubscriptionId();
            result.add(new SubInfo(
                    subscriptionInfo.getCarrierName(),
                    mKeyguardUpdateMonitor.getSimState(subId),
                    mKeyguardUpdateMonitor.getServiceState(subId)));
        }

        return result;
    }

    /** Factory for constructing an {@link OperatorNameViewController}. */
    public static class Factory {
        private final DarkIconDispatcher mDarkIconDispatcher;
        private final NetworkController mNetworkController;
        private final TunerService mTunerService;
        private final TelephonyManager mTelephonyManager;
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

        @Inject
        public Factory(DarkIconDispatcher darkIconDispatcher, NetworkController networkController,
                TunerService tunerService, TelephonyManager telephonyManager,
                KeyguardUpdateMonitor keyguardUpdateMonitor) {
            mDarkIconDispatcher = darkIconDispatcher;
            mNetworkController = networkController;
            mTunerService = tunerService;
            mTelephonyManager = telephonyManager;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        }

        /** Create an {@link OperatorNameViewController}. */
        public OperatorNameViewController create(OperatorNameView view) {
            return new OperatorNameViewController(view, mDarkIconDispatcher, mNetworkController,
                    mTunerService, mTelephonyManager, mKeyguardUpdateMonitor);
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

    private final NetworkController.SignalCallback mSignalCallback =
            new NetworkController.SignalCallback() {
        @Override
        public void setIsAirplaneMode(NetworkController.IconState icon) {
            update();
        }
    };

    private final TunerService.Tunable mTunable = (key, newValue) -> update();


    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshCarrierInfo() {
            mView.updateText(getSubInfos());
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
        private final CharSequence mCarrierName;
        private final int mSimState;
        private final ServiceState mServiceState;

        private SubInfo(CharSequence carrierName,
                int simState, ServiceState serviceState) {
            mCarrierName = carrierName;
            mSimState = simState;
            mServiceState = serviceState;
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
