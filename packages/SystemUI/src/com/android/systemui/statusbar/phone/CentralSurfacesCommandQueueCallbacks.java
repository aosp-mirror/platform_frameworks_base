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

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;

import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Slog;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.camera.CameraIntents;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.CameraLauncher;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;

import dagger.Lazy;

import java.util.Arrays;
import java.util.Optional;

import javax.inject.Inject;

/** */
@SysUISingleton
public class CentralSurfacesCommandQueueCallbacks implements CommandQueue.Callbacks {
    private final CentralSurfaces mCentralSurfaces;
    private final Context mContext;
    private final ScreenPinningRequest mScreenPinningRequest;
    private final com.android.systemui.shade.ShadeController mShadeController;
    private final CommandQueue mCommandQueue;
    private final ShadeViewController mShadeViewController;
    private final PanelExpansionInteractor mPanelExpansionInteractor;
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
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    private final PowerManager mPowerManager;
    private final Optional<Vibrator> mVibratorOptional;
    private final DisableFlagsLogger mDisableFlagsLogger;
    private final int mDisplayId;
    private final UserTracker mUserTracker;
    private final boolean mVibrateOnOpening;
    private final VibrationEffect mCameraLaunchGestureVibrationEffect;
    private final ActivityStarter mActivityStarter;
    private final Lazy<CameraLauncher> mCameraLauncherLazy;
    private final QuickSettingsController mQsController;
    private final QSHost mQSHost;
    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    private int mDisabled1;
    private int mDisabled2;

    @Inject
    CentralSurfacesCommandQueueCallbacks(
            CentralSurfaces centralSurfaces,
            QuickSettingsController quickSettingsController,
            Context context,
            @Main Resources resources,
            ScreenPinningRequest screenPinningRequest,
            ShadeController shadeController,
            CommandQueue commandQueue,
            ShadeViewController shadeViewController,
            PanelExpansionInteractor panelExpansionInteractor,
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
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            PowerManager powerManager,
            Optional<Vibrator> vibratorOptional,
            DisableFlagsLogger disableFlagsLogger,
            @DisplayId int displayId,
            Lazy<CameraLauncher> cameraLauncherLazy,
            UserTracker userTracker,
            QSHost qsHost,
            ActivityStarter activityStarter) {
        mCentralSurfaces = centralSurfaces;
        mQsController = quickSettingsController;
        mContext = context;
        mScreenPinningRequest = screenPinningRequest;
        mShadeController = shadeController;
        mCommandQueue = commandQueue;
        mShadeViewController = shadeViewController;
        mPanelExpansionInteractor = panelExpansionInteractor;
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
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mStatusBarHideIconsForBouncerManager = statusBarHideIconsForBouncerManager;
        mPowerManager = powerManager;
        mVibratorOptional = vibratorOptional;
        mDisableFlagsLogger = disableFlagsLogger;
        mDisplayId = displayId;
        mCameraLauncherLazy = cameraLauncherLazy;
        mUserTracker = userTracker;
        mQSHost = qsHost;

        mVibrateOnOpening = resources.getBoolean(R.bool.config_vibrateOnIconAnimation);
        mCameraLaunchGestureVibrationEffect = getCameraGestureVibrationEffect(
                mVibratorOptional, resources);
        mActivityStarter = activityStarter;
    }

    @Override
    public void addQsTile(ComponentName tile) {
        mQSHost.addTile(tile);
    }

    @Override
    public void addQsTileToFrontOrEnd(ComponentName tile, boolean end) {
        mQSHost.addTile(tile, end);
    }

    @Override
    public void remQsTile(ComponentName tile) {
        mQSHost.removeTileByUser(tile);
    }

    @Override
    public void setQsTiles(String[] tiles) {
        mQSHost.changeTilesByUser(mQSHost.getSpecs(), Arrays.stream(tiles).toList());
    }

    @Override
    public void clickTile(ComponentName tile) {
        // Can't inject this because it changes with the QS fragment
        QSPanelController qsPanelController = mCentralSurfaces.getQSPanelController();
        if (qsPanelController != null) {
            qsPanelController.clickTile(tile);
        }
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        mShadeController.animateCollapseShade(flags, force, false /* delayed */,
                1.0f /* speedUpFactor */);
    }

    @Override
    public void animateExpandNotificationsPanel() {
        if (CentralSurfaces.SPEW) {
            Log.d(CentralSurfaces.TAG,
                    "animateExpand: mExpandedVisible=" + mShadeController.isExpandedVisible());
        }
        mShadeController.animateExpandShade();
    }

    @Override
    public void animateExpandSettingsPanel(@Nullable String subPanel) {
        if (CentralSurfaces.SPEW) {
            Log.d(CentralSurfaces.TAG,
                    "animateExpand: mExpandedVisible=" + mShadeController.isExpandedVisible());
        }
        mShadeController.animateExpandQs();
    }

    @Override
    public void appTransitionCancelled(int displayId) {
    }

    @Override
    public void appTransitionFinished(int displayId) {
    }

    @Override
    public void dismissKeyboardShortcutsMenu() {
        mCentralSurfaces.resendMessage(CentralSurfaces.MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU);
    }
    /**
     * State is one or more of the DISABLE constants from StatusBarManager.
     *
     * @deprecated If you need to react to changes in disable flags, listen to
     * {@link com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository}
     * instead.
     */
    @Override
    @Deprecated
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != mDisplayId) {
            return;
        }

        final int old1 = mDisabled1;
        final int diff1 = state1 ^ old1;
        mDisabled1 = state1;

        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);
        final int old2 = mDisabled2;
        final int diff2 = state2 ^ old2;
        mDisabled2 = state2;

        if ((diff1 & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state1 & StatusBarManager.DISABLE_EXPAND) != 0) {
                mShadeController.animateCollapseShade();
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) {
            if ((state1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) {
                mHeadsUpManager.releaseAllImmediately();
            }
        }

        if ((diff2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
            if ((state2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
                mShadeController.animateCollapseShade();
            }
        }

        mShadeViewController.disableHeader(state1, state2, animate);
    }

    /**
     * Called for system navigation gestures. First action opens the panel, second opens
     * settings. Down action closes the entire panel.
     */
    @Override
    public void handleSystemKey(KeyEvent key) {
        if (CentralSurfaces.SPEW) {
            Log.d(CentralSurfaces.TAG, "handleNavigationKey: " + key);
        }
        if (!mCommandQueue.panelsEnabled() || !mKeyguardUpdateMonitor.isDeviceInteractive()
                || mKeyguardStateController.isShowing() && !mKeyguardStateController.isOccluded()) {
            return;
        }

        // Panels are not available in setup
        if (!mDeviceProvisionedController.isCurrentUserSetup()) return;

        if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP == key.getKeyCode()) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_UP);
            mShadeController.animateCollapseShade();
        } else if (KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN == key.getKeyCode()) {
            mMetricsLogger.action(MetricsEvent.ACTION_SYSTEM_NAVIGATION_KEY_DOWN);
            if (mPanelExpansionInteractor.isFullyCollapsed()) {
                if (mVibrateOnOpening) {
                    vibrateOnNavigationKeyDown();
                }
                mShadeController.animateExpandShade();
                mNotificationStackScrollLayoutController.setWillExpand(true);
                mHeadsUpManager.unpinAll(true /* userUnpinned */);
                mMetricsLogger.count("panel_open", 1);
            } else if (!mQsController.getExpanded()
                    && !mShadeController.isExpandingOrCollapsing()) {
                mShadeController.animateExpandQs();
                mMetricsLogger.count("panel_open_qs", 1);
            }
        }

    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mCentralSurfaces.setLastCameraLaunchSource(source);
        if (mCentralSurfaces.isGoingToSleep()) {
            if (CentralSurfaces.DEBUG_CAMERA_LIFT) {
                Slog.d(CentralSurfaces.TAG, "Finish going to sleep before launching camera");
            }
            mCentralSurfaces.setLaunchCameraOnFinishedGoingToSleep(true);
            return;
        }
        if (!mCameraLauncherLazy.get().canCameraGestureBeLaunched(
                mShadeViewController.getBarState())) {
            if (CentralSurfaces.DEBUG_CAMERA_LIFT) {
                Slog.d(CentralSurfaces.TAG, "Can't launch camera right now");
            }
            return;
        }
        if (!mCentralSurfaces.isDeviceInteractive()) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_CAMERA_LAUNCH,
                    "com.android.systemui:CAMERA_GESTURE");
        }
        vibrateForCameraGesture();

        if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) {
            Log.v(CentralSurfaces.TAG, "Camera launch");
            mKeyguardUpdateMonitor.onCameraLaunched();
        }

        if (!mKeyguardStateController.isShowing()) {
            final Intent cameraIntent = CameraIntents.getInsecureCameraIntent(mContext, mUserTracker.getUserId());
            cameraIntent.putExtra(CameraIntents.EXTRA_LAUNCH_SOURCE, source);
            mActivityStarter.startActivityDismissingKeyguard(cameraIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */, mUserTracker.getUserHandle());
        } else {
            if (!mCentralSurfaces.isDeviceInteractive()) {
                // Avoid flickering of the scrim when we instant launch the camera and the bouncer
                // comes on.
                mCentralSurfaces.acquireGestureWakeLock(
                        CentralSurfaces.LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
            }
            if (isWakingUpOrAwake()) {
                if (CentralSurfaces.DEBUG_CAMERA_LIFT) {
                    Slog.d(CentralSurfaces.TAG, "Launching camera");
                }
                if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                    mStatusBarKeyguardViewManager.reset(true /* hide */);
                }
                mCameraLauncherLazy.get().launchCamera(source,
                        mPanelExpansionInteractor.isFullyCollapsed());
                mCentralSurfaces.updateScrimController();
            } else {
                // We need to defer the camera launch until the screen comes on, since otherwise
                // we will dismiss us too early since we are waiting on an activity to be drawn and
                // incorrectly get notified because of the screen on event (which resumes and pauses
                // some activities)
                if (CentralSurfaces.DEBUG_CAMERA_LIFT) {
                    Slog.d(CentralSurfaces.TAG, "Deferring until screen turns on");
                }
                mCentralSurfaces.setLaunchCameraOnFinishedWaking(true);
            }
        }
    }

    @Override
    public void onEmergencyActionLaunchGestureDetected() {
        Intent emergencyIntent = mCentralSurfaces.getEmergencyActionIntent();

        if (emergencyIntent == null) {
            Log.wtf(CentralSurfaces.TAG, "Couldn't find an app to process the emergency intent.");
            return;
        }

        if (isGoingToSleep()) {
            mCentralSurfaces.setLaunchEmergencyActionOnFinishedGoingToSleep(true);
            return;
        }

        if (!mCentralSurfaces.isDeviceInteractive()) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_GESTURE,
                    "com.android.systemui:EMERGENCY_GESTURE");
        }
        // TODO(b/169087248) Possibly add haptics here for emergency action. Currently disabled for
        // app-side haptic experimentation.

        if (!mKeyguardStateController.isShowing()) {
            mActivityStarter.startActivityDismissingKeyguard(emergencyIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */, mUserTracker.getUserHandle());
            return;
        }

        if (!mCentralSurfaces.isDeviceInteractive()) {
            // Avoid flickering of the scrim when we instant launch the camera and the bouncer
            // comes on.
            mCentralSurfaces.acquireGestureWakeLock(
                    CentralSurfaces.LAUNCH_TRANSITION_TIMEOUT_MS + 1000L);
        }

        if (isWakingUpOrAwake()) {
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.reset(true /* hide */);
            }
            mContext.startActivityAsUser(emergencyIntent, mUserTracker.getUserHandle());
            return;
        }
        // We need to defer the emergency action launch until the screen comes on, since otherwise
        // we will dismiss us too early since we are waiting on an activity to be drawn and
        // incorrectly get notified because of the screen on event (which resumes and pauses
        // some activities)
        mCentralSurfaces.setLaunchEmergencyActionOnFinishedWaking(true);
    }

    @Override
    public void onRecentsAnimationStateChanged(boolean running) {
        mCentralSurfaces.setInteracting(StatusBarManager.WINDOW_NAVIGATION_BAR, running);
    }

    @Override
    public void toggleKeyboardShortcutsMenu(int deviceId) {
        mCentralSurfaces.resendMessage(new CentralSurfaces.KeyboardShortcutsMessage(deviceId));
    }

    @Override
    public void setTopAppHidesStatusBar(boolean topAppHidesStatusBar) {
        mStatusBarHideIconsForBouncerManager
                .setTopAppHidesStatusBarAndTriggerUpdate(topAppHidesStatusBar);
    }

    @Override
    public void showAssistDisclosure() {
        mAssistManager.showDisclosure();
    }

    @Override
    public void showScreenPinningRequest(int taskId) {
        if (mKeyguardStateController.isShowing()) {
            // Don't allow apps to trigger this from keyguard.
            return;
        }
        // Show screen pinning request, since this comes from an app, show 'no thanks', button.
        mScreenPinningRequest.showPrompt(taskId, true);
    }

    @Override
    public void showWirelessChargingAnimation(int batteryLevel) {
        mCentralSurfaces.showWirelessChargingAnimation(batteryLevel);
    }

    @Override
    public void startAssist(Bundle args) {
        mAssistManager.startAssist(args);
    }

    @Override
    public void suppressAmbientDisplay(boolean suppressed) {
        mDozeServiceHost.setAlwaysOnSuppressed(suppressed);
    }

    @Override
    public void togglePanel() {
        if (mShadeViewController.isPanelExpanded()) {
            mShadeController.animateCollapseShade();
        } else {
            mShadeController.animateExpandShade();
        }
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
        mVibratorOptional.ifPresent(
                v -> v.vibrate(mCameraLaunchGestureVibrationEffect,
                        HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES));
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
            // Make sure to pass -1 for repeat so VibratorManagerService doesn't stop us when going
            // to sleep.
            return VibrationEffect.createWaveform(
                    CentralSurfaces.CAMERA_LAUNCH_GESTURE_VIBRATION_TIMINGS,
                    CentralSurfaces.CAMERA_LAUNCH_GESTURE_VIBRATION_AMPLITUDES,
                    /* repeat= */ -1);
        }

        int[] pattern = resources.getIntArray(R.array.config_cameraLaunchGestureVibePattern);
        long[] timings = new long[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            timings[i] = pattern[i];
        }
        return VibrationEffect.createWaveform(timings, /* repeat= */ -1);
    }

    @VisibleForTesting
    void vibrateOnNavigationKeyDown() {
        mShadeViewController.performHapticFeedback(
                HapticFeedbackConstants.GESTURE_START
        );
    }
}
