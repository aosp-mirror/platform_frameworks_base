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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.PromptInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains the Biometric views (title, subtitle, icon, buttons, etc) and its controllers.
 */
public abstract class AuthBiometricView extends LinearLayout {

    private static final String TAG = "BiometricPrompt/AuthBiometricView";

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
         * Notify the receiver to start the fingerprint sensor.
         *
         * This is only applicable to multi-sensor devices that need to delay fingerprint auth
         * (i.e face -> fingerprint).
         */
        int ACTION_START_DELAYED_FINGERPRINT_SENSOR = 7;

        /**
         * When an action has occurred. The caller will only invoke this when the callback should
         * be propagated. e.g. the caller will handle any necessary delay.
         * @param action
         */
        void onAction(int action);
    }

    @VisibleForTesting
    static class Injector {
        AuthBiometricView mBiometricView;

        public Button getNegativeButton() {
            return mBiometricView.findViewById(R.id.button_negative);
        }

        public Button getCancelButton() {
            return mBiometricView.findViewById(R.id.button_cancel);
        }

        public Button getUseCredentialButton() {
            return mBiometricView.findViewById(R.id.button_use_credential);
        }

        public Button getConfirmButton() {
            return mBiometricView.findViewById(R.id.button_confirm);
        }

        public Button getTryAgainButton() {
            return mBiometricView.findViewById(R.id.button_try_again);
        }

        public TextView getTitleView() {
            return mBiometricView.findViewById(R.id.title);
        }

        public TextView getSubtitleView() {
            return mBiometricView.findViewById(R.id.subtitle);
        }

        public TextView getDescriptionView() {
            return mBiometricView.findViewById(R.id.description);
        }

        public TextView getIndicatorView() {
            return mBiometricView.findViewById(R.id.indicator);
        }

        public ImageView getIconView() {
            return mBiometricView.findViewById(R.id.biometric_icon);
        }

        public View getIconHolderView() {
            return mBiometricView.findViewById(R.id.biometric_icon_frame);
        }

        public int getDelayAfterError() {
            return BiometricPrompt.HIDE_DIALOG_DELAY;
        }

        public int getMediumToLargeAnimationDurationMs() {
            return AuthDialog.ANIMATE_MEDIUM_TO_LARGE_DURATION_MS;
        }
    }

    private final Injector mInjector;
    protected final Handler mHandler;
    private final AccessibilityManager mAccessibilityManager;
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
    protected ImageView mIconView;
    protected TextView mIndicatorView;

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

    protected Callback mCallback;
    protected @BiometricState int mState;

    private float mIconOriginalY;

    protected boolean mDialogSizeAnimating;
    protected Bundle mSavedState;

    /**
     * Delay after authentication is confirmed, before the dialog should be animated away.
     */
    protected abstract int getDelayAfterAuthenticatedDurationMs();
    /**
     * State that the dialog/icon should be in after showing a help message.
     */
    protected abstract int getStateForAfterError();
    /**
     * Invoked when the error message is being cleared.
     */
    protected abstract void handleResetAfterError();
    /**
     * Invoked when the help message is being cleared.
     */
    protected abstract void handleResetAfterHelp();

    /**
     * @return true if the dialog supports {@link AuthDialog.DialogSize#SIZE_SMALL}
     */
    protected abstract boolean supportsSmallDialog();

    private final Runnable mResetErrorRunnable;

    private final Runnable mResetHelpRunnable;

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
        this(context, attrs, new Injector());
    }

    @VisibleForTesting
    AuthBiometricView(Context context, AttributeSet attrs, Injector injector) {
        super(context, attrs);
        mHandler = new Handler(Looper.getMainLooper());
        mTextColorError = getResources().getColor(
                R.color.biometric_dialog_error, context.getTheme());
        mTextColorHint = getResources().getColor(
                R.color.biometric_dialog_gray, context.getTheme());

        mInjector = injector;
        mInjector.mBiometricView = this;

        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);

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
    }

    public void setPanelController(AuthPanelController panelController) {
        mPanelController = panelController;
    }

    public void setPromptInfo(PromptInfo promptInfo) {
        mPromptInfo = promptInfo;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void setBackgroundView(View backgroundView) {
        backgroundView.setOnClickListener(mBackgroundClickListener);
    }

    public void setUserId(int userId) {
        mUserId = userId;
    }

    public void setEffectiveUserId(int effectiveUserId) {
        mEffectiveUserId = effectiveUserId;
    }

    public void setRequireConfirmation(boolean requireConfirmation) {
        mRequireConfirmation = requireConfirmation;
    }

    @VisibleForTesting
    void updateSize(@AuthDialog.DialogSize int newSize) {
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
            as.setDuration(AuthDialog.ANIMATE_SMALL_TO_MEDIUM_DURATION_MS);
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
            translationAnimator.setDuration(mInjector.getMediumToLargeAnimationDurationMs());
            translationAnimator.addUpdateListener((animation) -> {
                final float translation = (float) animation.getAnimatedValue();
                biometricView.setTranslationY(translation);
            });
            translationAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (biometricView.getParent() != null) {
                        ((ViewGroup) biometricView.getParent()).removeView(biometricView);
                    }
                    mSize = newSize;
                }
            });

            // Opacity to 0 in half duration
            final ValueAnimator opacityAnimator = ValueAnimator.ofFloat(1, 0);
            opacityAnimator.setDuration(mInjector.getMediumToLargeAnimationDurationMs() / 2);
            opacityAnimator.addUpdateListener((animation) -> {
                final float opacity = (float) animation.getAnimatedValue();
                biometricView.setAlpha(opacity);
            });

            mPanelController.setUseFullScreen(true);
            mPanelController.updateForContentDimensions(
                    mPanelController.getContainerWidth(),
                    mPanelController.getContainerHeight(),
                    mInjector.getMediumToLargeAnimationDurationMs());

            // Start the animations together
            AnimatorSet as = new AnimatorSet();
            List<Animator> animators = new ArrayList<>();
            animators.add(translationAnimator);
            animators.add(opacityAnimator);

            as.playTogether(animators);
            as.setDuration(mInjector.getMediumToLargeAnimationDurationMs() * 2 / 3);
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
                mConfirmButton.setEnabled(true);
                mConfirmButton.setVisibility(View.VISIBLE);
                mIndicatorView.setTextColor(mTextColorHint);
                mIndicatorView.setText(R.string.biometric_dialog_tap_confirm);
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

    public void onAuthenticationSucceeded() {
        removePendingAnimations();
        if (mRequireConfirmation) {
            updateState(STATE_PENDING_CONFIRMATION);
        } else {
            updateState(STATE_AUTHENTICATED);
        }
    }

    public void onAuthenticationFailed(String failureReason) {
        showTemporaryMessage(failureReason, mResetErrorRunnable);
        updateState(STATE_ERROR);
    }

    public void onError(String error) {
        showTemporaryMessage(error, mResetErrorRunnable);
        updateState(STATE_ERROR);

        mHandler.postDelayed(() -> {
            mCallback.onAction(Callback.ACTION_ERROR);
        }, mInjector.getDelayAfterError());
    }

    public void onHelp(String help) {
        if (mSize != AuthDialog.SIZE_MEDIUM) {
            Log.w(TAG, "Help received in size: " + mSize);
            return;
        }
        showTemporaryMessage(help, mResetHelpRunnable);
        updateState(STATE_HELP);
    }

    public void onSaveState(@NonNull Bundle outState) {
        outState.putInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY,
                mTryAgainButton.getVisibility());
        outState.putInt(AuthDialog.KEY_BIOMETRIC_STATE, mState);
        outState.putString(AuthDialog.KEY_BIOMETRIC_INDICATOR_STRING,
                mIndicatorView.getText().toString());
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

    private void setText(TextView view, CharSequence charSequence) {
        view.setText(charSequence);
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
        mIndicatorView.setSelected(true);
        mHandler.postDelayed(resetMessageRunnable, BiometricPrompt.HIDE_DIALOG_DELAY);

        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        onFinishInflateInternal();
    }

    /**
     * After inflation, but before things like restoreState, onAttachedToWindow, etc.
     */
    @VisibleForTesting
    void onFinishInflateInternal() {
        mTitleView = mInjector.getTitleView();
        mSubtitleView = mInjector.getSubtitleView();
        mDescriptionView = mInjector.getDescriptionView();
        mIconView = mInjector.getIconView();
        mIconHolderView = mInjector.getIconHolderView();
        mIndicatorView = mInjector.getIndicatorView();

        // Negative-side (left) buttons
        mNegativeButton = mInjector.getNegativeButton();
        mCancelButton = mInjector.getCancelButton();
        mUseCredentialButton = mInjector.getUseCredentialButton();

        // Positive-side (right) buttons
        mConfirmButton = mInjector.getConfirmButton();
        mTryAgainButton = mInjector.getTryAgainButton();

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
        onAttachedToWindowInternal();
    }

    /**
     * Contains all the testable logic that should be invoked when {@link #onAttachedToWindow()} is
     * invoked.
     */
    @VisibleForTesting
    void onAttachedToWindowInternal() {
        setText(mTitleView, mPromptInfo.getTitle());

        if (isDeviceCredentialAllowed()) {
            final CharSequence credentialButtonText;
            final @Utils.CredentialType int credentialType =
                    Utils.getCredentialType(mContext, mEffectiveUserId);
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
                    credentialButtonText =
                            getResources().getString(R.string.biometric_dialog_use_password);
                    break;
                default:
                    credentialButtonText =
                            getResources().getString(R.string.biometric_dialog_use_password);
                    break;
            }

            mNegativeButton.setVisibility(View.GONE);

            mUseCredentialButton.setText(credentialButtonText);
            mUseCredentialButton.setVisibility(View.VISIBLE);
        } else {
            setText(mNegativeButton, mPromptInfo.getNegativeButtonText());
        }

        setTextOrHide(mSubtitleView, mPromptInfo.getSubtitle());

        setTextOrHide(mDescriptionView, mPromptInfo.getDescription());

        if (mSavedState == null) {
            updateState(STATE_AUTHENTICATING_ANIMATING_IN);
        } else {
            // Restore as much state as possible first
            updateState(mSavedState.getInt(AuthDialog.KEY_BIOMETRIC_STATE));

            // Restore positive button state
            mTryAgainButton.setVisibility(
                    mSavedState.getInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

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
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int newWidth = Math.min(width, height);

        // Use "newWidth" instead, so the landscape dialog width is the same as the portrait
        // width.
        mLayoutParams = onMeasureInternal(newWidth, height);
        setMeasuredDimension(mLayoutParams.mMediumWidth, mLayoutParams.mMediumHeight);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        onLayoutInternal();
    }

    /**
     * Contains all the testable logic that should be invoked when
     * {@link #onLayout(boolean, int, int, int, int)}, is invoked.
     */
    @VisibleForTesting
    void onLayoutInternal() {
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
                    onHelp(indicatorText);
                } else if (mSavedState.getBoolean(
                        AuthDialog.KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING)) {
                    onAuthenticationFailed(indicatorText);
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
}
