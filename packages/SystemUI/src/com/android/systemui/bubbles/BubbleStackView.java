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

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_STACK_VIEW;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.StatsLog;
import android.view.Choreographer;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.R;
import com.android.systemui.bubbles.animation.ExpandedAnimationController;
import com.android.systemui.bubbles.animation.PhysicsAnimationLayout;
import com.android.systemui.bubbles.animation.StackAnimationController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders bubbles in a stack and handles animating expanded and collapsed states.
 */
public class BubbleStackView extends FrameLayout {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleStackView" : TAG_BUBBLES;

    /** How far the flyout needs to be dragged before it's dismissed regardless of velocity. */
    static final float FLYOUT_DRAG_PERCENT_DISMISS = 0.25f;

    /** Velocity required to dismiss the flyout via drag. */
    private static final float FLYOUT_DISMISS_VELOCITY = 2000f;

    /**
     * Factor for attenuating translation when the flyout is overscrolled (8f = flyout moves 1 pixel
     * for every 8 pixels overscrolled).
     */
    private static final float FLYOUT_OVERSCROLL_ATTENUATION_FACTOR = 8f;

    /** Duration of the flyout alpha animations. */
    private static final int FLYOUT_ALPHA_ANIMATION_DURATION = 100;

    /** Percent to darken the bubbles when they're in the dismiss target. */
    private static final float DARKEN_PERCENT = 0.3f;

    /** How long to wait, in milliseconds, before hiding the flyout. */
    @VisibleForTesting
    static final int FLYOUT_HIDE_AFTER = 5000;

    /**
     * Interface to synchronize {@link View} state and the screen.
     *
     * {@hide}
     */
    interface SurfaceSynchronizer {
        /**
         * Wait until requested change on a {@link View} is reflected on the screen.
         *
         * @param callback callback to run after the change is reflected on the screen.
         */
        void syncSurfaceAndRun(Runnable callback);
    }

    private static final SurfaceSynchronizer DEFAULT_SURFACE_SYNCHRONIZER =
            new SurfaceSynchronizer() {
        @Override
        public void syncSurfaceAndRun(Runnable callback) {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                // Just wait 2 frames. There is no guarantee, but this is usually enough time that
                // the requested change is reflected on the screen.
                // TODO: Once SurfaceFlinger provide APIs to sync the state of {@code View} and
                // surfaces, rewrite this logic with them.
                private int mFrameWait = 2;

                @Override
                public void doFrame(long frameTimeNanos) {
                    if (--mFrameWait > 0) {
                        Choreographer.getInstance().postFrameCallback(this);
                    } else {
                        callback.run();
                    }
                }
            });
        }
    };

    private Point mDisplaySize;

    private final SpringAnimation mExpandedViewXAnim;
    private final SpringAnimation mExpandedViewYAnim;
    private final BubbleData mBubbleData;

    private final Vibrator mVibrator;
    private final ValueAnimator mDesaturateAndDarkenAnimator;
    private final Paint mDesaturateAndDarkenPaint = new Paint();

    private PhysicsAnimationLayout mBubbleContainer;
    private StackAnimationController mStackAnimationController;
    private ExpandedAnimationController mExpandedAnimationController;

    private FrameLayout mExpandedViewContainer;

    private BubbleFlyoutView mFlyout;
    /** Runnable that fades out the flyout and then sets it to GONE. */
    private Runnable mHideFlyout = () -> animateFlyoutCollapsed(true, 0 /* velX */);
    /**
     * Callback to run after the flyout hides. Also called if a new flyout is shown before the
     * previous one animates out.
     */
    private Runnable mFlyoutOnHide;

    /** Layout change listener that moves the stack to the nearest valid position on rotation. */
    private OnLayoutChangeListener mOrientationChangedListener;
    /** Whether the stack was on the left side of the screen prior to rotation. */
    private boolean mWasOnLeftBeforeRotation = false;
    /**
     * How far down the screen the stack was before rotation, in terms of percentage of the way down
     * the allowable region. Defaults to -1 if not set.
     */
    private float mVerticalPosPercentBeforeRotation = -1;

    private int mMaxBubbles;
    private int mBubbleSize;
    private int mBubbleElevation;
    private int mBubblePaddingTop;
    private int mBubbleTouchPadding;
    private int mExpandedViewPadding;
    private int mExpandedAnimateXDistance;
    private int mExpandedAnimateYDistance;
    private int mPointerHeight;
    private int mStatusBarHeight;
    private int mImeOffset;
    private BubbleIconFactory mBubbleIconFactory;
    private Bubble mExpandedBubble;
    private boolean mIsExpanded;

    /** Whether the stack is currently on the left side of the screen, or animating there. */
    private boolean mStackOnLeftOrWillBe = false;

    /** Whether a touch gesture, such as a stack/bubble drag or flyout drag, is in progress. */
    private boolean mIsGestureInProgress = false;

    /** Description of current animation controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Stack view state:");
        pw.print("  gestureInProgress:    "); pw.println(mIsGestureInProgress);
        pw.print("  showingDismiss:       "); pw.println(mShowingDismiss);
        pw.print("  isExpansionAnimating: "); pw.println(mIsExpansionAnimating);
        pw.print("  draggingInDismiss:    "); pw.println(mDraggingInDismissTarget);
        pw.print("  animatingMagnet:      "); pw.println(mAnimatingMagnet);
        mStackAnimationController.dump(fd, pw, args);
        mExpandedAnimationController.dump(fd, pw, args);
    }

    private BubbleTouchHandler mTouchHandler;
    private BubbleController.BubbleExpandListener mExpandListener;

    private boolean mViewUpdatedRequested = false;
    private boolean mIsExpansionAnimating = false;
    private boolean mShowingDismiss = false;

    /**
     * Whether the user is currently dragging their finger within the dismiss target. In this state
     * the stack will be magnetized to the center of the target, so we shouldn't move it until the
     * touch exits the dismiss target area.
     */
    private boolean mDraggingInDismissTarget = false;

    /** Whether the stack is magneting towards the dismiss target. */
    private boolean mAnimatingMagnet = false;

    /** The view to desaturate/darken when magneted to the dismiss target. */
    private View mDesaturateAndDarkenTargetView;

    private LayoutInflater mInflater;

    // Used for determining view / touch intersection
    int[] mTempLoc = new int[2];
    RectF mTempRect = new RectF();

    private final List<Rect> mSystemGestureExclusionRects = Collections.singletonList(new Rect());

    private ViewTreeObserver.OnPreDrawListener mViewUpdater =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
                    updateExpandedView();
                    mViewUpdatedRequested = false;
                    return true;
                }
            };

    private ViewTreeObserver.OnDrawListener mSystemGestureExcludeUpdater =
            this::updateSystemGestureExcludeRects;

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

    /** Float property that 'drags' the flyout. */
    private final FloatPropertyCompat mFlyoutCollapseProperty =
            new FloatPropertyCompat("FlyoutCollapseSpring") {
                @Override
                public float getValue(Object o) {
                    return mFlyoutDragDeltaX;
                }

                @Override
                public void setValue(Object o, float v) {
                    onFlyoutDragged(v);
                }
            };

    /** SpringAnimation that springs the flyout collapsed via onFlyoutDragged. */
    private final SpringAnimation mFlyoutTransitionSpring =
            new SpringAnimation(this, mFlyoutCollapseProperty);

    /** Distance the flyout has been dragged in the X axis. */
    private float mFlyoutDragDeltaX = 0f;

    /**
     * Runnable that animates in the flyout. This reference is needed to cancel delayed postings.
     */
    private Runnable mAnimateInFlyout;

    /**
     * End listener for the flyout spring that either posts a runnable to hide the flyout, or hides
     * it immediately.
     */
    private final DynamicAnimation.OnAnimationEndListener mAfterFlyoutTransitionSpring =
            (dynamicAnimation, b, v, v1) -> {
                if (mFlyoutDragDeltaX == 0) {
                    mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
                } else {
                    mFlyout.hideFlyout();
                }
            };

    @NonNull
    private final SurfaceSynchronizer mSurfaceSynchronizer;

    private BubbleDismissView mDismissContainer;
    private Runnable mAfterMagnet;

    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    public BubbleStackView(Context context, BubbleData data,
            @Nullable SurfaceSynchronizer synchronizer) {
        super(context);

        mBubbleData = data;
        mInflater = LayoutInflater.from(context);
        mTouchHandler = new BubbleTouchHandler(this, data, context);
        setOnTouchListener(mTouchHandler);
        mInflater = LayoutInflater.from(context);

        Resources res = getResources();
        mMaxBubbles = res.getInteger(R.integer.bubbles_max_rendered);
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubbleElevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mBubbleTouchPadding = res.getDimensionPixelSize(R.dimen.bubble_touch_padding);
        mExpandedAnimateXDistance =
                res.getDimensionPixelSize(R.dimen.bubble_expanded_animate_x_distance);
        mExpandedAnimateYDistance =
                res.getDimensionPixelSize(R.dimen.bubble_expanded_animate_y_distance);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);

        mStatusBarHeight =
                res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);

        mDisplaySize = new Point();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        // We use the real size & subtract screen decorations / window insets ourselves when needed
        wm.getDefaultDisplay().getRealSize(mDisplaySize);

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        mExpandedViewPadding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        int elevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);

        mStackAnimationController = new StackAnimationController();

        mExpandedAnimationController = new ExpandedAnimationController(
                mDisplaySize, mExpandedViewPadding, res.getConfiguration().orientation);
        mSurfaceSynchronizer = synchronizer != null ? synchronizer : DEFAULT_SURFACE_SYNCHRONIZER;

        mBubbleContainer = new PhysicsAnimationLayout(context);
        mBubbleContainer.setActiveController(mStackAnimationController);
        mBubbleContainer.setElevation(elevation);
        mBubbleContainer.setClipChildren(false);
        addView(mBubbleContainer, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        mBubbleIconFactory = new BubbleIconFactory(context);

        mExpandedViewContainer = new FrameLayout(context);
        mExpandedViewContainer.setElevation(elevation);
        mExpandedViewContainer.setPadding(mExpandedViewPadding, mExpandedViewPadding,
                mExpandedViewPadding, mExpandedViewPadding);
        mExpandedViewContainer.setClipChildren(false);
        addView(mExpandedViewContainer);

        setUpFlyout();
        mFlyoutTransitionSpring.setSpring(new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_LOW)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        mFlyoutTransitionSpring.addEndListener(mAfterFlyoutTransitionSpring);

        mDismissContainer = new BubbleDismissView(mContext);
        mDismissContainer.setLayoutParams(new FrameLayout.LayoutParams(
                MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.pip_dismiss_gradient_height),
                Gravity.BOTTOM));
        addView(mDismissContainer);

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
        mExpandedViewYAnim.addEndListener((anim, cancelled, value, velocity) -> {
            if (mIsExpanded && mExpandedBubble != null) {
                mExpandedBubble.getExpandedView().updateView();
            }
        });

        setClipChildren(false);
        setFocusable(true);
        mBubbleContainer.bringToFront();

        setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
            if (!mIsExpanded || mIsExpansionAnimating) {
                return view.onApplyWindowInsets(insets);
            }
            mExpandedAnimationController.updateYPosition(
                    // Update the insets after we're done translating otherwise position
                    // calculation for them won't be correct.
                    () -> mExpandedBubble.getExpandedView().updateInsets(insets));
            return view.onApplyWindowInsets(insets);
        });

        mOrientationChangedListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    mExpandedAnimationController.updateOrientation(mOrientation, mDisplaySize);
                    mStackAnimationController.updateOrientation(mOrientation);

                    // Reposition & adjust the height for new orientation
                    if (mIsExpanded) {
                        mExpandedViewContainer.setTranslationY(getExpandedViewY());
                        mExpandedBubble.getExpandedView().updateView();
                    }

                    // Need to update the padding around the view
                    WindowInsets insets = getRootWindowInsets();
                    int leftPadding = mExpandedViewPadding;
                    int rightPadding = mExpandedViewPadding;
                    if (insets != null) {
                        // Can't have the expanded view overlaying notches
                        int cutoutLeft = 0;
                        int cutoutRight = 0;
                        DisplayCutout cutout = insets.getDisplayCutout();
                        if (cutout != null) {
                            cutoutLeft = cutout.getSafeInsetLeft();
                            cutoutRight = cutout.getSafeInsetRight();
                        }
                        // Or overlaying nav or status bar
                        leftPadding += Math.max(cutoutLeft, insets.getStableInsetLeft());
                        rightPadding += Math.max(cutoutRight, insets.getStableInsetRight());
                    }
                    mExpandedViewContainer.setPadding(leftPadding, mExpandedViewPadding,
                            rightPadding, mExpandedViewPadding);

                    if (mIsExpanded) {
                        // Re-draw bubble row and pointer for new orientation.
                        mExpandedAnimationController.expandFromStack(() -> {
                            updatePointerPosition();
                        } /* after */);
                    }
                    if (mVerticalPosPercentBeforeRotation >= 0) {
                        mStackAnimationController.moveStackToSimilarPositionAfterRotation(
                                mWasOnLeftBeforeRotation, mVerticalPosPercentBeforeRotation);
                    }
                    removeOnLayoutChangeListener(mOrientationChangedListener);
                };

        // This must be a separate OnDrawListener since it should be called for every draw.
        getViewTreeObserver().addOnDrawListener(mSystemGestureExcludeUpdater);

        final ColorMatrix animatedMatrix = new ColorMatrix();
        final ColorMatrix darkenMatrix = new ColorMatrix();

        mDesaturateAndDarkenAnimator = ValueAnimator.ofFloat(1f, 0f);
        mDesaturateAndDarkenAnimator.addUpdateListener(animation -> {
            final float animatedValue = (float) animation.getAnimatedValue();
            animatedMatrix.setSaturation(animatedValue);

            final float animatedDarkenValue = (1f - animatedValue) * DARKEN_PERCENT;
            darkenMatrix.setScale(
                    1f - animatedDarkenValue /* red */,
                    1f - animatedDarkenValue /* green */,
                    1f - animatedDarkenValue /* blue */,
                    1f /* alpha */);

            // Concat the matrices so that the animatedMatrix both desaturates and darkens.
            animatedMatrix.postConcat(darkenMatrix);

            // Update the paint and apply it to the bubble container.
            mDesaturateAndDarkenPaint.setColorFilter(new ColorMatrixColorFilter(animatedMatrix));
            mDesaturateAndDarkenTargetView.setLayerPaint(mDesaturateAndDarkenPaint);
        });
    }

    private void setUpFlyout() {
        if (mFlyout != null) {
            removeView(mFlyout);
        }
        mFlyout = new BubbleFlyoutView(getContext());
        mFlyout.setVisibility(GONE);
        mFlyout.animate()
                .setDuration(FLYOUT_ALPHA_ANIMATION_DURATION)
                .setInterpolator(new AccelerateDecelerateInterpolator());
        addView(mFlyout, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    }

    /**
     * Handle theme changes.
     */
    public void onThemeChanged() {
        // Recreate icon factory to update default adaptive icon scale.
        mBubbleIconFactory = new BubbleIconFactory(mContext);
        setUpFlyout();
        for (Bubble b: mBubbleData.getBubbles()) {
            b.getIconView().setBubbleIconFactory(mBubbleIconFactory);
            b.getIconView().updateViews();
            b.getExpandedView().applyThemeAttrs();
        }
    }

    /** Respond to the phone being rotated by repositioning the stack and hiding any flyouts. */
    public void onOrientationChanged(int orientation) {
        mOrientation = orientation;

        // Display size is based on the rotation device was in when requested, we should update it
        // We use the real size & subtract screen decorations / window insets ourselves when needed
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealSize(mDisplaySize);

        // Some resources change depending on orientation
        Resources res = getContext().getResources();
        mStatusBarHeight = res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);

        final RectF allowablePos = mStackAnimationController.getAllowableStackPositionRegion();
        mWasOnLeftBeforeRotation = mStackAnimationController.isStackOnLeftSide();
        mVerticalPosPercentBeforeRotation =
                (mStackAnimationController.getStackPosition().y - allowablePos.top)
                        / (allowablePos.bottom - allowablePos.top);
        addOnLayoutChangeListener(mOrientationChangedListener);
        hideFlyoutImmediate();
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
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);

        // Custom actions.
        AccessibilityAction moveTopLeft = new AccessibilityAction(R.id.action_move_top_left,
                getContext().getResources()
                        .getString(R.string.bubble_accessibility_action_move_top_left));
        info.addAction(moveTopLeft);

        AccessibilityAction moveTopRight = new AccessibilityAction(R.id.action_move_top_right,
                getContext().getResources()
                        .getString(R.string.bubble_accessibility_action_move_top_right));
        info.addAction(moveTopRight);

        AccessibilityAction moveBottomLeft = new AccessibilityAction(R.id.action_move_bottom_left,
                getContext().getResources()
                        .getString(R.string.bubble_accessibility_action_move_bottom_left));
        info.addAction(moveBottomLeft);

        AccessibilityAction moveBottomRight = new AccessibilityAction(R.id.action_move_bottom_right,
                getContext().getResources()
                        .getString(R.string.bubble_accessibility_action_move_bottom_right));
        info.addAction(moveBottomRight);

        // Default actions.
        info.addAction(AccessibilityAction.ACTION_DISMISS);
        if (mIsExpanded) {
            info.addAction(AccessibilityAction.ACTION_COLLAPSE);
        } else {
            info.addAction(AccessibilityAction.ACTION_EXPAND);
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        final RectF stackBounds = mStackAnimationController.getAllowableStackPositionRegion();

        // R constants are not final so we cannot use switch-case here.
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
            mBubbleData.dismissAll(BubbleController.DISMISS_ACCESSIBILITY_ACTION);
            return true;
        } else if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
            mBubbleData.setExpanded(false);
            return true;
        } else if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            mBubbleData.setExpanded(true);
            return true;
        } else if (action == R.id.action_move_top_left) {
            mStackAnimationController.springStack(stackBounds.left, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_top_right) {
            mStackAnimationController.springStack(stackBounds.right, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_bottom_left) {
            mStackAnimationController.springStack(stackBounds.left, stackBounds.bottom);
            return true;
        } else if (action == R.id.action_move_bottom_right) {
            mStackAnimationController.springStack(stackBounds.right, stackBounds.bottom);
            return true;
        }
        return false;
    }

    /**
     * Update content description for a11y TalkBack.
     */
    public void updateContentDescription() {
        if (mBubbleData.getBubbles().isEmpty()) {
            return;
        }
        Bubble topBubble = mBubbleData.getBubbles().get(0);
        String appName = topBubble.getAppName();
        Notification notification = topBubble.getEntry().notification.getNotification();
        CharSequence titleCharSeq = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        String titleStr = getResources().getString(R.string.stream_notification);
        if (titleCharSeq != null) {
            titleStr = titleCharSeq.toString();
        }
        int moreCount = mBubbleContainer.getChildCount() - 1;

        // Example: Title from app name.
        String singleDescription = getResources().getString(
                R.string.bubble_content_description_single, titleStr, appName);

        // Example: Title from app name and 4 more.
        String stackDescription = getResources().getString(
                R.string.bubble_content_description_stack, titleStr, appName, moreCount);

        if (mIsExpanded) {
            // TODO(b/129522932) - update content description for each bubble in expanded view.
        } else {
            // Collapsed stack.
            if (moreCount > 0) {
                mBubbleContainer.setContentDescription(stackDescription);
            } else {
                mBubbleContainer.setContentDescription(singleDescription);
            }
        }
    }

    private void updateSystemGestureExcludeRects() {
        // Exclude the region occupied by the first BubbleView in the stack
        Rect excludeZone = mSystemGestureExclusionRects.get(0);
        if (mBubbleContainer.getChildCount() > 0) {
            View firstBubble = mBubbleContainer.getChildAt(0);
            excludeZone.set(firstBubble.getLeft(), firstBubble.getTop(), firstBubble.getRight(),
                    firstBubble.getBottom());
            excludeZone.offset((int) (firstBubble.getTranslationX() + 0.5f),
                    (int) (firstBubble.getTranslationY() + 0.5f));
            mBubbleContainer.setSystemGestureExclusionRects(mSystemGestureExclusionRects);
        } else {
            excludeZone.setEmpty();
            mBubbleContainer.setSystemGestureExclusionRects(Collections.emptyList());
        }
    }

    /**
     * Updates the visibility of the 'dot' indicating an update on the bubble.
     *
     * @param key the {@link NotificationEntry#key} associated with the bubble.
     */
    public void updateDotVisibility(String key) {
        Bubble b = mBubbleData.getBubbleWithKey(key);
        if (b != null) {
            b.updateDotVisibility();
        }
    }

    /**
     * Sets the listener to notify when the bubble stack is expanded.
     */
    public void setExpandListener(BubbleController.BubbleExpandListener listener) {
        mExpandListener = listener;
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isExpanded() {
        return mIsExpanded;
    }

    /**
     * Whether the stack of bubbles is animating to or from expansion.
     */
    public boolean isExpansionAnimating() {
        return mIsExpansionAnimating;
    }

    /**
     * The {@link BubbleView} that is expanded, null if one does not exist.
     */
    BubbleView getExpandedBubbleView() {
        return mExpandedBubble != null ? mExpandedBubble.getIconView() : null;
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
     * @deprecated replaced by setSelectedBubble(Bubble) + setExpanded(true)
     */
    @Deprecated
    void setExpandedBubble(String key) {
        Bubble bubbleToExpand = mBubbleData.getBubbleWithKey(key);
        if (bubbleToExpand != null) {
            setSelectedBubble(bubbleToExpand);
            bubbleToExpand.setShowInShadeWhenBubble(false);
            setExpanded(true);
        }
    }

    // via BubbleData.Listener
    void addBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "addBubble: " + bubble);
        }

        if (mBubbleContainer.getChildCount() == 0) {
            mStackOnLeftOrWillBe = mStackAnimationController.isStackOnLeftSide();
        }

        bubble.inflate(mInflater, this);
        bubble.getIconView().setBubbleIconFactory(mBubbleIconFactory);
        bubble.getIconView().updateViews();

        // Set the dot position to the opposite of the side the stack is resting on, since the stack
        // resting slightly off-screen would result in the dot also being off-screen.
        bubble.getIconView().setDotPosition(
                !mStackOnLeftOrWillBe /* onLeft */, false /* animate */);

        mBubbleContainer.addView(bubble.getIconView(), 0,
                new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        ViewClippingUtil.setClippingDeactivated(bubble.getIconView(), true, mClippingParameters);
        animateInFlyoutForBubble(bubble);
        requestUpdate();
        logBubbleEvent(bubble, StatsLog.BUBBLE_UICHANGED__ACTION__POSTED);
        updatePointerPosition();
    }

    // via BubbleData.Listener
    void removeBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "removeBubble: " + bubble);
        }
        // Remove it from the views
        int removedIndex = mBubbleContainer.indexOfChild(bubble.getIconView());
        if (removedIndex >= 0) {
            mBubbleContainer.removeViewAt(removedIndex);
            bubble.cleanupExpandedState();
            logBubbleEvent(bubble, StatsLog.BUBBLE_UICHANGED__ACTION__DISMISSED);
        } else {
            Log.d(TAG, "was asked to remove Bubble, but didn't find the view! " + bubble);
        }
        updatePointerPosition();
    }

    // via BubbleData.Listener
    void updateBubble(Bubble bubble) {
        animateInFlyoutForBubble(bubble);
        requestUpdate();
        logBubbleEvent(bubble, StatsLog.BUBBLE_UICHANGED__ACTION__UPDATED);
    }

    public void updateBubbleOrder(List<Bubble> bubbles) {
        for (int i = 0; i < bubbles.size(); i++) {
            Bubble bubble = bubbles.get(i);
            mBubbleContainer.reorderView(bubble.getIconView(), i);
        }

        updateBubbleZOrdersAndDotPosition(false /* animate */);
    }

    /**
     * Changes the currently selected bubble. If the stack is already expanded, the newly selected
     * bubble will be shown immediately. This does not change the expanded state or change the
     * position of any bubble.
     */
    // via BubbleData.Listener
    public void setSelectedBubble(@Nullable Bubble bubbleToSelect) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "setSelectedBubble: " + bubbleToSelect);
        }
        if (mExpandedBubble != null && mExpandedBubble.equals(bubbleToSelect)) {
            return;
        }
        final Bubble previouslySelected = mExpandedBubble;
        mExpandedBubble = bubbleToSelect;

        if (mIsExpanded) {
            // Make the container of the expanded view transparent before removing the expanded view
            // from it. Otherwise a punch hole created by {@link android.view.SurfaceView} in the
            // expanded view becomes visible on the screen. See b/126856255
            mExpandedViewContainer.setAlpha(0.0f);
            mSurfaceSynchronizer.syncSurfaceAndRun(() -> {
                if (previouslySelected != null) {
                    previouslySelected.setContentVisibility(false);
                }
                updateExpandedBubble();
                updatePointerPosition();
                requestUpdate();
                logBubbleEvent(previouslySelected, StatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
                logBubbleEvent(bubbleToSelect, StatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
                notifyExpansionChanged(previouslySelected, false /* expanded */);
                notifyExpansionChanged(bubbleToSelect, true /* expanded */);
            });
        }
    }

    /**
     * Changes the expanded state of the stack.
     *
     * @param shouldExpand whether the bubble stack should appear expanded
     */
    // via BubbleData.Listener
    public void setExpanded(boolean shouldExpand) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "setExpanded: " + shouldExpand);
        }
        if (shouldExpand == mIsExpanded) {
            return;
        }
        if (mIsExpanded) {
            animateCollapse();
            logBubbleEvent(mExpandedBubble, StatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
        } else {
            animateExpansion();
            // TODO: move next line to BubbleData
            logBubbleEvent(mExpandedBubble, StatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
            logBubbleEvent(mExpandedBubble, StatsLog.BUBBLE_UICHANGED__ACTION__STACK_EXPANDED);
        }
        notifyExpansionChanged(mExpandedBubble, mIsExpanded);
    }

    /**
     * Dismiss the stack of bubbles.
     *
     * @deprecated
     */
    @Deprecated
    void stackDismissed(int reason) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "stackDismissed: reason=" + reason);
        }
        mBubbleData.dismissAll(reason);
        logBubbleEvent(null /* no bubble associated with bubble stack dismiss */,
                StatsLog.BUBBLE_UICHANGED__ACTION__STACK_DISMISSED);
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
                // Could be tapping or dragging a bubble while expanded
                for (int i = 0; i < mBubbleContainer.getChildCount(); i++) {
                    BubbleView view = (BubbleView) mBubbleContainer.getChildAt(i);
                    if (isIntersecting(view, x, y)) {
                        return view;
                    }
                }
            }
            BubbleExpandedView bev = (BubbleExpandedView) mExpandedViewContainer.getChildAt(0);
            if (bev.intersectingTouchableContent((int) x, (int) y)) {
                return bev;
            }
            // Outside of the parts we care about.
            return null;
        } else if (mFlyout.getVisibility() == VISIBLE && isIntersecting(mFlyout, x, y)) {
            return mFlyout;
        }
        // If it wasn't an individual bubble in the expanded state, or the flyout, it's the stack.
        return this;
    }

    View getFlyoutView() {
        return mFlyout;
    }

    /**
     * Collapses the stack of bubbles.
     * <p>
     * Must be called from the main thread.
     *
     * @deprecated use {@link #setExpanded(boolean)} and {@link #setSelectedBubble(Bubble)}
     */
    @Deprecated
    @MainThread
    void collapseStack() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "collapseStack()");
        }
        mBubbleData.setExpanded(false);
    }

    /**
     * @deprecated use {@link #setExpanded(boolean)} and {@link #setSelectedBubble(Bubble)}
     */
    @Deprecated
    @MainThread
    void collapseStack(Runnable endRunnable) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "collapseStack(endRunnable)");
        }
        collapseStack();
        // TODO - use the runnable at end of animation
        endRunnable.run();
    }

    /**
     * Expands the stack of bubbles.
     * <p>
     * Must be called from the main thread.
     *
     * @deprecated use {@link #setExpanded(boolean)} and {@link #setSelectedBubble(Bubble)}
     */
    @Deprecated
    @MainThread
    void expandStack() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "expandStack()");
        }
        mBubbleData.setExpanded(true);
    }

    private void beforeExpandedViewAnimation() {
        hideFlyoutImmediate();
        updateExpandedBubble();
        updateExpandedView();
        mIsExpansionAnimating = true;
    }

    private void afterExpandedViewAnimation() {
        updateExpandedView();
        mIsExpansionAnimating = false;
        requestUpdate();
    }

    private void animateCollapse() {
        mIsExpanded = false;
        final Bubble previouslySelected = mExpandedBubble;
        beforeExpandedViewAnimation();

        mBubbleContainer.cancelAllAnimations();
        mExpandedAnimationController.collapseBackToStack(
                mStackAnimationController.getStackPositionAlongNearestHorizontalEdge()
                /* collapseTo */,
                () -> {
                    mBubbleContainer.setActiveController(mStackAnimationController);
                    afterExpandedViewAnimation();
                    previouslySelected.setContentVisibility(false);
                });

        mExpandedViewXAnim.animateToFinalPosition(getCollapsedX());
        mExpandedViewYAnim.animateToFinalPosition(getCollapsedY());
        mExpandedViewContainer.animate()
                .setDuration(100)
                .alpha(0f);
    }

    private void animateExpansion() {
        mIsExpanded = true;
        beforeExpandedViewAnimation();

        mBubbleContainer.setActiveController(mExpandedAnimationController);
        mExpandedAnimationController.expandFromStack(() -> {
            updatePointerPosition();
            afterExpandedViewAnimation();
        } /* after */);


        mExpandedViewContainer.setTranslationX(getCollapsedX());
        mExpandedViewContainer.setTranslationY(getCollapsedY());
        mExpandedViewContainer.setAlpha(0f);

        mExpandedViewXAnim.animateToFinalPosition(0f);
        mExpandedViewYAnim.animateToFinalPosition(getExpandedViewY());
        mExpandedViewContainer.animate()
                .setDuration(100)
                .alpha(1f);
    }

    private float getCollapsedX() {
        return mStackAnimationController.getStackPosition().x < getWidth() / 2
                ? -mExpandedAnimateXDistance
                : mExpandedAnimateXDistance;
    }

    private float getCollapsedY() {
        return Math.min(mStackAnimationController.getStackPosition().y,
                mExpandedAnimateYDistance);
    }

    private void notifyExpansionChanged(Bubble bubble, boolean expanded) {
        if (mExpandListener != null && bubble != null) {
            mExpandListener.onBubbleExpandChanged(expanded, bubble.getKey());
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
        mStackAnimationController.setImeHeight(visible ? height + mImeOffset : 0);

        if (!mIsExpanded) {
            mStackAnimationController.animateForImeVisibility(visible);
        }
    }

    /** Called when a drag operation on an individual bubble has started. */
    public void onBubbleDragStart(View bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "onBubbleDragStart: bubble=" + bubble);
        }
        mExpandedAnimationController.prepareForBubbleDrag(bubble);
    }

    /** Called with the coordinates to which an individual bubble has been dragged. */
    public void onBubbleDragged(View bubble, float x, float y) {
        if (!mIsExpanded || mIsExpansionAnimating) {
            return;
        }

        mExpandedAnimationController.dragBubbleOut(bubble, x, y);
        springInDismissTarget();
    }

    /** Called when a drag operation on an individual bubble has finished. */
    public void onBubbleDragFinish(
            View bubble, float x, float y, float velX, float velY) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "onBubbleDragFinish: bubble=" + bubble);
        }

        if (!mIsExpanded || mIsExpansionAnimating) {
            return;
        }

        mExpandedAnimationController.snapBubbleBack(bubble, velX, velY);
        hideDismissTarget();
    }

    void onDragStart() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "onDragStart()");
        }
        if (mIsExpanded || mIsExpansionAnimating) {
            return;
        }

        mStackAnimationController.cancelStackPositionAnimations();
        mBubbleContainer.setActiveController(mStackAnimationController);
        hideFlyoutImmediate();

        mDraggingInDismissTarget = false;
    }

    void onDragged(float x, float y) {
        if (mIsExpanded || mIsExpansionAnimating) {
            return;
        }

        springInDismissTarget();
        mStackAnimationController.moveStackFromTouch(x, y);
    }

    void onDragFinish(float x, float y, float velX, float velY) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "onDragFinish");
        }

        if (mIsExpanded || mIsExpansionAnimating) {
            return;
        }

        final float newStackX = mStackAnimationController.flingStackThenSpringToEdge(x, velX, velY);
        logBubbleEvent(null /* no bubble associated with bubble stack move */,
                StatsLog.BUBBLE_UICHANGED__ACTION__STACK_MOVED);

        mStackOnLeftOrWillBe = newStackX <= 0;
        updateBubbleZOrdersAndDotPosition(true /* animate */);
        hideDismissTarget();
    }

    void onFlyoutDragStart() {
        mFlyout.removeCallbacks(mHideFlyout);
    }

    void onFlyoutDragged(float deltaX) {
        // This shouldn't happen, but if it does, just wait until the flyout lays out. This method
        // is continually called.
        if (mFlyout.getWidth() <= 0) {
            return;
        }

        final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
        mFlyoutDragDeltaX = deltaX;

        final float collapsePercent =
                onLeft ? -deltaX / mFlyout.getWidth() : deltaX / mFlyout.getWidth();
        mFlyout.setCollapsePercent(Math.min(1f, Math.max(0f, collapsePercent)));

        // Calculate how to translate the flyout if it has been dragged too far in either direction.
        float overscrollTranslation = 0f;
        if (collapsePercent < 0f || collapsePercent > 1f) {
            // Whether we are more than 100% transitioned to the dot.
            final boolean overscrollingPastDot = collapsePercent > 1f;

            // Whether we are overscrolling physically to the left - this can either be pulling the
            // flyout away from the stack (if the stack is on the right) or pushing it to the left
            // after it has already become the dot.
            final boolean overscrollingLeft =
                    (onLeft && collapsePercent > 1f) || (!onLeft && collapsePercent < 0f);
            overscrollTranslation =
                    (overscrollingPastDot ? collapsePercent - 1f : collapsePercent * -1)
                            * (overscrollingLeft ? -1 : 1)
                            * (mFlyout.getWidth() / (FLYOUT_OVERSCROLL_ATTENUATION_FACTOR
                            // Attenuate the smaller dot less than the larger flyout.
                            / (overscrollingPastDot ? 2 : 1)));
        }

        mFlyout.setTranslationX(mFlyout.getRestingTranslationX() + overscrollTranslation);
    }

    /**
     * Set when the flyout is tapped, so that we can expand the bubble associated with the flyout
     * once it collapses.
     */
    @Nullable private Bubble mBubbleToExpandAfterFlyoutCollapse = null;

    void onFlyoutTapped() {
        mBubbleToExpandAfterFlyoutCollapse = mBubbleData.getSelectedBubble();

        mFlyout.removeCallbacks(mHideFlyout);
        mHideFlyout.run();
    }

    /**
     * Called when the flyout drag has finished, and returns true if the gesture successfully
     * dismissed the flyout.
     */
    void onFlyoutDragFinished(float deltaX, float velX) {
        final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
        final boolean metRequiredVelocity =
                onLeft ? velX < -FLYOUT_DISMISS_VELOCITY : velX > FLYOUT_DISMISS_VELOCITY;
        final boolean metRequiredDeltaX =
                onLeft
                        ? deltaX < -mFlyout.getWidth() * FLYOUT_DRAG_PERCENT_DISMISS
                        : deltaX > mFlyout.getWidth() * FLYOUT_DRAG_PERCENT_DISMISS;
        final boolean isCancelFling = onLeft ? velX > 0 : velX < 0;
        final boolean shouldDismiss = metRequiredVelocity || (metRequiredDeltaX && !isCancelFling);

        mFlyout.removeCallbacks(mHideFlyout);
        animateFlyoutCollapsed(shouldDismiss, velX);
    }

    /**
     * Called when the first touch event of a gesture (stack drag, bubble drag, flyout drag, etc.)
     * is received.
     */
    void onGestureStart() {
        mIsGestureInProgress = true;
    }

    /** Called when a gesture is completed or cancelled. */
    void onGestureFinished() {
        mIsGestureInProgress = false;

        if (mIsExpanded) {
            mExpandedAnimationController.onGestureFinished();
        }
    }

    /** Prepares and starts the desaturate/darken animation on the bubble stack. */
    private void animateDesaturateAndDarken(View targetView, boolean desaturateAndDarken) {
        mDesaturateAndDarkenTargetView = targetView;

        if (desaturateAndDarken) {
            // Use the animated paint for the bubbles.
            mDesaturateAndDarkenTargetView.setLayerType(
                    View.LAYER_TYPE_HARDWARE, mDesaturateAndDarkenPaint);
            mDesaturateAndDarkenAnimator.removeAllListeners();
            mDesaturateAndDarkenAnimator.start();
        } else {
            mDesaturateAndDarkenAnimator.removeAllListeners();
            mDesaturateAndDarkenAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    // Stop using the animated paint.
                    resetDesaturationAndDarken();
                }
            });
            mDesaturateAndDarkenAnimator.reverse();
        }
    }

    private void resetDesaturationAndDarken() {
        mDesaturateAndDarkenAnimator.removeAllListeners();
        mDesaturateAndDarkenAnimator.cancel();
        mDesaturateAndDarkenTargetView.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    /**
     * Magnets the stack to the target, while also transforming the target to encircle the stack and
     * desaturating/darkening the bubbles.
     */
    void animateMagnetToDismissTarget(
            View magnetView, boolean toTarget, float x, float y, float velX, float velY) {
        mDraggingInDismissTarget = toTarget;

        if (toTarget) {
            // The Y-value for the bubble stack to be positioned in the center of the dismiss target
            final float destY = mDismissContainer.getDismissTargetCenterY() - mBubbleSize / 2f;

            mAnimatingMagnet = true;

            final Runnable afterMagnet = () -> {
                mAnimatingMagnet = false;
                if (mAfterMagnet != null) {
                    mAfterMagnet.run();
                }
            };

            if (magnetView == this) {
                mStackAnimationController.magnetToDismiss(velX, velY, destY, afterMagnet);
                animateDesaturateAndDarken(mBubbleContainer, true);
            } else {
                mExpandedAnimationController.magnetBubbleToDismiss(
                        magnetView, velX, velY, destY, afterMagnet);

                animateDesaturateAndDarken(magnetView, true);
            }
        } else {
            mAnimatingMagnet = false;

            if (magnetView == this) {
                mStackAnimationController.demagnetizeFromDismissToPoint(x, y, velX, velY);
                animateDesaturateAndDarken(mBubbleContainer, false);
            } else {
                mExpandedAnimationController.demagnetizeBubbleTo(x, y, velX, velY);
                animateDesaturateAndDarken(magnetView, false);
            }
        }

        mVibrator.vibrate(VibrationEffect.get(toTarget
                ? VibrationEffect.EFFECT_CLICK
                : VibrationEffect.EFFECT_TICK));
    }

    /**
     * Magnets the stack to the dismiss target if it's not already there. Then, dismiss the stack
     * using the 'implode' animation and animate out the target.
     */
    void magnetToStackIfNeededThenAnimateDismissal(
            View touchedView, float velX, float velY, Runnable after) {
        final View draggedOutBubble = mExpandedAnimationController.getDraggedOutBubble();
        final Runnable animateDismissal = () -> {
            mAfterMagnet = null;

            mVibrator.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
            mDismissContainer.springOut();

            // 'Implode' the stack and then hide the dismiss target.
            if (touchedView == this) {
                mStackAnimationController.implodeStack(
                        () -> {
                            mAnimatingMagnet = false;
                            mShowingDismiss = false;
                            mDraggingInDismissTarget = false;
                            after.run();
                            resetDesaturationAndDarken();
                        });
            } else {
                mExpandedAnimationController.dismissDraggedOutBubble(draggedOutBubble, () -> {
                    mAnimatingMagnet = false;
                    mShowingDismiss = false;
                    mDraggingInDismissTarget = false;
                    resetDesaturationAndDarken();
                    after.run();
                });
            }
        };

        if (mAnimatingMagnet) {
            // If the magnet animation is currently playing, dismiss the stack after it's done. This
            // happens if the stack is flung towards the target.
            mAfterMagnet = animateDismissal;
        } else if (mDraggingInDismissTarget) {
            // If we're in the dismiss target, but not animating, we already magneted - dismiss
            // immediately.
            animateDismissal.run();
        } else {
            // Otherwise, we need to start the magnet animation and then dismiss afterward.
            animateMagnetToDismissTarget(touchedView, true, -1 /* x */, -1 /* y */, velX, velY);
            mAfterMagnet = animateDismissal;
        }
    }

    /** Animates in the dismiss target. */
    private void springInDismissTarget() {
        if (mShowingDismiss) {
            return;
        }

        mShowingDismiss = true;

        // Show the dismiss container and bring it to the front so the bubbles will go behind it.
        mDismissContainer.springIn();
        mDismissContainer.bringToFront();
        mDismissContainer.setZ(Short.MAX_VALUE - 1);
    }

    /**
     * Animates the dismiss target out, as well as the circle that encircles the bubbles, if they
     * were dragged into the target and encircled.
     */
    private void hideDismissTarget() {
        if (!mShowingDismiss) {
            return;
        }

        mDismissContainer.springOut();
        mShowingDismiss = false;
    }

    /** Whether the location of the given MotionEvent is within the dismiss target area. */
    boolean isInDismissTarget(MotionEvent ev) {
        return isIntersecting(mDismissContainer.getDismissTarget(), ev.getRawX(), ev.getRawY());
    }

    /** Animates the flyout collapsed (to dot), or the reverse, starting with the given velocity. */
    private void animateFlyoutCollapsed(boolean collapsed, float velX) {
        final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
        // If the flyout was tapped, we want a higher stiffness for the collapse animation so it's
        // faster.
        mFlyoutTransitionSpring.getSpring().setStiffness(
                (mBubbleToExpandAfterFlyoutCollapse != null)
                        ? SpringForce.STIFFNESS_MEDIUM
                        : SpringForce.STIFFNESS_LOW);
        mFlyoutTransitionSpring
                .setStartValue(mFlyoutDragDeltaX)
                .setStartVelocity(velX)
                .animateToFinalPosition(collapsed
                        ? (onLeft ? -mFlyout.getWidth() : mFlyout.getWidth())
                        : 0f);
    }

    /** Updates the dot visibility, this is used in response to a zen mode config change. */
    void updateDots() {
        int bubbsCount = mBubbleContainer.getChildCount();
        for (int i = 0; i < bubbsCount; i++) {
            BubbleView bv = (BubbleView) mBubbleContainer.getChildAt(i);
            // If nothing changed the animation won't happen
            bv.updateDotVisibility(true /* animate */);
        }
    }

    /**
     * Calculates the y position of the expanded view when it is expanded.
     */
    float getExpandedViewY() {
        return getStatusBarHeight() + mBubbleSize + mBubblePaddingTop + mPointerHeight;
    }

    /**
     * Animates in the flyout for the given bubble, if available, and then hides it after some time.
     */
    @VisibleForTesting
    void animateInFlyoutForBubble(Bubble bubble) {
        final CharSequence updateMessage = bubble.getUpdateMessage(getContext());
        if (!bubble.showFlyoutForBubble()) {
            // In case flyout was suppressed for this update, reset now.
            bubble.setSuppressFlyout(false);
            return;
        }
        if (updateMessage == null
                || isExpanded()
                || mIsExpansionAnimating
                || mIsGestureInProgress
                || mBubbleToExpandAfterFlyoutCollapse != null
                || bubble.getIconView() == null) {
            // Skip the message if none exists, we're expanded or animating expansion, or we're
            // about to expand a bubble from the previous tapped flyout, or if bubble view is null.
            return;
        }
        mFlyoutDragDeltaX = 0f;
        clearFlyoutOnHide();
        mFlyoutOnHide = () -> {
            resetDot(bubble);
            if (mBubbleToExpandAfterFlyoutCollapse == null) {
                return;
            }
            mBubbleData.setSelectedBubble(mBubbleToExpandAfterFlyoutCollapse);
            mBubbleData.setExpanded(true);
            mBubbleToExpandAfterFlyoutCollapse = null;
        };
        mFlyout.setVisibility(INVISIBLE);

        // Temporarily suppress the dot while the flyout is visible.
        bubble.getIconView().setSuppressDot(
                true /* suppressDot */, false /* animate */);

        // Start flyout expansion. Post in case layout isn't complete and getWidth returns 0.
        post(() -> {
            // An auto-expanding bubble could have been posted during the time it takes to
            // layout.
            if (isExpanded()) {
                return;
            }
            final Runnable expandFlyoutAfterDelay = () -> {
                mAnimateInFlyout = () -> {
                    mFlyout.setVisibility(VISIBLE);
                    mFlyoutDragDeltaX =
                            mStackAnimationController.isStackOnLeftSide()
                                    ? -mFlyout.getWidth()
                                    : mFlyout.getWidth();
                    animateFlyoutCollapsed(false /* collapsed */, 0 /* velX */);
                    mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
                };
                mFlyout.postDelayed(mAnimateInFlyout, 200);
            };
            mFlyout.setupFlyoutStartingAsDot(
                    updateMessage, mStackAnimationController.getStackPosition(), getWidth(),
                    mStackAnimationController.isStackOnLeftSide(),
                    bubble.getIconView().getBadgeColor() /* dotColor */,
                    expandFlyoutAfterDelay /* onLayoutComplete */,
                    mFlyoutOnHide,
                    bubble.getIconView().getDotCenter());
            mFlyout.bringToFront();
        });
        mFlyout.removeCallbacks(mHideFlyout);
        mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
        logBubbleEvent(bubble, StatsLog.BUBBLE_UICHANGED__ACTION__FLYOUT);
    }

    private void resetDot(Bubble bubble) {
        final boolean suppressDot = !bubble.showBubbleDot();
        // If we're going to suppress the dot, make it visible first so it'll
        // visibly animate away.

        if (suppressDot) {
            bubble.getIconView().setSuppressDot(
                    false /* suppressDot */, false /* animate */);
        }
        // Reset dot suppression. If we're not suppressing due to DND, then
        // stop suppressing it with no animation (since the flyout has
        // transformed into the dot). If we are suppressing due to DND, animate
        // it away.
        bubble.getIconView().setSuppressDot(
                suppressDot /* suppressDot */,
                suppressDot /* animate */);
    }

    /** Hide the flyout immediately and cancel any pending hide runnables. */
    private void hideFlyoutImmediate() {
        clearFlyoutOnHide();
        mFlyout.removeCallbacks(mAnimateInFlyout);
        mFlyout.removeCallbacks(mHideFlyout);
        mFlyout.hideFlyout();
    }

    private void clearFlyoutOnHide() {
        mFlyout.removeCallbacks(mAnimateInFlyout);
        if (mFlyoutOnHide == null) {
            return;
        }
        mFlyoutOnHide.run();
        mFlyoutOnHide = null;
    }

    @Override
    public void getBoundsOnScreen(Rect outRect) {
        if (!mIsExpanded) {
            if (mBubbleContainer.getChildCount() > 0) {
                mBubbleContainer.getChildAt(0).getBoundsOnScreen(outRect);
            }
            // Increase the touch target size of the bubble
            outRect.top -= mBubbleTouchPadding;
            outRect.left -= mBubbleTouchPadding;
            outRect.right += mBubbleTouchPadding;
            outRect.bottom += mBubbleTouchPadding;
        } else {
            mBubbleContainer.getBoundsOnScreen(outRect);
        }

        if (mFlyout.getVisibility() == View.VISIBLE) {
            final Rect flyoutBounds = new Rect();
            mFlyout.getBoundsOnScreen(flyoutBounds);
            outRect.union(flyoutBounds);
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
        if (mViewUpdatedRequested || mIsExpansionAnimating) {
            return;
        }
        mViewUpdatedRequested = true;
        getViewTreeObserver().addOnPreDrawListener(mViewUpdater);
        invalidate();
    }

    private void updateExpandedBubble() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updateExpandedBubble()");
        }
        mExpandedViewContainer.removeAllViews();
        if (mExpandedBubble != null && mIsExpanded) {
            mExpandedViewContainer.addView(mExpandedBubble.getExpandedView());
            mExpandedBubble.getExpandedView().populateExpandedView();
            mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
            mExpandedViewContainer.setAlpha(1.0f);
        }
    }

    private void updateExpandedView() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updateExpandedView: mIsExpanded=" + mIsExpanded);
        }

        mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        if (mIsExpanded) {
            // First update the view so that it calculates a new height (ensuring the y position
            // calculation is correct)
            mExpandedBubble.getExpandedView().updateView();
            final float y = getExpandedViewY();
            if (!mExpandedViewYAnim.isRunning()) {
                // We're not animating so set the value
                mExpandedViewContainer.setTranslationY(y);
                mExpandedBubble.getExpandedView().updateView();
            } else {
                // We are animating so update the value; there is an end listener on the animator
                // that will ensure expandedeView.updateView gets called.
                mExpandedViewYAnim.animateToFinalPosition(y);
            }
        }

        mStackOnLeftOrWillBe = mStackAnimationController.isStackOnLeftSide();
        updateBubbleZOrdersAndDotPosition(false);
    }

    /** Sets the appropriate Z-order and dot position for each bubble in the stack. */
    private void updateBubbleZOrdersAndDotPosition(boolean animate) {
        int bubbleCount = mBubbleContainer.getChildCount();
        for (int i = 0; i < bubbleCount; i++) {
            BubbleView bv = (BubbleView) mBubbleContainer.getChildAt(i);
            bv.updateDotVisibility(true /* animate */);
            bv.setZ((mMaxBubbles * mBubbleElevation) - i);
            // If the dot is on the left, and so is the stack, we need to change the dot position.
            if (bv.getDotPositionOnLeft() == mStackOnLeftOrWillBe) {
                bv.setDotPosition(!mStackOnLeftOrWillBe, animate);
            }
        }
    }

    private void updatePointerPosition() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updatePointerPosition()");
        }

        Bubble expandedBubble = getExpandedBubble();
        if (expandedBubble == null) {
            return;
        }

        int index = getBubbleIndex(expandedBubble);
        float bubbleLeftFromScreenLeft = mExpandedAnimationController.getBubbleLeft(index);
        float halfBubble = mBubbleSize / 2f;
        float bubbleCenter = bubbleLeftFromScreenLeft + halfBubble;
        // Padding might be adjusted for insets, so get it directly from the view
        bubbleCenter -= mExpandedViewContainer.getPaddingLeft();

        expandedBubble.getExpandedView().setPointerPosition(bubbleCenter);
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
        return mBubbleContainer.indexOfChild(bubble.getIconView());
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
        if (bubble == null || bubble.getEntry() == null
                || bubble.getEntry().notification == null) {
            StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                    null /* package name */,
                    null /* notification channel */,
                    0 /* notification ID */,
                    0 /* bubble position */,
                    getBubbleCount(),
                    action,
                    getNormalizedXPosition(),
                    getNormalizedYPosition(),
                    false /* unread bubble */,
                    false /* on-going bubble */,
                    false /* isAppForeground (unused) */);
        } else {
            StatusBarNotification notification = bubble.getEntry().notification;
            StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                    notification.getPackageName(),
                    notification.getNotification().getChannelId(),
                    notification.getId(),
                    getBubbleIndex(bubble),
                    getBubbleCount(),
                    action,
                    getNormalizedXPosition(),
                    getNormalizedYPosition(),
                    bubble.showInShadeWhenBubble(),
                    bubble.isOngoing(),
                    false /* isAppForeground (unused) */);
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
        return mExpandedBubble.getExpandedView().performBackPressIfNeeded();
    }

    /** For debugging only */
    List<Bubble> getBubblesOnScreen() {
        List<Bubble> bubbles = new ArrayList<>();
        for (int i = 0; i < mBubbleContainer.getChildCount(); i++) {
            View child = mBubbleContainer.getChildAt(i);
            if (child instanceof BubbleView) {
                String key = ((BubbleView) child).getKey();
                Bubble bubble = mBubbleData.getBubbleWithKey(key);
                bubbles.add(bubble);
            }
        }
        return bubbles;
    }
}
