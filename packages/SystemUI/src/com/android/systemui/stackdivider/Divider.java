/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import static com.android.systemui.shared.system.WindowManagerWrapper.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;

import android.app.ActivityManager;
import android.content.Context;
import android.window.WindowContainerToken;

import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Controls the docked stack divider.
 */
@SysUISingleton
public class Divider extends SystemUI {
    private final KeyguardStateController mKeyguardStateController;
    private final DividerController mDividerController;

    Divider(Context context, DividerController dividerController,
            KeyguardStateController keyguardStateController) {
        super(context);
        mDividerController = dividerController;
        mKeyguardStateController = keyguardStateController;
    }

    @Override
    public void start() {
        mDividerController.start();
        // Hide the divider when keyguard is showing. Even though keyguard/statusbar is above
        // everything, it is actually transparent except for notifications, so we still need to
        // hide any surfaces that are below it.
        // TODO(b/148906453): Figure out keyguard dismiss animation for divider view.
        mKeyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardShowingChanged() {
                mDividerController.onKeyguardShowingChanged(mKeyguardStateController.isShowing());
            }
        });
        // Don't initialize the divider or anything until we get the default display.

        ActivityManagerWrapper.getInstance().registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                            boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                        if (!wasVisible || task.configuration.windowConfiguration.getWindowingMode()
                                != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                                || !mDividerController.isSplitScreenSupported()) {
                            return;
                        }

                        if (mDividerController.isMinimized()) {
                            onUndockingTask();
                        }
                    }

                    @Override
                    public void onActivityForcedResizable(String packageName, int taskId,
                            int reason) {
                        mDividerController.onActivityForcedResizable(packageName, taskId, reason);
                    }

                    @Override
                    public void onActivityDismissingDockedStack() {
                        mDividerController.onActivityDismissingSplitScreen();
                    }

                    @Override
                    public void onActivityLaunchOnSecondaryDisplayFailed() {
                        mDividerController.onActivityLaunchOnSecondaryDisplayFailed();
                    }
                }
        );
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mDividerController.dump(pw);
    }

    /** Switch to minimized state if appropriate. */
    public void setMinimized(final boolean minimized) {
        mDividerController.setMinimized(minimized);
    }

    public boolean isMinimized() {
        return mDividerController.isMinimized();
    }

    public boolean isHomeStackResizable() {
        return mDividerController.isHomeStackResizable();
    }

    /** Callback for undocking task. */
    public void onUndockingTask() {
        mDividerController.onUndockingTask();
    }

    public void onRecentsDrawn() {
        mDividerController.onRecentsDrawn();
    }

    public void onDockedFirstAnimationFrame() {
        mDividerController.onDockedFirstAnimationFrame();
    }

    public void onDockedTopTask() {
        mDividerController.onDockedTopTask();
    }

    public void onAppTransitionFinished() {
        mDividerController.onAppTransitionFinished();
    }

    public DividerView getView() {
        return mDividerController.getDividerView();
    }

    /** @return the container token for the secondary split root task. */
    public WindowContainerToken getSecondaryRoot() {
        return mDividerController.getSecondaryRoot();
    }

    /** Register a listener that gets called whenever the existence of the divider changes */
    public void registerInSplitScreenListener(Consumer<Boolean> listener) {
        mDividerController.registerInSplitScreenListener(listener);
    }

    /** {@code true} if this is visible */
    public boolean isDividerVisible() {
        return mDividerController.isDividerVisible();
    }
}
