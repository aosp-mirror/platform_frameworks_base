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

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_BIOMETRIC_PROMPT_TRANSITION;
import static com.android.systemui.Flags.enableViewCaptureTracing;

import android.animation.Animator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.Flags;
import android.hardware.biometrics.PromptInfo;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.app.animation.Interpolators;
import com.android.app.viewcapture.ViewCapture;
import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.biometrics.AuthController.ScaleFactorProvider;
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor;
import com.android.systemui.biometrics.shared.model.BiometricModalities;
import com.android.systemui.biometrics.shared.model.PromptKind;
import com.android.systemui.biometrics.ui.CredentialView;
import com.android.systemui.biometrics.ui.binder.BiometricViewBinder;
import com.android.systemui.biometrics.ui.binder.BiometricViewSizeBinder;
import com.android.systemui.biometrics.ui.binder.Spaghetti;
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel;
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.util.concurrency.DelayableExecutor;

import com.google.android.msdl.domain.MSDLPlayer;

import kotlin.Lazy;

import kotlinx.coroutines.CoroutineScope;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Provider;

/**
 * Top level container/controller for the BiometricPrompt UI.
 *
 * @deprecated TODO(b/287311775): remove and merge view/layouts into new prompt.
 */
@Deprecated
public class AuthContainerView extends LinearLayout
        implements AuthDialog, WakefulnessLifecycle.Observer, CredentialView.Host {

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
    private final IBinder mWindowToken = new Binder();
    private final ViewCaptureAwareWindowManager mWindowManager;
    private final Interpolator mLinearOutSlowIn;
    private final LockPatternUtils mLockPatternUtils;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final CoroutineScope mApplicationCoroutineScope;

    // TODO(b/287311775): these should be migrated out once ready
    private final @NonNull Provider<PromptSelectorInteractor> mPromptSelectorInteractorProvider;
    // TODO(b/287311775): these should be migrated out of the view
    private final Provider<CredentialViewModel> mCredentialViewModelProvider;
    private final PromptViewModel mPromptViewModel;

    @VisibleForTesting final BiometricCallback mBiometricCallback;

    @Nullable private Spaghetti mBiometricView;
    @Nullable private View mCredentialView;
    private final AuthPanelController mPanelController;
    private final ViewGroup mLayout;
    private final ImageView mBackgroundView;
    private final View mPanelView;
    private final float mTranslationY;
    @VisibleForTesting @ContainerState int mContainerState = STATE_UNKNOWN;
    private final Set<Integer> mFailedModalities = new HashSet<Integer>();
    private final OnBackInvokedCallback mBackCallback = this::onBackInvoked;

    private final @Background DelayableExecutor mBackgroundExecutor;

    private final MSDLPlayer mMSDLPlayer;

    // Non-null only if the dialog is in the act of dismissing and has not sent the reason yet.
    @Nullable @AuthDialogCallback.DismissedReason private Integer mPendingCallbackReason;
    // HAT received from LockSettingsService when credential is verified.
    @Nullable private byte[] mCredentialAttestation;

    // TODO(b/313469218): remove when legacy prompt is replaced
    @Deprecated
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
        ScaleFactorProvider mScaleProvider;
    }

    @VisibleForTesting
    final class BiometricCallback implements Spaghetti.Callback {
        @Override
        public void onAuthenticated() {
            animateAway(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED);
        }

        @Override
        public void onUserCanceled() {
            sendEarlyUserCanceled();
            animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
        }

        @Override
        public void onButtonNegative() {
            animateAway(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE);
        }

        @Override
        public void onButtonTryAgain() {
            mFailedModalities.clear();
            mConfig.mCallback.onTryAgainPressed(getRequestId());
        }

        @Override
        public void onContentViewMoreOptionsButtonPressed() {
            animateAway(AuthDialogCallback.DISMISSED_BUTTON_CONTENT_VIEW_MORE_OPTIONS);
        }

        @Override
        public void onError() {
            animateAway(AuthDialogCallback.DISMISSED_ERROR);
        }

        @Override
        public void onUseDeviceCredential() {
            mConfig.mCallback.onDeviceCredentialPressed(getRequestId());
            addCredentialView(false /* animatePanel */, true /* animateContents */);

            // TODO(b/313469218): Remove Config
            mConfig.mPromptInfo.setAuthenticators(Authenticators.DEVICE_CREDENTIAL);
        }

        @Override
        public void onStartDelayedFingerprintSensor() {
            mConfig.mCallback.onStartFingerprintNow(getRequestId());
        }

        @Override
        public void onAuthenticatedAndConfirmed() {
            animateAway(AuthDialogCallback.DISMISSED_BUTTON_POSITIVE);
        }
    }

    @Override
    public void onCredentialMatched(@NonNull byte[] attestation) {
        mCredentialAttestation = attestation;
        animateAway(AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED);
    }

    @Override
    public void onCredentialAborted() {
        sendEarlyUserCanceled();
        animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
    }

    @Override
    public void onCredentialAttemptsRemaining(int remaining, @NonNull String messageBody) {
        // Only show dialog if <=1 attempts are left before wiping.
        if (remaining == 1) {
            showLastAttemptBeforeWipeDialog(messageBody);
        } else if (remaining <= 0) {
            showNowWipingDialog(messageBody);
        }
    }

    private void showLastAttemptBeforeWipeDialog(@NonNull String messageBody) {
        final AlertDialog alertDialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.biometric_dialog_last_attempt_before_wipe_dialog_title)
                .setMessage(messageBody)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        alertDialog.show();
    }

    private void showNowWipingDialog(@NonNull String messageBody) {
        final AlertDialog alertDialog = new AlertDialog.Builder(mContext)
                .setMessage(messageBody)
                .setPositiveButton(
                        com.android.settingslib.R.string.failed_attempts_now_wiping_dialog_dismiss,
                        null /* OnClickListener */)
                .setOnDismissListener(
                        dialog -> animateAway(AuthDialogCallback.DISMISSED_ERROR))
                .create();
        alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        alertDialog.show();
    }

    // TODO(b/251476085): remove Config and further decompose these properties out of view classes
    AuthContainerView(@NonNull Config config,
            @NonNull CoroutineScope applicationCoroutineScope,
            @Nullable List<FingerprintSensorPropertiesInternal> fpProps,
            @Nullable List<FaceSensorPropertiesInternal> faceProps,
            @NonNull WakefulnessLifecycle wakefulnessLifecycle,
            @NonNull UserManager userManager,
            @NonNull LockPatternUtils lockPatternUtils,
            @NonNull InteractionJankMonitor jankMonitor,
            @NonNull Provider<PromptSelectorInteractor> promptSelectorInteractorProvider,
            @NonNull PromptViewModel promptViewModel,
            @NonNull Provider<CredentialViewModel> credentialViewModelProvider,
            @NonNull @Background DelayableExecutor bgExecutor,
            @NonNull VibratorHelper vibratorHelper,
            Lazy<ViewCapture> lazyViewCapture,
            @NonNull MSDLPlayer msdlPlayer) {
        super(config.mContext);

        mConfig = config;
        mLockPatternUtils = lockPatternUtils;
        mEffectiveUserId = userManager.getCredentialOwnerProfile(mConfig.mUserId);
        WindowManager wm = getContext().getSystemService(WindowManager.class);
        mWindowManager = new ViewCaptureAwareWindowManager(wm, lazyViewCapture,
                enableViewCaptureTracing());
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mApplicationCoroutineScope = applicationCoroutineScope;

        mPromptViewModel = promptViewModel;
        mTranslationY = getResources()
                .getDimension(R.dimen.biometric_dialog_animation_translation_offset);
        mLinearOutSlowIn = Interpolators.LINEAR_OUT_SLOW_IN;
        mBiometricCallback = new BiometricCallback();
        mMSDLPlayer = msdlPlayer;

        final BiometricModalities biometricModalities = new BiometricModalities(
                Utils.findFirstSensorProperties(fpProps, mConfig.mSensorIds),
                Utils.findFirstSensorProperties(faceProps, mConfig.mSensorIds));

        final boolean isLandscape = mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mPromptSelectorInteractorProvider = promptSelectorInteractorProvider;
        mPromptSelectorInteractorProvider.get().setPrompt(mConfig.mPromptInfo, mEffectiveUserId,
                getRequestId(), biometricModalities, mConfig.mOperationId, mConfig.mOpPackageName,
                false /*onSwitchToCredential*/, isLandscape);

        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        final PromptKind kind = mPromptViewModel.getPromptKind().getValue();
        if (kind.isBiometric()) {
            if (kind.isTwoPaneLandscapeBiometric()) {
                mLayout = (ConstraintLayout) layoutInflater.inflate(
                        R.layout.biometric_prompt_two_pane_layout, this, false /* attachToRoot */);
            } else {
                mLayout = (ConstraintLayout) layoutInflater.inflate(
                        R.layout.biometric_prompt_one_pane_layout, this, false /* attachToRoot */);
            }
        } else {
            mLayout = (FrameLayout) layoutInflater.inflate(
                    R.layout.auth_container_view, this, false /* attachToRoot */);
        }
        addView(mLayout);
        mBackgroundView = mLayout.findViewById(R.id.background);

        mPanelView = mLayout.findViewById(R.id.panel);
        mPanelController = new AuthPanelController(mContext, mPanelView);
        mBackgroundExecutor = bgExecutor;
        mInteractionJankMonitor = jankMonitor;
        mCredentialViewModelProvider = credentialViewModelProvider;

        showPrompt(promptViewModel, vibratorHelper);

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
        requestFocus();
    }

    private void showPrompt(@NonNull PromptViewModel viewModel,
            @NonNull VibratorHelper vibratorHelper) {
        if (mPromptViewModel.getPromptKind().getValue().isBiometric()) {
            addBiometricView(viewModel, vibratorHelper);
        } else if (mPromptViewModel.getPromptKind().getValue().isCredential()) {
            addCredentialView(true, false);
        } else {
            mPromptSelectorInteractorProvider.get().resetPrompt(getRequestId());
        }
    }

    private void addBiometricView(@NonNull PromptViewModel viewModel,
            @NonNull VibratorHelper vibratorHelper) {
        mBiometricView = BiometricViewBinder.bind(mLayout, viewModel,
                // TODO(b/201510778): This uses the wrong timeout in some cases
                getJankListener(mLayout, TRANSIT,
                        BiometricViewSizeBinder.ANIMATE_MEDIUM_TO_LARGE_DURATION_MS),
                mBackgroundView, mBiometricCallback, mApplicationCoroutineScope,
                vibratorHelper, mMSDLPlayer);
    }

    @VisibleForTesting
    public void onBackInvoked() {
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

        PromptKind credentialType = Utils.getCredentialType(mLockPatternUtils, mEffectiveUserId);
        final int layoutResourceId;
        if (credentialType instanceof PromptKind.Pattern) {
            layoutResourceId = R.layout.auth_credential_pattern_view;
        } else if (credentialType instanceof PromptKind.Pin) {
            layoutResourceId = R.layout.auth_credential_pin_view;
        } else if (credentialType instanceof PromptKind.Password) {
            layoutResourceId = R.layout.auth_credential_password_view;
        } else {
            throw new IllegalStateException("Unknown credential type: " + credentialType);
        }
        // TODO(b/288175645): Once AuthContainerView is removed, set 0dp in credential view xml
        //  files with the corresponding left/right or top/bottom constraints being set to "parent".
        mCredentialView = factory.inflate(layoutResourceId, mLayout, false);

        // The background is used for detecting taps / cancelling authentication. Since the
        // credential view is full-screen and should not be canceled from background taps,
        // disable it.
        mBackgroundView.setOnClickListener(null);
        mBackgroundView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        final CredentialViewModel vm = mCredentialViewModelProvider.get();
        vm.setAnimateContents(animateContents);
        ((CredentialView) mCredentialView).init(vm, this, mPanelController, animatePanel,
                mBiometricCallback);

        mLayout.addView(mCredentialView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mPanelController.setContainerDimensions(getMeasuredWidth(), getMeasuredHeight());
    }

    @Override
    public void onOrientationChanged() {
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mContainerState == STATE_ANIMATING_OUT) {
            return;
        }

        mWakefulnessLifecycle.addObserver(this);
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
        OnBackInvokedDispatcher dispatcher = findOnBackInvokedDispatcher();
        if (dispatcher != null) {
            dispatcher.registerOnBackInvokedCallback(
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

    @Override
    public void onDetachedFromWindow() {
        OnBackInvokedDispatcher dispatcher = findOnBackInvokedDispatcher();
        if (dispatcher != null) {
            findOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
        }
        super.onDetachedFromWindow();
        mWakefulnessLifecycle.removeObserver(this);
    }

    @Override
    public void onStartedGoingToSleep() {
        animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
    }

    @Override
    public void show(WindowManager wm) {
        wm.addView(this, getLayoutParams(mWindowToken, mConfig.mPromptInfo.getTitle()));
    }

    private void forceExecuteAnimatedIn() {
        if (mContainerState == STATE_ANIMATING_IN) {
            //clear all animators
            if (mCredentialView != null && mCredentialView.isAttachedToWindow()) {
                mCredentialView.animate().cancel();
            }
            mPanelView.animate().cancel();
            mBiometricView.cancelAnimation();
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
            if (mFailedModalities.contains(TYPE_FACE)) {
                Log.d(TAG, "retrying failed modalities (pointer down)");
                mFailedModalities.remove(TYPE_FACE);
                mBiometricCallback.onButtonTryAgain();
            }
        } else {
            Log.e(TAG, "onPointerDown(): mBiometricView is null");
        }
    }

    @Override
    public String getOpPackageName() {
        return mConfig.mOpPackageName;
    }

    @Override
    public String getClassNameIfItIsConfirmDeviceCredentialActivity() {
        return  mConfig.mPromptInfo.getClassNameIfItIsConfirmDeviceCredentialActivity();
    }

    @Override
    public long getRequestId() {
        return mConfig.mRequestId;
    }

    @Override
    public void animateToCredentialUI(boolean isError) {
        if (mBiometricView != null) {
            mBiometricView.startTransitionToCredentialUI(isError);
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
            if (Flags.customBiometricPrompt()) {
                // TODO(b/288175645): resetPrompt calls should be lifecycle aware
                mPromptSelectorInteractorProvider.get().resetPrompt(getRequestId());
            }
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
            final boolean delayFingerprint = mBiometricView.isCoex() && !mConfig.mRequireConfirmation;
            mConfig.mCallback.onDialogAnimatedIn(getRequestId(), !delayFingerprint);
            mBiometricView.onDialogAnimatedIn(!delayFingerprint);
        }
    }

    @Override
    public PromptViewModel getViewModel() {
        return mPromptViewModel;
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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                windowFlags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(lp.getFitInsetsTypes() & ~WindowInsets.Type.ime()
                & ~WindowInsets.Type.systemBars());
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
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
    }
}
