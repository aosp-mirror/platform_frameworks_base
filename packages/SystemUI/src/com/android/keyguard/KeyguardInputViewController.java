/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.Flags;
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor;
import com.android.systemui.bouncer.ui.BouncerMessageView;
import com.android.systemui.bouncer.ui.binder.BouncerMessageViewBinder;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyboard.data.repository.KeyboardRepository;
import com.android.systemui.log.BouncerLogger;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;


/** Controller for a {@link KeyguardSecurityView}. */
public abstract class KeyguardInputViewController<T extends KeyguardInputView>
        extends ViewController<T> implements KeyguardSecurityView {

    private final SecurityMode mSecurityMode;
    private final KeyguardSecurityCallback mKeyguardSecurityCallback;
    private final EmergencyButtonController mEmergencyButtonController;
    private boolean mPaused;
    protected KeyguardMessageAreaController<BouncerKeyguardMessageArea> mMessageAreaController;

    // The following is used to ignore callbacks from SecurityViews that are no longer current
    // (e.g. face unlock). This avoids unwanted asynchronous events from messing with the
    // state for the current security method.
    private KeyguardSecurityCallback mNullCallback = new KeyguardSecurityCallback() {};
    private final FeatureFlags mFeatureFlags;
    protected final SelectedUserInteractor mSelectedUserInteractor;

    protected KeyguardInputViewController(T view, SecurityMode securityMode,
            KeyguardSecurityCallback keyguardSecurityCallback,
            EmergencyButtonController emergencyButtonController,
            @Nullable KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor) {
        super(view);
        mSecurityMode = securityMode;
        mKeyguardSecurityCallback = keyguardSecurityCallback;
        mEmergencyButtonController = emergencyButtonController;
        mFeatureFlags = featureFlags;
        mSelectedUserInteractor = selectedUserInteractor;
        if (messageAreaControllerFactory != null) {
            try {
                BouncerKeyguardMessageArea kma = view.requireViewById(R.id.bouncer_message_area);
                mMessageAreaController = messageAreaControllerFactory.create(kma);
                mMessageAreaController.init();
                mMessageAreaController.setIsVisible(true);
            } catch (IllegalArgumentException exception) {
                Log.e("KeyguardInputViewController",
                        "Ensure that a BouncerKeyguardMessageArea is included in the layout");
            }
        }
    }

    @Override
    protected void onInit() {
        mEmergencyButtonController.init();
    }

    @Override
    @CallSuper
    protected void onViewAttached() {
        updateMessageAreaVisibility();
        if (TextUtils.isEmpty(mMessageAreaController.getMessage())
                && getInitialMessageResId() != 0) {
            mMessageAreaController.setMessage(
                    mView.getResources().getString(getInitialMessageResId()),
                    /* animate= */ false);
        }
    }

    private void updateMessageAreaVisibility() {
        if (mMessageAreaController == null) return;
        if (Flags.revampedBouncerMessages()) {
            mMessageAreaController.disable();
        } else {
            mMessageAreaController.setIsVisible(true);
        }
    }


    @Override
    protected void onViewDetached() {
    }

    SecurityMode getSecurityMode() {
        return mSecurityMode;
    }

    protected KeyguardSecurityCallback getKeyguardSecurityCallback() {
        if (mPaused) {
            return mNullCallback;
        }

        return mKeyguardSecurityCallback;
    }

    @Override
    public void reset() {
        mMessageAreaController.setMessage("", false);
    }

    @Override
    public void onPause() {
        mPaused = true;
    }

    @Override
    public void onResume(int reason) {
        mPaused = false;
    }

    @Override
    public void showPromptReason(int reason) {
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState, boolean animated) {
    }

    public void startAppearAnimation() {
        mView.startAppearAnimation();
    }

    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(finishRunnable);
    }

    @Override
    public CharSequence getTitle() {
        return mView.getTitle();
    }

    /** Finds the index of this view in the suppplied parent view. */
    public int getIndexIn(KeyguardSecurityViewFlipper view) {
        return view.indexOfChild(mView);
    }

    /** Determines the message to show in the bouncer when it first appears. */
    protected abstract int getInitialMessageResId();

    /**
     * Binds the {@link KeyguardInputView#getBouncerMessageView()} view with the provided context.
     */
    public void bindMessageView(
            @NonNull BouncerMessageInteractor bouncerMessageInteractor,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            BouncerLogger bouncerLogger) {
        BouncerMessageView bouncerMessageView = (BouncerMessageView) mView.getBouncerMessageView();
        if (bouncerMessageView != null) {
            BouncerMessageViewBinder.bind(bouncerMessageView,
                    bouncerMessageInteractor,
                    messageAreaControllerFactory,
                    bouncerLogger);
        }
    }

    /** Factory for a {@link KeyguardInputViewController}. */
    public static class Factory {
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final LockPatternUtils mLockPatternUtils;
        private final LatencyTracker mLatencyTracker;
        private final KeyguardMessageAreaController.Factory mMessageAreaControllerFactory;
        private final InputMethodManager mInputMethodManager;
        private final DelayableExecutor mMainExecutor;
        private final Resources mResources;
        private final LiftToActivateListener mLiftToActivateListener;
        private final TelephonyManager mTelephonyManager;
        private final EmergencyButtonController.Factory mEmergencyButtonControllerFactory;
        private final FalsingCollector mFalsingCollector;
        private final DevicePostureController mDevicePostureController;
        private final KeyguardViewController mKeyguardViewController;
        private final FeatureFlags mFeatureFlags;
        private final SelectedUserInteractor mSelectedUserInteractor;
        private final UiEventLogger mUiEventLogger;
        private final KeyboardRepository mKeyboardRepository;

        @Inject
        public Factory(KeyguardUpdateMonitor keyguardUpdateMonitor,
                LockPatternUtils lockPatternUtils,
                LatencyTracker latencyTracker,
                KeyguardMessageAreaController.Factory messageAreaControllerFactory,
                InputMethodManager inputMethodManager, @Main DelayableExecutor mainExecutor,
                @Main Resources resources, LiftToActivateListener liftToActivateListener,
                TelephonyManager telephonyManager, FalsingCollector falsingCollector,
                EmergencyButtonController.Factory emergencyButtonControllerFactory,
                DevicePostureController devicePostureController,
                KeyguardViewController keyguardViewController,
                FeatureFlags featureFlags, SelectedUserInteractor selectedUserInteractor,
                UiEventLogger uiEventLogger,
                KeyboardRepository keyboardRepository) {
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mLockPatternUtils = lockPatternUtils;
            mLatencyTracker = latencyTracker;
            mMessageAreaControllerFactory = messageAreaControllerFactory;
            mInputMethodManager = inputMethodManager;
            mMainExecutor = mainExecutor;
            mResources = resources;
            mLiftToActivateListener = liftToActivateListener;
            mTelephonyManager = telephonyManager;
            mEmergencyButtonControllerFactory = emergencyButtonControllerFactory;
            mFalsingCollector = falsingCollector;
            mDevicePostureController = devicePostureController;
            mKeyguardViewController = keyguardViewController;
            mFeatureFlags = featureFlags;
            mSelectedUserInteractor = selectedUserInteractor;
            mUiEventLogger = uiEventLogger;
            mKeyboardRepository = keyboardRepository;
        }

        /** Create a new {@link KeyguardInputViewController}. */
        public KeyguardInputViewController create(KeyguardInputView keyguardInputView,
                SecurityMode securityMode, KeyguardSecurityCallback keyguardSecurityCallback) {
            EmergencyButtonController emergencyButtonController =
                    mEmergencyButtonControllerFactory.create(
                            keyguardInputView.findViewById(R.id.emergency_call_button));

            if (keyguardInputView instanceof KeyguardPatternView) {
                return new KeyguardPatternViewController((KeyguardPatternView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mLatencyTracker, mFalsingCollector,
                        emergencyButtonController, mMessageAreaControllerFactory,
                        mDevicePostureController, mFeatureFlags, mSelectedUserInteractor);
            } else if (keyguardInputView instanceof KeyguardPasswordView) {
                return new KeyguardPasswordViewController((KeyguardPasswordView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mInputMethodManager, emergencyButtonController, mMainExecutor, mResources,
                        mFalsingCollector, mKeyguardViewController,
                        mDevicePostureController, mFeatureFlags, mSelectedUserInteractor);
            } else if (keyguardInputView instanceof KeyguardPINView) {
                return new KeyguardPinViewController((KeyguardPINView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mLiftToActivateListener, emergencyButtonController, mFalsingCollector,
                        mDevicePostureController, mFeatureFlags, mSelectedUserInteractor,
                        mUiEventLogger, mKeyboardRepository
                );
            } else if (keyguardInputView instanceof KeyguardSimPinView) {
                return new KeyguardSimPinViewController((KeyguardSimPinView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mLiftToActivateListener, mTelephonyManager, mFalsingCollector,
                        emergencyButtonController, mFeatureFlags, mSelectedUserInteractor,
                        mKeyboardRepository);
            } else if (keyguardInputView instanceof KeyguardSimPukView) {
                return new KeyguardSimPukViewController((KeyguardSimPukView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mLiftToActivateListener, mTelephonyManager, mFalsingCollector,
                        emergencyButtonController, mFeatureFlags, mSelectedUserInteractor,
                        mKeyboardRepository);
            }

            throw new RuntimeException("Unable to find controller for " + keyguardInputView);
        }
    }
}
