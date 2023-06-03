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

import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_DIALOG_FAILED_ATTEMPTS_ALMOST_ERASING_PROFILE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_DIALOG_FAILED_ATTEMPTS_ERASING_PROFILE;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import static androidx.constraintlayout.widget.ConstraintSet.BOTTOM;
import static androidx.constraintlayout.widget.ConstraintSet.CHAIN_SPREAD;
import static androidx.constraintlayout.widget.ConstraintSet.END;
import static androidx.constraintlayout.widget.ConstraintSet.LEFT;
import static androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT;
import static androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;
import static androidx.constraintlayout.widget.ConstraintSet.RIGHT;
import static androidx.constraintlayout.widget.ConstraintSet.START;
import static androidx.constraintlayout.widget.ConstraintSet.TOP;
import static androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT;

import static com.android.systemui.animation.InterpolatorsAndroidX.DECELERATE_QUINT;
import static com.android.systemui.plugins.FalsingManager.LOW_PENALTY;

import static java.lang.Integer.max;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.UserManager;
import android.provider.Settings;
import android.transition.TransitionManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.UserIcons;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.settingslib.Utils;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.classifier.FalsingA11yDelegate;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.policy.BaseUserSwitcherAdapter;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.user.data.source.UserRecord;
import com.android.systemui.util.settings.GlobalSettings;

import java.util.ArrayList;
import java.util.List;

/** Determines how the bouncer is displayed to the user. */
public class KeyguardSecurityContainer extends ConstraintLayout {
    static final int USER_TYPE_PRIMARY = 1;
    static final int USER_TYPE_WORK_PROFILE = 2;
    static final int USER_TYPE_SECONDARY_USER = 3;

    @IntDef({MODE_UNINITIALIZED, MODE_DEFAULT, MODE_ONE_HANDED, MODE_USER_SWITCHER})
    public @interface Mode {}
    static final int MODE_UNINITIALIZED = -1;
    static final int MODE_DEFAULT = 0;
    static final int MODE_ONE_HANDED = 1;
    static final int MODE_USER_SWITCHER = 2;

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

    private static final String TAG = "KeyguardSecurityView";

    // Make the view move slower than the finger, as if the spring were applying force.
    private static final float TOUCH_Y_MULTIPLIER = 0.25f;
    // How much you need to drag the bouncer to trigger an auth retry (in dps.)
    private static final float MIN_DRAG_SIZE = 10;
    // How much to scale the default slop by, to avoid accidental drags.
    private static final float SLOP_SCALE = 4f;
    @VisibleForTesting
    // How much the view scales down to during back gestures.
    static final float MIN_BACK_SCALE = 0.9f;
    @VisibleForTesting
    KeyguardSecurityViewFlipper mSecurityViewFlipper;
    private GlobalSettings mGlobalSettings;
    private FalsingManager mFalsingManager;
    private UserSwitcherController mUserSwitcherController;
    private FalsingA11yDelegate mFalsingA11yDelegate;
    private AlertDialog mAlertDialog;
    private boolean mSwipeUpToRetry;

    private final ViewConfiguration mViewConfiguration;
    private final SpringAnimation mSpringAnimation;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private final List<Gefingerpoken> mMotionEventListeners = new ArrayList<>();
    private final GestureDetector mDoubleTapDetector;

    private float mLastTouchY = -1;
    private int mActivePointerId = -1;
    private boolean mIsDragging;
    private float mStartTouchY = -1;
    private boolean mDisappearAnimRunning;
    private SwipeListener mSwipeListener;
    private ViewMode mViewMode = new DefaultViewMode();
    private boolean mIsInteractable;
    protected ViewMediatorCallback mViewMediatorCallback;
    /*
     * Using MODE_UNINITIALIZED to mean the view mode is set to DefaultViewMode, but init() has not
     * yet been called on it. This will happen when the ViewController is initialized.
     */
    private @Mode int mCurrentMode = MODE_UNINITIALIZED;
    private int mWidth = -1;

    /**
     * This callback is used to animate KeyguardSecurityContainer and its child views based on
     * the interaction with the ime. After
     * {@link WindowInsetsAnimation.Callback#onPrepare(WindowInsetsAnimation)},
     * {@link #onApplyWindowInsets} is called where we
     * set the bottom padding to be the height of the keyboard. We use this padding to determine
     * the delta of vertical distance for y-translation animations.
     * Note that bottom padding is not set when the disappear animation is started because
     * we are deferring the y translation logic to the animator in
     * {@link KeyguardPasswordView#startDisappearAnimation(Runnable)}
     */
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

                    float alpha = mDisappearAnimRunning
                            ? 1 - interpolatedFraction
                            : Math.max(interpolatedFraction, getAlpha());
                    updateChildren(translationY, alpha);

                    return windowInsets;
                }

                @Override
                public void onEnd(WindowInsetsAnimation animation) {
                    if (!mDisappearAnimRunning) {
                        endJankInstrument(InteractionJankMonitor.CUJ_LOCKSCREEN_PASSWORD_APPEAR);
                    } else {
                        endJankInstrument(InteractionJankMonitor.CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR);
                        setAlpha(0f);
                    }
                    updateChildren(0 /* translationY */, 1f /* alpha */);
                }
            };

    private final OnBackAnimationCallback mBackCallback = new OnBackAnimationCallback() {
        @Override
        public void onBackCancelled() {
            // TODO(b/259608500): Remove once back API auto animates progress to 0 on cancel.
            resetScale();
        }

        @Override
        public void onBackInvoked() { }

        @Override
        public void onBackProgressed(BackEvent event) {
            float progress = event.getProgress();
            // TODO(b/263819310): Update the interpolator to match spec.
            float scale = MIN_BACK_SCALE
                    +  (1 - MIN_BACK_SCALE) * (1 - DECELERATE_QUINT.getInterpolation(progress));
            setScale(scale);
        }
    };
    /**
     * @return the {@link OnBackAnimationCallback} to animate this view during a back gesture.
     */
    @NonNull
    OnBackAnimationCallback getBackCallback() {
        return mBackCallback;
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
        mSpringAnimation = new SpringAnimation(this, DynamicAnimation.TRANSLATION_Y);
        mViewConfiguration = ViewConfiguration.get(context);
        mDoubleTapDetector = new GestureDetector(context, new DoubleTapListener());
    }

    void onResume(SecurityMode securityMode, boolean faceAuthEnabled) {
        mSecurityViewFlipper.setWindowInsetsAnimationCallback(mWindowInsetsAnimationCallback);
        updateBiometricRetry(securityMode, faceAuthEnabled);
    }

    void initMode(@Mode int mode, GlobalSettings globalSettings, FalsingManager falsingManager,
            UserSwitcherController userSwitcherController,
            UserSwitcherViewMode.UserSwitcherCallback userSwitcherCallback,
            FalsingA11yDelegate falsingA11yDelegate) {
        if (mCurrentMode == mode) return;
        Log.i(TAG, "Switching mode from " + modeToString(mCurrentMode) + " to "
                + modeToString(mode));
        mCurrentMode = mode;
        mViewMode.onDestroy();

        switch (mode) {
            case MODE_ONE_HANDED:
                mViewMode = new OneHandedViewMode();
                break;
            case MODE_USER_SWITCHER:
                mViewMode = new UserSwitcherViewMode(userSwitcherCallback);
                break;
            default:
                mViewMode = new DefaultViewMode();
        }
        mGlobalSettings = globalSettings;
        mFalsingManager = falsingManager;
        mFalsingA11yDelegate = falsingA11yDelegate;
        mUserSwitcherController = userSwitcherController;
        setupViewMode();
    }

    private String modeToString(@Mode int mode) {
        switch (mode) {
            case MODE_UNINITIALIZED:
                return "Uninitialized";
            case MODE_DEFAULT:
                return "Default";
            case MODE_ONE_HANDED:
                return "OneHanded";
            case MODE_USER_SWITCHER:
                return "UserSwitcher";
            default:
                throw new IllegalArgumentException("mode: " + mode + " not supported");
        }
    }

    private void setupViewMode() {
        if (mSecurityViewFlipper == null || mGlobalSettings == null
                || mFalsingManager == null || mUserSwitcherController == null) {
            return;
        }

        mViewMode.init(this, mGlobalSettings, mSecurityViewFlipper, mFalsingManager,
                mUserSwitcherController, mFalsingA11yDelegate);
    }

    @Mode int getMode() {
        return mCurrentMode;
    }

    /**
     * The position of the container can be adjusted based upon a touch at location x. This has
     * been used in one-handed mode to make sure the bouncer appears on the side of the display
     * that the user last interacted with.
     */
    void updatePositionByTouchX(float x) {
        mViewMode.updatePositionByTouchX(x);
    }

    public boolean isSidedSecurityMode() {
        return mViewMode instanceof SidedSecurityMode;
    }

    /** Returns whether the inner SecurityViewFlipper is left-aligned when in sided mode. */
    public boolean isSecurityLeftAligned() {
        return mViewMode instanceof SidedSecurityMode
                && ((SidedSecurityMode) mViewMode).isLeftAligned();
    }

    /**
     * Returns whether the touch happened on the other side of security (like bouncer) when in
     * sided mode.
     */
    public boolean isTouchOnTheOtherSideOfSecurity(MotionEvent ev) {
        return mViewMode instanceof SidedSecurityMode
                && ((SidedSecurityMode) mViewMode).isTouchOnTheOtherSideOfSecurity(ev);
    }

    public void onPause() {
        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
            mAlertDialog = null;
        }
        mSecurityViewFlipper.setWindowInsetsAnimationCallback(null);
        mViewMode.reset();
    }

    /** Set true if the view can be interacted with */
    public void setInteractable(boolean isInteractable) {
        mIsInteractable = isInteractable;
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!mIsInteractable) {
            return true;
        }

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

        // double tap detector should be called after listeners handle touches as listeners are
        // helping with ignoring falsing. Otherwise falsing will be activated for some double taps
        mDoubleTapDetector.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                int pointerIndex = event.findPointerIndex(mActivePointerId);
                if (pointerIndex != -1) {
                    float y = event.getY(pointerIndex);
                    if (mLastTouchY != -1) {
                        float dy = y - mLastTouchY;
                        setTranslationY(getTranslationY() + dy * TOUCH_Y_MULTIPLIER);
                    }
                    mLastTouchY = y;
                }
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
            }
        }
        return true;
    }

    private class DoubleTapListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return handleDoubleTap(e);
        }
    }

    @VisibleForTesting boolean handleDoubleTap(MotionEvent e) {
        if (!mIsDragging) {
            mViewMode.handleDoubleTap(e);
            return true;
        }
        return false;
    }

    void addMotionEventListener(Gefingerpoken listener) {
        mMotionEventListeners.add(listener);
    }

    void removeMotionEventListener(Gefingerpoken listener) {
        mMotionEventListeners.remove(listener);
    }

    void setSwipeListener(SwipeListener swipeListener) {
        mSwipeListener = swipeListener;
    }

    private void startSpringAnimation(float startVelocity) {
        mSpringAnimation
                .setStartVelocity(startVelocity)
                .animateToFinalPosition(0);
    }

    /**
     * Runs after a successful authentication only
     */
    public void startDisappearAnimation(SecurityMode securitySelection) {
        mDisappearAnimRunning = true;
        if (securitySelection == SecurityMode.Password
                && mSecurityViewFlipper.getSecurityView() instanceof KeyguardPasswordView) {
            ((KeyguardPasswordView) mSecurityViewFlipper.getSecurityView())
                    .setDisappearAnimationListener(this::setTranslationY);
        } else {
            mViewMode.startDisappearAnimation(securitySelection);
        }
    }

    /**
     * This will run when the bouncer shows in all cases except when the user drags the bouncer up.
     */
    public void startAppearAnimation(SecurityMode securityMode) {
        setTranslationY(0f);
        setAlpha(1f);
        updateChildren(0 /* translationY */, 1f /* alpha */);
        mViewMode.startAppearAnimation(securityMode);
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
        int paddingBottom = max(inset, getContext().getResources()
                .getDimensionPixelSize(R.dimen.keyguard_security_view_bottom_margin));
        // If security mode is password, we rely on the animation value of defined in
        // KeyguardPasswordView to determine the y translation animation.
        // This means that we will prevent the WindowInsetsAnimationCallback from setting any y
        // translation values by preventing the setting of the padding here.
        if (!mDisappearAnimRunning) {
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), paddingBottom);
        }
        return insets.inset(0, 0, 0, inset);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.keyguardDoneDrawing();
        }
    }

    public void setViewMediatorCallback(ViewMediatorCallback viewMediatorCallback) {
        mViewMediatorCallback = viewMediatorCallback;
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
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int width = right - left;
        if (changed && mWidth != width) {
            mWidth = width;
            mViewMode.updateSecurityViewLocation();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        mViewMode.updateSecurityViewLocation();
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
                message = mContext.getSystemService(DevicePolicyManager.class).getResources()
                        .getString(KEYGUARD_DIALOG_FAILED_ATTEMPTS_ALMOST_ERASING_PROFILE,
                                () -> mContext.getString(
                                        R.string.kg_failed_attempts_almost_at_erase_profile,
                                        attempts, remaining),
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
                message = mContext.getSystemService(DevicePolicyManager.class).getResources()
                        .getString(KEYGUARD_DIALOG_FAILED_ATTEMPTS_ERASING_PROFILE,
                                () -> mContext.getString(
                                        R.string.kg_failed_attempts_now_erasing_profile, attempts),
                        attempts);
                break;
        }
        showDialog(null, message);
    }

    public void reset() {
        mViewMode.reset();
        mDisappearAnimRunning = false;
    }

    void reloadColors() {
        mViewMode.reloadColors();
    }

    /** Handles density or font scale changes. */
    void onDensityOrFontScaleChanged() {
        mViewMode.onDensityOrFontScaleChanged();
    }

    void resetScale() {
        setScale(1);
    }

    private void setScale(float scale) {
        setScaleX(scale);
        setScaleY(scale);
    }

    private void updateChildren(int translationY, float alpha) {
        for (int i = 0; i < getChildCount(); ++i) {
            View child = getChildAt(i);
            child.setTranslationY(translationY);
            child.setAlpha(alpha);
        }
    }

    /**
     * Enscapsulates the differences between bouncer modes for the container.
     */
    interface ViewMode {
        default void init(@NonNull ConstraintLayout v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController,
                @NonNull FalsingA11yDelegate falsingA11yDelegate) {};

        /** Reinitialize the location */
        default void updateSecurityViewLocation() {};

        /** Alter the ViewFlipper position, based upon a touch outside of it */
        default void updatePositionByTouchX(float x) {};

        /** A double tap on the container, outside of the ViewFlipper */
        default void handleDoubleTap(MotionEvent event) {};

        /** Called when the view needs to reset or hides */
        default void reset() {};

        /** Refresh colors */
        default void reloadColors() {};

        /** Handles density or font scale changes. */
        default void onDensityOrFontScaleChanged() {}

        /** On a successful auth, optionally handle how the view disappears */
        default void startDisappearAnimation(SecurityMode securityMode) {};

        /** On notif tap, this animation will run */
        default void startAppearAnimation(SecurityMode securityMode) {};

        /** Called when we are setting a new ViewMode */
        default void onDestroy() {};
    }

    /**
     * Base class for modes which support having on left/right side of the screen, used for large
     * screen devices
     */
    abstract static class SidedSecurityMode implements ViewMode {
        private KeyguardSecurityViewFlipper mViewFlipper;
        private ConstraintLayout mView;
        private GlobalSettings mGlobalSettings;
        private int mDefaultSideSetting;

        public void init(ConstraintLayout v, KeyguardSecurityViewFlipper viewFlipper,
                GlobalSettings globalSettings, boolean leftAlignedByDefault) {
            mView = v;
            mViewFlipper = viewFlipper;
            mGlobalSettings = globalSettings;
            mDefaultSideSetting =
                    leftAlignedByDefault ? Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT
                            : Settings.Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT;
        }

        /**
         * Determine if a double tap on this view is on the other side. If so, will animate
         * positions and record the preference to always show on this side.
         */
        @Override
        public void handleDoubleTap(MotionEvent event) {
            boolean currentlyLeftAligned = isLeftAligned();
            // Did the tap hit the "other" side of the bouncer?
            if (isTouchOnTheOtherSideOfSecurity(event, currentlyLeftAligned)) {
                boolean willBeLeftAligned = !currentlyLeftAligned;
                updateSideSetting(willBeLeftAligned);

                int keyguardState = willBeLeftAligned
                        ? SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SWITCH_LEFT
                        : SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SWITCH_RIGHT;
                SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED, keyguardState);

                updateSecurityViewLocation(willBeLeftAligned, /* animate= */ true);
            }
        }

        private boolean isTouchOnTheOtherSideOfSecurity(MotionEvent ev, boolean leftAligned) {
            float x = ev.getX();
            return (leftAligned && (x > mView.getWidth() / 2f))
                    || (!leftAligned && (x < mView.getWidth() / 2f));
        }

        public boolean isTouchOnTheOtherSideOfSecurity(MotionEvent ev) {
            return isTouchOnTheOtherSideOfSecurity(ev, isLeftAligned());
        }

        protected abstract void updateSecurityViewLocation(boolean leftAlign, boolean animate);

        boolean isLeftAligned() {
            return mGlobalSettings.getInt(Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                    mDefaultSideSetting)
                    == Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT;
        }

        protected void updateSideSetting(boolean leftAligned) {
            mGlobalSettings.putInt(
                    Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                    leftAligned ? Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT
                            : Settings.Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT);
        }
    }

    /**
     * Default bouncer is centered within the space
     */
    static class DefaultViewMode implements ViewMode {
        private ConstraintLayout mView;
        private KeyguardSecurityViewFlipper mViewFlipper;

        @Override
        public void init(@NonNull ConstraintLayout v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController,
                @NonNull FalsingA11yDelegate falsingA11yDelegate) {
            mView = v;
            mViewFlipper = viewFlipper;

            // Reset ViewGroup to default positions
            updateSecurityViewGroup();
        }

        private void updateSecurityViewGroup() {
            ConstraintSet constraintSet = new ConstraintSet();
            constraintSet.connect(mViewFlipper.getId(), START, PARENT_ID, START);
            constraintSet.connect(mViewFlipper.getId(), END, PARENT_ID, END);
            constraintSet.connect(mViewFlipper.getId(), BOTTOM, PARENT_ID, BOTTOM);
            constraintSet.connect(mViewFlipper.getId(), TOP, PARENT_ID, TOP);
            constraintSet.constrainHeight(mViewFlipper.getId(), MATCH_CONSTRAINT);
            constraintSet.constrainWidth(mViewFlipper.getId(), MATCH_CONSTRAINT);
            constraintSet.applyTo(mView);
        }
    }

    /**
     * User switcher mode will display both the current user icon as well as
     * a user switcher, in both portrait and landscape modes.
     */
    static class UserSwitcherViewMode extends SidedSecurityMode {
        private ConstraintLayout mView;
        private ViewGroup mUserSwitcherViewGroup;
        private KeyguardSecurityViewFlipper mViewFlipper;
        private TextView mUserSwitcher;
        private FalsingManager mFalsingManager;
        private UserSwitcherController mUserSwitcherController;
        private KeyguardUserSwitcherPopupMenu mPopup;
        private Resources mResources;
        private UserSwitcherController.UserSwitchCallback mUserSwitchCallback =
                this::setupUserSwitcher;

        private UserSwitcherCallback mUserSwitcherCallback;
        private FalsingA11yDelegate mFalsingA11yDelegate;

        UserSwitcherViewMode(UserSwitcherCallback userSwitcherCallback) {
            mUserSwitcherCallback = userSwitcherCallback;
        }

        @Override
        public void init(@NonNull ConstraintLayout v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController,
                @NonNull FalsingA11yDelegate falsingA11yDelegate) {
            init(v, viewFlipper, globalSettings, /* leftAlignedByDefault= */false);
            mView = v;
            mViewFlipper = viewFlipper;
            mFalsingManager = falsingManager;
            mUserSwitcherController = userSwitcherController;
            mResources = v.getContext().getResources();
            mFalsingA11yDelegate = falsingA11yDelegate;

            if (mUserSwitcherViewGroup == null) {
                inflateUserSwitcher();
            }
            updateSecurityViewLocation();
            setupUserSwitcher();
            mUserSwitcherController.addUserSwitchCallback(mUserSwitchCallback);
        }

        @Override
        public void reset() {
            if (mPopup != null) {
                mPopup.dismiss();
                mPopup = null;
            }
            setupUserSwitcher();
        }

        @Override
        public void reloadColors() {
            TextView header =  (TextView) mView.findViewById(R.id.user_switcher_header);
            if (header != null) {
                header.setTextColor(Utils.getColorAttrDefaultColor(mView.getContext(),
                        android.R.attr.textColorPrimary));
                header.setBackground(mView.getContext().getDrawable(
                        R.drawable.bouncer_user_switcher_header_bg));
                Drawable keyDownDrawable =
                        ((LayerDrawable) header.getBackground().mutate()).findDrawableByLayerId(
                                R.id.user_switcher_key_down);
                keyDownDrawable.setTintList(Utils.getColorAttr(mView.getContext(),
                        android.R.attr.textColorPrimary));
            }
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            mView.removeView(mUserSwitcherViewGroup);
            inflateUserSwitcher();
        }

        @Override
        public void onDestroy() {
            mUserSwitcherController.removeUserSwitchCallback(mUserSwitchCallback);
        }

        private Drawable findUserIcon(int userId) {
            Bitmap userIcon = UserManager.get(mView.getContext()).getUserIcon(userId);
            if (userIcon != null) {
                return CircleFramedDrawable.getInstance(mView.getContext(),
                        userIcon);
            }

            return UserIcons.getDefaultUserIcon(mResources, userId, false);
        }

        @Override
        public void startAppearAnimation(SecurityMode securityMode) {
            // IME insets animations handle alpha and translation
            if (securityMode == SecurityMode.Password) {
                return;
            }

            mUserSwitcherViewGroup.setAlpha(0f);
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            int yTrans = mView.getResources().getDimensionPixelSize(R.dimen.pin_view_trans_y_entry);
            animator.setInterpolator(Interpolators.STANDARD_DECELERATE);
            animator.setDuration(650);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mUserSwitcherViewGroup.setAlpha(1f);
                    mUserSwitcherViewGroup.setTranslationY(0f);
                }
            });
            animator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                mUserSwitcherViewGroup.setAlpha(value);
                mUserSwitcherViewGroup.setTranslationY(yTrans - yTrans * value);
            });
            animator.start();
        }

        @Override
        public void startDisappearAnimation(SecurityMode securityMode) {
            // IME insets animations handle alpha and translation
            if (securityMode == SecurityMode.Password) {
                return;
            }

            int yTranslation = mResources.getDimensionPixelSize(R.dimen.disappear_y_translation);

            AnimatorSet anims = new AnimatorSet();
            ObjectAnimator yAnim = ObjectAnimator.ofFloat(mView, View.TRANSLATION_Y, yTranslation);
            ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(mUserSwitcherViewGroup, View.ALPHA,
                    0f);

            anims.setInterpolator(Interpolators.STANDARD_ACCELERATE);
            anims.playTogether(alphaAnim, yAnim);
            anims.start();
        }

        private void setupUserSwitcher() {
            final UserRecord currentUser = mUserSwitcherController.getCurrentUserRecord();
            if (currentUser == null) {
                Log.e(TAG, "Current user in user switcher is null.");
                return;
            }
            final String currentUserName = mUserSwitcherController.getCurrentUserName();
            Drawable userIcon = findUserIcon(currentUser.info.id);
            ((ImageView) mView.findViewById(R.id.user_icon)).setImageDrawable(userIcon);
            mUserSwitcher.setText(currentUserName);

            KeyguardUserSwitcherAnchor anchor = mView.findViewById(R.id.user_switcher_anchor);
            anchor.setAccessibilityDelegate(mFalsingA11yDelegate);

            BaseUserSwitcherAdapter adapter = new BaseUserSwitcherAdapter(mUserSwitcherController) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    UserRecord item = getItem(position);
                    FrameLayout view = (FrameLayout) convertView;
                    if (view == null) {
                        view = (FrameLayout) LayoutInflater.from(parent.getContext()).inflate(
                                R.layout.keyguard_bouncer_user_switcher_item,
                                parent,
                                false);
                    }
                    TextView textView = (TextView) view.getChildAt(0);
                    textView.setText(getName(parent.getContext(), item));
                    Drawable icon = null;
                    if (item.picture != null) {
                        icon = new BitmapDrawable(item.picture);
                    } else {
                        icon = getDrawable(item, view.getContext());
                    }
                    int iconSize = view.getResources().getDimensionPixelSize(
                            R.dimen.bouncer_user_switcher_item_icon_size);
                    int iconPadding = view.getResources().getDimensionPixelSize(
                            R.dimen.bouncer_user_switcher_item_icon_padding);
                    icon.setBounds(0, 0, iconSize, iconSize);
                    textView.setCompoundDrawablePadding(iconPadding);
                    textView.setCompoundDrawablesRelative(icon, null, null, null);

                    if (item == currentUser) {
                        textView.setBackground(view.getContext().getDrawable(
                                R.drawable.bouncer_user_switcher_item_selected_bg));
                    } else {
                        textView.setBackground(null);
                    }
                    textView.setSelected(item == currentUser);
                    view.setEnabled(item.isSwitchToEnabled);
                    UserSwitcherController.setSelectableAlpha(view);
                    return view;
                }

                private Drawable getDrawable(UserRecord item, Context context) {
                    Drawable drawable;
                    if (item.isCurrent && item.isGuest) {
                        drawable = context.getDrawable(R.drawable.ic_avatar_guest_user);
                    } else {
                        drawable = getIconDrawable(context, item);
                    }

                    int iconColor;
                    if (item.isSwitchToEnabled) {
                        iconColor = Utils.getColorAttrDefaultColor(context,
                                com.android.internal.R.attr.colorAccentPrimaryVariant);
                    } else {
                        iconColor = context.getResources().getColor(
                                R.color.kg_user_switcher_restricted_avatar_icon_color,
                                context.getTheme());
                    }
                    drawable.setTint(iconColor);

                    Drawable bg = context.getDrawable(R.drawable.user_avatar_bg);
                    bg.setTintBlendMode(BlendMode.DST);
                    bg.setTint(Utils.getColorAttrDefaultColor(context,
                                com.android.internal.R.attr.colorSurfaceVariant));
                    drawable = new LayerDrawable(new Drawable[]{bg, drawable});
                    return drawable;
                }
            };

            anchor.setOnClickListener((v) -> {
                if (mFalsingManager.isFalseTap(LOW_PENALTY)) return;
                mPopup = new KeyguardUserSwitcherPopupMenu(mView.getContext(), mFalsingManager);
                mPopup.setAnchorView(anchor);
                mPopup.setAdapter(adapter);
                mPopup.setOnItemClickListener((parent, view, pos, id) -> {
                    if (mFalsingManager.isFalseTap(LOW_PENALTY)) return;
                    if (!view.isEnabled()) return;
                    // Subtract one for the header
                    UserRecord user = adapter.getItem(pos - 1);
                    if (user.isManageUsers || user.isAddSupervisedUser) {
                        mUserSwitcherCallback.showUnlockToContinueMessage();
                    }
                    if (!user.isCurrent) {
                        adapter.onUserListItemClicked(user);
                    }
                    mPopup.dismiss();
                    mPopup = null;
                });
                mPopup.show();
            });
        }

        @Override
        public void updateSecurityViewLocation() {
            updateSecurityViewLocation(isLeftAligned(), /* animate= */false);
        }

        public void updateSecurityViewLocation(boolean leftAlign, boolean animate) {
            if (animate) {
                TransitionManager.beginDelayedTransition(mView,
                        new KeyguardSecurityViewTransition());
            }
            int yTrans = mResources.getDimensionPixelSize(R.dimen.bouncer_user_switcher_y_trans);
            int viewFlipperBottomMargin = mResources.getDimensionPixelSize(
                    R.dimen.bouncer_user_switcher_view_mode_view_flipper_bottom_margin);
            int userSwitcherBottomMargin = mResources.getDimensionPixelSize(
                    R.dimen.bouncer_user_switcher_view_mode_user_switcher_bottom_margin);
            if (mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                ConstraintSet constraintSet = new ConstraintSet();
                constraintSet.connect(mUserSwitcherViewGroup.getId(), TOP, PARENT_ID, TOP, yTrans);
                constraintSet.connect(mUserSwitcherViewGroup.getId(), BOTTOM, mViewFlipper.getId(),
                        TOP, userSwitcherBottomMargin);
                constraintSet.connect(mViewFlipper.getId(), TOP, mUserSwitcherViewGroup.getId(),
                        BOTTOM);
                constraintSet.connect(mViewFlipper.getId(), BOTTOM, PARENT_ID, BOTTOM,
                        viewFlipperBottomMargin);
                constraintSet.centerHorizontally(mViewFlipper.getId(), PARENT_ID);
                constraintSet.centerHorizontally(mUserSwitcherViewGroup.getId(), PARENT_ID);
                constraintSet.setVerticalChainStyle(mViewFlipper.getId(), CHAIN_SPREAD);
                constraintSet.setVerticalChainStyle(mUserSwitcherViewGroup.getId(), CHAIN_SPREAD);
                constraintSet.constrainHeight(mUserSwitcherViewGroup.getId(), WRAP_CONTENT);
                constraintSet.constrainWidth(mUserSwitcherViewGroup.getId(), WRAP_CONTENT);
                constraintSet.constrainHeight(mViewFlipper.getId(), MATCH_CONSTRAINT);
                constraintSet.applyTo(mView);
            } else {
                int leftElement = leftAlign ? mViewFlipper.getId() : mUserSwitcherViewGroup.getId();
                int rightElement =
                        leftAlign ? mUserSwitcherViewGroup.getId() : mViewFlipper.getId();

                ConstraintSet constraintSet = new ConstraintSet();
                constraintSet.connect(leftElement, LEFT, PARENT_ID, LEFT);
                constraintSet.connect(leftElement, RIGHT, rightElement, LEFT);
                constraintSet.connect(rightElement, LEFT, leftElement, RIGHT);
                constraintSet.connect(rightElement, RIGHT, PARENT_ID, RIGHT);
                constraintSet.connect(mUserSwitcherViewGroup.getId(), TOP, PARENT_ID, TOP);
                constraintSet.connect(mUserSwitcherViewGroup.getId(), BOTTOM, PARENT_ID, BOTTOM);
                constraintSet.connect(mViewFlipper.getId(), TOP, PARENT_ID, TOP);
                constraintSet.connect(mViewFlipper.getId(), BOTTOM, PARENT_ID, BOTTOM);
                constraintSet.setHorizontalChainStyle(mUserSwitcherViewGroup.getId(), CHAIN_SPREAD);
                constraintSet.setHorizontalChainStyle(mViewFlipper.getId(), CHAIN_SPREAD);
                constraintSet.constrainHeight(mUserSwitcherViewGroup.getId(),
                        MATCH_CONSTRAINT);
                constraintSet.constrainWidth(mUserSwitcherViewGroup.getId(),
                        MATCH_CONSTRAINT);
                constraintSet.constrainWidth(mViewFlipper.getId(), MATCH_CONSTRAINT);
                constraintSet.constrainHeight(mViewFlipper.getId(), MATCH_CONSTRAINT);
                constraintSet.applyTo(mView);
            }
        }

        private void inflateUserSwitcher() {
            LayoutInflater.from(mView.getContext()).inflate(
                    R.layout.keyguard_bouncer_user_switcher,
                    mView,
                    true);
            mUserSwitcherViewGroup = mView.findViewById(R.id.keyguard_bouncer_user_switcher);
            mUserSwitcher = mView.findViewById(R.id.user_switcher_header);
        }

        interface UserSwitcherCallback {
            void showUnlockToContinueMessage();
        }
    }

    /**
     * Logic to enabled one-handed bouncer mode. Supports animating the bouncer
     * between alternate sides of the display.
     */
    static class OneHandedViewMode extends SidedSecurityMode {
        private ConstraintLayout mView;
        private KeyguardSecurityViewFlipper mViewFlipper;

        @Override
        public void init(@NonNull ConstraintLayout v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController,
                @NonNull FalsingA11yDelegate falsingA11yDelegate) {
            init(v, viewFlipper, globalSettings, /* leftAlignedByDefault= */true);
            mView = v;
            mViewFlipper = viewFlipper;

            updateSecurityViewLocation(isLeftAligned(), /* animate= */false);
        }

        /**
         * Moves the bouncer to align with a tap (most likely in the shade), so the bouncer
         * appears on the same side as a touch.
         */
        @Override
        public void updatePositionByTouchX(float x) {
            boolean isTouchOnLeft = x <= mView.getWidth() / 2f;
            updateSideSetting(isTouchOnLeft);
            updateSecurityViewLocation(isTouchOnLeft, /* animate= */false);
        }

        @Override
        public void updateSecurityViewLocation() {
            updateSecurityViewLocation(isLeftAligned(), /* animate= */false);
        }

        protected void updateSecurityViewLocation(boolean leftAlign, boolean animate) {
            if (animate) {
                TransitionManager.beginDelayedTransition(mView,
                        new KeyguardSecurityViewTransition());
            }
            ConstraintSet constraintSet = new ConstraintSet();
            if (leftAlign) {
                constraintSet.connect(mViewFlipper.getId(), LEFT, PARENT_ID, LEFT);
            } else {
                constraintSet.connect(mViewFlipper.getId(), RIGHT, PARENT_ID, RIGHT);
            }
            constraintSet.connect(mViewFlipper.getId(), TOP, PARENT_ID, TOP);
            constraintSet.connect(mViewFlipper.getId(), BOTTOM, PARENT_ID, BOTTOM);
            constraintSet.constrainPercentWidth(mViewFlipper.getId(), 0.5f);
            constraintSet.applyTo(mView);
        }
    }
}
