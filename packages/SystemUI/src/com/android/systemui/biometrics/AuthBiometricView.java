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
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

        public Button getPositiveButton() {
            return mBiometricView.findViewById(R.id.button_positive);
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

        public TextView getErrorView() {
            return mBiometricView.findViewById(R.id.error);
        }

        public ImageView getIconView() {
            return mBiometricView.findViewById(R.id.biometric_icon);
        }
    }

    private final Injector mInjector;
    private final Handler mHandler;
    private final int mTextColorError;
    private final int mTextColorHint;

    private AuthPanelController mPanelController;
    private Bundle mBundle;
    private boolean mRequireConfirmation;
    @AuthDialog.DialogSize int mSize = AuthDialog.SIZE_UNKNOWN;

    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mDescriptionView;
    protected ImageView mIconView;
    @VisibleForTesting protected TextView mErrorView;
    @VisibleForTesting Button mNegativeButton;
    @VisibleForTesting Button mPositiveButton;
    @VisibleForTesting Button mTryAgainButton;

    // Measurements when biometric view is showing text, buttons, etc.
    private int mMediumHeight;
    private int mMediumWidth;

    private Callback mCallback;
    protected @BiometricState int mState;

    private float mIconOriginalY;

    protected boolean mDialogSizeAnimating;

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

    private final Runnable mResetErrorRunnable = () -> {
        updateState(getStateForAfterError());
        handleResetAfterError();
    };

    private final Runnable mResetHelpRunnable = () -> {
        updateState(STATE_AUTHENTICATING);
        handleResetAfterHelp();
    };

    private final OnClickListener mBackgroundClickListener = (view) -> {
        if (mState == STATE_AUTHENTICATED) {
            Log.w(TAG, "Ignoring background click after authenticated");
            return;
        } else if (mSize == AuthDialog.SIZE_SMALL) {
            Log.w(TAG, "Ignoring background click during small dialog");
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
    }

    public void setPanelController(AuthPanelController panelController) {
        mPanelController = panelController;
    }

    public void setBiometricPromptBundle(Bundle bundle) {
        mBundle = bundle;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void setBackgroundView(View backgroundView) {
        backgroundView.setOnClickListener(mBackgroundClickListener);
    }

    public void setRequireConfirmation(boolean requireConfirmation) {
        mRequireConfirmation = requireConfirmation;
    }

    @VisibleForTesting
    void updateSize(@AuthDialog.DialogSize int newSize) {
        Log.v(TAG, "Current: " + mSize + " New: " + newSize);
        if (newSize == AuthDialog.SIZE_SMALL) {
            mTitleView.setVisibility(View.GONE);
            mSubtitleView.setVisibility(View.GONE);
            mDescriptionView.setVisibility(View.GONE);
            mErrorView.setVisibility(View.GONE);
            mNegativeButton.setVisibility(View.GONE);

            final float iconPadding = getResources()
                    .getDimension(R.dimen.biometric_dialog_icon_padding);
            mIconView.setY(getHeight() - mIconView.getHeight() - iconPadding);

            final int newHeight = mIconView.getHeight() + 2 * (int) iconPadding;
            mPanelController.updateForContentDimensions(mMediumWidth, newHeight,
                    false /* animate */);

            mSize = newSize;
        } else if (mSize == AuthDialog.SIZE_SMALL && newSize == AuthDialog.SIZE_MEDIUM) {
            if (mDialogSizeAnimating) {
                return;
            }
            mDialogSizeAnimating = true;

            // Animate the icon back to original position
            final ValueAnimator iconAnimator =
                    ValueAnimator.ofFloat(mIconView.getY(), mIconOriginalY);
            iconAnimator.addUpdateListener((animation) -> {
                mIconView.setY((float) animation.getAnimatedValue());
            });

            // Animate the text
            final ValueAnimator opacityAnimator = ValueAnimator.ofFloat(0, 1);
            opacityAnimator.setDuration(AuthDialog.ANIMATE_DURATION_MS);
            opacityAnimator.addUpdateListener((animation) -> {
                final float opacity = (float) animation.getAnimatedValue();

                mTitleView.setAlpha(opacity);
                mErrorView.setAlpha(opacity);
                mNegativeButton.setAlpha(opacity);
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
            as.setDuration(AuthDialog.ANIMATE_DURATION_MS);
            as.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    mTitleView.setVisibility(View.VISIBLE);
                    mErrorView.setVisibility(View.VISIBLE);
                    mNegativeButton.setVisibility(View.VISIBLE);
                    mTryAgainButton.setVisibility(View.VISIBLE);

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
                }
            });

            as.play(iconAnimator).with(opacityAnimator);
            as.start();
            // Animate the panel
            mPanelController.updateForContentDimensions(mMediumWidth, mMediumHeight,
                    true /* animate */);
        } else if (newSize == AuthDialog.SIZE_MEDIUM) {
            mPanelController.updateForContentDimensions(mMediumWidth, mMediumHeight,
                    false /* animate */);
            mSize = newSize;
        } else {
            Log.e(TAG, "Unknown transition from: " + mSize + " to: " + newSize);
        }
    }

    public void updateState(@BiometricState int newState) {
        Log.v(TAG, "newState: " + newState);
        switch (newState) {
            case STATE_AUTHENTICATING_ANIMATING_IN:
            case STATE_AUTHENTICATING:
                removePendingAnimations();
                if (mRequireConfirmation) {
                    mPositiveButton.setEnabled(false);
                    mPositiveButton.setVisibility(View.VISIBLE);
                }
                break;

            case STATE_AUTHENTICATED:
                if (mSize != AuthDialog.SIZE_SMALL) {
                    mPositiveButton.setVisibility(View.GONE);
                    mNegativeButton.setVisibility(View.GONE);
                    mErrorView.setVisibility(View.INVISIBLE);
                }
                mHandler.postDelayed(() -> mCallback.onAction(Callback.ACTION_AUTHENTICATED),
                        getDelayAfterAuthenticatedDurationMs());
                break;

            case STATE_PENDING_CONFIRMATION:
                removePendingAnimations();
                mNegativeButton.setText(R.string.cancel);
                mNegativeButton.setContentDescription(getResources().getString(R.string.cancel));
                mPositiveButton.setEnabled(true);
                mErrorView.setTextColor(mTextColorHint);
                mErrorView.setText(R.string.biometric_dialog_tap_confirm);
                mErrorView.setVisibility(View.VISIBLE);
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

    public void onHelp(String help) {
        if (mSize != AuthDialog.SIZE_MEDIUM) {
            return;
        }
        showTemporaryMessage(help, mResetHelpRunnable);
        updateState(STATE_HELP);
    }

    private void setTextOrHide(TextView view, String string) {
        if (TextUtils.isEmpty(string)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(string);
        }
    }

    private void setText(TextView view, String string) {
        view.setText(string);
    }

    // Remove all pending icon and text animations
    private void removePendingAnimations() {
        mHandler.removeCallbacks(mResetHelpRunnable);
        mHandler.removeCallbacks(mResetErrorRunnable);
    }

    private void showTemporaryMessage(String message, Runnable resetMessageRunnable) {
        removePendingAnimations();
        mErrorView.setText(message);
        mErrorView.setTextColor(mTextColorError);
        mErrorView.setVisibility(View.VISIBLE);
        mHandler.postDelayed(resetMessageRunnable, BiometricPrompt.HIDE_DIALOG_DELAY);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        initializeViews();
    }

    @VisibleForTesting
    void initializeViews() {
        mTitleView = mInjector.getTitleView();
        mSubtitleView = mInjector.getSubtitleView();
        mDescriptionView = mInjector.getDescriptionView();
        mIconView = mInjector.getIconView();
        mErrorView = mInjector.getErrorView();
        mNegativeButton = mInjector.getNegativeButton();
        mPositiveButton = mInjector.getPositiveButton();
        mTryAgainButton = mInjector.getTryAgainButton();

        mNegativeButton.setOnClickListener((view) -> {
            if (mState == STATE_PENDING_CONFIRMATION) {
                mCallback.onAction(Callback.ACTION_USER_CANCELED);
            } else {
                mCallback.onAction(Callback.ACTION_BUTTON_NEGATIVE);
            }
        });

        mPositiveButton.setOnClickListener((view) -> {
            updateState(STATE_AUTHENTICATED);
        });

        mTryAgainButton.setOnClickListener((view) -> {
            updateState(STATE_AUTHENTICATING);
            mCallback.onAction(Callback.ACTION_BUTTON_TRY_AGAIN);
            mTryAgainButton.setVisibility(View.GONE);
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setText(mTitleView, mBundle.getString(BiometricPrompt.KEY_TITLE));
        setText(mNegativeButton, mBundle.getString(BiometricPrompt.KEY_NEGATIVE_TEXT));

        setTextOrHide(mSubtitleView, mBundle.getString(BiometricPrompt.KEY_SUBTITLE));
        setTextOrHide(mDescriptionView, mBundle.getString(BiometricPrompt.KEY_DESCRIPTION));

        updateState(STATE_AUTHENTICATING_ANIMATING_IN);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int newWidth = Math.min(width, height);

        int totalHeight = 0;
        final int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            final View child = getChildAt(i);

            if (child.getId() == R.id.biometric_icon) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            } else if (child.getId() == R.id.button_bar) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getLayoutParams().height,
                                MeasureSpec.EXACTLY));
            } else {
                child.measure(
                        MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }
            totalHeight += child.getMeasuredHeight();
        }

        // Use the new width so it's centered horizontally
        setMeasuredDimension(newWidth, totalHeight);

        mMediumHeight = totalHeight;
        mMediumWidth = getMeasuredWidth();
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Start with initial size only once. Subsequent layout changes don't matter since we
        // only care about the initial icon position.
        if (mIconOriginalY == 0) {
            mIconOriginalY = mIconView.getY();
            updateSize(mRequireConfirmation ? AuthDialog.SIZE_MEDIUM
                    : AuthDialog.SIZE_SMALL);
        }
    }
}
