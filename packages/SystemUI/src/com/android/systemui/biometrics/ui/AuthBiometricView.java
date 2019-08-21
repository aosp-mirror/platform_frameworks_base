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

package com.android.systemui.biometrics.ui;

import android.annotation.IntDef;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.biometrics.BiometricDialog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains the Biometric views (title, subtitle, icon, buttons, etc) and its controllers.
 */
public abstract class AuthBiometricView extends LinearLayout {

    private static final String TAG = "BiometricPrompt/AuthBiometricView";

    /**
     * Authentication hardware idle.
     */
    protected static final int STATE_IDLE = 0;
    /**
     * UI animating in, authentication hardware active.
     */
    protected static final int STATE_AUTHENTICATING_ANIMATING_IN = 1;
    /**
     * UI animated in, authentication hardware active.
     */
    protected static final int STATE_AUTHENTICATING = 2;
    /**
     * Hard error, e.g. ERROR_TIMEOUT. Authentication hardware idle.
     */
    protected static final int STATE_ERROR = 3;
    /**
     * Authenticated, waiting for user confirmation. Authentication hardware idle.
     */
    protected static final int STATE_PENDING_CONFIRMATION = 4;
    /**
     * Authenticated, dialog animating away soon.
     */
    protected static final int STATE_AUTHENTICATED = 5;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_IDLE, STATE_AUTHENTICATING_ANIMATING_IN, STATE_AUTHENTICATING, STATE_ERROR,
            STATE_PENDING_CONFIRMATION, STATE_AUTHENTICATED})
    @interface State {}

    /**
     * Callback to the parent when a user action has occurred.
     */
    interface Callback {
        int ACTION_AUTHENTICATED = 1;

        /**
         * When an action has occurred. The caller will only invoke this when the callback should
         * be propagated. e.g. the caller will handle any necessary delay.
         * @param action
         */
        void onAction(int action);
    }

    private final Handler mHandler;

    private AuthPanelController mPanelController;
    private Bundle mBundle;
    private boolean mRequireConfirmation;
    private @BiometricDialog.DialogSize int mSize = BiometricDialog.SIZE_UNKNOWN;

    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mDescriptionView;
    protected ImageView mIconView;
    private TextView mErrorView;
    private Button mNegativeButton;
    private Button mPositiveButton;
    private Button mTryAgainButton;

    private int mCurrentHeight;
    private int mCurrentWidth;
    private Callback mCallback;
    protected @State int mState;

    protected abstract int getDelayAfterAuthenticatedDurationMs();

    public AuthBiometricView(Context context) {
        this(context, null);
    }

    public AuthBiometricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHandler = new Handler(Looper.getMainLooper());

        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                updateSize(mRequireConfirmation ? BiometricDialog.SIZE_MEDIUM
                        : BiometricDialog.SIZE_SMALL);
                mPanelController.updateForContentDimensions(mCurrentWidth, mCurrentHeight);
            }
        });
    }

    public void setPanelController(AuthPanelController panelController) {
        mPanelController = panelController;
    }

    public void setBiometricPromptBundle(Bundle bundle) {
        mBundle = bundle;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void setRequireConfirmation(boolean requireConfirmation) {
        mRequireConfirmation = requireConfirmation;
    }

    public void updateSize(@BiometricDialog.DialogSize int newSize) {
        if (mSize == newSize) {
            Log.w(TAG, "Skipping updating size: " + mSize);
            return;
        }

        if (newSize == BiometricDialog.SIZE_SMALL) {
            mTitleView.setVisibility(View.GONE);
            mSubtitleView.setVisibility(View.GONE);
            mDescriptionView.setVisibility(View.GONE);
            mErrorView.setVisibility(View.GONE);
            mNegativeButton.setVisibility(View.GONE);

            final float iconPadding = getResources()
                    .getDimension(R.dimen.biometric_dialog_icon_padding);
            mIconView.setY(getHeight() - mIconView.getHeight() - iconPadding);

            mCurrentHeight = mIconView.getHeight() + 2 * (int) iconPadding;
        }

        mSize = newSize;
    }

    public void updateState(@State int newState) {
        Log.v(TAG, "newState: " + newState);
        if (newState == STATE_AUTHENTICATED) {
            if (mRequireConfirmation) {

            } else {
                mHandler.postDelayed(() -> {
                    mCallback.onAction(Callback.ACTION_AUTHENTICATED);
                }, getDelayAfterAuthenticatedDurationMs());
            }
        }
        mState = newState;
    }

    public void onDialogAnimatedIn() {
        updateState(STATE_AUTHENTICATING);
    }

    public void onAuthenticationSucceeded() {
        if (mRequireConfirmation) {
            updateState(STATE_PENDING_CONFIRMATION);
        } else {
            updateState(STATE_AUTHENTICATED);
        }
    }

    private void setTextOrHide(TextView view, String string) {
        if (TextUtils.isEmpty(string)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(string);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitleView = findViewById(R.id.title);
        mSubtitleView = findViewById(R.id.subtitle);
        mDescriptionView = findViewById(R.id.description);
        mIconView = findViewById(R.id.biometric_icon);
        mErrorView = findViewById(R.id.error);
        mNegativeButton = findViewById(R.id.button_negative);
        mPositiveButton = findViewById(R.id.button_positive);
        mTryAgainButton = findViewById(R.id.button_try_again);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setTextOrHide(mTitleView, mBundle.getString(BiometricPrompt.KEY_TITLE));
        setTextOrHide(mSubtitleView, mBundle.getString(BiometricPrompt.KEY_SUBTITLE));
        setTextOrHide(mDescriptionView, mBundle.getString(BiometricPrompt.KEY_DESCRIPTION));

        updateState(STATE_AUTHENTICATING_ANIMATING_IN);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int newWidth = Math.min(width, height);

        int totalHeight = 0;
        final int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            final View child = getChildAt(i);

            if (child.getId() == R.id.biometric_icon) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            } else {
                child.measure(
                        MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }
            totalHeight += child.getMeasuredHeight();
        }

        // Use the new width so it's centered horizontally
        setMeasuredDimension(newWidth, totalHeight);

        mCurrentHeight = getMeasuredHeight();
        mCurrentWidth = getMeasuredWidth();
    }
}
