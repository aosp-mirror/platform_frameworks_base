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

package com.android.systemui.car.statusbar;

import android.content.Context;
import android.view.View;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.R;
import com.android.systemui.car.navigationbar.CarNavigationBarController;
import com.android.systemui.dock.DockManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.NavigationModeController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Car implementation of the {@link StatusBarKeyguardViewManager}. */
@Singleton
public class CarStatusBarKeyguardViewManager extends StatusBarKeyguardViewManager {

    protected boolean mShouldHideNavBar;
    private final CarNavigationBarController mCarNavigationBarController;
    private Set<OnKeyguardCancelClickedListener> mKeygaurdCancelClickedListenerSet;

    @Inject
    public CarStatusBarKeyguardViewManager(Context context,
            ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils,
            SysuiStatusBarStateController sysuiStatusBarStateController,
            ConfigurationController configurationController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            NavigationModeController navigationModeController,
            DockManager dockManager,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController,
            NotificationMediaManager notificationMediaManager,
            CarNavigationBarController carNavigationBarController) {
        super(context, callback, lockPatternUtils, sysuiStatusBarStateController,
                configurationController, keyguardUpdateMonitor, navigationModeController,
                dockManager, notificationShadeWindowController, keyguardStateController,
                notificationMediaManager);
        mShouldHideNavBar = context.getResources()
                .getBoolean(R.bool.config_hideNavWhenKeyguardBouncerShown);
        mCarNavigationBarController = carNavigationBarController;
        mKeygaurdCancelClickedListenerSet = new HashSet<>();
    }

    @Override
    protected void updateNavigationBarVisibility(boolean navBarVisible) {
        if (!mShouldHideNavBar) {
            return;
        }
        int visibility = navBarVisible ? View.VISIBLE : View.GONE;
        mCarNavigationBarController.setBottomWindowVisibility(visibility);
        mCarNavigationBarController.setLeftWindowVisibility(visibility);
        mCarNavigationBarController.setRightWindowVisibility(visibility);
    }

    /**
     * Car is a multi-user system.  There's a cancel button on the bouncer that allows the user to
     * go back to the user switcher and select another user.  Different user may have different
     * security mode which requires bouncer container to be resized.  For this reason, the bouncer
     * view is destroyed on cancel.
     */
    @Override
    protected boolean shouldDestroyViewOnReset() {
        return true;
    }

    /**
     * Called when cancel button in bouncer is pressed.
     */
    @Override
    public void onCancelClicked() {
        mKeygaurdCancelClickedListenerSet.forEach(OnKeyguardCancelClickedListener::onCancelClicked);
    }

    /**
     * Do nothing on this change.
     * The base class hides the keyguard which for automotive we want to avoid b/c this would happen
     * on a configuration change due to day/night (headlight state).
     */
    @Override
    public void onDensityOrFontScaleChanged() {  }

    /**
     * Add listener for keyguard cancel clicked.
     */
    public void addOnKeyguardCancelClickedListener(
            OnKeyguardCancelClickedListener keyguardCancelClickedListener) {
        mKeygaurdCancelClickedListenerSet.add(keyguardCancelClickedListener);
    }

    /**
     * Remove listener for keyguard cancel clicked.
     */
    public void removeOnKeyguardCancelClickedListener(
            OnKeyguardCancelClickedListener keyguardCancelClickedListener) {
        mKeygaurdCancelClickedListenerSet.remove(keyguardCancelClickedListener);
    }


    /**
     * Defines a callback for keyguard cancel button clicked listeners.
     */
    public interface OnKeyguardCancelClickedListener {
        /**
         * Called when keyguard cancel button is clicked.
         */
        void onCancelClicked();
    }
}
