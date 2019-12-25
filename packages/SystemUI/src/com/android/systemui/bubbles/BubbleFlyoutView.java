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

package com.android.systemui.bubbles;

import static android.graphics.Paint.ANTI_ALIAS_FLAG;
import static android.graphics.Paint.FILTER_BITMAP_FLAG;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;

/**
 * Flyout view that appears as a 'chat bubble' alongside the bubble stack. The flyout can visually
 * transform into the 'new' dot, which is used during flyout dismiss animations/gestures.
 */
public class BubbleFlyoutView extends FrameLayout {
    /** Max width of the flyout, in terms of percent of the screen width. */
    private static final float FLYOUT_MAX_WIDTH_PERCENT = .6f;

    private final int mFlyoutPadding;
    private final int mFlyoutSpaceFromBubble;
    private final int mPointerSize;
    private final int mBubbleSize;
    private final int mBubbleIconBitmapSize;
    private final float mBubbleIconTopPadding;

    private final int mFlyoutElevation;
    private final int mBubbleElevation;
    private final int mFloatingBackgroundColor;
    private final float mCornerRadius;

    private final ViewGroup mFlyoutTextContainer;
    private final TextView mFlyoutText;

    /** Values related to the 'new' dot which we use to figure out where to collapse the flyout. */
    private final float mNewDotRadius;
    private final float mNewDotSize;
    private final float mOriginalDotSize;

    /**
     * The paint used to draw the background, whose color changes as the flyout transitions to the
     * tinted 'new' dot.
     */
    private final Paint mBgPaint = new Paint(ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG);
    private final ArgbEvaluator mArgbEvaluator = new ArgbEvaluator();

    /**
     * Triangular ShapeDrawables used for the triangle that points from the flyout to the bubble
     * stack (a chat-bubble effect).
     */
    private final ShapeDrawable mLeftTriangleShape;
    private final ShapeDrawable mRightTriangleShape;

    /** Whether the flyout arrow is on the left (pointing left) or right (pointing right). */
    private boolean mArrowPointingLeft = true;

    /** Color of the 'new' dot that the flyout will transform into. */
    private int mDotColor;

    /** The outline of the triangle, used for elevation shadows. */
    private final Outline mTriangleOutline = new Outline();

    /** The bounds of the flyout background, kept up to date as it transitions to the 'new' dot. */
    private final RectF mBgRect = new RectF();

    /**
     * Percent progress in the transition from flyout to 'new' dot. These two values are the inverse
     * of each other (if we're 40% transitioned to the dot, we're 60% flyout), but it makes the code
     * much more readable.
     */
    private float mPercentTransitionedToDot = 1f;
    private float mPercentStillFlyout = 0f;

    /**
     * The difference in values between the flyout and the dot. These differences are gradually
     * added over the course of the animation to transform the flyout into the 'new' dot.
     */
    private float mFlyoutToDotWidthDelta = 0f;
    private float mFlyoutToDotHeightDelta = 0f;

    /** The translation values when the flyout is completely transitioned into the dot. */
    private float mTranslationXWhenDot = 0f;
    private float mTranslationYWhenDot = 0f;

    /**
     * The current translation values applied to the flyout background as it transitions into the
     * 'new' dot.
     */
    private float mBgTranslationX;
    private float mBgTranslationY;

    private float[] mDotCenter;

    /** The flyout's X translation when at rest (not animating or dragging). */
    private float mRestingTranslationX = 0f;

    /** The badge sizes are defined as percentages of the app icon size. Same value as Launcher3. */
    private static final float SIZE_PERCENTAGE = 0.228f;

    private static final float DOT_SCALE = 1f;

    /** Callback to run when the flyout is hidden. */
    @Nullable private Runnable mOnHide;

    public BubbleFlyoutView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.bubble_flyout, this, true);

        mFlyoutTextContainer = findViewById(R.id.bubble_flyout_text_container);
        mFlyoutText = mFlyoutTextContainer.findViewById(R.id.bubble_flyout_text);

        final Resources res = getResources();
        mFlyoutPadding = res.getDimensionPixelSize(R.dimen.bubble_flyout_padding_x);
        mFlyoutSpaceFromBubble = res.getDimensionPixelSize(R.dimen.bubble_flyout_space_from_bubble);
        mPointerSize = res.getDimensionPixelSize(R.dimen.bubble_flyout_pointer_size);

        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubbleIconBitmapSize = res.getDimensionPixelSize(R.dimen.bubble_icon_bitmap_size);
        mBubbleIconTopPadding  = (mBubbleSize - mBubbleIconBitmapSize) / 2f;

        mBubbleElevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);
        mFlyoutElevation = res.getDimensionPixelSize(R.dimen.bubble_flyout_elevation);

        mOriginalDotSize = SIZE_PERCENTAGE * mBubbleIconBitmapSize;
        mNewDotRadius = (DOT_SCALE * mOriginalDotSize) / 2f;
        mNewDotSize = mNewDotRadius * 2f;

        final TypedArray ta = mContext.obtainStyledAttributes(
                new int[] {
                        android.R.attr.colorBackgroundFloating,
                        android.R.attr.dialogCornerRadius});
        mFloatingBackgroundColor = ta.getColor(0, Color.WHITE);
        mCornerRadius = ta.getDimensionPixelSize(1, 0);
        ta.recycle();

        // Add padding for the pointer on either side, onDraw will draw it in this space.
        setPadding(mPointerSize, 0, mPointerSize, 0);
        setWillNotDraw(false);
        setClipChildren(false);
        setTranslationZ(mFlyoutElevation);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                BubbleFlyoutView.this.getOutline(outline);
            }
        });

        mBgPaint.setColor(mFloatingBackgroundColor);

        mLeftTriangleShape =
                new ShapeDrawable(TriangleShape.createHorizontal(
                        mPointerSize, mPointerSize, true /* isPointingLeft */));
        mLeftTriangleShape.setBounds(0, 0, mPointerSize, mPointerSize);
        mLeftTriangleShape.getPaint().setColor(mFloatingBackgroundColor);

        mRightTriangleShape =
                new ShapeDrawable(TriangleShape.createHorizontal(
                        mPointerSize, mPointerSize, false /* isPointingLeft */));
        mRightTriangleShape.setBounds(0, 0, mPointerSize, mPointerSize);
        mRightTriangleShape.getPaint().setColor(mFloatingBackgroundColor);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderBackground(canvas);
        invalidateOutline();
        super.onDraw(canvas);
    }

    /** Configures the flyout, collapsed into to dot form. */
    void setupFlyoutStartingAsDot(
            CharSequence updateMessage, PointF stackPos, float parentWidth,
            boolean arrowPointingLeft, int dotColor, @Nullable Runnable onLayoutComplete,
            @Nullable Runnable onHide, float[] dotCenter) {
        mArrowPointingLeft = arrowPointingLeft;
        mDotColor = dotColor;
        mOnHide = onHide;
        mDotCenter = dotCenter;

        setCollapsePercent(1f);

        // Set the flyout TextView's max width in terms of percent, and then subtract out the
        // padding so that the entire flyout view will be the desired width (rather than the
        // TextView being the desired width + extra padding).
        mFlyoutText.setMaxWidth(
                (int) (parentWidth * FLYOUT_MAX_WIDTH_PERCENT) - mFlyoutPadding * 2);
        mFlyoutText.setText(updateMessage);

        // Wait for the TextView to lay out so we know its line count.
        post(() -> {
            float restingTranslationY;
            // Multi line flyouts get top-aligned to the bubble.
            if (mFlyoutText.getLineCount() > 1) {
                restingTranslationY = stackPos.y + mBubbleIconTopPadding;
            } else {
                // Single line flyouts are vertically centered with respect to the bubble.
                restingTranslationY =
                        stackPos.y + (mBubbleSize - mFlyoutTextContainer.getHeight()) / 2f;
            }
            setTranslationY(restingTranslationY);

            // Calculate the translation required to position the flyout next to the bubble stack,
            // with the desired padding.
            mRestingTranslationX = mArrowPointingLeft
                    ? stackPos.x + mBubbleSize + mFlyoutSpaceFromBubble
                    : stackPos.x - getWidth() - mFlyoutSpaceFromBubble;

            // Calculate the difference in size between the flyout and the 'dot' so that we can
            // transform into the dot later.
            mFlyoutToDotWidthDelta = getWidth() - mNewDotSize;
            mFlyoutToDotHeightDelta = getHeight() - mNewDotSize;

            // Calculate the translation values needed to be in the correct 'new dot' position.
            final float dotPositionX = stackPos.x + mDotCenter[0] - (mOriginalDotSize / 2f);
            final float dotPositionY = stackPos.y + mDotCenter[1] - (mOriginalDotSize / 2f);

            final float distanceFromFlyoutLeftToDotCenterX = mRestingTranslationX - dotPositionX;
            final float distanceFromLayoutTopToDotCenterY = restingTranslationY - dotPositionY;

            mTranslationXWhenDot = -distanceFromFlyoutLeftToDotCenterX;
            mTranslationYWhenDot = -distanceFromLayoutTopToDotCenterY;
            if (onLayoutComplete != null) {
                onLayoutComplete.run();
            }
        });
    }

    /**
     * Hides the flyout and runs the optional callback passed into setupFlyoutStartingAsDot.
     * The flyout has been animated into the 'new' dot by the time we call this, so no animations
     * are needed.
     */
    void hideFlyout() {
        if (mOnHide != null) {
            mOnHide.run();
            mOnHide = null;
        }

        setVisibility(GONE);
    }

    /** Sets the percentage that the flyout should be collapsed into dot form. */
    void setCollapsePercent(float percentCollapsed) {
        // This is unlikely, but can happen in a race condition where the flyout view hasn't been
        // laid out and returns 0 for getWidth(). We check for this condition at the sites where
        // this method is called, but better safe than sorry.
        if (Float.isNaN(percentCollapsed)) {
            return;
        }

        mPercentTransitionedToDot = Math.max(0f, Math.min(percentCollapsed, 1f));
        mPercentStillFlyout = (1f - mPercentTransitionedToDot);

        // Move and fade out the text.
        mFlyoutText.setTranslationX(
                (mArrowPointingLeft ? -getWidth() : getWidth()) * mPercentTransitionedToDot);
        mFlyoutText.setAlpha(clampPercentage(
                (mPercentStillFlyout - (1f - BubbleStackView.FLYOUT_DRAG_PERCENT_DISMISS))
                        / BubbleStackView.FLYOUT_DRAG_PERCENT_DISMISS));

        // Reduce the elevation towards that of the topmost bubble.
        setTranslationZ(
                mFlyoutElevation
                        - (mFlyoutElevation - mBubbleElevation) * mPercentTransitionedToDot);
        invalidate();
    }

    /** Return the flyout's resting X translation (translation when not dragging or animating). */
    float getRestingTranslationX() {
        return mRestingTranslationX;
    }

    /** Clamps a float to between 0 and 1. */
    private float clampPercentage(float percent) {
        return Math.min(1f, Math.max(0f, percent));
    }

    /**
     * Renders the background, which is either the rounded 'chat bubble' flyout, or some state
     * between that and the 'new' dot over the bubbles.
     */
    private void renderBackground(Canvas canvas) {
        // Calculate the width, height, and corner radius of the flyout given the current collapsed
        // percentage.
        final float width = getWidth() - (mFlyoutToDotWidthDelta * mPercentTransitionedToDot);
        final float height = getHeight() - (mFlyoutToDotHeightDelta * mPercentTransitionedToDot);
        final float interpolatedRadius = mNewDotRadius * mPercentTransitionedToDot
                + mCornerRadius * (1 - mPercentTransitionedToDot);

        // Translate the flyout background towards the collapsed 'dot' state.
        mBgTranslationX = mTranslationXWhenDot * mPercentTransitionedToDot;
        mBgTranslationY = mTranslationYWhenDot * mPercentTransitionedToDot;

        // Set the bounds of the rounded rectangle that serves as either the flyout background or
        // the collapsed 'dot'. These bounds will also be used to provide the outline for elevation
        // shadows. In the expanded flyout state, the left and right bounds leave space for the
        // pointer triangle - as the flyout collapses, this space is reduced since the triangle
        // retracts into the flyout.
        mBgRect.set(
                mPointerSize * mPercentStillFlyout /* left */,
                0 /* top */,
                width - mPointerSize * mPercentStillFlyout /* right */,
                height /* bottom */);

        mBgPaint.setColor(
                (int) mArgbEvaluator.evaluate(
                        mPercentTransitionedToDot, mFloatingBackgroundColor, mDotColor));

        canvas.save();
        canvas.translate(mBgTranslationX, mBgTranslationY);
        renderPointerTriangle(canvas, width, height);
        canvas.drawRoundRect(mBgRect, interpolatedRadius, interpolatedRadius, mBgPaint);
        canvas.restore();
    }

    /** Renders the 'pointer' triangle that points from the flyout to the bubble stack. */
    private void renderPointerTriangle(
            Canvas canvas, float currentFlyoutWidth, float currentFlyoutHeight) {
        canvas.save();

        // Translation to apply for the 'retraction' effect as the flyout collapses.
        final float retractionTranslationX =
                (mArrowPointingLeft ? 1 : -1) * (mPercentTransitionedToDot * mPointerSize * 2f);

        // Place the arrow either at the left side, or the far right, depending on whether the
        // flyout is on the left or right side.
        final float arrowTranslationX =
                mArrowPointingLeft
                        ? retractionTranslationX
                        : currentFlyoutWidth - mPointerSize + retractionTranslationX;

        // Vertically center the arrow at all times.
        final float arrowTranslationY = currentFlyoutHeight / 2f - mPointerSize / 2f;

        // Draw the appropriate direction of arrow.
        final ShapeDrawable relevantTriangle =
                mArrowPointingLeft ? mLeftTriangleShape : mRightTriangleShape;
        canvas.translate(arrowTranslationX, arrowTranslationY);
        relevantTriangle.setAlpha((int) (255f * mPercentStillFlyout));
        relevantTriangle.draw(canvas);

        // Save the triangle's outline for use in the outline provider, offsetting it to reflect its
        // current position.
        relevantTriangle.getOutline(mTriangleOutline);
        mTriangleOutline.offset((int) arrowTranslationX, (int) arrowTranslationY);

        canvas.restore();
    }

    /** Builds an outline that includes the transformed flyout background and triangle. */
    private void getOutline(Outline outline) {
        if (!mTriangleOutline.isEmpty()) {
            // Draw the rect into the outline as a path so we can merge the triangle path into it.
            final Path rectPath = new Path();
            final float interpolatedRadius = mNewDotRadius * mPercentTransitionedToDot
                    + mCornerRadius * (1 - mPercentTransitionedToDot);
            rectPath.addRoundRect(mBgRect, interpolatedRadius,
                    interpolatedRadius, Path.Direction.CW);
            outline.setConvexPath(rectPath);

            // Get rid of the triangle path once it has disappeared behind the flyout.
            if (mPercentStillFlyout > 0.5f) {
                outline.mPath.addPath(mTriangleOutline.mPath);
            }

            // Translate the outline to match the background's position.
            final Matrix outlineMatrix = new Matrix();
            outlineMatrix.postTranslate(getLeft() + mBgTranslationX, getTop() + mBgTranslationY);

            // At the very end, retract the outline into the bubble so the shadow will be pulled
            // into the flyout-dot as it (visually) becomes part of the bubble. We can't do this by
            // animating translationZ to zero since then it'll go under the bubbles, which have
            // elevation.
            if (mPercentTransitionedToDot > 0.98f) {
                final float percentBetween99and100 = (mPercentTransitionedToDot - 0.98f) / .02f;
                final float percentShadowVisible = 1f - percentBetween99and100;

                // Keep it centered.
                outlineMatrix.postTranslate(
                        mNewDotRadius * percentBetween99and100,
                        mNewDotRadius * percentBetween99and100);
                outlineMatrix.preScale(percentShadowVisible, percentShadowVisible);
            }

            outline.mPath.transform(outlineMatrix);
        }
    }
}
