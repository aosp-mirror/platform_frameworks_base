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

import android.content.ComponentName;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEventLogger;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.onehanded.OneHandedGestureHandler.OneHandedGestureEventCallback;

import java.io.PrintWriter;

/**
 * Manages and manipulates the one handed states, transitions, and gesture for phones.
 */
public class OneHandedController {
    private static final String TAG = "OneHandedController";

    private static final String ONE_HANDED_MODE_OFFSET_PERCENTAGE =
            "persist.debug.one_handed_offset_percentage";
    private static final String ONE_HANDED_MODE_GESTURAL_OVERLAY =
            "com.android.internal.systemui.onehanded.gestural";

    static final String SUPPORT_ONE_HANDED_MODE = "ro.support_one_handed_mode";

    private volatile boolean mIsOneHandedEnabled;
    private volatile boolean mIsSwipeToNotificationEnabled;
    private boolean mTaskChangeToExit;
    private boolean mLockedDisabled;
    private float mOffSetFraction;

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final OneHandedGestureHandler mGestureHandler;
    private final OneHandedTimeoutHandler mTimeoutHandler;
    private final OneHandedTouchHandler mTouchHandler;
    private final OneHandedTutorialHandler mTutorialHandler;
    private final OneHandedUiEventLogger mOneHandedUiEventLogger;
    private final TaskStackListenerImpl mTaskStackListener;
    private final IOverlayManager mOverlayManager;
    private final ShellExecutor mMainExecutor;
    private final Handler mMainHandler;
    private final OneHandedImpl mImpl = new OneHandedImpl();
    private final WindowManager mWindowManager;

    private OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;
    private final AccessibilityManager mAccessibilityManager;
    private OneHandedBackgroundPanelOrganizer mBackgroundPanelOrganizer;

    /**
     * Handle rotation based on OnDisplayChangingListener callback
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController =
            (display, fromRotation, toRotation, wct) -> {
                if (mDisplayAreaOrganizer != null) {
                    mDisplayAreaOrganizer.onRotateDisplay(fromRotation, toRotation, wct);
                }
            };

    private final ContentObserver mEnabledObserver;
    private final ContentObserver mTimeoutObserver;
    private final ContentObserver mTaskChangeExitObserver;
    private final ContentObserver mSwipeToNotificationEnabledObserver;

    private AccessibilityManager.AccessibilityStateChangeListener
            mAccessibilityStateChangeListener =
            new AccessibilityManager.AccessibilityStateChangeListener() {
                @Override
                public void onAccessibilityStateChanged(boolean enabled) {
                    if (enabled) {
                        final int mOneHandedTimeout = OneHandedSettingsUtil
                                .getSettingsOneHandedModeTimeout(mContext.getContentResolver());
                        final int timeout = mAccessibilityManager
                                .getRecommendedTimeoutMillis(mOneHandedTimeout * 1000
                                        /* align with A11y timeout millis */,
                                        AccessibilityManager.FLAG_CONTENT_CONTROLS);
                        mTimeoutHandler.setTimeout(timeout / 1000);
                    } else {
                        mTimeoutHandler.setTimeout(OneHandedSettingsUtil
                                .getSettingsOneHandedModeTimeout(mContext.getContentResolver()));
                    }
                }
            };

    private final TaskStackListenerCallback mTaskStackListenerCallback =
            new TaskStackListenerCallback() {
                @Override
                public void onTaskCreated(int taskId, ComponentName componentName) {
                    stopOneHanded(OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
                }

                @Override
                public void onTaskMovedToFront(int taskId) {
                    stopOneHanded(OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
                }
            };


    /**
     * Creates {@link OneHandedController}, returns {@code null} if the feature is not supported.
     */
    @Nullable
    public static OneHandedController create(
            Context context, WindowManager windowManager, DisplayController displayController,
            TaskStackListenerImpl taskStackListener, UiEventLogger uiEventLogger,
            ShellExecutor mainExecutor, Handler mainHandler) {
        if (!SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false)) {
            Slog.w(TAG, "Device doesn't support OneHanded feature");
            return null;
        }

        OneHandedTimeoutHandler timeoutHandler = new OneHandedTimeoutHandler(mainExecutor);
        OneHandedTutorialHandler tutorialHandler = new OneHandedTutorialHandler(context,
                windowManager, mainExecutor);
        OneHandedAnimationController animationController =
                new OneHandedAnimationController(context);
        OneHandedTouchHandler touchHandler = new OneHandedTouchHandler(timeoutHandler,
                mainExecutor);
        OneHandedGestureHandler gestureHandler = new OneHandedGestureHandler(
                context, windowManager, displayController, ViewConfiguration.get(context),
                mainExecutor);
        OneHandedBackgroundPanelOrganizer oneHandedBackgroundPanelOrganizer =
                new OneHandedBackgroundPanelOrganizer(context, windowManager, displayController,
                        mainExecutor);
        OneHandedDisplayAreaOrganizer organizer = new OneHandedDisplayAreaOrganizer(
                context, windowManager, displayController, animationController, tutorialHandler,
                oneHandedBackgroundPanelOrganizer, mainExecutor);
        OneHandedUiEventLogger oneHandedUiEventsLogger = new OneHandedUiEventLogger(uiEventLogger);
        IOverlayManager overlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        return new OneHandedController(context, windowManager, displayController,
                oneHandedBackgroundPanelOrganizer, organizer, touchHandler, tutorialHandler,
                gestureHandler, timeoutHandler, oneHandedUiEventsLogger, overlayManager,
                taskStackListener, mainExecutor, mainHandler);
    }

    @VisibleForTesting
    OneHandedController(Context context,
            WindowManager windowManager,
            DisplayController displayController,
            OneHandedBackgroundPanelOrganizer backgroundPanelOrganizer,
            OneHandedDisplayAreaOrganizer displayAreaOrganizer,
            OneHandedTouchHandler touchHandler,
            OneHandedTutorialHandler tutorialHandler,
            OneHandedGestureHandler gestureHandler,
            OneHandedTimeoutHandler timeoutHandler,
            OneHandedUiEventLogger uiEventsLogger,
            IOverlayManager overlayManager,
            TaskStackListenerImpl taskStackListener,
            ShellExecutor mainExecutor,
            Handler mainHandler) {
        mContext = context;
        mWindowManager = windowManager;
        mBackgroundPanelOrganizer = backgroundPanelOrganizer;
        mDisplayAreaOrganizer = displayAreaOrganizer;
        mDisplayController = displayController;
        mTouchHandler = touchHandler;
        mTutorialHandler = tutorialHandler;
        mGestureHandler = gestureHandler;
        mOverlayManager = overlayManager;
        mMainExecutor = mainExecutor;
        mMainHandler = mainHandler;
        mOneHandedUiEventLogger = uiEventsLogger;
        mTaskStackListener = taskStackListener;

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
        mTimeoutHandler = timeoutHandler;

        mEnabledObserver = getObserver(this::onEnabledSettingChanged);
        mTimeoutObserver = getObserver(this::onTimeoutSettingChanged);
        mTaskChangeExitObserver = getObserver(this::onTaskChangeExitSettingChanged);
        mSwipeToNotificationEnabledObserver =
                getObserver(this::onSwipeToNotificationEnabledSettingChanged);

        mDisplayController.addDisplayChangingController(mRotationController);

        setupCallback();
        setupSettingObservers();
        setupTimeoutListener();
        setupGesturalOverlay();
        updateSettings();

        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mAccessibilityManager.addAccessibilityStateChangeListener(
                mAccessibilityStateChangeListener);
    }

    public OneHanded asOneHanded() {
        return mImpl;
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
        if (enabled) {
            mTaskStackListener.addListener(mTaskStackListenerCallback);
        } else {
            mTaskStackListener.removeListener(mTaskStackListenerCallback);
        }
        mTaskChangeToExit = enabled;
    }

    /**
     * Sets whether to enable swipe bottom to notification gesture when user update settings.
     */
    void setSwipeToNotificationEnabled(boolean enabled) {
        mIsSwipeToNotificationEnabled = enabled;
        updateOneHandedEnabled();
    }

    @VisibleForTesting
    void startOneHanded() {
        if (isLockedDisabled()) {
            Slog.d(TAG, "Temporary lock disabled");
            return;
        }
        if (!mDisplayAreaOrganizer.isInOneHanded()) {
            final int yOffSet = Math.round(getDisplaySize().height() * mOffSetFraction);
            mDisplayAreaOrganizer.scheduleOffset(0, yOffSet);
            mTimeoutHandler.resetTimer();
            mOneHandedUiEventLogger.writeEvent(
                    OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_GESTURE_IN);
        }
    }

    @VisibleForTesting
    void stopOneHanded() {
        if (mDisplayAreaOrganizer.isInOneHanded()) {
            mDisplayAreaOrganizer.scheduleOffset(0, 0);
            mTimeoutHandler.removeTimer();
            //  Log metrics for Gesture navigation mode.
            mOneHandedUiEventLogger.writeEvent(
                    OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT);
        }
    }

    private void stopOneHanded(int uiEvent) {
        if (mDisplayAreaOrganizer.isInOneHanded()) {
            mDisplayAreaOrganizer.scheduleOffset(0, 0);
            mTimeoutHandler.removeTimer();
            mOneHandedUiEventLogger.writeEvent(uiEvent);
        }
    }

    private void setThreeButtonModeEnabled(boolean enabled) {
        mGestureHandler.onThreeButtonModeEnabled(enabled);
    }

    @VisibleForTesting
    void registerTransitionCallback(OneHandedTransitionCallback callback) {
        mDisplayAreaOrganizer.registerTransitionCallback(callback);
    }

    private void registerGestureCallback(OneHandedGestureEventCallback callback) {
        mGestureHandler.setGestureEventListener(callback);
    }

    private void setupCallback() {
        mTouchHandler.registerTouchEventListener(() ->
                stopOneHanded(OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_OVERSPACE_OUT));
        mDisplayAreaOrganizer.registerTransitionCallback(mTouchHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mGestureHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mTutorialHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mBackgroundPanelOrganizer);
        if (mTaskChangeToExit) {
            mTaskStackListener.addListener(mTaskStackListenerCallback);
        }
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

    private ContentObserver getObserver(Runnable onChangeRunnable) {
        return new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onChangeRunnable.run();
            }
        };
    }

    @VisibleForTesting
    void onEnabledSettingChanged() {
        final boolean enabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                mContext.getContentResolver());
        mOneHandedUiEventLogger.writeEvent(enabled
                ? OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_ENABLED_ON
                : OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_ENABLED_OFF);

        setOneHandedEnabled(enabled);

        // Also checks swipe to notification settings since they all need gesture overlay.
        setEnabledGesturalOverlay(
                enabled || OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                        mContext.getContentResolver()));
    }

    @VisibleForTesting
    void onTimeoutSettingChanged() {
        final int newTimeout = OneHandedSettingsUtil.getSettingsOneHandedModeTimeout(
                mContext.getContentResolver());
        int metricsId = OneHandedUiEventLogger.OneHandedSettingsTogglesEvent.INVALID.getId();
        switch (newTimeout) {
            case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_NEVER:
                metricsId = OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_NEVER;
                break;
            case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS:
                metricsId = OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_4;
                break;
            case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS:
                metricsId = OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_8;
                break;
            case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_LONG_IN_SECONDS:
                metricsId = OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_12;
                break;
            default:
                // do nothing
                break;
        }
        mOneHandedUiEventLogger.writeEvent(metricsId);

        if (mTimeoutHandler != null) {
            mTimeoutHandler.setTimeout(newTimeout);
        }
    }

    @VisibleForTesting
    void onTaskChangeExitSettingChanged() {
        final boolean enabled = OneHandedSettingsUtil.getSettingsTapsAppToExit(
                mContext.getContentResolver());
        mOneHandedUiEventLogger.writeEvent(enabled
                ? OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_ON
                : OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_OFF);

        setTaskChangeToExit(enabled);
    }

    @VisibleForTesting
    void onSwipeToNotificationEnabledSettingChanged() {
        final boolean enabled =
                OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                        mContext.getContentResolver());
        setSwipeToNotificationEnabled(enabled);

        // Also checks one handed mode settings since they all need gesture overlay.
        setEnabledGesturalOverlay(
                enabled || OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                        mContext.getContentResolver()));
    }

    private void setupTimeoutListener() {
        mTimeoutHandler.registerTimeoutListener(timeoutTime -> stopOneHanded(
                OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_TIMEOUT_OUT));
    }

    /**
     * Query the current display real size from {@link WindowManager}
     *
     * @return {@link WindowManager#getCurrentWindowMetrics()#getBounds()}
     */
    private Rect getDisplaySize() {
        if (mWindowManager == null) {
            Slog.e(TAG, "WindowManager instance is null! Can not get display size!");
            return new Rect();
        }
        final Rect displaySize = mWindowManager.getCurrentWindowMetrics().getBounds();
        if (displaySize.width() == 0 || displaySize.height() == 0) {
            Slog.e(TAG, "Display size error! width = " + displaySize.width()
                    + ", height = " + displaySize.height());
        }
        return displaySize;
    }

    @VisibleForTesting
    boolean isLockedDisabled() {
        return mLockedDisabled;
    }

    private void updateOneHandedEnabled() {
        if (mDisplayAreaOrganizer.isInOneHanded()) {
            stopOneHanded();
        }

        mTouchHandler.onOneHandedEnabled(mIsOneHandedEnabled);
        mGestureHandler.onOneHandedEnabled(mIsOneHandedEnabled || mIsSwipeToNotificationEnabled);

        if (!mIsOneHandedEnabled) {
            mDisplayAreaOrganizer.unregisterOrganizer();
            mBackgroundPanelOrganizer.unregisterOrganizer();
            // Do NOT register + unRegister DA in the same call
            return;
        }

        if (mDisplayAreaOrganizer.getDisplayAreaTokenMap().isEmpty()) {
            mDisplayAreaOrganizer.registerOrganizer(
                    OneHandedDisplayAreaOrganizer.FEATURE_ONE_HANDED);
        }

        if (mBackgroundPanelOrganizer.getBackgroundSurface() == null) {
            mBackgroundPanelOrganizer.registerOrganizer(
                    OneHandedBackgroundPanelOrganizer.FEATURE_ONE_HANDED_BACKGROUND_PANEL);
        }
    }

    private void setupGesturalOverlay() {
        if (!OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(mContext.getContentResolver())) {
            return;
        }

        OverlayInfo info = null;
        try {
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

    @VisibleForTesting
    void setLockedDisabled(boolean locked, boolean enabled) {
        if (enabled == mIsOneHandedEnabled) {
            return;
        }
        mLockedDisabled = locked && !enabled;
    }

    private void onConfigChanged(Configuration newConfig) {
        if (mTutorialHandler != null) {
            if (!mIsOneHandedEnabled
                    || newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return;
            }
            mTutorialHandler.onConfigurationChanged(newConfig);
        }
    }

    public void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mOffSetFraction=");
        pw.println(mOffSetFraction);
        pw.print(innerPrefix + "mLockedDisabled=");
        pw.println(mLockedDisabled);

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

    @ExternalThread
    private class OneHandedImpl implements OneHanded {
        @Override
        public boolean isOneHandedEnabled() {
            // This is volatile so return directly
            return mIsOneHandedEnabled;
        }

        @Override
        public boolean isSwipeToNotificationEnabled() {
            // This is volatile so return directly
            return mIsSwipeToNotificationEnabled;
        }

        @Override
        public void startOneHanded() {
            mMainExecutor.execute(() -> {
                OneHandedController.this.startOneHanded();
            });
        }

        @Override
        public void stopOneHanded() {
            mMainExecutor.execute(() -> {
                OneHandedController.this.stopOneHanded();
            });
        }

        @Override
        public void stopOneHanded(int event) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.stopOneHanded(event);
            });
        }

        @Override
        public void setThreeButtonModeEnabled(boolean enabled) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.setThreeButtonModeEnabled(enabled);
            });
        }

        @Override
        public void setLockedDisabled(boolean locked, boolean enabled) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.setLockedDisabled(locked, enabled);
            });
        }

        @Override
        public void registerTransitionCallback(OneHandedTransitionCallback callback) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.registerTransitionCallback(callback);
            });
        }

        @Override
        public void registerGestureCallback(OneHandedGestureEventCallback callback) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.registerGestureCallback(callback);
            });
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.onConfigChanged(newConfig);
            });
        }
    }
}
