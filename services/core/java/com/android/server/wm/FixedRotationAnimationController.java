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

import android.content.Context;
import android.util.ArrayMap;
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

    private final Context mContext;
    private final WindowState mStatusBar;
    private final WindowState mNavigationBar;
    private final ArrayList<WindowToken> mAnimatedWindowToken = new ArrayList<>(2);
    private final ArrayMap<WindowToken, Runnable> mDeferredFinishCallbacks = new ArrayMap<>();

    public FixedRotationAnimationController(DisplayContent displayContent) {
        mContext = displayContent.mWmService.mContext;
        final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
        mStatusBar = displayPolicy.getStatusBar();
        // Do not animate movable navigation bar (e.g. non-gesture mode).
        mNavigationBar = !displayPolicy.navigationBarCanMove()
                ? displayPolicy.getNavigationBar()
                : null;
    }

    /** Applies show animation on the previously hidden window tokens. */
    void show() {
        for (int i = mAnimatedWindowToken.size() - 1; i >= 0; i--) {
            final WindowToken windowToken = mAnimatedWindowToken.get(i);
            fadeWindowToken(true /* show */, windowToken);
        }
    }

    /** Applies hide animation on the window tokens which may be seamlessly rotated later. */
    void hide() {
        if (mNavigationBar != null) {
            fadeWindowToken(false /* show */, mNavigationBar.mToken);
        }
        if (mStatusBar != null) {
            fadeWindowToken(false /* show */, mStatusBar.mToken);
        }
    }

    private void fadeWindowToken(boolean show, WindowToken windowToken) {
        if (windowToken == null || windowToken.getParent() == null) {
            return;
        }

        final Animation animation = AnimationUtils.loadAnimation(mContext,
                show ? R.anim.fade_in : R.anim.fade_out);
        final LocalAnimationAdapter.AnimationSpec windowAnimationSpec =
                createAnimationSpec(animation);

        final FixedRotationAnimationAdapter animationAdapter = new FixedRotationAnimationAdapter(
                windowAnimationSpec, windowToken.getSurfaceAnimationRunner(), show, windowToken);

        // We deferred the end of the animation when hiding the token, so we need to end it now that
        // it's shown again.
        final SurfaceAnimator.OnAnimationFinishedCallback finishedCallback = show ? (t, r) -> {
            final Runnable runnable = mDeferredFinishCallbacks.remove(windowToken);
            if (runnable != null) {
                runnable.run();
            }
        } : null;
        windowToken.startAnimation(windowToken.getPendingTransaction(), animationAdapter,
                show /* hidden */, ANIMATION_TYPE_FIXED_TRANSFORM, finishedCallback);
        mAnimatedWindowToken.add(windowToken);
    }

    private LocalAnimationAdapter.AnimationSpec createAnimationSpec(Animation animation) {
        return new LocalAnimationAdapter.AnimationSpec() {

            final Transformation mTransformation = new Transformation();

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
        private final WindowToken mToken;

        FixedRotationAnimationAdapter(AnimationSpec windowAnimationSpec,
                SurfaceAnimationRunner surfaceAnimationRunner, boolean show,
                WindowToken token) {
            super(windowAnimationSpec, surfaceAnimationRunner);
            mShow = show;
            mToken = token;
        }

        @Override
        public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            // We defer the end of the hide animation to ensure the tokens stay hidden until
            // we show them again.
            if (!mShow) {
                mDeferredFinishCallbacks.put(mToken, endDeferFinishCallback);
                return true;
            }
            return false;
        }
    }
}
