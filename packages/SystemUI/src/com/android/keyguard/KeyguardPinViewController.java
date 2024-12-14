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

import static com.android.systemui.flags.Flags.LOCKSCREEN_ENABLE_LANDSCAPE;

import android.view.View;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor;
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

public class KeyguardPinViewController
        extends KeyguardPinBasedInputViewController<KeyguardPINView> {
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DevicePostureController mPostureController;
    private final DevicePostureController.Callback mPostureCallback = posture ->
            mView.onDevicePostureChanged(posture);
    private LockPatternUtils mLockPatternUtils;
    private final FeatureFlags mFeatureFlags;
    private static final int DEFAULT_PIN_LENGTH = 6;
    private static final int MIN_FAILED_PIN_ATTEMPTS = 5;
    private NumPadButton mBackspaceKey;
    private View mOkButton = mView.findViewById(R.id.key_enter);

    private long mPinLength;
    private final UiEventLogger mUiEventLogger;

    private boolean mDisabledAutoConfirmation;

    protected KeyguardPinViewController(KeyguardPINView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode, LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker, LiftToActivateListener liftToActivateListener,
            EmergencyButtonController emergencyButtonController,
            FalsingCollector falsingCollector,
            DevicePostureController postureController, FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor, UiEventLogger uiEventLogger,
            KeyguardKeyboardInteractor keyguardKeyboardInteractor,
            BouncerHapticPlayer bouncerHapticPlayer,
            UserActivityNotifier userActivityNotifier) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, liftToActivateListener,
                emergencyButtonController, falsingCollector, featureFlags, selectedUserInteractor,
                keyguardKeyboardInteractor, bouncerHapticPlayer, userActivityNotifier);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPostureController = postureController;
        mLockPatternUtils = lockPatternUtils;
        mFeatureFlags = featureFlags;
        view.setIsLockScreenLandscapeEnabled(mFeatureFlags.isEnabled(LOCKSCREEN_ENABLE_LANDSCAPE));
        mBackspaceKey = view.findViewById(R.id.delete_button);
        mPinLength = mLockPatternUtils.getPinLength(selectedUserInteractor.getSelectedUserId());
        mUiEventLogger = uiEventLogger;
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                getKeyguardSecurityCallback().reset();
                getKeyguardSecurityCallback().onCancelClicked();
            });
        }
        mPasswordEntry.setUserActivityListener(this::onUserInput);
        mView.onDevicePostureChanged(mPostureController.getDevicePosture());
        mPostureController.addCallback(mPostureCallback);
        if (mFeatureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)) {
            mPasswordEntry.setUsePinShapes(true);
            updateAutoConfirmationState();
        }
    }

    protected void onUserInput() {
        super.onUserInput();
        if (isAutoPinConfirmEnabledInSettings()) {
            updateAutoConfirmationState();
            if (mPasswordEntry.getText().length() == mPinLength
                    && mOkButton.getVisibility() == View.INVISIBLE) {
                mUiEventLogger.log(PinBouncerUiEvent.ATTEMPT_UNLOCK_WITH_AUTO_CONFIRM_FEATURE);
                verifyPasswordAndUnlock();
            }
        }
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mPostureController.removeCallback(mPostureCallback);
    }

    @Override
    public void startAppearAnimation() {
        super.startAppearAnimation();
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(
                mKeyguardUpdateMonitor.needsSlowUnlockTransition(), finishRunnable);
    }

    @Override
    protected void handleAttemptLockout(long elapsedRealtimeDeadline) {
        super.handleAttemptLockout(elapsedRealtimeDeadline);
        updateAutoConfirmationState();
    }

    private void updateAutoConfirmationState() {
        mDisabledAutoConfirmation = mLockPatternUtils.getCurrentFailedPasswordAttempts(
                mSelectedUserInteractor.getSelectedUserId()) >= MIN_FAILED_PIN_ATTEMPTS;
        updateOKButtonVisibility();
        updateBackSpaceVisibility();
        updatePinHinting();
    }

    /**
     * Updates the visibility of the OK button for auto confirm feature
     */
    private void updateOKButtonVisibility() {
        if (isAutoPinConfirmEnabledInSettings() && !mDisabledAutoConfirmation) {
            mOkButton.setVisibility(View.INVISIBLE);
        } else {
            mOkButton.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Updates the visibility and the enabled state of the backspace.
     * Visibility changes are only for auto confirmation configuration.
     */
    private void updateBackSpaceVisibility() {
        boolean isAutoConfirmation = isAutoPinConfirmEnabledInSettings();
        mBackspaceKey.setTransparentMode(/* isTransparentMode= */
                isAutoConfirmation && !mDisabledAutoConfirmation);
        if (isAutoConfirmation) {
            if (mPasswordEntry.getText().length() > 0
                    || mDisabledAutoConfirmation) {
                mBackspaceKey.setVisibility(View.VISIBLE);
            } else {
                mBackspaceKey.setVisibility(View.INVISIBLE);
            }
        }
    }
    /** Updates whether to use pin hinting or not. */
    void updatePinHinting() {
        mPasswordEntry.setIsPinHinting(isAutoPinConfirmEnabledInSettings() && isPinHinting()
                && !mDisabledAutoConfirmation);
    }

    /**
     * Responsible for identifying if PIN hinting is to be enabled or not
     */
    private boolean isPinHinting() {
        return mPinLength == DEFAULT_PIN_LENGTH;
    }

    /**
     * Responsible for identifying if auto confirm is enabled or not in Settings and
     * a valid PIN_LENGTH is stored on the device (though the latter check is only to make it more
     * robust since we only allow enabling PIN confirmation if the user has a valid PIN length
     * saved on device)
     */
    private boolean isAutoPinConfirmEnabledInSettings() {
        //Checks if user has enabled the auto confirm in Settings
        return mLockPatternUtils.isAutoPinConfirmEnabled(
                mSelectedUserInteractor.getSelectedUserId())
                && mPinLength != LockPatternUtils.PIN_LENGTH_UNAVAILABLE;
    }

    /** UI Events for the auto confirmation feature in*/
    enum PinBouncerUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Attempting to unlock the device with the auto confirm feature.")
        ATTEMPT_UNLOCK_WITH_AUTO_CONFIRM_FEATURE(1547);

        private final int mId;

        PinBouncerUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
