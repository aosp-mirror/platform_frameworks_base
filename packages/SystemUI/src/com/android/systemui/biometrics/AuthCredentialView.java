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

import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT;
import static android.app.admin.DevicePolicyResources.UNDEFINED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.PromptInfo;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Abstract base class for Pin, Pattern, or Password authentication, for
 * {@link BiometricPrompt.Builder#setAllowedAuthenticators(int)}}
 */
public abstract class AuthCredentialView extends LinearLayout {
    private static final String TAG = "BiometricPrompt/AuthCredentialView";
    private static final int ERROR_DURATION_MS = 3000;

    static final int USER_TYPE_PRIMARY = 1;
    static final int USER_TYPE_MANAGED_PROFILE = 2;
    static final int USER_TYPE_SECONDARY = 3;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({USER_TYPE_PRIMARY, USER_TYPE_MANAGED_PROFILE, USER_TYPE_SECONDARY})
    private @interface UserType {}

    protected final Handler mHandler;
    protected final LockPatternUtils mLockPatternUtils;

    private final AccessibilityManager mAccessibilityManager;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;

    private PromptInfo mPromptInfo;
    private AuthPanelController mPanelController;
    private boolean mShouldAnimatePanel;
    private boolean mShouldAnimateContents;

    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mDescriptionView;
    private ImageView mIconView;
    protected TextView mErrorView;

    protected @Utils.CredentialType int mCredentialType;
    protected AuthContainerView mContainerView;
    protected Callback mCallback;
    protected AsyncTask<?, ?, ?> mPendingLockCheck;
    protected int mUserId;
    protected long mOperationId;
    protected int mEffectiveUserId;
    protected ErrorTimer mErrorTimer;

    protected @Background DelayableExecutor mBackgroundExecutor;

    interface Callback {
        void onCredentialMatched(byte[] attestation);
    }

    protected static class ErrorTimer extends CountDownTimer {
        private final TextView mErrorView;
        private final Context mContext;

        /**
         * @param millisInFuture    The number of millis in the future from the call
         *                          to {@link #start()} until the countdown is done and {@link
         *                          #onFinish()}
         *                          is called.
         * @param countDownInterval The interval along the way to receive
         *                          {@link #onTick(long)} callbacks.
         */
        public ErrorTimer(Context context, long millisInFuture, long countDownInterval,
                TextView errorView) {
            super(millisInFuture, countDownInterval);
            mErrorView = errorView;
            mContext = context;
        }

        @Override
        public void onTick(long millisUntilFinished) {
            final int secondsCountdown = (int) (millisUntilFinished / 1000);
            mErrorView.setText(mContext.getString(
                    R.string.biometric_dialog_credential_too_many_attempts, secondsCountdown));
        }

        @Override
        public void onFinish() {
            if (mErrorView != null) {
                mErrorView.setText("");
            }
        }
    }

    protected final Runnable mClearErrorRunnable = new Runnable() {
        @Override
        public void run() {
            if (mErrorView != null) {
                mErrorView.setText("");
            }
        }
    };

    public AuthCredentialView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mLockPatternUtils = new LockPatternUtils(mContext);
        mHandler = new Handler(Looper.getMainLooper());
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mDevicePolicyManager = mContext.getSystemService(DevicePolicyManager.class);
    }

    protected void showError(String error) {
        if (mHandler != null) {
            mHandler.removeCallbacks(mClearErrorRunnable);
            mHandler.postDelayed(mClearErrorRunnable, ERROR_DURATION_MS);
        }
        if (mErrorView != null) {
            mErrorView.setText(error);
        }
    }

    private void setTextOrHide(TextView view, CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
        }

        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
    }

    private void setText(TextView view, CharSequence text) {
        view.setText(text);
    }

    void setUserId(int userId) {
        mUserId = userId;
    }

    void setOperationId(long operationId) {
        mOperationId = operationId;
    }

    void setEffectiveUserId(int effectiveUserId) {
        mEffectiveUserId = effectiveUserId;
    }

    void setCredentialType(@Utils.CredentialType int credentialType) {
        mCredentialType = credentialType;
    }

    void setCallback(Callback callback) {
        mCallback = callback;
    }

    void setPromptInfo(PromptInfo promptInfo) {
        mPromptInfo = promptInfo;
    }

    void setPanelController(AuthPanelController panelController, boolean animatePanel) {
        mPanelController = panelController;
        mShouldAnimatePanel = animatePanel;
    }

    void setShouldAnimateContents(boolean animateContents) {
        mShouldAnimateContents = animateContents;
    }

    void setContainerView(AuthContainerView containerView) {
        mContainerView = containerView;
    }

    void setBackgroundExecutor(@Background DelayableExecutor bgExecutor) {
        mBackgroundExecutor = bgExecutor;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final CharSequence title = getTitle(mPromptInfo);
        setText(mTitleView, title);
        setTextOrHide(mSubtitleView, getSubtitle(mPromptInfo));
        setTextOrHide(mDescriptionView, getDescription(mPromptInfo));
        announceForAccessibility(title);

        if (mIconView != null) {
            final boolean isManagedProfile = Utils.isManagedProfile(mContext, mEffectiveUserId);
            final Drawable image;
            if (isManagedProfile) {
                image = getResources().getDrawable(R.drawable.auth_dialog_enterprise,
                        mContext.getTheme());
            } else {
                image = getResources().getDrawable(R.drawable.auth_dialog_lock,
                        mContext.getTheme());
            }
            mIconView.setImageDrawable(image);
        }

        // Only animate this if we're transitioning from a biometric view.
        if (mShouldAnimateContents) {
            setTranslationY(getResources()
                    .getDimension(R.dimen.biometric_dialog_credential_translation_offset));
            setAlpha(0);

            postOnAnimation(() -> {
                animate().translationY(0)
                        .setDuration(AuthDialog.ANIMATE_CREDENTIAL_INITIAL_DURATION_MS)
                        .alpha(1.f)
                        .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                        .withLayer()
                        .start();
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mErrorTimer != null) {
            mErrorTimer.cancel();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitleView = findViewById(R.id.title);
        mSubtitleView = findViewById(R.id.subtitle);
        mDescriptionView = findViewById(R.id.description);
        mIconView = findViewById(R.id.icon);
        mErrorView = findViewById(R.id.error);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (mShouldAnimatePanel) {
            // Credential view is always full screen.
            mPanelController.setUseFullScreen(true);
            mPanelController.updateForContentDimensions(mPanelController.getContainerWidth(),
                    mPanelController.getContainerHeight(), 0 /* animateDurationMs */);
            mShouldAnimatePanel = false;
        }
    }

    protected void onErrorTimeoutFinish() {}

    protected void onCredentialVerified(@NonNull VerifyCredentialResponse response, int timeoutMs) {
        if (response.isMatched()) {
            mClearErrorRunnable.run();
            mLockPatternUtils.userPresent(mEffectiveUserId);

            // The response passed into this method contains the Gatekeeper Password. We still
            // have to request Gatekeeper to create a Hardware Auth Token with the
            // Gatekeeper Password and Challenge (keystore operationId in this case)
            final long pwHandle = response.getGatekeeperPasswordHandle();
            final VerifyCredentialResponse gkResponse = mLockPatternUtils
                    .verifyGatekeeperPasswordHandle(pwHandle, mOperationId, mEffectiveUserId);

            mCallback.onCredentialMatched(gkResponse.getGatekeeperHAT());
            mLockPatternUtils.removeGatekeeperPasswordHandle(pwHandle);
        } else {
            if (timeoutMs > 0) {
                mHandler.removeCallbacks(mClearErrorRunnable);
                long deadline = mLockPatternUtils.setLockoutAttemptDeadline(
                        mEffectiveUserId, timeoutMs);
                mErrorTimer = new ErrorTimer(mContext,
                        deadline - SystemClock.elapsedRealtime(),
                        LockPatternUtils.FAILED_ATTEMPT_COUNTDOWN_INTERVAL_MS,
                        mErrorView) {
                    @Override
                    public void onFinish() {
                        onErrorTimeoutFinish();
                        mClearErrorRunnable.run();
                    }
                };
                mErrorTimer.start();
            } else {
                final boolean didUpdateErrorText = reportFailedAttempt();
                if (!didUpdateErrorText) {
                    final @StringRes int errorRes;
                    switch (mCredentialType) {
                        case Utils.CREDENTIAL_PIN:
                            errorRes = R.string.biometric_dialog_wrong_pin;
                            break;
                        case Utils.CREDENTIAL_PATTERN:
                            errorRes = R.string.biometric_dialog_wrong_pattern;
                            break;
                        case Utils.CREDENTIAL_PASSWORD:
                        default:
                            errorRes = R.string.biometric_dialog_wrong_password;
                            break;
                    }
                    showError(getResources().getString(errorRes));
                }
            }
        }
    }

    private boolean reportFailedAttempt() {
        boolean result = updateErrorMessage(
                mLockPatternUtils.getCurrentFailedPasswordAttempts(mEffectiveUserId) + 1);
        mLockPatternUtils.reportFailedPasswordAttempt(mEffectiveUserId);
        return result;
    }

    private boolean updateErrorMessage(int numAttempts) {
        // Don't show any message if there's no maximum number of attempts.
        final int maxAttempts = mLockPatternUtils.getMaximumFailedPasswordsForWipe(
                mEffectiveUserId);
        if (maxAttempts <= 0 || numAttempts <= 0) {
            return false;
        }

        // Update the on-screen error string.
        if (mErrorView != null) {
            final String message = getResources().getString(
                    R.string.biometric_dialog_credential_attempts_before_wipe,
                    numAttempts,
                    maxAttempts);
            showError(message);
        }

        // Only show dialog if <=1 attempts are left before wiping.
        final int remainingAttempts = maxAttempts - numAttempts;
        if (remainingAttempts == 1) {
            showLastAttemptBeforeWipeDialog();
        } else if (remainingAttempts <= 0) {
            showNowWipingDialog();
        }
        return true;
    }

    private void showLastAttemptBeforeWipeDialog() {
        mBackgroundExecutor.execute(() -> {
            final AlertDialog alertDialog = new AlertDialog.Builder(mContext)
                    .setTitle(R.string.biometric_dialog_last_attempt_before_wipe_dialog_title)
                    .setMessage(
                            getLastAttemptBeforeWipeMessage(getUserTypeForWipe(), mCredentialType))
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
            mHandler.post(alertDialog::show);
        });
    }

    private void showNowWipingDialog() {
        mBackgroundExecutor.execute(() -> {
            String nowWipingMessage = getNowWipingMessage(getUserTypeForWipe());
            final AlertDialog alertDialog = new AlertDialog.Builder(mContext)
                    .setMessage(nowWipingMessage)
                    .setPositiveButton(
                            com.android.settingslib.R.string.failed_attempts_now_wiping_dialog_dismiss,
                            null /* OnClickListener */)
                    .setOnDismissListener(
                            dialog -> mContainerView.animateAway(
                                    AuthDialogCallback.DISMISSED_ERROR))
                    .create();
            alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
            mHandler.post(alertDialog::show);
        });
    }

    private @UserType int getUserTypeForWipe() {
        final UserInfo userToBeWiped = mUserManager.getUserInfo(
                mDevicePolicyManager.getProfileWithMinimumFailedPasswordsForWipe(mEffectiveUserId));
        if (userToBeWiped == null || userToBeWiped.isPrimary()) {
            return USER_TYPE_PRIMARY;
        } else if (userToBeWiped.isManagedProfile()) {
            return USER_TYPE_MANAGED_PROFILE;
        } else {
            return USER_TYPE_SECONDARY;
        }
    }

    // This should not be called on the main thread to avoid making an IPC.
    private String getLastAttemptBeforeWipeMessage(
            @UserType int userType, @Utils.CredentialType int credentialType) {
        switch (userType) {
            case USER_TYPE_PRIMARY:
                return getLastAttemptBeforeWipeDeviceMessage(credentialType);
            case USER_TYPE_MANAGED_PROFILE:
                return getLastAttemptBeforeWipeProfileMessage(credentialType);
            case USER_TYPE_SECONDARY:
                return getLastAttemptBeforeWipeUserMessage(credentialType);
            default:
                throw new IllegalArgumentException("Unrecognized user type:" + userType);
        }
    }

    private String getLastAttemptBeforeWipeDeviceMessage(
            @Utils.CredentialType int credentialType) {
        switch (credentialType) {
            case Utils.CREDENTIAL_PIN:
                return mContext.getString(
                        R.string.biometric_dialog_last_pin_attempt_before_wipe_device);
            case Utils.CREDENTIAL_PATTERN:
                return mContext.getString(
                        R.string.biometric_dialog_last_pattern_attempt_before_wipe_device);
            case Utils.CREDENTIAL_PASSWORD:
            default:
                return mContext.getString(
                        R.string.biometric_dialog_last_password_attempt_before_wipe_device);
        }
    }

    // This should not be called on the main thread to avoid making an IPC.
    private String getLastAttemptBeforeWipeProfileMessage(
            @Utils.CredentialType int credentialType) {
        return mDevicePolicyManager.getResources().getString(
                getLastAttemptBeforeWipeProfileUpdatableStringId(credentialType),
                () -> getLastAttemptBeforeWipeProfileDefaultMessage(credentialType));
    }

    private static String getLastAttemptBeforeWipeProfileUpdatableStringId(
            @Utils.CredentialType int credentialType) {
        switch (credentialType) {
            case Utils.CREDENTIAL_PIN:
                return BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT;
            case Utils.CREDENTIAL_PATTERN:
                return BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT;
            case Utils.CREDENTIAL_PASSWORD:
            default:
                return BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT;
        }
    }

    private String getLastAttemptBeforeWipeProfileDefaultMessage(
            @Utils.CredentialType int credentialType) {
        int resId;
        switch (credentialType) {
            case Utils.CREDENTIAL_PIN:
                resId = R.string.biometric_dialog_last_pin_attempt_before_wipe_profile;
                break;
            case Utils.CREDENTIAL_PATTERN:
                resId = R.string.biometric_dialog_last_pattern_attempt_before_wipe_profile;
                break;
            case Utils.CREDENTIAL_PASSWORD:
            default:
                resId = R.string.biometric_dialog_last_password_attempt_before_wipe_profile;
        }
        return mContext.getString(resId);
    }

    private String getLastAttemptBeforeWipeUserMessage(
            @Utils.CredentialType int credentialType) {
        int resId;
        switch (credentialType) {
            case Utils.CREDENTIAL_PIN:
                resId = R.string.biometric_dialog_last_pin_attempt_before_wipe_user;
                break;
            case Utils.CREDENTIAL_PATTERN:
                resId = R.string.biometric_dialog_last_pattern_attempt_before_wipe_user;
                break;
            case Utils.CREDENTIAL_PASSWORD:
            default:
                resId = R.string.biometric_dialog_last_password_attempt_before_wipe_user;
        }
        return mContext.getString(resId);
    }

    private String getNowWipingMessage(@UserType int userType) {
        return mDevicePolicyManager.getResources().getString(
                getNowWipingUpdatableStringId(userType),
                () -> getNowWipingDefaultMessage(userType));
    }

    private String getNowWipingUpdatableStringId(@UserType int userType) {
        switch (userType) {
            case USER_TYPE_MANAGED_PROFILE:
                return BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS;
            default:
                return UNDEFINED;
        }
    }

    private String getNowWipingDefaultMessage(@UserType int userType) {
        int resId;
        switch (userType) {
            case USER_TYPE_PRIMARY:
                resId = com.android.settingslib.R.string.failed_attempts_now_wiping_device;
                break;
            case USER_TYPE_MANAGED_PROFILE:
                resId = com.android.settingslib.R.string.failed_attempts_now_wiping_profile;
                break;
            case USER_TYPE_SECONDARY:
                resId = com.android.settingslib.R.string.failed_attempts_now_wiping_user;
                break;
            default:
                throw new IllegalArgumentException("Unrecognized user type:" + userType);
        }
        return mContext.getString(resId);
    }

    @Nullable
    private static CharSequence getTitle(@NonNull PromptInfo promptInfo) {
        final CharSequence credentialTitle = promptInfo.getDeviceCredentialTitle();
        return credentialTitle != null ? credentialTitle : promptInfo.getTitle();
    }

    @Nullable
    private static CharSequence getSubtitle(@NonNull PromptInfo promptInfo) {
        final CharSequence credentialSubtitle = promptInfo.getDeviceCredentialSubtitle();
        return credentialSubtitle != null ? credentialSubtitle : promptInfo.getSubtitle();
    }

    @Nullable
    private static CharSequence getDescription(@NonNull PromptInfo promptInfo) {
        final CharSequence credentialDescription = promptInfo.getDeviceCredentialDescription();
        return credentialDescription != null ? credentialDescription : promptInfo.getDescription();
    }
}
