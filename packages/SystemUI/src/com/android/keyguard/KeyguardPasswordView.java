/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.widget.TextViewInputDisabler;

import java.util.List;
/**
 * Displays an alphanumeric (latin-1) key entry for the user to enter
 * an unlock password
 */
public class KeyguardPasswordView extends KeyguardAbsKeyInputView
        implements KeyguardSecurityView, OnEditorActionListener, TextWatcher {

    private final boolean mShowImeAtScreenOn;
    private final int mDisappearYTranslation;

    // A delay constant to be used in a workaround for the situation where InputMethodManagerService
    // is not switched to the new user yet.
    // TODO: Remove this by ensuring such a race condition never happens.
    private static final int DELAY_MILLIS_TO_REEVALUATE_IME_SWITCH_ICON = 500;  // 500ms

    InputMethodManager mImm;
    private TextView mPasswordEntry;
    private TextViewInputDisabler mPasswordEntryDisabler;
    private View mSwitchImeButton;

    private Interpolator mLinearOutSlowInInterpolator;
    private Interpolator mFastOutLinearInInterpolator;

    public KeyguardPasswordView(Context context) {
        this(context, null);
    }

    public KeyguardPasswordView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mShowImeAtScreenOn = context.getResources().
                getBoolean(R.bool.kg_show_ime_at_screen_on);
        mDisappearYTranslation = getResources().getDimensionPixelSize(
                R.dimen.disappear_y_translation);
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(
                context, android.R.interpolator.linear_out_slow_in);
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(
                context, android.R.interpolator.fast_out_linear_in);
    }

    @Override
    protected void resetState() {
        mSecurityMessageDisplay.setMessage("");
        final boolean wasDisabled = mPasswordEntry.isEnabled();
        setPasswordEntryEnabled(true);
        setPasswordEntryInputEnabled(true);
        if (wasDisabled) {
            mImm.showSoftInput(mPasswordEntry, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.passwordEntry;
    }

    @Override
    public boolean needsInput() {
        return true;
    }

    @Override
    public void onResume(final int reason) {
        super.onResume(reason);

        // Wait a bit to focus the field so the focusable flag on the window is already set then.
        post(new Runnable() {
            @Override
            public void run() {
                if (isShown() && mPasswordEntry.isEnabled()) {
                    mPasswordEntry.requestFocus();
                    if (reason != KeyguardSecurityView.SCREEN_ON || mShowImeAtScreenOn) {
                        mImm.showSoftInput(mPasswordEntry, InputMethodManager.SHOW_IMPLICIT);
                    }
                }
            }
        });
    }

    @Override
    protected int getPromptReasonStringRes(int reason) {
        switch (reason) {
            case PROMPT_REASON_RESTART:
                return R.string.kg_prompt_reason_restart_password;
            case PROMPT_REASON_TIMEOUT:
                return R.string.kg_prompt_reason_timeout_password;
            case PROMPT_REASON_DEVICE_ADMIN:
                return R.string.kg_prompt_reason_device_admin;
            case PROMPT_REASON_USER_REQUEST:
                return R.string.kg_prompt_reason_user_request;
            case PROMPT_REASON_NONE:
                return 0;
            default:
                return R.string.kg_prompt_reason_timeout_password;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mImm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    @Override
    public void reset() {
        super.reset();
        mPasswordEntry.requestFocus();
    }

    private void updateSwitchImeButton() {
        // If there's more than one IME, enable the IME switcher button
        final boolean wasVisible = mSwitchImeButton.getVisibility() == View.VISIBLE;
        final boolean shouldBeVisible = hasMultipleEnabledIMEsOrSubtypes(mImm, false);
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

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mImm = (InputMethodManager) getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);

        mPasswordEntry = findViewById(getPasswordTextViewId());
        mPasswordEntryDisabler = new TextViewInputDisabler(mPasswordEntry);
        mPasswordEntry.setKeyListener(TextKeyListener.getInstance());
        mPasswordEntry.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mPasswordEntry.setOnEditorActionListener(this);
        mPasswordEntry.addTextChangedListener(this);

        // Poke the wakelock any time the text is selected or modified
        mPasswordEntry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.userActivity();
            }
        });

        // Set selected property on so the view can send accessibility events.
        mPasswordEntry.setSelected(true);

        mPasswordEntry.requestFocus();

        mSwitchImeButton = findViewById(R.id.switch_ime_button);
        mSwitchImeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mCallback.userActivity(); // Leave the screen on a bit longer
                // Do not show auxiliary subtypes in password lock screen.
                mImm.showInputMethodPicker(false /* showAuxiliarySubtypes */);
            }
        });

        View cancelBtn = findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                mCallback.reset();
            });
        }

        // If there's more than one IME, enable the IME switcher button
        updateSwitchImeButton();

        // When we the current user is switching, InputMethodManagerService sometimes has not
        // switched internal state yet here. As a quick workaround, we check the keyboard state
        // again.
        // TODO: Remove this workaround by ensuring such a race condition never happens.
        postDelayed(new Runnable() {
            @Override
            public void run() {
                updateSwitchImeButton();
            }
        }, DELAY_MILLIS_TO_REEVALUATE_IME_SWITCH_ICON);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // send focus to the password field
        return mPasswordEntry.requestFocus(direction, previouslyFocusedRect);
    }

    @Override
    protected void resetPasswordText(boolean animate, boolean announce) {
        mPasswordEntry.setText("");
    }

    @Override
    protected String getPasswordText() {
        return mPasswordEntry.getText().toString();
    }

    @Override
    protected void setPasswordEntryEnabled(boolean enabled) {
        mPasswordEntry.setEnabled(enabled);
    }

    @Override
    protected void setPasswordEntryInputEnabled(boolean enabled) {
        mPasswordEntryDisabler.setInputEnabled(enabled);
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
        final List<InputMethodInfo> enabledImis = imm.getEnabledInputMethodList();

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
        // imm.getEnabledInputMethodSubtypeList(null, false) will return the current IME's enabled
        // input method subtype (The current IME should be LatinIME.)
                || imm.getEnabledInputMethodSubtypeList(null, false).size() > 1;
    }

    @Override
    public void showUsabilityHint() {
    }

    @Override
    public int getWrongPasswordStringId() {
        return R.string.kg_wrong_password;
    }

    @Override
    public void startAppearAnimation() {
        setAlpha(0f);
        setTranslationY(0f);
        animate()
                .alpha(1)
                .withLayer()
                .setDuration(300)
                .setInterpolator(mLinearOutSlowInInterpolator);
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        animate()
                .alpha(0f)
                .translationY(mDisappearYTranslation)
                .setInterpolator(mFastOutLinearInInterpolator)
                .setDuration(100)
                .withEndAction(finishRunnable);
        return true;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (mCallback != null) {
            mCallback.userActivity();
        }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        // Poor man's user edit detection, assuming empty text is programmatic and everything else
        // is from the user.
        if (!TextUtils.isEmpty(s)) {
            onUserInput();
        }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
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
    }

    @Override
    public CharSequence getTitle() {
        return getContext().getString(
                com.android.internal.R.string.keyguard_accessibility_password_unlock);
    }
}
