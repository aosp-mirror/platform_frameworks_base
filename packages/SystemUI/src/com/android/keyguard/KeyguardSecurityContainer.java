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

import static com.android.systemui.plugins.FalsingManager.LOW_PENALTY;
import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_DISABLED_ALPHA;
import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_ENABLED_ALPHA;

import static java.lang.Integer.max;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.UserIcons;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.settingslib.Utils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.UserSwitcherController.BaseUserAdapter;
import com.android.systemui.statusbar.policy.UserSwitcherController.UserRecord;
import com.android.systemui.util.settings.GlobalSettings;

import java.util.ArrayList;
import java.util.List;

public class KeyguardSecurityContainer extends FrameLayout {
    static final int USER_TYPE_PRIMARY = 1;
    static final int USER_TYPE_WORK_PROFILE = 2;
    static final int USER_TYPE_SECONDARY_USER = 3;

    @IntDef({MODE_DEFAULT, MODE_ONE_HANDED, MODE_USER_SWITCHER})
    public @interface Mode {}
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

    private static final long IME_DISAPPEAR_DURATION_MS = 125;

    // The duration of the animation to switch bouncer sides.
    private static final long BOUNCER_HANDEDNESS_ANIMATION_DURATION_MS = 500;

    // How much of the switch sides animation should be dedicated to fading the bouncer out. The
    // remainder will fade it back in again.
    private static final float BOUNCER_HANDEDNESS_ANIMATION_FADE_OUT_PROPORTION = 0.2f;

    @VisibleForTesting
    KeyguardSecurityViewFlipper mSecurityViewFlipper;
    private GlobalSettings mGlobalSettings;
    private FalsingManager mFalsingManager;
    private UserSwitcherController mUserSwitcherController;
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
    private ViewMode mViewMode = new DefaultViewMode();
    private @Mode int mCurrentMode = MODE_DEFAULT;
    private int mWidth = -1;

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
                        updateChildren(0 /* translationY */, 1f /* alpha */);
                    } else {
                        endJankInstrument(InteractionJankMonitor.CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR);
                    }
                }

                private void updateChildren(int translationY, float alpha) {
                    for (int i = 0; i < KeyguardSecurityContainer.this.getChildCount(); ++i) {
                        View child = KeyguardSecurityContainer.this.getChildAt(i);
                        child.setTranslationY(translationY);
                        child.setAlpha(alpha);
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
        mSecurityViewFlipper.setWindowInsetsAnimationCallback(mWindowInsetsAnimationCallback);
        updateBiometricRetry(securityMode, faceAuthEnabled);
    }

    void initMode(@Mode int mode, GlobalSettings globalSettings, FalsingManager falsingManager,
            UserSwitcherController userSwitcherController) {
        if (mCurrentMode == mode) return;
        Log.i(TAG, "Switching mode from " + modeToString(mCurrentMode) + " to "
                + modeToString(mode));
        mCurrentMode = mode;

        switch (mode) {
            case MODE_ONE_HANDED:
                mViewMode = new OneHandedViewMode();
                break;
            case MODE_USER_SWITCHER:
                mViewMode = new UserSwitcherViewMode();
                break;
            default:
                mViewMode = new DefaultViewMode();
        }
        mGlobalSettings = globalSettings;
        mFalsingManager = falsingManager;
        mUserSwitcherController = userSwitcherController;
        setupViewMode();
    }

    private String modeToString(@Mode int mode) {
        switch (mode) {
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
                mUserSwitcherController);
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

    /** Returns whether the inner SecurityViewFlipper is left-aligned when in one-handed mode. */
    public boolean isOneHandedModeLeftAligned() {
        return mCurrentMode == MODE_ONE_HANDED
                && ((OneHandedViewMode) mViewMode).isLeftAligned();
    }

    public void onPause() {
        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
            mAlertDialog = null;
        }
        mSecurityViewFlipper.setWindowInsetsAnimationCallback(null);
        mViewMode.reset();
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
                    mViewMode.handleTap(event);
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

    void setSwipeListener(SwipeListener swipeListener) {
        mSwipeListener = swipeListener;
    }

    private void startSpringAnimation(float startVelocity) {
        mSpringAnimation
                .setStartVelocity(startVelocity)
                .animateToFinalPosition(0);
    }

    /**
     * Runs after a succsssful authentication only
     */
    public void startDisappearAnimation(SecurityMode securitySelection) {
        mDisappearAnimRunning = true;
        mViewMode.startDisappearAnimation(securitySelection);
    }

    /**
     * This will run when the bouncer shows in all cases except when the user drags the bouncer up.
     */
    public void startAppearAnimation(SecurityMode securityMode) {
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
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), paddingBottom);
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

        for (int i = 0; i < getChildCount(); i++) {
            final View view = getChildAt(i);
            if (view.getVisibility() != GONE) {
                int updatedWidthMeasureSpec = mViewMode.getChildWidthMeasureSpec(widthMeasureSpec);
                final LayoutParams lp = (LayoutParams) view.getLayoutParams();

                // When using EXACTLY spec, measure will use the layout width if > 0. Set before
                // measuring the child
                lp.width = MeasureSpec.getSize(updatedWidthMeasureSpec);
                measureChildWithMargins(view, updatedWidthMeasureSpec, 0, heightMeasureSpec, 0);

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
        mViewMode.reset();
        mDisappearAnimRunning = false;
    }

    /**
     * Enscapsulates the differences between bouncer modes for the container.
     */
    interface ViewMode {

        default void init(@NonNull ViewGroup v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController) {};

        /** Reinitialize the location */
        default void updateSecurityViewLocation() {};

        /** Alter the ViewFlipper position, based upon a touch outside of it */
        default void updatePositionByTouchX(float x) {};

        /** A tap on the container, outside of the ViewFlipper */
        default void handleTap(MotionEvent event) {};

        /** Called when the view needs to reset or hides */
        default void reset() {};

        /** On a successful auth, optionally handle how the view disappears */
        default void startDisappearAnimation(SecurityMode securityMode) {};

        /** On notif tap, this animation will run */
        default void startAppearAnimation(SecurityMode securityMode) {};

        /** Override to alter the width measure spec to perhaps limit the ViewFlipper size */
        default int getChildWidthMeasureSpec(int parentWidthMeasureSpec) {
            return parentWidthMeasureSpec;
        }
    }

    /**
     * Default bouncer is centered within the space
     */
    static class DefaultViewMode implements ViewMode {
        private ViewGroup mView;
        private KeyguardSecurityViewFlipper mViewFlipper;

        @Override
        public void init(@NonNull ViewGroup v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController) {
            mView = v;
            mViewFlipper = viewFlipper;

            // Reset ViewGroup to default positions
            updateSecurityViewGroup();
        }

        private void updateSecurityViewGroup() {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) mViewFlipper.getLayoutParams();
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            mViewFlipper.setLayoutParams(lp);
            mViewFlipper.setTranslationX(0);
        }
    }

    /**
     * User switcher mode will display both the current user icon as well as
     * a user switcher, in both portrait and landscape modes.
     */
    static class UserSwitcherViewMode implements ViewMode {
        private ViewGroup mView;
        private ViewGroup mUserSwitcherViewGroup;
        private KeyguardSecurityViewFlipper mViewFlipper;
        private TextView mUserSwitcher;
        private FalsingManager mFalsingManager;
        private UserSwitcherController mUserSwitcherController;
        private KeyguardUserSwitcherPopupMenu mPopup;
        private Resources mResources;

        @Override
        public void init(@NonNull ViewGroup v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController) {
            mView = v;
            mViewFlipper = viewFlipper;
            mFalsingManager = falsingManager;
            mUserSwitcherController = userSwitcherController;
            mResources = v.getContext().getResources();

            if (mUserSwitcherViewGroup == null) {
                LayoutInflater.from(v.getContext()).inflate(
                        R.layout.keyguard_bouncer_user_switcher,
                        mView,
                        true);
                mUserSwitcherViewGroup =  mView.findViewById(R.id.keyguard_bouncer_user_switcher);
            }

            updateSecurityViewLocation();

            mUserSwitcher = mView.findViewById(R.id.user_switcher_header);
            setupUserSwitcher();
        }

        @Override
        public void reset() {
            if (mPopup != null) {
                mPopup.dismiss();
                mPopup = null;
            }
            setupUserSwitcher();
        }

        private Drawable findUserIcon(int userId) {
            Bitmap userIcon = UserManager.get(mView.getContext()).getUserIcon(userId);
            if (userIcon != null) {
                return new BitmapDrawable(userIcon);
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
            ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(mUserSwitcherViewGroup, View.ALPHA,
                    1f);
            alphaAnim.setInterpolator(Interpolators.ALPHA_IN);
            alphaAnim.setDuration(500);
            alphaAnim.start();
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
            ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(mView, View.ALPHA, 0f);

            anims.setInterpolator(Interpolators.STANDARD_ACCELERATE);
            anims.playTogether(alphaAnim, yAnim);
            anims.start();
        }

        private void setupUserSwitcher() {
            final UserRecord currentUser = mUserSwitcherController.getCurrentUserRecord();
            if (currentUser == null) {
                Log.wtf(TAG, "Current user in user switcher is null.");
                return;
            }
            Drawable userIcon = findUserIcon(currentUser.info.id);
            ((ImageView) mView.findViewById(R.id.user_icon)).setImageDrawable(userIcon);
            mUserSwitcher.setText(mUserSwitcherController.getCurrentUserName());

            ViewGroup anchor = mView.findViewById(R.id.user_switcher_anchor);
            BaseUserAdapter adapter = new BaseUserAdapter(mUserSwitcherController) {
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
                    view.setEnabled(item.isSwitchToEnabled);
                    view.setAlpha(view.isEnabled() ? USER_SWITCH_ENABLED_ALPHA :
                            USER_SWITCH_DISABLED_ALPHA);
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

            if (adapter.getCount() < 2) {
                // The drop down arrow is at index 1
                ((LayerDrawable) mUserSwitcher.getBackground()).getDrawable(1).setAlpha(0);
                anchor.setClickable(false);
                return;
            } else {
                ((LayerDrawable) mUserSwitcher.getBackground()).getDrawable(1).setAlpha(255);
            }

            anchor.setOnClickListener((v) -> {
                if (mFalsingManager.isFalseTap(LOW_PENALTY)) return;

                mPopup = new KeyguardUserSwitcherPopupMenu(v.getContext(), mFalsingManager);
                mPopup.setAnchorView(anchor);
                mPopup.setAdapter(adapter);
                mPopup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                        public void onItemClick(AdapterView parent, View view, int pos, long id) {
                            if (mFalsingManager.isFalseTap(LOW_PENALTY)) return;
                            if (!view.isEnabled()) return;

                            // Subtract one for the header
                            UserRecord user = adapter.getItem(pos - 1);
                            if (!user.isCurrent) {
                                adapter.onUserListItemClicked(user);
                            }
                            mPopup.dismiss();
                            mPopup = null;
                        }
                    });
                mPopup.show();
            });
        }

        /**
         * Each view will get half the width. Yes, it would be easier to use something other than
         * FrameLayout but it was too disruptive to downstream projects to change.
         */
        @Override
        public int getChildWidthMeasureSpec(int parentWidthMeasureSpec) {
            return MeasureSpec.makeMeasureSpec(
                    MeasureSpec.getSize(parentWidthMeasureSpec) / 2,
                    MeasureSpec.EXACTLY);
        }

        @Override
        public void updateSecurityViewLocation() {
            int yTrans = mResources.getDimensionPixelSize(R.dimen.bouncer_user_switcher_y_trans);

            if (mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                updateViewGravity(mViewFlipper, Gravity.CENTER_HORIZONTAL);
                updateViewGravity(mUserSwitcherViewGroup, Gravity.CENTER_HORIZONTAL);

                mUserSwitcherViewGroup.setTranslationY(yTrans);
                mViewFlipper.setTranslationY(-yTrans);
            } else {
                updateViewGravity(mViewFlipper, Gravity.RIGHT | Gravity.BOTTOM);
                updateViewGravity(mUserSwitcherViewGroup, Gravity.LEFT | Gravity.CENTER_VERTICAL);

                // Attempt to reposition a bit higher to make up for this frame being a bit lower
                // on the device
                mUserSwitcherViewGroup.setTranslationY(-yTrans);
                mViewFlipper.setTranslationY(0);
            }
        }

        private void updateViewGravity(View v, int gravity) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.gravity = gravity;
            v.setLayoutParams(lp);
        }
    }

    /**
     * Logic to enabled one-handed bouncer mode. Supports animating the bouncer
     * between alternate sides of the display.
     */
    static class OneHandedViewMode implements ViewMode {
        @Nullable private ValueAnimator mRunningOneHandedAnimator;
        private ViewGroup mView;
        private KeyguardSecurityViewFlipper mViewFlipper;
        private GlobalSettings mGlobalSettings;

        @Override
        public void init(@NonNull ViewGroup v, @NonNull GlobalSettings globalSettings,
                @NonNull KeyguardSecurityViewFlipper viewFlipper,
                @NonNull FalsingManager falsingManager,
                @NonNull UserSwitcherController userSwitcherController) {
            mView = v;
            mViewFlipper = viewFlipper;
            mGlobalSettings = globalSettings;

            updateSecurityViewGravity();
            updateSecurityViewLocation(isLeftAligned(), /* animate= */false);
        }

        /**
         * One-handed mode contains the child to half of the available space.
         */
        @Override
        public int getChildWidthMeasureSpec(int parentWidthMeasureSpec) {
            return MeasureSpec.makeMeasureSpec(
                    MeasureSpec.getSize(parentWidthMeasureSpec) / 2,
                    MeasureSpec.EXACTLY);
        }

        private void updateSecurityViewGravity() {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) mViewFlipper.getLayoutParams();
            lp.gravity = Gravity.LEFT | Gravity.BOTTOM;
            mViewFlipper.setLayoutParams(lp);
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

        boolean isLeftAligned() {
            return mGlobalSettings.getInt(Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                    Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT)
                == Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT;
        }

        private void updateSideSetting(boolean leftAligned) {
            mGlobalSettings.putInt(
                    Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                    leftAligned ? Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT
                    : Settings.Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT);
        }

        /**
         * Determine if a tap on this view is on the other side. If so, will animate positions
         * and record the preference to always show on this side.
         */
        @Override
        public void handleTap(MotionEvent event) {
            float x = event.getX();
            boolean currentlyLeftAligned = isLeftAligned();
            // Did the tap hit the "other" side of the bouncer?
            if ((currentlyLeftAligned && (x > mView.getWidth() / 2f))
                    || (!currentlyLeftAligned && (x < mView.getWidth() / 2f))) {

                boolean willBeLeftAligned = !currentlyLeftAligned;
                updateSideSetting(willBeLeftAligned);

                int keyguardState = willBeLeftAligned
                        ? SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SWITCH_LEFT
                        : SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__SWITCH_RIGHT;
                SysUiStatsLog.write(SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED, keyguardState);

                updateSecurityViewLocation(willBeLeftAligned, true /* animate */);
            }
        }

        @Override
        public void updateSecurityViewLocation() {
            updateSecurityViewLocation(isLeftAligned(), /* animate= */false);
        }

        /**
         * Moves the inner security view to the correct location (in one handed mode) with
         * animation. This is triggered when the user taps on the side of the screen that is not
         * currently occupied by the security view.
         */
        private void updateSecurityViewLocation(boolean leftAlign, boolean animate) {
            if (mRunningOneHandedAnimator != null) {
                mRunningOneHandedAnimator.cancel();
                mRunningOneHandedAnimator = null;
            }

            int targetTranslation = leftAlign
                    ? 0 : (int) (mView.getMeasuredWidth() - mViewFlipper.getWidth());

            if (animate) {
                // This animation is a bit fun to implement. The bouncer needs to move, and fade
                // in/out at the same time. The issue is, the bouncer should only move a short
                // amount (120dp or so), but obviously needs to go from one side of the screen to
                // the other. This needs a pretty custom animation.
                //
                // This works as follows. It uses a ValueAnimation to simply drive the animation
                // progress. This animator is responsible for both the translation of the bouncer,
                // and the current fade. It will fade the bouncer out while also moving it along the
                // 120dp path. Once the bouncer is fully faded out though, it will "snap" the
                // bouncer closer to its destination, then fade it back in again. The effect is that
                // the bouncer will move from 0 -> X while fading out, then
                // (destination - X) -> destination while fading back in again.
                // TODO(b/208250221): Make this animation properly abortable.
                Interpolator positionInterpolator = AnimationUtils.loadInterpolator(
                        mView.getContext(), android.R.interpolator.fast_out_extra_slow_in);
                Interpolator fadeOutInterpolator = Interpolators.FAST_OUT_LINEAR_IN;
                Interpolator fadeInInterpolator = Interpolators.LINEAR_OUT_SLOW_IN;

                mRunningOneHandedAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
                mRunningOneHandedAnimator.setDuration(BOUNCER_HANDEDNESS_ANIMATION_DURATION_MS);
                mRunningOneHandedAnimator.setInterpolator(Interpolators.LINEAR);

                int initialTranslation = (int) mViewFlipper.getTranslationX();
                int totalTranslation = (int) mView.getResources().getDimension(
                        R.dimen.one_handed_bouncer_move_animation_translation);

                final boolean shouldRestoreLayerType = mViewFlipper.hasOverlappingRendering()
                        && mViewFlipper.getLayerType() != View.LAYER_TYPE_HARDWARE;
                if (shouldRestoreLayerType) {
                    mViewFlipper.setLayerType(View.LAYER_TYPE_HARDWARE, /* paint= */null);
                }

                float initialAlpha = mViewFlipper.getAlpha();

                mRunningOneHandedAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mRunningOneHandedAnimator = null;
                        }
                    });
                mRunningOneHandedAnimator.addUpdateListener(animation -> {
                    float switchPoint = BOUNCER_HANDEDNESS_ANIMATION_FADE_OUT_PROPORTION;
                    boolean isFadingOut = animation.getAnimatedFraction() < switchPoint;

                    int currentTranslation = (int) (positionInterpolator.getInterpolation(
                            animation.getAnimatedFraction()) * totalTranslation);
                    int translationRemaining = totalTranslation - currentTranslation;

                    // Flip the sign if we're going from right to left.
                    if (leftAlign) {
                        currentTranslation = -currentTranslation;
                        translationRemaining = -translationRemaining;
                    }

                    if (isFadingOut) {
                        // The bouncer fades out over the first X%.
                        float fadeOutFraction = MathUtils.constrainedMap(
                                /* rangeMin= */1.0f,
                                /* rangeMax= */0.0f,
                                /* valueMin= */0.0f,
                                /* valueMax= */switchPoint,
                                animation.getAnimatedFraction());
                        float opacity = fadeOutInterpolator.getInterpolation(fadeOutFraction);

                        // When fading out, the alpha needs to start from the initial opacity of the
                        // view flipper, otherwise we get a weird bit of jank as it ramps back to
                        // 100%.
                        mViewFlipper.setAlpha(opacity * initialAlpha);

                        // Animate away from the source.
                        mViewFlipper.setTranslationX(initialTranslation + currentTranslation);
                    } else {
                        // And in again over the remaining (100-X)%.
                        float fadeInFraction = MathUtils.constrainedMap(
                                /* rangeMin= */0.0f,
                                /* rangeMax= */1.0f,
                                /* valueMin= */switchPoint,
                                /* valueMax= */1.0f,
                                animation.getAnimatedFraction());

                        float opacity = fadeInInterpolator.getInterpolation(fadeInFraction);
                        mViewFlipper.setAlpha(opacity);

                        // Fading back in, animate towards the destination.
                        mViewFlipper.setTranslationX(targetTranslation - translationRemaining);
                    }

                    if (animation.getAnimatedFraction() == 1.0f && shouldRestoreLayerType) {
                        mViewFlipper.setLayerType(View.LAYER_TYPE_NONE, /* paint= */null);
                    }
                });

                mRunningOneHandedAnimator.start();
            } else {
                mViewFlipper.setTranslationX(targetTranslation);
            }
        }
    }
}
