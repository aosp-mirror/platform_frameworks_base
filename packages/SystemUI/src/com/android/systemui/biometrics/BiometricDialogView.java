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
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import static android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricAuthenticator;
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
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.util.leak.RotationUtils;

/**
 * Abstract base class. Shows a dialog for BiometricPrompt.
 */
public abstract class BiometricDialogView extends LinearLayout implements AuthDialog {

    private static final String TAG = "BiometricPrompt/DialogView";

    public static final String KEY_TRY_AGAIN_VISIBILITY = "key_try_again_visibility";
    public static final String KEY_CONFIRM_VISIBILITY = "key_confirm_visibility";
    public static final String KEY_CONFIRM_ENABLED = "key_confirm_enabled";
    public static final String KEY_STATE = "key_state";
    public static final String KEY_ERROR_TEXT_VISIBILITY = "key_error_text_visibility";
    public static final String KEY_ERROR_TEXT_STRING = "key_error_text_string";
    public static final String KEY_ERROR_TEXT_IS_TEMPORARY = "key_error_text_is_temporary";
    public static final String KEY_ERROR_TEXT_COLOR = "key_error_text_color";
    public static final String KEY_DIALOG_SIZE = "key_dialog_size";

    private static final int ANIMATION_DURATION_SHOW = 250; // ms
    private static final int ANIMATION_DURATION_AWAY = 350; // ms

    protected static final int MSG_RESET_MESSAGE = 1;

    protected static final int STATE_IDLE = 0;
    protected static final int STATE_AUTHENTICATING = 1;
    protected static final int STATE_ERROR = 2;
    protected static final int STATE_PENDING_CONFIRMATION = 3;
    protected static final int STATE_AUTHENTICATED = 4;

    // Dialog layout/animation
    private static final int IMPLICIT_Y_PADDING = 16; // dp
    private static final int GROW_DURATION = 150; // ms
    private static final int TEXT_ANIMATE_DISTANCE = 32; // dp
    @VisibleForTesting static final int SIZE_UNKNOWN = 0;
    @VisibleForTesting static final int SIZE_SMALL = 1;
    @VisibleForTesting static final int SIZE_GROWING = 2;
    @VisibleForTesting static final int SIZE_BIG = 3;
    @IntDef({SIZE_UNKNOWN, SIZE_SMALL, SIZE_GROWING, SIZE_BIG})
    @interface DialogSize {}

    @VisibleForTesting final WakefulnessLifecycle mWakefulnessLifecycle;
    private final AccessibilityManager mAccessibilityManager;
    private final IBinder mWindowToken = new Binder();
    private final Interpolator mLinearOutSlowIn;
    private final WindowManager mWindowManager;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final float mAnimationTranslationOffset;
    private final int mErrorColor;
    private final float mDialogWidth;
    protected final AuthDialogCallback mCallback;
    private final DialogOutlineProvider mOutlineProvider = new DialogOutlineProvider();

    protected final ViewGroup mLayout;
    protected final LinearLayout mDialog;
    @VisibleForTesting final TextView mTitleText;
    @VisibleForTesting final TextView mSubtitleText;
    @VisibleForTesting final TextView mDescriptionText;
    @VisibleForTesting final ImageView mBiometricIcon;
    @VisibleForTesting final TextView mErrorText;
    @VisibleForTesting final Button mPositiveButton;
    @VisibleForTesting final Button mNegativeButton;
    @VisibleForTesting final Button mTryAgainButton;

    protected final int mTextColor;

    private Bundle mBundle;
    private Bundle mRestoredState;
    private String mOpPackageName;

    private int mState = STATE_IDLE;
    private boolean mWasForceRemoved;
    private boolean mSkipIntro;
    protected boolean mRequireConfirmation;
    private int mUserId; // used to determine if we should show work background
    private @DialogSize int mSize;
    private float mIconOriginalY;

    private boolean mCompletedAnimatingIn;
    private boolean mPendingDismissDialog;

    protected abstract int getHintStringResourceId();
    protected abstract int getAuthenticatedAccessibilityResourceId();
    protected abstract int getIconDescriptionResourceId();
    protected abstract int getDelayAfterAuthenticatedDurationMs();
    protected abstract boolean shouldGrayAreaDismissDialog();
    protected abstract void handleResetMessage();
    protected abstract void updateIcon(int oldState, int newState);
    protected abstract boolean supportsSmallDialog();

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

    @VisibleForTesting
    final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedGoingToSleep() {
                    animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
                }
            };

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
            final float padding = Utils.dpToPixels(mContext, IMPLICIT_Y_PADDING);
            return mDialog.getHeight() - mBiometricIcon.getHeight() - 2 * (int) padding;
        }

        void setOutlineY(float y) {
            mY = y;
        }
    }

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

    /**
     * Builds the dialog with specified parameters.
     */
    public static class Builder {
        public static final int TYPE_FINGERPRINT = BiometricAuthenticator.TYPE_FINGERPRINT;
        public static final int TYPE_FACE = BiometricAuthenticator.TYPE_FACE;

        private Context mContext;
        private AuthDialogCallback mCallback;
        private Bundle mBundle;
        private boolean mRequireConfirmation;
        private int mUserId;
        private String mOpPackageName;
        private boolean mSkipIntro;

        public Builder(Context context) {
            mContext = context;
        }

        public Builder setCallback(AuthDialogCallback callback) {
            mCallback = callback;
            return this;
        }

        public Builder setBiometricPromptBundle(Bundle bundle) {
            mBundle = bundle;
            return this;
        }

        public Builder setRequireConfirmation(boolean requireConfirmation) {
            mRequireConfirmation = requireConfirmation;
            return this;
        }

        public Builder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        public Builder setOpPackageName(String opPackageName) {
            mOpPackageName = opPackageName;
            return this;
        }

        public Builder setSkipIntro(boolean skipIntro) {
            mSkipIntro = skipIntro;
            return this;
        }

        public BiometricDialogView build(int type) {
            return build(type, new Injector());
        }

        public BiometricDialogView build(int type, Injector injector) {
            BiometricDialogView dialog;
            if (type == TYPE_FINGERPRINT) {
                dialog = new FingerprintDialogView(mContext, mCallback, injector);
            } else if (type == TYPE_FACE) {
                dialog = new FaceDialogView(mContext, mCallback, injector);
            } else {
                return null;
            }
            dialog.setBundle(mBundle);
            dialog.setRequireConfirmation(mRequireConfirmation);
            dialog.setUserId(mUserId);
            dialog.setOpPackageName(mOpPackageName);
            dialog.setSkipIntro(mSkipIntro);
            return dialog;
        }
    }

    public static class Injector {
        public WakefulnessLifecycle getWakefulnessLifecycle() {
            return Dependency.get(WakefulnessLifecycle.class);
        }
    }

    protected BiometricDialogView(Context context, AuthDialogCallback callback, Injector injector) {
        super(context);
        mWakefulnessLifecycle = injector.getWakefulnessLifecycle();

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
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
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
                animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            } else {
                animateAway(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE);
            }
        });

        mPositiveButton.setOnClickListener((View v) -> {
            updateState(STATE_AUTHENTICATED);
            mHandler.postDelayed(() -> {
                animateAway(AuthDialogCallback.DISMISSED_BUTTON_POSITIVE);
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

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);

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
        mNegativeButton.setText(mBundle.getCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT));

        if (RotationUtils.getRotation(mContext) != RotationUtils.ROTATION_NONE) {
            mDialog.getLayoutParams().width = (int) mDialogWidth;
        }

        if (mRestoredState == null) {
            updateState(STATE_AUTHENTICATING);
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
        if (getSize() != SIZE_UNKNOWN) {
            // Probably not the cleanest way to do this, but since dialog is big by default,
            // and small dialogs can persist across orientation changes, we need to set it to
            // small size here again.
            if (getSize() == SIZE_SMALL) {
                updateSize(SIZE_SMALL);
            }
            return;
        }

        // If we don't require confirmation, show the small dialog first (until errors occur).
        if (!requiresConfirmation() && supportsSmallDialog()) {
            updateSize(SIZE_SMALL);
        } else {
            updateSize(SIZE_BIG);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mWakefulnessLifecycle.removeObserver(mWakefulnessObserver);
    }

    @VisibleForTesting
    void updateSize(@DialogSize int newSize) {
        final float padding = Utils.dpToPixels(mContext, IMPLICIT_Y_PADDING);
        final float iconSmallPositionY = mDialog.getHeight() - mBiometricIcon.getHeight() - padding;

        if (newSize == SIZE_SMALL) {
            if (!supportsSmallDialog()) {
                Log.e(TAG, "Small dialog unsupported");
                return;
            }

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
            announceAccessibilityEvent();
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
                    ValueAnimator.ofFloat(Utils.dpToPixels(mContext, TEXT_ANIMATE_DISTANCE), 0);
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

    private void setDismissesDialog(View v) {
        v.setClickable(true);
        v.setOnClickListener(v1 -> {
            if (mState != STATE_AUTHENTICATED && shouldGrayAreaDismissDialog()) {
                animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            }
        });
    }

    private void animateAway(@AuthDialogCallback.DismissedReason int reason) {
        animateAway(true /* sendReason */, reason);
    }

    /**
     * Animate the dialog away
     * @param reason one of the {@link AuthDialogCallback} codes
     */
    private void animateAway(boolean sendReason, @AuthDialogCallback.DismissedReason int reason) {
        if (!mCompletedAnimatingIn) {
            Log.w(TAG, "startDismiss(): waiting for onDialogAnimatedIn");
            mPendingDismissDialog = true;
            return;
        }

        // This is where final cleanup should occur.
        final Runnable endActionRunnable = new Runnable() {
            @Override
            public void run() {
                mWindowManager.removeView(BiometricDialogView.this);
                // Set the icons / text back to normal state
                handleResetMessage();
                showTryAgainButton(false /* show */);
                updateState(STATE_IDLE);
                if (sendReason) {
                    mCallback.onDismissed(reason);
                }
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
     * Skip the intro animation
     */
    private void setSkipIntro(boolean skip) {
        mSkipIntro = skip;
    }

    private void setBundle(Bundle bundle) {
        mBundle = bundle;
    }

    private void setRequireConfirmation(boolean requireConfirmation) {
        mRequireConfirmation = requireConfirmation;
    }

    protected boolean requiresConfirmation() {
        return mRequireConfirmation;
    }

    private void setUserId(int userId) {
        mUserId = userId;
    }

    private void setOpPackageName(String opPackageName) {
        mOpPackageName = opPackageName;
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

    @Override
    public void show(WindowManager wm) {
        wm.addView(this, getLayoutParams(mWindowToken));
    }

    /**
     * Force remove the window, cancelling any animation that's happening. This should only be
     * called if we want to quickly show the dialog again (e.g. on rotation). Calling this method
     * will cause the dialog to show without an animation the next time it's attached.
     */
    @Override
    public void dismissWithoutCallback(boolean animate) {
        if (animate) {
            animateAway(false /* sendReason */, 0 /* reason */);
        } else {
            mLayout.animate().cancel();
            mDialog.animate().cancel();
            mWindowManager.removeView(BiometricDialogView.this);
            mWasForceRemoved = true;
        }
    }

    @Override
    public void dismissFromSystemServer() {
        animateAway(AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER);
    }

    @Override
    public void onAuthenticationSucceeded() {
        announceForAccessibility(getResources().getText(getAuthenticatedAccessibilityResourceId()));

        if (requiresConfirmation()) {
            updateState(STATE_PENDING_CONFIRMATION);
        } else {
            mHandler.postDelayed(() -> {
                animateAway(AuthDialogCallback.DISMISSED_AUTHENTICATED);
            }, getDelayAfterAuthenticatedDurationMs());

            updateState(STATE_AUTHENTICATED);
        }
    }


    @Override
    public void onAuthenticationFailed(String message) {
        updateState(STATE_ERROR);
        showTemporaryMessage(message);
    }

    /**
     * Transient help message (acquire) is received, dialog stays showing. Sensor stays in
     * "authenticating" state.
     * @param message
     */
    @Override
    public void onHelp(String message) {
        updateState(STATE_ERROR);
        showTemporaryMessage(message);
    }

    /**
     * Hard error is received, dialog will be dismissed soon.
     * @param error
     */
    @Override
    public void onError(String error) {
        // All error messages will cause the dialog to go from small -> big. Error messages
        // are messages such as lockout, auth failed, etc.
        if (mSize == SIZE_SMALL) {
            updateSize(SIZE_BIG);
        }

        updateState(STATE_ERROR);
        showTemporaryMessage(error);
        showTryAgainButton(false /* show */);

        mHandler.postDelayed(() -> {
            animateAway(AuthDialogCallback.DISMISSED_ERROR);
        }, BiometricPrompt.HIDE_DIALOG_DELAY);
    }


    @Override
    public void onSaveState(Bundle bundle) {
        bundle.putInt(KEY_TRY_AGAIN_VISIBILITY, mTryAgainButton.getVisibility());
        bundle.putInt(KEY_CONFIRM_VISIBILITY, mPositiveButton.getVisibility());
        bundle.putBoolean(KEY_CONFIRM_ENABLED, mPositiveButton.isEnabled());
        bundle.putInt(KEY_STATE, mState);
        bundle.putInt(KEY_ERROR_TEXT_VISIBILITY, mErrorText.getVisibility());
        bundle.putCharSequence(KEY_ERROR_TEXT_STRING, mErrorText.getText());
        bundle.putBoolean(KEY_ERROR_TEXT_IS_TEMPORARY, mHandler.hasMessages(MSG_RESET_MESSAGE));
        bundle.putInt(KEY_ERROR_TEXT_COLOR, mErrorText.getCurrentTextColor());
        bundle.putInt(KEY_DIALOG_SIZE, mSize);
    }

    @Override
    public void restoreState(Bundle bundle) {
        mRestoredState = bundle;

        // Keep in mind that this happens before onAttachedToWindow()
        mSize = bundle.getInt(KEY_DIALOG_SIZE);

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

    @Override
    public String getOpPackageName() {
        return mOpPackageName;
    }

    protected void updateState(int newState) {
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

    protected @DialogSize int getSize() {
        return mSize;
    }

    protected void showTryAgainButton(boolean show) {
        if (show && getSize() == SIZE_SMALL) {
            // Do not call super, we will nicely animate the alpha together with the rest
            // of the elements in here.
            updateSize(SIZE_BIG);
        } else {
            if (show) {
                mTryAgainButton.setVisibility(View.VISIBLE);
            } else {
                mTryAgainButton.setVisibility(View.GONE);
                announceAccessibilityEvent();
            }
        }

        if (show) {
            mPositiveButton.setVisibility(View.GONE);
            announceAccessibilityEvent();
        }
    }

    protected void onDialogAnimatedIn() {
        mCompletedAnimatingIn = true;

        if (mPendingDismissDialog) {
            Log.d(TAG, "onDialogAnimatedIn(): mPendingDismissDialog=true, dismissing now");
            animateAway(false /* sendReason */, 0);
            mPendingDismissDialog = false;
        }
    }

    /**
     * @param windowToken token for the window
     * @return
     */
    public static WindowManager.LayoutParams getLayoutParams(IBinder windowToken) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("BiometricDialogView");
        lp.token = windowToken;
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
