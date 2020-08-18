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

package com.android.systemui.onehanded;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ONE_HANDED_ACTIVE;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.CommandQueue;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Manages and manipulates the one handed states, transitions, and gesture for phones.
 */
@SysUISingleton
public class OneHandedManagerImpl implements OneHandedManager, Dumpable {
    private static final String TAG = "OneHandedManager";
    private static final String ONE_HANDED_MODE_OFFSET_PERCENTAGE =
            "persist.debug.one_handed_offset_percentage";

    private boolean mIsOneHandedEnabled;
    private boolean mIsSwipeToNotificationEnabled;
    private boolean mTaskChangeToExit;
    private float mOffSetFraction;

    private final CommandQueue mCommandQueue;
    private final DisplayController mDisplayController;
    private final OneHandedGestureHandler mGestureHandler;
    private final OneHandedTimeoutHandler mTimeoutHandler;
    private final OneHandedTouchHandler mTouchHandler;
    private final OneHandedTutorialHandler mTutorialHandler;
    private final SysUiState mSysUiFlagContainer;

    private OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;

    /**
     * Handler for system task stack changes, exit when user lunch new task or bring task to front
     */
    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            if (!mIsOneHandedEnabled || !mDisplayAreaOrganizer.isInOneHanded()) {
                return;
            }
            OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
            stopOneHanded();
        }

        @Override
        public void onTaskMovedToFront(int taskId) {
            if (!mIsOneHandedEnabled || !mDisplayAreaOrganizer.isInOneHanded()) {
                return;
            }
            OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
            stopOneHanded();
        }
    };

    /**
     * Handle rotation based on OnDisplayChangingListener callback
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController =
            (display, fromRotation, toRotation, wct) -> {
                if (mDisplayAreaOrganizer != null) {
                    mDisplayAreaOrganizer.onRotateDisplay(fromRotation, toRotation);
                }
            };

    /**
     * Constructor of OneHandedManager
     */
    @Inject
    public OneHandedManagerImpl(Context context,
            CommandQueue commandQueue,
            DisplayController displayController,
            NavigationModeController navigationModeController,
            SysUiState sysUiState) {
        mCommandQueue = commandQueue;
        mDisplayController = displayController;
        mDisplayController.addDisplayChangingController(mRotationController);
        mSysUiFlagContainer = sysUiState;
        mOffSetFraction = SystemProperties.getInt(ONE_HANDED_MODE_OFFSET_PERCENTAGE, 50) / 100.0f;

        mIsOneHandedEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                context.getContentResolver());
        mIsSwipeToNotificationEnabled = OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                context.getContentResolver());
        mTimeoutHandler = OneHandedTimeoutHandler.get();
        mTouchHandler = new OneHandedTouchHandler();
        mTutorialHandler = new OneHandedTutorialHandler(context);
        mDisplayAreaOrganizer = new OneHandedDisplayAreaOrganizer(context, displayController,
                new OneHandedAnimationController(context), mTutorialHandler);
        mGestureHandler = new OneHandedGestureHandler(
                context, displayController, navigationModeController);
        updateOneHandedEnabled();
        setupGestures();
    }

    /**
     * Constructor of OneHandedManager for testing
     */
    // TODO(b/161980408): Should remove extra constructor.
    @VisibleForTesting
    OneHandedManagerImpl(Context context,
            CommandQueue commandQueue,
            DisplayController displayController,
            OneHandedDisplayAreaOrganizer displayAreaOrganizer,
            OneHandedTouchHandler touchHandler,
            OneHandedTutorialHandler tutorialHandler,
            OneHandedGestureHandler gestureHandler,
            SysUiState sysUiState) {
        mCommandQueue = commandQueue;
        mDisplayAreaOrganizer = displayAreaOrganizer;
        mDisplayController = displayController;
        mDisplayController.addDisplayChangingController(mRotationController);
        mSysUiFlagContainer = sysUiState;
        mOffSetFraction = SystemProperties.getInt(ONE_HANDED_MODE_OFFSET_PERCENTAGE, 50) / 100.0f;

        mIsOneHandedEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                context.getContentResolver());
        mIsSwipeToNotificationEnabled = OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                context.getContentResolver());
        mTimeoutHandler = OneHandedTimeoutHandler.get();
        mTouchHandler = touchHandler;
        mTutorialHandler = tutorialHandler;
        mGestureHandler = gestureHandler;
        updateOneHandedEnabled();
        setupGestures();
    }

    /**
     * Set one handed enabled or disabled by OneHanded UI when user update settings
     */
    public void setOneHandedEnabled(boolean enabled) {
        mIsOneHandedEnabled = enabled;
        updateOneHandedEnabled();
    }

    /**
     * Set one handed enabled or disabled by OneHanded UI when user update settings
     */
    public void setTaskChangeToExit(boolean enabled) {
        if (mTaskChangeToExit == enabled) {
            return;
        }
        mTaskChangeToExit = enabled;
        updateOneHandedEnabled();
    }

    /**
     * Sets whether to enable swipe bottom to notification gesture when user update settings.
     */
    public void setSwipeToNotificationEnabled(boolean enabled) {
        mIsSwipeToNotificationEnabled = enabled;
        updateOneHandedEnabled();
    }

    /**
     * Enters one handed mode.
     */
    @Override
    public void startOneHanded() {
        if (!mDisplayAreaOrganizer.isInOneHanded()) {
            final int yOffSet = Math.round(getDisplaySize().y * mOffSetFraction);
            mDisplayAreaOrganizer.scheduleOffset(0, yOffSet);
            mTimeoutHandler.resetTimer();
        }
    }

    /**
     * Exits one handed mode.
     */
    @Override
    public void stopOneHanded() {
        if (mDisplayAreaOrganizer.isInOneHanded()) {
            mDisplayAreaOrganizer.scheduleOffset(0, 0);
            mTimeoutHandler.removeTimer();
        }
    }

    private void setupGestures() {
        mTouchHandler.registerTouchEventListener(
                new OneHandedTouchHandler.OneHandedTouchEventCallback() {
                    @Override
                    public void onStart() {
                        if (mIsOneHandedEnabled) {
                            startOneHanded();
                        }
                    }

                    @Override
                    public void onStop() {
                        if (mIsOneHandedEnabled) {
                            stopOneHanded();
                        }
                    }
                });

        mGestureHandler.setGestureEventListener(
                new OneHandedGestureHandler.OneHandedGestureEventCallback() {
                    @Override
                    public void onStart() {
                        if (mIsOneHandedEnabled) {
                            startOneHanded();
                        } else if (mIsSwipeToNotificationEnabled) {
                            mCommandQueue.handleSystemKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
                        }
                    }

                    @Override
                    public void onStop() {
                        if (mIsOneHandedEnabled) {
                            stopOneHanded();
                        } else if (mIsSwipeToNotificationEnabled) {
                            mCommandQueue.handleSystemKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
                        }
                    }
                });

        mDisplayAreaOrganizer.registerTransitionCallback(new OneHandedTransitionCallback() {
            @Override
            public void onStartFinished(Rect bounds) {
                mSysUiFlagContainer.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                        true).commitUpdate(DEFAULT_DISPLAY);
            }

            @Override
            public void onStopFinished(Rect bounds) {
                mSysUiFlagContainer.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                        false).commitUpdate(DEFAULT_DISPLAY);
            }
        });

        mDisplayAreaOrganizer.registerTransitionCallback(mTouchHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mGestureHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mTutorialHandler);
    }

    /**
     * Query the current display real size from {@link DisplayController}
     *
     * @return {@link DisplayController#getDisplay(int)#getDisplaySize()}
     */
    private Point getDisplaySize() {
        Point displaySize = new Point();
        if (mDisplayController != null && mDisplayController.getDisplay(DEFAULT_DISPLAY) != null) {
            mDisplayController.getDisplay(DEFAULT_DISPLAY).getRealSize(displaySize);
        }
        return displaySize;
    }

    private void updateOneHandedEnabled() {
        if (mDisplayAreaOrganizer.isInOneHanded()) {
            stopOneHanded();
        }
        // TODO Be aware to unregisterOrganizer() after animation finished
        mDisplayAreaOrganizer.unregisterOrganizer();
        if (mIsOneHandedEnabled) {
            mDisplayAreaOrganizer.registerOrganizer(
                    OneHandedDisplayAreaOrganizer.FEATURE_ONE_HANDED);
        }
        ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskStackListener);
        if (mTaskChangeToExit) {
            ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);
        }
        mTouchHandler.onOneHandedEnabled(mIsOneHandedEnabled);
        mGestureHandler.onOneHandedEnabled(mIsOneHandedEnabled || mIsSwipeToNotificationEnabled);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mSysUiFlagContainer=");
        pw.println(mSysUiFlagContainer.getFlags());
        pw.print(innerPrefix + "mOffSetFraction=");
        pw.println(mOffSetFraction);

        if (mDisplayAreaOrganizer != null) {
            mDisplayAreaOrganizer.dump(fd, pw, args);
        }
    }
}
