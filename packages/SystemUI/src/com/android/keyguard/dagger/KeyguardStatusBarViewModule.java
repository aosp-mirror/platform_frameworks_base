/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard.dagger;

import com.android.keyguard.CarrierText;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;

import dagger.Module;
import dagger.Provides;

/** Dagger module for {@link KeyguardStatusBarViewComponent}. */
@Module
public abstract class KeyguardStatusBarViewModule {
    @Provides
    @KeyguardStatusBarViewScope
    static CarrierText getCarrierText(KeyguardStatusBarView view) {
        return view.findViewById(R.id.keyguard_carrier_text);
    }

    /** */
    @Provides
    @KeyguardStatusBarViewScope
    static BatteryMeterView getBatteryMeterView(KeyguardStatusBarView view) {
        return view.findViewById(R.id.battery);
    }

    /** */
    @Provides
    @KeyguardStatusBarViewScope
    static StatusBarLocation getStatusBarLocation() {
        return StatusBarLocation.KEYGUARD;
    }

    /** */
    @Provides
    @KeyguardStatusBarViewScope
    static StatusBarUserSwitcherContainer getUserSwitcherContainer(KeyguardStatusBarView view) {
        return view.findViewById(R.id.user_switcher_container);
    }
}
