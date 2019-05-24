/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist;

import android.app.StatusBarManager;
import android.content.Context;

import androidx.annotation.Nullable;

import com.android.systemui.Dependency;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.AssistHandleBehaviorController.BehaviorController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.phone.NavigationBarFragment;

/**
 * Assistant Handle behavior that makes Assistant handles show/hide when the home handle is
 * shown/hidden, respectively.
 */
final class AssistHandleLikeHomeBehavior implements BehaviorController {

    private final CommandQueue.Callbacks mCallbacks = new CommandQueue.Callbacks() {
        @Override
        public void setWindowState(int displayId, int window, int state) {
            if (mNavBarDisplayId == displayId
                    && window == StatusBarManager.WINDOW_NAVIGATION_BAR) {
                handleWindowStateChanged(state);
            }
        }
    };

    private CommandQueue mCommandQueue;
    private int mNavBarDisplayId;
    private boolean mIsNavBarWindowVisible;

    @Nullable private AssistHandleCallbacks mAssistHandleCallbacks;

    @Override
    public void onModeActivated(Context context, AssistHandleCallbacks callbacks) {
        mAssistHandleCallbacks = callbacks;
        NavigationBarFragment navigationBarFragment =
                Dependency.get(NavigationBarController.class).getDefaultNavigationBarFragment();
        mNavBarDisplayId = navigationBarFragment.mDisplayId;
        mIsNavBarWindowVisible = navigationBarFragment.isNavBarWindowVisible();
        mCommandQueue = SysUiServiceProvider.getComponent(context, CommandQueue.class);
        mCommandQueue.addCallback(mCallbacks);
        callbackForCurrentState();
    }

    @Override
    public void onModeDeactivated() {
        mAssistHandleCallbacks = null;
        mCommandQueue.removeCallback(mCallbacks);
    }

    private void handleWindowStateChanged(int state) {
        boolean newVisibility = state == StatusBarManager.WINDOW_STATE_SHOWING;
        if (mIsNavBarWindowVisible == newVisibility) {
            return;
        }

        mIsNavBarWindowVisible = newVisibility;
        callbackForCurrentState();
    }

    private void callbackForCurrentState() {
        if (mAssistHandleCallbacks == null) {
            return;
        }

        if (mIsNavBarWindowVisible) {
            mAssistHandleCallbacks.showAndStay();
        } else {
            mAssistHandleCallbacks.hide();
        }
    }
}
