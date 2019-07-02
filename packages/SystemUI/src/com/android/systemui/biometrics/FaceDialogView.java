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

package com.android.systemui.biometrics;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.systemui.R;

/**
 * This class loads the view for the system-provided dialog. The view consists of:
 * Application Icon, Title, Subtitle, Description, Biometric Icon, Error/Help message area,
 * and positive/negative buttons.
 */
public class FaceDialogView extends BiometricDialogView {

    private static final String TAG = "FaceDialogView";
    private static final String KEY_DIALOG_SIZE = "key_dialog_size";
    private static final String KEY_DIALOG_ANIMATED_IN = "key_dialog_animated_in";

    private static final int HIDE_DIALOG_DELAY = 500; // ms
    private static final int IMPLICIT_Y_PADDING = 16; // dp
    private static final int GROW_DURATION = 150; // ms
    private static final int TEXT_ANIMATE_DISTANCE = 32; // dp

    private static final int SIZE_UNKNOWN = 0;
    private static final int SIZE_SMALL = 1;
    private static final int SIZE_GROWING = 2;
    private static final int SIZE_BIG = 3;

    private int mSize;
    private float mIconOriginalY;
    private DialogOutlineProvider mOutlineProvider = new DialogOutlineProvider();
    private IconController mIconController;
    private boolean mDialogAnimatedIn;

    /**
     * Class that handles the biometric icon animations.
     */
    private final class IconController extends Animatable2.AnimationCallback {

        private boolean mLastPulseDirection; // false = dark to light, true = light to dark

        int mState;

        IconController() {
            mState = STATE_IDLE;
        }

        public void animateOnce(int iconRes) {
            animateIcon(iconRes, false);
        }

        public void showStatic(int iconRes) {
            mBiometricIcon.setImageDrawable(mContext.getDrawable(iconRes));
        }

        public void startPulsing() {
            mLastPulseDirection = false;
            animateIcon(R.drawable.face_dialog_pulse_dark_to_light, true);
        }

        public void showIcon(int iconRes) {
            final Drawable drawable = mContext.getDrawable(iconRes);
            mBiometricIcon.setImageDrawable(drawable);
        }

        private void animateIcon(int iconRes, boolean repeat) {
            final AnimatedVectorDrawable icon =
                    (AnimatedVectorDrawable) mContext.getDrawable(iconRes);
            mBiometricIcon.setImageDrawable(icon);
            icon.forceAnimationOnUI();
            if (repeat) {
                icon.registerAnimationCallback(this);
            }
            icon.start();
        }

        private void pulseInNextDirection() {
            int iconRes = mLastPulseDirection ? R.drawable.face_dialog_pulse_dark_to_light
                    : R.drawable.face_dialog_pulse_light_to_dark;
            animateIcon(iconRes, true /* repeat */);
            mLastPulseDirection = !mLastPulseDirection;
        }

        @Override
        public void onAnimationEnd(Drawable drawable) {
            super.onAnimationEnd(drawable);

            if (mState == STATE_AUTHENTICATING) {
                // Still authenticating, pulse the icon
                pulseInNextDirection();
            }
        }
    }

    private final class DialogOutlineProvider extends ViewOutlineProvider {

        float mY;

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(
                    0 /* left */,
                    (int) mY, /* top */
                    mDialog.getWidth() /* right */,
                    mDialog.getBottom(), /* bottom */
                    getResources().getDimension(R.dimen.biometric_dialog_corner_size));
        }

        int calculateSmall() {
            final float padding = dpToPixels(IMPLICIT_Y_PADDING);
            return mDialog.getHeight() - mBiometricIcon.getHeight() - 2 * (int) padding;
        }

        void setOutlineY(float y) {
            mY = y;
        }
    }

    private final Runnable mErrorToIdleAnimationRunnable = () -> {
        updateState(STATE_IDLE);
        mErrorText.setVisibility(View.INVISIBLE);
    };

    public FaceDialogView(Context context,
            DialogViewCallback callback) {
        super(context, callback);
        mIconController = new IconController();
    }

    private void updateSize(int newSize) {
        final float padding = dpToPixels(IMPLICIT_Y_PADDING);
        final float iconSmallPositionY = mDialog.getHeight() - mBiometricIcon.getHeight() - padding;

        if (newSize == SIZE_SMALL) {
            // These fields are required and/or always hold a spot on the UI, so should be set to
            // INVISIBLE so they keep their position
            mTitleText.setVisibility(View.INVISIBLE);
            mErrorText.setVisibility(View.INVISIBLE);
            mNegativeButton.setVisibility(View.INVISIBLE);

            // These fields are optional, so set them to gone or invisible depending on their
            // usage. If they're empty, they're already set to GONE in BiometricDialogView.
            if (!TextUtils.isEmpty(mSubtitleText.getText())) {
                mSubtitleText.setVisibility(View.INVISIBLE);
            }
            if (!TextUtils.isEmpty(mDescriptionText.getText())) {
                mDescriptionText.setVisibility(View.INVISIBLE);
            }

            // Move the biometric icon to the small spot
            mBiometricIcon.setY(iconSmallPositionY);

            // Clip the dialog to the small size
            mDialog.setOutlineProvider(mOutlineProvider);
            mOutlineProvider.setOutlineY(mOutlineProvider.calculateSmall());

            mDialog.setClipToOutline(true);
            mDialog.invalidateOutline();

            mSize = newSize;
        } else if (mSize == SIZE_SMALL && newSize == SIZE_BIG) {
            mSize = SIZE_GROWING;

            // Animate the outline
            final ValueAnimator outlineAnimator =
                    ValueAnimator.ofFloat(mOutlineProvider.calculateSmall(), 0);
            outlineAnimator.addUpdateListener((animation) -> {
                final float y = (float) animation.getAnimatedValue();
                mOutlineProvider.setOutlineY(y);
                mDialog.invalidateOutline();
            });

            // Animate the icon back to original big position
            final ValueAnimator iconAnimator =
                    ValueAnimator.ofFloat(iconSmallPositionY, mIconOriginalY);
            iconAnimator.addUpdateListener((animation) -> {
                final float y = (float) animation.getAnimatedValue();
                mBiometricIcon.setY(y);
            });

            // Animate the error text so it slides up with the icon
            final ValueAnimator textSlideAnimator =
                    ValueAnimator.ofFloat(dpToPixels(TEXT_ANIMATE_DISTANCE), 0);
            textSlideAnimator.addUpdateListener((animation) -> {
                final float y = (float) animation.getAnimatedValue();
                mErrorText.setTranslationY(y);
            });

            // Opacity animator for things that should fade in (title, subtitle, details, negative
            // button)
            final ValueAnimator opacityAnimator = ValueAnimator.ofFloat(0, 1);
            opacityAnimator.addUpdateListener((animation) -> {
                final float opacity = (float) animation.getAnimatedValue();

                // These fields are required and/or always hold a spot on the UI
                mTitleText.setAlpha(opacity);
                mErrorText.setAlpha(opacity);
                mNegativeButton.setAlpha(opacity);
                mTryAgainButton.setAlpha(opacity);

                // These fields are optional, so only animate them if they're supposed to be showing
                if (!TextUtils.isEmpty(mSubtitleText.getText())) {
                    mSubtitleText.setAlpha(opacity);
                }
                if (!TextUtils.isEmpty(mDescriptionText.getText())) {
                    mDescriptionText.setAlpha(opacity);
                }
            });

            // Choreograph together
            final AnimatorSet as = new AnimatorSet();
            as.setDuration(GROW_DURATION);
            as.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    // Set the visibility of opacity-animating views back to VISIBLE
                    mTitleText.setVisibility(View.VISIBLE);
                    mErrorText.setVisibility(View.VISIBLE);
                    mNegativeButton.setVisibility(View.VISIBLE);
                    mTryAgainButton.setVisibility(View.VISIBLE);

                    if (!TextUtils.isEmpty(mSubtitleText.getText())) {
                        mSubtitleText.setVisibility(View.VISIBLE);
                    }
                    if (!TextUtils.isEmpty(mDescriptionText.getText())) {
                        mDescriptionText.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    mSize = SIZE_BIG;
                }
            });
            as.play(outlineAnimator).with(iconAnimator).with(opacityAnimator)
                    .with(textSlideAnimator);
            as.start();
        } else if (mSize == SIZE_BIG) {
            mDialog.setClipToOutline(false);
            mDialog.invalidateOutline();

            mBiometricIcon.setY(mIconOriginalY);

            mSize = newSize;
        }
    }

    @Override
    public void onSaveState(Bundle bundle) {
        super.onSaveState(bundle);
        bundle.putInt(KEY_DIALOG_SIZE, mSize);
        bundle.putBoolean(KEY_DIALOG_ANIMATED_IN, mDialogAnimatedIn);
    }


    @Override
    protected void handleResetMessage() {
        mErrorText.setText(getHintStringResourceId());
        mErrorText.setContentDescription(mContext.getString(getHintStringResourceId()));
        mErrorText.setTextColor(mTextColor);
        if (getState() == STATE_AUTHENTICATING) {
            mErrorText.setVisibility(View.VISIBLE);
        } else {
            mErrorText.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void restoreState(Bundle bundle) {
        super.restoreState(bundle);
        // Keep in mind that this happens before onAttachedToWindow()
        mSize = bundle.getInt(KEY_DIALOG_SIZE);
        mDialogAnimatedIn = bundle.getBoolean(KEY_DIALOG_ANIMATED_IN);
    }

    /**
     * Do small/big layout here instead of onAttachedToWindow, since:
     * 1) We need the big layout to be measured, etc for small -> big animation
     * 2) We need the dialog measurements to know where to move the biometric icon to
     *
     * BiometricDialogView already sets the views to their default big state, so here we only
     * need to hide the ones that are unnecessary.
     */
    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (mIconOriginalY == 0) {
            mIconOriginalY = mBiometricIcon.getY();
        }

        // UNKNOWN means size hasn't been set yet. First time we create the dialog.
        // onLayout can happen when visibility of views change (during animation, etc).
        if (mSize != SIZE_UNKNOWN) {
            // Probably not the cleanest way to do this, but since dialog is big by default,
            // and small dialogs can persist across orientation changes, we need to set it to
            // small size here again.
            if (mSize == SIZE_SMALL) {
                updateSize(SIZE_SMALL);
            }
            return;
        }

        // If we don't require confirmation, show the small dialog first (until errors occur).
        if (!requiresConfirmation()) {
            updateSize(SIZE_SMALL);
        } else {
            updateSize(SIZE_BIG);
        }
    }

    @Override
    public void onErrorReceived(String error) {
        super.onErrorReceived(error);
        // All error messages will cause the dialog to go from small -> big. Error messages
        // are messages such as lockout, auth failed, etc.
        if (mSize == SIZE_SMALL) {
            updateSize(SIZE_BIG);
        }
    }

    @Override
    public void onAuthenticationFailed(String message) {
        super.onAuthenticationFailed(message);
        showTryAgainButton(true);
    }

    @Override
    public void showTryAgainButton(boolean show) {
        if (show && mSize == SIZE_SMALL) {
            // Do not call super, we will nicely animate the alpha together with the rest
            // of the elements in here.
            updateSize(SIZE_BIG);
        } else {
            if (show) {
                mTryAgainButton.setVisibility(View.VISIBLE);
            } else {
                mTryAgainButton.setVisibility(View.GONE);
            }
        }

        if (show) {
            mPositiveButton.setVisibility(View.GONE);
        }
    }

    @Override
    protected int getHintStringResourceId() {
        return R.string.face_dialog_looking_for_face;
    }

    @Override
    protected int getAuthenticatedAccessibilityResourceId() {
        if (mRequireConfirmation) {
            return com.android.internal.R.string.face_authenticated_confirmation_required;
        } else {
            return com.android.internal.R.string.face_authenticated_no_confirmation_required;
        }
    }

    @Override
    protected int getIconDescriptionResourceId() {
        return R.string.accessibility_face_dialog_face_icon;
    }

    @Override
    protected void updateIcon(int oldState, int newState) {
        mIconController.mState = newState;

        if (newState == STATE_AUTHENTICATING) {
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            if (mDialogAnimatedIn) {
                mIconController.startPulsing();
                mErrorText.setVisibility(View.VISIBLE);
            } else {
                mIconController.showIcon(R.drawable.face_dialog_pulse_dark_to_light);
            }
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating));
        } else if (oldState == STATE_PENDING_CONFIRMATION && newState == STATE_AUTHENTICATED) {
            mIconController.animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_confirmed));
        } else if (oldState == STATE_ERROR && newState == STATE_IDLE) {
            mIconController.animateOnce(R.drawable.face_dialog_error_to_idle);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_idle));
        } else if (oldState == STATE_ERROR && newState == STATE_AUTHENTICATED) {
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            mIconController.animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == STATE_ERROR) {
            // It's easier to only check newState and gate showing the animation on the
            // mErrorToIdleAnimationRunnable as a proxy, than add a ton of extra state. For example,
            // we may go from error -> error due to configuration change which is valid and we
            // should show the animation, or we can go from error -> error by receiving repeated
            // acquire messages in which case we do not want to repeatedly start the animation.
            if (!mHandler.hasCallbacks(mErrorToIdleAnimationRunnable)) {
                mIconController.animateOnce(R.drawable.face_dialog_dark_to_error);
                mHandler.postDelayed(mErrorToIdleAnimationRunnable,
                        BiometricPrompt.HIDE_DIALOG_DELAY);
            }
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
            mIconController.animateOnce(R.drawable.face_dialog_dark_to_checkmark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == STATE_PENDING_CONFIRMATION) {
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            mIconController.animateOnce(R.drawable.face_dialog_wink_from_dark);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated));
        } else if (newState == STATE_IDLE) {
            mIconController.showStatic(R.drawable.face_dialog_idle_static);
            mBiometricIcon.setContentDescription(mContext.getString(
                    R.string.biometric_dialog_face_icon_description_idle));
        } else {
            Log.w(TAG, "Unknown animation from " + oldState + " -> " + newState);
        }

        // Note that this must be after the newState == STATE_ERROR check above since this affects
        // the logic.
        if (oldState == STATE_ERROR && newState == STATE_ERROR) {
            // Keep the error icon and text around for a while longer if we keep receiving
            // STATE_ERROR
            mHandler.removeCallbacks(mErrorToIdleAnimationRunnable);
            mHandler.postDelayed(mErrorToIdleAnimationRunnable, BiometricPrompt.HIDE_DIALOG_DELAY);
        }
    }

    @Override
    public void onDialogAnimatedIn() {
        mDialogAnimatedIn = true;
        mIconController.startPulsing();
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return HIDE_DIALOG_DELAY;
    }

    @Override
    protected boolean shouldGrayAreaDismissDialog() {
        if (mSize == SIZE_SMALL) {
            return false;
        }
        return true;
    }

    private float dpToPixels(float dp) {
        return dp * ((float) mContext.getResources().getDisplayMetrics().densityDpi
                / DisplayMetrics.DENSITY_DEFAULT);
    }

    private float pixelsToDp(float pixels) {
        return pixels / ((float) mContext.getResources().getDisplayMetrics().densityDpi
                / DisplayMetrics.DENSITY_DEFAULT);
    }
}
