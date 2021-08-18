/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BACK_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;

import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.view.InsetsVisibilities;
import android.view.View;

import com.android.internal.view.AppearanceRegion;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.statusbar.CommandQueue;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TaskbarDelegate implements CommandQueue.Callbacks {

    private OverviewProxyService mOverviewProxyService;
    private NavigationBarA11yHelper mNavigationBarA11yHelper;
    private SysUiState mSysUiState;
    private int mDisplayId;
    private int mNavigationIconHints;
    private final NavigationBarA11yHelper.NavA11yEventListener mNavA11yEventListener =
            this::updateSysuiFlags;
    private int mDisabledFlags;

    @Inject
    public TaskbarDelegate() { /* no-op */ }

    public void setOverviewProxyService(OverviewProxyService overviewProxyService,
            NavigationBarA11yHelper navigationBarA11yHelper,
            SysUiState sysUiState) {
        // TODO: adding this in the ctor results in a dagger dependency cycle :(
        mOverviewProxyService = overviewProxyService;
        mNavigationBarA11yHelper = navigationBarA11yHelper;
        mSysUiState = sysUiState;
    }

    public void destroy() {
        mNavigationBarA11yHelper.removeA11yEventListener(mNavA11yEventListener);
    }

    public void init(int displayId) {
        mDisplayId = displayId;
        mNavigationBarA11yHelper.registerA11yEventListener(mNavA11yEventListener);
        // Set initial state for any listeners
        updateSysuiFlags();
    }

    private void updateSysuiFlags() {
        int a11yFlags = mNavigationBarA11yHelper.getA11yButtonState();
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiState.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_IME_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_BACK_ALT) != 0)
                .setFlag(SYSUI_STATE_IME_SWITCHER_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_IME_SHOWN) != 0)
                .setFlag(SYSUI_STATE_OVERVIEW_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                .setFlag(SYSUI_STATE_HOME_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                .setFlag(SYSUI_STATE_BACK_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                .commitUpdate(mDisplayId);
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
        int hints = Utilities.calculateBackDispositionHints(mNavigationIconHints, backDisposition,
                imeShown, showImeSwitcher);
        if (hints != mNavigationIconHints) {
            mNavigationIconHints = hints;
            updateSysuiFlags();
        }
    }

    @Override
    public void onRotationProposal(int rotation, boolean isValid) {
        mOverviewProxyService.onRotationProposal(rotation, isValid);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        mDisabledFlags = state1;
        updateSysuiFlags();
        mOverviewProxyService.disable(displayId, state1, state2, animate);
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme, int behavior,
            InsetsVisibilities requestedVisibilities, String packageName) {
        mOverviewProxyService.onSystemBarAttributesChanged(displayId, behavior);
    }
}
