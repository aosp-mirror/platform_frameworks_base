/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManagerGlobal;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.support.annotation.DimenRes;
import com.android.systemui.Dependency;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.system.NavigationBarCompat;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static com.android.systemui.OverviewProxyService.DEBUG_OVERVIEW_PROXY;
import static com.android.systemui.OverviewProxyService.TAG_OPS;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_HOME;

/**
 * Class to detect gestures on the navigation bar and implement quick scrub.
 */
public class QuickStepController implements GestureHelper {

    private static final String TAG = "QuickStepController";
    private static final int ANIM_DURATION_MS = 200;

    private NavigationBarView mNavigationBarView;

    private boolean mQuickScrubActive;
    private boolean mAllowGestureDetection;
    private boolean mQuickStepStarted;
    private float mDownOffset;
    private float mTranslation;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mDragScrubActive;
    private boolean mDragPositive;
    private boolean mIsVertical;
    private boolean mIsRTL;
    private float mTrackAlpha;
    private int mLightTrackColor;
    private int mDarkTrackColor;
    private float mDarkIntensity;
    private View mHomeButtonView;

    private final Handler mHandler = new Handler();
    private final Interpolator mQuickScrubEndInterpolator = new DecelerateInterpolator();
    private final Rect mTrackRect = new Rect();
    private final Paint mTrackPaint = new Paint();
    private final OverviewProxyService mOverviewEventSender;
    private final int mTrackThickness;
    private final int mTrackPadding;
    private final ValueAnimator mTrackAnimator;
    private final ValueAnimator mButtonAnimator;
    private final AnimatorSet mQuickScrubEndAnimator;
    private final Context mContext;
    private final Matrix mTransformGlobalMatrix = new Matrix();
    private final Matrix mTransformLocalMatrix = new Matrix();
    private final ArgbEvaluator mTrackColorEvaluator = new ArgbEvaluator();

    private final AnimatorUpdateListener mTrackAnimatorListener = valueAnimator -> {
        mTrackAlpha = (float) valueAnimator.getAnimatedValue();
        mNavigationBarView.invalidate();
    };

    private final AnimatorUpdateListener mButtonTranslationListener = animator -> {
        int pos = (int) animator.getAnimatedValue();
        if (!mQuickScrubActive) {
            pos = mDragPositive ? Math.min((int) mTranslation, pos) : Math.max((int) mTranslation, pos);
        }
        if (mIsVertical) {
            mHomeButtonView.setTranslationY(pos);
        } else {
            mHomeButtonView.setTranslationX(pos);
        }
    };

    private AnimatorListenerAdapter mQuickScrubEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mQuickScrubActive = false;
            mDragScrubActive = false;
            mTranslation = 0;
            mQuickScrubEndAnimator.setCurrentPlayTime(mQuickScrubEndAnimator.getDuration());
            mHomeButtonView = null;
        }
    };

    public QuickStepController(Context context) {
        mContext = context;
        mOverviewEventSender = Dependency.get(OverviewProxyService.class);
        mTrackThickness = getDimensionPixelSize(mContext, R.dimen.nav_quick_scrub_track_thickness);
        mTrackPadding = getDimensionPixelSize(mContext, R.dimen.nav_quick_scrub_track_edge_padding);
        mTrackPaint.setAlpha(0);

        mTrackAnimator = ObjectAnimator.ofFloat();
        mTrackAnimator.addUpdateListener(mTrackAnimatorListener);
        mTrackAnimator.setFloatValues(0);
        mButtonAnimator = ObjectAnimator.ofInt();
        mButtonAnimator.addUpdateListener(mButtonTranslationListener);
        mButtonAnimator.setIntValues(0);
        mQuickScrubEndAnimator = new AnimatorSet();
        mQuickScrubEndAnimator.playTogether(mTrackAnimator, mButtonAnimator);
        mQuickScrubEndAnimator.setDuration(ANIM_DURATION_MS);
        mQuickScrubEndAnimator.addListener(mQuickScrubEndListener);
        mQuickScrubEndAnimator.setInterpolator(mQuickScrubEndInterpolator);
    }

    public void setComponents(NavigationBarView navigationBarView) {
        mNavigationBarView = navigationBarView;
    }

    /**
     * @return true if we want to intercept touch events for quick scrub and prevent proxying the
     *         event to the overview service.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return handleTouchEvent(event);
    }

    /**
     * @return true if we want to handle touch events for quick scrub or if down event (that will
     *         get consumed and ignored). No events will be proxied to the overview service.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // The same down event was just sent on intercept and therefore can be ignored here
        final boolean ignoreProxyDownEvent = event.getAction() == MotionEvent.ACTION_DOWN
                && mOverviewEventSender.getProxy() != null;
        return ignoreProxyDownEvent || handleTouchEvent(event);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        if (mOverviewEventSender.getProxy() == null || (!mNavigationBarView.isQuickScrubEnabled()
                && !mNavigationBarView.isQuickStepSwipeUpEnabled())) {
            mNavigationBarView.getHomeButton().setDelayTouchFeedback(false /* delay */);
            return false;
        }
        mNavigationBarView.requestUnbufferedDispatch(event);

        final ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        final boolean homePressed = mNavigationBarView.getDownHitTarget() == HIT_TARGET_HOME;
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                // End any existing quickscrub animations before starting the new transition
                if (mHomeButtonView != null) {
                    mQuickScrubEndAnimator.end();
                }
                mHomeButtonView = homeButton.getCurrentView();
                homeButton.setDelayTouchFeedback(true /* delay */);
                mTouchDownX = x;
                mTouchDownY = y;
                mTransformGlobalMatrix.set(Matrix.IDENTITY_MATRIX);
                mTransformLocalMatrix.set(Matrix.IDENTITY_MATRIX);
                mNavigationBarView.transformMatrixToGlobal(mTransformGlobalMatrix);
                mNavigationBarView.transformMatrixToLocal(mTransformLocalMatrix);
                mQuickStepStarted = false;
                mAllowGestureDetection = true;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mQuickStepStarted || !mAllowGestureDetection || mHomeButtonView == null){
                    break;
                }
                int x = (int) event.getX();
                int y = (int) event.getY();
                int xDiff = Math.abs(x - mTouchDownX);
                int yDiff = Math.abs(y - mTouchDownY);

                boolean exceededScrubTouchSlop, exceededSwipeUpTouchSlop, exceededScrubDragSlop;
                int pos, touchDown, offset, trackSize;

                if (mIsVertical) {
                    exceededScrubTouchSlop =
                            yDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && yDiff > xDiff;
                    exceededSwipeUpTouchSlop =
                            xDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && xDiff > yDiff;
                    exceededScrubDragSlop =
                            yDiff > NavigationBarCompat.getQuickScrubDragSlopPx() && yDiff > xDiff;
                    pos = y;
                    touchDown = mTouchDownY;
                    offset = pos - mTrackRect.top;
                    trackSize = mTrackRect.height();
                } else {
                    exceededScrubTouchSlop =
                            xDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && xDiff > yDiff;
                    exceededSwipeUpTouchSlop =
                            yDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && yDiff > xDiff;
                    exceededScrubDragSlop =
                            xDiff > NavigationBarCompat.getQuickScrubDragSlopPx() && xDiff > yDiff;
                    pos = x;
                    touchDown = mTouchDownX;
                    offset = pos - mTrackRect.left;
                    trackSize = mTrackRect.width();
                }
                // Decide to start quickstep if dragging away from the navigation bar, otherwise in
                // the parallel direction, decide to start quickscrub. Only one may run.
                if (!mQuickScrubActive && exceededSwipeUpTouchSlop) {
                    if (mNavigationBarView.isQuickStepSwipeUpEnabled()) {
                        startQuickStep(event);
                    }
                    break;
                }

                // Do not handle quick scrub if disabled or hit target is not home button
                if (!homePressed || !mNavigationBarView.isQuickScrubEnabled()) {
                    break;
                }

                if (!mDragPositive) {
                    offset -= mIsVertical ? mTrackRect.height() : mTrackRect.width();
                }

                final boolean allowDrag = !mDragPositive
                        ? offset < 0 && pos < touchDown : offset >= 0 && pos > touchDown;
                if (allowDrag) {
                    // Passing the drag slop is for visual feedback and will not initiate anything
                    if (!mDragScrubActive && exceededScrubDragSlop) {
                        mDownOffset = offset;
                        mDragScrubActive = true;
                    }

                    // Passing the drag slop then touch slop will start quick step
                    if (!mQuickScrubActive && exceededScrubTouchSlop) {
                        homeButton.abortCurrentGesture();
                        startQuickScrub();
                    }
                }

                if ((mQuickScrubActive || mDragScrubActive) && (mDragPositive && offset >= 0
                        || !mDragPositive && offset <= 0)) {
                    mTranslation = !mDragPositive
                            ? Utilities.clamp(offset - mDownOffset, -trackSize, 0)
                            : Utilities.clamp(offset - mDownOffset, 0, trackSize);
                    if (mQuickScrubActive) {
                        float scrubFraction =
                                Utilities.clamp(Math.abs(offset) * 1f / trackSize, 0, 1);
                        try {
                            mOverviewEventSender.getProxy().onQuickScrubProgress(scrubFraction);
                            if (DEBUG_OVERVIEW_PROXY) {
                                Log.d(TAG_OPS, "Quick Scrub Progress:" + scrubFraction);
                            }
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to send progress of quick scrub.", e);
                        }
                    }
                    if (mIsVertical) {
                        mHomeButtonView.setTranslationY(mTranslation);
                    } else {
                        mHomeButtonView.setTranslationX(mTranslation);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                endQuickScrub(true /* animate */);
                break;
        }

        // Proxy motion events to launcher if not handled by quick scrub
        // Proxy motion events up/cancel that would be sent after long press on any nav button
        if (!mQuickScrubActive && (mAllowGestureDetection || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_UP)) {
            proxyMotionEvents(event);
        }
        return mQuickScrubActive || mQuickStepStarted;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (!mNavigationBarView.isQuickScrubEnabled()) {
            return;
        }
        int color = (int) mTrackColorEvaluator.evaluate(mDarkIntensity, mLightTrackColor,
                mDarkTrackColor);
        mTrackPaint.setColor(color);
        mTrackPaint.setAlpha((int) (mTrackPaint.getAlpha() * mTrackAlpha));
        canvas.drawRect(mTrackRect, mTrackPaint);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int width = (right - left) - mNavigationBarView.getPaddingEnd()
                - mNavigationBarView.getPaddingStart();
        final int height = (bottom - top) - mNavigationBarView.getPaddingBottom()
                - mNavigationBarView.getPaddingTop();
        final int x1, x2, y1, y2;
        if (mIsVertical) {
            x1 = (width - mTrackThickness) / 2 + mNavigationBarView.getPaddingLeft();
            x2 = x1 + mTrackThickness;
            y1 = mDragPositive ? height / 2 : mTrackPadding;
            y2 = y1 + height / 2 - mTrackPadding;
        } else {
            y1 = (height - mTrackThickness) / 2 + mNavigationBarView.getPaddingTop();
            y2 = y1 + mTrackThickness;
            x1 = mDragPositive ? width / 2 : mTrackPadding;
            x2 = x1 + width / 2 - mTrackPadding;
        }
        mTrackRect.set(x1, y1, x2, y2);
    }

    @Override
    public void onDarkIntensityChange(float intensity) {
        mDarkIntensity = intensity;
        mNavigationBarView.invalidate();
    }

    @Override
    public void setBarState(boolean isVertical, boolean isRTL) {
        final boolean changed = (mIsVertical != isVertical) || (mIsRTL != isRTL);
        if (changed) {
            // End quickscrub if the state changes mid-transition
            endQuickScrub(false /* animate */);
        }
        mIsVertical = isVertical;
        mIsRTL = isRTL;
        try {
            int navbarPos = WindowManagerGlobal.getWindowManagerService().getNavBarPosition();
            mDragPositive = navbarPos == NAV_BAR_LEFT || navbarPos == NAV_BAR_BOTTOM;
            if (isRTL) {
                mDragPositive = !mDragPositive;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get nav bar position.", e);
        }
    }

    @Override
    public void onNavigationButtonLongPress(View v) {
        mAllowGestureDetection = false;
        mHandler.removeCallbacksAndMessages(null);
    }

    private void startQuickStep(MotionEvent event) {
        mQuickStepStarted = true;
        event.transform(mTransformGlobalMatrix);
        try {
            mOverviewEventSender.getProxy().onQuickStep(event);
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Quick Step Start");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send quick step started.", e);
        } finally {
            event.transform(mTransformLocalMatrix);
        }
        mOverviewEventSender.notifyQuickStepStarted();
        mNavigationBarView.getHomeButton().abortCurrentGesture();
        mHandler.removeCallbacksAndMessages(null);

        if (mDragScrubActive) {
            animateEnd();
        }
    }

    private void startQuickScrub() {
        if (!mQuickScrubActive && mDragScrubActive) {
            mQuickScrubActive = true;
            mLightTrackColor = mContext.getColor(R.color.quick_step_track_background_light);
            mDarkTrackColor = mContext.getColor(R.color.quick_step_track_background_dark);
            mTrackAnimator.setFloatValues(0, 1);
            mTrackAnimator.start();

            // Hide menu buttons on nav bar until quick scrub has ended
            mNavigationBarView.setMenuContainerVisibility(false /* visible */);

            try {
                mOverviewEventSender.getProxy().onQuickScrubStart();
                if (DEBUG_OVERVIEW_PROXY) {
                    Log.d(TAG_OPS, "Quick Scrub Start");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send start of quick scrub.", e);
            }
            mOverviewEventSender.notifyQuickScrubStarted();
        }
    }

    private void endQuickScrub(boolean animate) {
        if (mQuickScrubActive || mDragScrubActive) {
            animateEnd();

            // Restore the nav bar menu buttons visibility
            mNavigationBarView.setMenuContainerVisibility(true /* visible */);

            if (mQuickScrubActive) {
                try {
                    mOverviewEventSender.getProxy().onQuickScrubEnd();
                    if (DEBUG_OVERVIEW_PROXY) {
                        Log.d(TAG_OPS, "Quick Scrub End");
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to send end of quick scrub.", e);
                }
            }
        }
        if (mHomeButtonView != null && !animate) {
            mQuickScrubEndAnimator.end();
        }
    }

    private boolean proxyMotionEvents(MotionEvent event) {
        final IOverviewProxy overviewProxy = mOverviewEventSender.getProxy();
        event.transform(mTransformGlobalMatrix);
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                overviewProxy.onPreMotionEvent(mNavigationBarView.getDownHitTarget());
            }
            overviewProxy.onMotionEvent(event);
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Send MotionEvent: " + event.toString());
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Callback failed", e);
        } finally {
            event.transform(mTransformLocalMatrix);
        }
        return false;
    }

    private void animateEnd() {
        mButtonAnimator.setIntValues((int) mTranslation, 0);
        mTrackAnimator.setFloatValues(mTrackAlpha, 0);
        mQuickScrubEndAnimator.setCurrentPlayTime(0);
        mQuickScrubEndAnimator.start();
    }

    private int getDimensionPixelSize(Context context, @DimenRes int resId) {
        return context.getResources().getDimensionPixelSize(resId);
    }
}
