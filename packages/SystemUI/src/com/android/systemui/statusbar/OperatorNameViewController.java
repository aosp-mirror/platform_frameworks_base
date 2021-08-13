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
import android.telephony.TelephonyManager;
import android.view.View;

import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link OperatorNameView}. */
public class OperatorNameViewController extends ViewController<OperatorNameView> {
    private static final String KEY_SHOW_OPERATOR_NAME = "show_operator_name";

    private final DarkIconDispatcher mDarkIconDispatcher;
    private final NetworkController mNetworkController;
    private final TunerService mTunerService;
    private final TelephonyManager mTelephonyManager;

    private OperatorNameViewController(OperatorNameView view,
            DarkIconDispatcher darkIconDispatcher,
            NetworkController networkController,
            TunerService tunerService,
            TelephonyManager telephonyManager) {
        super(view);
        mDarkIconDispatcher = darkIconDispatcher;
        mNetworkController = networkController;
        mTunerService = tunerService;
        mTelephonyManager = telephonyManager;
    }

    @Override
    protected void onViewAttached() {
        mDarkIconDispatcher.addDarkReceiver(mDarkReceiver);
        mNetworkController.addCallback(mSignalCallback);
        mTunerService.addTunable(mTunable, KEY_SHOW_OPERATOR_NAME);
    }

    @Override
    protected void onViewDetached() {
        mDarkIconDispatcher.removeDarkReceiver(mDarkReceiver);
        mNetworkController.removeCallback(mSignalCallback);
        mTunerService.removeTunable(mTunable);
    }

    private void update() {
        mView.update(mTunerService.getValue(KEY_SHOW_OPERATOR_NAME, 1) != 0,
                mTelephonyManager.isDataCapable());
    }

    /** Factory for constructing an {@link OperatorNameViewController}. */
    public static class Factory {
        private final DarkIconDispatcher mDarkIconDispatcher;
        private final NetworkController mNetworkController;
        private final TunerService mTunerService;
        private final TelephonyManager mTelephonyManager;

        @Inject
        public Factory(DarkIconDispatcher darkIconDispatcher, NetworkController networkController,
                TunerService tunerService, TelephonyManager telephonyManager) {
            mDarkIconDispatcher = darkIconDispatcher;
            mNetworkController = networkController;
            mTunerService = tunerService;
            mTelephonyManager = telephonyManager;
        }

        /** Create an {@link OperatorNameViewController}. */
        public OperatorNameViewController create(OperatorNameView view) {
            return new OperatorNameViewController(view, mDarkIconDispatcher, mNetworkController,
                    mTunerService, mTelephonyManager);
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
}
