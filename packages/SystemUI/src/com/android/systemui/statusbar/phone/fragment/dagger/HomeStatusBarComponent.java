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
import com.android.systemui.dagger.qualifiers.DisplaySpecific;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.LegacyLightsOutNotifController;
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController;
import com.android.systemui.statusbar.phone.StatusBarBoundsProvider;
import com.android.systemui.statusbar.phone.StatusBarDemoMode;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;

import dagger.BindsInstance;
import dagger.Subcomponent;

import java.util.Set;

/**
 * A subcomponent that gets re-created each time we create a new {@link CollapsedStatusBarFragment}.
 *
 * This component will also re-create all classes that depend on {@link CollapsedStatusBarFragment}
 * and friends. Specifically, the fragment creates a new {@link PhoneStatusBarView} and multiple
 * controllers need access to that view, so those controllers will be re-created whenever the
 * fragment is recreated.
 *
 * Anything that depends on {@link CollapsedStatusBarFragment} or {@link PhoneStatusBarView}
 * should be included here or in {@link HomeStatusBarModule}.
 */
@Subcomponent(modules = {
        HomeStatusBarModule.class,
        StatusBarStartablesModule.class
})
@HomeStatusBarScope
public interface HomeStatusBarComponent {
    /** Simple factory. */
    @Subcomponent.Factory
    interface Factory {
        /** */
        HomeStatusBarComponent create(
                @BindsInstance @RootView PhoneStatusBarView phoneStatusBarView);
    }

    /**
     * Performs initialization logic after {@link HomeStatusBarComponent} has been constructed.
     */
    interface Startable {
        /** */
        void start();
        /** */
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
        if (!NotificationsLiveDataStoreRefactor.isEnabled()) {
            getLegacyLightsOutNotifController().init();
        }
        getStatusBarDemoMode().init();
    }

    /** */
    @HomeStatusBarScope
    BatteryMeterViewController getBatteryMeterViewController();

    /** */
    @HomeStatusBarScope
    @RootView
    PhoneStatusBarView getPhoneStatusBarView();

    /** */
    @HomeStatusBarScope
    PhoneStatusBarViewController getPhoneStatusBarViewController();

    /** */
    @HomeStatusBarScope
    HeadsUpAppearanceController getHeadsUpAppearanceController();

    /** */
    @HomeStatusBarScope
    LegacyLightsOutNotifController getLegacyLightsOutNotifController();

    /** */
    @HomeStatusBarScope
    StatusBarDemoMode getStatusBarDemoMode();

    /** */
    @HomeStatusBarScope
    PhoneStatusBarTransitions getPhoneStatusBarTransitions();

    /** */
    Set<Startable> getStartables();

    /** */
    StatusBarBoundsProvider getBoundsProvider();

    /** */
    @DisplaySpecific
    DarkIconDispatcher getDarkIconDispatcher();

    /** */
    @DisplaySpecific
    int getDisplayId();
}
