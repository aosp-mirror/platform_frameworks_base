/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity;

import com.android.settingslib.net.DataUsageController;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.statusbar.policy.DataSaverController;

/** */
public interface NetworkController extends CallbackController<SignalCallback>, DemoMode {
    /** */
    boolean hasMobileDataFeature();
    /** */
    void setWifiEnabled(boolean enabled);
    /** */
    AccessPointController getAccessPointController();
    /** */
    DataUsageController getMobileDataController();
    /** */
    DataSaverController getDataSaverController();
    /** */
    String getMobileDataNetworkName();
    /** */
    boolean isMobileDataNetworkInService();
    /** */
    int getNumberSubscriptions();

    /** */
    boolean hasVoiceCallingFeature();

    /** */
    void addEmergencyListener(EmergencyListener listener);
    /** */
    void removeEmergencyListener(EmergencyListener listener);

    /** */
    boolean isRadioOn();

    /** */
    interface EmergencyListener {
        void setEmergencyCallsOnly(boolean emergencyOnly);
    }
}
