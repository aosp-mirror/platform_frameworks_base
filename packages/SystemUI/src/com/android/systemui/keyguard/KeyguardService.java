/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_UNOCCLUDE;

import android.app.ActivityTaskManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardDrawnCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.systemui.SystemUIApplication;
import com.android.wm.shell.transition.Transitions;

import javax.inject.Inject;

public class KeyguardService extends Service {
    static final String TAG = "KeyguardService";
    static final String PERMISSION = android.Manifest.permission.CONTROL_KEYGUARD;

    /**
     * Run Keyguard animation as remote animation in System UI instead of local animation in
     * the server process.
     *
     * 0: Runs all keyguard animation as local animation
     * 1: Only runs keyguard going away animation as remote animation
     * 2: Runs all keyguard animation as remote animation
     *
     * Note: Must be consistent with WindowManagerService.
     */
    private static final String ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY =
            "persist.wm.enable_remote_keyguard_animation";

    private static final int sEnableRemoteKeyguardAnimation =
            SystemProperties.getInt(ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY, 0);

    /**
     * @see #ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY
     */
    public static boolean sEnableRemoteKeyguardGoingAwayAnimation =
            !Transitions.ENABLE_SHELL_TRANSITIONS && sEnableRemoteKeyguardAnimation >= 1;

    /**
     * @see #ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY
     */
    public static boolean sEnableRemoteKeyguardOccludeAnimation =
            !Transitions.ENABLE_SHELL_TRANSITIONS && sEnableRemoteKeyguardAnimation >= 2;

    private final KeyguardViewMediator mKeyguardViewMediator;
    private final KeyguardLifecyclesDispatcher mKeyguardLifecyclesDispatcher;

    @Inject
    public KeyguardService(KeyguardViewMediator keyguardViewMediator,
                           KeyguardLifecyclesDispatcher keyguardLifecyclesDispatcher) {
        super();
        mKeyguardViewMediator = keyguardViewMediator;
        mKeyguardLifecyclesDispatcher = keyguardLifecyclesDispatcher;

        RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        if (sEnableRemoteKeyguardGoingAwayAnimation) {
            final RemoteAnimationAdapter exitAnimationAdapter =
                    new RemoteAnimationAdapter(mExitAnimationRunner, 0, 0);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_GOING_AWAY, exitAnimationAdapter);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                    exitAnimationAdapter);
        }
        if (sEnableRemoteKeyguardOccludeAnimation) {
            final RemoteAnimationAdapter occludeAnimationAdapter =
                    new RemoteAnimationAdapter(mOccludeAnimationRunner, 0, 0);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_OCCLUDE, occludeAnimationAdapter);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_UNOCCLUDE, occludeAnimationAdapter);
        }
        ActivityTaskManager.getInstance().registerRemoteAnimationsForDisplay(
                DEFAULT_DISPLAY, definition);
    }

    @Override
    public void onCreate() {
        ((SystemUIApplication) getApplication()).startServicesIfNeeded();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    void checkPermission() {
        // Avoid deadlock by avoiding calling back into the system process.
        if (Binder.getCallingUid() == Process.SYSTEM_UID) return;

        // Otherwise,explicitly check for caller permission ...
        if (getBaseContext().checkCallingOrSelfPermission(PERMISSION) != PERMISSION_GRANTED) {
            Log.w(TAG, "Caller needs permission '" + PERMISSION + "' to call " + Debug.getCaller());
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + PERMISSION);
        }
    }

    private final IRemoteAnimationRunner.Stub mExitAnimationRunner =
            new IRemoteAnimationRunner.Stub() {
        @Override // Binder interface
        public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) {
            Trace.beginSection("KeyguardService.mBinder#startKeyguardExitAnimation");
            checkPermission();
            mKeyguardViewMediator.startKeyguardExitAnimation(transit, apps, wallpapers,
                    null /* nonApps */, finishedCallback);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onAnimationCancelled() {
            mKeyguardViewMediator.cancelKeyguardExitAnimation();
        }
    };

    private final IRemoteAnimationRunner.Stub mOccludeAnimationRunner =
            new IRemoteAnimationRunner.Stub() {
        @Override // Binder interface
        public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                       RemoteAnimationTarget[] apps,
                       RemoteAnimationTarget[] wallpapers,
                        RemoteAnimationTarget[] nonApps,
                        IRemoteAnimationFinishedCallback finishedCallback) {
            try {
                if (transit == TRANSIT_OLD_KEYGUARD_OCCLUDE) {
                    mBinder.setOccluded(true /* isOccluded */, true /* animate */);
                } else if (transit == TRANSIT_OLD_KEYGUARD_UNOCCLUDE) {
                    mBinder.setOccluded(false /* isOccluded */, true /* animate */);
                }
                // TODO(bc-unlock): Implement occlude/unocclude animation applied on apps,
                //  wallpapers and nonApps.
                finishedCallback.onAnimationFinished();
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException");
            }
        }

        @Override // Binder interface
        public void onAnimationCancelled() {
        }
    };

    private final IKeyguardService.Stub mBinder = new IKeyguardService.Stub() {

        @Override // Binder interface
        public void addStateMonitorCallback(IKeyguardStateCallback callback) {
            checkPermission();
            mKeyguardViewMediator.addStateMonitorCallback(callback);
        }

        @Override // Binder interface
        public void verifyUnlock(IKeyguardExitCallback callback) {
            Trace.beginSection("KeyguardService.mBinder#verifyUnlock");
            checkPermission();
            mKeyguardViewMediator.verifyUnlock(callback);
            Trace.endSection();
        }

        @Override // Binder interface
        public void setOccluded(boolean isOccluded, boolean animate) {
            Trace.beginSection("KeyguardService.mBinder#setOccluded");
            checkPermission();
            mKeyguardViewMediator.setOccluded(isOccluded, animate);
            Trace.endSection();
        }

        @Override // Binder interface
        public void dismiss(IKeyguardDismissCallback callback, CharSequence message) {
            checkPermission();
            mKeyguardViewMediator.dismiss(callback, message);
        }

        @Override // Binder interface
        public void onDreamingStarted() {
            checkPermission();
            mKeyguardViewMediator.onDreamingStarted();
        }

        @Override // Binder interface
        public void onDreamingStopped() {
            checkPermission();
            mKeyguardViewMediator.onDreamingStopped();
        }

        @Override // Binder interface
        public void onStartedGoingToSleep(@PowerManager.GoToSleepReason int pmSleepReason) {
            checkPermission();
            mKeyguardViewMediator.onStartedGoingToSleep(
                    WindowManagerPolicyConstants.translateSleepReasonToOffReason(pmSleepReason));
            mKeyguardLifecyclesDispatcher.dispatch(
                    KeyguardLifecyclesDispatcher.STARTED_GOING_TO_SLEEP, pmSleepReason);
        }

        @Override // Binder interface
        public void onFinishedGoingToSleep(
                @PowerManager.GoToSleepReason int pmSleepReason, boolean cameraGestureTriggered) {
            checkPermission();
            mKeyguardViewMediator.onFinishedGoingToSleep(
                    WindowManagerPolicyConstants.translateSleepReasonToOffReason(pmSleepReason),
                    cameraGestureTriggered);
            mKeyguardLifecyclesDispatcher.dispatch(
                    KeyguardLifecyclesDispatcher.FINISHED_GOING_TO_SLEEP);
        }

        @Override // Binder interface
        public void onStartedWakingUp(
                @PowerManager.WakeReason int pmWakeReason, boolean cameraGestureTriggered) {
            Trace.beginSection("KeyguardService.mBinder#onStartedWakingUp");
            checkPermission();
            mKeyguardViewMediator.onStartedWakingUp(cameraGestureTriggered);
            mKeyguardLifecyclesDispatcher.dispatch(
                    KeyguardLifecyclesDispatcher.STARTED_WAKING_UP, pmWakeReason);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onFinishedWakingUp() {
            Trace.beginSection("KeyguardService.mBinder#onFinishedWakingUp");
            checkPermission();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.FINISHED_WAKING_UP);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onScreenTurningOn(IKeyguardDrawnCallback callback) {
            Trace.beginSection("KeyguardService.mBinder#onScreenTurningOn");
            checkPermission();
            mKeyguardViewMediator.onScreenTurningOn(callback);
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNING_ON);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onScreenTurnedOn() {
            Trace.beginSection("KeyguardService.mBinder#onScreenTurnedOn");
            checkPermission();
            mKeyguardViewMediator.onScreenTurnedOn();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNED_ON);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onScreenTurningOff() {
            checkPermission();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNING_OFF);
        }

        @Override // Binder interface
        public void onScreenTurnedOff() {
            checkPermission();
            mKeyguardViewMediator.onScreenTurnedOff();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNED_OFF);
        }

        @Override // Binder interface
        public void setKeyguardEnabled(boolean enabled) {
            checkPermission();
            mKeyguardViewMediator.setKeyguardEnabled(enabled);
        }

        @Override // Binder interface
        public void onSystemReady() {
            Trace.beginSection("KeyguardService.mBinder#onSystemReady");
            checkPermission();
            mKeyguardViewMediator.onSystemReady();
            Trace.endSection();
        }

        @Override // Binder interface
        public void doKeyguardTimeout(Bundle options) {
            checkPermission();
            mKeyguardViewMediator.doKeyguardTimeout(options);
        }

        @Override // Binder interface
        public void setSwitchingUser(boolean switching) {
            checkPermission();
            mKeyguardViewMediator.setSwitchingUser(switching);
        }

        @Override // Binder interface
        public void setCurrentUser(int userId) {
            checkPermission();
            mKeyguardViewMediator.setCurrentUser(userId);
        }

        @Override
        public void onBootCompleted() {
            checkPermission();
            mKeyguardViewMediator.onBootCompleted();
        }

        /**
         * @deprecated When remote animation is enabled, this won't be called anymore. Use
         * {@code IRemoteAnimationRunner#onAnimationStart} instead.
         */
        @Deprecated
        @Override
        public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
            Trace.beginSection("KeyguardService.mBinder#startKeyguardExitAnimation");
            checkPermission();
            mKeyguardViewMediator.startKeyguardExitAnimation(startTime, fadeoutDuration);
            Trace.endSection();
        }

        @Override
        public void onShortPowerPressedGoHome() {
            checkPermission();
            mKeyguardViewMediator.onShortPowerPressedGoHome();
        }
    };
}

