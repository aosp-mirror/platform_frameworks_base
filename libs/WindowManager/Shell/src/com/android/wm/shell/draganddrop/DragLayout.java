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
import android.content.ClipData;
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

import androidx.annotation.NonNull;

import com.android.internal.logging.InstanceId;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.util.ArrayList;

/**
 * Coordinates the visible drop targets for the current drag.
 */
public class DragLayout extends View {

    private final DragAndDropPolicy mPolicy;

    private DragAndDropPolicy.Target mCurrentTarget = null;
    private DropOutlineDrawable mDropOutline;
    private int mDisplayMargin;
    private Insets mInsets = Insets.NONE;

    private boolean mIsShowing;
    private boolean mHasDropped;

    public DragLayout(Context context, SplitScreenController splitscreen) {
        super(context);
        mPolicy = new DragAndDropPolicy(context, splitscreen);
        mDisplayMargin = context.getResources().getDimensionPixelSize(
                R.dimen.drop_layout_display_margin);
        mDropOutline = new DropOutlineDrawable(context);
        setBackground(mDropOutline);
        setWillNotDraw(false);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mInsets = insets.getInsets(Type.systemBars() | Type.displayCutout());
        recomputeDropTargets();
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

    public void prepare(DisplayLayout displayLayout, ClipData initialData,
            InstanceId loggerSessionId) {
        mPolicy.start(displayLayout, initialData, loggerSessionId);
        mHasDropped = false;
        mCurrentTarget = null;
    }

    public void show() {
        mIsShowing = true;
        recomputeDropTargets();
    }

    /**
     * Recalculates the drop targets based on the current policy.
     */
    private void recomputeDropTargets() {
        if (!mIsShowing) {
            return;
        }
        final ArrayList<DragAndDropPolicy.Target> targets = mPolicy.getTargets(mInsets);
        for (int i = 0; i < targets.size(); i++) {
            final DragAndDropPolicy.Target target = targets.get(i);
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Add target: %s", target);
            // Inset the draw region by a little bit
            target.drawRegion.inset(mDisplayMargin, mDisplayMargin);
        }
    }

    /**
     * Updates the visible drop target as the user drags.
     */
    public void update(DragEvent event) {
        // Find containing region, if the same as mCurrentRegion, then skip, otherwise, animate the
        // visibility of the current region
        DragAndDropPolicy.Target target = mPolicy.getTargetAtLocation(
                (int) event.getX(), (int) event.getY());
        if (mCurrentTarget != target) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DRAG_AND_DROP, "Current target: %s", target);
            if (target == null) {
                // Animating to no target
                mDropOutline.startVisibilityAnimation(false, LINEAR);
                Rect finalBounds = new Rect(mCurrentTarget.drawRegion);
                finalBounds.inset(mDisplayMargin, mDisplayMargin);
                mDropOutline.startBoundsAnimation(finalBounds, FAST_OUT_LINEAR_IN);
            } else if (mCurrentTarget == null) {
                // Animating to first target
                mDropOutline.startVisibilityAnimation(true, LINEAR);
                Rect initialBounds = new Rect(target.drawRegion);
                initialBounds.inset(mDisplayMargin, mDisplayMargin);
                mDropOutline.setRegionBounds(initialBounds);
                mDropOutline.startBoundsAnimation(target.drawRegion, LINEAR_OUT_SLOW_IN);
            } else {
                // Bounds change
                mDropOutline.startBoundsAnimation(target.drawRegion, FAST_OUT_SLOW_IN);
            }
            mCurrentTarget = target;
        }
    }

    /**
     * Hides the drag layout and animates out the visible drop targets.
     */
    public void hide(DragEvent event, Runnable hideCompleteCallback) {
        mIsShowing = false;
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

    /**
     * Handles the drop onto a target and animates out the visible drop targets.
     */
    public boolean drop(DragEvent event, SurfaceControl dragSurface,
            Runnable dropCompleteCallback) {
        final boolean handledDrop = mCurrentTarget != null;
        mHasDropped = true;

        // Process the drop
        mPolicy.handleDrop(mCurrentTarget, event.getClipData());

        // TODO(b/169894807): Coordinate with dragSurface
        hide(event, dropCompleteCallback);
        return handledDrop;
    }
}
