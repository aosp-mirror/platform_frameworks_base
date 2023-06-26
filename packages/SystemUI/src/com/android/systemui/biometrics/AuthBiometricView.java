/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_NONE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.PromptInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;

import com.airbnb.lottie.LottieAnimationView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Contains the Biometric views (title, subtitle, icon, buttons, etc.) and its controllers.
 */
public abstract class AuthBiometricView extends LinearLayout {

    private static final String TAG = "AuthBiometricView";

    /**
     * Authentication hardware idle.
     */
    protected static final int STATE_IDLE = 0;
    /**
     * UI animating in, authentication hardware active.
     */
    protected static final int STATE_AUTHENTICATING_ANIMATING_IN = 1;
    /**
     * UI animated in, authentication hardware active.
     */
    protected static final int STATE_AUTHENTICATING = 2;
    /**
     * UI animated in, authentication hardware active.
     */
    protected static final int STATE_HELP = 3;
    /**
     * Hard error, e.g. ERROR_TIMEOUT. Authentication hardware idle.
     */
    protected static final int STATE_ERROR = 4;
    /**
     * Authenticated, waiting for user confirmation. Authentication hardware idle.
     */
    protected static final int STATE_PENDING_CONFIRMATION = 5;
    /**
     * Authenticated, dialog animating away soon.
     */
    protected static final int STATE_AUTHENTICATED = 6;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_IDLE, STATE_AUTHENTICATING_ANIMATING_IN, STATE_AUTHENTICATING, STATE_HELP,
            STATE_ERROR, STATE_PENDING_CONFIRMATION, STATE_AUTHENTICATED})
    @interface BiometricState {}

    /**
     * Callback to the parent when a user action has occurred.
     */
    interface Callback {
        int ACTION_AUTHENTICATED = 1;
        int ACTION_USER_CANCELED = 2;
        int ACTION_BUTTON_NEGATIVE = 3;
        int ACTION_BUTTON_TRY_AGAIN = 4;
        int ACTION_ERROR = 5;
        int ACTION_USE_DEVICE_CREDENTIAL = 6;

        /**
         * When an action has occurred. The caller will only invoke this when the callback should
         * be propagated. e.g. the caller will handle any necessary delay.
         * @param action
         */
        void onAction(int action);
    }

    private final Handler mHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final LockPatternUtils mLockPatternUtils;
    protected final int mTextColorError;
    protected final int mTextColorHint;

    private AuthPanelController mPanelController;
    private PromptInfo mPromptInfo;
    private boolean mRequireConfirmation;
    private int mUserId;
    private int mEffectiveUserId;
    private @AuthDialog.DialogSize int mSize = AuthDialog.SIZE_UNKNOWN;

    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mDescriptionView;
    private View mIconHolderView;
    protected LottieAnimationView mIconViewOverlay;
    protected LottieAnimationView mIconView;
    protected TextView mIndicatorView;

    @VisibleForTesting @NonNull AuthIconController mIconController;
    @VisibleForTesting int mAnimationDurationShort = AuthDialog.ANIMATE_SMALL_TO_MEDIUM_DURATION_MS;
    @VisibleForTesting int mAnimationDurationLong = AuthDialog.ANIMATE_MEDIUM_TO_LARGE_DURATION_MS;
    @VisibleForTesting int mAnimationDurationHideDialog = BiometricPrompt.HIDE_DIALOG_DELAY;

    // Negative button position, exclusively for the app-specified behavior
    @VisibleForTesting Button mNegativeButton;
    // Negative button position, exclusively for cancelling auth after passive auth success
    @VisibleForTesting Button mCancelButton;
    // Negative button position, shown if device credentials are allowed
    @VisibleForTesting Button mUseCredentialButton;

    // Positive button position,
    @VisibleForTesting Button mConfirmButton;
    @VisibleForTesting Button mTryAgainButton;

    // Measurements when biometric view is showing text, buttons, etc.
    @Nullable @VisibleForTesting AuthDialog.LayoutParams mLayoutParams;

    private Callback mCallback;
    @BiometricState private int mState;

    private float mIconOriginalY;

    protected boolean mDialogSizeAnimating;
    protected Bundle mSavedState;

    private final Runnable mResetErrorRunnable;
    private final Runnable mResetHelpRunnable;

    private Animator.AnimatorListener mJankListener;

    private final boolean mUseCustomBpSize;
    private final int mCustomBpWidth;
    private final int mCustomBpHeight;

    private final OnClickListener mBackgroundClickListener = (view) -> {
        if (mState == STATE_AUTHENTICATED) {
            Log.w(TAG, "Ignoring background click after authenticated");
            return;
        } else if (mSize == AuthDialog.SIZE_SMALL) {
            Log.w(TAG, "Ignoring background click during small dialog");
            return;
        } else if (mSize == AuthDialog.SIZE_LARGE) {
            Log.w(TAG, "Ignoring background click during large dialog");
            return;
        }
        mCallback.onAction(Callback.ACTION_USER_CANCELED);
    };

    public AuthBiometricView(Context context) {
        this(context, null);
    }

    public AuthBiometricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHandler = new Handler(Looper.getMainLooper());
        mTextColorError = getResources().getColor(
                R.color.biometric_dialog_error, context.getTheme());
        mTextColorHint = getResources().getColor(
                R.color.biometric_dialog_gray, context.getTheme());

        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mLockPatternUtils = new LockPatternUtils(context);

        mResetErrorRunnable = () -> {
            updateState(getStateForAfterError());
            handleResetAfterError();
            Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
        };

        mResetHelpRunnable = () -> {
            updateState(STATE_AUTHENTICATING);
            handleResetAfterHelp();
            Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
        };

        mUseCustomBpSize = getResources().getBoolean(R.bool.use_custom_bp_size);
        mCustomBpWidth = getResources().getDimensionPixelSize(R.dimen.biometric_dialog_width);
        mCustomBpHeight = getResources().getDimensionPixelSize(R.dimen.biometric_dialog_height);
    }

    /** Delay after authentication is confirmed, before the dialog should be animated away. */
    protected int getDelayAfterAuthenticatedDurationMs() {
        return 0;
    }

    /** State that the dialog/icon should be in after showing a help message. */
    protected int getStateForAfterError() {
        return STATE_IDLE;
    }

    /** Invoked when the error message is being cleared. */
    protected void handleResetAfterError() {}

    /** Invoked when the help message is being cleared. */
    protected void handleResetAfterHelp() {}

    /** True if the dialog supports {@link AuthDialog.DialogSize#SIZE_SMALL}. */
    protected boolean supportsSmallDialog() {
        return false;
    }

    /** The string to show when the user must tap to confirm via the button or icon. */
    @StringRes
    protected int getConfirmationPrompt() {
        return R.string.biometric_dialog_tap_confirm;
    }

    /** True if require confirmation will be honored when set via the API. */
    protected boolean supportsRequireConfirmation() {
        return false;
    }

    /** True if confirmation will be required even if it was not supported/requested. */
    protected boolean forceRequireConfirmation(@Modality int modality) {
        return false;
    }

    /** Ignore all events from this (secondary) modality except successful authentication. */
    protected boolean ignoreUnsuccessfulEventsFrom(@Modality int modality) {
        return false;
    }

    /** Create the controller for managing the icons transitions during the prompt.*/
    @NonNull
    protected abstract AuthIconController createIconController();

    void setPanelController(AuthPanelController panelController) {
        mPanelController = panelController;
    }

    void setPromptInfo(PromptInfo promptInfo) {
        mPromptInfo = promptInfo;
    }

    void setCallback(Callback callback) {
        mCallback = callback;
    }

    void setBackgroundView(View backgroundView) {
        backgroundView.setOnClickListener(mBackgroundClickListener);
    }

    void setUserId(int userId) {
        mUserId = userId;
    }

    void setEffectiveUserId(int effectiveUserId) {
        mEffectiveUserId = effectiveUserId;
    }

    void setRequireConfirmation(boolean requireConfirmation) {
        mRequireConfirmation = requireConfirmation && supportsRequireConfirmation();
    }

    void setJankListener(Animator.AnimatorListener jankListener) {
        mJankListener = jankListener;
    }

    @VisibleForTesting
    final void updateSize(@AuthDialog.DialogSize int newSize) {
        Log.v(TAG, "Current size: " + mSize + " New size: " + newSize);
        if (newSize == AuthDialog.SIZE_SMALL) {
            mTitleView.setVisibility(View.GONE);
            mSubtitleView.setVisibility(View.GONE);
            mDescriptionView.setVisibility(View.GONE);
            mIndicatorView.setVisibility(View.GONE);
            mNegativeButton.setVisibility(View.GONE);
            mUseCredentialButton.setVisibility(View.GONE);

            final float iconPadding = getResources()
                    .getDimension(R.dimen.biometric_dialog_icon_padding);
            mIconHolderView.setY(getHeight() - mIconHolderView.getHeight() - iconPadding);

            // Subtract the vertical padding from the new height since it's only used to create
            // extra space between the other elements, and not part of the actual icon.
            final int newHeight = mIconHolderView.getHeight() + 2 * (int) iconPadding
                    - mIconHolderView.getPaddingTop() - mIconHolderView.getPaddingBottom();
            mPanelController.updateForContentDimensions(mLayoutParams.mMediumWidth, newHeight,
                    0 /* animateDurationMs */);

            mSize = newSize;
        } else if (mSize == AuthDialog.SIZE_SMALL && newSize == AuthDialog.SIZE_MEDIUM) {
            if (mDialogSizeAnimating) {
                return;
            }
            mDialogSizeAnimating = true;

            // Animate the icon back to original position
            final ValueAnimator iconAnimator =
                    ValueAnimator.ofFloat(mIconHolderView.getY(), mIconOriginalY);
            iconAnimator.addUpdateListener((animation) -> {
                mIconHolderView.setY((float) animation.getAnimatedValue());
            });

            // Animate the text
            final ValueAnimator opacityAnimator = ValueAnimator.ofFloat(0, 1);
            opacityAnimator.addUpdateListener((animation) -> {
                final float opacity = (float) animation.getAnimatedValue();
                mTitleView.setAlpha(opacity);
                mIndicatorView.setAlpha(opacity);
                mNegativeButton.setAlpha(opacity);
                mCancelButton.setAlpha(opacity);
                mTryAgainButton.setAlpha(opacity);

                if (!TextUtils.isEmpty(mSubtitleView.getText())) {
                    mSubtitleView.setAlpha(opacity);
                }
                if (!TextUtils.isEmpty(mDescriptionView.getText())) {
                    mDescriptionView.setAlpha(opacity);
                }
            });

            // Choreograph together
            final AnimatorSet as = new AnimatorSet();
            as.setDuration(mAnimationDurationShort);
            as.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    mTitleView.setVisibility(View.VISIBLE);
                    mIndicatorView.setVisibility(View.VISIBLE);

                    if (isDeviceCredentialAllowed()) {
                        mUseCredentialButton.setVisibility(View.VISIBLE);
                    } else {
                        mNegativeButton.setVisibility(View.VISIBLE);
                    }
                    if (supportsManualRetry()) {
                        mTryAgainButton.setVisibility(View.VISIBLE);
                    }

                    if (!TextUtils.isEmpty(mSubtitleView.getText())) {
                        mSubtitleView.setVisibility(View.VISIBLE);
                    }
                    if (!TextUtils.isEmpty(mDescriptionView.getText())) {
                        mDescriptionView.setVisibility(View.VISIBLE);
                    }
                }
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    mSize = newSize;
                    mDialogSizeAnimating = false;
                    Utils.notifyAccessibilityContentChanged(mAccessibilityManager,
                            AuthBiometricView.this);
                }
            });

            if (mJankListener != null) {
                as.addListener(mJankListener);
            }
            as.play(iconAnimator).with(opacityAnimator);
            as.start();
            // Animate the panel
            mPanelController.updateForContentDimensions(mLayoutParams.mMediumWidth,
                    mLayoutParams.mMediumHeight,
                    AuthDialog.ANIMATE_SMALL_TO_MEDIUM_DURATION_MS);
        } else if (newSize == AuthDialog.SIZE_MEDIUM) {
            mPanelController.updateForContentDimensions(mLayoutParams.mMediumWidth,
                    mLayoutParams.mMediumHeight,
                    0 /* animateDurationMs */);
            mSize = newSize;
        } else if (newSize == AuthDialog.SIZE_LARGE) {
            final float translationY = getResources().getDimension(
                            R.dimen.biometric_dialog_medium_to_large_translation_offset);
            final AuthBiometricView biometricView = this;

            // Translate at full duration
            final ValueAnimator translationAnimator = ValueAnimator.ofFloat(
                    biometricView.getY(), biometricView.getY() - translationY);
            translationAnimator.setDuration(mAnimationDurationLong);
            translationAnimator.addUpdateListener((animation) -> {
                final float translation = (float) animation.getAnimatedValue();
                biometricView.setTranslationY(translation);
            });
            translationAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (biometricView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) biometricView.getParent()).removeView(biometricView);
                    }
                    mSize = newSize;
                }
            });

            // Opacity to 0 in half duration
            final ValueAnimator opacityAnimator = ValueAnimator.ofFloat(1, 0);
            opacityAnimator.setDuration(mAnimationDurationLong / 2);
            opacityAnimator.addUpdateListener((animation) -> {
                final float opacity = (float) animation.getAnimatedValue();
                biometricView.setAlpha(opacity);
            });

            mPanelController.setUseFullScreen(true);
            mPanelController.updateForContentDimensions(
                    mPanelController.getContainerWidth(),
                    mPanelController.getContainerHeight(),
                    mAnimationDurationLong);

            // Start the animations together
            AnimatorSet as = new AnimatorSet();
            List<Animator> animators = new ArrayList<>();
            animators.add(translationAnimator);
            animators.add(opacityAnimator);

            if (mJankListener != null) {
                as.addListener(mJankListener);
            }
            as.playTogether(animators);
            as.setDuration(mAnimationDurationLong * 2 / 3);
            as.start();
        } else {
            Log.e(TAG, "Unknown transition from: " + mSize + " to: " + newSize);
        }
        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
    }

    protected boolean supportsManualRetry() {
        return false;
    }

    public void updateState(@BiometricState int newState) {
        Log.v(TAG, "newState: " + newState);

        mIconController.updateState(mState, newState);

        switch (newState) {
            case STATE_AUTHENTICATING_ANIMATING_IN:
            case STATE_AUTHENTICATING:
                removePendingAnimations();
                if (mRequireConfirmation) {
                    mConfirmButton.setEnabled(false);
                    mConfirmButton.setVisibility(View.VISIBLE);
                }
                break;

            case STATE_AUTHENTICATED:
                removePendingAnimations();
                if (mSize != AuthDialog.SIZE_SMALL) {
                    mConfirmButton.setVisibility(View.GONE);
                    mNegativeButton.setVisibility(View.GONE);
                    mUseCredentialButton.setVisibility(View.GONE);
                    mCancelButton.setVisibility(View.GONE);
                    mIndicatorView.setVisibility(View.INVISIBLE);
                }
                announceForAccessibility(getResources()
                        .getString(R.string.biometric_dialog_authenticated));
                mHandler.postDelayed(() -> mCallback.onAction(Callback.ACTION_AUTHENTICATED),
                        getDelayAfterAuthenticatedDurationMs());
                break;

            case STATE_PENDING_CONFIRMATION:
                removePendingAnimations();
                mNegativeButton.setVisibility(View.GONE);
                mCancelButton.setVisibility(View.VISIBLE);
                mUseCredentialButton.setVisibility(View.GONE);
                // forced confirmations (multi-sensor) use the icon view as the confirm button
                mConfirmButton.setEnabled(mRequireConfirmation);
                mConfirmButton.setVisibility(mRequireConfirmation ? View.VISIBLE : View.GONE);
                mIndicatorView.setTextColor(mTextColorHint);
                mIndicatorView.setText(getConfirmationPrompt());
                mIndicatorView.setVisibility(View.VISIBLE);
                break;

            case STATE_ERROR:
                if (mSize == AuthDialog.SIZE_SMALL) {
                    updateSize(AuthDialog.SIZE_MEDIUM);
                }
                break;

            default:
                Log.w(TAG, "Unhandled state: " + newState);
                break;
        }

        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
        mState = newState;
    }

    public void onDialogAnimatedIn() {
        updateState(STATE_AUTHENTICATING);
    }

    public void onAuthenticationSucceeded(@Modality int modality) {
        removePendingAnimations();
        if (mRequireConfirmation || forceRequireConfirmation(modality)) {
            updateState(STATE_PENDING_CONFIRMATION);
        } else {
            updateState(STATE_AUTHENTICATED);
        }
    }

    /**
     * Notify the view that auth has failed.
     *
     * @param modality sensor modality that failed
     * @param failureReason message
     */
    public void onAuthenticationFailed(
            @Modality int modality, @Nullable String failureReason) {
        if (ignoreUnsuccessfulEventsFrom(modality)) {
            return;
        }

        showTemporaryMessage(failureReason, mResetErrorRunnable);
        updateState(STATE_ERROR);
    }

    /**
     * Notify the view that an error occurred.
     *
     * @param modality sensor modality that failed
     * @param error message
     */
    public void onError(@Modality int modality, String error) {
        if (ignoreUnsuccessfulEventsFrom(modality)) {
            return;
        }

        showTemporaryMessage(error, mResetErrorRunnable);
        updateState(STATE_ERROR);

        mHandler.postDelayed(() -> mCallback.onAction(Callback.ACTION_ERROR),
                mAnimationDurationHideDialog);
    }

    /**
     * Fingerprint pointer down event. This does nothing by default and will not be called if the
     * device does not have an appropriate sensor (UDFPS), but it may be used as an alternative
     * to the "retry" button when fingerprint is used with other modalities.
     *
     * @param failedModalities the set of modalities that have failed
     * @return true if a retry was initiated as a result of this event
     */
    public boolean onPointerDown(Set<Integer> failedModalities) {
        return false;
    }

    /**
     * Show a help message to the user.
     *
     * @param modality sensor modality
     * @param help message
     */
    public void onHelp(@Modality int modality, String help) {
        if (ignoreUnsuccessfulEventsFrom(modality)) {
            return;
        }
        if (mSize != AuthDialog.SIZE_MEDIUM) {
            Log.w(TAG, "Help received in size: " + mSize);
            return;
        }
        if (TextUtils.isEmpty(help)) {
            Log.w(TAG, "Ignoring blank help message");
            return;
        }

        showTemporaryMessage(help, mResetHelpRunnable);
        updateState(STATE_HELP);
    }

    public void onSaveState(@NonNull Bundle outState) {
        outState.putInt(AuthDialog.KEY_BIOMETRIC_CONFIRM_VISIBILITY,
                mConfirmButton.getVisibility());
        outState.putInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY,
                mTryAgainButton.getVisibility());
        outState.putInt(AuthDialog.KEY_BIOMETRIC_STATE, mState);
        outState.putString(AuthDialog.KEY_BIOMETRIC_INDICATOR_STRING,
                mIndicatorView.getText() != null ? mIndicatorView.getText().toString() : "");
        outState.putBoolean(AuthDialog.KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING,
                mHandler.hasCallbacks(mResetErrorRunnable));
        outState.putBoolean(AuthDialog.KEY_BIOMETRIC_INDICATOR_HELP_SHOWING,
                mHandler.hasCallbacks(mResetHelpRunnable));
        outState.putInt(AuthDialog.KEY_BIOMETRIC_DIALOG_SIZE, mSize);
    }

    /**
     * Invoked after inflation but before being attached to window.
     * @param savedState
     */
    public void restoreState(@Nullable Bundle savedState) {
        mSavedState = savedState;
    }

    private void setTextOrHide(TextView view, CharSequence charSequence) {
        if (TextUtils.isEmpty(charSequence)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(charSequence);
        }

        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
    }

    // Remove all pending icon and text animations
    private void removePendingAnimations() {
        mHandler.removeCallbacks(mResetHelpRunnable);
        mHandler.removeCallbacks(mResetErrorRunnable);
    }

    private void showTemporaryMessage(String message, Runnable resetMessageRunnable) {
        removePendingAnimations();
        mIndicatorView.setText(message);
        mIndicatorView.setTextColor(mTextColorError);
        mIndicatorView.setVisibility(View.VISIBLE);
        // select to enable marquee unless a screen reader is enabled
        mIndicatorView.setSelected(!mAccessibilityManager.isEnabled()
                || !mAccessibilityManager.isTouchExplorationEnabled());
        mHandler.postDelayed(resetMessageRunnable, mAnimationDurationHideDialog);

        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mIconController.onConfigurationChanged(newConfig);
        if (mSavedState != null) {
            updateState(mSavedState.getInt(AuthDialog.KEY_BIOMETRIC_STATE));
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mTitleView = findViewById(R.id.title);
        mSubtitleView = findViewById(R.id.subtitle);
        mDescriptionView = findViewById(R.id.description);
        mIconViewOverlay = findViewById(R.id.biometric_icon_overlay);
        mIconView = findViewById(R.id.biometric_icon);
        mIconHolderView = findViewById(R.id.biometric_icon_frame);
        mIndicatorView = findViewById(R.id.indicator);

        // Negative-side (left) buttons
        mNegativeButton = findViewById(R.id.button_negative);
        mCancelButton = findViewById(R.id.button_cancel);
        mUseCredentialButton = findViewById(R.id.button_use_credential);

        // Positive-side (right) buttons
        mConfirmButton = findViewById(R.id.button_confirm);
        mTryAgainButton = findViewById(R.id.button_try_again);

        mNegativeButton.setOnClickListener((view) -> {
            mCallback.onAction(Callback.ACTION_BUTTON_NEGATIVE);
        });

        mCancelButton.setOnClickListener((view) -> {
            mCallback.onAction(Callback.ACTION_USER_CANCELED);
        });

        mUseCredentialButton.setOnClickListener((view) -> {
            startTransitionToCredentialUI();
        });

        mConfirmButton.setOnClickListener((view) -> {
            updateState(STATE_AUTHENTICATED);
        });

        mTryAgainButton.setOnClickListener((view) -> {
            updateState(STATE_AUTHENTICATING);
            mCallback.onAction(Callback.ACTION_BUTTON_TRY_AGAIN);
            mTryAgainButton.setVisibility(View.GONE);
            Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
        });

        mIconController = createIconController();
        if (mIconController.getActsAsConfirmButton()) {
            mIconViewOverlay.setOnClickListener((view)->{
                if (mState == STATE_PENDING_CONFIRMATION) {
                    updateState(STATE_AUTHENTICATED);
                }
            });
            mIconView.setOnClickListener((view) -> {
                if (mState == STATE_PENDING_CONFIRMATION) {
                    updateState(STATE_AUTHENTICATED);
                }
            });
        }
    }

    /**
     * Kicks off the animation process and invokes the callback.
     */
    void startTransitionToCredentialUI() {
        updateSize(AuthDialog.SIZE_LARGE);
        mCallback.onAction(Callback.ACTION_USE_DEVICE_CREDENTIAL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mTitleView.setText(mPromptInfo.getTitle());

        //setSelected could make marguee work
        mTitleView.setSelected(true);
        mSubtitleView.setSelected(true);
        //make description view become scrollable
        mDescriptionView.setMovementMethod(new ScrollingMovementMethod());

        if (isDeviceCredentialAllowed()) {
            final CharSequence credentialButtonText;
            @Utils.CredentialType final int credentialType =
                    Utils.getCredentialType(mLockPatternUtils, mEffectiveUserId);
            switch (credentialType) {
                case Utils.CREDENTIAL_PIN:
                    credentialButtonText =
                            getResources().getString(R.string.biometric_dialog_use_pin);
                    break;
                case Utils.CREDENTIAL_PATTERN:
                    credentialButtonText =
                            getResources().getString(R.string.biometric_dialog_use_pattern);
                    break;
                case Utils.CREDENTIAL_PASSWORD:
                default:
                    credentialButtonText =
                            getResources().getString(R.string.biometric_dialog_use_password);
                    break;
            }

            mNegativeButton.setVisibility(View.GONE);

            mUseCredentialButton.setText(credentialButtonText);
            mUseCredentialButton.setVisibility(View.VISIBLE);
        } else {
            mNegativeButton.setText(mPromptInfo.getNegativeButtonText());
        }

        setTextOrHide(mSubtitleView, mPromptInfo.getSubtitle());
        setTextOrHide(mDescriptionView, mPromptInfo.getDescription());

        if (mSavedState == null) {
            updateState(STATE_AUTHENTICATING_ANIMATING_IN);
        } else {
            // Restore as much state as possible first
            updateState(mSavedState.getInt(AuthDialog.KEY_BIOMETRIC_STATE));

            // Restore positive button(s) state
            mConfirmButton.setVisibility(
                    mSavedState.getInt(AuthDialog.KEY_BIOMETRIC_CONFIRM_VISIBILITY));
            if (mConfirmButton.getVisibility() == View.GONE) {
                setRequireConfirmation(false);
            }
            mTryAgainButton.setVisibility(
                    mSavedState.getInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY));

        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mIconController.setDeactivated(true);

        // Empty the handler, otherwise things like ACTION_AUTHENTICATED may be duplicated once
        // the new dialog is restored.
        mHandler.removeCallbacksAndMessages(null /* all */);
    }

    /**
     * Contains all of the testable logic that should be invoked when {@link #onMeasure(int, int)}
     * is invoked. In addition, this allows subclasses to implement custom measuring logic while
     * allowing the base class to have common code to apply the custom measurements.
     *
     * @param width Width to constrain the measurements to.
     * @param height Height to constrain the measurements to.
     * @return See {@link AuthDialog.LayoutParams}
     */
    @NonNull
    AuthDialog.LayoutParams onMeasureInternal(int width, int height) {
        int totalHeight = 0;
        final int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            final View child = getChildAt(i);

            if (child.getId() == R.id.space_above_icon
                    || child.getId() == R.id.space_below_icon
                    || child.getId() == R.id.button_bar) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getLayoutParams().height,
                                MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.biometric_icon_frame) {
                final View iconView = findViewById(R.id.biometric_icon);
                child.measure(
                        MeasureSpec.makeMeasureSpec(iconView.getLayoutParams().width,
                                MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(iconView.getLayoutParams().height,
                                MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.biometric_icon) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            } else {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }

            if (child.getVisibility() != View.GONE) {
                totalHeight += child.getMeasuredHeight();
            }
        }

        return new AuthDialog.LayoutParams(width, totalHeight);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (mUseCustomBpSize) {
            width = mCustomBpWidth;
            height = mCustomBpHeight;
        } else {
            width = Math.min(width, height);
        }

        mLayoutParams = onMeasureInternal(width, height);
        setMeasuredDimension(mLayoutParams.mMediumWidth, mLayoutParams.mMediumHeight);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Start with initial size only once. Subsequent layout changes don't matter since we
        // only care about the initial icon position.
        if (mIconOriginalY == 0) {
            mIconOriginalY = mIconHolderView.getY();
            if (mSavedState == null) {
                updateSize(!mRequireConfirmation && supportsSmallDialog() ? AuthDialog.SIZE_SMALL
                        : AuthDialog.SIZE_MEDIUM);
            } else {
                updateSize(mSavedState.getInt(AuthDialog.KEY_BIOMETRIC_DIALOG_SIZE));

                // Restore indicator text state only after size has been restored
                final String indicatorText =
                        mSavedState.getString(AuthDialog.KEY_BIOMETRIC_INDICATOR_STRING);
                if (mSavedState.getBoolean(AuthDialog.KEY_BIOMETRIC_INDICATOR_HELP_SHOWING)) {
                    onHelp(TYPE_NONE, indicatorText);
                } else if (mSavedState.getBoolean(
                        AuthDialog.KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING)) {
                    onAuthenticationFailed(TYPE_NONE, indicatorText);
                }
            }
        }
    }

    private boolean isDeviceCredentialAllowed() {
        return Utils.isDeviceCredentialAllowed(mPromptInfo);
    }

    @AuthDialog.DialogSize int getSize() {
        return mSize;
    }

    /** If authentication has successfully occurred and the view is done. */
    boolean isAuthenticated() {
        return mState == STATE_AUTHENTICATED;
    }

    /** If authentication is currently in progress. */
    boolean isAuthenticating() {
        return mState == STATE_AUTHENTICATING;
    }
}
