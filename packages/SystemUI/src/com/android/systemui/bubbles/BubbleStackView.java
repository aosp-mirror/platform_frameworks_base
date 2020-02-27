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

import static com.android.systemui.bubbles.BadgedImageView.DOT_STATE_DEFAULT;
import static com.android.systemui.bubbles.BadgedImageView.DOT_STATE_SUPPRESSED_FOR_FLYOUT;
import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_STACK_VIEW;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.os.Vibrator;
import android.util.Log;
import android.view.Choreographer;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
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
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.util.DismissCircleView;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.systemui.util.animation.PhysicsAnimator;
import com.android.systemui.util.magnetictarget.MagnetizedObject;

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
    private Runnable mAfterFlyoutHidden;

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
    private BubbleViewProvider mExpandedBubble;
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
        mStackAnimationController.dump(fd, pw, args);
        mExpandedAnimationController.dump(fd, pw, args);
    }

    private BubbleTouchHandler mTouchHandler;
    private BubbleController.BubbleExpandListener mExpandListener;

    private boolean mViewUpdatedRequested = false;
    private boolean mIsExpansionAnimating = false;
    private boolean mShowingDismiss = false;

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

    /**
     * The currently magnetized object, which is being dragged and will be attracted to the magnetic
     * dismiss target.
     *
     * This is either the stack itself, or an individual bubble.
     */
    private MagnetizedObject<?> mMagnetizedObject;

    /**
     * The action to run when the magnetized object is released in the dismiss target.
     *
     * This will actually perform the dismissal of either the stack or an individual bubble.
     */
    private Runnable mReleasedInDismissTargetAction;

    /**
     * The MagneticTarget instance for our circular dismiss view. This is added to the
     * MagnetizedObject instances for the stack and any dragged-out bubbles.
     */
    private MagnetizedObject.MagneticTarget mMagneticTarget;

    /** Magnet listener that handles animating and dismissing individual dragged-out bubbles. */
    private final MagnetizedObject.MagnetListener mIndividualBubbleMagnetListener =
            new MagnetizedObject.MagnetListener() {
                @Override
                public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    animateDesaturateAndDarken(
                            mExpandedAnimationController.getDraggedOutBubble(), true);
                }

                @Override
                public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                        float velX, float velY, boolean wasFlungOut) {
                    animateDesaturateAndDarken(
                            mExpandedAnimationController.getDraggedOutBubble(), false);

                    if (wasFlungOut) {
                        mExpandedAnimationController.snapBubbleBack(
                                mExpandedAnimationController.getDraggedOutBubble(), velX, velY);
                        hideDismissTarget();
                    } else {
                        mExpandedAnimationController.onUnstuckFromTarget();
                    }
                }

                @Override
                public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    mExpandedAnimationController.dismissDraggedOutBubble(
                            mExpandedAnimationController.getDraggedOutBubble(),
                            mReleasedInDismissTargetAction);
                    hideDismissTarget();
                }
            };

    /** Magnet listener that handles animating and dismissing the entire stack. */
    private final MagnetizedObject.MagnetListener mStackMagnetListener =
            new MagnetizedObject.MagnetListener() {
                @Override
                public void onStuckToTarget(
                        @NonNull MagnetizedObject.MagneticTarget target) {
                    animateDesaturateAndDarken(mBubbleContainer, true);
                }

                @Override
                public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                        float velX, float velY, boolean wasFlungOut) {
                    animateDesaturateAndDarken(mBubbleContainer, false);

                    if (wasFlungOut) {
                        mStackAnimationController.flingStackThenSpringToEdge(
                                mStackAnimationController.getStackPosition().x, velX, velY);
                        hideDismissTarget();
                    } else {
                        mStackAnimationController.onUnstuckFromTarget();
                    }
                }

                @Override
                public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    mStackAnimationController.implodeStack(
                            () -> {
                                resetDesaturationAndDarken();
                                mReleasedInDismissTargetAction.run();
                            }
                    );

                    hideDismissTarget();
                }
            };

    private ViewGroup mDismissTargetContainer;
    private PhysicsAnimator<View> mDismissTargetAnimator;
    private PhysicsAnimator.SpringConfig mDismissTargetSpring = new PhysicsAnimator.SpringConfig(
            SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    private BubbleOverflow mBubbleOverflow;

    public BubbleStackView(Context context, BubbleData data,
            @Nullable SurfaceSynchronizer synchronizer,
            FloatingContentCoordinator floatingContentCoordinator) {
        super(context);

        mBubbleData = data;
        mInflater = LayoutInflater.from(context);
        mTouchHandler = new BubbleTouchHandler(this, data, context);
        setOnTouchListener(mTouchHandler);

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

        mStackAnimationController = new StackAnimationController(floatingContentCoordinator);

        mExpandedAnimationController = new ExpandedAnimationController(
                mDisplaySize, mExpandedViewPadding, res.getConfiguration().orientation);
        mSurfaceSynchronizer = synchronizer != null ? synchronizer : DEFAULT_SURFACE_SYNCHRONIZER;

        mBubbleContainer = new PhysicsAnimationLayout(context);
        mBubbleContainer.setActiveController(mStackAnimationController);
        mBubbleContainer.setElevation(elevation);
        mBubbleContainer.setClipChildren(false);
        addView(mBubbleContainer, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

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

        final View targetView = new DismissCircleView(context);
        final FrameLayout.LayoutParams newParams =
                new FrameLayout.LayoutParams(targetView.getLayoutParams());
        newParams.gravity = Gravity.CENTER;
        targetView.setLayoutParams(newParams);
        mDismissTargetAnimator = PhysicsAnimator.getInstance(targetView);

        mDismissTargetContainer = new FrameLayout(context);
        mDismissTargetContainer.setLayoutParams(new FrameLayout.LayoutParams(
                MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.pip_dismiss_gradient_height),
                Gravity.BOTTOM));
        mDismissTargetContainer.setClipChildren(false);
        mDismissTargetContainer.addView(targetView);
        mDismissTargetContainer.setVisibility(View.INVISIBLE);
        addView(mDismissTargetContainer);

        // Start translated down so the target springs up.
        targetView.setTranslationY(
                getResources().getDimensionPixelSize(R.dimen.pip_dismiss_gradient_height));

        // Save the MagneticTarget instance for the newly set up view - we'll add this to the
        // MagnetizedObjects.
        mMagneticTarget = new MagnetizedObject.MagneticTarget(targetView, mBubbleSize * 2);

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

        setUpOverflow();

        setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
            if (!mIsExpanded || mIsExpansionAnimating) {
                return view.onApplyWindowInsets(insets);
            }
            mExpandedAnimationController.updateYPosition(
                    // Update the insets after we're done translating otherwise position
                    // calculation for them won't be correct.
                    () -> {
                        if (mExpandedBubble != null) {
                            mExpandedBubble.getExpandedView().updateInsets(insets);
                        }
                    });
            return view.onApplyWindowInsets(insets);
        });

        mOrientationChangedListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    mExpandedAnimationController.updateOrientation(mOrientation, mDisplaySize);
                    mStackAnimationController.updateOrientation(mOrientation);

                    // Reposition & adjust the height for new orientation
                    if (mIsExpanded) {
                        mExpandedViewContainer.setTranslationY(getExpandedViewY());
                        if (mExpandedBubble != null) {
                            mExpandedBubble.getExpandedView().updateView();
                        }
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

    void showExpandedViewContents(int displayId) {
        if (mExpandedBubble != null
                && mExpandedBubble.getExpandedView().getVirtualDisplayId() == displayId) {
            mExpandedBubble.setContentVisibility(true);
        }
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

    private void setUpOverflow() {
        int overflowBtnIndex = 0;
        if (mBubbleOverflow == null) {
            mBubbleOverflow = new BubbleOverflow(mContext);
            mBubbleOverflow.setUpOverflow(this);
        } else {
            mBubbleContainer.removeView(mBubbleOverflow.getBtn());
            mBubbleOverflow.updateIcon(mContext, this);
            overflowBtnIndex = mBubbleContainer.getChildCount() - 1;
        }
        mBubbleContainer.addView(mBubbleOverflow.getBtn(), overflowBtnIndex,
                new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

    }
    /**
     * Handle theme changes.
     */
    public void onThemeChanged() {
        setUpFlyout();
        setUpOverflow();
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
            mStackAnimationController.springStackAfterFling(stackBounds.left, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_top_right) {
            mStackAnimationController.springStackAfterFling(stackBounds.right, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_bottom_left) {
            mStackAnimationController.springStackAfterFling(stackBounds.left, stackBounds.bottom);
            return true;
        } else if (action == R.id.action_move_bottom_right) {
            mStackAnimationController.springStackAfterFling(stackBounds.right, stackBounds.bottom);
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
        Notification notification = topBubble.getEntry().getSbn().getNotification();
        CharSequence titleCharSeq = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        String titleStr = getResources().getString(R.string.notification_bubble_title);
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
        if (getBubbleCount() > 0) {
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
     * The {@link BadgedImageView} that is expanded, null if one does not exist.
     */
    View getExpandedBubbleView() {
        return mExpandedBubble != null ? mExpandedBubble.getIconView() : null;
    }

    /**
     * The {@link Bubble} that is expanded, null if one does not exist.
     */
    @Nullable
    Bubble getExpandedBubble() {
        if (mExpandedBubble == null
                || (mExpandedBubble.getIconView() == mBubbleOverflow.getBtn()
                    && mExpandedBubble.getKey() == BubbleOverflow.KEY)) {
            return null;
        }
        return (Bubble) mExpandedBubble;
    }

    // via BubbleData.Listener
    void addBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "addBubble: " + bubble);
        }

        if (getBubbleCount() == 0) {
            mStackOnLeftOrWillBe = mStackAnimationController.isStackOnLeftSide();
        }

        // Set the dot position to the opposite of the side the stack is resting on, since the stack
        // resting slightly off-screen would result in the dot also being off-screen.
        bubble.getIconView().setDotPosition(
                !mStackOnLeftOrWillBe /* onLeft */, false /* animate */);

        mBubbleContainer.addView(bubble.getIconView(), 0,
                new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        ViewClippingUtil.setClippingDeactivated(bubble.getIconView(), true, mClippingParameters);
        animateInFlyoutForBubble(bubble);
        updatePointerPosition();
        updateOverflowBtnVisibility( /*apply */ true);
        requestUpdate();
        logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__POSTED);
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
            bubble.setInflated(false);
            logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__DISMISSED);
        } else {
            Log.d(TAG, "was asked to remove Bubble, but didn't find the view! " + bubble);
        }
        updateOverflowBtnVisibility(/* apply */ true);
    }

    private void updateOverflowBtnVisibility(boolean apply) {
        if (mIsExpanded) {
            if (DEBUG_BUBBLE_STACK_VIEW) {
                Log.d(TAG, "Show overflow button.");
            }
            mBubbleOverflow.setBtnVisible(VISIBLE);
            if (apply) {
                mExpandedAnimationController.expandFromStack(() -> {
                    updatePointerPosition();
                } /* after */);
            }
        } else {
            if (DEBUG_BUBBLE_STACK_VIEW) {
                Log.d(TAG, "Collapsed. Hide overflow button.");
            }
            mBubbleOverflow.setBtnVisible(GONE);
        }
    }

    // via BubbleData.Listener
    void updateBubble(Bubble bubble) {
        animateInFlyoutForBubble(bubble);
        requestUpdate();
        logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__UPDATED);
    }

    public void updateBubbleOrder(List<Bubble> bubbles) {
        for (int i = 0; i < bubbles.size(); i++) {
            Bubble bubble = bubbles.get(i);
            mBubbleContainer.reorderView(bubble.getIconView(), i);
        }

        updateBubbleZOrdersAndDotPosition(false /* animate */);
    }

    void showOverflow() {
        setSelectedBubble(mBubbleOverflow);
    }

    /**
     * Changes the currently selected bubble. If the stack is already expanded, the newly selected
     * bubble will be shown immediately. This does not change the expanded state or change the
     * position of any bubble.
     */
    // via BubbleData.Listener
    public void setSelectedBubble(@Nullable BubbleViewProvider bubbleToSelect) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "setSelectedBubble: " + bubbleToSelect);
        }
        if (mExpandedBubble != null && mExpandedBubble.equals(bubbleToSelect)) {
            return;
        }
        final BubbleViewProvider previouslySelected = mExpandedBubble;
        mExpandedBubble = bubbleToSelect;

        if (mIsExpanded) {
            // Make the container of the expanded view transparent before removing the expanded view
            // from it. Otherwise a punch hole created by {@link android.view.SurfaceView} in the
            // expanded view becomes visible on the screen. See b/126856255
            mExpandedViewContainer.setAlpha(0.0f);
            mSurfaceSynchronizer.syncSurfaceAndRun(() -> {
                previouslySelected.setContentVisibility(false);
                updateExpandedBubble();
                updatePointerPosition();
                requestUpdate();

                logBubbleEvent(previouslySelected,
                        SysUiStatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
                logBubbleEvent(bubbleToSelect, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
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
            logBubbleEvent(mExpandedBubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
        } else {
            animateExpansion();
            // TODO: move next line to BubbleData
            logBubbleEvent(mExpandedBubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
            logBubbleEvent(mExpandedBubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__STACK_EXPANDED);
        }
        notifyExpansionChanged(mExpandedBubble, mIsExpanded);
    }

    /**
     * Sets the action to run to dismiss the currently dragging object (either the stack or an
     * individual bubble).
     */
    public void setReleasedInDismissTargetAction(Runnable action) {
        mReleasedInDismissTargetAction = action;
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
                SysUiStatsLog.BUBBLE_UICHANGED__ACTION__STACK_DISMISSED);
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
                if (isIntersecting(mBubbleOverflow.getBtn(), x, y)) {
                    return mBubbleOverflow.getBtn();
                }
                // Could be tapping or dragging a bubble while expanded
                for (int i = 0; i < getBubbleCount(); i++) {
                    BadgedImageView view = (BadgedImageView) mBubbleContainer.getChildAt(i);
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
        final BubbleViewProvider previouslySelected = mExpandedBubble;
        beforeExpandedViewAnimation();

        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "animateCollapse");
            Log.d(TAG, BubbleDebugConfig.formatBubblesString(this.getBubblesOnScreen(),
                    this.getExpandedBubble()));
        }
        updateOverflowBtnVisibility(/* apply */ false);
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
        updateOverflowBtnVisibility(/* apply */ false);
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

    private void notifyExpansionChanged(BubbleViewProvider bubble, boolean expanded) {
        if (mExpandListener != null && bubble != null) {
            mExpandListener.onBubbleExpandChanged(expanded, bubble.getKey());
        }
    }

    /** Return the BubbleView at the given index from the bubble container. */
    public BadgedImageView getBubbleAt(int i) {
        return getBubbleCount() > i
                ? (BadgedImageView) mBubbleContainer.getChildAt(i)
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
        mExpandedAnimationController.prepareForBubbleDrag(bubble, mMagneticTarget);

        // We're dragging an individual bubble, so set the magnetized object to the magnetized
        // bubble.
        mMagnetizedObject = mExpandedAnimationController.getMagnetizedBubbleDraggingOut();
        mMagnetizedObject.setMagnetListener(mIndividualBubbleMagnetListener);
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
            if (DEBUG_BUBBLE_STACK_VIEW) {
                Log.d(TAG, "mIsExpanded or mIsExpansionAnimating");
            }
            return;
        }
        mStackAnimationController.cancelStackPositionAnimations();
        mBubbleContainer.setActiveController(mStackAnimationController);
        hideFlyoutImmediate();

        // Since we're dragging the stack, set the magnetized object to the magnetized stack.
        mMagnetizedObject = mStackAnimationController.getMagnetizedStack(mMagneticTarget);
        mMagnetizedObject.setMagnetListener(mStackMagnetListener);
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
                SysUiStatsLog.BUBBLE_UICHANGED__ACTION__STACK_MOVED);

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

    /** Passes the MotionEvent to the magnetized object and returns true if it was consumed. */
    boolean passEventToMagnetizedObject(MotionEvent event) {
        return mMagnetizedObject != null && mMagnetizedObject.maybeConsumeMotionEvent(event);
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

    /** Animates in the dismiss target. */
    private void springInDismissTarget() {
        if (mShowingDismiss) {
            return;
        }

        mShowingDismiss = true;

        mDismissTargetContainer.bringToFront();
        mDismissTargetContainer.setZ(Short.MAX_VALUE - 1);
        mDismissTargetContainer.setVisibility(VISIBLE);

        mDismissTargetAnimator.cancel();
        mDismissTargetAnimator
                .spring(DynamicAnimation.TRANSLATION_Y, 0f, mDismissTargetSpring)
                .start();
    }

    /**
     * Animates the dismiss target out, as well as the circle that encircles the bubbles, if they
     * were dragged into the target and encircled.
     */
    private void hideDismissTarget() {
        if (!mShowingDismiss) {
            return;
        }

        mShowingDismiss = false;

        mDismissTargetAnimator
                .spring(DynamicAnimation.TRANSLATION_Y, mDismissTargetContainer.getHeight(),
                        mDismissTargetSpring)
                .withEndActions(() -> mDismissTargetContainer.setVisibility(View.INVISIBLE))
                .start();
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
        Bubble.FlyoutMessage flyoutMessage = bubble.getFlyoutMessage();
        final BadgedImageView bubbleView = bubble.getIconView();
        if (flyoutMessage == null
                || flyoutMessage.message == null
                || !bubble.showFlyout()
                || isExpanded()
                || mIsExpansionAnimating
                || mIsGestureInProgress
                || mBubbleToExpandAfterFlyoutCollapse != null
                || bubbleView == null) {
            if (bubbleView != null) {
                bubbleView.setDotState(DOT_STATE_DEFAULT);
            }
            // Skip the message if none exists, we're expanded or animating expansion, or we're
            // about to expand a bubble from the previous tapped flyout, or if bubble view is null.
            return;
        }

        mFlyoutDragDeltaX = 0f;
        clearFlyoutOnHide();
        mAfterFlyoutHidden = () -> {
            // Null it out to ensure it runs once.
            mAfterFlyoutHidden = null;

            if (mBubbleToExpandAfterFlyoutCollapse != null) {
                // User tapped on the flyout and we should expand
                mBubbleData.setSelectedBubble(mBubbleToExpandAfterFlyoutCollapse);
                mBubbleData.setExpanded(true);
                mBubbleToExpandAfterFlyoutCollapse = null;
            }
            bubbleView.setDotState(DOT_STATE_DEFAULT);
        };
        mFlyout.setVisibility(INVISIBLE);

        // Don't show the dot when we're animating the flyout
        bubbleView.setDotState(DOT_STATE_SUPPRESSED_FOR_FLYOUT);

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
            mFlyout.setupFlyoutStartingAsDot(flyoutMessage,
                    mStackAnimationController.getStackPosition(), getWidth(),
                    mStackAnimationController.isStackOnLeftSide(),
                    bubble.getIconView().getDotColor() /* dotColor */,
                    expandFlyoutAfterDelay /* onLayoutComplete */,
                    mAfterFlyoutHidden,
                    bubble.getIconView().getDotCenter(),
                    !bubble.showDot());
            mFlyout.bringToFront();
        });
        mFlyout.removeCallbacks(mHideFlyout);
        mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
        logBubbleEvent(bubble, SysUiStatsLog.BUBBLE_UICHANGED__ACTION__FLYOUT);
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
        if (mAfterFlyoutHidden == null) {
            return;
        }
        mAfterFlyoutHidden.run();
        mAfterFlyoutHidden = null;
    }

    @Override
    public void getBoundsOnScreen(Rect outRect) {
        // If the bubble menu is open, the entire screen should capture touch events.
        if (!mIsExpanded) {
            if (getBubbleCount() > 0) {
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
        if (mIsExpanded && mExpandedBubble != null) {
            BubbleExpandedView bev = mExpandedBubble.getExpandedView();
            mExpandedViewContainer.addView(bev);
            bev.populateExpandedView();
            mExpandedViewContainer.setVisibility(VISIBLE);
            mExpandedViewContainer.setAlpha(1.0f);
        }
    }

    private void updateExpandedView() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updateExpandedView: mIsExpanded=" + mIsExpanded);
        }

        mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        if (mIsExpanded) {
            final float y = getExpandedViewY();
            if (!mExpandedViewYAnim.isRunning()) {
                // We're not animating so set the value
                mExpandedViewContainer.setTranslationY(y);
                if (mExpandedBubble != null) {
                    mExpandedBubble.getExpandedView().updateView();
                }
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
        int bubbleCount = getBubbleCount();
        for (int i = 0; i < bubbleCount; i++) {
            BadgedImageView bv = (BadgedImageView) mBubbleContainer.getChildAt(i);
            bv.setZ((mMaxBubbles * mBubbleElevation) - i);
            // If the dot is on the left, and so is the stack, we need to change the dot position.
            if (bv.getDotPositionOnLeft() == mStackOnLeftOrWillBe) {
                bv.setDotPosition(!mStackOnLeftOrWillBe, animate);
            }
        }
    }

    private void updatePointerPosition() {
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
        // Subtract 1 for the overflow button that is always in the bubble container.
        return mBubbleContainer.getChildCount() - 1;
    }

    /**
     * Finds the bubble index within the stack.
     *
     * @param provider the bubble view provider with the bubble to look up.
     * @return the index of the bubble view within the bubble stack. The range of the position
     * is between 0 and the bubble count minus 1.
     */
    int getBubbleIndex(@Nullable BubbleViewProvider provider) {
        if (provider == null || provider.getKey() == BubbleOverflow.KEY) {
            return 0;
        }
        Bubble b = (Bubble) provider;
        return mBubbleContainer.indexOfChild(b.getIconView());
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
    private void logBubbleEvent(@Nullable BubbleViewProvider bubble, int action) {
        if (bubble == null) {
            return;
        }
        bubble.logUIEvent(getBubbleCount(), action, getNormalizedXPosition(),
                getNormalizedYPosition(), getBubbleIndex(bubble));
    }

    /**
     * Called when a back gesture should be directed to the Bubbles stack. When expanded,
     * a back key down/up event pair is forwarded to the bubble Activity.
     */
    boolean performBackPressIfNeeded() {
        if (!isExpanded() || mExpandedBubble == null) {
            return false;
        }
        return mExpandedBubble.getExpandedView().performBackPressIfNeeded();
    }

    /** For debugging only */
    List<Bubble> getBubblesOnScreen() {
        List<Bubble> bubbles = new ArrayList<>();
        for (int i = 0; i < getBubbleCount(); i++) {
            View child = mBubbleContainer.getChildAt(i);
            if (child instanceof BadgedImageView) {
                String key = ((BadgedImageView) child).getKey();
                Bubble bubble = mBubbleData.getBubbleWithKey(key);
                bubbles.add(bubble);
            }
        }
        return bubbles;
    }
}
