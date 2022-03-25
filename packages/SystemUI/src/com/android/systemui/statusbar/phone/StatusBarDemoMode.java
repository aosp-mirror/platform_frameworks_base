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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_WARNING;
import static com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentModule.OPERATOR_NAME_VIEW;

import android.annotation.NonNull;
import android.os.Bundle;
import android.view.View;

import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentScope;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A controller that updates status-bar-related views during demo mode.
 *
 * This class extends ViewController not because it controls a specific view, but because we want it
 * to get torn down and re-created in line with the view's lifecycle.
 */
@StatusBarFragmentScope
public class StatusBarDemoMode extends ViewController<View> implements DemoMode {
    private final Clock mClockView;
    private final View mOperatorNameView;
    private final DemoModeController mDemoModeController;
    private final PhoneStatusBarTransitions mPhoneStatusBarTransitions;
    private final NavigationBarController mNavigationBarController;
    private final int mDisplayId;

    @Inject
    StatusBarDemoMode(
            Clock clockView,
            @Named(OPERATOR_NAME_VIEW) View operatorNameView,
            DemoModeController demoModeController,
            PhoneStatusBarTransitions phoneStatusBarTransitions,
            NavigationBarController navigationBarController,
            @DisplayId int displayId) {
        super(clockView);
        mClockView = clockView;
        mOperatorNameView = operatorNameView;
        mDemoModeController = demoModeController;
        mPhoneStatusBarTransitions = phoneStatusBarTransitions;
        mNavigationBarController = navigationBarController;
        mDisplayId = displayId;
    }

    @Override
    protected void onViewAttached() {
        mDemoModeController.addCallback(this);
    }

    @Override
    protected void onViewDetached() {
        mDemoModeController.removeCallback(this);
    }

    @Override
    public List<String> demoCommands() {
        List<String> s = new ArrayList<>();
        s.add(DemoMode.COMMAND_BARS);
        s.add(DemoMode.COMMAND_CLOCK);
        s.add(DemoMode.COMMAND_OPERATOR);
        return s;
    }

    @Override
    public void onDemoModeStarted() {
        // Must send this message to any view that we delegate to via dispatchDemoCommandToView
        dispatchDemoModeStartedToView(mClockView);
        dispatchDemoModeStartedToView(mOperatorNameView);
    }

    @Override
    public void onDemoModeFinished() {
        dispatchDemoModeFinishedToView(mClockView);
        dispatchDemoModeFinishedToView(mOperatorNameView);
    }

    @Override
    public void dispatchDemoCommand(String command, @NonNull Bundle args) {
        if (command.equals(COMMAND_CLOCK)) {
            dispatchDemoCommandToView(command, args, mClockView);
        }
        if (command.equals(COMMAND_OPERATOR)) {
            dispatchDemoCommandToView(command, args, mOperatorNameView);
        }
        if (command.equals(COMMAND_BARS)) {
            String mode = args.getString("mode");
            int barMode = "opaque".equals(mode) ? MODE_OPAQUE :
                    "translucent".equals(mode) ? MODE_TRANSLUCENT :
                            "semi-transparent".equals(mode) ? MODE_SEMI_TRANSPARENT :
                                    "transparent".equals(mode) ? MODE_TRANSPARENT :
                                            "warning".equals(mode) ? MODE_WARNING :
                                                    -1;
            if (barMode != -1) {
                boolean animate = true;
                mPhoneStatusBarTransitions.transitionTo(barMode, animate);
                mNavigationBarController.transitionTo(mDisplayId, barMode, animate);
            }
        }
    }

    private void dispatchDemoModeStartedToView(View v) {
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).onDemoModeStarted();
        }
    }

    private void dispatchDemoCommandToView(String command, Bundle args, View v) {
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).dispatchDemoCommand(command, args);
        }
    }

    private void dispatchDemoModeFinishedToView(View v) {
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).onDemoModeFinished();
        }
    }
}
