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

package com.android.server.wm;

import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_TO_FRONT;
import static android.view.WindowManager.TRANSIT_OLD_WALLPAPER_CLOSE;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_REMOTE_ANIMATIONS;
import static com.android.server.wm.AnimationAdapterProto.REMOTE;
import static com.android.server.wm.RemoteAnimationAdapterWrapperProto.TARGET;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.ArrayList;

class NonAppWindowAnimationAdapter implements AnimationAdapter {

    private final WindowContainer mWindowContainer;
    private RemoteAnimationTarget mTarget;
    private SurfaceControl mCapturedLeash;
    private SurfaceAnimator.OnAnimationFinishedCallback mCapturedLeashFinishCallback;
    private @SurfaceAnimator.AnimationType int mLastAnimationType;

    private long mDurationHint;
    private long mStatusBarTransitionDelay;

    @Override
    public boolean getShowWallpaper() {
        return false;
    }

    NonAppWindowAnimationAdapter(WindowContainer w, long durationHint,
            long statusBarTransitionDelay) {
        mWindowContainer = w;
        mDurationHint = durationHint;
        mStatusBarTransitionDelay = statusBarTransitionDelay;
    }

    static RemoteAnimationTarget[] startNonAppWindowAnimations(WindowManagerService service,
            DisplayContent displayContent, @WindowManager.TransitionOldType int transit,
            long durationHint, long statusBarTransitionDelay,
            ArrayList<NonAppWindowAnimationAdapter> adaptersOut) {
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        if (transit == TRANSIT_OLD_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER) {
            startNonAppWindowAnimationsForKeyguardExit(
                    service, durationHint, statusBarTransitionDelay, targets, adaptersOut);
        } else if (transit == TRANSIT_OLD_TASK_OPEN || transit == TRANSIT_OLD_TASK_TO_FRONT
                || transit == TRANSIT_OLD_WALLPAPER_CLOSE) {
            final boolean shouldAttachNavBarToApp =
                    displayContent.getDisplayPolicy().shouldAttachNavBarToAppDuringTransition()
                            && service.getRecentsAnimationController() == null
                            && displayContent.getFadeRotationAnimationController() == null;
            if (shouldAttachNavBarToApp) {
                startNavigationBarWindowAnimation(
                        displayContent, durationHint, statusBarTransitionDelay, targets,
                        adaptersOut);
            }
        }
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    /**
     * Creates and starts remote animations for all the visible non app windows.
     *
     * @return RemoteAnimationTarget[] targets for all the visible non app windows
     */
    private static void startNonAppWindowAnimationsForKeyguardExit(WindowManagerService service,
            long durationHint, long statusBarTransitionDelay,
            ArrayList<RemoteAnimationTarget> targets,
            ArrayList<NonAppWindowAnimationAdapter> adaptersOut) {

        final WindowManagerPolicy policy = service.mPolicy;
        service.mRoot.forAllWindows(nonAppWindow -> {
            if (nonAppWindow.mActivityRecord == null && policy.canBeHiddenByKeyguardLw(nonAppWindow)
                    && nonAppWindow.wouldBeVisibleIfPolicyIgnored() && !nonAppWindow.isVisible()) {
                final NonAppWindowAnimationAdapter nonAppAdapter = new NonAppWindowAnimationAdapter(
                        nonAppWindow, durationHint, statusBarTransitionDelay);
                adaptersOut.add(nonAppAdapter);
                nonAppWindow.startAnimation(nonAppWindow.getPendingTransaction(),
                        nonAppAdapter, false /* hidden */, ANIMATION_TYPE_WINDOW_ANIMATION);
                targets.add(nonAppAdapter.createRemoteAnimationTarget());
            }
        }, true /* traverseTopToBottom */);
    }

    /**
     * Creates and starts remote animation for the navigation bar windows.
     *
     * @return RemoteAnimationTarget[] targets for all the visible non app windows
     */
    private static void startNavigationBarWindowAnimation(DisplayContent displayContent,
            long durationHint, long statusBarTransitionDelay,
            ArrayList<RemoteAnimationTarget> targets,
            ArrayList<NonAppWindowAnimationAdapter> adaptersOut) {
        final WindowState navWindow = displayContent.getDisplayPolicy().getNavigationBar();
        final NonAppWindowAnimationAdapter nonAppAdapter = new NonAppWindowAnimationAdapter(
                navWindow.mToken, durationHint, statusBarTransitionDelay);
        adaptersOut.add(nonAppAdapter);
        navWindow.mToken.startAnimation(navWindow.mToken.getPendingTransaction(),
                nonAppAdapter, false /* hidden */, ANIMATION_TYPE_WINDOW_ANIMATION);
        targets.add(nonAppAdapter.createRemoteAnimationTarget());
    }

    /**
     * Create a remote animation target for this animation adapter.
     */
    RemoteAnimationTarget createRemoteAnimationTarget() {
        mTarget = new RemoteAnimationTarget(-1, -1, getLeash(), false,
                new Rect(), null, mWindowContainer.getPrefixOrderIndex(),
                mWindowContainer.getLastSurfacePosition(), mWindowContainer.getBounds(), null,
                mWindowContainer.getWindowConfiguration(), true, null, null, null,
                mWindowContainer.getWindowType());
        return mTarget;
    }

    @Override
    public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
            int type, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "startAnimation");
        mCapturedLeash = animationLeash;
        mCapturedLeashFinishCallback = finishCallback;
        mLastAnimationType = type;
    }

    /**
     * @return the callback to call to clean up when the animation has finished.
     */
    SurfaceAnimator.OnAnimationFinishedCallback getLeashFinishedCallback() {
        return mCapturedLeashFinishCallback;
    }

    /**
     * @return the type of animation.
     */
    @SurfaceAnimator.AnimationType
    int getLastAnimationType() {
        return mLastAnimationType;
    }

    WindowContainer getWindowContainer() {
        return mWindowContainer;
    }

    @Override
    public long getDurationHint() {
        return mDurationHint;
    }

    @Override
    public long getStatusBarTransitionsStartTime() {
        return SystemClock.uptimeMillis() + mStatusBarTransitionDelay;
    }

    /**
     * @return the leash for this animation (only valid after the non app window surface animation
     * has started).
     */
    SurfaceControl getLeash() {
        return mCapturedLeash;
    }

    @Override
    public void onAnimationCancelled(SurfaceControl animationLeash) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "onAnimationCancelled");
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("windowContainer=");
        pw.println(mWindowContainer);
        if (mTarget != null) {
            pw.print(prefix);
            pw.println("Target:");
            mTarget.dump(pw, prefix + "  ");
        } else {
            pw.print(prefix);
            pw.println("Target: null");
        }
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto) {
        final long token = proto.start(REMOTE);
        if (mTarget != null) {
            mTarget.dumpDebug(proto, TARGET);
        }
        proto.end(token);
    }
}
