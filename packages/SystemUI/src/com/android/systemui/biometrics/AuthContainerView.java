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

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricManager.BiometricMultiSensorMode;

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
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.keyguard.WakefulnessLifecycle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Top level container/controller for the BiometricPrompt UI.
 */
public class AuthContainerView extends LinearLayout
        implements AuthDialog, WakefulnessLifecycle.Observer {

    private static final String TAG = "BiometricPrompt/AuthContainerView";
    private static final int ANIMATION_DURATION_SHOW_MS = 250;
    private static final int ANIMATION_DURATION_AWAY_MS = 350; // ms

    static final int STATE_UNKNOWN = 0;
    static final int STATE_ANIMATING_IN = 1;
    static final int STATE_PENDING_DISMISS = 2;
    static final int STATE_SHOWING = 3;
    static final int STATE_ANIMATING_OUT = 4;
    static final int STATE_GONE = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_UNKNOWN, STATE_ANIMATING_IN, STATE_PENDING_DISMISS, STATE_SHOWING,
            STATE_ANIMATING_OUT, STATE_GONE})
    @interface ContainerState {}

    final Config mConfig;
    final int mEffectiveUserId;
    @Nullable private final List<FingerprintSensorPropertiesInternal> mFpProps;
    @Nullable private final List<FaceSensorPropertiesInternal> mFaceProps;
    private final Handler mHandler;
    private final Injector mInjector;
    private final IBinder mWindowToken = new Binder();
    private final WindowManager mWindowManager;
    private final AuthPanelController mPanelController;
    private final Interpolator mLinearOutSlowIn;
    @VisibleForTesting final BiometricCallback mBiometricCallback;
    private final CredentialCallback mCredentialCallback;

    @VisibleForTesting final FrameLayout mFrameLayout;
    @VisibleForTesting @Nullable AuthBiometricView mBiometricView;
    @VisibleForTesting @Nullable AuthCredentialView mCredentialView;

    @VisibleForTesting final ImageView mBackgroundView;
    @VisibleForTesting final ScrollView mBiometricScrollView;
    private final View mPanelView;

    private final float mTranslationY;

    @VisibleForTesting final WakefulnessLifecycle mWakefulnessLifecycle;

    @VisibleForTesting @ContainerState int mContainerState = STATE_UNKNOWN;

    // Non-null only if the dialog is in the act of dismissing and has not sent the reason yet.
    @Nullable @AuthDialogCallback.DismissedReason Integer mPendingCallbackReason;
    // HAT received from LockSettingsService when credential is verified.
    @Nullable byte[] mCredentialAttestation;

    static class Config {
        Context mContext;
        AuthDialogCallback mCallback;
        PromptInfo mPromptInfo;
        boolean mRequireConfirmation;
        int mUserId;
        String mOpPackageName;
        int[] mSensorIds;
        boolean mCredentialAllowed;
        boolean mSkipIntro;
        long mOperationId;
        @BiometricMultiSensorMode int mMultiSensorConfig;
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

        public Builder setOperationId(long operationId) {
            mConfig.mOperationId = operationId;
            return this;
        }

        /** The multi-sensor mode. */
        public Builder setMultiSensorConfig(@BiometricMultiSensorMode int multiSensorConfig) {
            mConfig.mMultiSensorConfig = multiSensorConfig;
            return this;
        }

        public AuthContainerView build(int[] sensorIds, boolean credentialAllowed,
                @Nullable List<FingerprintSensorPropertiesInternal> fpProps,
                @Nullable List<FaceSensorPropertiesInternal> faceProps) {
            mConfig.mSensorIds = sensorIds;
            mConfig.mCredentialAllowed = credentialAllowed;
            return new AuthContainerView(mConfig, new Injector(), fpProps, faceProps);
        }
    }

    public static class Injector {
        ScrollView getBiometricScrollView(FrameLayout parent) {
            return parent.findViewById(R.id.biometric_scrollview);
        }

        FrameLayout inflateContainerView(LayoutInflater factory, ViewGroup root) {
            return (FrameLayout) factory.inflate(
                    R.layout.auth_container_view, root, false /* attachToRoot */);
        }

        AuthPanelController getPanelController(Context context, View panelView) {
            return new AuthPanelController(context, panelView);
        }

        ImageView getBackgroundView(FrameLayout parent) {
            return parent.findViewById(R.id.background);
        }

        View getPanelView(FrameLayout parent) {
            return parent.findViewById(R.id.panel);
        }

        int getAnimateCredentialStartDelayMs() {
            return AuthDialog.ANIMATE_CREDENTIAL_START_DELAY_MS;
        }

        UserManager getUserManager(Context context) {
            return UserManager.get(context);
        }

        int getCredentialType(Context context, int effectiveUserId) {
            return Utils.getCredentialType(context, effectiveUserId);
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
                    mConfig.mCallback.onTryAgainPressed();
                    break;
                case AuthBiometricView.Callback.ACTION_ERROR:
                    animateAway(AuthDialogCallback.DISMISSED_ERROR);
                    break;
                case AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL:
                    mConfig.mCallback.onDeviceCredentialPressed();
                    mHandler.postDelayed(() -> {
                        addCredentialView(false /* animatePanel */, true /* animateContents */);
                    }, mInjector.getAnimateCredentialStartDelayMs());
                    break;
                case AuthBiometricView.Callback.ACTION_START_DELAYED_FINGERPRINT_SENSOR:
                    mConfig.mCallback.onStartFingerprintNow();
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
    AuthContainerView(Config config, Injector injector,
            @Nullable List<FingerprintSensorPropertiesInternal> fpProps,
            @Nullable List<FaceSensorPropertiesInternal> faceProps) {
        super(config.mContext);

        mConfig = config;
        mInjector = injector;
        mFpProps = fpProps;
        mFaceProps = faceProps;

        mEffectiveUserId = mInjector.getUserManager(mContext)
                .getCredentialOwnerProfile(mConfig.mUserId);

        mHandler = new Handler(Looper.getMainLooper());
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mWakefulnessLifecycle = Dependency.get(WakefulnessLifecycle.class);

        mTranslationY = getResources()
                .getDimension(R.dimen.biometric_dialog_animation_translation_offset);
        mLinearOutSlowIn = Interpolators.LINEAR_OUT_SLOW_IN;
        mBiometricCallback = new BiometricCallback();
        mCredentialCallback = new CredentialCallback();

        final LayoutInflater factory = LayoutInflater.from(mContext);
        mFrameLayout = mInjector.inflateContainerView(factory, this);

        mPanelView = mInjector.getPanelView(mFrameLayout);
        mPanelController = mInjector.getPanelController(mContext, mPanelView);

        // Inflate biometric view only if necessary.
        final int sensorCount = config.mSensorIds.length;
        if (Utils.isBiometricAllowed(mConfig.mPromptInfo)) {
            if (sensorCount == 1) {
                final int singleSensorAuthId = config.mSensorIds[0];
                if (Utils.containsSensorId(mFpProps, singleSensorAuthId)) {
                    FingerprintSensorPropertiesInternal sensorProps = null;
                    for (FingerprintSensorPropertiesInternal prop : mFpProps) {
                        if (prop.sensorId == singleSensorAuthId) {
                            sensorProps = prop;
                            break;
                        }
                    }

                    if (sensorProps.isAnyUdfpsType()) {
                        AuthBiometricUdfpsView udfpsView = (AuthBiometricUdfpsView) factory
                                .inflate(R.layout.auth_biometric_udfps_view, null, false);
                        udfpsView.setSensorProps(sensorProps);
                        mBiometricView = udfpsView;
                    } else {
                        mBiometricView = (AuthBiometricFingerprintView) factory
                                .inflate(R.layout.auth_biometric_fingerprint_view, null, false);
                    }
                } else if (Utils.containsSensorId(mFaceProps, singleSensorAuthId)) {
                    mBiometricView = (AuthBiometricFaceView)
                            factory.inflate(R.layout.auth_biometric_face_view, null, false);
                } else {
                    // Unknown sensorId
                    Log.e(TAG, "Unknown sensorId: " + singleSensorAuthId);
                    mBiometricView = null;
                    mBackgroundView = null;
                    mBiometricScrollView = null;
                    return;
                }
            } else if (sensorCount == 2) {
                final int[] allSensors = findFaceAndFingerprintSensors();
                final int faceSensorId = allSensors[0];
                final int fingerprintSensorId = allSensors[1];

                if (fingerprintSensorId == -1 || faceSensorId == -1) {
                    Log.e(TAG, "Missing fingerprint or face for dual-sensor config");
                    mBiometricView = null;
                    mBackgroundView = null;
                    mBiometricScrollView = null;
                    return;
                }

                FingerprintSensorPropertiesInternal fingerprintSensorProps = null;
                for (FingerprintSensorPropertiesInternal prop : mFpProps) {
                    if (prop.sensorId == fingerprintSensorId) {
                        fingerprintSensorProps = prop;
                        break;
                    }
                }

                if (fingerprintSensorProps != null) {
                    final AuthBiometricFaceToFingerprintView faceToFingerprintView =
                            (AuthBiometricFaceToFingerprintView) factory.inflate(
                                    R.layout.auth_biometric_face_to_fingerprint_view, null, false);
                    faceToFingerprintView.setFingerprintSensorProps(fingerprintSensorProps);
                    faceToFingerprintView.setModalityListener(new ModalityListener() {
                        @Override
                        public void onModalitySwitched(int oldModality, int newModality) {
                            maybeUpdatePositionForUdfps(true /* invalidate */);
                        }
                    });
                    mBiometricView = faceToFingerprintView;
                } else {
                    Log.e(TAG, "Fingerprint props not found for sensor ID: " + fingerprintSensorId);
                    mBiometricView = null;
                    mBackgroundView = null;
                    mBiometricScrollView = null;
                    return;
                }
            } else {
                Log.e(TAG, "Unsupported sensor array, length: " + sensorCount);
                mBiometricView = null;
                mBackgroundView = null;
                mBiometricScrollView = null;
                return;
            }
        }

        mBiometricScrollView = mInjector.getBiometricScrollView(mFrameLayout);
        mBackgroundView = mInjector.getBackgroundView(mFrameLayout);

        addView(mFrameLayout);

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

    private void addBiometricView() {
        mBiometricScrollView.addView(mBiometricView);
    }

    /**
     * Adds the credential view. When going from biometric to credential view, the biometric
     * view starts the panel expansion animation. If the credential view is being shown first,
     * it should own the panel expansion.
     * @param animatePanel if the credential view needs to own the panel expansion animation
     */
    private void addCredentialView(boolean animatePanel, boolean animateContents) {
        final LayoutInflater factory = LayoutInflater.from(mContext);

        final @Utils.CredentialType int credentialType = mInjector.getCredentialType(
                mContext, mEffectiveUserId);

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
        mFrameLayout.addView(mCredentialView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mPanelController.setContainerDimensions(getMeasuredWidth(), getMeasuredHeight());
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        onAttachedToWindowInternal();
    }

    @VisibleForTesting
    void onAttachedToWindowInternal() {
        mWakefulnessLifecycle.addObserver(this);

        if (Utils.isBiometricAllowed(mConfig.mPromptInfo)) {
            addBiometricView();
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
            postOnAnimation(() -> {
                mPanelView.animate()
                        .translationY(0)
                        .setDuration(ANIMATION_DURATION_SHOW_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .withEndAction(this::onDialogAnimatedIn)
                        .start();
                mBiometricScrollView.animate()
                        .translationY(0)
                        .setDuration(ANIMATION_DURATION_SHOW_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
                if (mCredentialView != null && mCredentialView.isAttachedToWindow()) {
                    mCredentialView.setY(mTranslationY);
                    mCredentialView.animate()
                            .translationY(0)
                            .setDuration(ANIMATION_DURATION_SHOW_MS)
                            .setInterpolator(mLinearOutSlowIn)
                            .withLayer()
                            .start();
                }
                animate()
                        .alpha(1f)
                        .setDuration(ANIMATION_DURATION_SHOW_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
            });
        }
    }

    private static boolean shouldUpdatePositionForUdfps(@NonNull View view) {
        if (view instanceof AuthBiometricUdfpsView) {
            return true;
        }

        if (view instanceof AuthBiometricFaceToFingerprintView) {
            AuthBiometricFaceToFingerprintView faceToFingerprintView =
                    (AuthBiometricFaceToFingerprintView) view;
            return faceToFingerprintView.getActiveSensorType() == TYPE_FINGERPRINT
                    && faceToFingerprintView.isFingerprintUdfps();
        }

        return false;
    }

    private boolean maybeUpdatePositionForUdfps(boolean invalidate) {
        if (!shouldUpdatePositionForUdfps(mBiometricView)) {
            return false;
        }

        final int displayRotation = getDisplay().getRotation();
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
    public void onAuthenticationSucceeded() {
        mBiometricView.onAuthenticationSucceeded();
    }

    @Override
    public void onAuthenticationFailed(@Modality int modality, String failureReason) {
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
    public void onSaveState(@NonNull Bundle outState) {
        outState.putInt(AuthDialog.KEY_CONTAINER_STATE, mContainerState);
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
    public void animateToCredentialUI() {
        mBiometricView.startTransitionToCredentialUI();
    }

    @VisibleForTesting
    void animateAway(int reason) {
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

        postOnAnimation(() -> {
            mPanelView.animate()
                    .translationY(mTranslationY)
                    .setDuration(ANIMATION_DURATION_AWAY_MS)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .withEndAction(endActionRunnable)
                    .start();
            mBiometricScrollView.animate()
                    .translationY(mTranslationY)
                    .setDuration(ANIMATION_DURATION_AWAY_MS)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
            if (mCredentialView != null && mCredentialView.isAttachedToWindow()) {
                mCredentialView.animate()
                        .translationY(mTranslationY)
                        .setDuration(ANIMATION_DURATION_AWAY_MS)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
            }
            animate()
                    .alpha(0f)
                    .setDuration(ANIMATION_DURATION_AWAY_MS)
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
        mWindowManager.removeView(this);
    }

    @VisibleForTesting
    void onDialogAnimatedIn() {
        if (mContainerState == STATE_PENDING_DISMISS) {
            Log.d(TAG, "onDialogAnimatedIn(): mPendingDismissDialog=true, dismissing now");
            animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            return;
        }
        mContainerState = STATE_SHOWING;
        if (mBiometricView != null) {
            mConfig.mCallback.onDialogAnimatedIn();
            mBiometricView.onDialogAnimatedIn();
        }
    }

    @VisibleForTesting
    static WindowManager.LayoutParams getLayoutParams(IBinder windowToken,
            CharSequence title) {
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

    // returns [face, fingerprint] sensor ids (id is -1 if not present)
    private int[] findFaceAndFingerprintSensors() {
        int faceSensorId = -1;
        int fingerprintSensorId = -1;

        for (final int sensorId : mConfig.mSensorIds) {
            if (Utils.containsSensorId(mFpProps, sensorId)) {
                fingerprintSensorId = sensorId;
            } else if (Utils.containsSensorId(mFaceProps, sensorId)) {
                faceSensorId = sensorId;
            }

            if (fingerprintSensorId != -1 && faceSensorId != -1) {
                break;
            }
        }

        return new int[] {faceSensorId, fingerprintSensorId};
    }
}
