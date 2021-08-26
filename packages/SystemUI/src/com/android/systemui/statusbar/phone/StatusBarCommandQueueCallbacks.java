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

import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.windowStateToString;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.containsType;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;

import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Slog;
import android.view.InsetsState.InternalInsetsType;
import android.view.InsetsVisibilities;
import android.view.KeyEvent;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.view.AppearanceRegion;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.camera.CameraIntents;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DisableFlagsLogger;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;

import java.util.Optional;

import javax.inject.Inject;

/** */
@StatusBarComponent.StatusBarScope
public class StatusBarCommandQueueCallbacks implements CommandQueue.Callbacks {
    private final StatusBar mStatusBar;
    private final Context mContext;
    private final ShadeController mShadeController;
    private final CommandQueue mCommandQueue;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    private final RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private final MetricsLogger mMetricsLogger;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final HeadsUpManager mHeadsUpManager;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final AssistManager mAssistManager;
    private final DozeServiceHost mDozeServiceHost;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotificationShadeWindowView mNotificationShadeWindowView;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final PowerManager mPowerManager;
    private final VibratorHelper mVibratorHelper;
    private final Optional<Vibrator> mVibratorOptional;
    private final LightBarController mLightBarController;
    private final DisableFlagsLogger mDisableFlagsLogger;
    private final int mDisplayId;
    private final boolean mVibrateOnOpening;
    private final VibrationEffect mCameraLaunchGestureVibrationEffect;


    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    @Inject
    StatusBarCommandQueueCallbacks(
            StatusBar statusBar,
            Context context,
            @Main Resources resources,
            ShadeController shadeController,
            CommandQueue commandQueue,
            NotificationPanelViewController notificationPanelViewController,
            Optional<LegacySplitScreen> splitScreenOptional,
            RemoteInputQuickSettingsDisabler remoteInputQuickSettingsDisabler,
            MetricsLogger metricsLogger,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardStateController keyguardStateController,
            HeadsUpManager headsUpManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            DeviceProvisionedController deviceProvisionedController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            AssistManager assistManager,
            DozeServiceHost dozeServiceHost,
            SysuiStatusBarStateController statusBarStateController,
            NotificationShadeWindowView notificationShadeWindowView,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            PowerManager powerManager,
            VibratorHelper vibratorHelper,
            Optional<Vibrator> vibratorOptional,
            LightBarController lightBarController,
            DisableFlagsLogger disableFlagsLogger,
            @DisplayId int displayId) {

        mStatusBar = statusBar;
        mContext = context;
        mShadeController = shadeController;
        mCommandQueue = commandQueue;
        mNotificationPanelViewController = notificationPanelViewController;
        mSplitScreenOptional = splitScreenOptional;
        mRemoteInputQuickSettingsDisabler = remoteInputQuickSettingsDisabler;
        mMetricsLogger = metricsLogger;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManager;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mDeviceProvisionedController = deviceProvisionedController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mAssistManager = assistManager;
        mDozeServiceHost = dozeServiceHost;
        mStatusBarStateController = statusBarStateController;
        mNotificationShadeWindowView = notificationShadeWindowView;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mPowerManager = powerManager;
        mVibratorHelper = vibratorHelper;
        mVibratorOptional = vibratorOptional;
        mLightBarController = lightBarController;
        mDisableFlagsLogger = disableFlagsLogger;
        mDisplayId = displayId;

        mVibrateOnOpening = resources.getBoolean(R.bool.config_vibrateOnIconAnimation);
        mCameraLaunchGestureVibrationEffect = getCameraGestureVibrationEffect(
                mVibratorOptional, resources);
    }

    @Override
    public void abortTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_STATUS_BAR)) {
            return;
        }
        mStatusBar.clearTransient();
    }

    @Override
    public void addQsTile(ComponentName tile) {
        QSPanelController qsPanelController = mStatusBar.getQSPanelController();
        if (qsPanelController != null && qsPanelController.getHost() != null) {
            qsPanelController.getHost().addTile(tile);
        }
    }

    @Override
    public void remQsTile(ComponentName tile) {
        QSPanelController qsPanelController = mStatusBar.getQSPanelController();
        if (qsPanelController != null && qsPanelController.getHost() != null) {
            qsPanelController.getHost().removeTile(tile);
        }
    }

    @Override
    public void clickTile(ComponentName tile) {
        QSPanelController qsPanelController = mStatusBar.getQSPanelController();
        if (qsPanelController != null) {
            qsPanelController.clickTile(tile);
        }
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        mShadeController.animateCollapsePanels(flags, force, false /* delayed */,
                1.0f /* speedUpFactor */);
    }

    @Override
    public void animateExpandNotificationsPanel() {
        if (StatusBar.SPEW) {
            Log.d(StatusBar.TAG,
                    "animateExpand: mExpandedVisible=" + mStatusBar.isExpandedVisible());
        }
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }

        mNotificationPanelViewController.expandWithoutQs();
    }

    @Override
    public void animateExpandSettingsPanel(@Nullable String subPanel) {
        if (StatusBar.SPEW) {
            Log.d(StatusBar.TAG,
                    "animateExpand: mExpandedVisible=" + mStatusBar.isExpandedVisible());
        }
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }

        // Settings are not available in setup
        if (!mDeviceProvisionedController.isCurrentUserSetup()) return;


        QSPanelController qsPanelController = mStatusBar.getQSPanelController();
        if (subPanel != null && qsPanelController != null) {
            qsPanelController.openDetails(subPanel);
        }
        mNotificationPanelViewController.expandWithQs();
    }

    @Override
    public void appTransitionCancelled(int displayId) {
        if (displayId == mDisplayId) {
            mSplitScreenOptional.ifPresent(LegacySplitScreen::onAppTransitionFinished);
        }
    }

    @Override
    public void appTransitionFinished(int displayId) {
        if (displayId == mDisplayId) {
            mSplitScreenOptional.ifPresent(LegacySplitScreen::onAppTransitionFinished);
        }
    }

    @Override
    public void dismissKeyboardShortcutsMenu() {
        mStatusBar.resendMessage(StatusBar.MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU);
    }
    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     */
    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != mDisplayId) {
            return;
        }

        int state2BeforeAdjustment = state2;
        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);
        Log.d(StatusBar.TAG,
                mDisableFlagsLogger.getDisableFlagsString(
                        /* old= */ new DisableFlagsLogger.DisableState(
                                mStatusBar.getDisabled1(), mStatusBar.getDisabled2()),
                        /* new= */ new DisableFlagsLogger.DisableState(
                                state1, state2BeforeAdjustment),
                        /* newStateAfterLocalModification= */ new DisableFlagsLogger.DisableState(
                                state1, state2)));

        final int old1 = mStatusBar.getDisabled1();
        final int diff1 = state1 ^ old1;
        mStatusBar.setDisabled1(state1);

        final int old2 = mStatusBar.getDisabled2();
        final int diff2 = state2 ^ old2;
        mStatusBar.setDisabled2(state2);

        if ((diff1 & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state1 & StatusBarManager.DISABLE_EXPAND) != 0) {
                mShadeController.animateCollapsePanels();
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) {
            if (mStatusBar.areNotificationAlertsDisabled()) {
                mHeadsUpManager.releaseAllImmediately();
            }
        }

        if ((diff2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) != 0) {
            mStatusBar.updateQsExpansionEnabled();
        }

        if ((diff2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
            mStatusBar.updateQsExpansionEnabled();
            if ((state2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
                mShadeController.animateCollapsePanels();
            }
        }
    }

    /**
     * Called for system navigation gestures. First action opens the panel, second opens
     * settings. Down action closes the entire panel.
     */
    @Override
    public void handleSystemKey(int key) {
        if (StatusBar.SPEW) {
            Log.d(StatusBar.TAG, "handleNavigationKey: " + key);
        }
        if (!mCommandQueue.panelsEnabled() || !mKeyguardUpdateMonitor.isDeviceInteractive()
                || mKeyguardStateController.isShowing() && !mKeyguardStateController.isOccluded()) {
            return;
        }

        // Panels are not available in setup
        if (!mDeviceProvisionedController.isCurrentUserSetup()) return;

        if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP == key) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_UP);
            mNotificationPanelViewController.collapse(
                    false /* delayed */, 1.0f /* speedUpFactor */);
        } else if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN == key) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_DOWN);
            if (mNotificationPanelViewController.isFullyCollapsed()) {
                if (mVibrateOnOpening) {
                    mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
                }
                mNotificationPanelViewController.expand(true /* animate */);
                mNotificationStackScrollLayoutController.setWillExpand(true);
                mHeadsUpManager.unpinAll(true /* userUnpinned */);
                mMetricsLogger.count(NotificationPanelView.COUNTER_PANEL_OPEN, 1);
            } else if (!mNotificationPanelViewController.isInSettings()
                    && !mNotificationPanelViewController.isExpanding()) {
                mNotificationPanelViewController.flingSettings(0 /* velocity */,
                        NotificationPanelView.FLING_EXPAND);
                mMetricsLogger.count(NotificationPanelView.COUNTER_PANEL_OPEN_QS, 1);
            }
        }

    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mStatusBar.setLastCameraLaunchSource(source);
        if (mStatusBar.isGoingToSleep()) {
            if (StatusBar.DEBUG_CAMERA_LIFT) {
                Slog.d(StatusBar.TAG, "Finish going to sleep before launching camera");
            }
            mStatusBar.setLaunchCameraOnFinishedGoingToSleep(true);
            return;
        }
        if (!mNotificationPanelViewController.canCameraGestureBeLaunched()) {
            if (StatusBar.DEBUG_CAMERA_LIFT) {
                Slog.d(StatusBar.TAG, "Can't launch camera right now");
            }
            return;
        }
        if (!mStatusBar.isDeviceInteractive()) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_CAMERA_LAUNCH,
                    "com.android.systemui:CAMERA_GESTURE");
        }
        vibrateForCameraGesture();

        if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) {
            Log.v(StatusBar.TAG, "Camera launch");
            mKeyguardUpdateMonitor.onCameraLaunched();
        }

        if (!mStatusBarKeyguardViewManager.isShowing()) {
            final Intent cameraIntent = CameraIntents.getInsecureCameraIntent(mContext);
            mStatusBar.startActivityDismissingKeyguard(cameraIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */);
        } else {
            if (!mStatusBar.isDeviceInteractive()) {
                // Avoid flickering of the scrim when we instant launch the camera and the bouncer
                // comes on.
                mStatusBar.acquireGestureWakeLock(StatusBar.LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
            }
            if (isWakingUpOrAwake()) {
                if (StatusBar.DEBUG_CAMERA_LIFT) {
                    Slog.d(StatusBar.TAG, "Launching camera");
                }
                if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                    mStatusBarKeyguardViewManager.reset(true /* hide */);
                }
                mNotificationPanelViewController.launchCamera(
                        mStatusBar.isDeviceInteractive() /* animate */, source);
                mStatusBar.updateScrimController();
            } else {
                // We need to defer the camera launch until the screen comes on, since otherwise
                // we will dismiss us too early since we are waiting on an activity to be drawn and
                // incorrectly get notified because of the screen on event (which resumes and pauses
                // some activities)
                if (StatusBar.DEBUG_CAMERA_LIFT) {
                    Slog.d(StatusBar.TAG, "Deferring until screen turns on");
                }
                mStatusBar.setLaunchCameraOnFinishedWaking(true);
            }
        }
    }

    @Override
    public void onEmergencyActionLaunchGestureDetected() {
        Intent emergencyIntent = mStatusBar.getEmergencyActionIntent();

        if (emergencyIntent == null) {
            Log.wtf(StatusBar.TAG, "Couldn't find an app to process the emergency intent.");
            return;
        }

        if (isGoingToSleep()) {
            mStatusBar.setLaunchEmergencyActionOnFinishedGoingToSleep(true);
            return;
        }

        if (!mStatusBar.isDeviceInteractive()) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_GESTURE,
                    "com.android.systemui:EMERGENCY_GESTURE");
        }
        // TODO(b/169087248) Possibly add haptics here for emergency action. Currently disabled for
        // app-side haptic experimentation.

        if (!mStatusBarKeyguardViewManager.isShowing()) {
            mStatusBar.startActivityDismissingKeyguard(emergencyIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */);
            return;
        }

        if (!mStatusBar.isDeviceInteractive()) {
            // Avoid flickering of the scrim when we instant launch the camera and the bouncer
            // comes on.
            mStatusBar.acquireGestureWakeLock(StatusBar.LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
        }

        if (isWakingUpOrAwake()) {
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.reset(true /* hide */);
            }
            mContext.startActivityAsUser(emergencyIntent, UserHandle.CURRENT);
            return;
        }
        // We need to defer the emergency action launch until the screen comes on, since otherwise
        // we will dismiss us too early since we are waiting on an activity to be drawn and
        // incorrectly get notified because of the screen on event (which resumes and pauses
        // some activities)
        mStatusBar.setLaunchEmergencyActionOnFinishedWaking(true);
    }

    @Override
    public void onRecentsAnimationStateChanged(boolean running) {
        mStatusBar.setInteracting(StatusBarManager.WINDOW_NAVIGATION_BAR, running);
    }


    @Override
    public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, InsetsVisibilities requestedVisibilities, String packageName) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean barModeChanged = mStatusBar.setAppearance(appearance);

        mLightBarController.onStatusBarAppearanceChanged(appearanceRegions, barModeChanged,
                mStatusBar.getBarMode(), navbarColorManagedByIme);

        mStatusBar.updateBubblesVisibility();
        mStatusBarStateController.setSystemBarAttributes(
                appearance, behavior, requestedVisibilities, packageName);
    }

    @Override
    public void showTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_STATUS_BAR)) {
            return;
        }
        mStatusBar.showTransientUnchecked();
    }

    @Override
    public void toggleKeyboardShortcutsMenu(int deviceId) {
        mStatusBar.resendMessage(new StatusBar.KeyboardShortcutsMessage(deviceId));
    }

    @Override
    public void setTopAppHidesStatusBar(boolean topAppHidesStatusBar) {
        mStatusBar.setTopHidesStatusBar(topAppHidesStatusBar);
        if (!topAppHidesStatusBar && mStatusBar.getWereIconsJustHidden()) {
            // Immediately update the icon hidden state, since that should only apply if we're
            // staying fullscreen.
            mStatusBar.setWereIconsJustHidden(false);
            mCommandQueue.recomputeDisableFlags(mDisplayId, true);
        }
        mStatusBar.updateHideIconsForBouncer(true /* animate */);
    }

    @Override
    public void setWindowState(
            int displayId, @StatusBarManager.WindowType int window,
            @StatusBarManager.WindowVisibleState int state) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean showing = state == WINDOW_STATE_SHOWING;
        if (mNotificationShadeWindowView != null
                && window == StatusBarManager.WINDOW_STATUS_BAR
                && !mStatusBar.isSameStatusBarState(state)) {
            mStatusBar.setWindowState(state);
            if (StatusBar.DEBUG_WINDOW_STATE) {
                Log.d(StatusBar.TAG, "Status bar " + windowStateToString(state));
            }
            if (mStatusBar.getStatusBarView() != null) {
                if (!showing && mStatusBarStateController.getState() == StatusBarState.SHADE) {
                    mStatusBar.getStatusBarView().collapsePanel(
                            false /* animate */, false /* delayed */, 1.0f /* speedUpFactor */);
                }

                mStatusBar.updateHideIconsForBouncer(false /* animate */);
            }
        }

        mStatusBar.updateBubblesVisibility();
    }

    @Override
    public void showAssistDisclosure() {
        mAssistManager.showDisclosure();
    }

    @Override
    public void showPinningEnterExitToast(boolean entering) {
        if (mStatusBar.getNavigationBarView() != null) {
            mStatusBar.getNavigationBarView().showPinningEnterExitToast(entering);
        }
    }

    @Override
    public void showPinningEscapeToast() {
        if (mStatusBar.getNavigationBarView() != null) {
            mStatusBar.getNavigationBarView().showPinningEscapeToast();
        }
    }

    @Override
    public void showScreenPinningRequest(int taskId) {
        if (mKeyguardStateController.isShowing()) {
            // Don't allow apps to trigger this from keyguard.
            return;
        }
        // Show screen pinning request, since this comes from an app, show 'no thanks', button.
        mStatusBar.showScreenPinningRequest(taskId, true);
    }

    @Override
    public void showWirelessChargingAnimation(int batteryLevel) {
        mStatusBar.showWirelessChargingAnimation(batteryLevel);
    }

    @Override
    public void startAssist(Bundle args) {
        mAssistManager.startAssist(args);
    }

    @Override
    public void suppressAmbientDisplay(boolean suppressed) {
        mDozeServiceHost.setDozeSuppressed(suppressed);
    }

    @Override
    public void togglePanel() {
        if (mStatusBar.isPanelExpanded()) {
            mShadeController.animateCollapsePanels();
        } else {
            animateExpandNotificationsPanel();
        }
    }

    @Override
    public void toggleSplitScreen() {
        mStatusBar.toggleSplitScreenMode(-1 /* metricsDockAction */, -1 /* metricsUndockAction */);
    }

    private boolean isGoingToSleep() {
        return mWakefulnessLifecycle.getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;
    }

    private boolean isWakingUpOrAwake() {
        return mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_AWAKE
                || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_WAKING;
    }

    private void vibrateForCameraGesture() {
        // Make sure to pass -1 for repeat so VibratorService doesn't stop us when going to sleep.
        mVibratorOptional.ifPresent(
                v -> v.vibrate(mCameraLaunchGestureVibrationEffect, VIBRATION_ATTRIBUTES));
    }

    private static VibrationEffect getCameraGestureVibrationEffect(
            Optional<Vibrator> vibratorOptional, Resources resources) {
        if (vibratorOptional.isPresent() && vibratorOptional.get().areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            return VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 50)
                    .compose();
        }
        if (vibratorOptional.isPresent() && vibratorOptional.get().hasAmplitudeControl()) {
            return VibrationEffect.createWaveform(
                    StatusBar.CAMERA_LAUNCH_GESTURE_VIBRATION_TIMINGS,
                    StatusBar.CAMERA_LAUNCH_GESTURE_VIBRATION_AMPLITUDES,
                    /* repeat= */ -1);
        }

        int[] pattern = resources.getIntArray(R.array.config_cameraLaunchGestureVibePattern);
        long[] timings = new long[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            timings[i] = pattern[i];
        }
        return VibrationEffect.createWaveform(timings, /* repeat= */ -1);
    }
}
