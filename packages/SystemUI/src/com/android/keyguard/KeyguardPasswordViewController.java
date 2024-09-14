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
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor;
import com.android.systemui.Flags;
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.List;

public class KeyguardPasswordViewController
        extends KeyguardAbsKeyInputViewController<KeyguardPasswordView> {

    private final KeyguardKeyboardInteractor mKeyguardKeyboardInteractor;
    private final KeyguardSecurityCallback mKeyguardSecurityCallback;
    private final DevicePostureController mPostureController;
    private final DevicePostureController.Callback mPostureCallback = posture ->
            mView.onDevicePostureChanged(posture);
    private final InputMethodManager mInputMethodManager;
    private final DelayableExecutor mMainExecutor;
    private final KeyguardViewController mKeyguardViewController;
    private final boolean mShowImeAtScreenOn;
    private Drawable mDefaultPasswordFieldBackground;
    private Drawable mFocusedPasswordFieldBackground;
    private EditText mPasswordEntry;
    private ImageView mSwitchImeButton;
    private boolean mPaused;

    private final OnEditorActionListener mOnEditorActionListener = (v, actionId, event) -> {
        // Check if this was the result of hitting the IME done action
        final boolean isSoftImeEvent = event == null
                && (actionId == EditorInfo.IME_NULL
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT);
        if (isSoftImeEvent) {
            verifyPasswordAndUnlock();
            return true;
        }
        return false;
    };

    private final View.OnKeyListener mKeyListener = (v, keyCode, keyEvent) -> {
        // Ignore SPACE as a confirm key to allow the space character within passwords.
        final boolean isKeyboardEnterKey = keyEvent != null
                && KeyEvent.isConfirmKey(keyCode) && keyCode != KeyEvent.KEYCODE_SPACE
                && keyEvent.getAction() == KeyEvent.ACTION_UP;
        if (isKeyboardEnterKey) {
            verifyPasswordAndUnlock();
            return true;
        }
        return false;
    };

    private final TextWatcher mTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            mKeyguardSecurityCallback.userActivity();
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (!TextUtils.isEmpty(s)) {
                onUserInput();
            }
        }
    };

    protected KeyguardPasswordViewController(KeyguardPasswordView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode,
            LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker,
            InputMethodManager inputMethodManager,
            EmergencyButtonController emergencyButtonController,
            @Main DelayableExecutor mainExecutor,
            @Main Resources resources,
            FalsingCollector falsingCollector,
            KeyguardViewController keyguardViewController,
            DevicePostureController postureController,
            FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor,
            KeyguardKeyboardInteractor keyguardKeyboardInteractor,
            BouncerHapticPlayer bouncerHapticPlayer,
            UserActivityNotifier userActivityNotifier) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, falsingCollector,
                emergencyButtonController, featureFlags, selectedUserInteractor,
                bouncerHapticPlayer, userActivityNotifier);
        mKeyguardSecurityCallback = keyguardSecurityCallback;
        mInputMethodManager = inputMethodManager;
        mPostureController = postureController;
        mMainExecutor = mainExecutor;
        mKeyguardViewController = keyguardViewController;
        mKeyguardKeyboardInteractor = keyguardKeyboardInteractor;
        if (featureFlags.isEnabled(LOCKSCREEN_ENABLE_LANDSCAPE)) {
            view.setIsLockScreenLandscapeEnabled();
        }
        mShowImeAtScreenOn = resources.getBoolean(R.bool.kg_show_ime_at_screen_on);
        mPasswordEntry = mView.findViewById(mView.getPasswordTextViewId());
        mDefaultPasswordFieldBackground = mPasswordEntry.getBackground();
        mFocusedPasswordFieldBackground = getResources().getDrawable(
                R.drawable.bouncer_password_view_background);
        mSwitchImeButton = mView.findViewById(R.id.switch_ime_button);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mPasswordEntry.setTextOperationUser(
                UserHandle.of(mSelectedUserInteractor.getSelectedUserId()));
        mPasswordEntry.setKeyListener(TextKeyListener.getInstance());
        mPasswordEntry.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        mView.onDevicePostureChanged(mPostureController.getDevicePosture());

        mPostureController.addCallback(mPostureCallback);

        // Set selected property on so the view can send accessibility events.
        mPasswordEntry.setSelected(true);
        mPasswordEntry.setOnEditorActionListener(mOnEditorActionListener);
        mPasswordEntry.setOnKeyListener(mKeyListener);
        mPasswordEntry.addTextChangedListener(mTextWatcher);

        // Poke the wakelock any time the text is selected or modified
        // TODO(b/362362385): Revert to the previous onClickListener implementation once this bug is
        //  fixed.
        mPasswordEntry.setOnClickListener(new View.OnClickListener() {

            private final boolean mAutomotiveAndVisibleBackgroundUsers =
                    isAutomotiveAndVisibleBackgroundUsers();

            @Override
            public void onClick(View v) {
                if (mAutomotiveAndVisibleBackgroundUsers) {
                    mInputMethodManager.restartInput(v);
                }
                mKeyguardSecurityCallback.userActivity();
            }

            private boolean isAutomotiveAndVisibleBackgroundUsers() {
                final Context context = getContext();
                return context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_AUTOMOTIVE)
                        && UserManager.isVisibleBackgroundUsersEnabled()
                        && context.getResources().getBoolean(
                        android.R.bool.config_perDisplayFocusEnabled);
            }
        });

        mSwitchImeButton.setOnClickListener(v -> {
            mKeyguardSecurityCallback.userActivity(); // Leave the screen on a bit longer
            // Do not show auxiliary subtypes in password lock screen.
            mInputMethodManager.showInputMethodPickerFromSystem(false,
                    mView.getContext().getDisplayId());
        });

        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                mKeyguardSecurityCallback.reset();
                mKeyguardSecurityCallback.onCancelClicked();
            });
        }

        // If there's more than one IME, enable the IME switcher button
        updateSwitchImeButton();

        if (Flags.pinInputFieldStyledFocusState()) {
            collectFlow(mPasswordEntry,
                    mKeyguardKeyboardInteractor.isAnyKeyboardConnected(),
                    this::setPasswordFieldFocusBackground);

            ViewGroup.LayoutParams layoutParams = mPasswordEntry.getLayoutParams();
            layoutParams.height = (int) getResources()
                    .getDimension(R.dimen.keyguard_password_field_height);
            layoutParams.width = (int) getResources()
                    .getDimension(R.dimen.keyguard_password_field_width);
        }

    }

    private void setPasswordFieldFocusBackground(boolean isAnyKeyboardConnected) {
        if (isAnyKeyboardConnected) {
            mPasswordEntry.setBackground(mFocusedPasswordFieldBackground);
        } else {
            mPasswordEntry.setBackground(mDefaultPasswordFieldBackground);
        }
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mPasswordEntry.setOnEditorActionListener(null);
        mPostureController.removeCallback(mPostureCallback);
    }

    @Override
    public boolean needsInput() {
        return true;
    }

    @Override
    void resetState() {
        mPasswordEntry.setTextOperationUser(
                UserHandle.of(mSelectedUserInteractor.getSelectedUserId()));
        mMessageAreaController.setMessage(getInitialMessageResId());
        final boolean wasDisabled = mPasswordEntry.isEnabled();
        mView.setPasswordEntryEnabled(true);
        mView.setPasswordEntryInputEnabled(true);
        // Don't call showSoftInput when PasswordEntry is invisible or in pausing stage.
        if (!mResumed || !mPasswordEntry.isVisibleToUser()) {
            return;
        }
        if (wasDisabled) {
            showInput();
        }
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        mPaused = false;
        if (reason != KeyguardSecurityView.SCREEN_ON || mShowImeAtScreenOn) {
            showInput();
        }
    }

    private void showInput() {
        if (!mKeyguardViewController.isBouncerShowing()) {
            return;
        }

        if (mView.isShown()) {
            mView.showKeyboard();
        }
    }

    @Override
    public void onPause() {
        if (mPaused) {
            return;
        }
        mPaused = true;

        if (!mPasswordEntry.isVisibleToUser()) {
            // Reset all states directly and then hide IME when the screen turned off.
            super.onPause();
        } else {
            // In order not to break the IME hide animation by resetting states too early after
            // the password checked, make sure resetting states after the IME hiding animation
            // finished.
            mView.setOnFinishImeAnimationRunnable(() -> {
                mPasswordEntry.clearFocus();
                super.onPause();
            });
        }
        mView.hideKeyboard();
    }

    @Override
    public void onStartingToHide() {
        mView.hideKeyboard();
    }

    private void updateSwitchImeButton() {
        // If there's more than one IME, enable the IME switcher button
        final boolean wasVisible = mSwitchImeButton.getVisibility() == View.VISIBLE;
        final boolean shouldBeVisible = hasMultipleEnabledIMEsOrSubtypes(
                mInputMethodManager, false);
        if (wasVisible != shouldBeVisible) {
            mSwitchImeButton.setVisibility(shouldBeVisible ? View.VISIBLE : View.GONE);
        }

        // TODO: Check if we still need this hack.
        // If no icon is visible, reset the start margin on the password field so the text is
        // still centered.
        if (mSwitchImeButton.getVisibility() != View.VISIBLE) {
            android.view.ViewGroup.LayoutParams params = mPasswordEntry.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                final MarginLayoutParams mlp = (MarginLayoutParams) params;
                mlp.setMarginStart(0);
                mPasswordEntry.setLayoutParams(params);
            }
        }
    }

    /**
     * Method adapted from com.android.inputmethod.latin.Utils
     *
     * @param imm The input method manager
     * @param shouldIncludeAuxiliarySubtypes
     * @return true if we have multiple IMEs to choose from
     */
    private boolean hasMultipleEnabledIMEsOrSubtypes(InputMethodManager imm,
            final boolean shouldIncludeAuxiliarySubtypes) {
        final List<InputMethodInfo> enabledImis =
                imm.getEnabledInputMethodListAsUser(
                        UserHandle.of(mSelectedUserInteractor.getSelectedUserId()));

        // Number of the filtered IMEs
        int filteredImisCount = 0;

        for (InputMethodInfo imi : enabledImis) {
            // We can return true immediately after we find two or more filtered IMEs.
            if (filteredImisCount > 1) return true;
            final List<InputMethodSubtype> subtypes =
                    imm.getEnabledInputMethodSubtypeList(imi, true);
            // IMEs that have no subtypes should be counted.
            if (subtypes.isEmpty()) {
                ++filteredImisCount;
                continue;
            }

            int auxCount = 0;
            for (InputMethodSubtype subtype : subtypes) {
                if (subtype.isAuxiliary()) {
                    ++auxCount;
                }
            }
            final int nonAuxCount = subtypes.size() - auxCount;

            // IMEs that have one or more non-auxiliary subtypes should be counted.
            // If shouldIncludeAuxiliarySubtypes is true, IMEs that have two or more auxiliary
            // subtypes should be counted as well.
            if (nonAuxCount > 0 || (shouldIncludeAuxiliarySubtypes && auxCount > 1)) {
                ++filteredImisCount;
                continue;
            }
        }

        return filteredImisCount > 1
                // imm.getEnabledInputMethodSubtypeList(null, false) will return the current IME's
                //enabled input method subtype (The current IME should be LatinIME.)
                || imm.getEnabledInputMethodSubtypeList(null, false).size() > 1;
    }

    @Override
    protected int getInitialMessageResId() {
        return R.string.keyguard_enter_your_password;
    }
}
