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

import static com.android.systemui.classifier.Classifier.NOTIFICATION_DISMISS;

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
import android.util.ArrayMap;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.wm.shell.animation.FlingAnimationUtils;

public class SwipeHelper implements Gefingerpoken {
    static final String TAG = "com.android.systemui.SwipeHelper";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;
    private static final boolean CONSTRAIN_SWIPE = true;
    private static final boolean FADE_OUT_DURING_SWIPE = true;
    private static final boolean DISMISS_IF_SWIPED_FAR_ENOUGH = true;

    public static final int X = 0;
    public static final int Y = 1;

    private static final float SWIPE_ESCAPE_VELOCITY = 500f; // dp/sec
    private static final int DEFAULT_ESCAPE_ANIMATION_DURATION = 200; // ms
    private static final int MAX_ESCAPE_ANIMATION_DURATION = 400; // ms
    private static final int MAX_DISMISS_VELOCITY = 4000; // dp/sec
    private static final int SNAP_ANIM_LEN = SLOW_ANIMATIONS ? 1000 : 150; // ms

    static final float SWIPE_PROGRESS_FADE_END = 0.5f; // fraction of thumbnail width
                                              // beyond which swipe progress->0
    public static final float SWIPED_FAR_ENOUGH_SIZE_FRACTION = 0.6f;
    static final float MAX_SCROLL_SIZE_FRACTION = 0.3f;

    protected final Handler mHandler;

    private float mMinSwipeProgress = 0f;
    private float mMaxSwipeProgress = 1f;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private float mPagingTouchSlop;
    private final float mSlopMultiplier;
    private int mTouchSlop;
    private float mTouchSlopMultiplier;

    private final Callback mCallback;
    private final int mSwipeDirection;
    private final VelocityTracker mVelocityTracker;
    private final FalsingManager mFalsingManager;

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
            int swipeDirection, Callback callback, Resources resources,
            ViewConfiguration viewConfiguration, FalsingManager falsingManager) {
        mCallback = callback;
        mHandler = new Handler();
        mSwipeDirection = swipeDirection;
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
        return mSwipeDirection == X ? ev.getX() : ev.getY();
    }

    private float getPerpendicularPos(MotionEvent ev) {
        return mSwipeDirection == X ? ev.getY() : ev.getX();
    }

    protected float getTranslation(View v) {
        return mSwipeDirection == X ? v.getTranslationX() : v.getTranslationY();
    }

    private float getVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getXVelocity() :
                vt.getYVelocity();
    }

    protected ObjectAnimator createTranslationAnimation(View v, float newPos) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(v,
                mSwipeDirection == X ? View.TRANSLATION_X : View.TRANSLATION_Y, newPos);
        return anim;
    }

    private float getPerpendicularVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getYVelocity() :
                vt.getXVelocity();
    }

    protected Animator getViewTranslationAnimator(View v, float target,
            AnimatorUpdateListener listener) {
        ObjectAnimator anim = createTranslationAnimation(v, target);
        if (listener != null) {
            anim.addUpdateListener(listener);
        }
        return anim;
    }

    protected void setTranslation(View v, float translate) {
        if (v == null) {
            return;
        }
        if (mSwipeDirection == X) {
            v.setTranslationX(translate);
        } else {
            v.setTranslationY(translate);
        }
    }

    protected float getSize(View v) {
        return mSwipeDirection == X ? v.getMeasuredWidth() : v.getMeasuredHeight();
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

    private float getSwipeAlpha(float progress) {
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
                animView.setAlpha(getSwipeAlpha(swipeProgress));
            }
        }
        invalidateGlobalRegion(animView);
    }

    // invalidate the view's own bounds all the way up the view hierarchy
    public static void invalidateGlobalRegion(View view) {
        invalidateGlobalRegion(
            view,
            new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
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
                mIsSwiping = false;
                mTouchedView = null;
                mLongPressSent = false;
                mCallback.onLongPressSent(null);
                mMenuRowIntercepting = false;
                cancelLongPress();
                if (captured) return true;
                break;
        }
        return mIsSwiping || mLongPressSent || mMenuRowIntercepting;
    }

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
    public void dismissChild(final View animView, float velocity, final Runnable endAction,
            long delay, boolean useAccelerateInterpolator, long fixedDuration,
            boolean isDismissAll) {
        final boolean canBeDismissed = mCallback.canChildBeDismissed(animView);
        float newPos;
        boolean isLayoutRtl = animView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // if we use the Menu to dismiss an item in landscape, animate up
        boolean animateUpForMenu = velocity == 0 && (getTranslation(animView) == 0 || isDismissAll)
                && mSwipeDirection == Y;
        // if the language is rtl we prefer swiping to the left
        boolean animateLeftForRtl = velocity == 0 && (getTranslation(animView) == 0 || isDismissAll)
                && isLayoutRtl;
        boolean animateLeft = (Math.abs(velocity) > getEscapeVelocity() && velocity < 0) ||
                (getTranslation(animView) < 0 && !isDismissAll);
        if (animateLeft || animateLeftForRtl || animateUpForMenu) {
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
                    resetSwipeState();
                }
                if (endAction != null) {
                    endAction.run();
                }
                if (!mDisableHwLayers) {
                    animView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
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

    public void snapChild(final View animView, final float targetLeft, float velocity) {
        final boolean canBeDismissed = mCallback.canChildBeDismissed(animView);
        AnimatorUpdateListener updateListener = animation -> onTranslationUpdate(animView,
                (float) animation.getAnimatedValue(), canBeDismissed);

        Animator anim = getViewTranslationAnimator(animView, targetLeft, updateListener);
        if (anim == null) {
            return;
        }
        anim.addListener(new AnimatorListenerAdapter() {
            boolean wasCancelled = false;

            @Override
            public void onAnimationCancel(Animator animator) {
                wasCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                mSnappingChild = false;
                if (!wasCancelled) {
                    updateSwipeProgressFromOffset(animView, canBeDismissed);
                    resetSwipeState();
                }
            }
        });
        prepareSnapBackAnimation(animView, anim);
        mSnappingChild = true;
        float maxDistance = Math.abs(targetLeft - getTranslation(animView));
        mFlingAnimationUtils.apply(anim, getTranslation(animView), targetLeft, velocity,
                maxDistance);
        anim.start();
        mCallback.onChildSnappedBack(animView, targetLeft);
    }

    /**
     * Give the swipe helper itself a chance to do something on snap back so NSSL doesn't have
     * to tell us what to do
     */
    protected void onChildSnappedBack(View animView, float targetLeft) {
    }

    /**
     * Called to update the snap back animation.
     */
    protected void prepareSnapBackAnimation(View view, Animator anim) {
        // Do nothing
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

    public void resetSwipeState() {
        mTouchedView = null;
        mIsSwiping = false;
    }

    private float getTouchSlop(MotionEvent event) {
        // Adjust the touch slop if another gesture may be being performed.
        return event.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                ? mTouchSlop * mTouchSlopMultiplier
                : mTouchSlop;
    }

    private boolean isAvailableToDragAndDrop(View v) {
        if (v.getResources().getBoolean(R.bool.config_notificationToContents)) {
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
