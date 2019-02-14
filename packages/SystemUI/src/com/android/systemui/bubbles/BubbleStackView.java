/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.StatsLog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.R;
import com.android.systemui.bubbles.animation.ExpandedAnimationController;
import com.android.systemui.bubbles.animation.PhysicsAnimationLayout;
import com.android.systemui.bubbles.animation.StackAnimationController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders bubbles in a stack and handles animating expanded and collapsed states.
 */
public class BubbleStackView extends FrameLayout implements BubbleTouchHandler.FloatingView {
    private static final String TAG = "BubbleStackView";

    /**
     * Friction applied to fling animations. Since the stack must land on one of the sides of the
     * screen, we want less friction horizontally so that the stack has a better chance of making it
     * to the side without needing a spring.
     */
    private static final float FLING_FRICTION_X = 1.15f;
    private static final float FLING_FRICTION_Y = 1.5f;

    /**
     * Damping ratio to use for the stack spring animation used to spring the stack to its final
     * position after a fling.
     */
    private static final float SPRING_DAMPING_RATIO = 0.85f;

    /**
     * Minimum fling velocity required to trigger moving the stack from one side of the screen to
     * the other.
     */
    private static final float ESCAPE_VELOCITY = 750f;

    private Point mDisplaySize;

    private final SpringAnimation mExpandedViewXAnim;
    private final SpringAnimation mExpandedViewYAnim;
    private final BubbleData mBubbleData;

    private PhysicsAnimationLayout mBubbleContainer;
    private StackAnimationController mStackAnimationController;
    private ExpandedAnimationController mExpandedAnimationController;

    private FrameLayout mExpandedViewContainer;


    private int mBubbleSize;
    private int mBubblePadding;
    private int mExpandedAnimateXDistance;
    private int mExpandedAnimateYDistance;
    private int mStatusBarHeight;

    private Bubble mExpandedBubble;
    private boolean mIsExpanded;

    private BubbleTouchHandler mTouchHandler;
    private BubbleController.BubbleExpandListener mExpandListener;
    private BubbleExpandedView.OnBubbleBlockedListener mBlockedListener;

    private boolean mViewUpdatedRequested = false;
    private boolean mIsAnimating = false;

    private LayoutInflater mInflater;

    // Used for determining view / touch intersection
    int[] mTempLoc = new int[2];
    RectF mTempRect = new RectF();

    private ViewTreeObserver.OnPreDrawListener mViewUpdater =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
                    applyCurrentState();
                    mViewUpdatedRequested = false;
                    return true;
                }
            };

    private ViewClippingUtil.ClippingParameters mClippingParameters =
            new ViewClippingUtil.ClippingParameters() {

        @Override
        public boolean shouldFinish(View view) {
            return false;
        }

        @Override
        public boolean isClippingEnablingAllowed(View view) {
            return !mIsExpanded;
        }
    };

    public BubbleStackView(Context context, BubbleData data) {
        super(context);

        mBubbleData = data;
        mInflater = LayoutInflater.from(context);
        mTouchHandler = new BubbleTouchHandler(context);
        setOnTouchListener(mTouchHandler);

        Resources res = getResources();
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubblePadding = res.getDimensionPixelSize(R.dimen.bubble_padding);
        mExpandedAnimateXDistance =
                res.getDimensionPixelSize(R.dimen.bubble_expanded_animate_x_distance);
        mExpandedAnimateYDistance =
                res.getDimensionPixelSize(R.dimen.bubble_expanded_animate_y_distance);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);

        mDisplaySize = new Point();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getSize(mDisplaySize);

        int padding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        int elevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);

        mStackAnimationController = new StackAnimationController();
        mExpandedAnimationController = new ExpandedAnimationController();

        mBubbleContainer = new PhysicsAnimationLayout(context);
        mBubbleContainer.setMaxRenderedChildren(
                getResources().getInteger(R.integer.bubbles_max_rendered));
        mBubbleContainer.setController(mStackAnimationController);
        mBubbleContainer.setElevation(elevation);
        mBubbleContainer.setPadding(padding, 0, padding, 0);
        mBubbleContainer.setClipChildren(false);
        addView(mBubbleContainer, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        mExpandedViewContainer = new FrameLayout(context);
        mExpandedViewContainer.setElevation(elevation);
        mExpandedViewContainer.setPadding(padding, padding, padding, padding);
        mExpandedViewContainer.setClipChildren(false);
        addView(mExpandedViewContainer);

        mExpandedViewXAnim =
                new SpringAnimation(mExpandedViewContainer, DynamicAnimation.TRANSLATION_X);
        mExpandedViewXAnim.setSpring(
                new SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_LOW)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));

        mExpandedViewYAnim =
                new SpringAnimation(mExpandedViewContainer, DynamicAnimation.TRANSLATION_Y);
        mExpandedViewYAnim.setSpring(
                new SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_LOW)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));

        setClipChildren(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        float x = ev.getRawX();
        float y = ev.getRawY();
        // If we're expanded only intercept if the tap is outside of the widget container
        if (mIsExpanded && isIntersecting(mExpandedViewContainer, x, y)) {
            return false;
        } else {
            return isIntersecting(mBubbleContainer, x, y);
        }
    }

    /**
     * Updates the visibility of the 'dot' indicating an update on the bubble.
     * @param key the {@link NotificationEntry#key} associated with the bubble.
     */
    public void updateDotVisibility(String key) {
        Bubble b = mBubbleData.getBubble(key);
        if (b != null) {
            b.iconView.updateDotVisibility();
        }
    }

    /**
     * Sets the listener to notify when the bubble stack is expanded.
     */
    public void setExpandListener(BubbleController.BubbleExpandListener listener) {
        mExpandListener = listener;
    }

    /**
     * Sets the listener to notify when a bubble is blocked.
     */
    public void setOnBlockedListener(BubbleExpandedView.OnBubbleBlockedListener listener) {
        mBlockedListener = listener;
        for (Bubble b : mBubbleData.getBubbles()) {
            b.expandedView.setOnBlockedListener(mBlockedListener);
        }
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isExpanded() {
        return mIsExpanded;
    }

    /**
     * The {@link BubbleView} that is expanded, null if one does not exist.
     */
    BubbleView getExpandedBubbleView() {
        return mExpandedBubble != null ? mExpandedBubble.iconView : null;
    }

    /**
     * The {@link Bubble} that is expanded, null if one does not exist.
     */
    Bubble getExpandedBubble() {
        return mExpandedBubble;
    }

    /**
     * Sets the bubble that should be expanded and expands if needed.
     *
     * @param key the {@link NotificationEntry#key} associated with the bubble to expand.
     */
    void setExpandedBubble(String key) {
        Bubble bubbleToExpand = mBubbleData.getBubble(key);
        if (mIsExpanded && !bubbleToExpand.equals(mExpandedBubble)) {
            // Previously expanded, notify that this bubble is no longer expanded
            notifyExpansionChanged(mExpandedBubble.entry, false /* expanded */);
        }
        Bubble prevBubble = mExpandedBubble;
        mExpandedBubble = bubbleToExpand;
        if (!mIsExpanded) {
            // If we weren't previously expanded we should animate open.
            animateExpansion(true /* expand */);
            logBubbleEvent(mExpandedBubble, StatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
        } else {
            // Otherwise just update the views
            // TODO: probably animate / page to expanded one
            updateExpandedBubble();
            updatePointerPosition();
            requestUpdate();
            logBubbleEvent(prevBubble, StatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
            logBubbleEvent(mExpandedBubble, StatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
        }
        mExpandedBubble.entry.setShowInShadeWhenBubble(false);
        notifyExpansionChanged(mExpandedBubble.entry, true /* expanded */);
    }

    /**
     * Sets the entry that should be expanded and expands if needed.
     */
    @VisibleForTesting
    public void setExpandedBubble(NotificationEntry entry) {
        for (int i = 0; i < mBubbleContainer.getChildCount(); i++) {
            BubbleView bv = (BubbleView) mBubbleContainer.getChildAt(i);
            if (entry.equals(bv.getEntry())) {
                setExpandedBubble(entry.key);
            }
        }
    }

    /**
     * Adds a bubble to the top of the stack.
     *
     * @param entry the notification to add to the stack of bubbles.
     */
    public void addBubble(NotificationEntry entry) {
        Bubble b = new Bubble(entry, mInflater, this /* stackView */, mBlockedListener);
        mBubbleData.addBubble(b);

        mBubbleContainer.addView(b.iconView, 0,
                new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        ViewClippingUtil.setClippingDeactivated(b.iconView, true, mClippingParameters);

        requestUpdate();
        logBubbleEvent(b, StatsLog.BUBBLE_UICHANGED__ACTION__POSTED);
    }

    /**
     * Remove a bubble from the stack.
     */
    public void removeBubble(String key) {
        Bubble b = mBubbleData.removeBubble(key);
        if (b == null) {
            return;
        }
        b.entry.setBubbleDismissed(true);

        // Remove it from the views
        int removedIndex = mBubbleContainer.indexOfChild(b.iconView);
        b.expandedView.destroyActivityView(this /* tmpParent */);
        mBubbleContainer.removeView(b.iconView);

        int bubbleCount = mBubbleContainer.getChildCount();
        if (bubbleCount == 0) {
            // If no bubbles remain, collapse the entire stack.
            collapseStack();
            return;
        } else if (b.equals(mExpandedBubble)) {
            // Was the current bubble just removed?
            // If we have other bubbles and are expanded go to the next one or previous
            // if the bubble removed was last
            int nextIndex = bubbleCount > removedIndex ? removedIndex : bubbleCount - 1;
            BubbleView expandedBubble = (BubbleView) mBubbleContainer.getChildAt(nextIndex);
            if (mIsExpanded) {
                setExpandedBubble(expandedBubble.getKey());
            } else {
                mExpandedBubble = null;
            }
        }
        logBubbleEvent(b, StatsLog.BUBBLE_UICHANGED__ACTION__DISMISSED);
    }

    /**
     * Dismiss the stack of bubbles.
     */
    public void stackDismissed() {
        for (Bubble bubble : mBubbleData.getBubbles()) {
            bubble.entry.setBubbleDismissed(true);
            bubble.expandedView.destroyActivityView(this /* tmpParent */);
        }
        mBubbleData.clear();
        collapseStack();
        mBubbleContainer.removeAllViews();
        mExpandedViewContainer.removeAllViews();
        logBubbleEvent(null /* no bubble associated with bubble stack dismiss */,
                StatsLog.BUBBLE_UICHANGED__ACTION__STACK_DISMISSED);
    }

    /**
     * Updates a bubble in the stack.
     *
     * @param entry the entry to update in the stack.
     * @param updatePosition whether this bubble should be moved to top of the stack.
     */
    public void updateBubble(NotificationEntry entry, boolean updatePosition) {
        Bubble b = mBubbleData.getBubble(entry.key);
        b.iconView.update(entry);
        // TODO: should also update the expanded view here (e.g. height change)

        if (updatePosition && !mIsExpanded) {
            // If alerting it gets promoted to top of the stack.
            if (mBubbleContainer.indexOfChild(b.iconView) != 0) {
                mBubbleContainer.moveViewTo(b.iconView, 0);
            }
            requestUpdate();
        }
        if (mIsExpanded && entry.equals(mExpandedBubble.entry)) {
            entry.setShowInShadeWhenBubble(false);
            requestUpdate();
        }
        logBubbleEvent(b, StatsLog.BUBBLE_UICHANGED__ACTION__UPDATED);
    }

    /**
     * @return the view the touch event is on
     */
    @Nullable
    public View getTargetView(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();
        if (mIsExpanded) {
            if (isIntersecting(mBubbleContainer, x, y)) {
                for (int i = 0; i < mBubbleContainer.getChildCount(); i++) {
                    BubbleView view = (BubbleView) mBubbleContainer.getChildAt(i);
                    if (isIntersecting(view, x, y)) {
                        return view;
                    }
                }
            } else if (isIntersecting(mExpandedViewContainer, x, y)) {
                return mExpandedViewContainer;
            }
            // Outside parts of view we care about.
            return null;
        }
        // If we're collapsed, the stack is always the target.
        return this;
    }

    /**
     * Collapses the stack of bubbles.
     * <p>
     * Must be called from the main thread.
     */
    @MainThread
    public void collapseStack() {
        if (mIsExpanded) {
            // TODO: Save opened bubble & move it to top of stack
            animateExpansion(false /* shouldExpand */);
            notifyExpansionChanged(mExpandedBubble.entry, mIsExpanded);
            logBubbleEvent(mExpandedBubble, StatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
        }
    }

    void collapseStack(Runnable endRunnable) {
        collapseStack();
        // TODO - use the runnable at end of animation
        endRunnable.run();
    }

    /**
     * Expands the stack fo bubbles.
     * <p>
     * Must be called from the main thread.
     */
    @MainThread
    public void expandStack() {
        if (!mIsExpanded) {
            String expandedBubbleKey = getBubbleAt(0).getKey();
            setExpandedBubble(expandedBubbleKey);
            logBubbleEvent(mExpandedBubble, StatsLog.BUBBLE_UICHANGED__ACTION__STACK_EXPANDED);
        }
    }

    /**
     * Tell the stack to animate to collapsed or expanded state.
     */
    private void animateExpansion(boolean shouldExpand) {
        if (mIsExpanded != shouldExpand) {
            mIsExpanded = shouldExpand;
            updateExpandedBubble();
            applyCurrentState();

            mIsAnimating = true;

            Runnable updateAfter = () -> {
                applyCurrentState();
                mIsAnimating = false;
                requestUpdate();
            };

            if (shouldExpand) {
                mBubbleContainer.setController(mExpandedAnimationController);
                mExpandedAnimationController.expandFromStack(
                                mStackAnimationController.getStackPosition(), () -> {
                                updatePointerPosition();
                                updateAfter.run();
                        });
            } else {
                mBubbleContainer.cancelAllAnimations();
                mExpandedAnimationController.collapseBackToStack(
                        () -> {
                            mBubbleContainer.setController(mStackAnimationController);
                            updateAfter.run();
                        });
            }

            final float xStart =
                    mStackAnimationController.getStackPosition().x < getWidth() / 2
                            ? -mExpandedAnimateXDistance
                            : mExpandedAnimateXDistance;

            final float yStart = Math.min(
                    mStackAnimationController.getStackPosition().y,
                    mExpandedAnimateYDistance);
            final float yDest = getStatusBarHeight()
                    + mExpandedBubble.iconView.getHeight() + mBubblePadding;

            if (shouldExpand) {
                mExpandedViewContainer.setTranslationX(xStart);
                mExpandedViewContainer.setTranslationY(yStart);
                mExpandedViewContainer.setAlpha(0f);
            }

            mExpandedViewXAnim.animateToFinalPosition(shouldExpand ? 0f : xStart);
            mExpandedViewYAnim.animateToFinalPosition(shouldExpand ? yDest : yStart);
            mExpandedViewContainer.animate()
                    .setDuration(100)
                    .alpha(shouldExpand ? 1f : 0f);
        }
    }

    /**
     * The width of the collapsed stack of bubbles.
     */
    public int getStackWidth() {
        return mBubblePadding * (mBubbleContainer.getChildCount() - 1)
                + mBubbleSize + mBubbleContainer.getPaddingEnd()
                + mBubbleContainer.getPaddingStart();
    }

    private void notifyExpansionChanged(NotificationEntry entry, boolean expanded) {
        if (mExpandListener != null) {
            mExpandListener.onBubbleExpandChanged(expanded, entry != null ? entry.key : null);
        }
    }

    /** Return the BubbleView at the given index from the bubble container. */
    public BubbleView getBubbleAt(int i) {
        return mBubbleContainer.getChildCount() > i
                ? (BubbleView) mBubbleContainer.getChildAt(i)
                : null;
    }

    @Override
    public void setPosition(float x, float y) {
        mStackAnimationController.moveFirstBubbleWithStackFollowing(x, y);
    }

    @Override
    public void setPositionX(float x) {
        // Unsupported, use setPosition(x, y).
    }

    @Override
    public void setPositionY(float y) {
        // Unsupported, use setPosition(x, y).
    }

    @Override
    public PointF getPosition() {
        return mStackAnimationController.getStackPosition();
    }

    /** Called when a drag operation on an individual bubble has started. */
    public void onBubbleDragStart(BubbleView bubble) {
        // TODO: Save position and snap back if not dismissed.
    }

    /** Called with the coordinates to which an individual bubble has been dragged. */
    public void onBubbleDragged(BubbleView bubble, float x, float y) {
        bubble.setTranslationX(x);
        bubble.setTranslationY(y);
    }

    /** Called when a drag operation on an individual bubble has finished. */
    public void onBubbleDragFinish(BubbleView bubble, float x, float y, float velX, float velY) {
        // TODO: Add fling to bottom to dismiss.
    }

    void onDragStart() {
        if (mIsExpanded) {
            return;
        }

        mStackAnimationController.cancelStackPositionAnimations();
        mBubbleContainer.setController(mStackAnimationController);
        mIsAnimating = false;
    }

    void onDragged(float x, float y) {
        // TODO: We can drag if animating - just need to reroute inflight anims to drag point.
        if (mIsExpanded) {
            return;
        }

        mStackAnimationController.moveFirstBubbleWithStackFollowing(x, y);
    }

    void onDragFinish(float x, float y, float velX, float velY) {
        // TODO: Add fling to bottom to dismiss.

        if (mIsExpanded || mIsAnimating) {
            return;
        }

        final boolean stackOnLeftSide = x
                - mBubbleContainer.getChildAt(0).getWidth() / 2
                < mDisplaySize.x / 2;

        final boolean stackShouldFlingLeft = stackOnLeftSide
                ? velX < ESCAPE_VELOCITY
                : velX < -ESCAPE_VELOCITY;

        final RectF stackBounds = mStackAnimationController.getAllowableStackPositionRegion();

        // Target X translation (either the left or right side of the screen).
        final float destinationRelativeX = stackShouldFlingLeft
                ? stackBounds.left : stackBounds.right;

        // Minimum velocity required for the stack to make it to the side of the screen.
        final float escapeVelocity = getMinXVelocity(
                x,
                destinationRelativeX,
                FLING_FRICTION_X);

        // Use the touch event's velocity if it's sufficient, otherwise use the minimum velocity so
        // that it'll make it all the way to the side of the screen.
        final float startXVelocity = stackShouldFlingLeft
                ? Math.min(escapeVelocity, velX)
                : Math.max(escapeVelocity, velX);

        mStackAnimationController.flingThenSpringFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_X,
                startXVelocity,
                FLING_FRICTION_X,
                new SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_LOW)
                        .setDampingRatio(SPRING_DAMPING_RATIO),
                destinationRelativeX);

        mStackAnimationController.flingThenSpringFirstBubbleWithStackFollowing(
                DynamicAnimation.TRANSLATION_Y,
                velY,
                FLING_FRICTION_Y,
                new SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_LOW)
                        .setDampingRatio(SPRING_DAMPING_RATIO),
                /* destination */ null);

        logBubbleEvent(null /* no bubble associated with bubble stack move */,
                StatsLog.BUBBLE_UICHANGED__ACTION__STACK_MOVED);
    }

    /**
     * Minimum velocity, in pixels/second, required to get from x to destX while being slowed by a
     * given frictional force.
     *
     * This is not derived using real math, I just made it up because the math in FlingAnimation
     * looks hard and this seems to work. It doesn't actually matter because if it doesn't make it
     * to the edge via Fling, it'll get Spring'd there anyway.
     *
     * TODO(tsuji, or someone who likes math): Figure out math.
     */
    private float getMinXVelocity(float x, float destX, float friction) {
        return (destX - x) * (friction * 5) + ESCAPE_VELOCITY;
    }

    @Override
    public void getBoundsOnScreen(Rect outRect) {
        if (!mIsExpanded) {
            mBubbleContainer.getChildAt(0).getBoundsOnScreen(outRect);
        } else {
            mBubbleContainer.getBoundsOnScreen(outRect);
        }
    }

    private int getStatusBarHeight() {
        if (getRootWindowInsets() != null) {
            WindowInsets insets = getRootWindowInsets();
            return Math.max(
                    mStatusBarHeight,
                    insets.getDisplayCutout() != null
                            ? insets.getDisplayCutout().getSafeInsetTop()
                            : 0);
        }

        return 0;
    }

    private boolean isIntersecting(View view, float x, float y) {
        mTempLoc = view.getLocationOnScreen();
        mTempRect.set(mTempLoc[0], mTempLoc[1], mTempLoc[0] + view.getWidth(),
                mTempLoc[1] + view.getHeight());
        return mTempRect.contains(x, y);
    }

    private void requestUpdate() {
        if (mViewUpdatedRequested || mIsAnimating) {
            return;
        }
        mViewUpdatedRequested = true;
        getViewTreeObserver().addOnPreDrawListener(mViewUpdater);
        invalidate();
    }

    private void updateExpandedBubble() {
        mExpandedViewContainer.removeAllViews();
        if (mExpandedBubble != null && mIsExpanded) {
            mExpandedViewContainer.addView(mExpandedBubble.expandedView);
            mExpandedBubble.expandedView.populateActivityView();
            mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        }
    }

    private void applyCurrentState() {
        Log.d(TAG, "applyCurrentState: mIsExpanded=" + mIsExpanded);

        mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        if (mIsExpanded) {
            mExpandedBubble.expandedView.updateView();
        }

        int bubbsCount = mBubbleContainer.getChildCount();
        for (int i = 0; i < bubbsCount; i++) {
            BubbleView bv = (BubbleView) mBubbleContainer.getChildAt(i);
            bv.updateDotVisibility();
            bv.setZ(bubbsCount - i);

            // Draw the shadow around the circle inscribed within the bubble's bounds. This
            // (intentionally) does not draw a shadow behind the update dot, which should be drawing
            // its own shadow since it's on a different (higher) plane.
            bv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, mBubbleSize, mBubbleSize);
                }
            });
            bv.setClipToOutline(false);
        }
    }

    private void updatePointerPosition() {
        if (mExpandedBubble != null) {
            float pointerPosition = mExpandedBubble.iconView.getPosition().x
                    + (mExpandedBubble.iconView.getWidth() / 2f);
            mExpandedBubble.expandedView.setPointerPosition(pointerPosition);
        }
    }

    /**
     * @return the number of bubbles in the stack view.
     */
    public int getBubbleCount() {
        return mBubbleContainer.getChildCount();
    }

    /**
     * Finds the bubble index within the stack.
     *
     * @param bubble the bubble to look up.
     * @return the index of the bubble view within the bubble stack. The range of the position
     * is between 0 and the bubble count minus 1.
     */
    int getBubbleIndex(Bubble bubble) {
        return mBubbleContainer.indexOfChild(bubble.iconView);
    }

    /**
     * @return the normalized x-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedXPosition() {
        return new BigDecimal(getPosition().x / mDisplaySize.x)
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    /**
     * @return the normalized y-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedYPosition() {
        return new BigDecimal(getPosition().y / mDisplaySize.y)
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    /**
     * Logs the bubble UI event.
     *
     * @param bubble the bubble that is being interacted on. Null value indicates that
     *               the user interaction is not specific to one bubble.
     * @param action the user interaction enum.
     */
    private void logBubbleEvent(@Nullable Bubble bubble, int action) {
        if (bubble == null) {
            StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                    null /* package name */,
                    null /* notification channel */,
                    0 /* notification ID */,
                    0 /* bubble position */,
                    getBubbleCount(),
                    action,
                    getNormalizedXPosition(),
                    getNormalizedYPosition());
        } else {
            StatusBarNotification notification = bubble.entry.notification;
            StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                    notification.getPackageName(),
                    notification.getNotification().getChannelId(),
                    notification.getId(),
                    getBubbleIndex(bubble),
                    getBubbleCount(),
                    action,
                    getNormalizedXPosition(),
                    getNormalizedYPosition());
        }
    }
}
