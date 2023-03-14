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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowInsets.Type.ime;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Insets;
import android.os.UserHandle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnApplyWindowInsetsListener;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImeAwareEditText;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.systemui.Dumpable;
import com.android.systemui.R;

import java.io.PrintWriter;

/**
 * Pin and Password UI
 */
public class AuthCredentialPasswordView extends AuthCredentialView
        implements TextView.OnEditorActionListener, OnApplyWindowInsetsListener, Dumpable {

    private static final String TAG = "BiometricPrompt/AuthCredentialPasswordView";

    private final InputMethodManager mImm;
    private ImeAwareEditText mPasswordField;
    private ViewGroup mAuthCredentialHeader;
    private ViewGroup mAuthCredentialInput;
    private int mBottomInset = 0;
    private OnBackInvokedDispatcher mOnBackInvokedDispatcher;
    private final OnBackInvokedCallback mBackCallback = this::onBackInvoked;

    public AuthCredentialPasswordView(Context context,
            AttributeSet attrs) {
        super(context, attrs);
        mImm = mContext.getSystemService(InputMethodManager.class);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAuthCredentialHeader = findViewById(R.id.auth_credential_header);
        mAuthCredentialInput = findViewById(R.id.auth_credential_input);
        mPasswordField = findViewById(R.id.lockPassword);
        mPasswordField.setOnEditorActionListener(this);
        // TODO: De-dupe the logic with AuthContainerView
        mPasswordField.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                onBackInvoked();
            }
            return true;
        });

        setOnApplyWindowInsetsListener(this);
    }

    private void onBackInvoked() {
        mContainerView.sendEarlyUserCanceled();
        mContainerView.animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mPasswordField.setTextOperationUser(UserHandle.of(mUserId));
        if (mCredentialType == Utils.CREDENTIAL_PIN) {
            mPasswordField.setInputType(
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }

        mPasswordField.requestFocus();
        mPasswordField.scheduleShowSoftInput();

        mOnBackInvokedDispatcher = findOnBackInvokedDispatcher();
        if (mOnBackInvokedDispatcher != null) {
            mOnBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);
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
            checkPasswordAndUnlock();
            return true;
        }
        return false;
    }

    private void checkPasswordAndUnlock() {
        try (LockscreenCredential password = mCredentialType == Utils.CREDENTIAL_PIN
                ? LockscreenCredential.createPinOrNone(mPasswordField.getText())
                : LockscreenCredential.createPasswordOrNone(mPasswordField.getText())) {
            if (password.isNone()) {
                return;
            }

            // Request LockSettingsService to return the Gatekeeper Password in the
            // VerifyCredentialResponse so that we can request a Gatekeeper HAT with the
            // Gatekeeper Password and operationId.
            mPendingLockCheck = LockPatternChecker.verifyCredential(mLockPatternUtils,
                    password, mEffectiveUserId, LockPatternUtils.VERIFY_FLAG_REQUEST_GK_PW_HANDLE,
                    this::onCredentialVerified);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mOnBackInvokedDispatcher != null) {
            mOnBackInvokedDispatcher.unregisterOnBackInvokedCallback(mBackCallback);
            mOnBackInvokedDispatcher = null;
        }
    }

    @Override
    protected void onCredentialVerified(@NonNull VerifyCredentialResponse response,
            int timeoutMs) {
        super.onCredentialVerified(response, timeoutMs);

        if (response.isMatched()) {
            mImm.hideSoftInputFromWindow(getWindowToken(), 0 /* flags */);
        } else {
            mPasswordField.setText("");
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (mAuthCredentialInput == null || mAuthCredentialHeader == null || mSubtitleView == null
                || mDescriptionView == null || mPasswordField == null || mErrorView == null) {
            return;
        }

        int inputLeftBound;
        int inputTopBound;
        int headerRightBound = right;
        int headerTopBounds = top;
        final int subTitleBottom = (mSubtitleView.getVisibility() == GONE) ? mTitleView.getBottom()
                : mSubtitleView.getBottom();
        final int descBottom = (mDescriptionView.getVisibility() == GONE) ? subTitleBottom
                : mDescriptionView.getBottom();
        if (getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE) {
            inputTopBound = (bottom - mAuthCredentialInput.getHeight()) / 2;
            inputLeftBound = (right - left) / 2;
            headerRightBound = inputLeftBound;
            headerTopBounds -= Math.min(mIconView.getBottom(), mBottomInset);
        } else {
            inputTopBound =
                    descBottom + (bottom - descBottom - mAuthCredentialInput.getHeight()) / 2;
            inputLeftBound = (right - left - mAuthCredentialInput.getWidth()) / 2;
        }

        if (mDescriptionView.getBottom() > mBottomInset) {
            mAuthCredentialHeader.layout(left, headerTopBounds, headerRightBound, bottom);
        }
        mAuthCredentialInput.layout(inputLeftBound, inputTopBound, right, bottom);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int newWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int newHeight = MeasureSpec.getSize(heightMeasureSpec) - mBottomInset;

        setMeasuredDimension(newWidth, newHeight);

        final int halfWidthSpec = MeasureSpec.makeMeasureSpec(getWidth() / 2,
                MeasureSpec.AT_MOST);
        final int fullHeightSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.UNSPECIFIED);
        if (getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE) {
            measureChildren(halfWidthSpec, fullHeightSpec);
        } else {
            measureChildren(widthMeasureSpec, fullHeightSpec);
        }
    }

    @NonNull
    @Override
    public WindowInsets onApplyWindowInsets(@NonNull View v, WindowInsets insets) {

        final Insets bottomInset = insets.getInsets(ime());
        if (v instanceof AuthCredentialPasswordView && mBottomInset != bottomInset.bottom) {
            mBottomInset = bottomInset.bottom;
            if (mBottomInset > 0
                    && getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE) {
                mTitleView.setSingleLine(true);
                mTitleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                mTitleView.setMarqueeRepeatLimit(-1);
                // select to enable marquee unless a screen reader is enabled
                mTitleView.setSelected(!mAccessibilityManager.isEnabled()
                        || !mAccessibilityManager.isTouchExplorationEnabled());
            } else {
                mTitleView.setSingleLine(false);
                mTitleView.setEllipsize(null);
                // select to enable marquee unless a screen reader is enabled
                mTitleView.setSelected(false);
            }
            requestLayout();
        }
        return insets;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println(TAG + "State:");
        pw.println("  mBottomInset=" + mBottomInset);
        pw.println("  mAuthCredentialHeader size=(" + mAuthCredentialHeader.getWidth() + ","
                + mAuthCredentialHeader.getHeight());
        pw.println("  mAuthCredentialInput size=(" + mAuthCredentialInput.getWidth() + ","
                + mAuthCredentialInput.getHeight());
    }
}
