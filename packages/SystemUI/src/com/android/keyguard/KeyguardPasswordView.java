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

package com.android.keyguard;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowInsets.Type.ime;

import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NONE;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_PREPARE_FOR_UPDATE;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_RESTART;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TIMEOUT;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TRUSTAGENT_EXPIRED;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_USER_REQUEST;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Trace;
import android.util.AttributeSet;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimationControlListener;
import android.view.WindowInsetsAnimationController;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.motion.widget.MotionLayout;

import com.android.app.animation.Interpolators;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.TextViewInputDisabler;
import com.android.systemui.DejankUtils;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DevicePostureController;


/**
 * Displays an alphanumeric (latin-1) key entry for the user to enter
 * an unlock password
 */
public class KeyguardPasswordView extends KeyguardAbsKeyInputView {

    private TextView mPasswordEntry;
    private TextViewInputDisabler mPasswordEntryDisabler;
    private DisappearAnimationListener mDisappearAnimationListener;
    @Nullable private MotionLayout mContainerMotionLayout;
    private boolean mAlreadyUsingSplitBouncer = false;
    private boolean mIsLockScreenLandscapeEnabled = false;
    @DevicePostureController.DevicePostureInt
    private int mLastDevicePosture = DEVICE_POSTURE_UNKNOWN;
    private static final int[] DISABLE_STATE_SET = {-android.R.attr.state_enabled};
    private static final int[] ENABLE_STATE_SET = {android.R.attr.state_enabled};

    public KeyguardPasswordView(Context context) {
        this(context, null);
    }

    public KeyguardPasswordView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Use motion layout (new bouncer implementation) if LOCKSCREEN_ENABLE_LANDSCAPE flag is
     * enabled
     */
    public void setIsLockScreenLandscapeEnabled() {
        mIsLockScreenLandscapeEnabled = true;
        findContainerLayout();
    }

    private void findContainerLayout() {
        if (mIsLockScreenLandscapeEnabled) {
            mContainerMotionLayout = findViewById(R.id.password_container);
        }
    }

    @Override
    protected void resetState() {
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.passwordEntry;
    }

    @Override
    protected int getPromptReasonStringRes(int reason) {
        switch (reason) {
            case PROMPT_REASON_RESTART:
                return R.string.kg_prompt_reason_restart_password;
            case PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE:
                return R.string.kg_prompt_after_update_password;
            case PROMPT_REASON_TIMEOUT:
                return R.string.kg_prompt_reason_timeout_password;
            case PROMPT_REASON_DEVICE_ADMIN:
                return R.string.kg_prompt_reason_device_admin;
            case PROMPT_REASON_USER_REQUEST:
                return R.string.kg_prompt_after_user_lockdown_password;
            case PROMPT_REASON_PREPARE_FOR_UPDATE:
                return R.string.kg_prompt_reason_timeout_password;
            case PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT:
                return R.string.kg_prompt_reason_timeout_password;
            case PROMPT_REASON_TRUSTAGENT_EXPIRED:
                return R.string.kg_prompt_reason_timeout_password;
            case PROMPT_REASON_NONE:
                return 0;
            default:
                return R.string.kg_prompt_reason_timeout_password;
        }
    }

    void onDevicePostureChanged(@DevicePostureController.DevicePostureInt int posture) {
        if (mLastDevicePosture == posture) return;
        mLastDevicePosture = posture;

        if (mIsLockScreenLandscapeEnabled) {
            boolean useSplitBouncerAfterFold =
                    mLastDevicePosture == DEVICE_POSTURE_CLOSED
                    && getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE
                    && getResources().getBoolean(R.bool.update_bouncer_constraints);

            if (mAlreadyUsingSplitBouncer != useSplitBouncerAfterFold) {
                updateConstraints(useSplitBouncerAfterFold);
            }
        }

    }

    @Override
    protected void updateConstraints(boolean useSplitBouncer) {
        mAlreadyUsingSplitBouncer = useSplitBouncer;
        if (useSplitBouncer) {
            mContainerMotionLayout.jumpToState(R.id.split_constraints);
            mContainerMotionLayout.setMaxWidth(Integer.MAX_VALUE);
        } else {
            mContainerMotionLayout.jumpToState(R.id.single_constraints);
            mContainerMotionLayout.setMaxWidth(getResources()
                    .getDimensionPixelSize(R.dimen.keyguard_security_width));
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mPasswordEntry = findViewById(getPasswordTextViewId());
        mPasswordEntryDisabler = new TextViewInputDisabler(mPasswordEntry);

        // EditText cursor can fail screenshot tests, so disable it when testing
        if (ActivityManager.isRunningInTestHarness()) {
            mPasswordEntry.setCursorVisible(false);
        }
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // send focus to the password field
        return mPasswordEntry.requestFocus(direction, previouslyFocusedRect);
    }

    @Override
    protected void resetPasswordText(boolean animate, boolean announce) {
        mPasswordEntry.setText("");
    }

    @Override
    protected LockscreenCredential getEnteredCredential() {
        return LockscreenCredential.createPasswordOrNone(mPasswordEntry.getText());
    }

    @Override
    protected void setPasswordEntryEnabled(boolean enabled) {
        int color = mPasswordEntry.getTextColors().getColorForState(
                enabled ? ENABLE_STATE_SET : DISABLE_STATE_SET, 0);
        mPasswordEntry.setBackgroundTintList(ColorStateList.valueOf(color));
        mPasswordEntry.setCursorVisible(enabled);
    }

    @Override
    protected void setPasswordEntryInputEnabled(boolean enabled) {
        mPasswordEntryDisabler.setInputEnabled(enabled);
    }

    @Override
    public int getWrongPasswordStringId() {
        return R.string.kg_wrong_password;
    }

    @Override
    public void startAppearAnimation() {
        // Reset state, and let IME animation reveal the view as it slides in, if one exists.
        // It is possible for an IME to have no view, so provide a default animation since no
        // calls to animateForIme would occur
        setAlpha(0f);
        animate()
            .alpha(1f)
            .setDuration(300)
            .start();

        setTranslationY(0f);
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        getWindowInsetsController().controlWindowInsetsAnimation(ime(),
                100,
                Interpolators.LINEAR, null, new WindowInsetsAnimationControlListener() {

                    @Override
                    public void onReady(@NonNull WindowInsetsAnimationController controller,
                            int types) {
                        ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
                        anim.addUpdateListener(animation -> {
                            if (controller.isCancelled()) {
                                return;
                            }
                            float value = (float) animation.getAnimatedValue();
                            float fraction = anim.getAnimatedFraction();
                            Insets shownInsets = controller.getShownStateInsets();
                            int dist = (int) (-shownInsets.bottom / 4
                                    * fraction);
                            Insets insets = Insets.add(shownInsets, Insets.of(0, 0, 0, dist));
                            if (mDisappearAnimationListener != null) {
                                mDisappearAnimationListener.setTranslationY(-dist);
                            }

                            controller.setInsetsAndAlpha(insets, value, fraction);
                            setAlpha(value);
                        });
                        anim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationStart(Animator animation) {
                            }

                            @Override
                            public void onAnimationEnd(Animator animation) {
                                // Run this in the next frame since it results in a slow binder call
                                // to InputMethodManager#hideSoftInput()
                                DejankUtils.postAfterTraversal(() -> {
                                    Trace.beginSection("KeyguardPasswordView#onAnimationEnd");
                                    // // TODO(b/230620476): Make hideSoftInput oneway
                                    // controller.finish() eventually calls hideSoftInput
                                    controller.finish(false);
                                    runOnFinishImeAnimationRunnable();
                                    finishRunnable.run();
                                    mDisappearAnimationListener = null;
                                    Trace.endSection();
                                });
                            }
                        });
                        anim.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
                        anim.start();
                    }

                    @Override
                    public void onFinished(
                            @NonNull WindowInsetsAnimationController controller) {
                    }

                    @Override
                    public void onCancelled(
                            @Nullable WindowInsetsAnimationController controller) {
                        // It is possible to be denied control of ime insets, which means onReady
                        // is never called. We still need to notify the runnables in order to
                        // complete the bouncer disappearing
                        runOnFinishImeAnimationRunnable();
                        finishRunnable.run();
                    }
                });
        return true;
    }

    @Override
    public CharSequence getTitle() {
        return getResources().getString(
                com.android.internal.R.string.keyguard_accessibility_password_unlock);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (!mPasswordEntry.isFocused() && isVisibleToUser()) {
            mPasswordEntry.requestFocus();
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            if (isVisibleToUser()) {
                showKeyboard();
            } else {
                hideKeyboard();
            }
        }
    }

    /**
     * Sends signal to the focused window to show the keyboard.
     */
    public void showKeyboard() {
        post(() -> {
            if (mPasswordEntry.isAttachedToWindow()
                    && !mPasswordEntry.getRootWindowInsets().isVisible(WindowInsets.Type.ime())) {
                mPasswordEntry.requestFocus();
                mPasswordEntry.getWindowInsetsController().show(WindowInsets.Type.ime());
            }
        });
    }

    /**
     * Sends signal to the focused window to hide the keyboard.
     */
    public void hideKeyboard() {
        post(() -> {
            if (mPasswordEntry.isAttachedToWindow()
                    && mPasswordEntry.getRootWindowInsets().isVisible(WindowInsets.Type.ime())) {
                mPasswordEntry.clearFocus();
                mPasswordEntry.getWindowInsetsController().hide(WindowInsets.Type.ime());
            }
        });
    }

    /**
     * Listens to the progress of the disappear animation and handles it.
     */
    interface DisappearAnimationListener {
        void setTranslationY(int transY);
    }

    /**
     * Set an instance of the disappear animation listener to this class. This will be
     * removed when the animation completes.
     */
    public void setDisappearAnimationListener(DisappearAnimationListener listener) {
        mDisappearAnimationListener = listener;
    }
}
