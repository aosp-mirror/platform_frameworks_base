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

package com.android.wm.shell.onehanded;

import static android.os.UserHandle.USER_CURRENT;
import static android.view.Display.DEFAULT_DISPLAY;

import android.content.ComponentName;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.database.ContentObserver;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.onehanded.OneHandedGestureHandler.OneHandedGestureEventCallback;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * Manages and manipulates the one handed states, transitions, and gesture for phones.
 */
public class OneHandedController implements OneHanded {
    private static final String TAG = "OneHandedController";

    private static final String ONE_HANDED_MODE_OFFSET_PERCENTAGE =
            "persist.debug.one_handed_offset_percentage";
    private static final String ONE_HANDED_MODE_GESTURAL_OVERLAY =
            "com.android.internal.systemui.onehanded.gestural";

    static final String SUPPORT_ONE_HANDED_MODE = "ro.support_one_handed_mode";

    private boolean mIsOneHandedEnabled;
    private boolean mIsSwipeToNotificationEnabled;
    private boolean mTaskChangeToExit;
    private float mOffSetFraction;

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final OneHandedGestureHandler mGestureHandler;
    private final OneHandedTimeoutHandler mTimeoutHandler;
    private final OneHandedTouchHandler mTouchHandler;
    private final OneHandedTutorialHandler mTutorialHandler;
    private final IOverlayManager mOverlayManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;

    /**
     * Handle rotation based on OnDisplayChangingListener callback
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController =
            (display, fromRotation, toRotation, wct) -> {
                if (mDisplayAreaOrganizer != null) {
                    mDisplayAreaOrganizer.onRotateDisplay(fromRotation, toRotation, wct);
                }
            };

    private final ContentObserver mEnabledObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean enabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                    mContext.getContentResolver());
            OneHandedEvents.writeEvent(enabled
                    ? OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_ENABLED_ON
                    : OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_ENABLED_OFF);

            setOneHandedEnabled(enabled);

            // Also checks swipe to notification settings since they all need gesture overlay.
            setEnabledGesturalOverlay(
                    enabled || OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                            mContext.getContentResolver()));
        }
    };

    private final ContentObserver mTimeoutObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final int newTimeout = OneHandedSettingsUtil.getSettingsOneHandedModeTimeout(
                    mContext.getContentResolver());
            int metricsId = OneHandedEvents.OneHandedSettingsTogglesEvent.INVALID.getId();
            switch (newTimeout) {
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_NEVER:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_NEVER;
                    break;
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_4;
                    break;
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_8;
                    break;
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_LONG_IN_SECONDS:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_12;
                    break;
                default:
                    // do nothing
                    break;
            }
            OneHandedEvents.writeEvent(metricsId);

            if (mTimeoutHandler != null) {
                mTimeoutHandler.setTimeout(newTimeout);
            }
        }
    };

    private final ContentObserver mTaskChangeExitObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean enabled = OneHandedSettingsUtil.getSettingsTapsAppToExit(
                    mContext.getContentResolver());
            OneHandedEvents.writeEvent(enabled
                    ? OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_ON
                    : OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_OFF);

            setTaskChangeToExit(enabled);
        }
    };

    private final ContentObserver mSwipeToNotificationEnabledObserver =
            new ContentObserver(mMainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    final boolean enabled =
                            OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                                    mContext.getContentResolver());
                    setSwipeToNotificationEnabled(enabled);

                    // Also checks one handed mode settings since they all need gesture overlay.
                    setEnabledGesturalOverlay(
                            enabled || OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                                    mContext.getContentResolver()));
                }
            };

    /**
     * Creates {@link OneHandedController}, returns {@code null} if the feature is not supported.
     */
    @Nullable
    public static OneHandedController create(
            Context context, DisplayController displayController,
            TaskStackListenerImpl taskStackListener, Executor executor) {
        if (!SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false)) {
            Slog.w(TAG, "Device doesn't support OneHanded feature");
            return null;
        }

        OneHandedTutorialHandler tutorialHandler = new OneHandedTutorialHandler(context);
        OneHandedAnimationController animationController =
                new OneHandedAnimationController(context);
        OneHandedTouchHandler touchHandler = new OneHandedTouchHandler();
        OneHandedGestureHandler gestureHandler = new OneHandedGestureHandler(
                context, displayController);
        OneHandedDisplayAreaOrganizer organizer = new OneHandedDisplayAreaOrganizer(
                context, displayController, animationController, tutorialHandler, executor);
        IOverlayManager overlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        return new OneHandedController(context, displayController, organizer, touchHandler,
                tutorialHandler, gestureHandler, overlayManager, taskStackListener);
    }

    @VisibleForTesting
    OneHandedController(Context context,
            DisplayController displayController,
            OneHandedDisplayAreaOrganizer displayAreaOrganizer,
            OneHandedTouchHandler touchHandler,
            OneHandedTutorialHandler tutorialHandler,
            OneHandedGestureHandler gestureHandler,
            IOverlayManager overlayManager,
            TaskStackListenerImpl taskStackListener) {
        mContext = context;
        mDisplayAreaOrganizer = displayAreaOrganizer;
        mDisplayController = displayController;
        mTouchHandler = touchHandler;
        mTutorialHandler = tutorialHandler;
        mGestureHandler = gestureHandler;
        mOverlayManager = overlayManager;

        final float offsetPercentageConfig = context.getResources().getFraction(
                R.fraction.config_one_handed_offset, 1, 1);
        final int sysPropPercentageConfig = SystemProperties.getInt(
                ONE_HANDED_MODE_OFFSET_PERCENTAGE, Math.round(offsetPercentageConfig * 100.0f));
        mOffSetFraction = sysPropPercentageConfig / 100.0f;
        mIsOneHandedEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                context.getContentResolver());
        mIsSwipeToNotificationEnabled =
                OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                        context.getContentResolver());
        mTimeoutHandler = OneHandedTimeoutHandler.get();

        mDisplayController.addDisplayChangingController(mRotationController);

        setupCallback();
        setupSettingObservers();
        setupTimeoutListener();
        setupGesturalOverlay();
        updateSettings();

        taskStackListener.addListener(
                new TaskStackListenerCallback() {
                    @Override
                    public void onTaskCreated(int taskId, ComponentName componentName) {
                        stopOneHanded(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
                    }

                    @Override
                    public void onTaskMovedToFront(int taskId) {
                        stopOneHanded(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
                    }
                });
    }

    /**
     * Set one handed enabled or disabled when user update settings
     */
    void setOneHandedEnabled(boolean enabled) {
        mIsOneHandedEnabled = enabled;
        updateOneHandedEnabled();
    }

    /**
     * Set one handed enabled or disabled by when user update settings
     */
    void setTaskChangeToExit(boolean enabled) {
        mTaskChangeToExit = enabled;
    }

    /**
     * Sets whether to enable swipe bottom to notification gesture when user update settings.
     */
    void setSwipeToNotificationEnabled(boolean enabled) {
        mIsSwipeToNotificationEnabled = enabled;
        updateOneHandedEnabled();
    }

    @Override
    public boolean isOneHandedEnabled() {
        return mIsOneHandedEnabled;
    }

    @Override
    public boolean isSwipeToNotificationEnabled() {
        return mIsSwipeToNotificationEnabled;
    }

    @Override
    public void startOneHanded() {
        if (!mDisplayAreaOrganizer.isInOneHanded()) {
            final int yOffSet = Math.round(getDisplaySize().y * mOffSetFraction);
            mDisplayAreaOrganizer.scheduleOffset(0, yOffSet);
            mTimeoutHandler.resetTimer();

            OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_GESTURE_IN);
        }
    }

    @Override
    public void stopOneHanded() {
        if (mDisplayAreaOrganizer.isInOneHanded()) {
            mDisplayAreaOrganizer.scheduleOffset(0, 0);
            mTimeoutHandler.removeTimer();
        }
    }

    @Override
    public void stopOneHanded(int event) {
        if (!mTaskChangeToExit && event == OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT) {
            //Task change exit not enable, do nothing and return here.
            return;
        }

        if (mDisplayAreaOrganizer.isInOneHanded()) {
            OneHandedEvents.writeEvent(event);
        }

        stopOneHanded();
    }

    @Override
    public void setThreeButtonModeEnabled(boolean enabled) {
        mGestureHandler.onThreeButtonModeEnabled(enabled);
    }

    @Override
    public void registerTransitionCallback(OneHandedTransitionCallback callback) {
        mDisplayAreaOrganizer.registerTransitionCallback(callback);
    }

    @Override
    public void registerGestureCallback(OneHandedGestureEventCallback callback) {
        mGestureHandler.setGestureEventListener(callback);
    }

    private void setupCallback() {
        mTouchHandler.registerTouchEventListener(() ->
                stopOneHanded(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_OVERSPACE_OUT));
        mDisplayAreaOrganizer.registerTransitionCallback(mTouchHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mGestureHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mTutorialHandler);
    }

    private void setupSettingObservers() {
        OneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_ENABLED,
                mContext.getContentResolver(), mEnabledObserver);
        OneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                mContext.getContentResolver(), mTimeoutObserver);
        OneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.TAPS_APP_TO_EXIT,
                mContext.getContentResolver(), mTaskChangeExitObserver);
        OneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED,
                mContext.getContentResolver(), mSwipeToNotificationEnabledObserver);
    }

    private void updateSettings() {
        setOneHandedEnabled(OneHandedSettingsUtil
                .getSettingsOneHandedModeEnabled(mContext.getContentResolver()));
        mTimeoutHandler.setTimeout(OneHandedSettingsUtil
                .getSettingsOneHandedModeTimeout(mContext.getContentResolver()));
        setTaskChangeToExit(OneHandedSettingsUtil
                .getSettingsTapsAppToExit(mContext.getContentResolver()));
        setSwipeToNotificationEnabled(OneHandedSettingsUtil
                .getSettingsSwipeToNotificationEnabled(mContext.getContentResolver()));
    }

    private void setupTimeoutListener() {
        mTimeoutHandler.registerTimeoutListener(timeoutTime -> {
            stopOneHanded(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_TIMEOUT_OUT);
        });
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
        mTouchHandler.onOneHandedEnabled(mIsOneHandedEnabled);
        mGestureHandler.onOneHandedEnabled(mIsOneHandedEnabled || mIsSwipeToNotificationEnabled);
    }

    private void setupGesturalOverlay() {
        if (!OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(mContext.getContentResolver())) {
            return;
        }

        OverlayInfo info = null;
        try {
            // TODO(b/157958539) migrate new RRO config file after S+
            mOverlayManager.setHighestPriority(ONE_HANDED_MODE_GESTURAL_OVERLAY, USER_CURRENT);
            info = mOverlayManager.getOverlayInfo(ONE_HANDED_MODE_GESTURAL_OVERLAY, USER_CURRENT);
        } catch (RemoteException e) { /* Do nothing */ }

        if (info != null && !info.isEnabled()) {
            // Enable the default gestural one handed overlay.
            setEnabledGesturalOverlay(true);
        }
    }

    @VisibleForTesting
    private void setEnabledGesturalOverlay(boolean enabled) {
        try {
            mOverlayManager.setEnabled(ONE_HANDED_MODE_GESTURAL_OVERLAY, enabled, USER_CURRENT);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mOffSetFraction=");
        pw.println(mOffSetFraction);

        if (mDisplayAreaOrganizer != null) {
            mDisplayAreaOrganizer.dump(pw);
        }

        if (mTouchHandler != null) {
            mTouchHandler.dump(pw);
        }

        if (mTimeoutHandler != null) {
            mTimeoutHandler.dump(pw);
        }

        if (mTutorialHandler != null) {
            mTutorialHandler.dump(pw);
        }

        OneHandedSettingsUtil.dump(pw, innerPrefix, mContext.getContentResolver());

        if (mOverlayManager != null) {
            OverlayInfo info = null;
            try {
                info = mOverlayManager.getOverlayInfo(ONE_HANDED_MODE_GESTURAL_OVERLAY,
                        USER_CURRENT);
            } catch (RemoteException e) { /* Do nothing */ }

            if (info != null && !info.isEnabled()) {
                pw.print(innerPrefix + "OverlayInfo=");
                pw.println(info);
            }
        }
    }
}
