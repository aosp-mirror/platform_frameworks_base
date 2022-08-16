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

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.UserHandle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.WindowInsets;
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
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.List;

public class KeyguardPasswordViewController
        extends KeyguardAbsKeyInputViewController<KeyguardPasswordView> {

    private static final int DELAY_MILLIS_TO_REEVALUATE_IME_SWITCH_ICON = 500;  // 500ms

    private final KeyguardSecurityCallback mKeyguardSecurityCallback;
    private final InputMethodManager mInputMethodManager;
    private final DelayableExecutor mMainExecutor;
    private final KeyguardViewController mKeyguardViewController;
    private final boolean mShowImeAtScreenOn;
    private EditText mPasswordEntry;
    private ImageView mSwitchImeButton;
    private boolean mPaused;

    private final OnEditorActionListener mOnEditorActionListener = (v, actionId, event) -> {
        // Check if this was the result of hitting the enter key
        final boolean isSoftImeEvent = event == null
                && (actionId == EditorInfo.IME_NULL
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT);
        final boolean isKeyboardEnterKey = event != null
                && KeyEvent.isConfirmKey(event.getKeyCode())
                && event.getAction() == KeyEvent.ACTION_DOWN;
        if (isSoftImeEvent || isKeyboardEnterKey) {
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

    @Override
    public void reloadColors() {
        super.reloadColors();
        int textColor = Utils.getColorAttr(mView.getContext(),
                android.R.attr.textColorPrimary).getDefaultColor();
        mPasswordEntry.setTextColor(textColor);
        mPasswordEntry.setHighlightColor(textColor);
        mPasswordEntry.setBackgroundTintList(ColorStateList.valueOf(textColor));
        mPasswordEntry.setForegroundTintList(ColorStateList.valueOf(textColor));
        mSwitchImeButton.setImageTintList(ColorStateList.valueOf(textColor));
    }

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
            KeyguardViewController keyguardViewController) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, falsingCollector,
                emergencyButtonController);
        mKeyguardSecurityCallback = keyguardSecurityCallback;
        mInputMethodManager = inputMethodManager;
        mMainExecutor = mainExecutor;
        mKeyguardViewController = keyguardViewController;
        mShowImeAtScreenOn = resources.getBoolean(R.bool.kg_show_ime_at_screen_on);
        mPasswordEntry = mView.findViewById(mView.getPasswordTextViewId());
        mSwitchImeButton = mView.findViewById(R.id.switch_ime_button);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mPasswordEntry.setTextOperationUser(UserHandle.of(KeyguardUpdateMonitor.getCurrentUser()));
        mPasswordEntry.setKeyListener(TextKeyListener.getInstance());
        mPasswordEntry.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // Set selected property on so the view can send accessibility events.
        mPasswordEntry.setSelected(true);
        mPasswordEntry.setOnEditorActionListener(mOnEditorActionListener);
        mPasswordEntry.addTextChangedListener(mTextWatcher);
        // Poke the wakelock any time the text is selected or modified
        mPasswordEntry.setOnClickListener(v -> mKeyguardSecurityCallback.userActivity());

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

        // When we the current user is switching, InputMethodManagerService sometimes has not
        // switched internal state yet here. As a quick workaround, we check the keyboard state
        // again.
        // TODO: Remove this workaround by ensuring such a race condition never happens.
        mMainExecutor.executeDelayed(
                this::updateSwitchImeButton, DELAY_MILLIS_TO_REEVALUATE_IME_SWITCH_ICON);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mPasswordEntry.setOnEditorActionListener(null);
    }

    @Override
    public boolean needsInput() {
        return true;
    }

    @Override
    void resetState() {
        mPasswordEntry.setTextOperationUser(UserHandle.of(KeyguardUpdateMonitor.getCurrentUser()));
        mMessageAreaController.setMessage("");
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

        mView.post(() -> {
            if (mView.isShown()) {
                mPasswordEntry.requestFocus();
                mPasswordEntry.getWindowInsetsController().show(WindowInsets.Type.ime());
            }
        });
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
        if (mPasswordEntry.isAttachedToWindow()) {
            mPasswordEntry.getWindowInsetsController().hide(WindowInsets.Type.ime());
        }
    }

    @Override
    public void onStartingToHide() {
        if (mPasswordEntry.isAttachedToWindow()) {
            mPasswordEntry.getWindowInsetsController().hide(WindowInsets.Type.ime());
        }
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
                imm.getEnabledInputMethodListAsUser(KeyguardUpdateMonitor.getCurrentUser());

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
}
