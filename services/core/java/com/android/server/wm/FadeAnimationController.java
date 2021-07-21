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
import android.content.Context;
import android.util.ArrayMap;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import com.android.internal.R;

import java.io.PrintWriter;

/**
 * An animation controller to fade-in/out for a window token.
 */
public class FadeAnimationController {
    protected final Context mContext;
    protected final ArrayMap<WindowToken, Runnable> mDeferredFinishCallbacks = new ArrayMap<>();

    public FadeAnimationController(DisplayContent displayContent) {
        mContext = displayContent.mWmService.mContext;
    }

    /**
     * @return a fade-in Animation.
     */
    public Animation getFadeInAnimation() {
        return AnimationUtils.loadAnimation(mContext, R.anim.fade_in);
    }

    /**
     * @return a fade-out Animation.
     */
    public Animation getFadeOutAnimation() {
        return AnimationUtils.loadAnimation(mContext, R.anim.fade_out);
    }

    /**
     * Run the fade in/out animation for a window token.
     *
     * @param show true for fade-in, otherwise for fade-out.
     * @param windowToken the window token to run the animation.
     * @param animationType the animation type defined in SurfaceAnimator.
     */
    public void fadeWindowToken(boolean show, WindowToken windowToken, int animationType) {
        if (windowToken == null || windowToken.getParent() == null) {
            return;
        }

        final FadeAnimationAdapter animationAdapter = createAdapter(show, windowToken);
        if (animationAdapter == null) {
            return;
        }

        // We deferred the end of the animation when hiding the token, so we need to end it now that
        // it's shown again.
        final SurfaceAnimator.OnAnimationFinishedCallback finishedCallback = show ? (t, r) -> {
            final Runnable runnable = mDeferredFinishCallbacks.remove(windowToken);
            if (runnable != null) {
                runnable.run();
            }
        } : null;
        windowToken.startAnimation(windowToken.getPendingTransaction(), animationAdapter,
                show /* hidden */, animationType, finishedCallback);
    }

    protected FadeAnimationAdapter createAdapter(boolean show, WindowToken windowToken) {
        final Animation animation = show ? getFadeInAnimation() : getFadeOutAnimation();
        if (animation == null) {
            return null;
        }

        final LocalAnimationAdapter.AnimationSpec windowAnimationSpec =
                createAnimationSpec(animation);

        return new FadeAnimationAdapter(
                windowAnimationSpec, windowToken.getSurfaceAnimationRunner(), show, windowToken);
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

    protected class FadeAnimationAdapter extends LocalAnimationAdapter {
        protected final boolean mShow;
        private final WindowToken mToken;

        FadeAnimationAdapter(AnimationSpec windowAnimationSpec,
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
