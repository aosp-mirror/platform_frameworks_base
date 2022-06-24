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
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.TransitionFlags;
import static android.view.WindowManager.TransitionOldType;
import static android.view.WindowManager.TransitionType;
import static android.window.TransitionInfo.FLAG_OCCLUDES_KEYGUARD;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.RemoteTransition;
import android.window.TransitionFilter;
import android.window.TransitionInfo;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardDrawnCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.systemui.SystemUIApplication;
import com.android.wm.shell.transition.ShellTransitions;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;

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
            SystemProperties.getInt(ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY, 2);

    /**
     * @see #ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY
     */
    public static boolean sEnableRemoteKeyguardGoingAwayAnimation =
            sEnableRemoteKeyguardAnimation >= 1;

    /**
     * @see #ENABLE_REMOTE_KEYGUARD_ANIMATION_PROPERTY
     */
    public static boolean sEnableRemoteKeyguardOccludeAnimation =
            sEnableRemoteKeyguardAnimation >= 2;

    private final KeyguardViewMediator mKeyguardViewMediator;
    private final KeyguardLifecyclesDispatcher mKeyguardLifecyclesDispatcher;
    private final ShellTransitions mShellTransitions;

    private static int newModeToLegacyMode(int newMode) {
        switch (newMode) {
            case WindowManager.TRANSIT_OPEN:
            case WindowManager.TRANSIT_TO_FRONT:
                return MODE_OPENING;
            case WindowManager.TRANSIT_CLOSE:
            case WindowManager.TRANSIT_TO_BACK:
                return MODE_CLOSING;
            default:
                return 2; // MODE_CHANGING
        }
    }

    private static RemoteAnimationTarget[] wrap(TransitionInfo info, boolean wallpapers) {
        final ArrayList<RemoteAnimationTarget> out = new ArrayList<>();
        for (int i = 0; i < info.getChanges().size(); i++) {
            boolean changeIsWallpaper =
                    (info.getChanges().get(i).getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0;
            if (wallpapers != changeIsWallpaper) continue;

            final TransitionInfo.Change change = info.getChanges().get(i);
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            final int taskId = taskInfo != null ? change.getTaskInfo().taskId : -1;
            boolean isNotInRecents;
            WindowConfiguration windowConfiguration = null;
            if (taskInfo != null) {
                if (taskInfo.getConfiguration() != null) {
                    windowConfiguration =
                            change.getTaskInfo().getConfiguration().windowConfiguration;
                }
                isNotInRecents = !change.getTaskInfo().isRunning;
            } else {
                isNotInRecents = true;
            }
            Rect localBounds = new Rect(change.getEndAbsBounds());
            localBounds.offsetTo(change.getEndRelOffset().x, change.getEndRelOffset().y);

            final RemoteAnimationTarget target = new RemoteAnimationTarget(
                    taskId,
                    newModeToLegacyMode(change.getMode()),
                    change.getLeash(),
                    (change.getFlags() & TransitionInfo.FLAG_TRANSLUCENT) != 0
                            || (change.getFlags() & TransitionInfo.FLAG_SHOW_WALLPAPER) != 0,
                    null /* clipRect */,
                    new Rect(0, 0, 0, 0) /* contentInsets */,
                    info.getChanges().size() - i,
                    new Point(), localBounds, new Rect(change.getEndAbsBounds()),
                    windowConfiguration, isNotInRecents, null /* startLeash */,
                    change.getStartAbsBounds(), taskInfo, false /* allowEnterPip */);
            // Use hasAnimatingParent to mark the anything below root task
            if (taskId != -1 && change.getParent() != null) {
                final TransitionInfo.Change parentChange = info.getChange(change.getParent());
                if (parentChange != null && parentChange.getTaskInfo() != null) {
                    target.hasAnimatingParent = true;
                }
            }
            out.add(target);
        }
        return out.toArray(new RemoteAnimationTarget[out.size()]);
    }

    private static @TransitionOldType int getTransitionOldType(@TransitionType int type,
            @TransitionFlags int flags, RemoteAnimationTarget[] apps) {
        if (type == TRANSIT_KEYGUARD_GOING_AWAY
                || (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY) != 0) {
            return apps.length == 0 ? TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER
                    : TRANSIT_OLD_KEYGUARD_GOING_AWAY;
        } else if (type == TRANSIT_KEYGUARD_OCCLUDE) {
            return TRANSIT_OLD_KEYGUARD_OCCLUDE;
        } else if (type == TRANSIT_KEYGUARD_UNOCCLUDE) {
            return TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
        } else {
            Slog.d(TAG, "Unexpected transit type: " + type);
            return TRANSIT_OLD_NONE;
        }
    }

    // Wrap Keyguard going away animation
    private static IRemoteTransition wrap(IRemoteAnimationRunner runner) {
        return new IRemoteTransition.Stub() {
            final ArrayMap<IBinder, IRemoteTransitionFinishedCallback> mFinishCallbacks =
                    new ArrayMap<>();

            @Override
            public void startAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t, IRemoteTransitionFinishedCallback finishCallback)
                    throws RemoteException {
                Slog.d(TAG, "Starts IRemoteAnimationRunner: info=" + info);
                final RemoteAnimationTarget[] apps = wrap(info, false /* wallpapers */);
                final RemoteAnimationTarget[] wallpapers = wrap(info, true /* wallpapers */);
                final RemoteAnimationTarget[] nonApps = new RemoteAnimationTarget[0];

                // Sets the alpha to 0 for the opening root task for fade in animation. And since
                // the fade in animation can only apply on the first opening app, so set alpha to 1
                // for anything else.
                boolean foundOpening = false;
                for (RemoteAnimationTarget target : apps) {
                    if (target.taskId != -1
                            && target.mode == RemoteAnimationTarget.MODE_OPENING
                            && !target.hasAnimatingParent) {
                        if (foundOpening) {
                            Log.w(TAG, "More than one opening target");
                            t.setAlpha(target.leash, 1.0f);
                            continue;
                        }
                        t.setAlpha(target.leash, 0.0f);
                        foundOpening = true;
                    } else {
                        t.setAlpha(target.leash, 1.0f);
                    }
                }
                t.apply();
                synchronized (mFinishCallbacks) {
                    mFinishCallbacks.put(transition, finishCallback);
                }
                runner.onAnimationStart(getTransitionOldType(info.getType(), info.getFlags(), apps),
                        apps, wallpapers, nonApps,
                        new IRemoteAnimationFinishedCallback.Stub() {
                            @Override
                            public void onAnimationFinished() throws RemoteException {
                                synchronized (mFinishCallbacks) {
                                    if (mFinishCallbacks.remove(transition) == null) return;
                                }
                                Slog.d(TAG, "Finish IRemoteAnimationRunner.");
                                finishCallback.onTransitionFinished(null /* wct */, null /* t */);
                            }
                        }
                );
            }

            public void mergeAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finishCallback) {
                try {
                    final IRemoteTransitionFinishedCallback origFinishCB;
                    synchronized (mFinishCallbacks) {
                        origFinishCB = mFinishCallbacks.remove(transition);
                    }
                    if (origFinishCB == null) {
                        // already finished (or not started yet), so do nothing.
                        return;
                    }
                    runner.onAnimationCancelled();
                    origFinishCB.onTransitionFinished(null /* wct */, null /* t */);
                } catch (RemoteException e) {
                    // nothing, we'll just let it finish on its own I guess.
                }
            }
        };
    }

    @Inject
    public KeyguardService(KeyguardViewMediator keyguardViewMediator,
                           KeyguardLifecyclesDispatcher keyguardLifecyclesDispatcher,
                           ShellTransitions shellTransitions) {
        super();
        mKeyguardViewMediator = keyguardViewMediator;
        mKeyguardLifecyclesDispatcher = keyguardLifecyclesDispatcher;
        mShellTransitions = shellTransitions;
    }

    @Override
    public void onCreate() {
        ((SystemUIApplication) getApplication()).startServicesIfNeeded();

        if (mShellTransitions == null || !Transitions.ENABLE_SHELL_TRANSITIONS) {
            RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
            if (sEnableRemoteKeyguardGoingAwayAnimation) {
                final RemoteAnimationAdapter exitAnimationAdapter =
                        new RemoteAnimationAdapter(mExitAnimationRunner, 0, 0);
                definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                        exitAnimationAdapter);
                definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                        exitAnimationAdapter);
            }
            if (sEnableRemoteKeyguardOccludeAnimation) {
                final RemoteAnimationAdapter occludeAnimationAdapter =
                        new RemoteAnimationAdapter(
                                mKeyguardViewMediator.getOccludeAnimationRunner(), 0, 0);
                definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_OCCLUDE,
                        occludeAnimationAdapter);

                final RemoteAnimationAdapter unoccludeAnimationAdapter =
                        new RemoteAnimationAdapter(
                                mKeyguardViewMediator.getUnoccludeAnimationRunner(), 0, 0);
                definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_UNOCCLUDE,
                        unoccludeAnimationAdapter);
            }
            ActivityTaskManager.getInstance().registerRemoteAnimationsForDisplay(
                    DEFAULT_DISPLAY, definition);
            return;
        }
        if (sEnableRemoteKeyguardGoingAwayAnimation) {
            Slog.d(TAG, "KeyguardService registerRemote: TRANSIT_KEYGUARD_GOING_AWAY");
            TransitionFilter f = new TransitionFilter();
            f.mFlags = TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
            mShellTransitions.registerRemote(f,
                    new RemoteTransition(wrap(mExitAnimationRunner), getIApplicationThread()));
        }
        if (sEnableRemoteKeyguardOccludeAnimation) {
            Slog.d(TAG, "KeyguardService registerRemote: TRANSIT_KEYGUARD_(UN)OCCLUDE");
            // Register for occluding
            TransitionFilter f = new TransitionFilter();
            f.mFlags = TRANSIT_FLAG_KEYGUARD_LOCKED;
            f.mRequirements = new TransitionFilter.Requirement[]{
                    new TransitionFilter.Requirement(), new TransitionFilter.Requirement()};
            // First require at-least one app showing that occludes.
            f.mRequirements[0].mMustBeIndependent = false;
            f.mRequirements[0].mFlags = FLAG_OCCLUDES_KEYGUARD;
            f.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
            // Then require that we aren't closing any occludes (because this would mean a
            // regular task->task or activity->activity animation not involving keyguard).
            f.mRequirements[1].mNot = true;
            f.mRequirements[1].mMustBeIndependent = false;
            f.mRequirements[1].mFlags = FLAG_OCCLUDES_KEYGUARD;
            f.mRequirements[1].mModes = new int[]{TRANSIT_CLOSE, TRANSIT_TO_BACK};
            mShellTransitions.registerRemote(f,
                    new RemoteTransition(mOccludeAnimation, getIApplicationThread()));

            // Now register for un-occlude.
            f = new TransitionFilter();
            f.mFlags = TRANSIT_FLAG_KEYGUARD_LOCKED;
            f.mRequirements = new TransitionFilter.Requirement[]{
                    new TransitionFilter.Requirement(), new TransitionFilter.Requirement()};
            // First require at-least one app going-away (doesn't need occlude flag
            // as that is implicit by it having been visible and we don't want to exclude
            // cases where we are un-occluding because the app removed its showWhenLocked
            // capability at runtime).
            f.mRequirements[1].mMustBeIndependent = false;
            f.mRequirements[1].mModes = new int[]{TRANSIT_CLOSE, TRANSIT_TO_BACK};
            f.mRequirements[1].mMustBeTask = true;
            // Then require that we aren't opening any occludes (otherwise we'd remain
            // occluded).
            f.mRequirements[0].mNot = true;
            f.mRequirements[0].mMustBeIndependent = false;
            f.mRequirements[0].mFlags = FLAG_OCCLUDES_KEYGUARD;
            f.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
            mShellTransitions.registerRemote(f,
                    new RemoteTransition(mUnoccludeAnimation, getIApplicationThread()));
        }
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
            Trace.beginSection("mExitAnimationRunner.onAnimationStart#startKeyguardExitAnimation");
            checkPermission();
            mKeyguardViewMediator.startKeyguardExitAnimation(transit, apps, wallpapers,
                    nonApps, finishedCallback);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onAnimationCancelled() {
            mKeyguardViewMediator.cancelKeyguardExitAnimation();
        }
    };

    final IRemoteTransition mOccludeAnimation = new IRemoteTransition.Stub() {
        @Override
        public void startAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IRemoteTransitionFinishedCallback finishCallback)
                    throws RemoteException {
            t.apply();
            mBinder.setOccluded(true /* isOccluded */, true /* animate */);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        }

        @Override
        public void mergeAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IBinder mergeTarget,
                IRemoteTransitionFinishedCallback finishCallback) {
        }
    };

    final IRemoteTransition mUnoccludeAnimation = new IRemoteTransition.Stub() {
        @Override
        public void startAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IRemoteTransitionFinishedCallback finishCallback)
                throws RemoteException {
            t.apply();
            mBinder.setOccluded(false /* isOccluded */, true /* animate */);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        }

        @Override
        public void mergeAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IBinder mergeTarget,
                IRemoteTransitionFinishedCallback finishCallback) {
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
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNING_ON,
                    callback);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onScreenTurnedOn() {
            Trace.beginSection("KeyguardService.mBinder#onScreenTurnedOn");
            checkPermission();
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

        @Override
        public void dismissKeyguardToLaunch(Intent intentToLaunch) {
            checkPermission();
            mKeyguardViewMediator.dismissKeyguardToLaunch(intentToLaunch);
        }

        @Override
        public void onSystemKeyPressed(int keycode) {
            checkPermission();
            mKeyguardViewMediator.onSystemKeyPressed(keycode);
        }
    };
}

