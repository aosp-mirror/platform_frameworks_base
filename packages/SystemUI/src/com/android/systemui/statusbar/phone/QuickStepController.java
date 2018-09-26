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

import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static com.android.systemui.Interpolators.ALPHA_IN;
import static com.android.systemui.Interpolators.ALPHA_OUT;
import static com.android.systemui.OverviewProxyService.DEBUG_OVERVIEW_PROXY;
import static com.android.systemui.OverviewProxyService.TAG_OPS;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_DEAD_ZONE;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_HOME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Slog;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManagerGlobal;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.system.NavigationBarCompat;
import java.io.PrintWriter;

/**
 * Class to detect gestures on the navigation bar and implement quick scrub.
 */
public class QuickStepController implements GestureHelper {

    private static final String TAG = "QuickStepController";
    private static final int ANIM_IN_DURATION_MS = 150;
    private static final int ANIM_OUT_DURATION_MS = 134;
    private static final float TRACK_SCALE = 0.95f;
    private static final float GRADIENT_WIDTH = .75f;

    /** Experiment to swipe home button left to execute a back key press */
    private static final String PULL_HOME_GO_BACK_PROP = "persist.quickstepcontroller.homegoesback";
    private static final String HIDE_BACK_BUTTON_PROP = "persist.quickstepcontroller.hideback";
    private static final String BACK_AFTER_END_PROP
            = "persist.quickstepcontroller.homegoesbackwhenend";
    private static final long BACK_BUTTON_FADE_OUT_ALPHA = 60;
    private static final long BACK_BUTTON_FADE_IN_ALPHA = 150;
    private static final long BACK_GESTURE_POLL_TIMEOUT = 1000;

    /** When the home-swipe-back gesture is disallowed, make it harder to pull */
    private static final float DISALLOW_GESTURE_DAMPING_FACTOR = 0.16f;

    private NavigationBarView mNavigationBarView;

    private boolean mQuickScrubActive;
    private boolean mAllowGestureDetection;
    private boolean mBackGestureActive;
    private boolean mCanPerformBack;
    private boolean mQuickStepStarted;
    private boolean mNotificationsVisibleOnDown;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mDragPositive;
    private boolean mIsVertical;
    private boolean mIsRTL;
    private float mTrackAlpha;
    private float mTrackScale = TRACK_SCALE;
    private float mDarkIntensity;
    private RadialGradient mHighlight;
    private float mHighlightCenter;
    private AnimatorSet mTrackAnimator;
    private ViewPropertyAnimator mHomeAnimator;
    private ButtonDispatcher mHitTarget;
    private View mCurrentNavigationBarView;
    private boolean mIsInScreenPinning;

    private final Handler mHandler = new Handler();
    private final Rect mTrackRect = new Rect();
    private final OverviewProxyService mOverviewEventSender;
    private final int mTrackThickness;
    private final int mTrackEndPadding;
    private final int mHomeBackGestureDragLimit;
    private final Context mContext;
    private final StatusBar mStatusBar;
    private final Matrix mTransformGlobalMatrix = new Matrix();
    private final Matrix mTransformLocalMatrix = new Matrix();
    private final Paint mTrackPaint = new Paint();

    public static boolean swipeHomeGoBackGestureEnabled() {
        return SystemProperties.getBoolean(PULL_HOME_GO_BACK_PROP, false);
    }
    public static boolean shouldhideBackButton() {
        return swipeHomeGoBackGestureEnabled()
            && SystemProperties.getBoolean(HIDE_BACK_BUTTON_PROP, false);
    }

    private final FloatProperty<QuickStepController> mTrackAlphaProperty =
            new FloatProperty<QuickStepController>("TrackAlpha") {
        @Override
        public void setValue(QuickStepController controller, float alpha) {
            mTrackAlpha = alpha;
            mNavigationBarView.invalidate();
        }

        @Override
        public Float get(QuickStepController controller) {
            return mTrackAlpha;
        }
    };

    private final FloatProperty<QuickStepController> mTrackScaleProperty =
            new FloatProperty<QuickStepController>("TrackScale") {
        @Override
        public void setValue(QuickStepController controller, float scale) {
            mTrackScale = scale;
            mNavigationBarView.invalidate();
        }

        @Override
        public Float get(QuickStepController controller) {
            return mTrackScale;
        }
    };

    private final FloatProperty<QuickStepController> mNavBarAlphaProperty =
            new FloatProperty<QuickStepController>("NavBarAlpha") {
        @Override
        public void setValue(QuickStepController controller, float alpha) {
            if (mCurrentNavigationBarView != null) {
                mCurrentNavigationBarView.setAlpha(alpha);
            }
        }

        @Override
        public Float get(QuickStepController controller) {
            if (mCurrentNavigationBarView != null) {
                return mCurrentNavigationBarView.getAlpha();
            }
            return 1f;
        }
    };

    private AnimatorListenerAdapter mQuickScrubEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            resetQuickScrub();
        }
    };

    private final Runnable mExecuteBackRunnable = new Runnable() {
        @Override
        public void run() {
            if (canPerformHomeBackGesture()) {
                performBack();
                mHandler.postDelayed(this, BACK_GESTURE_POLL_TIMEOUT);
            }
        }
    };

    public QuickStepController(Context context) {
        final Resources res = context.getResources();
        mContext = context;
        mStatusBar = SysUiServiceProvider.getComponent(context, StatusBar.class);
        mOverviewEventSender = Dependency.get(OverviewProxyService.class);
        mTrackThickness = res.getDimensionPixelSize(R.dimen.nav_quick_scrub_track_thickness);
        mTrackEndPadding = res.getDimensionPixelSize(R.dimen.nav_quick_scrub_track_edge_padding);
        mHomeBackGestureDragLimit =
                res.getDimensionPixelSize(R.dimen.nav_home_back_gesture_drag_limit);
        mTrackPaint.setAntiAlias(true);
        mTrackPaint.setDither(true);
    }

    public void setComponents(NavigationBarView navigationBarView) {
        mNavigationBarView = navigationBarView;

        mNavigationBarView.getBackButton().setVisibility(shouldhideBackButton()
                ? View.GONE
                : View.VISIBLE);
    }

    /**
     * @return true if we want to intercept touch events for quick scrub and prevent proxying the
     *         event to the overview service.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mStatusBar.isKeyguardShowing()) {
            // Disallow any handling when the keyguard is showing
            return false;
        }
        return handleTouchEvent(event);
    }

    /**
     * @return true if we want to handle touch events for quick scrub or if down event (that will
     *         get consumed and ignored). No events will be proxied to the overview service.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mStatusBar.isKeyguardShowing()) {
            // Disallow any handling when the keyguard is showing
            return false;
        }

        // The same down event was just sent on intercept and therefore can be ignored here
        final boolean ignoreProxyDownEvent = event.getAction() == MotionEvent.ACTION_DOWN
                && mOverviewEventSender.getProxy() != null;
        return ignoreProxyDownEvent || handleTouchEvent(event);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        final boolean deadZoneConsumed =
                mNavigationBarView.getDownHitTarget() == HIT_TARGET_DEAD_ZONE;
        if (mOverviewEventSender.getProxy() == null || (!mNavigationBarView.isQuickScrubEnabled()
                && !mNavigationBarView.isQuickStepSwipeUpEnabled())) {
            return deadZoneConsumed;
        }
        mNavigationBarView.requestUnbufferedDispatch(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int x = (int) event.getX();
                int y = (int) event.getY();
                mIsInScreenPinning = mNavigationBarView.inScreenPinning();

                // End any existing quickscrub animations before starting the new transition
                if (mTrackAnimator != null) {
                    mTrackAnimator.end();
                    mTrackAnimator = null;
                }

                mCurrentNavigationBarView = mNavigationBarView.getCurrentView();
                mHitTarget = mNavigationBarView.getButtonAtPosition(x, y);
                if (mHitTarget != null) {
                    // Pre-emptively delay the touch feedback for the button that we just touched
                    mHitTarget.setDelayTouchFeedback(true);
                }
                mTouchDownX = x;
                mTouchDownY = y;
                mTransformGlobalMatrix.set(Matrix.IDENTITY_MATRIX);
                mTransformLocalMatrix.set(Matrix.IDENTITY_MATRIX);
                mNavigationBarView.transformMatrixToGlobal(mTransformGlobalMatrix);
                mNavigationBarView.transformMatrixToLocal(mTransformLocalMatrix);
                mQuickStepStarted = false;
                mBackGestureActive = false;
                mAllowGestureDetection = true;
                mNotificationsVisibleOnDown = !mNavigationBarView.isNotificationsFullyCollapsed();
                mCanPerformBack = canPerformHomeBackGesture();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mQuickStepStarted || !mAllowGestureDetection){
                    break;
                }
                int x = (int) event.getX();
                int y = (int) event.getY();
                int xDiff = Math.abs(x - mTouchDownX);
                int yDiff = Math.abs(y - mTouchDownY);

                boolean exceededScrubTouchSlop, exceededSwipeUpTouchSlop;
                int pos, touchDown, offset, trackSize;

                if (mIsVertical) {
                    exceededScrubTouchSlop =
                            yDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && yDiff > xDiff;
                    exceededSwipeUpTouchSlop =
                            xDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && xDiff > yDiff;
                    pos = y;
                    touchDown = mTouchDownY;
                    offset = pos - mTrackRect.top;
                    trackSize = mTrackRect.height();
                } else {
                    exceededScrubTouchSlop =
                            xDiff > NavigationBarCompat.getQuickScrubTouchSlopPx() && xDiff > yDiff;
                    exceededSwipeUpTouchSlop =
                            yDiff > NavigationBarCompat.getQuickStepTouchSlopPx() && yDiff > xDiff;
                    pos = x;
                    touchDown = mTouchDownX;
                    offset = pos - mTrackRect.left;
                    trackSize = mTrackRect.width();
                }
                // Decide to start quickstep if dragging away from the navigation bar, otherwise in
                // the parallel direction, decide to start quickscrub. Only one may run.
                if (!mBackGestureActive && !mQuickScrubActive && exceededSwipeUpTouchSlop) {
                    if (mNavigationBarView.isQuickStepSwipeUpEnabled()
                            && !mNotificationsVisibleOnDown) {
                        startQuickStep(event);
                    }
                    break;
                }

                // Do not handle quick scrub if disabled
                if (!mNavigationBarView.isQuickScrubEnabled()) {
                    break;
                }

                if (!mDragPositive) {
                    offset -= mIsVertical ? mTrackRect.height() : mTrackRect.width();
                }

                final boolean allowDrag = !mDragPositive
                        ? offset < 0 && pos < touchDown : offset >= 0 && pos > touchDown;
                float scrubFraction = Utilities.clamp(Math.abs(offset) * 1f / trackSize, 0, 1);
                if (!mQuickScrubActive && !mBackGestureActive && exceededScrubTouchSlop) {
                    // Passing the drag slop then touch slop will start quick step
                    if (allowDrag) {
                        startQuickScrub();
                    } else if (swipeHomeGoBackGestureEnabled()
                            && mNavigationBarView.getDownHitTarget() == HIT_TARGET_HOME
                            && mDragPositive ? pos < touchDown : pos > touchDown) {
                        startBackGesture();
                    }
                }

                if (mQuickScrubActive && (mDragPositive && offset >= 0
                        || !mDragPositive && offset <= 0)) {
                    try {
                        mOverviewEventSender.getProxy().onQuickScrubProgress(scrubFraction);
                        if (DEBUG_OVERVIEW_PROXY) {
                            Log.d(TAG_OPS, "Quick Scrub Progress:" + scrubFraction);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to send progress of quick scrub.", e);
                    }
                    mHighlightCenter = x;
                    mNavigationBarView.invalidate();
                } else if (mBackGestureActive) {
                    int diff = pos - touchDown;
                    // If dragging the incorrect direction after starting back gesture or unable
                    // to execute back functionality, then move home but dampen its distance
                    if (!mCanPerformBack || (mDragPositive ? diff > 0 : diff < 0)) {
                        diff *= DISALLOW_GESTURE_DAMPING_FACTOR;
                    } if (Math.abs(diff) > mHomeBackGestureDragLimit) {
                        // Once the user drags the home button past a certain limit, the distance
                        // will lessen as the home button dampens showing that it was pulled too far
                        float distanceAfterDragLimit = (Math.abs(diff) - mHomeBackGestureDragLimit)
                                * DISALLOW_GESTURE_DAMPING_FACTOR;
                        diff = (int)(distanceAfterDragLimit + mHomeBackGestureDragLimit);
                        if (mDragPositive) {
                            diff *= -1;
                        }
                    }
                    moveHomeButton(diff);
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                endQuickScrub(true /* animate */);
                endBackGesture();
                break;
        }

        if (shouldProxyEvents(action)) {
            proxyMotionEvents(event);
        }
        return mBackGestureActive || mQuickScrubActive || mQuickStepStarted || deadZoneConsumed;
    }

    private boolean shouldProxyEvents(int action) {
        if (!mBackGestureActive && !mQuickScrubActive && !mIsInScreenPinning) {
            // Allow down, cancel and up events, move and other events are passed if notifications
            // are not showing and disabled gestures (such as long press) are not executed
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    return true;
                default:
                    return !mNotificationsVisibleOnDown && mAllowGestureDetection;
            }
        }
        return false;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (!mNavigationBarView.isQuickScrubEnabled()) {
            return;
        }
        mTrackPaint.setAlpha(Math.round(255f * mTrackAlpha));

        // Scale the track, but apply the inverse scale from the nav bar
        final float radius = mTrackRect.height() / 2;
        canvas.save();
        float translate = Utilities.clamp(mHighlightCenter, mTrackRect.left, mTrackRect.right);
        canvas.translate(translate, 0);
        canvas.scale(mTrackScale / mNavigationBarView.getScaleX(),
                1f / mNavigationBarView.getScaleY(),
                mTrackRect.centerX(), mTrackRect.centerY());
        canvas.drawRoundRect(mTrackRect.left - translate, mTrackRect.top,
                mTrackRect.right - translate, mTrackRect.bottom, radius, radius, mTrackPaint);
        canvas.restore();
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int paddingLeft = mNavigationBarView.getPaddingLeft();
        final int paddingTop = mNavigationBarView.getPaddingTop();
        final int paddingRight = mNavigationBarView.getPaddingRight();
        final int paddingBottom = mNavigationBarView.getPaddingBottom();
        final int width = (right - left) - paddingRight - paddingLeft;
        final int height = (bottom - top) - paddingBottom - paddingTop;
        final int x1, x2, y1, y2;
        if (mIsVertical) {
            x1 = (width - mTrackThickness) / 2 + paddingLeft;
            x2 = x1 + mTrackThickness;
            y1 = paddingTop + mTrackEndPadding;
            y2 = y1 + height - 2 * mTrackEndPadding;
        } else {
            y1 = (height - mTrackThickness) / 2 + paddingTop;
            y2 = y1 + mTrackThickness;
            x1 = mNavigationBarView.getPaddingStart() + mTrackEndPadding;
            x2 = x1 + width - 2 * mTrackEndPadding;
        }
        mTrackRect.set(x1, y1, x2, y2);
        updateHighlight();
    }

    @Override
    public void onDarkIntensityChange(float intensity) {
        final float oldIntensity = mDarkIntensity;
        mDarkIntensity = intensity;

        // When in quick scrub, invalidate gradient if changing intensity from black to white and
        // vice-versa
        if (mNavigationBarView.isQuickScrubEnabled()
                && Math.round(intensity) != Math.round(oldIntensity)) {
            updateHighlight();
        }
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

    @Override
    public void dump(PrintWriter pw) {
        pw.println("QuickStepController {");
        pw.print("    "); pw.println("mQuickScrubActive=" + mQuickScrubActive);
        pw.print("    "); pw.println("mQuickStepStarted=" + mQuickStepStarted);
        pw.print("    "); pw.println("mAllowGestureDetection=" + mAllowGestureDetection);
        pw.print("    "); pw.println("mBackGestureActive=" + mBackGestureActive);
        pw.print("    "); pw.println("mCanPerformBack=" + mCanPerformBack);
        pw.print("    "); pw.println("mNotificationsVisibleOnDown=" + mNotificationsVisibleOnDown);
        pw.print("    "); pw.println("mIsVertical=" + mIsVertical);
        pw.print("    "); pw.println("mIsRTL=" + mIsRTL);
        pw.print("    "); pw.println("mIsInScreenPinning=" + mIsInScreenPinning);
        pw.println("}");
    }

    private void startQuickStep(MotionEvent event) {
        if (mIsInScreenPinning) {
            mNavigationBarView.showPinningEscapeToast();
            mAllowGestureDetection = false;
            return;
        }

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
        mHandler.removeCallbacksAndMessages(null);

        if (mHitTarget != null) {
            mHitTarget.abortCurrentGesture();
        }

        if (mQuickScrubActive) {
            animateEnd();
        }
    }

    private void startQuickScrub() {
        if (mIsInScreenPinning) {
            mNavigationBarView.showPinningEscapeToast();
            mAllowGestureDetection = false;
            return;
        }

        if (!mQuickScrubActive) {
            updateHighlight();
            mQuickScrubActive = true;
            ObjectAnimator trackAnimator = ObjectAnimator.ofPropertyValuesHolder(this,
                    PropertyValuesHolder.ofFloat(mTrackAlphaProperty, 1f),
                    PropertyValuesHolder.ofFloat(mTrackScaleProperty, 1f));
            trackAnimator.setInterpolator(ALPHA_IN);
            trackAnimator.setDuration(ANIM_IN_DURATION_MS);
            ObjectAnimator navBarAnimator = ObjectAnimator.ofFloat(this, mNavBarAlphaProperty, 0f);
            navBarAnimator.setInterpolator(ALPHA_OUT);
            navBarAnimator.setDuration(ANIM_OUT_DURATION_MS);
            mTrackAnimator = new AnimatorSet();
            mTrackAnimator.playTogether(trackAnimator, navBarAnimator);
            mTrackAnimator.start();

            // Disable slippery for quick scrub to not cancel outside the nav bar
            mNavigationBarView.updateSlippery();

            try {
                mOverviewEventSender.getProxy().onQuickScrubStart();
                if (DEBUG_OVERVIEW_PROXY) {
                    Log.d(TAG_OPS, "Quick Scrub Start");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send start of quick scrub.", e);
            }
            mOverviewEventSender.notifyQuickScrubStarted();

            if (mHitTarget != null) {
                mHitTarget.abortCurrentGesture();
            }
        }
    }

    private void endQuickScrub(boolean animate) {
        if (mQuickScrubActive) {
            animateEnd();
            try {
                mOverviewEventSender.getProxy().onQuickScrubEnd();
                if (DEBUG_OVERVIEW_PROXY) {
                    Log.d(TAG_OPS, "Quick Scrub End");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send end of quick scrub.", e);
            }
        }
        if (!animate) {
            if (mTrackAnimator != null) {
                mTrackAnimator.end();
                mTrackAnimator = null;
            }
        }
    }

    private void startBackGesture() {
        if (!mBackGestureActive) {
            mBackGestureActive = true;
            mNavigationBarView.getHomeButton().abortCurrentGesture();
            final boolean runBackMidGesture
                    = !SystemProperties.getBoolean(BACK_AFTER_END_PROP, false);
            if (mCanPerformBack) {
                if (!shouldhideBackButton()) {
                    mNavigationBarView.getBackButton().setAlpha(0 /* alpha */, true /* animate */,
                            BACK_BUTTON_FADE_OUT_ALPHA);
                }
                if (runBackMidGesture) {
                    performBack();
                }
            }
            mHandler.removeCallbacks(mExecuteBackRunnable);
            if (runBackMidGesture) {
                mHandler.postDelayed(mExecuteBackRunnable, BACK_GESTURE_POLL_TIMEOUT);
            }
        }
    }

    private void endBackGesture() {
        if (mBackGestureActive) {
            mHandler.removeCallbacks(mExecuteBackRunnable);
            mHomeAnimator = mNavigationBarView.getHomeButton().getCurrentView()
                    .animate()
                    .setDuration(BACK_BUTTON_FADE_IN_ALPHA)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
            if (mIsVertical) {
                mHomeAnimator.translationY(0);
            } else {
                mHomeAnimator.translationX(0);
            }
            mHomeAnimator.start();
            if (!shouldhideBackButton()) {
                mNavigationBarView.getBackButton().setAlpha(
                        mOverviewEventSender.getBackButtonAlpha(), true /* animate */);
            }
            if (SystemProperties.getBoolean(BACK_AFTER_END_PROP, false)) {
                performBack();
            }
        }
    }

    private void animateEnd() {
        if (mTrackAnimator != null) {
            mTrackAnimator.cancel();
        }

        ObjectAnimator trackAnimator = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(mTrackAlphaProperty, 0f),
                PropertyValuesHolder.ofFloat(mTrackScaleProperty, TRACK_SCALE));
        trackAnimator.setInterpolator(ALPHA_OUT);
        trackAnimator.setDuration(ANIM_OUT_DURATION_MS);
        ObjectAnimator navBarAnimator = ObjectAnimator.ofFloat(this, mNavBarAlphaProperty, 1f);
        navBarAnimator.setInterpolator(ALPHA_IN);
        navBarAnimator.setDuration(ANIM_IN_DURATION_MS);
        mTrackAnimator = new AnimatorSet();
        mTrackAnimator.playTogether(trackAnimator, navBarAnimator);
        mTrackAnimator.addListener(mQuickScrubEndListener);
        mTrackAnimator.start();
    }

    private void resetQuickScrub() {
        mQuickScrubActive = false;
        mAllowGestureDetection = false;
        if (mCurrentNavigationBarView != null) {
            mCurrentNavigationBarView.setAlpha(1f);
        }
        mCurrentNavigationBarView = null;
        updateHighlight();
    }

    private void moveHomeButton(float pos) {
        if (mHomeAnimator != null) {
            mHomeAnimator.cancel();
            mHomeAnimator = null;
        }
        final View homeButton = mNavigationBarView.getHomeButton().getCurrentView();
        if (mIsVertical) {
            homeButton.setTranslationY(pos);
        } else {
            homeButton.setTranslationX(pos);
        }
    }

    private void updateHighlight() {
        if (mTrackRect.isEmpty()) {
            return;
        }
        int colorBase, colorGrad;
        if (mDarkIntensity > 0.5f) {
            colorBase = mContext.getColor(R.color.quick_step_track_background_background_dark);
            colorGrad = mContext.getColor(R.color.quick_step_track_background_foreground_dark);
        } else {
            colorBase = mContext.getColor(R.color.quick_step_track_background_background_light);
            colorGrad = mContext.getColor(R.color.quick_step_track_background_foreground_light);
        }
        mHighlight = new RadialGradient(0, mTrackRect.height() / 2,
                mTrackRect.width() * GRADIENT_WIDTH, colorGrad, colorBase,
                Shader.TileMode.CLAMP);
        mTrackPaint.setShader(mHighlight);
    }

    private boolean canPerformHomeBackGesture() {
        return swipeHomeGoBackGestureEnabled() && mOverviewEventSender.getBackButtonAlpha() > 0;
    }

    private void performBack() {
        sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
        mNavigationBarView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void sendEvent(int action, int code) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        InputManager.getInstance().injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
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
}
