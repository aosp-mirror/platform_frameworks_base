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

import static com.android.server.wm.AnimationSpecProto.WINDOW;
import static com.android.server.wm.WindowAnimationSpecProto.ANIMATION;

import android.annotation.NonNull;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;

import java.io.PrintWriter;

/**
 * An animation controller to fade-in/out for a window token.
 */
public class FadeAnimationController {
    static final int SHORT_DURATION_MS = 200;
    static final int MEDIUM_DURATION_MS = 350;

    protected final DisplayContent mDisplayContent;

    public FadeAnimationController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    /**
     * @return a fade-in Animation.
     */
    public Animation getFadeInAnimation() {
        final AlphaAnimation anim = new AlphaAnimation(0f, 1f);
        anim.setDuration(getScaledDuration(MEDIUM_DURATION_MS));
        anim.setInterpolator(new DecelerateInterpolator());
        return anim;
    }

    /**
     * @return a fade-out Animation.
     */
    public Animation getFadeOutAnimation() {
        final AlphaAnimation anim = new AlphaAnimation(1f, 0f);
        anim.setDuration(getScaledDuration(SHORT_DURATION_MS));
        anim.setInterpolator(new AccelerateInterpolator());
        return anim;
    }

    long getScaledDuration(int durationMs) {
        return (long) (durationMs * mDisplayContent.mWmService.getWindowAnimationScaleLocked());
    }

    /** Run the fade in/out animation for a window token. */
    public void fadeWindowToken(boolean show, WindowToken windowToken, int animationType) {
        fadeWindowToken(show, windowToken, animationType, null);
    }

    /**
     * Run the fade in/out animation for a window token.
     *
     * @param show true for fade-in, otherwise for fade-out.
     * @param windowToken the window token to run the animation.
     * @param animationType the animation type defined in SurfaceAnimator.
     * @param finishedCallback the callback after the animation finished.
     */
    public void fadeWindowToken(boolean show, WindowToken windowToken, int animationType,
            SurfaceAnimator.OnAnimationFinishedCallback finishedCallback) {
        if (windowToken == null || windowToken.getParent() == null) {
            return;
        }

        final Animation animation = show ? getFadeInAnimation() : getFadeOutAnimation();
        final FadeAnimationAdapter animationAdapter = animation != null
                ? createAdapter(createAnimationSpec(animation), show, windowToken) : null;
        if (animationAdapter == null) {
            return;
        }
        windowToken.startAnimation(windowToken.getPendingTransaction(), animationAdapter,
                show /* hidden */, animationType, finishedCallback);
    }

    protected FadeAnimationAdapter createAdapter(LocalAnimationAdapter.AnimationSpec animationSpec,
            boolean show, WindowToken windowToken) {
        return new FadeAnimationAdapter(animationSpec, windowToken.getSurfaceAnimationRunner(),
                show, windowToken);
    }

    protected LocalAnimationAdapter.AnimationSpec createAnimationSpec(
            @NonNull Animation animation) {
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

    protected static class FadeAnimationAdapter extends LocalAnimationAdapter {
        protected final boolean mShow;
        protected final WindowToken mToken;

        FadeAnimationAdapter(AnimationSpec windowAnimationSpec,
                SurfaceAnimationRunner surfaceAnimationRunner, boolean show,
                WindowToken token) {
            super(windowAnimationSpec, surfaceAnimationRunner);
            mShow = show;
            mToken = token;
        }

        @Override
        public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            // Defer the finish callback (restore leash) of the hide animation to ensure the token
            // stay hidden until it needs to show again. Besides, when starting the show animation,
            // the previous hide animation will be cancelled, so the callback can be ignored.
            return !mShow;
        }
    }
}
