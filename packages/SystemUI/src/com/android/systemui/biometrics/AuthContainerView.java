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

import static com.android.internal.jank.InteractionJankMonitor.CUJ_BIOMETRIC_PROMPT_TRANSITION;

import android.animation.Animator;
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
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.biometrics.AuthController.ScaleFactorProvider;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.PrintWriter;
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

    private static final float BACKGROUND_DIM_AMOUNT = 0.5f;

    /** Shows biometric prompt dialog animation. */
    private static final String SHOW = "show";
    /** Dismiss biometric prompt dialog animation.  */
    private static final String DISMISS = "dismiss";
    /** Transit biometric prompt dialog to pin, password, pattern credential panel. */
    private static final String TRANSIT = "transit";

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
    private final InteractionJankMonitor mInteractionJankMonitor;

    @VisibleForTesting final BiometricCallback mBiometricCallback;

    @Nullable private AuthBiometricView mBiometricView;
    @VisibleForTesting @Nullable AuthCredentialView mCredentialView;
    private final AuthPanelController mPanelController;
    private final FrameLayout mFrameLayout;
    private final ImageView mBackgroundView;
    private final ScrollView mBiometricScrollView;
    private final View mPanelView;
    private final float mTranslationY;
    @VisibleForTesting @ContainerState int mContainerState = STATE_UNKNOWN;
    private final Set<Integer> mFailedModalities = new HashSet<Integer>();
    private OnBackInvokedDispatcher mOnBackInvokedDispatcher;
    private final OnBackInvokedCallback mBackCallback = this::onBackInvoked;

    private final @Background DelayableExecutor mBackgroundExecutor;
    private boolean mIsOrientationChanged = false;

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
        ScaleFactorProvider mScaleProvider;
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

        public Builder setScaleFactorProvider(ScaleFactorProvider scaleProvider) {
            mConfig.mScaleProvider = scaleProvider;
            return this;
        }

        public AuthContainerView build(@Background DelayableExecutor bgExecutor, int[] sensorIds,
                @Nullable List<FingerprintSensorPropertiesInternal> fpProps,
                @Nullable List<FaceSensorPropertiesInternal> faceProps,
                @NonNull WakefulnessLifecycle wakefulnessLifecycle,
                @NonNull UserManager userManager,
                @NonNull LockPatternUtils lockPatternUtils,
                @NonNull InteractionJankMonitor jankMonitor) {
            mConfig.mSensorIds = sensorIds;
            return new AuthContainerView(mConfig, fpProps, faceProps, wakefulnessLifecycle,
                    userManager, lockPatternUtils, jankMonitor, new Handler(Looper.getMainLooper()),
                    bgExecutor);
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
                    mConfig.mCallback.onTryAgainPressed(getRequestId());
                    break;
                case AuthBiometricView.Callback.ACTION_ERROR:
                    animateAway(AuthDialogCallback.DISMISSED_ERROR);
                    break;
                case AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL:
                    mConfig.mCallback.onDeviceCredentialPressed(getRequestId());
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
            @NonNull InteractionJankMonitor jankMonitor,
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
        mInteractionJankMonitor = jankMonitor;

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
                fingerprintAndFaceView.setScaleFactorProvider(config.mScaleProvider);
                fingerprintAndFaceView.updateOverrideIconLayoutParamsSize();
                mBiometricView = fingerprintAndFaceView;
            } else if (fpProperties != null) {
                final AuthBiometricFingerprintView fpView =
                        (AuthBiometricFingerprintView) layoutInflater.inflate(
                                R.layout.auth_biometric_fingerprint_view, null, false);
                fpView.setSensorProperties(fpProperties);
                fpView.setScaleFactorProvider(config.mScaleProvider);
                fpView.updateOverrideIconLayoutParamsSize();
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
            mBiometricView.setJankListener(getJankListener(mBiometricView, TRANSIT,
                    AuthDialog.ANIMATE_MEDIUM_TO_LARGE_DURATION_MS));
        }

        // TODO: De-dupe the logic with AuthCredentialPasswordView
        setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                onBackInvoked();
            }
            return true;
        });

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusableInTouchMode(true);
        requestFocus();
    }

    private void onBackInvoked() {
        sendEarlyUserCanceled();
        animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
    }

    void sendEarlyUserCanceled() {
        mConfig.mCallback.onSystemEvent(
                BiometricConstants.BIOMETRIC_SYSTEM_EVENT_EARLY_USER_CANCEL, getRequestId());
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
        mIsOrientationChanged = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            //it's a workaround to avoid closing BP incorrectly
            //BP gets a onWindowFocusChanged(false) and then gets a onWindowFocusChanged(true)
            if (mIsOrientationChanged) {
                mIsOrientationChanged = false;
                return;
            }
            Log.v(TAG, "Lost window focus, dismissing the dialog");
            animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
        }
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
            setY(mTranslationY);
            setAlpha(0f);
            final long animateDuration = mConfig.mSkipAnimation ? 0 : ANIMATION_DURATION_SHOW_MS;
            postOnAnimation(() -> {
                animate()
                        .alpha(1f)
                        .translationY(0)
                        .setDuration(animateDuration)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .setListener(getJankListener(this, SHOW, animateDuration))
                        .withEndAction(this::onDialogAnimatedIn)
                        .start();
            });
        }
        mOnBackInvokedDispatcher = findOnBackInvokedDispatcher();
        if (mOnBackInvokedDispatcher != null) {
            mOnBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);
        }
    }

    private Animator.AnimatorListener getJankListener(View v, String type, long timeout) {
        return new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(@androidx.annotation.NonNull Animator animation) {
                if (!v.isAttachedToWindow()) {
                    Log.w(TAG, "Un-attached view should not begin Jank trace.");
                    return;
                }
                mInteractionJankMonitor.begin(InteractionJankMonitor.Configuration.Builder.withView(
                        CUJ_BIOMETRIC_PROMPT_TRANSITION, v).setTag(type).setTimeout(timeout));
            }

            @Override
            public void onAnimationEnd(@androidx.annotation.NonNull Animator animation) {
                if (!v.isAttachedToWindow()) {
                    Log.w(TAG, "Un-attached view should not end Jank trace.");
                    return;
                }
                mInteractionJankMonitor.end(CUJ_BIOMETRIC_PROMPT_TRANSITION);
            }

            @Override
            public void onAnimationCancel(@androidx.annotation.NonNull Animator animation) {
                if (!v.isAttachedToWindow()) {
                    Log.w(TAG, "Un-attached view should not cancel Jank trace.");
                    return;
                }
                mInteractionJankMonitor.cancel(CUJ_BIOMETRIC_PROMPT_TRANSITION);
            }

            @Override
            public void onAnimationRepeat(@androidx.annotation.NonNull Animator animation) {
                // no-op
            }
        };
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
        if (mOnBackInvokedDispatcher != null) {
            mOnBackInvokedDispatcher.unregisterOnBackInvokedCallback(mBackCallback);
            mOnBackInvokedDispatcher = null;
        }
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

        if (savedState != null) {
            mIsOrientationChanged = savedState.getBoolean(
                    AuthDialog.KEY_BIOMETRIC_ORIENTATION_CHANGED);
        }

        wm.addView(this, getLayoutParams(mWindowToken, mConfig.mPromptInfo.getTitle()));
    }

    private void forceExecuteAnimatedIn() {
        if (mContainerState == STATE_ANIMATING_IN) {
            //clear all animators
            if (mCredentialView != null && mCredentialView.isAttachedToWindow()) {
                mCredentialView.animate().cancel();
            }
            mPanelView.animate().cancel();
            mBiometricView.animate().cancel();
            animate().cancel();
            onDialogAnimatedIn();
        }
    }

    @Override
    public void dismissWithoutCallback(boolean animate) {
        if (animate) {
            animateAway(false /* sendReason */, 0 /* reason */);
        } else {
            forceExecuteAnimatedIn();
            removeWindowIfAttached();
        }
    }

    @Override
    public void dismissFromSystemServer() {
        animateAway(false /* sendReason */, 0 /* reason */);
    }

    @Override
    public void onAuthenticationSucceeded(@Modality int modality) {
        if (mBiometricView != null) {
            mBiometricView.onAuthenticationSucceeded(modality);
        } else {
            Log.e(TAG, "onAuthenticationSucceeded(): mBiometricView is null");
        }
    }

    @Override
    public void onAuthenticationFailed(@Modality int modality, String failureReason) {
        if (mBiometricView != null) {
            mFailedModalities.add(modality);
            mBiometricView.onAuthenticationFailed(modality, failureReason);
        } else {
            Log.e(TAG, "onAuthenticationFailed(): mBiometricView is null");
        }
    }

    @Override
    public void onHelp(@Modality int modality, String help) {
        if (mBiometricView != null) {
            mBiometricView.onHelp(modality, help);
        } else {
            Log.e(TAG, "onHelp(): mBiometricView is null");
        }
    }

    @Override
    public void onError(@Modality int modality, String error) {
        if (mBiometricView != null) {
            mBiometricView.onError(modality, error);
        } else {
            Log.e(TAG, "onError(): mBiometricView is null");
        }
    }

    @Override
    public void onPointerDown() {
        if (mBiometricView != null) {
            if (mBiometricView.onPointerDown(mFailedModalities)) {
                Log.d(TAG, "retrying failed modalities (pointer down)");
                mBiometricCallback.onAction(AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN);
            }
        } else {
            Log.e(TAG, "onPointerDown(): mBiometricView is null");
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

        outState.putBoolean(AuthDialog.KEY_BIOMETRIC_ORIENTATION_CHANGED, mIsOrientationChanged);

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
        if (mBiometricView != null) {
            mBiometricView.startTransitionToCredentialUI();
        } else {
            Log.e(TAG, "animateToCredentialUI(): mBiometricView is null");
        }
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

        // Request hiding soft-keyboard before animating away credential UI, in case IME insets
        // animation get delayed by dismissing animation.
        if (isAttachedToWindow() && getRootWindowInsets().isVisible(WindowInsets.Type.ime())) {
            getWindowInsetsController().hide(WindowInsets.Type.ime());
        }

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
            animate()
                    .alpha(0f)
                    .translationY(mTranslationY)
                    .setDuration(animateDuration)
                    .setInterpolator(mLinearOutSlowIn)
                    .setListener(getJankListener(this, DISMISS, animateDuration))
                    .setUpdateListener(animation -> {
                        if (mWindowManager == null || getViewRootImpl() == null) {
                            Log.w(TAG, "skip updateViewLayout() for dim animation.");
                            return;
                        }
                        final WindowManager.LayoutParams lp = getViewRootImpl().mWindowAttributes;
                        lp.dimAmount = (1.0f - (Float) animation.getAnimatedValue())
                                * BACKGROUND_DIM_AMOUNT;
                        mWindowManager.updateViewLayout(this, lp);
                    })
                    .withLayer()
                    .withEndAction(endActionRunnable)
                    .start();
        });
    }

    private void sendPendingCallbackIfNotNull() {
        Log.d(TAG, "pendingCallback: " + mPendingCallbackReason);
        if (mPendingCallbackReason != null) {
            mConfig.mCallback.onDismissed(mPendingCallbackReason,
                    mCredentialAttestation, getRequestId());
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
            mWindowManager.removeViewImmediate(this);
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
            mConfig.mCallback.onDialogAnimatedIn(getRequestId());
            mBiometricView.onDialogAnimatedIn();
        }
    }

    @VisibleForTesting
    static WindowManager.LayoutParams getLayoutParams(IBinder windowToken, CharSequence title) {
        final int windowFlags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
                windowFlags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(lp.getFitInsetsTypes() & ~WindowInsets.Type.ime());
        lp.setTitle("BiometricPrompt");
        lp.accessibilityTitle = title;
        lp.dimAmount = BACKGROUND_DIM_AMOUNT;
        lp.token = windowToken;
        return lp;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("    isAttachedToWindow=" + isAttachedToWindow());
        pw.println("    containerState=" + mContainerState);
        pw.println("    pendingCallbackReason=" + mPendingCallbackReason);
        pw.println("    config exist=" + (mConfig != null));
        if (mConfig != null) {
            pw.println("    config.sensorIds exist=" + (mConfig.mSensorIds != null));
        }
        final AuthBiometricView biometricView = mBiometricView;
        pw.println("    scrollView=" + findViewById(R.id.biometric_scrollview));
        pw.println("      biometricView=" + biometricView);
        if (biometricView != null) {
            int[] ids = {
                    R.id.title,
                    R.id.subtitle,
                    R.id.description,
                    R.id.biometric_icon_frame,
                    R.id.biometric_icon,
                    R.id.indicator,
                    R.id.button_bar,
                    R.id.button_negative,
                    R.id.button_use_credential,
                    R.id.button_confirm,
                    R.id.button_try_again
            };
            for (final int id: ids) {
                pw.println("        " + biometricView.findViewById(id));
            }
        }
    }
}
