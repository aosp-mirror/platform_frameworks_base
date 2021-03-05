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

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_REMOTE_ANIMATIONS;
import static com.android.server.wm.AnimationAdapterProto.REMOTE;
import static com.android.server.wm.RemoteAnimationAdapterWrapperProto.TARGET;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.ArrayList;

class NonAppWindowAnimationAdapter implements AnimationAdapter {

    private final WindowState mWindow;
    private RemoteAnimationTarget mTarget;
    private SurfaceControl mCapturedLeash;

    private long mDurationHint;
    private long mStatusBarTransitionDelay;

    @Override
    public boolean getShowWallpaper() {
        return false;
    }

    NonAppWindowAnimationAdapter(WindowState w,
            long durationHint, long statusBarTransitionDelay) {
        mWindow = w;
        mDurationHint = durationHint;
        mStatusBarTransitionDelay = statusBarTransitionDelay;
    }

    /**
     * Creates and starts remote animations for all the visible non app windows.
     *
     * @return RemoteAnimationTarget[] targets for all the visible non app windows
     */
    public static RemoteAnimationTarget[] startNonAppWindowAnimationsForKeyguardExit(
            WindowManagerService service, long durationHint, long statusBarTransitionDelay) {
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();

        final WindowManagerPolicy policy = service.mPolicy;
        service.mRoot.forAllWindows(nonAppWindow -> {
            if (nonAppWindow.mActivityRecord == null && policy.canBeHiddenByKeyguardLw(nonAppWindow)
                    && nonAppWindow.wouldBeVisibleIfPolicyIgnored() && !nonAppWindow.isVisible()) {
                final NonAppWindowAnimationAdapter nonAppAdapter = new NonAppWindowAnimationAdapter(
                        nonAppWindow, durationHint, statusBarTransitionDelay);
                nonAppWindow.startAnimation(nonAppWindow.getPendingTransaction(),
                        nonAppAdapter, false /* hidden */, ANIMATION_TYPE_WINDOW_ANIMATION);
                targets.add(nonAppAdapter.createRemoteAnimationTarget());
            }
        }, true /* traverseTopToBottom */);
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    /**
     * Create a remote animation target for this animation adapter.
     */
    RemoteAnimationTarget createRemoteAnimationTarget() {
        mTarget = new RemoteAnimationTarget(-1, -1, getLeash(), false,
                new Rect(), null, mWindow.getPrefixOrderIndex(), mWindow.getLastSurfacePosition(),
                mWindow.getBounds(), null, mWindow.getWindowConfiguration(), true, null, null,
                null);
        return mTarget;
    }

    @Override
    public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
            int type, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
        mCapturedLeash = animationLeash;
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
        pw.print("token=");
        pw.println(mWindow.mToken);
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
