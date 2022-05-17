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

import static android.hardware.biometrics.BiometricManager.BIOMETRIC_MULTI_SENSOR_DEFAULT;
import static android.hardware.biometrics.BiometricManager.BiometricMultiSensorMode;

import android.annotation.DurationMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.PromptInfo;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Top level container/controller for the BiometricPrompt UI.
 */
public class AuthContainerView extends LinearLayout
        implements AuthDialog, WakefulnessLifecycle.Observer {

    private static final String TAG = "AuthContainerView";

    private static final int ANIMATION_DURATION_SHOW_MS = 250;
    private static final int ANIMATION_DURATION_AWAY_MS = 350;

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_ANIMATING_IN = 1;
    private static final int STATE_PENDING_DISMISS = 2;
    private static final int STATE_SHOWING = 3;
    private static final int STATE_ANIMATING_OUT = 4;
    private static final int STATE_GONE = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_UNKNOWN, STATE_ANIMATING_IN, STATE_PENDING_DISMISS, STATE_SHOWING,
            STATE_ANIMATING_OUT, STATE_GONE})
    private @interface ContainerState {}

    private final Config mConfig;
    private final int mEffectiveUserId;
    private final Handler mHandler;
    private final IBinder mWindowToken = new Binder();
    private final WindowManager mWindowManager;
    private final Interpolator mLinearOutSlowIn;
    private final CredentialCallback mCredentialCallback;
    private final LockPatternUtils mLockPatternUtils;
    private final WakefulnessLifecycle mWakefulnessLifecycle;

    @VisibleForTesting final BiometricCallback mBiometricCallback;

    @Nullable private AuthBiometricView mBiometricView;
    @Nullable private AuthCredentialView mCredentialView;
    private final AuthPanelController mPanelController;
    private final FrameLayout mFrameLayout;
    private final ImageView mBackgroundView;
    private final ScrollView mBiometricScrollView;
    private final View mPanelView;
    private final float mTranslationY;
    @ContainerState private int mContainerState = STATE_UNKNOWN;
    private final Set<Integer> mFailedModalities = new HashSet<Integer>();

    private final @Background DelayableExecutor mBackgroundExecutor;

    // Non-null only if the dialog is in the act of dismissing and has not sent the reason yet.
    @Nullable @AuthDialogCallback.DismissedReason private Integer mPendingCallbackReason;
    // HAT received from LockSettingsService when credential is verified.
    @Nullable private byte[] mCredentialAttestation;

    @VisibleForTesting
    static class Config {
        Context mContext;
        AuthDialogCallback mCallback;
        PromptInfo mPromptInfo;
        boolean mRequireConfirmation;
        int mUserId;
        String mOpPackageName;
        int[] mSensorIds;
        boolean mSkipIntro;
        long mOperationId;
        long mRequestId = -1;
        boolean mSkipAnimation = false;
        @BiometricMultiSensorMode int mMultiSensorConfig = BIOMETRIC_MULTI_SENSOR_DEFAULT;
    }

    public static class Builder {
        Config mConfig;

        public Builder(Context context) {
            mConfig = new Config();
            mConfig.mContext = context;
        }

        public Builder setCallback(AuthDialogCallback callback) {
            mConfig.mCallback = callback;
            return this;
        }

        public Builder setPromptInfo(PromptInfo promptInfo) {
            mConfig.mPromptInfo = promptInfo;
            return this;
        }

        public Builder setRequireConfirmation(boolean requireConfirmation) {
            mConfig.mRequireConfirmation = requireConfirmation;
            return this;
        }

        public Builder setUserId(int userId) {
            mConfig.mUserId = userId;
            return this;
        }

        public Builder setOpPackageName(String opPackageName) {
            mConfig.mOpPackageName = opPackageName;
            return this;
        }

        public Builder setSkipIntro(boolean skip) {
            mConfig.mSkipIntro = skip;
            return this;
        }

        public Builder setOperationId(@DurationMillisLong long operationId) {
            mConfig.mOperationId = operationId;
            return this;
        }

        /** Unique id for this request. */
        public Builder setRequestId(long requestId) {
            mConfig.mRequestId = requestId;
            return this;
        }

        @VisibleForTesting
        public Builder setSkipAnimationDuration(boolean skip) {
            mConfig.mSkipAnimation = skip;
            return this;
        }

        /** The multi-sensor mode. */
        public Builder setMultiSensorConfig(@BiometricMultiSensorMode int multiSensorConfig) {
            mConfig.mMultiSensorConfig = multiSensorConfig;
            return this;
        }

        public AuthContainerView build(@Background DelayableExecutor bgExecutor, int[] sensorIds,
                @Nullable List<FingerprintSensorPropertiesInternal> fpProps,
                @Nullable List<FaceSensorPropertiesInternal> faceProps,
                @NonNull WakefulnessLifecycle wakefulnessLifecycle,
                @NonNull UserManager userManager,
                @NonNull LockPatternUtils lockPatternUtils) {
            mConfig.mSensorIds = sensorIds;
            return new AuthContainerView(mConfig, fpProps, faceProps, wakefulnessLifecycle,
                    userManager, lockPatternUtils, new Handler(Looper.getMainLooper()), bgExecutor);
        }
    }

    @VisibleForTesting
    final class BiometricCallback implements AuthBiometricView.Callback {
        @Override
        public void onAction(int action) {
            switch (action) {
                case AuthBiometricView.Callback.ACTION_AUTHENTICATED:
                    animateAway(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED);
                    break;
                case AuthBiometricView.Callback.ACTION_USER_CANCELED:
                    sendEarlyUserCanceled();
                    animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
                    break;
                case AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE:
                    animateAway(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE);
                    break;
                case AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN:
                    mFailedModalities.clear();
                    mConfig.mCallback.onTryAgainPressed();
                    break;
                case AuthBiometricView.Callback.ACTION_ERROR:
                    animateAway(AuthDialogCallback.DISMISSED_ERROR);
                    break;
                case AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL:
                    mConfig.mCallback.onDeviceCredentialPressed();
                    mHandler.postDelayed(() -> {
                        addCredentialView(false /* animatePanel */, true /* animateContents */);
                    }, mConfig.mSkipAnimation ? 0 : AuthDialog.ANIMATE_CREDENTIAL_START_DELAY_MS);
                    break;
                default:
                    Log.e(TAG, "Unhandled action: " + action);
            }
        }
    }

    final class CredentialCallback implements AuthCredentialView.Callback {
        @Override
        public void onCredentialMatched(byte[] attestation) {
            mCredentialAttestation = attestation;
            animateAway(AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED);
        }
    }

    @VisibleForTesting
    AuthContainerView(Config config,
            @Nullable List<FingerprintSensorPropertiesInternal> fpProps,
            @Nullable List<FaceSensorPropertiesInternal> faceProps,
            @NonNull WakefulnessLifecycle wakefulnessLifecycle,
            @NonNull UserManager userManager,
            @NonNull LockPatternUtils lockPatternUtils,
            @NonNull Handler mainHandler,
            @NonNull @Background DelayableExecutor bgExecutor) {
        super(config.mContext);

        mConfig = config;
        mLockPatternUtils = lockPatternUtils;
        mEffectiveUserId = userManager.getCredentialOwnerProfile(mConfig.mUserId);
        mHandler = mainHandler;
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mWakefulnessLifecycle = wakefulnessLifecycle;

        mTranslationY = getResources()
                .getDimension(R.dimen.biometric_dialog_animation_translation_offset);
        mLinearOutSlowIn = Interpolators.LINEAR_OUT_SLOW_IN;
        mBiometricCallback = new BiometricCallback();
        mCredentialCallback = new CredentialCallback();

        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mFrameLayout = (FrameLayout) layoutInflater.inflate(
                R.layout.auth_container_view, this, false /* attachToRoot */);
        addView(mFrameLayout);
        mBiometricScrollView = mFrameLayout.findViewById(R.id.biometric_scrollview);
        mBackgroundView = mFrameLayout.findViewById(R.id.background);
        mPanelView = mFrameLayout.findViewById(R.id.panel);
        mPanelController = new AuthPanelController(mContext, mPanelView);
        mBackgroundExecutor = bgExecutor;

        // Inflate biometric view only if necessary.
        if (Utils.isBiometricAllowed(mConfig.mPromptInfo)) {
            final FingerprintSensorPropertiesInternal fpProperties =
                    Utils.findFirstSensorProperties(fpProps, mConfig.mSensorIds);
            final FaceSensorPropertiesInternal faceProperties =
                    Utils.findFirstSensorProperties(faceProps, mConfig.mSensorIds);

            if (fpProperties != null && faceProperties != null) {
                final AuthBiometricFingerprintAndFaceView fingerprintAndFaceView =
                        (AuthBiometricFingerprintAndFaceView) layoutInflater.inflate(
                                R.layout.auth_biometric_fingerprint_and_face_view, null, false);
                fingerprintAndFaceView.setSensorProperties(fpProperties);
                mBiometricView = fingerprintAndFaceView;
            } else if (fpProperties != null) {
                final AuthBiometricFingerprintView fpView =
                        (AuthBiometricFingerprintView) layoutInflater.inflate(
                                R.layout.auth_biometric_fingerprint_view, null, false);
                fpView.setSensorProperties(fpProperties);
                mBiometricView = fpView;
            } else if (faceProperties != null) {
                mBiometricView = (AuthBiometricFaceView) layoutInflater.inflate(
                        R.layout.auth_biometric_face_view, null, false);
            } else {
                Log.e(TAG, "No sensors found!");
            }
        }

        // init view before showing
        if (mBiometricView != null) {
            mBiometricView.setRequireConfirmation(mConfig.mRequireConfirmation);
            mBiometricView.setPanelController(mPanelController);
            mBiometricView.setPromptInfo(mConfig.mPromptInfo);
            mBiometricView.setCallback(mBiometricCallback);
            mBiometricView.setBackgroundView(mBackgroundView);
            mBiometricView.setUserId(mConfig.mUserId);
            mBiometricView.setEffectiveUserId(mEffectiveUserId);
        }

        // TODO: De-dupe the logic with AuthCredentialPasswordView
        setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                sendEarlyUserCanceled();
                animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            }
            return true;
        });

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusableInTouchMode(true);
        requestFocus();
    }

    void sendEarlyUserCanceled() {
        mConfig.mCallback.onSystemEvent(
                BiometricConstants.BIOMETRIC_SYSTEM_EVENT_EARLY_USER_CANCEL);
    }

    @Override
    public boolean isAllowDeviceCredentials() {
        return Utils.isDeviceCredentialAllowed(mConfig.mPromptInfo);
    }

    /**
     * Adds the credential view. When going from biometric to credential view, the biometric
     * view starts the panel expansion animation. If the credential view is being shown first,
     * it should own the panel expansion.
     * @param animatePanel if the credential view needs to own the panel expansion animation
     */
    private void addCredentialView(boolean animatePanel, boolean animateContents) {
        final LayoutInflater factory = LayoutInflater.from(mContext);

        @Utils.CredentialType final int credentialType = Utils.getCredentialType(
                mLockPatternUtils, mEffectiveUserId);

        switch (credentialType) {
            case Utils.CREDENTIAL_PATTERN:
                mCredentialView = (AuthCredentialView) factory.inflate(
                        R.layout.auth_credential_pattern_view, null, false);
                break;
            case Utils.CREDENTIAL_PIN:
            case Utils.CREDENTIAL_PASSWORD:
                mCredentialView = (AuthCredentialView) factory.inflate(
                        R.layout.auth_credential_password_view, null, false);
                break;
            default:
                throw new IllegalStateException("Unknown credential type: " + credentialType);
        }

        // The background is used for detecting taps / cancelling authentication. Since the
        // credential view is full-screen and should not be canceled from background taps,
        // disable it.
        mBackgroundView.setOnClickListener(null);
        mBackgroundView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        mCredentialView.setContainerView(this);
        mCredentialView.setUserId(mConfig.mUserId);
        mCredentialView.setOperationId(mConfig.mOperationId);
        mCredentialView.setEffectiveUserId(mEffectiveUserId);
        mCredentialView.setCredentialType(credentialType);
        mCredentialView.setCallback(mCredentialCallback);
        mCredentialView.setPromptInfo(mConfig.mPromptInfo);
        mCredentialView.setPanelController(mPanelController, animatePanel);
        mCredentialView.setShouldAnimateContents(animateContents);
        mCredentialView.setBackgroundExecutor(mBackgroundExecutor);
        mFrameLayout.addView(mCredentialView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mPanelController.setContainerDimensions(getMeasuredWidth(), getMeasuredHeight());
    }

    @Override
    public void onOrientationChanged() {
        maybeUpdatePositionForUdfps(true /* invalidate */);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mWakefulnessLifecycle.addObserver(this);

        if (Utils.isBiometricAllowed(mConfig.mPromptInfo)) {
            mBiometricScrollView.addView(mBiometricView);
        } else if (Utils.isDeviceCredentialAllowed(mConfig.mPromptInfo)) {
            addCredentialView(true /* animatePanel */, false /* animateContents */);
        } else {
            throw new IllegalStateException("Unknown configuration: "
                    + mConfig.mPromptInfo.getAuthenticators());
        }

        maybeUpdatePositionForUdfps(false /* invalidate */);

        if (mConfig.mSkipIntro) {
            mContainerState = STATE_SHOWING;
        } else {
            mContainerState = STATE_ANIMATING_IN;
            // The background panel and content are different views since we need to be able to
            // animate them separately in other places.
            mPanelView.setY(mTranslationY);
            mBiometricScrollView.setY(mTranslationY);

            setAlpha(0f);
            final long animateDuration = mConfig.mSkipAnimation ? 0 : ANIMATION_DURATION_SHOW_MS;
            postOnAnimation(() -> {
                mPanelView.animate()
                        .translationY(0)
                        .setDuration(animateDuration)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .withEndAction(this::onDialogAnimatedIn)
                        .start();
                mBiometricScrollView.animate()
                        .translationY(0)
                        .setDuration(animateDuration)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
                if (mCredentialView != null && mCredentialView.isAttachedToWindow()) {
                    mCredentialView.setY(mTranslationY);
                    mCredentialView.animate()
                            .translationY(0)
                            .setDuration(animateDuration)
                            .setInterpolator(mLinearOutSlowIn)
                            .withLayer()
                            .start();
                }
                animate()
                        .alpha(1f)
                        .setDuration(animateDuration)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
            });
        }
    }

    private static boolean shouldUpdatePositionForUdfps(@NonNull View view) {
        if (view instanceof AuthBiometricFingerprintView) {
            return ((AuthBiometricFingerprintView) view).isUdfps();
        }

        return false;
    }

    private boolean maybeUpdatePositionForUdfps(boolean invalidate) {
        final Display display = getDisplay();
        if (display == null) {
            return false;
        }
        if (!shouldUpdatePositionForUdfps(mBiometricView)) {
            return false;
        }

        final int displayRotation = display.getRotation();
        switch (displayRotation) {
            case Surface.ROTATION_0:
                mPanelController.setPosition(AuthPanelController.POSITION_BOTTOM);
                setScrollViewGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
                break;

            case Surface.ROTATION_90:
                mPanelController.setPosition(AuthPanelController.POSITION_RIGHT);
                setScrollViewGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
                break;

            case Surface.ROTATION_270:
                mPanelController.setPosition(AuthPanelController.POSITION_LEFT);
                setScrollViewGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                break;

            case Surface.ROTATION_180:
            default:
                Log.e(TAG, "Unsupported display rotation: " + displayRotation);
                mPanelController.setPosition(AuthPanelController.POSITION_BOTTOM);
                setScrollViewGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
                break;
        }

        if (invalidate) {
            mPanelView.invalidateOutline();
            mBiometricView.requestLayout();
        }

        return true;
    }

    private void setScrollViewGravity(int gravity) {
        final FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) mBiometricScrollView.getLayoutParams();
        params.gravity = gravity;
        mBiometricScrollView.setLayoutParams(params);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mWakefulnessLifecycle.removeObserver(this);
    }

    @Override
    public void onStartedGoingToSleep() {
        animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
    }

    @Override
    public void show(WindowManager wm, @Nullable Bundle savedState) {
        if (mBiometricView != null) {
            mBiometricView.restoreState(savedState);
        }
        wm.addView(this, getLayoutParams(mWindowToken, mConfig.mPromptInfo.getTitle()));
    }

    @Override
    public void dismissWithoutCallback(boolean animate) {
        if (animate) {
            animateAway(false /* sendReason */, 0 /* reason */);
        } else {
            removeWindowIfAttached();
        }
    }

    @Override
    public void dismissFromSystemServer() {
        animateAway(false /* sendReason */, 0 /* reason */);
    }

    @Override
    public void onAuthenticationSucceeded(@Modality int modality) {
        mBiometricView.onAuthenticationSucceeded(modality);
    }

    @Override
    public void onAuthenticationFailed(@Modality int modality, String failureReason) {
        mFailedModalities.add(modality);
        mBiometricView.onAuthenticationFailed(modality, failureReason);
    }

    @Override
    public void onHelp(@Modality int modality, String help) {
        mBiometricView.onHelp(modality, help);
    }

    @Override
    public void onError(@Modality int modality, String error) {
        mBiometricView.onError(modality, error);
    }

    @Override
    public void onPointerDown() {
        if (mBiometricView.onPointerDown(mFailedModalities)) {
            Log.d(TAG, "retrying failed modalities (pointer down)");
            mBiometricCallback.onAction(AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN);
        }
    }

    @Override
    public void onSaveState(@NonNull Bundle outState) {
        outState.putBoolean(AuthDialog.KEY_CONTAINER_GOING_AWAY,
                mContainerState == STATE_ANIMATING_OUT);
        // In the case where biometric and credential are both allowed, we can assume that
        // biometric isn't showing if credential is showing since biometric is shown first.
        outState.putBoolean(AuthDialog.KEY_BIOMETRIC_SHOWING,
                mBiometricView != null && mCredentialView == null);
        outState.putBoolean(AuthDialog.KEY_CREDENTIAL_SHOWING, mCredentialView != null);

        if (mBiometricView != null) {
            mBiometricView.onSaveState(outState);
        }
    }

    @Override
    public String getOpPackageName() {
        return mConfig.mOpPackageName;
    }

    @Override
    public long getRequestId() {
        return mConfig.mRequestId;
    }

    @Override
    public void animateToCredentialUI() {
        mBiometricView.startTransitionToCredentialUI();
    }

    void animateAway(@AuthDialogCallback.DismissedReason int reason) {
        animateAway(true /* sendReason */, reason);
    }

    private void animateAway(boolean sendReason, @AuthDialogCallback.DismissedReason int reason) {
        if (mContainerState == STATE_ANIMATING_IN) {
            Log.w(TAG, "startDismiss(): waiting for onDialogAnimatedIn");
            mContainerState = STATE_PENDING_DISMISS;
            return;
        }

        if (mContainerState == STATE_ANIMATING_OUT) {
            Log.w(TAG, "Already dismissing, sendReason: " + sendReason + " reason: " + reason);
            return;
        }
        mContainerState = STATE_ANIMATING_OUT;

        if (sendReason) {
            mPendingCallbackReason = reason;
        } else {
            mPendingCallbackReason = null;
        }

        final Runnable endActionRunnable = () -> {
            setVisibility(View.INVISIBLE);
            removeWindowIfAttached();
        };

        final long animateDuration = mConfig.mSkipAnimation ? 0 : ANIMATION_DURATION_AWAY_MS;
        postOnAnimation(() -> {
            mPanelView.animate()
                    .translationY(mTranslationY)
                    .setDuration(animateDuration)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .withEndAction(endActionRunnable)
                    .start();
            mBiometricScrollView.animate()
                    .translationY(mTranslationY)
                    .setDuration(animateDuration)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
            if (mCredentialView != null && mCredentialView.isAttachedToWindow()) {
                mCredentialView.animate()
                        .translationY(mTranslationY)
                        .setDuration(animateDuration)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
            }
            animate()
                    .alpha(0f)
                    .setDuration(animateDuration)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
        });
    }

    private void sendPendingCallbackIfNotNull() {
        Log.d(TAG, "pendingCallback: " + mPendingCallbackReason);
        if (mPendingCallbackReason != null) {
            mConfig.mCallback.onDismissed(mPendingCallbackReason, mCredentialAttestation);
            mPendingCallbackReason = null;
        }
    }

    private void removeWindowIfAttached() {
        sendPendingCallbackIfNotNull();

        if (mContainerState == STATE_GONE) {
            return;
        }
        mContainerState = STATE_GONE;
        if (isAttachedToWindow()) {
            mWindowManager.removeView(this);
        }
    }

    private void onDialogAnimatedIn() {
        if (mContainerState == STATE_PENDING_DISMISS) {
            Log.d(TAG, "onDialogAnimatedIn(): mPendingDismissDialog=true, dismissing now");
            animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            return;
        }
        if (mContainerState == STATE_ANIMATING_OUT || mContainerState == STATE_GONE) {
            Log.d(TAG, "onDialogAnimatedIn(): ignore, already animating out or gone - state: "
                    + mContainerState);
            return;
        }
        mContainerState = STATE_SHOWING;
        if (mBiometricView != null) {
            mConfig.mCallback.onDialogAnimatedIn();
            mBiometricView.onDialogAnimatedIn();
        }
    }

    @VisibleForTesting
    static WindowManager.LayoutParams getLayoutParams(IBinder windowToken, CharSequence title) {
        final int windowFlags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SECURE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                windowFlags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(lp.getFitInsetsTypes() & ~WindowInsets.Type.ime());
        lp.setTitle("BiometricPrompt");
        lp.accessibilityTitle = title;
        lp.token = windowToken;
        return lp;
    }
}
