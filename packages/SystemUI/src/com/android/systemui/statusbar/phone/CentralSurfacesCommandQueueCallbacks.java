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
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationAttributes;
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
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.view.AppearanceRegion;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.camera.CameraIntents;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.shade.CameraLauncher;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.DisableFlagsLogger;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;

import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;

/** */
@CentralSurfacesComponent.CentralSurfacesScope
public class CentralSurfacesCommandQueueCallbacks implements CommandQueue.Callbacks {
    private final CentralSurfaces mCentralSurfaces;
    private final Context mContext;
    private final com.android.systemui.shade.ShadeController mShadeController;
    private final CommandQueue mCommandQueue;
    private final NotificationPanelViewController mNotificationPanelViewController;
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
    private final VibratorHelper mVibratorHelper;
    private final Optional<Vibrator> mVibratorOptional;
    private final DisableFlagsLogger mDisableFlagsLogger;
    private final int mDisplayId;
    private final boolean mVibrateOnOpening;
    private final VibrationEffect mCameraLaunchGestureVibrationEffect;
    private final SystemBarAttributesListener mSystemBarAttributesListener;
    private final Lazy<CameraLauncher> mCameraLauncherLazy;

    private static final VibrationAttributes HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    @Inject
    CentralSurfacesCommandQueueCallbacks(
            CentralSurfaces centralSurfaces,
            Context context,
            @Main Resources resources,
            ShadeController shadeController,
            CommandQueue commandQueue,
            NotificationPanelViewController notificationPanelViewController,
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
            VibratorHelper vibratorHelper,
            Optional<Vibrator> vibratorOptional,
            DisableFlagsLogger disableFlagsLogger,
            @DisplayId int displayId,
            SystemBarAttributesListener systemBarAttributesListener,
            Lazy<CameraLauncher> cameraLauncherLazy) {
        mCentralSurfaces = centralSurfaces;
        mContext = context;
        mShadeController = shadeController;
        mCommandQueue = commandQueue;
        mNotificationPanelViewController = notificationPanelViewController;
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
        mVibratorHelper = vibratorHelper;
        mVibratorOptional = vibratorOptional;
        mDisableFlagsLogger = disableFlagsLogger;
        mDisplayId = displayId;
        mCameraLauncherLazy = cameraLauncherLazy;

        mVibrateOnOpening = resources.getBoolean(R.bool.config_vibrateOnIconAnimation);
        mCameraLaunchGestureVibrationEffect = getCameraGestureVibrationEffect(
                mVibratorOptional, resources);
        mSystemBarAttributesListener = systemBarAttributesListener;
    }

    @Override
    public void abortTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_STATUS_BAR)) {
            return;
        }
        mCentralSurfaces.clearTransient();
    }

    @Override
    public void addQsTile(ComponentName tile) {
        QSPanelController qsPanelController = mCentralSurfaces.getQSPanelController();
        if (qsPanelController != null && qsPanelController.getHost() != null) {
            qsPanelController.getHost().addTile(tile);
        }
    }

    @Override
    public void remQsTile(ComponentName tile) {
        QSPanelController qsPanelController = mCentralSurfaces.getQSPanelController();
        if (qsPanelController != null && qsPanelController.getHost() != null) {
            qsPanelController.getHost().removeTileByUser(tile);
        }
    }

    @Override
    public void clickTile(ComponentName tile) {
        QSPanelController qsPanelController = mCentralSurfaces.getQSPanelController();
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
        if (CentralSurfaces.SPEW) {
            Log.d(CentralSurfaces.TAG,
                    "animateExpand: mExpandedVisible=" + mShadeController.isExpandedVisible());
        }
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }

        mNotificationPanelViewController.expandShadeToNotifications();
    }

    @Override
    public void animateExpandSettingsPanel(@Nullable String subPanel) {
        if (CentralSurfaces.SPEW) {
            Log.d(CentralSurfaces.TAG,
                    "animateExpand: mExpandedVisible=" + mShadeController.isExpandedVisible());
        }
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }

        // Settings are not available in setup
        if (!mDeviceProvisionedController.isCurrentUserSetup()) return;

        mNotificationPanelViewController.expandWithQs();
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
     */
    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != mDisplayId) {
            return;
        }

        int state2BeforeAdjustment = state2;
        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);
        Log.d(CentralSurfaces.TAG,
                mDisableFlagsLogger.getDisableFlagsString(
                        /* old= */ new DisableFlagsLogger.DisableState(
                                mCentralSurfaces.getDisabled1(), mCentralSurfaces.getDisabled2()),
                        /* new= */ new DisableFlagsLogger.DisableState(
                                state1, state2BeforeAdjustment),
                        /* newStateAfterLocalModification= */ new DisableFlagsLogger.DisableState(
                                state1, state2)));

        final int old1 = mCentralSurfaces.getDisabled1();
        final int diff1 = state1 ^ old1;
        mCentralSurfaces.setDisabled1(state1);

        final int old2 = mCentralSurfaces.getDisabled2();
        final int diff2 = state2 ^ old2;
        mCentralSurfaces.setDisabled2(state2);

        if ((diff1 & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((state1 & StatusBarManager.DISABLE_EXPAND) != 0) {
                mShadeController.animateCollapseShade();
            }
        }

        if ((diff1 & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0) {
            if (mCentralSurfaces.areNotificationAlertsDisabled()) {
                mHeadsUpManager.releaseAllImmediately();
            }
        }

        if ((diff2 & StatusBarManager.DISABLE2_QUICK_SETTINGS) != 0) {
            mCentralSurfaces.updateQsExpansionEnabled();
        }

        if ((diff2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
            mCentralSurfaces.updateQsExpansionEnabled();
            if ((state2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) != 0) {
                mShadeController.animateCollapseShade();
            }
        }

        mNotificationPanelViewController.disable(state1, state2, animate);
    }

    /**
     * Called for system navigation gestures. First action opens the panel, second opens
     * settings. Down action closes the entire panel.
     */
    @Override
    public void handleSystemKey(int key) {
        if (CentralSurfaces.SPEW) {
            Log.d(CentralSurfaces.TAG, "handleNavigationKey: " + key);
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
                mMetricsLogger.count("panel_open", 1);
            } else if (!mNotificationPanelViewController.isInSettings()
                    && !mNotificationPanelViewController.isExpanding()) {
                mNotificationPanelViewController.flingSettings(0 /* velocity */,
                        NotificationPanelViewController.FLING_EXPAND);
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
                mNotificationPanelViewController.getBarState())) {
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
            final Intent cameraIntent = CameraIntents.getInsecureCameraIntent(mContext);
            cameraIntent.putExtra(CameraIntents.EXTRA_LAUNCH_SOURCE, source);
            mCentralSurfaces.startActivityDismissingKeyguard(cameraIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */, UserHandle.CURRENT);
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
                        mNotificationPanelViewController.isFullyCollapsed());
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
            mCentralSurfaces.startActivityDismissingKeyguard(emergencyIntent,
                    false /* onlyProvisioned */, true /* dismissShade */,
                    true /* disallowEnterPictureInPictureWhileLaunching */, null /* callback */, 0,
                    null /* animationController */, UserHandle.CURRENT);
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
            mContext.startActivityAsUser(emergencyIntent, UserHandle.CURRENT);
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
    public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, InsetsVisibilities requestedVisibilities, String packageName,
            LetterboxDetails[] letterboxDetails) {
        if (displayId != mDisplayId) {
            return;
        }
        // SystemBarAttributesListener should __always__ be the top-level listener for system bar
        // attributes changed.
        mSystemBarAttributesListener.onSystemBarAttributesChanged(
                displayId,
                appearance,
                appearanceRegions,
                navbarColorManagedByIme,
                behavior,
                requestedVisibilities,
                packageName,
                letterboxDetails
        );
    }

    @Override
    public void showTransient(int displayId, @InternalInsetsType int[] types,
            boolean isGestureOnSystemBar) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_STATUS_BAR)) {
            return;
        }
        mCentralSurfaces.showTransientUnchecked();
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
    public void showPinningEnterExitToast(boolean entering) {
        mCentralSurfaces.showPinningEnterExitToast(entering);
    }

    @Override
    public void showPinningEscapeToast() {
        mCentralSurfaces.showPinningEscapeToast();
    }

    @Override
    public void showScreenPinningRequest(int taskId) {
        if (mKeyguardStateController.isShowing()) {
            // Don't allow apps to trigger this from keyguard.
            return;
        }
        // Show screen pinning request, since this comes from an app, show 'no thanks', button.
        mCentralSurfaces.showScreenPinningRequest(taskId, true);
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
        if (mCentralSurfaces.isPanelExpanded()) {
            mShadeController.animateCollapseShade();
        } else {
            animateExpandNotificationsPanel();
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
}
