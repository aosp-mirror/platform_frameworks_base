/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui;

import static androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X;
import static androidx.dynamicanimation.animation.FloatPropertyCompat.createFloatPropertyCompat;

import static com.android.systemui.classifier.Classifier.NOTIFICATION_DISMISS;
import static com.android.systemui.statusbar.notification.NotificationUtils.logKey;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.res.Resources;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.VisibleForTesting;

import com.android.app.animation.Interpolators;
import com.android.internal.dynamicanimation.animation.SpringForce;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.wm.shell.animation.FlingAnimationUtils;
import com.android.wm.shell.animation.PhysicsAnimator;
import com.android.wm.shell.animation.PhysicsAnimator.SpringConfig;

import java.io.PrintWriter;
import java.util.function.Consumer;

public class SwipeHelper implements Gefingerpoken, Dumpable {
    static final String TAG = "com.android.systemui.SwipeHelper";
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean CONSTRAIN_SWIPE = true;
    private static final boolean FADE_OUT_DURING_SWIPE = true;
    private static final boolean DISMISS_IF_SWIPED_FAR_ENOUGH = true;

    public static final int X = 0;
    public static final int Y = 1;

    private static final float SWIPE_ESCAPE_VELOCITY = 500f; // dp/sec
    private static final int DEFAULT_ESCAPE_ANIMATION_DURATION = 200; // ms
    private static final int MAX_ESCAPE_ANIMATION_DURATION = 400; // ms
    private static final int MAX_DISMISS_VELOCITY = 4000; // dp/sec

    public static final float SWIPE_PROGRESS_FADE_END = 0.6f; // fraction of thumbnail width
                                              // beyond which swipe progress->0
    public static final float SWIPED_FAR_ENOUGH_SIZE_FRACTION = 0.6f;
    static final float MAX_SCROLL_SIZE_FRACTION = 0.3f;

    protected final Handler mHandler;

    private float mMinSwipeProgress = 0f;
    private float mMaxSwipeProgress = 1f;

    private final SpringConfig mSnapBackSpringConfig =
            new SpringConfig(SpringForce.STIFFNESS_LOW, SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    private final FlingAnimationUtils mFlingAnimationUtils;
    private float mPagingTouchSlop;
    private final float mSlopMultiplier;
    private int mTouchSlop;
    private float mTouchSlopMultiplier;

    private final Callback mCallback;
    private final VelocityTracker mVelocityTracker;
    private final FalsingManager mFalsingManager;
    private final FeatureFlags mFeatureFlags;

    private float mInitialTouchPos;
    private float mPerpendicularInitialTouchPos;
    private boolean mIsSwiping;
    private boolean mSnappingChild;
    private View mTouchedView;
    private boolean mCanCurrViewBeDimissed;
    private float mDensityScale;
    private float mTranslation = 0;

    private boolean mMenuRowIntercepting;
    private final long mLongPressTimeout;
    private boolean mLongPressSent;
    private final float[] mDownLocation = new float[2];
    private final Runnable mPerformLongPress = new Runnable() {

        private final int[] mViewOffset = new int[2];

        @Override
        public void run() {
            if (mTouchedView != null && !mLongPressSent) {
                mLongPressSent = true;
                if (mTouchedView instanceof ExpandableNotificationRow) {
                    mTouchedView.getLocationOnScreen(mViewOffset);
                    final int x = (int) mDownLocation[0] - mViewOffset[0];
                    final int y = (int) mDownLocation[1] - mViewOffset[1];
                    mTouchedView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    ((ExpandableNotificationRow) mTouchedView).doLongClickCallback(x, y);

                    if (isAvailableToDragAndDrop(mTouchedView)) {
                        mCallback.onLongPressSent(mTouchedView);
                    }
                }
            }
        }
    };

    private final int mFalsingThreshold;
    private boolean mTouchAboveFalsingThreshold;
    private boolean mDisableHwLayers;
    private final boolean mFadeDependingOnAmountSwiped;

    private final ArrayMap<View, Animator> mDismissPendingMap = new ArrayMap<>();

    public SwipeHelper(
            Callback callback, Resources resources, ViewConfiguration viewConfiguration,
            FalsingManager falsingManager, FeatureFlags featureFlags) {
        mCallback = callback;
        mHandler = new Handler();
        mVelocityTracker = VelocityTracker.obtain();
        mPagingTouchSlop = viewConfiguration.getScaledPagingTouchSlop();
        mSlopMultiplier = viewConfiguration.getScaledAmbiguousGestureMultiplier();
        mTouchSlop = viewConfiguration.getScaledTouchSlop();
        mTouchSlopMultiplier = viewConfiguration.getAmbiguousGestureMultiplier();

        // Extra long-press!
        mLongPressTimeout = (long) (ViewConfiguration.getLongPressTimeout() * 1.5f);

        mDensityScale =  resources.getDisplayMetrics().density;
        mFalsingThreshold = resources.getDimensionPixelSize(R.dimen.swipe_helper_falsing_threshold);
        mFadeDependingOnAmountSwiped = resources.getBoolean(
                R.bool.config_fadeDependingOnAmountSwiped);
        mFalsingManager = falsingManager;
        mFeatureFlags = featureFlags;
        mFlingAnimationUtils = new FlingAnimationUtils(resources.getDisplayMetrics(),
                getMaxEscapeAnimDuration() / 1000f);
    }

    public void setDensityScale(float densityScale) {
        mDensityScale = densityScale;
    }

    public void setPagingTouchSlop(float pagingTouchSlop) {
        mPagingTouchSlop = pagingTouchSlop;
    }

    public void setDisableHardwareLayers(boolean disableHwLayers) {
        mDisableHwLayers = disableHwLayers;
    }

    private float getPos(MotionEvent ev) {
        return ev.getX();
    }

    private float getPerpendicularPos(MotionEvent ev) {
        return ev.getY();
    }

    protected float getTranslation(View v) {
        return v.getTranslationX();
    }

    private float getVelocity(VelocityTracker vt) {
        return vt.getXVelocity();
    }


    protected Animator getViewTranslationAnimator(View view, float target,
            AnimatorUpdateListener listener) {

        cancelSnapbackAnimation(view);

        if (view instanceof ExpandableNotificationRow) {
            return ((ExpandableNotificationRow) view).getTranslateViewAnimator(target, listener);
        }

        return createTranslationAnimation(view, target, listener);
    }

    protected Animator createTranslationAnimation(View view, float newPos,
            AnimatorUpdateListener listener) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, newPos);

        if (listener != null) {
            anim.addUpdateListener(listener);
        }

        return anim;
    }

    protected void setTranslation(View v, float translate) {
        if (v != null) {
            v.setTranslationX(translate);
        }
    }

    protected float getSize(View v) {
        return v.getMeasuredWidth();
    }

    public void setMinSwipeProgress(float minSwipeProgress) {
        mMinSwipeProgress = minSwipeProgress;
    }

    public void setMaxSwipeProgress(float maxSwipeProgress) {
        mMaxSwipeProgress = maxSwipeProgress;
    }

    private float getSwipeProgressForOffset(View view, float translation) {
        float viewSize = getSize(view);
        float result = Math.abs(translation / viewSize);
        return Math.min(Math.max(mMinSwipeProgress, result), mMaxSwipeProgress);
    }

    /**
     * Returns the alpha value depending on the progress of the swipe.
     */
    @VisibleForTesting
    public float getSwipeAlpha(float progress) {
        if (mFadeDependingOnAmountSwiped) {
            // The more progress has been fade, the lower the alpha value so that the view fades.
            return Math.max(1 - progress, 0);
        }

        return 1f - Math.max(0, Math.min(1, progress / SWIPE_PROGRESS_FADE_END));
    }

    private void updateSwipeProgressFromOffset(View animView, boolean dismissable) {
        updateSwipeProgressFromOffset(animView, dismissable, getTranslation(animView));
    }

    private void updateSwipeProgressFromOffset(View animView, boolean dismissable,
            float translation) {
        float swipeProgress = getSwipeProgressForOffset(animView, translation);
        if (!mCallback.updateSwipeProgress(animView, dismissable, swipeProgress)) {
            if (FADE_OUT_DURING_SWIPE && dismissable) {
                if (!mDisableHwLayers) {
                    if (swipeProgress != 0f && swipeProgress != 1f) {
                        animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    } else {
                        animView.setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                }
                updateSwipeProgressAlpha(animView, getSwipeAlpha(swipeProgress));
            }
        }
        invalidateGlobalRegion(animView);
    }

    // invalidate the view's own bounds all the way up the view hierarchy
    public static void invalidateGlobalRegion(View view) {
        Trace.beginSection("SwipeHelper.invalidateGlobalRegion");
        invalidateGlobalRegion(
            view,
            new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
        Trace.endSection();
    }

    // invalidate a rectangle relative to the view's coordinate system all the way up the view
    // hierarchy
    public static void invalidateGlobalRegion(View view, RectF childBounds) {
        //childBounds.offset(view.getTranslationX(), view.getTranslationY());
        if (DEBUG_INVALIDATE)
            Log.v(TAG, "-------------");
        while (view.getParent() != null && view.getParent() instanceof View) {
            view = (View) view.getParent();
            view.getMatrix().mapRect(childBounds);
            view.invalidate((int) Math.floor(childBounds.left),
                            (int) Math.floor(childBounds.top),
                            (int) Math.ceil(childBounds.right),
                            (int) Math.ceil(childBounds.bottom));
            if (DEBUG_INVALIDATE) {
                Log.v(TAG, "INVALIDATE(" + (int) Math.floor(childBounds.left)
                        + "," + (int) Math.floor(childBounds.top)
                        + "," + (int) Math.ceil(childBounds.right)
                        + "," + (int) Math.ceil(childBounds.bottom));
            }
        }
    }

    public void cancelLongPress() {
        mHandler.removeCallbacks(mPerformLongPress);
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent ev) {
        if (mTouchedView instanceof ExpandableNotificationRow) {
            NotificationMenuRowPlugin nmr = ((ExpandableNotificationRow) mTouchedView).getProvider();
            if (nmr != null) {
                mMenuRowIntercepting = nmr.onInterceptTouchEvent(mTouchedView, ev);
            }
        }
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTouchAboveFalsingThreshold = false;
                mIsSwiping = false;
                mSnappingChild = false;
                mLongPressSent = false;
                mCallback.onLongPressSent(null);
                mVelocityTracker.clear();
                cancelLongPress();
                mTouchedView = mCallback.getChildAtPosition(ev);

                if (mTouchedView != null) {
                    cancelSnapbackAnimation(mTouchedView);
                    onDownUpdate(mTouchedView, ev);
                    mCanCurrViewBeDimissed = mCallback.canChildBeDismissed(mTouchedView);
                    mVelocityTracker.addMovement(ev);
                    mInitialTouchPos = getPos(ev);
                    mPerpendicularInitialTouchPos = getPerpendicularPos(ev);
                    mTranslation = getTranslation(mTouchedView);
                    mDownLocation[0] = ev.getRawX();
                    mDownLocation[1] = ev.getRawY();
                    mHandler.postDelayed(mPerformLongPress, mLongPressTimeout);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mTouchedView != null && !mLongPressSent) {
                    mVelocityTracker.addMovement(ev);
                    float pos = getPos(ev);
                    float perpendicularPos = getPerpendicularPos(ev);
                    float delta = pos - mInitialTouchPos;
                    float deltaPerpendicular = perpendicularPos - mPerpendicularInitialTouchPos;
                    // Adjust the touch slop if another gesture may be being performed.
                    final float pagingTouchSlop =
                            ev.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                            ? mPagingTouchSlop * mSlopMultiplier
                            : mPagingTouchSlop;
                    if (Math.abs(delta) > pagingTouchSlop
                            && Math.abs(delta) > Math.abs(deltaPerpendicular)) {
                        if (mCallback.canChildBeDragged(mTouchedView)) {
                            mIsSwiping = true;
                            mCallback.onBeginDrag(mTouchedView);
                            mInitialTouchPos = getPos(ev);
                            mTranslation = getTranslation(mTouchedView);
                        }
                        cancelLongPress();
                    } else if (ev.getClassification() == MotionEvent.CLASSIFICATION_DEEP_PRESS
                                    && mHandler.hasCallbacks(mPerformLongPress)) {
                        // Accelerate the long press signal.
                        cancelLongPress();
                        mPerformLongPress.run();
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                final boolean captured = (mIsSwiping || mLongPressSent || mMenuRowIntercepting);
                mLongPressSent = false;
                mCallback.onLongPressSent(null);
                mMenuRowIntercepting = false;
                resetSwipeState();
                cancelLongPress();
                if (captured) return true;
                break;
        }
        return mIsSwiping || mLongPressSent || mMenuRowIntercepting;
    }

    /**
     * After dismissChild() and related animation finished, this function will be called.
     */
    protected void onDismissChildWithAnimationFinished() {}

    /**
     * @param view The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     * @param useAccelerateInterpolator Should an accelerating Interpolator be used
     */
    public void dismissChild(final View view, float velocity, boolean useAccelerateInterpolator) {
        dismissChild(view, velocity, null /* endAction */, 0 /* delay */,
                useAccelerateInterpolator, 0 /* fixedDuration */, false /* isDismissAll */);
    }

    /**
     * @param animView The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     * @param endAction The action to perform at the end
     * @param delay The delay after which we should start
     * @param useAccelerateInterpolator Should an accelerating Interpolator be used
     * @param fixedDuration If not 0, this exact duration will be taken
     */
    public void dismissChild(final View animView, float velocity, final Consumer<Boolean> endAction,
            long delay, boolean useAccelerateInterpolator, long fixedDuration,
            boolean isDismissAll) {
        final boolean canBeDismissed = mCallback.canChildBeDismissed(animView);
        float newPos;
        boolean isLayoutRtl = animView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // if the language is rtl we prefer swiping to the left
        boolean animateLeftForRtl = velocity == 0 && (getTranslation(animView) == 0 || isDismissAll)
                && isLayoutRtl;
        boolean animateLeft = (Math.abs(velocity) > getEscapeVelocity() && velocity < 0) ||
                (getTranslation(animView) < 0 && !isDismissAll);
        if (animateLeft || animateLeftForRtl) {
            newPos = -getTotalTranslationLength(animView);
        } else {
            newPos = getTotalTranslationLength(animView);
        }
        long duration;
        if (fixedDuration == 0) {
            duration = MAX_ESCAPE_ANIMATION_DURATION;
            if (velocity != 0) {
                duration = Math.min(duration,
                        (int) (Math.abs(newPos - getTranslation(animView)) * 1000f / Math
                                .abs(velocity))
                );
            } else {
                duration = DEFAULT_ESCAPE_ANIMATION_DURATION;
            }
        } else {
            duration = fixedDuration;
        }

        if (!mDisableHwLayers) {
            animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        AnimatorUpdateListener updateListener = new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                onTranslationUpdate(animView, (float) animation.getAnimatedValue(), canBeDismissed);
            }
        };

        Animator anim = getViewTranslationAnimator(animView, newPos, updateListener);
        if (anim == null) {
            onDismissChildWithAnimationFinished();
            return;
        }
        if (useAccelerateInterpolator) {
            anim.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
            anim.setDuration(duration);
        } else {
            mFlingAnimationUtils.applyDismissing(anim, getTranslation(animView),
                    newPos, velocity, getSize(animView));
        }
        if (delay > 0) {
            anim.setStartDelay(delay);
        }
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                mCallback.onBeginDrag(animView);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                updateSwipeProgressFromOffset(animView, canBeDismissed);
                mDismissPendingMap.remove(animView);
                boolean wasRemoved = false;
                if (animView instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) animView;
                    wasRemoved = row.isRemoved();
                }
                if (!mCancelled || wasRemoved) {
                    mCallback.onChildDismissed(animView);
                    resetViewIfSwiping(animView);
                }
                if (endAction != null) {
                    endAction.accept(mCancelled);
                }
                if (!mDisableHwLayers) {
                    animView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
                onDismissChildWithAnimationFinished();
            }
        });

        prepareDismissAnimation(animView, anim);
        mDismissPendingMap.put(animView, anim);
        anim.start();
    }

    /**
     * Get the total translation length where we want to swipe to when dismissing the view. By
     * default this is the size of the view, but can also be larger.
     * @param animView the view to ask about
     */
    protected float getTotalTranslationLength(View animView) {
        return getSize(animView);
    }

    /**
     * Called to update the dismiss animation.
     */
    protected void prepareDismissAnimation(View view, Animator anim) {
        // Do nothing
    }

    /**
     * Starts a snapback animation and cancels any previous translate animations on the given view.
     *
     * @param animView view to animate
     * @param targetLeft the end position of the translation
     * @param velocity the initial velocity of the animation
     */
    protected void snapChild(final View animView, final float targetLeft, float velocity) {
        final boolean canBeDismissed = mCallback.canChildBeDismissed(animView);

        cancelTranslateAnimation(animView);

        PhysicsAnimator<? extends View> anim =
                createSnapBackAnimation(animView, targetLeft, velocity);
        anim.addUpdateListener((target, values) -> {
            onTranslationUpdate(target, getTranslation(target), canBeDismissed);
        });
        anim.addEndListener((t, p, wasFling, cancelled, finalValue, finalVelocity, allEnded) -> {
            mSnappingChild = false;

            if (!cancelled) {
                updateSwipeProgressFromOffset(animView, canBeDismissed);
                resetViewIfSwiping(animView);
                // Clear the snapped view after success, assuming it's not being swiped now
                if (animView == mTouchedView && !mIsSwiping) {
                    mTouchedView = null;
                }
            }
            onChildSnappedBack(animView, targetLeft);
        });
        mSnappingChild = true;
        anim.start();
    }

    private PhysicsAnimator<? extends View> createSnapBackAnimation(View target, float toPosition,
            float startVelocity) {
        if (target instanceof ExpandableNotificationRow) {
            return PhysicsAnimator.getInstance((ExpandableNotificationRow) target).spring(
                    createFloatPropertyCompat(ExpandableNotificationRow.TRANSLATE_CONTENT),
                    toPosition,
                    startVelocity,
                    mSnapBackSpringConfig);
        }
        return PhysicsAnimator.getInstance(target).spring(TRANSLATION_X, toPosition, startVelocity,
                mSnapBackSpringConfig);
    }

    private void cancelTranslateAnimation(View animView) {
        if (animView instanceof ExpandableNotificationRow) {
            ((ExpandableNotificationRow) animView).cancelTranslateAnimation();
        }
        cancelSnapbackAnimation(animView);
    }

    private void cancelSnapbackAnimation(View target) {
        PhysicsAnimator.getInstance(target).cancel();
    }

    /**
     * Called to update the content alpha while the view is swiped
     */
    protected void updateSwipeProgressAlpha(View animView, float alpha) {
        animView.setAlpha(alpha);
    }

    /**
     * Called after {@link #snapChild(View, float, float)} and its related animation has finished.
     */
    protected void onChildSnappedBack(View animView, float targetLeft) {
        mCallback.onChildSnappedBack(animView, targetLeft);
    }

    /**
     * Called when there's a down event.
     */
    public void onDownUpdate(View currView, MotionEvent ev) {
        // Do nothing
    }

    /**
     * Called on a move event.
     */
    protected void onMoveUpdate(View view, MotionEvent ev, float totalTranslation, float delta) {
        // Do nothing
    }

    /**
     * Called in {@link AnimatorUpdateListener#onAnimationUpdate(ValueAnimator)} when the current
     * view is being animated to dismiss or snap.
     */
    public void onTranslationUpdate(View animView, float value, boolean canBeDismissed) {
        updateSwipeProgressFromOffset(animView, canBeDismissed, value);
    }

    private void snapChildInstantly(final View view) {
        final boolean canAnimViewBeDismissed = mCallback.canChildBeDismissed(view);
        setTranslation(view, 0);
        updateSwipeProgressFromOffset(view, canAnimViewBeDismissed);
    }

    /**
     * Called when a view is updated to be non-dismissable, if the view was being dismissed before
     * the update this will handle snapping it back into place.
     *
     * @param view the view to snap if necessary.
     * @param animate whether to animate the snap or not.
     * @param targetLeft the target to snap to.
     */
    public void snapChildIfNeeded(final View view, boolean animate, float targetLeft) {
        if ((mIsSwiping && mTouchedView == view) || mSnappingChild) {
            return;
        }
        boolean needToSnap = false;
        Animator dismissPendingAnim = mDismissPendingMap.get(view);
        if (dismissPendingAnim != null) {
            needToSnap = true;
            dismissPendingAnim.cancel();
        } else if (getTranslation(view) != 0) {
            needToSnap = true;
        }
        if (needToSnap) {
            if (animate) {
                snapChild(view, targetLeft, 0.0f /* velocity */);
            } else {
                snapChildInstantly(view);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mIsSwiping && !mMenuRowIntercepting && !mLongPressSent) {
            if (mCallback.getChildAtPosition(ev) != null) {
                // We are dragging directly over a card, make sure that we also catch the gesture
                // even if nobody else wants the touch event.
                mTouchedView = mCallback.getChildAtPosition(ev);
                onInterceptTouchEvent(ev);
                return true;
            } else {
                // We are not doing anything, make sure the long press callback
                // is not still ticking like a bomb waiting to go off.
                cancelLongPress();
                return false;
            }
        }

        mVelocityTracker.addMovement(ev);
        final int action = ev.getAction();
        switch (action) {
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_MOVE:
                if (mTouchedView != null) {
                    float delta = getPos(ev) - mInitialTouchPos;
                    float absDelta = Math.abs(delta);
                    if (absDelta >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                    }

                    if (mLongPressSent) {
                        if (absDelta >= getTouchSlop(ev)) {
                            if (mTouchedView instanceof ExpandableNotificationRow) {
                                ((ExpandableNotificationRow) mTouchedView)
                                        .doDragCallback(ev.getX(), ev.getY());
                            }
                        }
                    } else {
                        // don't let items that can't be dismissed be dragged more than
                        // maxScrollDistance
                        if (CONSTRAIN_SWIPE && !mCallback.canChildBeDismissedInDirection(
                                mTouchedView,
                                delta > 0)) {
                            float size = getSize(mTouchedView);
                            float maxScrollDistance = MAX_SCROLL_SIZE_FRACTION * size;
                            if (absDelta >= size) {
                                delta = delta > 0 ? maxScrollDistance : -maxScrollDistance;
                            } else {
                                int startPosition = mCallback.getConstrainSwipeStartPosition();
                                if (absDelta > startPosition) {
                                    int signedStartPosition =
                                            (int) (startPosition * Math.signum(delta));
                                    delta = signedStartPosition
                                            + maxScrollDistance * (float) Math.sin(
                                            ((delta - signedStartPosition) / size) * (Math.PI / 2));
                                }
                            }
                        }

                        setTranslation(mTouchedView, mTranslation + delta);
                        updateSwipeProgressFromOffset(mTouchedView, mCanCurrViewBeDimissed);
                        onMoveUpdate(mTouchedView, ev, mTranslation + delta, delta);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mTouchedView == null) {
                    break;
                }
                mVelocityTracker.computeCurrentVelocity(1000 /* px/sec */, getMaxVelocity());
                float velocity = getVelocity(mVelocityTracker);

                if (!handleUpEvent(ev, mTouchedView, velocity, getTranslation(mTouchedView))) {
                    if (isDismissGesture(ev)) {
                        dismissChild(mTouchedView, velocity,
                                !swipedFastEnough() /* useAccelerateInterpolator */);
                    } else {
                        mCallback.onDragCancelled(mTouchedView);
                        snapChild(mTouchedView, 0 /* leftTarget */, velocity);
                    }
                    mTouchedView = null;
                }
                mIsSwiping = false;
                break;
        }
        return true;
    }

    private int getFalsingThreshold() {
        float factor = mCallback.getFalsingThresholdFactor();
        return (int) (mFalsingThreshold * factor);
    }

    private float getMaxVelocity() {
        return MAX_DISMISS_VELOCITY * mDensityScale;
    }

    protected float getEscapeVelocity() {
        return getUnscaledEscapeVelocity() * mDensityScale;
    }

    protected float getUnscaledEscapeVelocity() {
        return SWIPE_ESCAPE_VELOCITY;
    }

    protected long getMaxEscapeAnimDuration() {
        return MAX_ESCAPE_ANIMATION_DURATION;
    }

    protected boolean swipedFarEnough() {
        float translation = getTranslation(mTouchedView);
        return DISMISS_IF_SWIPED_FAR_ENOUGH
                && Math.abs(translation) > SWIPED_FAR_ENOUGH_SIZE_FRACTION * getSize(
                mTouchedView);
    }

    public boolean isDismissGesture(MotionEvent ev) {
        float translation = getTranslation(mTouchedView);
        return ev.getActionMasked() == MotionEvent.ACTION_UP
                && !mFalsingManager.isUnlockingDisabled()
                && !isFalseGesture() && (swipedFastEnough() || swipedFarEnough())
                && mCallback.canChildBeDismissedInDirection(mTouchedView, translation > 0);
    }

    /** Returns true if the gesture should be rejected. */
    public boolean isFalseGesture() {
        boolean falsingDetected = mCallback.isAntiFalsingNeeded();
        if (mFalsingManager.isClassifierEnabled()) {
            falsingDetected = falsingDetected && mFalsingManager.isFalseTouch(NOTIFICATION_DISMISS);
        } else {
            falsingDetected = falsingDetected && !mTouchAboveFalsingThreshold;
        }
        return falsingDetected;
    }

    protected boolean swipedFastEnough() {
        float velocity = getVelocity(mVelocityTracker);
        float translation = getTranslation(mTouchedView);
        boolean ret = (Math.abs(velocity) > getEscapeVelocity())
                && (velocity > 0) == (translation > 0);
        return ret;
    }

    protected boolean handleUpEvent(MotionEvent ev, View animView, float velocity,
            float translation) {
        return false;
    }

    public boolean isSwiping() {
        return mIsSwiping;
    }

    @Nullable
    public View getSwipedView() {
        return mIsSwiping ? mTouchedView : null;
    }

    protected void resetViewIfSwiping(View view) {
        if (getSwipedView() == view) {
            resetSwipeState();
        }
    }

    private void resetSwipeState() {
        resetSwipeStates(/* resetAll= */ false);
    }

    public void resetTouchState() {
        resetSwipeStates(/* resetAll= */ true);
    }

    public void forceResetSwipeState(@NonNull View view) {
        if (view.getTranslationX() == 0) return;
        setTranslation(view, 0);
        updateSwipeProgressFromOffset(view, /* dismissable= */ true, 0);
    }

    /** This method resets the swipe state, and if `resetAll` is true, also resets the snap state */
    private void resetSwipeStates(boolean resetAll) {
        final View touchedView = mTouchedView;
        final boolean wasSnapping = mSnappingChild;
        final boolean wasSwiping = mIsSwiping;
        mTouchedView = null;
        mIsSwiping = false;
        // If we were swiping, then we resetting swipe requires resetting everything.
        resetAll |= wasSwiping;
        if (resetAll) {
            mSnappingChild = false;
        }
        if (touchedView == null) return;  // No view to reset visually
        // When snap needs to be reset, first thing is to cancel any translation animation
        final boolean snapNeedsReset = resetAll && wasSnapping;
        if (snapNeedsReset) {
            cancelTranslateAnimation(touchedView);
        }
        // actually reset the view to default state
        if (resetAll) {
            snapChildIfNeeded(touchedView, false, 0);
        }
        // report if a swipe or snap was reset.
        if (wasSwiping || snapNeedsReset) {
            onChildSnappedBack(touchedView, 0);
        }
    }

    private float getTouchSlop(MotionEvent event) {
        // Adjust the touch slop if another gesture may be being performed.
        return event.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                ? mTouchSlop * mTouchSlopMultiplier
                : mTouchSlop;
    }

    private boolean isAvailableToDragAndDrop(View v) {
        if (mFeatureFlags.isEnabled(Flags.NOTIFICATION_DRAG_TO_CONTENTS)) {
            if (v instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow enr = (ExpandableNotificationRow) v;
                boolean canBubble = enr.getEntry().canBubble();
                Notification notif = enr.getEntry().getSbn().getNotification();
                PendingIntent dragIntent = notif.contentIntent != null ? notif.contentIntent
                        : notif.fullScreenIntent;
                if (dragIntent != null && dragIntent.isActivity() && !canBubble) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.append("mTouchedView=").print(mTouchedView);
        if (mTouchedView instanceof ExpandableNotificationRow) {
            pw.append(" key=").println(logKey((ExpandableNotificationRow) mTouchedView));
        } else {
            pw.println();
        }
        pw.append("mIsSwiping=").println(mIsSwiping);
        pw.append("mSnappingChild=").println(mSnappingChild);
        pw.append("mLongPressSent=").println(mLongPressSent);
        pw.append("mInitialTouchPos=").println(mInitialTouchPos);
        pw.append("mTranslation=").println(mTranslation);
        pw.append("mCanCurrViewBeDimissed=").println(mCanCurrViewBeDimissed);
        pw.append("mMenuRowIntercepting=").println(mMenuRowIntercepting);
        pw.append("mDisableHwLayers=").println(mDisableHwLayers);
        pw.append("mDismissPendingMap: ").println(mDismissPendingMap.size());
        if (!mDismissPendingMap.isEmpty()) {
            mDismissPendingMap.forEach((view, animator) -> {
                pw.append("  ").print(view);
                pw.append(": ").println(animator);
            });
        }
    }

    public interface Callback {
        View getChildAtPosition(MotionEvent ev);

        boolean canChildBeDismissed(View v);

        /**
         * Returns true if the provided child can be dismissed by a swipe in the given direction.
         *
         * @param isRightOrDown {@code true} if the swipe direction is right or down,
         *                      {@code false} if it is left or up.
         */
        default boolean canChildBeDismissedInDirection(View v, boolean isRightOrDown) {
            return canChildBeDismissed(v);
        }

        boolean isAntiFalsingNeeded();

        void onBeginDrag(View v);

        void onChildDismissed(View v);

        void onDragCancelled(View v);

        /**
         * Called when the child is long pressed and available to start drag and drop.
         *
         * @param v the view that was long pressed.
         */
        void onLongPressSent(View v);

        /**
         * Called when the child is snapped to a position.
         *
         * @param animView the view that was snapped.
         * @param targetLeft the left position the view was snapped to.
         */
        void onChildSnappedBack(View animView, float targetLeft);

        /**
         * Updates the swipe progress on a child.
         *
         * @return if true, prevents the default alpha fading.
         */
        boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress);

        /**
         * @return The factor the falsing threshold should be multiplied with
         */
        float getFalsingThresholdFactor();

        /**
         * @return The position, in pixels, at which a constrained swipe should start being
         * constrained.
         */
        default int getConstrainSwipeStartPosition() {
            return 0;
        }

        /**
         * @return If true, the given view is draggable.
         */
        default boolean canChildBeDragged(@NonNull View animView) { return true; }
    }
}
