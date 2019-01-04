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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.util.leak.RotationUtils;

/**
 * Abstract base class. Shows a dialog for BiometricPrompt.
 */
public abstract class BiometricDialogView extends LinearLayout {

    private static final String TAG = "BiometricDialogView";

    private static final int ANIMATION_DURATION_SHOW = 250; // ms
    private static final int ANIMATION_DURATION_AWAY = 350; // ms

    private static final int MSG_CLEAR_MESSAGE = 1;

    protected static final int STATE_NONE = 0;
    protected static final int STATE_AUTHENTICATING = 1;
    protected static final int STATE_ERROR = 2;
    protected static final int STATE_PENDING_CONFIRMATION = 3;
    protected static final int STATE_AUTHENTICATED = 4;

    private final IBinder mWindowToken = new Binder();
    private final Interpolator mLinearOutSlowIn;
    private final WindowManager mWindowManager;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final float mAnimationTranslationOffset;
    private final int mErrorColor;
    private final int mTextColor;
    private final float mDialogWidth;
    private final DialogViewCallback mCallback;

    private ViewGroup mLayout;
    private final Button mPositiveButton;
    private final Button mNegativeButton;
    private final TextView mErrorText;
    private Bundle mBundle;
    private final LinearLayout mDialog;
    private int mLastState;
    private boolean mAnimatingAway;
    private boolean mWasForceRemoved;
    private boolean mSkipIntro;
    protected boolean mRequireConfirmation;
    private int mUserId; // used to determine if we should show work background

    private boolean mPendingShowTryAgain;
    private boolean mPendingShowConfirm;

    protected abstract int getHintStringResourceId();
    protected abstract int getAuthenticatedAccessibilityResourceId();
    protected abstract int getIconDescriptionResourceId();
    protected abstract Drawable getAnimationForTransition(int oldState, int newState);
    protected abstract boolean shouldAnimateForTransition(int oldState, int newState);
    protected abstract int getDelayAfterAuthenticatedDurationMs();

    private final Runnable mShowAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            mLayout.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION_SHOW)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
            mDialog.animate()
                    .translationY(0)
                    .setDuration(ANIMATION_DURATION_SHOW)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
        }
    };

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_CLEAR_MESSAGE:
                    handleClearMessage();
                    break;
                default:
                    Log.e(TAG, "Unhandled message: " + msg.what);
                    break;
            }
        }
    };

    public BiometricDialogView(Context context, DialogViewCallback callback) {
        super(context);
        mCallback = callback;
        mLinearOutSlowIn = Interpolators.LINEAR_OUT_SLOW_IN;
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mDevicePolicyManager = mContext.getSystemService(DevicePolicyManager.class);
        mAnimationTranslationOffset = getResources()
                .getDimension(R.dimen.biometric_dialog_animation_translation_offset);

        TypedArray array = getContext().obtainStyledAttributes(
                new int[]{android.R.attr.colorError, android.R.attr.textColorSecondary});
        mErrorColor = array.getColor(0, 0);
        mTextColor = array.getColor(1, 0);
        array.recycle();

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDialogWidth = Math.min(metrics.widthPixels, metrics.heightPixels);

        // Create the dialog
        LayoutInflater factory = LayoutInflater.from(getContext());
        mLayout = (ViewGroup) factory.inflate(R.layout.biometric_dialog, this, false);
        addView(mLayout);

        mDialog = mLayout.findViewById(R.id.dialog);

        mErrorText = mLayout.findViewById(R.id.error);

        mLayout.setOnKeyListener(new View.OnKeyListener() {
            boolean downPressed = false;
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && downPressed == false) {
                    downPressed = true;
                } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    downPressed = false;
                } else if (event.getAction() == KeyEvent.ACTION_UP && downPressed == true) {
                    downPressed = false;
                    mCallback.onUserCanceled();
                }
                return true;
            }
        });

        final View space = mLayout.findViewById(R.id.space);
        final View leftSpace = mLayout.findViewById(R.id.left_space);
        final View rightSpace = mLayout.findViewById(R.id.right_space);
        final ImageView icon = mLayout.findViewById(R.id.biometric_icon);
        final Button tryAgain = mLayout.findViewById(R.id.button_try_again);
        mNegativeButton = mLayout.findViewById(R.id.button2);
        mPositiveButton = mLayout.findViewById(R.id.button1);

        icon.setContentDescription(getResources().getString(getIconDescriptionResourceId()));

        setDismissesDialog(space);
        setDismissesDialog(leftSpace);
        setDismissesDialog(rightSpace);

        mNegativeButton.setOnClickListener((View v) -> {
            mCallback.onNegativePressed();
        });

        mPositiveButton.setOnClickListener((View v) -> {
            updateState(STATE_AUTHENTICATED);
            mHandler.postDelayed(() -> {
                mCallback.onPositivePressed();
            }, getDelayAfterAuthenticatedDurationMs());
        });

        tryAgain.setOnClickListener((View v) -> {
            showTryAgainButton(false /* show */);
            mCallback.onTryAgainPressed();
        });

        mLayout.setFocusableInTouchMode(true);
        mLayout.requestFocus();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mErrorText.setText(getHintStringResourceId());

        final TextView title = mLayout.findViewById(R.id.title);
        final TextView subtitle = mLayout.findViewById(R.id.subtitle);
        final TextView description = mLayout.findViewById(R.id.description);
        final ImageView backgroundView = mLayout.findViewById(R.id.background);

        if (mUserManager.isManagedProfile(mUserId)) {
            final Drawable image = getResources().getDrawable(R.drawable.work_challenge_background,
                    mContext.getTheme());
            image.setColorFilter(mDevicePolicyManager.getOrganizationColorForUser(mUserId),
                    PorterDuff.Mode.DARKEN);
            backgroundView.setImageDrawable(image);
        } else {
            backgroundView.setImageDrawable(null);
            backgroundView.setBackgroundColor(R.color.biometric_dialog_dim_color);
        }

        mNegativeButton.setVisibility(View.VISIBLE);
        mErrorText.setVisibility(View.VISIBLE);

        if (RotationUtils.getRotation(mContext) != RotationUtils.ROTATION_NONE) {
            mDialog.getLayoutParams().width = (int) mDialogWidth;
        }

        mLastState = STATE_NONE;
        updateState(STATE_AUTHENTICATING);

        CharSequence titleText = mBundle.getCharSequence(BiometricPrompt.KEY_TITLE);

        title.setText(titleText);
        title.setSelected(true);

        final CharSequence subtitleText = mBundle.getCharSequence(BiometricPrompt.KEY_SUBTITLE);
        if (TextUtils.isEmpty(subtitleText)) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(subtitleText);
        }

        final CharSequence descriptionText =
                mBundle.getCharSequence(BiometricPrompt.KEY_DESCRIPTION);
        if (TextUtils.isEmpty(descriptionText)) {
            description.setVisibility(View.GONE);
        } else {
            description.setVisibility(View.VISIBLE);
            description.setText(descriptionText);
        }

        mNegativeButton.setText(mBundle.getCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT));

        showTryAgainButton(mPendingShowTryAgain);
        showConfirmationButton(mPendingShowConfirm);

        if (mWasForceRemoved || mSkipIntro) {
            // Show the dialog immediately
            mLayout.animate().cancel();
            mDialog.animate().cancel();
            mDialog.setAlpha(1.0f);
            mDialog.setTranslationY(0);
            mLayout.setAlpha(1.0f);
        } else {
            // Dim the background and slide the dialog up
            mDialog.setTranslationY(mAnimationTranslationOffset);
            mLayout.setAlpha(0f);
            postOnAnimation(mShowAnimationRunnable);
        }
        mWasForceRemoved = false;
        mSkipIntro = false;
    }

    protected void updateIcon(int lastState, int newState) {
        final Drawable icon = getAnimationForTransition(lastState, newState);
        if (icon == null) {
            Log.e(TAG, "Animation not found");
            return;
        }

        final AnimatedVectorDrawable animation = icon instanceof AnimatedVectorDrawable
                ? (AnimatedVectorDrawable) icon
                : null;

        final ImageView imageView = getLayout().findViewById(R.id.biometric_icon);
        imageView.setImageDrawable(icon);

        if (animation != null && shouldAnimateForTransition(lastState, newState)) {
            animation.forceAnimationOnUI();
            animation.start();
        }
    }

    private void setDismissesDialog(View v) {
        v.setClickable(true);
        v.setOnTouchListener((View view, MotionEvent event) -> {
            if (mLastState != STATE_AUTHENTICATED) {
                mCallback.onUserCanceled();
            }
            return true;
        });
    }

    public void startDismiss() {
        mAnimatingAway = true;

        // This is where final cleanup should occur.
        final Runnable endActionRunnable = new Runnable() {
            @Override
            public void run() {
                mWindowManager.removeView(BiometricDialogView.this);
                mAnimatingAway = false;
                // Set the icons / text back to normal state
                handleClearMessage();
                showTryAgainButton(false /* show */);
                mPendingShowTryAgain = false;
                mPendingShowConfirm = false;
                updateState(STATE_NONE);
            }
        };

        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                mLayout.animate()
                        .alpha(0f)
                        .setDuration(ANIMATION_DURATION_AWAY)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
                mDialog.animate()
                        .translationY(mAnimationTranslationOffset)
                        .setDuration(ANIMATION_DURATION_AWAY)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .withEndAction(endActionRunnable)
                        .start();
            }
        });
    }

    /**
     * Force remove the window, cancelling any animation that's happening. This should only be
     * called if we want to quickly show the dialog again (e.g. on rotation). Calling this method
     * will cause the dialog to show without an animation the next time it's attached.
     */
    public void forceRemove() {
        mLayout.animate().cancel();
        mDialog.animate().cancel();
        mWindowManager.removeView(BiometricDialogView.this);
        mAnimatingAway = false;
        mWasForceRemoved = true;
    }

    /**
     * Skip the intro animation
     */
    public void setSkipIntro(boolean skip) {
        mSkipIntro = skip;
    }

    public boolean isAnimatingAway() {
        return mAnimatingAway;
    }

    public void setBundle(Bundle bundle) {
        mBundle = bundle;
    }

    public void setRequireConfirmation(boolean requireConfirmation) {
        mRequireConfirmation = requireConfirmation;
    }

    public boolean requiresConfirmation() {
        return mRequireConfirmation;
    }

    public void showConfirmationButton(boolean show) {
        if (show) {
            updateState(STATE_PENDING_CONFIRMATION);
            mPositiveButton.setVisibility(View.VISIBLE);
        } else {
            mPositiveButton.setVisibility(View.GONE);
        }
    }

    public void setUserId(int userId) {
        mUserId = userId;
    }

    public ViewGroup getLayout() {
        return mLayout;
    }

    // Clears the temporary message and shows the help message.
    private void handleClearMessage() {
        updateState(STATE_AUTHENTICATING);
        mErrorText.setText(getHintStringResourceId());
        mErrorText.setTextColor(mTextColor);
    }

    // Shows an error/help message
    private void showTemporaryMessage(String message) {
        mHandler.removeMessages(MSG_CLEAR_MESSAGE);
        updateState(STATE_ERROR);
        mErrorText.setText(message);
        mErrorText.setTextColor(mErrorColor);
        mErrorText.setContentDescription(message);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CLEAR_MESSAGE),
                BiometricPrompt.HIDE_DIALOG_DELAY);
    }

    public void clearTemporaryMessage() {
        mHandler.removeMessages(MSG_CLEAR_MESSAGE);
        mHandler.obtainMessage(MSG_CLEAR_MESSAGE).sendToTarget();
    }

    public void showHelpMessage(String message) {
        showTemporaryMessage(message);
    }

    public void showErrorMessage(String error) {
        showTemporaryMessage(error);
        showTryAgainButton(false /* show */);
        mCallback.onErrorShown();
    }

    public void updateState(int newState) {
        if (newState == STATE_PENDING_CONFIRMATION) {
            mErrorText.setVisibility(View.INVISIBLE);
        } else if (newState == STATE_AUTHENTICATED) {
            mPositiveButton.setVisibility(View.GONE);
            mNegativeButton.setVisibility(View.GONE);
            mErrorText.setVisibility(View.INVISIBLE);
        }

        updateIcon(mLastState, newState);
        mLastState = newState;
    }

    public void showTryAgainButton(boolean show) {
        final Button tryAgain = mLayout.findViewById(R.id.button_try_again);
        if (show) {
            tryAgain.setVisibility(View.VISIBLE);
        } else {
            tryAgain.setVisibility(View.GONE);
        }
    }

    // Set the state before the window is attached, so we know if the dialog should be started
    // with or without the button. This is because there's no good onPause signal
    public void setPendingTryAgain(boolean show) {
        mPendingShowTryAgain = show;
    }

    public void setPendingConfirm(boolean show) {
        mPendingShowConfirm = show;
    }

    public WindowManager.LayoutParams getLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("BiometricDialogView");
        lp.token = mWindowToken;
        return lp;
    }
}
