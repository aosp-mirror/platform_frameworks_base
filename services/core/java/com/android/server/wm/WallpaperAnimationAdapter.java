/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.graphics.Point;
import android.os.SystemClock;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.wm.SurfaceAnimator.AnimationType;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * An animation adapter for wallpaper windows.
 */
class WallpaperAnimationAdapter implements AnimationAdapter {
    private static final String TAG = "WallpaperAnimationAdapter";

    private final WallpaperWindowToken mWallpaperToken;
    private SurfaceControl mCapturedLeash;
    private SurfaceAnimator.OnAnimationFinishedCallback mCapturedLeashFinishCallback;
    private @AnimationType int mLastAnimationType;

    private long mDurationHint;
    private long mStatusBarTransitionDelay;

    private Consumer<WallpaperAnimationAdapter> mAnimationCanceledRunnable;
    private RemoteAnimationTarget mTarget;

    WallpaperAnimationAdapter(WallpaperWindowToken wallpaperToken,
            long durationHint, long statusBarTransitionDelay,
            Consumer<WallpaperAnimationAdapter> animationCanceledRunnable) {
        mWallpaperToken = wallpaperToken;
        mDurationHint = durationHint;
        mStatusBarTransitionDelay = statusBarTransitionDelay;
        mAnimationCanceledRunnable = animationCanceledRunnable;
    }

    /**
     * Creates and starts remote animations for all the visible wallpaper windows.
     *
     * @return RemoteAnimationTarget[] targets for all the visible wallpaper windows
     */
    public static RemoteAnimationTarget[] startWallpaperAnimations(DisplayContent displayContent,
            long durationHint, long statusBarTransitionDelay,
            Consumer<WallpaperAnimationAdapter> animationCanceledRunnable,
            ArrayList<WallpaperAnimationAdapter> adaptersOut) {
        if (!shouldStartWallpaperAnimation(displayContent)) {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS,
                    "\tWallpaper of display=%s is not visible", displayContent);
            return new RemoteAnimationTarget[0];
        }
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        displayContent.forAllWallpaperWindows(wallpaperWindow -> {
            final WallpaperAnimationAdapter wallpaperAdapter = new WallpaperAnimationAdapter(
                    wallpaperWindow, durationHint, statusBarTransitionDelay,
                    animationCanceledRunnable);
            wallpaperWindow.startAnimation(wallpaperWindow.getPendingTransaction(),
                    wallpaperAdapter, false /* hidden */, ANIMATION_TYPE_WINDOW_ANIMATION);
            targets.add(wallpaperAdapter.createRemoteAnimationTarget());
            adaptersOut.add(wallpaperAdapter);
        });
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    static boolean shouldStartWallpaperAnimation(DisplayContent displayContent) {
        return displayContent.mWallpaperController.isWallpaperVisible();
    }

    /**
     * Create a remote animation target for this animation adapter.
     */
    RemoteAnimationTarget createRemoteAnimationTarget() {
        mTarget = new RemoteAnimationTarget(-1, -1, getLeash(), false, null, null,
                mWallpaperToken.getPrefixOrderIndex(), new Point(), null, null,
                mWallpaperToken.getWindowConfiguration(), true, null, null, null, false);
        return mTarget;
    }

    /**
     * @return the leash for this animation (only valid after the wallpaper window surface animation
     * has started).
     */
    SurfaceControl getLeash() {
        return mCapturedLeash;
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
    @AnimationType int getLastAnimationType() {
        return mLastAnimationType;
    }

    /**
     * @return the wallpaper window
     */
    WallpaperWindowToken getToken() {
        return mWallpaperToken;
    }

    @Override
    public boolean getShowWallpaper() {
        // Not used
        return false;
    }

    @Override
    public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
            @AnimationType int type,
            @NonNull SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "startAnimation");

        // Restore z-layering until client has a chance to modify it.
        t.setLayer(animationLeash, mWallpaperToken.getPrefixOrderIndex());
        mCapturedLeash = animationLeash;
        mCapturedLeashFinishCallback = finishCallback;
        mLastAnimationType = type;
    }

    @Override
    public void onAnimationCancelled(SurfaceControl animationLeash) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "onAnimationCancelled");
        mAnimationCanceledRunnable.accept(this);
    }

    @Override
    public long getDurationHint() {
        return mDurationHint;
    }

    @Override
    public long getStatusBarTransitionsStartTime() {
        return SystemClock.uptimeMillis() + mStatusBarTransitionDelay;
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("token=");
        pw.println(mWallpaperToken);
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
