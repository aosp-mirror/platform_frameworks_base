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
import static android.os.UserHandle.myUserId;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_ACTIVE;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_ENTERING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_EXITING;
import static com.android.wm.shell.onehanded.OneHandedState.STATE_NONE;

import android.annotation.BinderThread;
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
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEventLogger;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;

/**
 * Manages and manipulates the one handed states, transitions, and gesture for phones.
 */
public class OneHandedController implements RemoteCallable<OneHandedController> {
    private static final String TAG = "OneHandedController";

    private static final String ONE_HANDED_MODE_OFFSET_PERCENTAGE =
            "persist.debug.one_handed_offset_percentage";
    private static final String ONE_HANDED_MODE_GESTURAL_OVERLAY =
            "com.android.internal.systemui.onehanded.gestural";
    private static final int OVERLAY_ENABLED_DELAY_MS = 250;
    private static final int DISPLAY_AREA_READY_RETRY_MS = 10;

    static final String SUPPORT_ONE_HANDED_MODE = "ro.support_one_handed_mode";

    private volatile boolean mIsOneHandedEnabled;
    private volatile boolean mIsSwipeToNotificationEnabled;
    private boolean mTaskChangeToExit;
    private boolean mLockedDisabled;
    private boolean mKeyguardShowing;
    private int mUserId;
    private float mOffSetFraction;

    private Context mContext;

    private final AccessibilityManager mAccessibilityManager;
    private final DisplayController mDisplayController;
    private final OneHandedSettingsUtil mOneHandedSettingsUtil;
    private final OneHandedAccessibilityUtil mOneHandedAccessibilityUtil;
    private final OneHandedTimeoutHandler mTimeoutHandler;
    private final OneHandedTouchHandler mTouchHandler;
    private final OneHandedState mState;
    private final OneHandedTutorialHandler mTutorialHandler;
    private final TaskStackListenerImpl mTaskStackListener;
    private final IOverlayManager mOverlayManager;
    private final ShellExecutor mMainExecutor;
    private final Handler mMainHandler;
    private final OneHandedImpl mImpl = new OneHandedImpl();

    private OneHandedEventCallback mEventCallback;
    private OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;
    private OneHandedBackgroundPanelOrganizer mBackgroundPanelOrganizer;
    private OneHandedUiEventLogger mOneHandedUiEventLogger;

    /**
     * Handle rotation based on OnDisplayChangingListener callback
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController =
            (display, fromRotation, toRotation, wct) -> {
                if (!isInitialized()) {
                    return;
                }
                mDisplayAreaOrganizer.onRotateDisplay(mContext, toRotation, wct);
                mOneHandedUiEventLogger.writeEvent(
                        OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_ROTATION_OUT);
            };

    private final DisplayController.OnDisplaysChangedListener mDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                    if (displayId != DEFAULT_DISPLAY || !isInitialized()) {
                        return;
                    }
                    updateDisplayLayout(displayId);
                }

                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId != DEFAULT_DISPLAY || !isInitialized()) {
                        return;
                    }
                    updateDisplayLayout(displayId);
                }
            };

    private final ContentObserver mActivatedObserver;
    private final ContentObserver mEnabledObserver;
    private final ContentObserver mTimeoutObserver;
    private final ContentObserver mTaskChangeExitObserver;
    private final ContentObserver mSwipeToNotificationEnabledObserver;

    private AccessibilityManager.AccessibilityStateChangeListener
            mAccessibilityStateChangeListener =
            new AccessibilityManager.AccessibilityStateChangeListener() {
                @Override
                public void onAccessibilityStateChanged(boolean enabled) {
                    if (!isInitialized()) {
                        return;
                    }
                    if (enabled) {
                        final int mOneHandedTimeout = mOneHandedSettingsUtil
                                .getSettingsOneHandedModeTimeout(
                                        mContext.getContentResolver(), mUserId);
                        final int timeout = mAccessibilityManager
                                .getRecommendedTimeoutMillis(mOneHandedTimeout * 1000
                                        /* align with A11y timeout millis */,
                                        AccessibilityManager.FLAG_CONTENT_CONTROLS);
                        mTimeoutHandler.setTimeout(timeout / 1000);
                    } else {
                        mTimeoutHandler.setTimeout(mOneHandedSettingsUtil
                                .getSettingsOneHandedModeTimeout(
                                        mContext.getContentResolver(), mUserId));
                    }
                }
            };

    private final OneHandedTransitionCallback mTransitionCallBack =
            new OneHandedTransitionCallback() {
                @Override
                public void onStartFinished(Rect bounds) {
                    mState.setState(STATE_ACTIVE);
                    notifyShortcutState(STATE_ACTIVE);
                }

                @Override
                public void onStopFinished(Rect bounds) {
                    mState.setState(STATE_NONE);
                    notifyShortcutState(STATE_NONE);
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

    private boolean isInitialized() {
        if (mDisplayAreaOrganizer == null || mDisplayController == null
                || mOneHandedSettingsUtil == null) {
            Slog.w(TAG, "Components may not initialized yet!");
            return false;
        }
        return true;
    }

    /**
     * Creates {@link OneHandedController}, returns {@code null} if the feature is not supported.
     */
    @Nullable
    public static OneHandedController create(
            Context context, WindowManager windowManager, DisplayController displayController,
            DisplayLayout displayLayout, TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger, ShellExecutor mainExecutor, Handler mainHandler) {
        if (!SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false)) {
            Slog.w(TAG, "Device doesn't support OneHanded feature");
            return null;
        }

        OneHandedSettingsUtil settingsUtil = new OneHandedSettingsUtil();
        OneHandedAccessibilityUtil accessibilityUtil = new OneHandedAccessibilityUtil(context);
        OneHandedTimeoutHandler timeoutHandler = new OneHandedTimeoutHandler(mainExecutor);
        OneHandedState transitionState = new OneHandedState();
        OneHandedTutorialHandler tutorialHandler = new OneHandedTutorialHandler(context,
                displayLayout, windowManager, settingsUtil, mainExecutor);
        OneHandedAnimationController animationController =
                new OneHandedAnimationController(context);
        OneHandedTouchHandler touchHandler = new OneHandedTouchHandler(timeoutHandler,
                mainExecutor);
        OneHandedBackgroundPanelOrganizer oneHandedBackgroundPanelOrganizer =
                new OneHandedBackgroundPanelOrganizer(context, displayLayout, mainExecutor);
        OneHandedDisplayAreaOrganizer organizer = new OneHandedDisplayAreaOrganizer(
                context, displayLayout, settingsUtil, animationController, tutorialHandler,
                oneHandedBackgroundPanelOrganizer, mainExecutor);
        OneHandedUiEventLogger oneHandedUiEventsLogger = new OneHandedUiEventLogger(uiEventLogger);
        IOverlayManager overlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        return new OneHandedController(context, displayController,
                oneHandedBackgroundPanelOrganizer, organizer, touchHandler, tutorialHandler,
                settingsUtil, accessibilityUtil, timeoutHandler, transitionState,
                oneHandedUiEventsLogger, overlayManager, taskStackListener, mainExecutor,
                mainHandler);
    }

    @VisibleForTesting
    OneHandedController(Context context,
            DisplayController displayController,
            OneHandedBackgroundPanelOrganizer backgroundPanelOrganizer,
            OneHandedDisplayAreaOrganizer displayAreaOrganizer,
            OneHandedTouchHandler touchHandler,
            OneHandedTutorialHandler tutorialHandler,
            OneHandedSettingsUtil settingsUtil,
            OneHandedAccessibilityUtil oneHandedAccessibilityUtil,
            OneHandedTimeoutHandler timeoutHandler,
            OneHandedState state,
            OneHandedUiEventLogger uiEventsLogger,
            IOverlayManager overlayManager,
            TaskStackListenerImpl taskStackListener,
            ShellExecutor mainExecutor,
            Handler mainHandler) {
        mContext = context;
        mOneHandedSettingsUtil = settingsUtil;
        mOneHandedAccessibilityUtil = oneHandedAccessibilityUtil;
        mBackgroundPanelOrganizer = backgroundPanelOrganizer;
        mDisplayAreaOrganizer = displayAreaOrganizer;
        mDisplayController = displayController;
        mTouchHandler = touchHandler;
        mState = state;
        mTutorialHandler = tutorialHandler;
        mOverlayManager = overlayManager;
        mMainExecutor = mainExecutor;
        mMainHandler = mainHandler;
        mOneHandedUiEventLogger = uiEventsLogger;
        mTaskStackListener = taskStackListener;

        mDisplayController.addDisplayWindowListener(mDisplaysChangedListener);
        final float offsetPercentageConfig = context.getResources().getFraction(
                R.fraction.config_one_handed_offset, 1, 1);
        final int sysPropPercentageConfig = SystemProperties.getInt(
                ONE_HANDED_MODE_OFFSET_PERCENTAGE, Math.round(offsetPercentageConfig * 100.0f));
        mUserId = myUserId();
        mOffSetFraction = sysPropPercentageConfig / 100.0f;
        mIsOneHandedEnabled = mOneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                context.getContentResolver(), mUserId);
        mIsSwipeToNotificationEnabled =
                mOneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                        context.getContentResolver(), mUserId);
        mTimeoutHandler = timeoutHandler;

        mActivatedObserver = getObserver(this::onActivatedActionChanged);
        mEnabledObserver = getObserver(this::onEnabledSettingChanged);
        mTimeoutObserver = getObserver(this::onTimeoutSettingChanged);
        mTaskChangeExitObserver = getObserver(this::onTaskChangeExitSettingChanged);
        mSwipeToNotificationEnabledObserver =
                getObserver(this::onSwipeToNotificationEnabledChanged);

        mDisplayController.addDisplayChangingController(mRotationController);
        setupCallback();
        registerSettingObservers(mUserId);
        setupTimeoutListener();
        setupGesturalOverlay();
        updateSettings();

        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mAccessibilityManager.addAccessibilityStateChangeListener(
                mAccessibilityStateChangeListener);

        mState.addSListeners(mTutorialHandler);
    }

    public OneHanded asOneHanded() {
        return mImpl;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
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
    void notifyShortcutState(@OneHandedState.State int state) {
        mOneHandedSettingsUtil.setOneHandedModeActivated(
                mContext.getContentResolver(), state == STATE_ACTIVE ? 1 : 0, mUserId);
    }

    @VisibleForTesting
    void startOneHanded() {
        if (isLockedDisabled() || mKeyguardShowing) {
            Slog.d(TAG, "Temporary lock disabled");
            return;
        }

        if (!mDisplayAreaOrganizer.isReady()) {
            // Must wait until DisplayAreaOrganizer is ready for transitioning.
            mMainExecutor.executeDelayed(this::startOneHanded, DISPLAY_AREA_READY_RETRY_MS);
            return;
        }

        if (mState.isTransitioning() || mState.isInOneHanded()) {
            return;
        }

        final int currentRotation = mDisplayAreaOrganizer.getDisplayLayout().rotation();
        if (currentRotation != Surface.ROTATION_0 && currentRotation != Surface.ROTATION_180) {
            Slog.w(TAG, "One handed mode only support portrait mode");
            return;
        }

        mState.setState(STATE_ENTERING);
        final int yOffSet = Math.round(
                mDisplayAreaOrganizer.getDisplayLayout().height() * mOffSetFraction);
        mOneHandedAccessibilityUtil.announcementForScreenReader(
                mOneHandedAccessibilityUtil.getOneHandedStartDescription());
        mDisplayAreaOrganizer.scheduleOffset(0, yOffSet);
        mTimeoutHandler.resetTimer();
        mOneHandedUiEventLogger.writeEvent(
                OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_GESTURE_IN);
    }

    @VisibleForTesting
    void stopOneHanded() {
        stopOneHanded(OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT);
    }

    private void stopOneHanded(int uiEvent) {
        if (mState.isTransitioning() || mState.getState() == STATE_NONE) {
            return;
        }
        mState.setState(STATE_EXITING);
        mOneHandedAccessibilityUtil.announcementForScreenReader(
                mOneHandedAccessibilityUtil.getOneHandedStopDescription());
        mDisplayAreaOrganizer.scheduleOffset(0, 0);
        mTimeoutHandler.removeTimer();
        mOneHandedUiEventLogger.writeEvent(uiEvent);
    }

    void registerEventCallback(OneHandedEventCallback callback) {
        mEventCallback = callback;
    }

    @VisibleForTesting
    void registerTransitionCallback(OneHandedTransitionCallback callback) {
        mDisplayAreaOrganizer.registerTransitionCallback(callback);
    }

    private void setupCallback() {
        mTouchHandler.registerTouchEventListener(() ->
                stopOneHanded(OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_OVERSPACE_OUT));
        mDisplayAreaOrganizer.registerTransitionCallback(mTouchHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mTutorialHandler);
        mDisplayAreaOrganizer.registerTransitionCallback(mBackgroundPanelOrganizer);
        mDisplayAreaOrganizer.registerTransitionCallback(mTransitionCallBack);
        if (mTaskChangeToExit) {
            mTaskStackListener.addListener(mTaskStackListenerCallback);
        }
    }

    private void registerSettingObservers(int newUserId) {
        mOneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.ONE_HANDED_MODE_ACTIVATED,
                mContext.getContentResolver(), mActivatedObserver, newUserId);
        mOneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_ENABLED,
                mContext.getContentResolver(), mEnabledObserver, newUserId);
        mOneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                mContext.getContentResolver(), mTimeoutObserver, newUserId);
        mOneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.TAPS_APP_TO_EXIT,
                mContext.getContentResolver(), mTaskChangeExitObserver, newUserId);
        mOneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED,
                mContext.getContentResolver(), mSwipeToNotificationEnabledObserver, newUserId);
    }

    private void unregisterSettingObservers() {
        mOneHandedSettingsUtil.unregisterSettingsKeyObserver(mContext.getContentResolver(),
                mEnabledObserver);
        mOneHandedSettingsUtil.unregisterSettingsKeyObserver(mContext.getContentResolver(),
                mTimeoutObserver);
        mOneHandedSettingsUtil.unregisterSettingsKeyObserver(mContext.getContentResolver(),
                mTaskChangeExitObserver);
        mOneHandedSettingsUtil.unregisterSettingsKeyObserver(mContext.getContentResolver(),
                mSwipeToNotificationEnabledObserver);
    }

    private void updateSettings() {
        setOneHandedEnabled(mOneHandedSettingsUtil
                .getSettingsOneHandedModeEnabled(mContext.getContentResolver(), mUserId));
        mTimeoutHandler.setTimeout(mOneHandedSettingsUtil
                .getSettingsOneHandedModeTimeout(mContext.getContentResolver(), mUserId));
        setTaskChangeToExit(mOneHandedSettingsUtil
                .getSettingsTapsAppToExit(mContext.getContentResolver(), mUserId));
        setSwipeToNotificationEnabled(mOneHandedSettingsUtil
                .getSettingsSwipeToNotificationEnabled(mContext.getContentResolver(), mUserId));
    }

    private void updateDisplayLayout(int displayId) {
        final DisplayLayout newDisplayLayout = mDisplayController.getDisplayLayout(displayId);
        mDisplayAreaOrganizer.setDisplayLayout(newDisplayLayout);
        mTutorialHandler.onDisplayChanged(newDisplayLayout);
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
    void notifyExpandNotification() {
        if (mEventCallback != null) {
            mMainExecutor.execute(() -> mEventCallback.notifyExpandNotification());
        }
    }

    @VisibleForTesting
    void notifyUserConfigChanged(boolean success) {
        if (!success) {
            return;
        }
        // TODO Check UX if popup Toast to notify user when auto-enabled one-handed is good option.
        Toast.makeText(mContext, R.string.one_handed_tutorial_title, Toast.LENGTH_LONG).show();
    }

    @VisibleForTesting
    void onActivatedActionChanged() {
        if (!isShortcutEnabled()) {
            Slog.w(TAG, "Shortcut not enabled, skip onActivatedActionChanged()");
            return;
        }

        if (!isOneHandedEnabled()) {
            final boolean success = mOneHandedSettingsUtil.setOneHandedModeEnabled(
                    mContext.getContentResolver(), 1 /* Enabled for shortcut */, mUserId);
            notifyUserConfigChanged(success);
        }

        if (isSwipeToNotificationEnabled()) {
            notifyExpandNotification();
            return;
        }

        final boolean isActivated = mState.getState() == STATE_ACTIVE;
        final boolean requestActivated = mOneHandedSettingsUtil.getOneHandedModeActivated(
                mContext.getContentResolver(), mUserId);
        // When gesture trigger action, we will update settings and introduce observer callback
        // again, then the following logic will just ignore the second redundant callback.
        if (isActivated ^ requestActivated) {
            if (requestActivated) {
                startOneHanded();
            } else {
                stopOneHanded();
            }
        }
    }

    @VisibleForTesting
    void onEnabledSettingChanged() {
        final boolean enabled = mOneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                mContext.getContentResolver(), mUserId);
        mOneHandedUiEventLogger.writeEvent(enabled
                ? OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_ENABLED_ON
                : OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_ENABLED_OFF);

        setOneHandedEnabled(enabled);

        // Also checks swipe to notification settings since they all need gesture overlay.
        setEnabledGesturalOverlay(
                enabled || mOneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                        mContext.getContentResolver(), mUserId), true /* DelayExecute */);
    }

    @VisibleForTesting
    void onTimeoutSettingChanged() {
        final int newTimeout = mOneHandedSettingsUtil.getSettingsOneHandedModeTimeout(
                mContext.getContentResolver(), mUserId);
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
        final boolean enabled = mOneHandedSettingsUtil.getSettingsTapsAppToExit(
                mContext.getContentResolver(), mUserId);
        mOneHandedUiEventLogger.writeEvent(enabled
                ? OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_ON
                : OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_OFF);

        setTaskChangeToExit(enabled);
    }

    @VisibleForTesting
    void onSwipeToNotificationEnabledChanged() {
        final boolean enabled =
                mOneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                        mContext.getContentResolver(), mUserId);
        setSwipeToNotificationEnabled(enabled);

        mOneHandedUiEventLogger.writeEvent(enabled
                ? OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_SHOW_NOTIFICATION_ENABLED_ON
                : OneHandedUiEventLogger.EVENT_ONE_HANDED_SETTINGS_SHOW_NOTIFICATION_ENABLED_OFF);

        // Also checks one handed mode settings since they all need gesture overlay.
        setEnabledGesturalOverlay(
                enabled || mOneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                        mContext.getContentResolver(), mUserId), true /* DelayExecute */);
    }

    private void setupTimeoutListener() {
        mTimeoutHandler.registerTimeoutListener(timeoutTime -> stopOneHanded(
                OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_TIMEOUT_OUT));
    }

    @VisibleForTesting
    boolean isLockedDisabled() {
        return mLockedDisabled;
    }

    @VisibleForTesting
    boolean isOneHandedEnabled() {
        return mIsOneHandedEnabled;
    }

    @VisibleForTesting
    boolean isShortcutEnabled() {
        return mOneHandedSettingsUtil.getShortcutEnabled(mContext.getContentResolver(), mUserId);
    }

    @VisibleForTesting
    boolean isSwipeToNotificationEnabled() {
        return mIsSwipeToNotificationEnabled;
    }

    private void updateOneHandedEnabled() {
        if (mState.getState() == STATE_ENTERING || mState.getState() == STATE_ACTIVE) {
            mMainExecutor.execute(() -> stopOneHanded());
        }

        // If setting is pull screen, notify shortcut one_handed_mode_activated to reset
        // and align status with current mState when function enabled.
        if (isOneHandedEnabled() && !isSwipeToNotificationEnabled()) {
            notifyShortcutState(mState.getState());
        }

        mTouchHandler.onOneHandedEnabled(mIsOneHandedEnabled);

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
        if (!mOneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                mContext.getContentResolver(), mUserId)) {
            return;
        }

        OverlayInfo info = null;
        try {
            mOverlayManager.setHighestPriority(ONE_HANDED_MODE_GESTURAL_OVERLAY, USER_CURRENT);
            info = mOverlayManager.getOverlayInfo(ONE_HANDED_MODE_GESTURAL_OVERLAY, USER_CURRENT);
        } catch (RemoteException e) { /* Do nothing */ }

        if (info != null && !info.isEnabled()) {
            // Enable the default gestural one handed overlay.
            setEnabledGesturalOverlay(true /* enabled */, false /* delayExecute */);
        }
    }

    @VisibleForTesting
    private void setEnabledGesturalOverlay(boolean enabled, boolean delayExecute) {
        if (mState.isTransitioning() || delayExecute) {
            // Enabled overlay package may affect the current animation(e.g:Settings switch),
            // so we delay 250ms to enabled overlay after switch animation finish, only delay once.
            mMainExecutor.executeDelayed(() -> setEnabledGesturalOverlay(enabled, false),
                    OVERLAY_ENABLED_DELAY_MS);
            return;
        }
        try {
            mOverlayManager.setEnabled(ONE_HANDED_MODE_GESTURAL_OVERLAY, enabled, USER_CURRENT);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @VisibleForTesting
    void setLockedDisabled(boolean locked, boolean enabled) {
        final boolean isFeatureEnabled = mIsOneHandedEnabled || mIsSwipeToNotificationEnabled;

        if (enabled == isFeatureEnabled) {
            return;
        }

        mLockedDisabled = locked && !enabled;
    }

    private void onConfigChanged(Configuration newConfig) {
        if (mTutorialHandler == null) {
            return;
        }
        if (!mIsOneHandedEnabled || newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }
        mTutorialHandler.onConfigurationChanged();
    }

    private void onKeyguardVisibilityChanged(boolean showing) {
        mKeyguardShowing = showing;
    }

    private void onUserSwitch(int newUserId) {
        unregisterSettingObservers();
        mUserId = newUserId;
        registerSettingObservers(newUserId);
        updateSettings();
        updateOneHandedEnabled();
    }

    public void dump(@NonNull PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println();
        pw.println(TAG);
        pw.print(innerPrefix + "mOffSetFraction=");
        pw.println(mOffSetFraction);
        pw.print(innerPrefix + "mLockedDisabled=");
        pw.println(mLockedDisabled);
        pw.print(innerPrefix + "mUserId=");
        pw.println(mUserId);
        pw.print(innerPrefix + "isShortcutEnabled=");
        pw.println(isShortcutEnabled());

        if (mBackgroundPanelOrganizer != null) {
            mBackgroundPanelOrganizer.dump(pw);
        }

        if (mDisplayAreaOrganizer != null) {
            mDisplayAreaOrganizer.dump(pw);
        }

        if (mTouchHandler != null) {
            mTouchHandler.dump(pw);
        }

        if (mTimeoutHandler != null) {
            mTimeoutHandler.dump(pw);
        }

        if (mState != null) {
            mState.dump(pw);
        }

        if (mTutorialHandler != null) {
            mTutorialHandler.dump(pw);
        }

        if (mOneHandedAccessibilityUtil != null) {
            mOneHandedAccessibilityUtil.dump(pw);
        }

        mOneHandedSettingsUtil.dump(pw, innerPrefix, mContext.getContentResolver(), mUserId);

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

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class OneHandedImpl implements OneHanded {
        private IOneHandedImpl mIOneHanded;

        @Override
        public IOneHanded createExternalInterface() {
            if (mIOneHanded != null) {
                mIOneHanded.invalidate();
            }
            mIOneHanded = new IOneHandedImpl(OneHandedController.this);
            return mIOneHanded;
        }

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
        public void setLockedDisabled(boolean locked, boolean enabled) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.setLockedDisabled(locked, enabled);
            });
        }

        @Override
        public void registerEventCallback(OneHandedEventCallback callback) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.registerEventCallback(callback);
            });
        }

        @Override
        public void registerTransitionCallback(OneHandedTransitionCallback callback) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.registerTransitionCallback(callback);
            });
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.onConfigChanged(newConfig);
            });
        }

        @Override
        public void onUserSwitch(int userId) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.onUserSwitch(userId);
            });
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mMainExecutor.execute(() -> {
                OneHandedController.this.onKeyguardVisibilityChanged(showing);
            });
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IOneHandedImpl extends IOneHanded.Stub {
        private OneHandedController mController;

        IOneHandedImpl(OneHandedController controller) {
            mController = controller;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        void invalidate() {
            mController = null;
        }

        @Override
        public void startOneHanded() {
            executeRemoteCallWithTaskPermission(mController, "startOneHanded",
                    (controller) -> {
                        controller.startOneHanded();
                    });
        }

        @Override
        public void stopOneHanded() {
            executeRemoteCallWithTaskPermission(mController, "stopOneHanded",
                    (controller) -> {
                        controller.stopOneHanded();
                    });
        }
    }
}
