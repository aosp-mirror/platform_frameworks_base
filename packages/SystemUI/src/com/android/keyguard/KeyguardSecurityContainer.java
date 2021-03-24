/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.keyguard;

import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import static java.lang.Integer.max;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

import java.util.ArrayList;
import java.util.List;

public class KeyguardSecurityContainer extends FrameLayout {
    static final int USER_TYPE_PRIMARY = 1;
    static final int USER_TYPE_WORK_PROFILE = 2;
    static final int USER_TYPE_SECONDARY_USER = 3;

    // Bouncer is dismissed due to no security.
    static final int BOUNCER_DISMISS_NONE_SECURITY = 0;
    // Bouncer is dismissed due to pin, password or pattern entered.
    static final int BOUNCER_DISMISS_PASSWORD = 1;
    // Bouncer is dismissed due to biometric (face, fingerprint or iris) authenticated.
    static final int BOUNCER_DISMISS_BIOMETRIC = 2;
    // Bouncer is dismissed due to extended access granted.
    static final int BOUNCER_DISMISS_EXTENDED_ACCESS = 3;
    // Bouncer is dismissed due to sim card unlock code entered.
    static final int BOUNCER_DISMISS_SIM = 4;

    // Make the view move slower than the finger, as if the spring were applying force.
    private static final float TOUCH_Y_MULTIPLIER = 0.25f;
    // How much you need to drag the bouncer to trigger an auth retry (in dps.)
    private static final float MIN_DRAG_SIZE = 10;
    // How much to scale the default slop by, to avoid accidental drags.
    private static final float SLOP_SCALE = 4f;

    private static final long IME_DISAPPEAR_DURATION_MS = 125;

    @VisibleForTesting
    KeyguardSecurityViewFlipper mSecurityViewFlipper;
    private AlertDialog mAlertDialog;
    private boolean mSwipeUpToRetry;

    private final ViewConfiguration mViewConfiguration;
    private final SpringAnimation mSpringAnimation;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private final List<Gefingerpoken> mMotionEventListeners = new ArrayList<>();

    private float mLastTouchY = -1;
    private int mActivePointerId = -1;
    private boolean mIsDragging;
    private float mStartTouchY = -1;
    private boolean mDisappearAnimRunning;
    private SwipeListener mSwipeListener;

    private boolean mIsSecurityViewLeftAligned = true;
    private boolean mOneHandedMode = false;
    private SecurityMode mSecurityMode = SecurityMode.Invalid;
    private ViewPropertyAnimator mRunningOneHandedAnimator;

    private final WindowInsetsAnimation.Callback mWindowInsetsAnimationCallback =
            new WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {

                private final Rect mInitialBounds = new Rect();
                private final Rect mFinalBounds = new Rect();

                @Override
                public void onPrepare(WindowInsetsAnimation animation) {
                    mSecurityViewFlipper.getBoundsOnScreen(mInitialBounds);
                }

                @Override
                public WindowInsetsAnimation.Bounds onStart(WindowInsetsAnimation animation,
                        WindowInsetsAnimation.Bounds bounds) {
                    if (!mDisappearAnimRunning) {
                        beginJankInstrument(InteractionJankMonitor.CUJ_LOCKSCREEN_PASSWORD_APPEAR);
                    } else {
                        beginJankInstrument(
                                InteractionJankMonitor.CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR);
                    }
                    mSecurityViewFlipper.getBoundsOnScreen(mFinalBounds);
                    return bounds;
                }

                @Override
                public WindowInsets onProgress(WindowInsets windowInsets,
                        List<WindowInsetsAnimation> list) {
                    float start = mDisappearAnimRunning
                            ? -(mFinalBounds.bottom - mInitialBounds.bottom)
                            : mInitialBounds.bottom - mFinalBounds.bottom;
                    float end = mDisappearAnimRunning
                            ? -((mFinalBounds.bottom - mInitialBounds.bottom) * 0.75f)
                            : 0f;
                    int translationY = 0;
                    float interpolatedFraction = 1f;
                    for (WindowInsetsAnimation animation : list) {
                        if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) {
                            continue;
                        }
                        interpolatedFraction = animation.getInterpolatedFraction();

                        final int paddingBottom = (int) MathUtils.lerp(
                                start, end,
                                interpolatedFraction);
                        translationY += paddingBottom;
                    }
                    mSecurityViewFlipper.animateForIme(translationY, interpolatedFraction,
                            !mDisappearAnimRunning);

                    return windowInsets;
                }

                @Override
                public void onEnd(WindowInsetsAnimation animation) {
                    if (!mDisappearAnimRunning) {
                        endJankInstrument(InteractionJankMonitor.CUJ_LOCKSCREEN_PASSWORD_APPEAR);
                        mSecurityViewFlipper.animateForIme(0, /* interpolatedFraction */ 1f,
                                true /* appearingAnim */);
                    } else {
                        endJankInstrument(InteractionJankMonitor.CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR);
                    }
                }
            };

    // Used to notify the container when something interesting happens.
    public interface SecurityCallback {
        boolean dismiss(boolean authenticated, int targetUserId, boolean bypassSecondaryLockScreen);

        void userActivity();

        void onSecurityModeChanged(SecurityMode securityMode, boolean needsInput);

        /**
         * @param strongAuth   wheher the user has authenticated with strong authentication like
         *                     pattern, password or PIN but not by trust agents or fingerprint
         * @param targetUserId a user that needs to be the foreground user at the finish completion.
         */
        void finish(boolean strongAuth, int targetUserId);

        void reset();

        void onCancelClicked();
    }

    public interface SwipeListener {
        void onSwipeUp();
    }

    @VisibleForTesting
    public enum BouncerUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Default UiEvent used for variable initialization.")
        UNKNOWN(0),

        @UiEvent(doc = "Bouncer is dismissed using extended security access.")
        BOUNCER_DISMISS_EXTENDED_ACCESS(413),

        @UiEvent(doc = "Bouncer is dismissed using biometric.")
        BOUNCER_DISMISS_BIOMETRIC(414),

        @UiEvent(doc = "Bouncer is dismissed without security access.")
        BOUNCER_DISMISS_NONE_SECURITY(415),

        @UiEvent(doc = "Bouncer is dismissed using password security.")
        BOUNCER_DISMISS_PASSWORD(416),

        @UiEvent(doc = "Bouncer is dismissed using sim security access.")
        BOUNCER_DISMISS_SIM(417),

        @UiEvent(doc = "Bouncer is successfully unlocked using password.")
        BOUNCER_PASSWORD_SUCCESS(418),

        @UiEvent(doc = "An attempt to unlock bouncer using password has failed.")
        BOUNCER_PASSWORD_FAILURE(419);

        private final int mId;

        BouncerUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    public KeyguardSecurityContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardSecurityContainer(Context context) {
        this(context, null, 0);
    }

    public KeyguardSecurityContainer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mSpringAnimation = new SpringAnimation(this, DynamicAnimation.Y);
        mViewConfiguration = ViewConfiguration.get(context);
    }

    void onResume(SecurityMode securityMode, boolean faceAuthEnabled) {
        mSecurityMode = securityMode;
        mSecurityViewFlipper.setWindowInsetsAnimationCallback(mWindowInsetsAnimationCallback);
        updateBiometricRetry(securityMode, faceAuthEnabled);

        updateLayoutForSecurityMode(securityMode);
    }

    void updateLayoutForSecurityMode(SecurityMode securityMode) {
        mSecurityMode = securityMode;
        mOneHandedMode = canUseOneHandedBouncer();

        if (mOneHandedMode) {
            mIsSecurityViewLeftAligned = isOneHandedKeyguardLeftAligned(mContext);
        }

        updateSecurityViewGravity();
        updateSecurityViewLocation(false);
    }

    /** Return whether the one-handed keyguard should be enabled. */
    private boolean canUseOneHandedBouncer() {
        // Is it enabled?
        if (!getResources().getBoolean(
                com.android.internal.R.bool.config_enableOneHandedKeyguard)) {
            return false;
        }

        if (!KeyguardSecurityModel.isSecurityViewOneHanded(mSecurityMode)) {
            return false;
        }

        return getResources().getBoolean(R.bool.can_use_one_handed_bouncer);
    }

    /** Read whether the one-handed keyguard should be on the left/right from settings. */
    private boolean isOneHandedKeyguardLeftAligned(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ONE_HANDED_KEYGUARD_SIDE)
                    == Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT;
        } catch (Settings.SettingNotFoundException ex) {
            return true;
        }
    }

    private void updateSecurityViewGravity() {
        View securityView = findKeyguardSecurityView();

        if (securityView == null) {
            return;
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) securityView.getLayoutParams();

        if (mOneHandedMode) {
            lp.gravity = Gravity.LEFT | Gravity.BOTTOM;
        } else {
            lp.gravity = Gravity.CENTER_HORIZONTAL;
        }

        securityView.setLayoutParams(lp);
    }

    /**
     * Moves the inner security view to the correct location (in one handed mode) with animation.
     * This is triggered when the user taps on the side of the screen that is not currently occupied
     * by the security view .
     */
    private void updateSecurityViewLocation(boolean animate) {
        View securityView = findKeyguardSecurityView();

        if (securityView == null) {
            return;
        }

        if (!mOneHandedMode) {
            securityView.setTranslationX(0);
            return;
        }

        if (mRunningOneHandedAnimator != null) {
            mRunningOneHandedAnimator.cancel();
            mRunningOneHandedAnimator = null;
        }

        int targetTranslation = mIsSecurityViewLeftAligned ? 0 : (int) (getMeasuredWidth() / 2f);

        if (animate) {
            mRunningOneHandedAnimator = securityView.animate().translationX(targetTranslation);
            mRunningOneHandedAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
            mRunningOneHandedAnimator.setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mRunningOneHandedAnimator = null;
                }
            });

            mRunningOneHandedAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
            mRunningOneHandedAnimator.start();
        } else {
            securityView.setTranslationX(targetTranslation);
        }
    }

    @Nullable
    private KeyguardSecurityViewFlipper findKeyguardSecurityView() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);

            if (isKeyguardSecurityView(child)) {
                return (KeyguardSecurityViewFlipper) child;
            }
        }

        return null;
    }

    private boolean isKeyguardSecurityView(View view) {
        return view instanceof KeyguardSecurityViewFlipper;
    }

    public void onPause() {
        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
            mAlertDialog = null;
        }
        mSecurityViewFlipper.setWindowInsetsAnimationCallback(null);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        boolean result =  mMotionEventListeners.stream().anyMatch(
                listener -> listener.onInterceptTouchEvent(event))
                || super.onInterceptTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                int pointerIndex = event.getActionIndex();
                mStartTouchY = event.getY(pointerIndex);
                mActivePointerId = event.getPointerId(pointerIndex);
                mVelocityTracker.clear();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mIsDragging) {
                    return true;
                }
                if (!mSwipeUpToRetry) {
                    return false;
                }
                // Avoid dragging the pattern view
                if (mSecurityViewFlipper.getSecurityView().disallowInterceptTouch(event)) {
                    return false;
                }
                int index = event.findPointerIndex(mActivePointerId);
                float touchSlop = mViewConfiguration.getScaledTouchSlop() * SLOP_SCALE;
                if (index != -1 && mStartTouchY - event.getY(index) > touchSlop) {
                    mIsDragging = true;
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mIsDragging = false;
                break;
        }
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        boolean result =  mMotionEventListeners.stream()
                .anyMatch(listener -> listener.onTouchEvent(event))
                || super.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                int pointerIndex = event.findPointerIndex(mActivePointerId);
                float y = event.getY(pointerIndex);
                if (mLastTouchY != -1) {
                    float dy = y - mLastTouchY;
                    setTranslationY(getTranslationY() + dy * TOUCH_Y_MULTIPLIER);
                }
                mLastTouchY = y;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mActivePointerId = -1;
                mLastTouchY = -1;
                mIsDragging = false;
                startSpringAnimation(mVelocityTracker.getYVelocity());
                break;
            case MotionEvent.ACTION_POINTER_UP:
                int index = event.getActionIndex();
                int pointerId = event.getPointerId(index);
                if (pointerId == mActivePointerId) {
                    // This was our active pointer going up. Choose a new
                    // active pointer and adjust accordingly.
                    final int newPointerIndex = index == 0 ? 1 : 0;
                    mLastTouchY = event.getY(newPointerIndex);
                    mActivePointerId = event.getPointerId(newPointerIndex);
                }
                break;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (-getTranslationY() > TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    MIN_DRAG_SIZE, getResources().getDisplayMetrics())) {
                if (mSwipeListener != null) {
                    mSwipeListener.onSwipeUp();
                }
            } else {
                if (!mIsDragging) {
                    handleTap(event);
                }
            }
        }
        return true;
    }

    void addMotionEventListener(Gefingerpoken listener) {
        mMotionEventListeners.add(listener);
    }

    void removeMotionEventListener(Gefingerpoken listener) {
        mMotionEventListeners.remove(listener);
    }

    private void handleTap(MotionEvent event) {
        // If we're using a fullscreen security mode, skip
        if (!mOneHandedMode) {
            return;
        }

        // Did the tap hit the "other" side of the bouncer?
        if ((mIsSecurityViewLeftAligned && (event.getX() > getWidth() / 2f))
                || (!mIsSecurityViewLeftAligned && (event.getX() < getWidth() / 2f))) {
            mIsSecurityViewLeftAligned = !mIsSecurityViewLeftAligned;

            Settings.Global.putInt(
                    mContext.getContentResolver(),
                    Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                    mIsSecurityViewLeftAligned ? Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT
                            : Settings.Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT);

            updateSecurityViewLocation(true);
        }
    }

    void setSwipeListener(SwipeListener swipeListener) {
        mSwipeListener = swipeListener;
    }

    private void startSpringAnimation(float startVelocity) {
        mSpringAnimation
                .setStartVelocity(startVelocity)
                .animateToFinalPosition(0);
    }

    public void startDisappearAnimation(SecurityMode securitySelection) {
        mDisappearAnimRunning = true;
    }

    private void beginJankInstrument(int cuj) {
        KeyguardInputView securityView = mSecurityViewFlipper.getSecurityView();
        if (securityView == null) return;
        InteractionJankMonitor.getInstance().begin(securityView, cuj);
    }

    private void endJankInstrument(int cuj) {
        InteractionJankMonitor.getInstance().end(cuj);
    }

    private void cancelJankInstrument(int cuj) {
        InteractionJankMonitor.getInstance().cancel(cuj);
    }

    /**
     * Enables/disables swipe up to retry on the bouncer.
     */
    private void updateBiometricRetry(SecurityMode securityMode, boolean faceAuthEnabled) {
        mSwipeUpToRetry = faceAuthEnabled
                && securityMode != SecurityMode.SimPin
                && securityMode != SecurityMode.SimPuk
                && securityMode != SecurityMode.None;
    }

    public CharSequence getTitle() {
        return mSecurityViewFlipper.getTitle();
    }


    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mSecurityViewFlipper = findViewById(R.id.view_flipper);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {

        // Consume bottom insets because we're setting the padding locally (for IME and navbar.)
        int bottomInset = insets.getInsetsIgnoringVisibility(systemBars()).bottom;
        int imeInset = insets.getInsets(ime()).bottom;
        int inset = max(bottomInset, imeInset);
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), inset);
        return insets.inset(0, 0, 0, inset);
    }

    private void showDialog(String title, String message) {
        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
        }

        mAlertDialog = new AlertDialog.Builder(mContext)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setNeutralButton(R.string.ok, null)
                .create();
        if (!(mContext instanceof Activity)) {
            mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        mAlertDialog.show();
    }

    void showTimeoutDialog(int userId, int timeoutMs, LockPatternUtils lockPatternUtils,
            SecurityMode securityMode) {
        int timeoutInSeconds = timeoutMs / 1000;
        int messageId = 0;

        switch (securityMode) {
            case Pattern:
                messageId = R.string.kg_too_many_failed_pattern_attempts_dialog_message;
                break;
            case PIN:
                messageId = R.string.kg_too_many_failed_pin_attempts_dialog_message;
                break;
            case Password:
                messageId = R.string.kg_too_many_failed_password_attempts_dialog_message;
                break;
            // These don't have timeout dialogs.
            case Invalid:
            case None:
            case SimPin:
            case SimPuk:
                break;
        }

        if (messageId != 0) {
            final String message = mContext.getString(messageId,
                    lockPatternUtils.getCurrentFailedPasswordAttempts(userId),
                    timeoutInSeconds);
            showDialog(null, message);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxHeight = 0;
        int maxWidth = 0;
        int childState = 0;

        int halfWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec) / 2,
                MeasureSpec.getMode(widthMeasureSpec));

        for (int i = 0; i < getChildCount(); i++) {
            final View view = getChildAt(i);
            if (view.getVisibility() != GONE) {
                if (mOneHandedMode && isKeyguardSecurityView(view)) {
                    measureChildWithMargins(view, halfWidthMeasureSpec, 0,
                            heightMeasureSpec, 0);
                } else {
                    measureChildWithMargins(view, widthMeasureSpec, 0,
                            heightMeasureSpec, 0);
                }
                final LayoutParams lp = (LayoutParams) view.getLayoutParams();
                maxWidth = Math.max(maxWidth,
                        view.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
                maxHeight = Math.max(maxHeight,
                        view.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
                childState = combineMeasuredStates(childState, view.getMeasuredState());
            }
        }

        maxWidth += getPaddingLeft() + getPaddingRight();
        maxHeight += getPaddingTop() + getPaddingBottom();

        // Check against our minimum height and width
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // After a layout pass, we need to re-place the inner bouncer, as our bounds may have
        // changed.
        updateSecurityViewLocation(/* animate= */false);
    }

    void showAlmostAtWipeDialog(int attempts, int remaining, int userType) {
        String message = null;
        switch (userType) {
            case USER_TYPE_PRIMARY:
                message = mContext.getString(R.string.kg_failed_attempts_almost_at_wipe,
                        attempts, remaining);
                break;
            case USER_TYPE_SECONDARY_USER:
                message = mContext.getString(R.string.kg_failed_attempts_almost_at_erase_user,
                        attempts, remaining);
                break;
            case USER_TYPE_WORK_PROFILE:
                message = mContext.getString(R.string.kg_failed_attempts_almost_at_erase_profile,
                        attempts, remaining);
                break;
        }
        showDialog(null, message);
    }

    void showWipeDialog(int attempts, int userType) {
        String message = null;
        switch (userType) {
            case USER_TYPE_PRIMARY:
                message = mContext.getString(R.string.kg_failed_attempts_now_wiping,
                        attempts);
                break;
            case USER_TYPE_SECONDARY_USER:
                message = mContext.getString(R.string.kg_failed_attempts_now_erasing_user,
                        attempts);
                break;
            case USER_TYPE_WORK_PROFILE:
                message = mContext.getString(R.string.kg_failed_attempts_now_erasing_profile,
                        attempts);
                break;
        }
        showDialog(null, message);
    }

    public void reset() {
        mDisappearAnimRunning = false;
    }
}

