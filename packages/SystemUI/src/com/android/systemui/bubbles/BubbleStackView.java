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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController.DismissReason;
import com.android.systemui.bubbles.animation.ExpandedAnimationController;
import com.android.systemui.bubbles.animation.PhysicsAnimationLayout;
import com.android.systemui.bubbles.animation.StackAnimationController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders bubbles in a stack and handles animating expanded and collapsed states.
 */
public class BubbleStackView extends FrameLayout {
    private static final String TAG = "BubbleStackView";
    private static final boolean DEBUG = false;

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
    private int mPipDismissHeight;
    private int mImeOffset;

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
        mTouchHandler = new BubbleTouchHandler(context, this);
        setOnTouchListener(mTouchHandler);
        mInflater = LayoutInflater.from(context);

        Resources res = getResources();
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubblePadding = res.getDimensionPixelSize(R.dimen.bubble_padding);
        mExpandedAnimateXDistance =
                res.getDimensionPixelSize(R.dimen.bubble_expanded_animate_x_distance);
        mExpandedAnimateYDistance =
                res.getDimensionPixelSize(R.dimen.bubble_expanded_animate_y_distance);
        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mPipDismissHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.pip_dismiss_gradient_height);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);

        mDisplaySize = new Point();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getSize(mDisplaySize);

        int padding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        int elevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);

        mStackAnimationController = new StackAnimationController();
        mExpandedAnimationController = new ExpandedAnimationController(mDisplaySize);

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
        setFocusable(true);
        mBubbleContainer.bringToFront();
    }

    @Override
    public void getBoundsOnScreen(Rect outRect, boolean clipToParent) {
        getBoundsOnScreen(outRect);
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

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        if (mIsExpanded) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_DISMISS:
                stackDismissed(BubbleController.DISMISS_ACCESSIBILITY_ACTION);
                return true;
            case AccessibilityNodeInfo.ACTION_COLLAPSE:
                collapseStack();
                return true;
            case AccessibilityNodeInfo.ACTION_EXPAND:
                expandStack();
                return true;
        }
        return false;
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
    public void removeBubble(String key, int reason) {
        Bubble b = mBubbleData.removeBubble(key);
        if (b == null) {
            return;
        }
        int removedIndex = dismissBubble(b, reason);
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
        // TODO: consider logging reason code
        logBubbleEvent(b, StatsLog.BUBBLE_UICHANGED__ACTION__DISMISSED);
    }

    /**
     * Dismiss the stack of bubbles.
     */
    public void stackDismissed(int reason) {
        for (Bubble bubble : mBubbleData.getBubbles()) {
            dismissBubble(bubble, reason);
        }
        mBubbleData.clear();
        collapseStack();
        mBubbleContainer.removeAllViews();
        mExpandedViewContainer.removeAllViews();
        // TODO: consider logging reason code
        logBubbleEvent(null /* no bubble associated with bubble stack dismiss */,
                StatsLog.BUBBLE_UICHANGED__ACTION__STACK_DISMISSED);
    }

    /**
     * Marks the notification entry as dismissed, cleans up Bubble icon and expanded view UI
     * elements and calls deleteIntent if necessary.
     *
     * <p>Note: This does not remove the Bubble from BubbleData.
     *
     * @param bubble the Bubble being dismissed
     * @param reason code for the reason the dismiss was triggered
     * @see BubbleController.DismissReason
     */
    private int dismissBubble(Bubble bubble, @DismissReason int reason) {
        if (DEBUG) {
            Log.d(TAG, "dismissBubble: " + bubble + " reason=" + reason);
        }
        bubble.entry.setBubbleDismissed(true);
        bubble.expandedView.cleanUpExpandedState();

        // Remove it from the views
        int removedIndex = mBubbleContainer.indexOfChild(bubble.iconView);
        mBubbleContainer.removeViewAt(removedIndex);

        if (reason == BubbleController.DISMISS_USER_GESTURE) {
            Notification.BubbleMetadata bubbleMetadata = bubble.entry.getBubbleMetadata();
            PendingIntent deleteIntent = bubbleMetadata.getDeleteIntent();
            if (deleteIntent != null) {
                try {
                    deleteIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send delete intent for bubble with key: "
                            + (bubble.entry != null ? bubble.entry.key : " null entry"));
                }
            }
        }
        return removedIndex;
    }

    /**
     * Updates a bubble in the stack.
     *
     * @param entry the entry to update in the stack.
     * @param updatePosition whether this bubble should be moved to top of the stack.
     */
    public void updateBubble(NotificationEntry entry, boolean updatePosition) {
        Bubble b = mBubbleData.getBubble(entry.key);
        mBubbleData.updateBubble(entry.key, entry);

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
     * Expands the stack of bubbles.
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
                        /* collapseTo */
                        mStackAnimationController.getStackPositionAlongNearestHorizontalEdge(),
                        /* after */
                        () -> {
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
            final float yDest = getYPositionForExpandedView();

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

    /** Moves the bubbles out of the way if they're going to be over the keyboard. */
    public void onImeVisibilityChanged(boolean visible, int height) {
        if (!mIsExpanded) {
            if (visible) {
                mStackAnimationController.updateBoundsForVisibleImeAndAnimate(height + mImeOffset);
            } else {
                mStackAnimationController.updateBoundsForInvisibleImeAndAnimate();
            }
        }
    }

    /** Called when a drag operation on an individual bubble has started. */
    public void onBubbleDragStart(View bubble) {
        mExpandedAnimationController.prepareForBubbleDrag(bubble);
    }

    /** Called with the coordinates to which an individual bubble has been dragged. */
    public void onBubbleDragged(View bubble, float x, float y) {
        if (!mIsExpanded || mIsAnimating) {
            return;
        }

        mExpandedAnimationController.dragBubbleOut(bubble, x, y);
    }

    /** Called when a drag operation on an individual bubble has finished. */
    public void onBubbleDragFinish(
            View bubble, float x, float y, float velX, float velY, boolean dismissed) {
        if (!mIsExpanded || mIsAnimating) {
            return;
        }

        if (dismissed) {
            mExpandedAnimationController.prepareForDismissalWithVelocity(bubble, velX, velY);
        } else {
            mExpandedAnimationController.snapBubbleBack(bubble, velX, velY);
        }
    }

    void onDragStart() {
        if (mIsExpanded || mIsAnimating) {
            return;
        }

        mStackAnimationController.cancelStackPositionAnimations();
        mBubbleContainer.setController(mStackAnimationController);
    }

    void onDragged(float x, float y) {
        if (mIsExpanded || mIsAnimating) {
            return;
        }

        mStackAnimationController.moveFirstBubbleWithStackFollowing(x, y);
    }

    void onDragFinish(float x, float y, float velX, float velY) {
        // TODO: Add fling to bottom to dismiss.

        if (mIsExpanded || mIsAnimating) {
            return;
        }

        mStackAnimationController.flingStackThenSpringToEdge(x, velX, velY);
        logBubbleEvent(null /* no bubble associated with bubble stack move */,
                StatsLog.BUBBLE_UICHANGED__ACTION__STACK_MOVED);
    }

    /**
     * Calculates how large the expanded view of the bubble can be. This takes into account the
     * y position when the bubbles are expanded as well as the bounds of the dismiss target.
     */
    int getMaxExpandedHeight() {
        boolean showOnTop = BubbleController.showBubblesAtTop(getContext());
        int expandedY = (int) mExpandedAnimationController.getExpandedY();
        if (showOnTop) {
            // PIP dismiss view uses FLAG_LAYOUT_IN_SCREEN so we need to subtract the bottom inset
            int pipDismissHeight = mPipDismissHeight - getBottomInset();
            return mDisplaySize.y - expandedY - mBubbleSize - pipDismissHeight;
        } else {
            return expandedY - getStatusBarHeight();
        }
    }

    /**
     * Calculates the y position of the expanded view when it is expanded.
     */
    float getYPositionForExpandedView() {
        boolean showOnTop = BubbleController.showBubblesAtTop(getContext());
        if (showOnTop) {
            return getStatusBarHeight() + mBubbleSize + mBubblePadding;
        } else {
            return mExpandedAnimationController.getExpandedY()
                    - mExpandedBubble.expandedView.getExpandedSize() - mBubblePadding;
        }
    }

    /**
     * Called when the height of the currently expanded view has changed (not via an
     * update to the bubble's desired height but for some other reason, e.g. permission view
     * goes away).
     */
    void onExpandedHeightChanged() {
        if (mIsExpanded) {
            requestUpdate();
        }
    }

    @Override
    public void getBoundsOnScreen(Rect outRect) {
        if (!mIsExpanded) {
            if (mBubbleContainer.getChildCount() > 0) {
                mBubbleContainer.getChildAt(0).getBoundsOnScreen(outRect);
            }
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

    private int getBottomInset() {
        if (getRootWindowInsets() != null) {
            WindowInsets insets = getRootWindowInsets();
            return insets.getSystemWindowInsetBottom();
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
            mExpandedBubble.expandedView.populateExpandedView();
            mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        }
    }

    private void applyCurrentState() {
        Log.d(TAG, "applyCurrentState: mIsExpanded=" + mIsExpanded);

        mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        if (mIsExpanded) {
            // First update the view so that it calculates a new height (ensuring the y position
            // calculation is correct)
            mExpandedBubble.expandedView.updateView();
            final float y = getYPositionForExpandedView();
            mExpandedViewContainer.setTranslationY(y);
            // Then update the view so that ActivityView knows we translated
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
            float pointerPosition = mExpandedBubble.iconView.getTranslationX()
                    + (mExpandedBubble.iconView.getWidth() / 2f);
            mExpandedBubble.expandedView.setPointerPosition((int) pointerPosition);
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
    int getBubbleIndex(@Nullable Bubble bubble) {
        if (bubble == null) {
            return 0;
        }
        return mBubbleContainer.indexOfChild(bubble.iconView);
    }

    /**
     * @return the normalized x-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedXPosition() {
        return new BigDecimal(getStackPosition().x / mDisplaySize.x)
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    /**
     * @return the normalized y-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedYPosition() {
        return new BigDecimal(getStackPosition().y / mDisplaySize.y)
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    public PointF getStackPosition() {
        return mStackAnimationController.getStackPosition();
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

    /**
     * Called when a back gesture should be directed to the Bubbles stack. When expanded,
     * a back key down/up event pair is forwarded to the bubble Activity.
     */
    boolean performBackPressIfNeeded() {
        if (!isExpanded()) {
            return false;
        }
        return mExpandedBubble.expandedView.performBackPressIfNeeded();
    }
}
