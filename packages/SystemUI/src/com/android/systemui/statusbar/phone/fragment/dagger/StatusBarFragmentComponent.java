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

package com.android.systemui.statusbar.phone.fragment.dagger;

import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.LightsOutNotifController;
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController;
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider;
import com.android.systemui.statusbar.phone.StatusBarDemoMode;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;

import java.util.Set;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * A subcomponent that gets re-created each time we create a new {@link CollapsedStatusBarFragment}.
 *
 * This component will also re-create all classes that depend on {@link CollapsedStatusBarFragment}
 * and friends. Specifically, the fragment creates a new {@link PhoneStatusBarView} and multiple
 * controllers need access to that view, so those controllers will be re-created whenever the
 * fragment is recreated.
 *
 * Anything that depends on {@link CollapsedStatusBarFragment} or {@link PhoneStatusBarView}
 * should be included here or in {@link StatusBarFragmentModule}.
 */

@Subcomponent(modules = {
        StatusBarFragmentModule.class,
        StatusBarStartablesModule.class
})
@StatusBarFragmentScope
public interface StatusBarFragmentComponent {
    /** Simple factory. */
    @Subcomponent.Factory
    interface Factory {
        StatusBarFragmentComponent create(
                @BindsInstance CollapsedStatusBarFragment collapsedStatusBarFragment);
    }

    /**
     * Performs initialization logic after {@link StatusBarFragmentComponent} has been constructed.
     */
    interface Startable {
        void start();
        void stop();

        enum State {
            NONE, STARTING, STARTED, STOPPING, STOPPED
        }
    }

    /**
     * Initialize anything extra for the component. Must be called after the component is created.
     */
    default void init() {
        // No one accesses these controllers, so we need to make sure we reference them here so they
        // do get initialized.
        getBatteryMeterViewController().init();
        getHeadsUpAppearanceController().init();
        getPhoneStatusBarViewController().init();
        getLightsOutNotifController().init();
        getStatusBarDemoMode().init();
    }

    /** */
    @StatusBarFragmentScope
    BatteryMeterViewController getBatteryMeterViewController();

    /** */
    @StatusBarFragmentScope
    @RootView
    PhoneStatusBarView getPhoneStatusBarView();

    /** */
    @StatusBarFragmentScope
    PhoneStatusBarViewController getPhoneStatusBarViewController();

    /** */
    @StatusBarFragmentScope
    HeadsUpAppearanceController getHeadsUpAppearanceController();

    /** */
    @StatusBarFragmentScope
    LightsOutNotifController getLightsOutNotifController();

    /** */
    @StatusBarFragmentScope
    StatusBarDemoMode getStatusBarDemoMode();

    /** */
    @StatusBarFragmentScope
    PhoneStatusBarTransitions getPhoneStatusBarTransitions();

    /** */
    Set<Startable> getStartables();

    /** */
    StatusBarBoundsProvider getBoundsProvider();
}
