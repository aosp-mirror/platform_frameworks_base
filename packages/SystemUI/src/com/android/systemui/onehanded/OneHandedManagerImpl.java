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

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.model.SysUiState;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages and manipulates the one handed states, transitions, and gesture for phones.
 */
@Singleton
public class OneHandedManagerImpl implements OneHandedManager, Dumpable {
    private static final String TAG = "OneHandedManager";

    private boolean mIsOneHandedEnabled;
    private boolean mTaskChangeToExit;
    private float mOffSetFraction;

    private final DisplayController mDisplayController;
    private final OneHandedGestureHandler mGestureHandler;
    private final OneHandedTimeoutHandler mTimeoutHandler;
    private final OneHandedTouchHandler mTouchHandler;
    private final SysUiState mSysUiFlagContainer;

    private Context mContext;
    private OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;
    private OneHandedGestureHandler.OneHandedGestureEventCallback mGestureEventCallback;
    private OneHandedTouchHandler.OneHandedTouchEventCallback mTouchEventCallback;
    private OneHandedTransitionCallback mTransitionCallback;

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
    @VisibleForTesting
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
            DisplayController displayController,
            OneHandedDisplayAreaOrganizer displayAreaOrganizer,
            OneHandedTouchHandler touchHandler,
            OneHandedGestureHandler gestureHandler,
            SysUiState sysUiState) {
        mContext = context;
        mDisplayAreaOrganizer = displayAreaOrganizer;
        mDisplayController = displayController;
        mDisplayController.addDisplayChangingController(mRotationController);
        mSysUiFlagContainer = sysUiState;
        mOffSetFraction =
                context.getResources().getFraction(R.fraction.config_one_handed_offset, 1, 1);
        mIsOneHandedEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                context.getContentResolver());
        mTimeoutHandler = OneHandedTimeoutHandler.get();
        mTouchHandler = touchHandler;
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
     * Start one handed mode
     */
    @Override
    public void startOneHanded() {
        if (!mDisplayAreaOrganizer.isInOneHanded() && mIsOneHandedEnabled) {
            final int yOffSet = Math.round(getDisplaySize().y * mOffSetFraction);
            mDisplayAreaOrganizer.scheduleOffset(0, yOffSet);
            mTimeoutHandler.resetTimer();
        }
    }

    /**
     * Stop one handed mode
     */
    @Override
    public void stopOneHanded() {
        if (mDisplayAreaOrganizer.isInOneHanded()) {
            mDisplayAreaOrganizer.scheduleOffset(0, 0);
            mTimeoutHandler.removeTimer();
        }
    }

    private void setupGestures() {
        mTouchEventCallback = new OneHandedTouchHandler.OneHandedTouchEventCallback() {
            @Override
            public boolean onStart() {
                boolean result = false;
                if (!mDisplayAreaOrganizer.isInOneHanded()) {
                    startOneHanded();
                    result = true;
                }
                return result;
            }

            @Override
            public boolean onStop() {
                boolean result = false;
                if (mDisplayAreaOrganizer.isInOneHanded()) {
                    stopOneHanded();
                    result = true;
                }
                return result;
            }
        };
        mTouchHandler.registerTouchEventListener(mTouchEventCallback);

        mGestureEventCallback = new OneHandedGestureHandler.OneHandedGestureEventCallback() {
            @Override
            public boolean onStart() {
                boolean result = false;
                if (!mDisplayAreaOrganizer.isInOneHanded()) {
                    startOneHanded();
                    result = true;
                }
                return result;
            }

            @Override
            public boolean onStop() {
                boolean result = false;
                if (mDisplayAreaOrganizer.isInOneHanded()) {
                    stopOneHanded();
                    result = true;
                }
                return result;
            }
        };
        mGestureHandler.setGestureEventListener(mGestureEventCallback);

        mTransitionCallback = new OneHandedTransitionCallback() {
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
        };
        mDisplayAreaOrganizer.registerTransitionCallback(mTransitionCallback);
        mDisplayAreaOrganizer.registerTransitionCallback(mTouchHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mGestureHandler);
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
        mGestureHandler.onOneHandedEnabled(mIsOneHandedEnabled);
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
