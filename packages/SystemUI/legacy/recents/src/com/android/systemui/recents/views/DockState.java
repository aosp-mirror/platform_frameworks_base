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
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT;
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.util.IntProperty;
import android.view.animation.Interpolator;

import com.android.internal.policy.DockedDividerUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.LegacyRecentsImpl;
import com.android.systemui.recents.utilities.Utilities;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * The various possible dock states when dragging and dropping a task.
 */
public class DockState implements DropTarget {

    public static final int DOCK_AREA_BG_COLOR = 0xFFffffff;
    public static final int DOCK_AREA_GRID_BG_COLOR = 0xFF000000;

    // The rotation to apply to the hint text
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HORIZONTAL, VERTICAL})
    public @interface TextOrientation {}
    private static final int HORIZONTAL = 0;
    private static final int VERTICAL = 1;

    private static final int DOCK_AREA_ALPHA = 80;
    public static final DockState NONE = new DockState(DOCKED_INVALID, -1, 80, 255, HORIZONTAL,
            null, null, null);
    public static final DockState LEFT = new DockState(DOCKED_LEFT,
            SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, DOCK_AREA_ALPHA, 0, VERTICAL,
            new RectF(0, 0, 0.125f, 1), new RectF(0, 0, 0.125f, 1),
            new RectF(0, 0, 0.5f, 1));
    public static final DockState TOP = new DockState(DOCKED_TOP,
            SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, DOCK_AREA_ALPHA, 0, HORIZONTAL,
            new RectF(0, 0, 1, 0.125f), new RectF(0, 0, 1, 0.125f),
            new RectF(0, 0, 1, 0.5f));
    public static final DockState RIGHT = new DockState(DOCKED_RIGHT,
            SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT, DOCK_AREA_ALPHA, 0, VERTICAL,
            new RectF(0.875f, 0, 1, 1), new RectF(0.875f, 0, 1, 1),
            new RectF(0.5f, 0, 1, 1));
    public static final DockState BOTTOM = new DockState(DOCKED_BOTTOM,
            SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT, DOCK_AREA_ALPHA, 0, HORIZONTAL,
            new RectF(0, 0.875f, 1, 1), new RectF(0, 0.875f, 1, 1),
            new RectF(0, 0.5f, 1, 1));

    @Override
    public boolean acceptsDrop(int x, int y, int width, int height, Rect insets,
            boolean isCurrentTarget) {
        if (isCurrentTarget) {
            getMappedRect(expandedTouchDockArea, width, height, mTmpRect);
            return mTmpRect.contains(x, y);
        } else {
            getMappedRect(touchArea, width, height, mTmpRect);
            updateBoundsWithSystemInsets(mTmpRect, insets);
            return mTmpRect.contains(x, y);
        }
    }

    // Represents the view state of this dock state
    public static class ViewState {
        private static final IntProperty<ViewState> HINT_ALPHA =
                new IntProperty<ViewState>("drawableAlpha") {
                    @Override
                    public void setValue(ViewState object, int alpha) {
                        object.mHintTextAlpha = alpha;
                        object.dockAreaOverlay.invalidateSelf();
                    }

                    @Override
                    public Integer get(ViewState object) {
                        return object.mHintTextAlpha;
                    }
                };

        public final int dockAreaAlpha;
        public final ColorDrawable dockAreaOverlay;
        public final int hintTextAlpha;
        public final int hintTextOrientation;

        private final int mHintTextResId;
        private String mHintText;
        private Paint mHintTextPaint;
        private Point mHintTextBounds = new Point();
        private int mHintTextAlpha = 255;
        private AnimatorSet mDockAreaOverlayAnimator;
        private Rect mTmpRect = new Rect();

        private ViewState(int areaAlpha, int hintAlpha, @TextOrientation int hintOrientation,
                int hintTextResId) {
            dockAreaAlpha = areaAlpha;
            dockAreaOverlay = new ColorDrawable(LegacyRecentsImpl.getConfiguration().isGridEnabled
                    ? DOCK_AREA_GRID_BG_COLOR : DOCK_AREA_BG_COLOR);
            dockAreaOverlay.setAlpha(0);
            hintTextAlpha = hintAlpha;
            hintTextOrientation = hintOrientation;
            mHintTextResId = hintTextResId;
            mHintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mHintTextPaint.setColor(Color.WHITE);
        }

        /**
         * Updates the view state with the given context.
         */
        public void update(Context context) {
            Resources res = context.getResources();
            mHintText = context.getString(mHintTextResId);
            mHintTextPaint.setTextSize(res.getDimensionPixelSize(
                    R.dimen.recents_drag_hint_text_size));
            mHintTextPaint.getTextBounds(mHintText, 0, mHintText.length(), mTmpRect);
            mHintTextBounds.set((int) mHintTextPaint.measureText(mHintText), mTmpRect.height());
        }

        /**
         * Draws the current view state.
         */
        public void draw(Canvas canvas) {
            // Draw the overlay background
            if (dockAreaOverlay.getAlpha() > 0) {
                dockAreaOverlay.draw(canvas);
            }

            // Draw the hint text
            if (mHintTextAlpha > 0) {
                Rect bounds = dockAreaOverlay.getBounds();
                int x = bounds.left + (bounds.width() - mHintTextBounds.x) / 2;
                int y = bounds.top + (bounds.height() + mHintTextBounds.y) / 2;
                mHintTextPaint.setAlpha(mHintTextAlpha);
                if (hintTextOrientation == VERTICAL) {
                    canvas.save();
                    canvas.rotate(-90f, bounds.centerX(), bounds.centerY());
                }
                canvas.drawText(mHintText, x, y, mHintTextPaint);
                if (hintTextOrientation == VERTICAL) {
                    canvas.restore();
                }
            }
        }

        /**
         * Creates a new bounds and alpha animation.
         */
        public void startAnimation(Rect bounds, int areaAlpha, int hintAlpha, int duration,
                Interpolator interpolator, boolean animateAlpha, boolean animateBounds) {
            if (mDockAreaOverlayAnimator != null) {
                mDockAreaOverlayAnimator.cancel();
            }

            ObjectAnimator anim;
            ArrayList<Animator> animators = new ArrayList<>();
            if (dockAreaOverlay.getAlpha() != areaAlpha) {
                if (animateAlpha) {
                    anim = ObjectAnimator.ofInt(dockAreaOverlay,
                            Utilities.DRAWABLE_ALPHA, dockAreaOverlay.getAlpha(), areaAlpha);
                    anim.setDuration(duration);
                    anim.setInterpolator(interpolator);
                    animators.add(anim);
                } else {
                    dockAreaOverlay.setAlpha(areaAlpha);
                }
            }
            if (mHintTextAlpha != hintAlpha) {
                if (animateAlpha) {
                    anim = ObjectAnimator.ofInt(this, HINT_ALPHA, mHintTextAlpha,
                            hintAlpha);
                    anim.setDuration(150);
                    anim.setInterpolator(hintAlpha > mHintTextAlpha
                            ? Interpolators.ALPHA_IN
                            : Interpolators.ALPHA_OUT);
                    animators.add(anim);
                } else {
                    mHintTextAlpha = hintAlpha;
                    dockAreaOverlay.invalidateSelf();
                }
            }
            if (bounds != null && !dockAreaOverlay.getBounds().equals(bounds)) {
                if (animateBounds) {
                    PropertyValuesHolder prop = PropertyValuesHolder.ofObject(
                            Utilities.DRAWABLE_RECT, Utilities.RECT_EVALUATOR,
                            new Rect(dockAreaOverlay.getBounds()), bounds);
                    anim = ObjectAnimator.ofPropertyValuesHolder(dockAreaOverlay, prop);
                    anim.setDuration(duration);
                    anim.setInterpolator(interpolator);
                    animators.add(anim);
                } else {
                    dockAreaOverlay.setBounds(bounds);
                }
            }
            if (!animators.isEmpty()) {
                mDockAreaOverlayAnimator = new AnimatorSet();
                mDockAreaOverlayAnimator.playTogether(animators);
                mDockAreaOverlayAnimator.start();
            }
        }
    }

    public final int dockSide;
    public final int createMode;
    public final ViewState viewState;
    private final RectF touchArea;
    private final RectF dockArea;
    private final RectF expandedTouchDockArea;
    private static final Rect mTmpRect = new Rect();

    /**
     * @param createMode used to pass to ActivityManager to dock the task
     * @param touchArea the area in which touch will initiate this dock state
     * @param dockArea the visible dock area
     * @param expandedTouchDockArea the area in which touch will continue to dock after entering
     *                              the initial touch area.  This is also the new dock area to
     *                              draw.
     */
    DockState(int dockSide, int createMode, int dockAreaAlpha, int hintTextAlpha,
            @TextOrientation int hintTextOrientation, RectF touchArea, RectF dockArea,
            RectF expandedTouchDockArea) {
        this.dockSide = dockSide;
        this.createMode = createMode;
        this.viewState = new ViewState(dockAreaAlpha, hintTextAlpha, hintTextOrientation,
                R.string.recents_drag_hint_message);
        this.dockArea = dockArea;
        this.touchArea = touchArea;
        this.expandedTouchDockArea = expandedTouchDockArea;
    }

    /**
     * Updates the dock state with the given context.
     */
    public void update(Context context) {
        viewState.update(context);
    }

    /**
     * Returns the docked task bounds with the given {@param width} and {@param height}.
     */
    public Rect getPreDockedBounds(int width, int height, Rect insets) {
        getMappedRect(dockArea, width, height, mTmpRect);
        return updateBoundsWithSystemInsets(mTmpRect, insets);
    }

    /**
     * Returns the expanded docked task bounds with the given {@param width} and
     * {@param height}.
     */
    public Rect getDockedBounds(int width, int height, int dividerSize, Rect insets,
            Resources res) {
        // Calculate the docked task bounds
        boolean isHorizontalDivision =
                res.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        int position = DockedDividerUtils.calculateMiddlePosition(isHorizontalDivision,
                insets, width, height, dividerSize);
        Rect newWindowBounds = new Rect();
        DockedDividerUtils.calculateBoundsForPosition(position, dockSide, newWindowBounds,
                width, height, dividerSize);
        return newWindowBounds;
    }

    /**
     * Returns the task stack bounds with the given {@param width} and
     * {@param height}.
     */
    public Rect getDockedTaskStackBounds(Rect displayRect, int width, int height,
            int dividerSize, Rect insets, TaskStackLayoutAlgorithm layoutAlgorithm,
            Resources res, Rect windowRectOut) {
        // Calculate the inverse docked task bounds
        boolean isHorizontalDivision =
                res.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        int position = DockedDividerUtils.calculateMiddlePosition(isHorizontalDivision,
                insets, width, height, dividerSize);
        DockedDividerUtils.calculateBoundsForPosition(position,
                DockedDividerUtils.invertDockSide(dockSide), windowRectOut, width, height,
                dividerSize);

        // Calculate the task stack bounds from the new window bounds
        Rect taskStackBounds = new Rect();
        // If the task stack bounds is specifically under the dock area, then ignore the top
        // inset
        int top = dockArea.bottom < 1f
                ? 0
                : insets.top;
        // For now, ignore the left insets since we always dock on the left and show Recents
        // on the right
        layoutAlgorithm.getTaskStackBounds(displayRect, windowRectOut, top, 0, insets.right,
                taskStackBounds);
        return taskStackBounds;
    }

    /**
     * Returns the expanded bounds in certain dock sides such that the bounds account for the
     * system insets (namely the vertical nav bar).  This call modifies and returns the given
     * {@param bounds}.
     */
    private Rect updateBoundsWithSystemInsets(Rect bounds, Rect insets) {
        if (dockSide == DOCKED_LEFT) {
            bounds.right += insets.left;
        } else if (dockSide == DOCKED_RIGHT) {
            bounds.left -= insets.right;
        }
        return bounds;
    }

    /**
     * Returns the mapped rect to the given dimensions.
     */
    private void getMappedRect(RectF bounds, int width, int height, Rect out) {
        out.set((int) (bounds.left * width), (int) (bounds.top * height),
                (int) (bounds.right * width), (int) (bounds.bottom * height));
    }
}