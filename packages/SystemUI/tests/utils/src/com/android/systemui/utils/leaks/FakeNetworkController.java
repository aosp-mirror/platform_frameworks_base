/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.os.Bundle;
import android.testing.LeakCheck;

import com.android.settingslib.net.DataUsageController;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.policy.DataSaverController;

public class FakeNetworkController extends BaseLeakChecker<SignalCallback>
        implements NetworkController {

    private final FakeDataSaverController mDataSaverController;
    private final BaseLeakChecker<EmergencyListener> mEmergencyChecker;

    public FakeNetworkController(LeakCheck test) {
        super(test, "network");
        mDataSaverController = new FakeDataSaverController(test);
        mEmergencyChecker = new BaseLeakChecker<>(test, "emergency");
    }

    @Override
    public void addEmergencyListener(EmergencyListener listener) {
        mEmergencyChecker.addCallback(listener);
    }

    @Override
    public void removeEmergencyListener(EmergencyListener listener) {
        mEmergencyChecker.removeCallback(listener);
    }

    @Override
    public boolean isRadioOn() {
        return false;
    }

    @Override
    public DataSaverController getDataSaverController() {
        return mDataSaverController;
    }

    @Override
    public boolean hasMobileDataFeature() {
        return false;
    }

    @Override
    public void setWifiEnabled(boolean enabled) {

    }

    @Override
    public AccessPointController getAccessPointController() {
        return null;
    }

    @Override
    public DataUsageController getMobileDataController() {
        return null;
    }

    @Override
    public boolean hasVoiceCallingFeature() {
        return false;
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {

    }

    @Override
    public String getMobileDataNetworkName() {
        return "";
    }

    @Override
    public boolean isMobileDataNetworkInService() {
        return false;
    }

    @Override
    public int getNumberSubscriptions() {
        return 0;
    }
}
