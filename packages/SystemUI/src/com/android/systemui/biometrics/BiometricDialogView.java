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

import static android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
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

    private static final String KEY_TRY_AGAIN_VISIBILITY = "key_try_again_visibility";
    private static final String KEY_CONFIRM_VISIBILITY = "key_confirm_visibility";
    private static final String KEY_CONFIRM_ENABLED = "key_confirm_enabled";
    private static final String KEY_STATE = "key_state";
    private static final String KEY_ERROR_TEXT_VISIBILITY = "key_error_text_visibility";
    private static final String KEY_ERROR_TEXT_STRING = "key_error_text_string";
    private static final String KEY_ERROR_TEXT_IS_TEMPORARY = "key_error_text_is_temporary";
    private static final String KEY_ERROR_TEXT_COLOR = "key_error_text_color";

    private static final int ANIMATION_DURATION_SHOW = 250; // ms
    private static final int ANIMATION_DURATION_AWAY = 350; // ms

    protected static final int MSG_RESET_MESSAGE = 1;

    protected static final int STATE_IDLE = 0;
    protected static final int STATE_AUTHENTICATING = 1;
    protected static final int STATE_ERROR = 2;
    protected static final int STATE_PENDING_CONFIRMATION = 3;
    protected static final int STATE_AUTHENTICATED = 4;

    private final AccessibilityManager mAccessibilityManager;
    private final IBinder mWindowToken = new Binder();
    private final Interpolator mLinearOutSlowIn;
    private final WindowManager mWindowManager;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final float mAnimationTranslationOffset;
    private final int mErrorColor;
    private final float mDialogWidth;
    protected final DialogViewCallback mCallback;

    protected final ViewGroup mLayout;
    protected final LinearLayout mDialog;
    protected final TextView mTitleText;
    protected final TextView mSubtitleText;
    protected final TextView mDescriptionText;
    protected final ImageView mBiometricIcon;
    protected final TextView mErrorText;
    protected final Button mPositiveButton;
    protected final Button mNegativeButton;
    protected final Button mTryAgainButton;

    protected final int mTextColor;

    private Bundle mBundle;
    private Bundle mRestoredState;

    private int mState = STATE_IDLE;
    private boolean mAnimatingAway;
    private boolean mWasForceRemoved;
    private boolean mSkipIntro;
    protected boolean mRequireConfirmation;
    private int mUserId; // used to determine if we should show work background

    private boolean mCompletedAnimatingIn;
    private boolean mPendingDismissDialog;

    protected abstract int getHintStringResourceId();
    protected abstract int getAuthenticatedAccessibilityResourceId();
    protected abstract int getIconDescriptionResourceId();
    protected abstract int getDelayAfterAuthenticatedDurationMs();
    protected abstract boolean shouldGrayAreaDismissDialog();
    protected abstract void handleResetMessage();
    protected abstract void updateIcon(int oldState, int newState);

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
                    .withEndAction(() -> onDialogAnimatedIn())
                    .start();
        }
    };

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_RESET_MESSAGE:
                    handleResetMessage();
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
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mDevicePolicyManager = mContext.getSystemService(DevicePolicyManager.class);
        mAnimationTranslationOffset = getResources()
                .getDimension(R.dimen.biometric_dialog_animation_translation_offset);
        mErrorColor = getResources().getColor(R.color.biometric_dialog_error);
        mTextColor = getResources().getColor(R.color.biometric_dialog_gray);

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDialogWidth = Math.min(metrics.widthPixels, metrics.heightPixels);

        // Create the dialog
        LayoutInflater factory = LayoutInflater.from(getContext());
        mLayout = (ViewGroup) factory.inflate(R.layout.biometric_dialog, this, false);
        addView(mLayout);

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

        mDialog = mLayout.findViewById(R.id.dialog);
        mTitleText = mLayout.findViewById(R.id.title);
        mSubtitleText = mLayout.findViewById(R.id.subtitle);
        mDescriptionText = mLayout.findViewById(R.id.description);
        mBiometricIcon = mLayout.findViewById(R.id.biometric_icon);
        mErrorText = mLayout.findViewById(R.id.error);
        mNegativeButton = mLayout.findViewById(R.id.button2);
        mPositiveButton = mLayout.findViewById(R.id.button1);
        mTryAgainButton = mLayout.findViewById(R.id.button_try_again);

        mBiometricIcon.setContentDescription(
                getResources().getString(getIconDescriptionResourceId()));

        setDismissesDialog(space);
        setDismissesDialog(leftSpace);
        setDismissesDialog(rightSpace);

        mNegativeButton.setOnClickListener((View v) -> {
            if (mState == STATE_PENDING_CONFIRMATION || mState == STATE_AUTHENTICATED) {
                mCallback.onUserCanceled();
            } else {
                mCallback.onNegativePressed();
            }
        });

        mPositiveButton.setOnClickListener((View v) -> {
            updateState(STATE_AUTHENTICATED);
            mHandler.postDelayed(() -> {
                mCallback.onPositivePressed();
            }, getDelayAfterAuthenticatedDurationMs());
        });

        mTryAgainButton.setOnClickListener((View v) -> {
            handleResetMessage();
            updateState(STATE_AUTHENTICATING);
            showTryAgainButton(false /* show */);

            mPositiveButton.setVisibility(View.VISIBLE);
            mPositiveButton.setEnabled(false);

            mCallback.onTryAgainPressed();
        });

        // Must set these in order for the back button events to be received.
        mLayout.setFocusableInTouchMode(true);
        mLayout.requestFocus();
    }

    public void onSaveState(Bundle bundle) {
        bundle.putInt(KEY_TRY_AGAIN_VISIBILITY, mTryAgainButton.getVisibility());
        bundle.putInt(KEY_CONFIRM_VISIBILITY, mPositiveButton.getVisibility());
        bundle.putBoolean(KEY_CONFIRM_ENABLED, mPositiveButton.isEnabled());
        bundle.putInt(KEY_STATE, mState);
        bundle.putInt(KEY_ERROR_TEXT_VISIBILITY, mErrorText.getVisibility());
        bundle.putCharSequence(KEY_ERROR_TEXT_STRING, mErrorText.getText());
        bundle.putBoolean(KEY_ERROR_TEXT_IS_TEMPORARY, mHandler.hasMessages(MSG_RESET_MESSAGE));
        bundle.putInt(KEY_ERROR_TEXT_COLOR, mErrorText.getCurrentTextColor());
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        final ImageView backgroundView = mLayout.findViewById(R.id.background);

        if (mUserManager.isManagedProfile(mUserId)) {
            final Drawable image = getResources().getDrawable(R.drawable.work_challenge_background,
                    mContext.getTheme());
            image.setColorFilter(mDevicePolicyManager.getOrganizationColorForUser(mUserId),
                    PorterDuff.Mode.DARKEN);
            backgroundView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backgroundView.setImageDrawable(image);
        } else {
            backgroundView.setImageDrawable(null);
            backgroundView.setBackgroundColor(R.color.biometric_dialog_dim_color);
        }

        mNegativeButton.setVisibility(View.VISIBLE);

        if (RotationUtils.getRotation(mContext) != RotationUtils.ROTATION_NONE) {
            mDialog.getLayoutParams().width = (int) mDialogWidth;
        }

        if (mRestoredState == null) {
            updateState(STATE_AUTHENTICATING);
            mNegativeButton.setText(mBundle.getCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT));
            final int hint = getHintStringResourceId();
            if (hint != 0) {
                mErrorText.setText(hint);
                mErrorText.setContentDescription(mContext.getString(hint));
                mErrorText.setVisibility(View.VISIBLE);
            } else {
                mErrorText.setVisibility(View.INVISIBLE);
            }
            announceAccessibilityEvent();
        } else {
            updateState(mState);
        }

        CharSequence titleText = mBundle.getCharSequence(BiometricPrompt.KEY_TITLE);

        mTitleText.setVisibility(View.VISIBLE);
        mTitleText.setText(titleText);

        final CharSequence subtitleText = mBundle.getCharSequence(BiometricPrompt.KEY_SUBTITLE);
        if (TextUtils.isEmpty(subtitleText)) {
            mSubtitleText.setVisibility(View.GONE);
            announceAccessibilityEvent();
        } else {
            mSubtitleText.setVisibility(View.VISIBLE);
            mSubtitleText.setText(subtitleText);
        }

        final CharSequence descriptionText =
                mBundle.getCharSequence(BiometricPrompt.KEY_DESCRIPTION);
        if (TextUtils.isEmpty(descriptionText)) {
            mDescriptionText.setVisibility(View.GONE);
            announceAccessibilityEvent();
        } else {
            mDescriptionText.setVisibility(View.VISIBLE);
            mDescriptionText.setText(descriptionText);
        }

        if (requiresConfirmation() && mRestoredState == null) {
            mPositiveButton.setVisibility(View.VISIBLE);
            mPositiveButton.setEnabled(false);
        }

        if (mWasForceRemoved || mSkipIntro) {
            // Show the dialog immediately
            mLayout.animate().cancel();
            mDialog.animate().cancel();
            mDialog.setAlpha(1.0f);
            mDialog.setTranslationY(0);
            mLayout.setAlpha(1.0f);
            mCompletedAnimatingIn = true;
        } else {
            // Dim the background and slide the dialog up
            mDialog.setTranslationY(mAnimationTranslationOffset);
            mLayout.setAlpha(0f);
            postOnAnimation(mShowAnimationRunnable);
        }
        mWasForceRemoved = false;
        mSkipIntro = false;
    }

    private void setDismissesDialog(View v) {
        v.setClickable(true);
        v.setOnClickListener(v1 -> {
            if (mState != STATE_AUTHENTICATED && shouldGrayAreaDismissDialog()) {
                mCallback.onUserCanceled();
            }
        });
    }

    public void startDismiss() {
        if (!mCompletedAnimatingIn) {
            Log.w(TAG, "startDismiss(): waiting for onDialogAnimatedIn");
            mPendingDismissDialog = true;
            return;
        }

        mAnimatingAway = true;

        // This is where final cleanup should occur.
        final Runnable endActionRunnable = new Runnable() {
            @Override
            public void run() {
                mWindowManager.removeView(BiometricDialogView.this);
                mAnimatingAway = false;
                // Set the icons / text back to normal state
                handleResetMessage();
                showTryAgainButton(false /* show */);
                updateState(STATE_IDLE);
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

    public void setUserId(int userId) {
        mUserId = userId;
    }

    public ViewGroup getLayout() {
        return mLayout;
    }

    // Shows an error/help message
    protected void showTemporaryMessage(String message) {
        mHandler.removeMessages(MSG_RESET_MESSAGE);
        mErrorText.setText(message);
        mErrorText.setTextColor(mErrorColor);
        mErrorText.setContentDescription(message);
        mErrorText.setVisibility(View.VISIBLE);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RESET_MESSAGE),
                BiometricPrompt.HIDE_DIALOG_DELAY);
    }

    /**
     * Transient help message (acquire) is received, dialog stays showing. Sensor stays in
     * "authenticating" state.
     * @param message
     */
    public void onHelpReceived(String message) {
        updateState(STATE_ERROR);
        showTemporaryMessage(message);
    }

    public void onAuthenticationFailed(String message) {
        updateState(STATE_ERROR);
        showTemporaryMessage(message);
    }

    /**
     * Hard error is received, dialog will be dismissed soon.
     * @param error
     */
    public void onErrorReceived(String error) {
        updateState(STATE_ERROR);
        showTemporaryMessage(error);
        showTryAgainButton(false /* show */);
        mCallback.onErrorShown(); // TODO: Split between fp and face
    }

    public void updateState(int newState) {
        if (newState == STATE_PENDING_CONFIRMATION) {
            mHandler.removeMessages(MSG_RESET_MESSAGE);
            mErrorText.setTextColor(mTextColor);
            mErrorText.setText(R.string.biometric_dialog_tap_confirm);
            mErrorText.setContentDescription(
                    getResources().getString(R.string.biometric_dialog_tap_confirm));
            mErrorText.setVisibility(View.VISIBLE);
            announceAccessibilityEvent();
            mPositiveButton.setVisibility(View.VISIBLE);
            mPositiveButton.setEnabled(true);
        } else if (newState == STATE_AUTHENTICATED) {
            mPositiveButton.setVisibility(View.GONE);
            mNegativeButton.setVisibility(View.GONE);
            mErrorText.setVisibility(View.INVISIBLE);
            announceAccessibilityEvent();
        }

        if (newState == STATE_PENDING_CONFIRMATION || newState == STATE_AUTHENTICATED) {
            mNegativeButton.setText(R.string.cancel);
            mNegativeButton.setContentDescription(getResources().getString(R.string.cancel));
        }

        updateIcon(mState, newState);
        mState = newState;
    }

    public void showTryAgainButton(boolean show) {
    }

    public void onDialogAnimatedIn() {
        mCompletedAnimatingIn = true;

        if (mPendingDismissDialog) {
            Log.d(TAG, "onDialogAnimatedIn(): mPendingDismissDialog=true, dismissing now");
            startDismiss();
            mPendingDismissDialog = false;
        }
    }

    public void restoreState(Bundle bundle) {
        mRestoredState = bundle;
        final int tryAgainVisibility = bundle.getInt(KEY_TRY_AGAIN_VISIBILITY);
        mTryAgainButton.setVisibility(tryAgainVisibility);
        final int confirmVisibility = bundle.getInt(KEY_CONFIRM_VISIBILITY);
        mPositiveButton.setVisibility(confirmVisibility);
        final boolean confirmEnabled = bundle.getBoolean(KEY_CONFIRM_ENABLED);
        mPositiveButton.setEnabled(confirmEnabled);
        mState = bundle.getInt(KEY_STATE);
        mErrorText.setText(bundle.getCharSequence(KEY_ERROR_TEXT_STRING));
        mErrorText.setContentDescription(bundle.getCharSequence(KEY_ERROR_TEXT_STRING));
        final int errorTextVisibility = bundle.getInt(KEY_ERROR_TEXT_VISIBILITY);
        mErrorText.setVisibility(errorTextVisibility);
        if (errorTextVisibility == View.INVISIBLE || tryAgainVisibility == View.INVISIBLE
                || confirmVisibility == View.INVISIBLE) {
            announceAccessibilityEvent();
        }
        mErrorText.setTextColor(bundle.getInt(KEY_ERROR_TEXT_COLOR));
        if (bundle.getBoolean(KEY_ERROR_TEXT_IS_TEMPORARY)) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_RESET_MESSAGE),
                    BiometricPrompt.HIDE_DIALOG_DELAY);
        }
    }

    protected int getState() {
        return mState;
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

    // Every time a view becomes invisible we need to announce an accessibility event.
    // This is due to an issue in the framework, b/132298701 recommended this workaround.
    protected void announceAccessibilityEvent() {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setContentChangeTypes(CONTENT_CHANGE_TYPE_SUBTREE);
        mDialog.sendAccessibilityEventUnchecked(event);
        mDialog.notifySubtreeAccessibilityStateChanged(mDialog, mDialog,
                CONTENT_CHANGE_TYPE_SUBTREE);
    }
}
