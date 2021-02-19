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

package com.android.keyguard;

import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link CarrierText}.
 */
public class CarrierTextController extends ViewController<CarrierText> {
    private final CarrierTextManager mCarrierTextManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final CarrierTextManager.CarrierTextCallback mCarrierTextCallback =
            new CarrierTextManager.CarrierTextCallback() {
                @Override
                public void updateCarrierInfo(CarrierTextManager.CarrierTextCallbackInfo info) {
                    mView.setText(info.carrierText);
                }

                @Override
                public void startedGoingToSleep() {
                    mView.setSelected(false);
                }

                @Override
                public void finishedWakingUp() {
                    mView.setSelected(true);
                }
            };

    @Inject
    public CarrierTextController(CarrierText view,
            CarrierTextManager.Builder carrierTextManagerBuilder,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        super(view);

        mCarrierTextManager = carrierTextManagerBuilder
                .setShowAirplaneMode(mView.getShowAirplaneMode())
                .setShowMissingSim(mView.getShowMissingSim())
                .build();
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
    }

    @Override
    protected void onInit() {
        super.onInit();
        mView.setSelected(mKeyguardUpdateMonitor.isDeviceInteractive());
    }

    @Override
    protected void onViewAttached() {
        mCarrierTextManager.setListening(mCarrierTextCallback);
    }

    @Override
    protected void onViewDetached() {
        mCarrierTextManager.setListening(null);
    }
}
