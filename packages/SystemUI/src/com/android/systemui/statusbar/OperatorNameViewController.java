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
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor;
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxy;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.kotlin.JavaAdapter;

import kotlinx.coroutines.Job;

import javax.inject.Inject;

/** Controller for {@link OperatorNameView}. */
public class OperatorNameViewController extends ViewController<OperatorNameView> {
    private static final String KEY_SHOW_OPERATOR_NAME = "show_operator_name";

    private final DarkIconDispatcher mDarkIconDispatcher;
    private final TunerService mTunerService;
    private final TelephonyManager mTelephonyManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final CarrierConfigTracker mCarrierConfigTracker;
    private final AirplaneModeInteractor mAirplaneModeInteractor;
    private final SubscriptionManagerProxy mSubscriptionManagerProxy;
    private final JavaAdapter mJavaAdapter;

    private Job mAirplaneModeJob;

    private OperatorNameViewController(OperatorNameView view,
            DarkIconDispatcher darkIconDispatcher,
            TunerService tunerService,
            TelephonyManager telephonyManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            CarrierConfigTracker carrierConfigTracker,
            AirplaneModeInteractor airplaneModeInteractor,
            SubscriptionManagerProxy subscriptionManagerProxy,
            JavaAdapter javaAdapter) {
        super(view);
        mDarkIconDispatcher = darkIconDispatcher;
        mTunerService = tunerService;
        mTelephonyManager = telephonyManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mCarrierConfigTracker = carrierConfigTracker;
        mAirplaneModeInteractor = airplaneModeInteractor;
        mSubscriptionManagerProxy = subscriptionManagerProxy;
        mJavaAdapter = javaAdapter;
    }

    @Override
    protected void onViewAttached() {
        mDarkIconDispatcher.addDarkReceiver(mDarkReceiver);
        mAirplaneModeJob =
                mJavaAdapter.alwaysCollectFlow(
                        mAirplaneModeInteractor.isAirplaneMode(),
                        (isAirplaneMode) -> update());
        mTunerService.addTunable(mTunable, KEY_SHOW_OPERATOR_NAME);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
    }

    @Override
    protected void onViewDetached() {
        mDarkIconDispatcher.removeDarkReceiver(mDarkReceiver);
        mAirplaneModeJob.cancel(null);
        mTunerService.removeTunable(mTunable);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
    }

    private void update() {
        SubInfo defaultSubInfo = getDefaultSubInfo();
        boolean showOperatorName =
                mCarrierConfigTracker
                        .getShowOperatorNameInStatusBarConfig(defaultSubInfo.getSubId())
                        && (mTunerService.getValue(KEY_SHOW_OPERATOR_NAME, 1) != 0);
        mView.update(
                showOperatorName,
                mTelephonyManager.isDataCapable(),
                mAirplaneModeInteractor.isAirplaneMode().getValue(),
                getDefaultSubInfo()
        );
    }

    private SubInfo getDefaultSubInfo() {
        int defaultSubId = mSubscriptionManagerProxy.getDefaultDataSubscriptionId();

        SubscriptionInfo sI = mKeyguardUpdateMonitor.getSubscriptionInfoForSubId(defaultSubId);
        return new SubInfo(
                sI.getSubscriptionId(),
                sI.getCarrierName(),
                mKeyguardUpdateMonitor.getSimState(defaultSubId),
                mKeyguardUpdateMonitor.getServiceState(defaultSubId));
    }

    /** Factory for constructing an {@link OperatorNameViewController}. */
    public static class Factory {
        private final TunerService mTunerService;
        private final TelephonyManager mTelephonyManager;
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final CarrierConfigTracker mCarrierConfigTracker;
        private final AirplaneModeInteractor mAirplaneModeInteractor;
        private final SubscriptionManagerProxy mSubscriptionManagerProxy;
        private final JavaAdapter mJavaAdapter;

        @Inject
        public Factory(
                TunerService tunerService,
                TelephonyManager telephonyManager,
                KeyguardUpdateMonitor keyguardUpdateMonitor,
                CarrierConfigTracker carrierConfigTracker,
                AirplaneModeInteractor airplaneModeInteractor,
                SubscriptionManagerProxy subscriptionManagerProxy,
                JavaAdapter javaAdapter) {
            mTunerService = tunerService;
            mTelephonyManager = telephonyManager;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mCarrierConfigTracker = carrierConfigTracker;
            mAirplaneModeInteractor = airplaneModeInteractor;
            mSubscriptionManagerProxy = subscriptionManagerProxy;
            mJavaAdapter = javaAdapter;
        }

        /** Create an {@link OperatorNameViewController}. */
        public OperatorNameViewController create(
                OperatorNameView view, DarkIconDispatcher darkIconDispatcher) {
            return new OperatorNameViewController(
                    view,
                    darkIconDispatcher,
                    mTunerService,
                    mTelephonyManager,
                    mKeyguardUpdateMonitor,
                    mCarrierConfigTracker,
                    mAirplaneModeInteractor,
                    mSubscriptionManagerProxy,
                    mJavaAdapter);
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
