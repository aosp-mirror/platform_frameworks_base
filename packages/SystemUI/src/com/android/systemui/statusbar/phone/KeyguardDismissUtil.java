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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/**
 * Executes actions that require the screen to be unlocked.
 */
@SysUISingleton
public class KeyguardDismissUtil implements KeyguardDismissHandler {
    private final KeyguardStateController mKeyguardStateController;

    private final SysuiStatusBarStateController mStatusBarStateController;

    private final ActivityStarter mActivityStarter;

    @Inject
    public KeyguardDismissUtil(KeyguardStateController keyguardStateController,
            SysuiStatusBarStateController statusBarStateController,
            ActivityStarter activityStarter) {
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mActivityStarter = activityStarter;
    }

    @Override
    public void executeWhenUnlocked(OnDismissAction action, boolean requiresShadeOpen,
            boolean afterKeyguardGone) {
        if (mKeyguardStateController.isShowing() && requiresShadeOpen) {
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        }
        mActivityStarter.dismissKeyguardThenExecute(action, null /* cancelAction */,
                afterKeyguardGone /* afterKeyguardGone */);
    }
}
