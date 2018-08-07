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

package com.android.systemui.fingerprint;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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

/**
 * This class loads the view for the system-provided dialog. The view consists of:
 * Application Icon, Title, Subtitle, Description, Fingerprint Icon, Error/Help message area,
 * and positive/negative buttons.
 */
public class FingerprintDialogView extends LinearLayout {

    private static final String TAG = "FingerprintDialogView";

    private static final int ANIMATION_DURATION_SHOW = 250; // ms
    private static final int ANIMATION_DURATION_AWAY = 350; // ms

    private static final int STATE_NONE = 0;
    private static final int STATE_FINGERPRINT = 1;
    private static final int STATE_FINGERPRINT_ERROR = 2;
    private static final int STATE_FINGERPRINT_AUTHENTICATED = 3;

    private final IBinder mWindowToken = new Binder();
    private final Interpolator mLinearOutSlowIn;
    private final WindowManager mWindowManager;
    private final float mAnimationTranslationOffset;
    private final int mErrorColor;
    private final int mTextColor;
    private final int mFingerprintColor;

    private ViewGroup mLayout;
    private final TextView mErrorText;
    private Handler mHandler;
    private Bundle mBundle;
    private final LinearLayout mDialog;
    private int mLastState;
    private boolean mAnimatingAway;
    private boolean mWasForceRemoved;

    private final float mDisplayWidth;

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

    public FingerprintDialogView(Context context, Handler handler) {
        super(context);
        mHandler = handler;
        mLinearOutSlowIn = Interpolators.LINEAR_OUT_SLOW_IN;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mAnimationTranslationOffset = getResources()
                .getDimension(R.dimen.fingerprint_dialog_animation_translation_offset);
        mErrorColor = Color.parseColor(
                getResources().getString(R.color.fingerprint_dialog_error_color));
        mTextColor = Color.parseColor(
                getResources().getString(R.color.fingerprint_dialog_text_light_color));
        mFingerprintColor = Color.parseColor(
                getResources().getString(R.color.fingerprint_dialog_fingerprint_color));

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDisplayWidth = metrics.widthPixels;

        // Create the dialog
        LayoutInflater factory = LayoutInflater.from(getContext());
        mLayout = (ViewGroup) factory.inflate(R.layout.fingerprint_dialog, this, false);
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
                    mHandler.obtainMessage(FingerprintDialogImpl.MSG_USER_CANCELED).sendToTarget();
                }
                return true;
            }
        });

        final View space = mLayout.findViewById(R.id.space);
        final View leftSpace = mLayout.findViewById(R.id.left_space);
        final View rightSpace = mLayout.findViewById(R.id.right_space);
        final Button negative = mLayout.findViewById(R.id.button2);
        final Button positive = mLayout.findViewById(R.id.button1);

        setDismissesDialog(space);
        setDismissesDialog(leftSpace);
        setDismissesDialog(rightSpace);

        negative.setOnClickListener((View v) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_BUTTON_NEGATIVE).sendToTarget();
        });

        positive.setOnClickListener((View v) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_BUTTON_POSITIVE).sendToTarget();
        });

        mLayout.setFocusableInTouchMode(true);
        mLayout.requestFocus();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        final TextView title = mLayout.findViewById(R.id.title);
        final TextView subtitle = mLayout.findViewById(R.id.subtitle);
        final TextView description = mLayout.findViewById(R.id.description);
        final Button negative = mLayout.findViewById(R.id.button2);
        final Button positive = mLayout.findViewById(R.id.button1);

        mDialog.getLayoutParams().width = (int) mDisplayWidth;

        mLastState = STATE_NONE;
        updateFingerprintIcon(STATE_FINGERPRINT);

        title.setText(mBundle.getCharSequence(BiometricPrompt.KEY_TITLE));
        title.setSelected(true);

        final CharSequence subtitleText = mBundle.getCharSequence(BiometricPrompt.KEY_SUBTITLE);
        if (TextUtils.isEmpty(subtitleText)) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(subtitleText);
        }

        final CharSequence descriptionText = mBundle.getCharSequence(BiometricPrompt.KEY_DESCRIPTION);
        if (TextUtils.isEmpty(descriptionText)) {
            description.setVisibility(View.GONE);
        } else {
            description.setVisibility(View.VISIBLE);
            description.setText(descriptionText);
        }

        negative.setText(mBundle.getCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT));

        final CharSequence positiveText =
                mBundle.getCharSequence(BiometricPrompt.KEY_POSITIVE_TEXT);
        positive.setText(positiveText); // needs to be set for marquee to work
        if (positiveText != null) {
            positive.setVisibility(View.VISIBLE);
        } else {
            positive.setVisibility(View.GONE);
        }

        if (!mWasForceRemoved) {
            // Dim the background and slide the dialog up
            mDialog.setTranslationY(mAnimationTranslationOffset);
            mLayout.setAlpha(0f);
            postOnAnimation(mShowAnimationRunnable);
        } else {
            // Show the dialog immediately
            mLayout.animate().cancel();
            mDialog.animate().cancel();
            mDialog.setAlpha(1.0f);
            mDialog.setTranslationY(0);
            mLayout.setAlpha(1.0f);
        }
        mWasForceRemoved = false;
    }

    private void setDismissesDialog(View v) {
        v.setClickable(true);
        v.setOnTouchListener((View view, MotionEvent event) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_HIDE_DIALOG, true /* userCanceled */)
                    .sendToTarget();
            return true;
        });
    }

    public void startDismiss() {
        mAnimatingAway = true;

        final Runnable endActionRunnable = new Runnable() {
            @Override
            public void run() {
                mWindowManager.removeView(FingerprintDialogView.this);
                mAnimatingAway = false;
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
        mWindowManager.removeView(FingerprintDialogView.this);
        mAnimatingAway = false;
        mWasForceRemoved = true;
    }

    public boolean isAnimatingAway() {
        return mAnimatingAway;
    }

    public void setBundle(Bundle bundle) {
        mBundle = bundle;
    }

    // Clears the temporary message and shows the help message.
    protected void resetMessage() {
        updateFingerprintIcon(STATE_FINGERPRINT);
        mErrorText.setText(R.string.fingerprint_dialog_touch_sensor);
        mErrorText.setTextColor(mTextColor);
    }

    // Shows an error/help message
    private void showTemporaryMessage(String message) {
        mHandler.removeMessages(FingerprintDialogImpl.MSG_CLEAR_MESSAGE);
        updateFingerprintIcon(STATE_FINGERPRINT_ERROR);
        mErrorText.setText(message);
        mErrorText.setTextColor(mErrorColor);
        mErrorText.setContentDescription(message);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(FingerprintDialogImpl.MSG_CLEAR_MESSAGE),
                BiometricPrompt.HIDE_DIALOG_DELAY);
    }

    public void showHelpMessage(String message) {
        showTemporaryMessage(message);
    }

    public void showErrorMessage(String error) {
        showTemporaryMessage(error);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(FingerprintDialogImpl.MSG_HIDE_DIALOG,
                false /* userCanceled */), BiometricPrompt.HIDE_DIALOG_DELAY);
    }

    private void updateFingerprintIcon(int newState) {
        Drawable icon  = getAnimationForTransition(mLastState, newState);

        if (icon == null) {
            Log.e(TAG, "Animation not found");
            return;
        }

        final AnimatedVectorDrawable animation = icon instanceof AnimatedVectorDrawable
                ? (AnimatedVectorDrawable) icon
                : null;

        final ImageView fingerprint_icon = mLayout.findViewById(R.id.fingerprint_icon);
        fingerprint_icon.setImageDrawable(icon);

        if (animation != null && shouldAnimateForTransition(mLastState, newState)) {
            animation.forceAnimationOnUI();
            animation.start();
        }

        mLastState = newState;
    }

    private boolean shouldAnimateForTransition(int oldState, int newState) {
        if (oldState == STATE_NONE && newState == STATE_FINGERPRINT) {
            return false;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_ERROR) {
            return true;
        } else if (oldState == STATE_FINGERPRINT_ERROR && newState == STATE_FINGERPRINT) {
            return true;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_AUTHENTICATED) {
            // TODO(b/77328470): add animation when fingerprint is authenticated
            return false;
        }
        return false;
    }

    private Drawable getAnimationForTransition(int oldState, int newState) {
        int iconRes;
        if (oldState == STATE_NONE && newState == STATE_FINGERPRINT) {
            iconRes = R.drawable.fingerprint_dialog_fp_to_error;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_ERROR) {
            iconRes = R.drawable.fingerprint_dialog_fp_to_error;
        } else if (oldState == STATE_FINGERPRINT_ERROR && newState == STATE_FINGERPRINT) {
            iconRes = R.drawable.fingerprint_dialog_error_to_fp;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_AUTHENTICATED) {
            // TODO(b/77328470): add animation when fingerprint is authenticated
            iconRes = R.drawable.fingerprint_dialog_error_to_fp;
        }
        else {
            return null;
        }
        return mContext.getDrawable(iconRes);
    }

    public WindowManager.LayoutParams getLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("FingerprintDialogView");
        lp.token = mWindowToken;
        return lp;
    }
}
