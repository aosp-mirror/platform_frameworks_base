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

package com.android.wm.shell.draganddrop;

import static com.android.wm.shell.animation.Interpolators.FAST_OUT_LINEAR_IN;
import static com.android.wm.shell.animation.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.wm.shell.animation.Interpolators.LINEAR;
import static com.android.wm.shell.animation.Interpolators.LINEAR_OUT_SLOW_IN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.DragEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Coordinates the visible drop targets for the current drag.
 */
public class DragLayout extends View {

    private final DisplayLayout mDisplayLayout;
    private final SplitScreen mSplitScreen;

    private final ArrayList<Target> mTargets = new ArrayList<>();
    private Target mCurrentTarget = null;
    private DropOutlineDrawable mDropOutline;
    private int mDisplayMargin;
    private Insets mInsets = Insets.NONE;
    private boolean mHasDropped;

    public DragLayout(Context context, DisplayLayout displayLayout, SplitScreen splitscreen) {
        super(context);
        mDisplayLayout = displayLayout;
        mSplitScreen = splitscreen;
        mDisplayMargin = context.getResources().getDimensionPixelSize(
                R.dimen.drop_layout_display_margin);
        mDropOutline = new DropOutlineDrawable(context);
        setBackground(mDropOutline);
        setWillNotDraw(false);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mInsets = insets.getInsets(Type.systemBars() | Type.displayCutout());
        calculateDropTargets();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mDropOutline || super.verifyDrawable(who);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mCurrentTarget != null) {
            mDropOutline.draw(canvas);
        }
    }

    public boolean hasDropTarget() {
        return mCurrentTarget != null;
    }

    public boolean hasDropped() {
        return mHasDropped;
    }

    public void show(DragEvent event) {
        calculateDropTargets();
        mHasDropped = false;
    }

    private void calculateDropTargets() {
        // Calculate all the regions based on split and landscape portrait
        // TODO: Filter based on clip data
        final float SIDE_MARGIN_PCT = 0.3f;
        final int w = mDisplayLayout.width();
        final int h = mDisplayLayout.height();
        final int iw = w - mInsets.left - mInsets.right;
        final int ih = h - mInsets.top - mInsets.bottom;
        final int l = mInsets.left;
        final int t = mInsets.top;
        final int r = mInsets.right;
        final int b = mInsets.bottom;
        mTargets.clear();

        // Left split
        addTarget(new Target(
                new Rect(0, 0,
                        (int) (w * SIDE_MARGIN_PCT), h),
                new Rect(l + mDisplayMargin, t + mDisplayMargin,
                        l + iw / 2 - mDisplayMargin, t + ih - mDisplayMargin),
                new Rect(0, 0, w / 2, h)));

        // Fullscreen
        addTarget(new Target(
                new Rect((int) (w * SIDE_MARGIN_PCT), 0,
                        w - (int) (w * SIDE_MARGIN_PCT), h),
                new Rect(l + mDisplayMargin, t + mDisplayMargin,
                        l + iw - mDisplayMargin, t + ih - mDisplayMargin),
                new Rect(0, 0, w, h)));

        // Right split
        addTarget(new Target(
                new Rect(w - (int) (w * SIDE_MARGIN_PCT), 0,
                        w, h),
                new Rect(l + iw / 2 + mDisplayMargin, t + mDisplayMargin,
                        l + iw - mDisplayMargin, t + ih - mDisplayMargin),
                new Rect(w / 2, 0, w, h)));
    }

    private void addTarget(Target t) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Add target: %s", t);
        mTargets.add(t);
    }

    public void update(DragEvent event) {
        // Find containing region, if the same as mCurrentRegion, then skip, otherwise, animate the
        // visibility of the current region
        Target target = null;
        for (int i = mTargets.size() - 1; i >= 0; i--) {
            Target t = mTargets.get(i);
            if (t.hitRegion.contains((int) event.getX(), (int) event.getY())) {
                target = t;
                break;
            }
        }
        if (target != null && mCurrentTarget != target) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Current target: %s", target);
            Interpolator boundsInterpolator = FAST_OUT_SLOW_IN;
            if (mCurrentTarget == null) {
                mDropOutline.startVisibilityAnimation(true, LINEAR);
                Rect initialBounds = new Rect(target.drawRegion);
                initialBounds.inset(mDisplayMargin, mDisplayMargin);
                mDropOutline.setRegionBounds(initialBounds);
                boundsInterpolator = LINEAR_OUT_SLOW_IN;
            }
            mDropOutline.startBoundsAnimation(target.drawRegion, boundsInterpolator);
            mCurrentTarget = target;
        }
    }

    public void hide(DragEvent event, Runnable hideCompleteCallback) {
        ObjectAnimator alphaAnimator = mDropOutline.startVisibilityAnimation(false, LINEAR);
        ObjectAnimator boundsAnimator = null;
        if (mCurrentTarget != null) {
            Rect finalBounds = new Rect(mCurrentTarget.drawRegion);
            finalBounds.inset(mDisplayMargin, mDisplayMargin);
            boundsAnimator = mDropOutline.startBoundsAnimation(finalBounds, FAST_OUT_LINEAR_IN);
        }

        if (hideCompleteCallback != null) {
            ObjectAnimator lastAnim = boundsAnimator != null
                    ? boundsAnimator
                    : alphaAnimator;
            lastAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    hideCompleteCallback.run();
                }
            });
        }

        mCurrentTarget = null;
    }

    public boolean drop(DragEvent event, SurfaceControl dragSurface,
            Consumer<Rect> dropCompleteCallback) {
        mHasDropped = true;
        // TODO(b/169894807): Coordinate with dragSurface
        final Rect dropRegion = mCurrentTarget != null
                ? mCurrentTarget.dropTargetBounds
                : null;

        ObjectAnimator alphaAnimator = mDropOutline.startVisibilityAnimation(false, LINEAR);
        ObjectAnimator boundsAnimator = null;
        if (dropRegion != null) {
            Rect finalBounds = new Rect(mCurrentTarget.drawRegion);
            finalBounds.inset(mDisplayMargin, mDisplayMargin);
            mDropOutline.startBoundsAnimation(finalBounds, FAST_OUT_LINEAR_IN);
        }

        if (dropCompleteCallback != null) {
            ObjectAnimator lastAnim = boundsAnimator != null
                    ? boundsAnimator
                    : alphaAnimator;
            lastAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    dropCompleteCallback.accept(dropRegion);
                }
            });
        }
        return dropRegion != null;
    }

    private static class Target {
        final Rect hitRegion;
        final Rect drawRegion;
        final Rect dropTargetBounds;

        public Target(Rect hit, Rect draw, Rect drop) {
            hitRegion = hit;
            drawRegion = draw;
            dropTargetBounds = drop;
        }

        @Override
        public String toString() {
            return "Target {hit=" + hitRegion + " drop=" + dropTargetBounds + "}";
        }
    }
}
