/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.content.Context;
import android.view.View;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

public class CarStatusBarKeyguardViewManager extends StatusBarKeyguardViewManager {

    protected boolean mShouldHideNavBar;

    public CarStatusBarKeyguardViewManager(Context context,
            ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        super(context, callback, lockPatternUtils);
        mShouldHideNavBar = context.getResources()
                .getBoolean(R.bool.config_hideNavWhenKeyguardBouncerShown);
    }

    @Override
    protected void updateNavigationBarVisibility(boolean navBarVisible) {
        if (!mShouldHideNavBar) {
            return;
        }
        CarStatusBar statusBar = (CarStatusBar) mStatusBar;
        statusBar.setNavBarVisibility(navBarVisible ? View.VISIBLE : View.GONE);
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
        CarStatusBar statusBar = (CarStatusBar) mStatusBar;
        statusBar.showUserSwitcher();
    }
}
