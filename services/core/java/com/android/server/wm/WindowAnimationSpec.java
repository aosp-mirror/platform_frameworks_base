/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.AnimationAdapter.STATUS_BAR_TRANSITION_DURATION;
import static com.android.server.wm.AnimationSpecProto.WINDOW;
import static com.android.server.wm.WindowAnimationSpecProto.ANIMATION;
import static com.android.server.wm.WindowStateAnimator.ROOT_TASK_CLIP_NONE;

import android.annotation.ColorInt;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Interpolator;
import android.view.animation.Transformation;
import android.view.animation.TranslateAnimation;

import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;

import java.io.PrintWriter;

/**
 * Animation spec for regular window animations.
 */
public class WindowAnimationSpec implements AnimationSpec {

    private Animation mAnimation;
    private final Point mPosition = new Point();
    private final ThreadLocal<TmpValues> mThreadLocalTmps = ThreadLocal.withInitial(TmpValues::new);
    private final boolean mCanSkipFirstFrame;
    private final boolean mIsAppAnimation;
    private final Rect mRootTaskBounds = new Rect();
    private int mRootTaskClipMode;
    private final Rect mTmpRect = new Rect();
    private final float mWindowCornerRadius;

    public WindowAnimationSpec(Animation animation, Point position, boolean canSkipFirstFrame,
            float windowCornerRadius)  {
        this(animation, position, null /* rootTaskBounds */, canSkipFirstFrame, ROOT_TASK_CLIP_NONE,
                false /* isAppAnimation */, windowCornerRadius);
    }

    public WindowAnimationSpec(Animation animation, Point position, Rect rootTaskBounds,
            boolean canSkipFirstFrame, int rootTaskClipMode, boolean isAppAnimation,
            float windowCornerRadius) {
        mAnimation = animation;
        if (position != null) {
            mPosition.set(position.x, position.y);
        }
        mWindowCornerRadius = windowCornerRadius;
        mCanSkipFirstFrame = canSkipFirstFrame;
        mIsAppAnimation = isAppAnimation;
        mRootTaskClipMode = rootTaskClipMode;
        if (rootTaskBounds != null) {
            mRootTaskBounds.set(rootTaskBounds);
        }
    }

    @Override
    public boolean getShowWallpaper() {
        return mAnimation.getShowWallpaper();
    }

    @Override
    public long getDuration() {
        return mAnimation.computeDurationHint();
    }

    @Override
    @ColorInt
    public int getBackgroundColor() {
        return mAnimation.getBackgroundColor();
    }

    @Override
    public void apply(Transaction t, SurfaceControl leash, long currentPlayTime) {
        final TmpValues tmp = mThreadLocalTmps.get();
        tmp.transformation.clear();
        mAnimation.getTransformation(currentPlayTime, tmp.transformation);
        tmp.transformation.getMatrix().postTranslate(mPosition.x, mPosition.y);
        t.setMatrix(leash, tmp.transformation.getMatrix(), tmp.floats);
        t.setAlpha(leash, tmp.transformation.getAlpha());

        boolean cropSet = false;
        if (mRootTaskClipMode == ROOT_TASK_CLIP_NONE) {
            if (tmp.transformation.hasClipRect()) {
                t.setWindowCrop(leash, tmp.transformation.getClipRect());
                cropSet = true;
            }
        } else {
            mTmpRect.set(mRootTaskBounds);
            if (tmp.transformation.hasClipRect()) {
                mTmpRect.intersect(tmp.transformation.getClipRect());
            }
            t.setWindowCrop(leash, mTmpRect);
            cropSet = true;
        }

        // We can only apply rounded corner if a crop is set, as otherwise the value is meaningless,
        // since it doesn't have anything it's relative to.
        if (cropSet && mAnimation.hasRoundedCorners() && mWindowCornerRadius > 0) {
            t.setCornerRadius(leash, mWindowCornerRadius);
        }
    }

    @Override
    public long calculateStatusBarTransitionStartTime() {
        TranslateAnimation openTranslateAnimation = findTranslateAnimation(mAnimation);
        if (openTranslateAnimation != null) {

            // Some interpolators are extremely quickly mostly finished, but not completely. For
            // our purposes, we need to find the fraction for which ther interpolator is mostly
            // there, and use that value for the calculation.
            float t = findAlmostThereFraction(openTranslateAnimation.getInterpolator());
            return SystemClock.uptimeMillis()
                    + openTranslateAnimation.getStartOffset()
                    + (long)(openTranslateAnimation.getDuration() * t)
                    - STATUS_BAR_TRANSITION_DURATION;
        } else {
            return SystemClock.uptimeMillis();
        }
    }

    @Override
    public boolean canSkipFirstFrame() {
        return mCanSkipFirstFrame;
    }

    @Override
    public boolean needsEarlyWakeup() {
        return mIsAppAnimation;
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.println(mAnimation);
    }

    @Override
    public void dumpDebugInner(ProtoOutputStream proto) {
        final long token = proto.start(WINDOW);
        proto.write(ANIMATION, mAnimation.toString());
        proto.end(token);
    }

    /**
     * Tries to find a {@link TranslateAnimation} inside the {@code animation}.
     *
     * @return the found animation, {@code null} otherwise
     */
    private static TranslateAnimation findTranslateAnimation(Animation animation) {
        if (animation instanceof TranslateAnimation) {
            return (TranslateAnimation) animation;
        } else if (animation instanceof AnimationSet) {
            AnimationSet set = (AnimationSet) animation;
            for (int i = 0; i < set.getAnimations().size(); i++) {
                Animation a = set.getAnimations().get(i);
                if (a instanceof TranslateAnimation) {
                    return (TranslateAnimation) a;
                }
            }
        }
        return null;
    }

    /**
     * Binary searches for a {@code t} such that there exists a {@code -0.01 < eps < 0.01} for which
     * {@code interpolator(t + eps) > 0.99}.
     */
    private static float findAlmostThereFraction(Interpolator interpolator) {
        float val = 0.5f;
        float adj = 0.25f;
        while (adj >= 0.01f) {
            if (interpolator.getInterpolation(val) < 0.99f) {
                val += adj;
            } else {
                val -= adj;
            }
            adj /= 2;
        }
        return val;
    }

    private static class TmpValues {
        final Transformation transformation = new Transformation();
        final float[] floats = new float[9];
    }
}
