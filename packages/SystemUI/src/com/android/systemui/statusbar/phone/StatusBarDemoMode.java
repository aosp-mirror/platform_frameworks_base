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

import android.annotation.NonNull;
import android.os.Bundle;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** */
@StatusBarComponent.StatusBarScope
public class StatusBarDemoMode implements DemoMode {
    private final StatusBar mStatusBar;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    private final NavigationBarController mNavigationBarController;
    private final int mDisplayId;

    @Inject
    StatusBarDemoMode(
            StatusBar statusBar,
            NotificationShadeWindowController notificationShadeWindowController,
            NotificationShadeWindowViewController notificationShadeWindowViewController,
            NavigationBarController navigationBarController,
            @DisplayId int displayId) {
        mStatusBar = statusBar;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mNotificationShadeWindowViewController = notificationShadeWindowViewController;
        mNavigationBarController = navigationBarController;
        mDisplayId = displayId;
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
        dispatchDemoModeStartedToView(R.id.clock);
        dispatchDemoModeStartedToView(R.id.operator_name);
    }

    @Override
    public void onDemoModeFinished() {
        dispatchDemoModeFinishedToView(R.id.clock);
        dispatchDemoModeFinishedToView(R.id.operator_name);
        mStatusBar.checkBarModes();
    }

    @Override
    public void dispatchDemoCommand(String command, @NonNull Bundle args) {
        if (command.equals(COMMAND_CLOCK)) {
            dispatchDemoCommandToView(command, args, R.id.clock);
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
                if (mNotificationShadeWindowController != null
                        && mNotificationShadeWindowViewController.getBarTransitions() != null) {
                    mNotificationShadeWindowViewController.getBarTransitions().transitionTo(
                            barMode, animate);
                }
                mNavigationBarController.transitionTo(mDisplayId, barMode, animate);
            }
        }
        if (command.equals(COMMAND_OPERATOR)) {
            dispatchDemoCommandToView(command, args, R.id.operator_name);
        }
    }

    private void dispatchDemoModeStartedToView(int id) {
        View statusBarView = mStatusBar.getStatusBarView();
        if (statusBarView == null) return;
        View v = statusBarView.findViewById(id);
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).onDemoModeStarted();
        }
    }

    //TODO: these should have controllers, and this method should be removed
    private void dispatchDemoCommandToView(String command, Bundle args, int id) {
        View statusBarView = mStatusBar.getStatusBarView();
        if (statusBarView == null) return;
        View v = statusBarView.findViewById(id);
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).dispatchDemoCommand(command, args);
        }
    }

    private void dispatchDemoModeFinishedToView(int id) {
        View statusBarView = mStatusBar.getStatusBarView();
        if (statusBarView == null) return;
        View v = statusBarView.findViewById(id);
        if (v instanceof DemoModeCommandReceiver) {
            ((DemoModeCommandReceiver) v).onDemoModeFinished();
        }
    }
}
