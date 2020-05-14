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

package com.android.server.wm;

import static com.android.server.wm.AnimationSpecProto.WINDOW;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_FIXED_TRANSFORM;
import static com.android.server.wm.WindowAnimationSpecProto.ANIMATION;

import android.content.res.Configuration;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import com.android.internal.R;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controller to fade out and in system ui when applying a fixed rotation transform to a window
 * token.
 *
 * The system bars will be fade out when the fixed rotation transform starts and will be fade in
 * once all surfaces have been rotated.
 */
public class FixedRotationAnimationController {

    private final WindowManagerService mWmService;
    private boolean mShowRequested = true;
    private int mTargetRotation = Configuration.ORIENTATION_UNDEFINED;
    private final ArrayList<WindowState> mAnimatedWindowStates = new ArrayList<>(2);
    private final Runnable[] mDeferredFinishCallbacks;

    public FixedRotationAnimationController(DisplayContent displayContent) {
        mWmService = displayContent.mWmService;
        addAnimatedWindow(displayContent.getDisplayPolicy().getStatusBar());
        addAnimatedWindow(displayContent.getDisplayPolicy().getNavigationBar());
        mDeferredFinishCallbacks = new Runnable[mAnimatedWindowStates.size()];
    }

    private void addAnimatedWindow(WindowState windowState) {
        if (windowState != null) {
            mAnimatedWindowStates.add(windowState);
        }
    }

    /**
     * Show the previously hidden {@link WindowToken} if their surfaces have already been rotated.
     *
     * @return True if the show animation has been started, in which case the caller no longer needs
     * this {@link FixedRotationAnimationController}.
     */
    boolean show() {
        if (!mShowRequested && readyToShow()) {
            mShowRequested = true;
            for (int i = mAnimatedWindowStates.size() - 1; i >= 0; i--) {
                WindowState windowState = mAnimatedWindowStates.get(i);
                fadeWindowToken(true, windowState.getParent(), i);
            }
            return true;
        }
        return false;
    }

    void hide(int targetRotation) {
        mTargetRotation = targetRotation;
        if (mShowRequested) {
            mShowRequested = false;
            for (int i = mAnimatedWindowStates.size() - 1; i >= 0; i--) {
                WindowState windowState = mAnimatedWindowStates.get(i);
                fadeWindowToken(false /* show */, windowState.getParent(), i);
            }
        }
    }

    void cancel() {
        for (int i = mAnimatedWindowStates.size() - 1; i >= 0; i--) {
            WindowState windowState = mAnimatedWindowStates.get(i);
            mShowRequested = true;
            fadeWindowToken(true /* show */, windowState.getParent(), i);
        }
    }

    private void fadeWindowToken(boolean show, WindowContainer<WindowToken> windowToken,
            int index) {
        Animation animation = AnimationUtils.loadAnimation(mWmService.mContext,
                show ? R.anim.fade_in : R.anim.fade_out);
        LocalAnimationAdapter.AnimationSpec windowAnimationSpec = createAnimationSpec(animation);

        FixedRotationAnimationAdapter animationAdapter = new FixedRotationAnimationAdapter(
                windowAnimationSpec, windowToken.getSurfaceAnimationRunner(), show, index);

        // We deferred the end of the animation when hiding the token, so we need to end it now that
        // it's shown again.
        SurfaceAnimator.OnAnimationFinishedCallback finishedCallback = show ? (t, r) -> {
            if (mDeferredFinishCallbacks[index] != null) {
                mDeferredFinishCallbacks[index].run();
                mDeferredFinishCallbacks[index] = null;
            }
        } : null;
        windowToken.startAnimation(windowToken.getPendingTransaction(), animationAdapter,
                mShowRequested, ANIMATION_TYPE_FIXED_TRANSFORM, finishedCallback);
    }

    /**
     * Check if all the mAnimatedWindowState's surfaces have been rotated to the
     * mTargetRotation.
     */
    private boolean readyToShow() {
        for (int i = mAnimatedWindowStates.size() - 1; i >= 0; i--) {
            WindowState windowState = mAnimatedWindowStates.get(i);
            if (windowState.getConfiguration().windowConfiguration.getRotation()
                    != mTargetRotation || windowState.mPendingSeamlessRotate != null) {
                return false;
            }
        }
        return true;
    }


    private LocalAnimationAdapter.AnimationSpec createAnimationSpec(Animation animation) {
        return new LocalAnimationAdapter.AnimationSpec() {

            Transformation mTransformation = new Transformation();

            @Override
            public boolean getShowWallpaper() {
                return true;
            }

            @Override
            public long getDuration() {
                return animation.getDuration();
            }

            @Override
            public void apply(SurfaceControl.Transaction t, SurfaceControl leash,
                    long currentPlayTime) {
                mTransformation.clear();
                animation.getTransformation(currentPlayTime, mTransformation);
                t.setAlpha(leash, mTransformation.getAlpha());
            }

            @Override
            public void dump(PrintWriter pw, String prefix) {
                pw.print(prefix);
                pw.println(animation);
            }

            @Override
            public void dumpDebugInner(ProtoOutputStream proto) {
                final long token = proto.start(WINDOW);
                proto.write(ANIMATION, animation.toString());
                proto.end(token);
            }
        };
    }

    private class FixedRotationAnimationAdapter extends LocalAnimationAdapter {
        private final boolean mShow;
        private final int mIndex;

        FixedRotationAnimationAdapter(AnimationSpec windowAnimationSpec,
                SurfaceAnimationRunner surfaceAnimationRunner, boolean show, int index) {
            super(windowAnimationSpec, surfaceAnimationRunner);
            mShow = show;
            mIndex = index;
        }

        @Override
        public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            // We defer the end of the hide animation to ensure the tokens stay hidden until
            // we show them again.
            if (!mShow) {
                mDeferredFinishCallbacks[mIndex] = endDeferFinishCallback;
                return true;
            }
            return false;
        }
    }
}
