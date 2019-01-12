/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.biometrics;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.android.systemui.R;

/**
 * This class loads the view for the system-provided dialog. The view consists of:
 * Application Icon, Title, Subtitle, Description, Biometric Icon, Error/Help message area,
 * and positive/negative buttons.
 */
public class FingerprintDialogView extends BiometricDialogView {

    public FingerprintDialogView(Context context,
            DialogViewCallback callback) {
        super(context, callback);
    }

    @Override
    protected void handleClearMessage(boolean requireTryAgain) {
        updateState(STATE_AUTHENTICATING);
        mErrorText.setText(getHintStringResourceId());
        mErrorText.setTextColor(mTextColor);
    }

    @Override
    protected int getHintStringResourceId() {
        return R.string.fingerprint_dialog_touch_sensor;
    }

    @Override
    protected int getAuthenticatedAccessibilityResourceId() {
        return com.android.internal.R.string.fingerprint_authenticated;
    }

    @Override
    protected int getIconDescriptionResourceId() {
        return R.string.accessibility_fingerprint_dialog_fingerprint_icon;
    }

    @Override
    protected boolean shouldAnimateForTransition(int oldState, int newState) {
        if (oldState == STATE_IDLE && newState == STATE_AUTHENTICATING) {
            return false;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_ERROR) {
            return true;
        } else if (oldState == STATE_ERROR && newState == STATE_AUTHENTICATING) {
            return true;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
            // TODO(b/77328470): add animation when fingerprint is authenticated
            return false;
        }
        return false;
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return 0;
    }

    @Override
    protected boolean shouldGrayAreaDismissDialog() {
        // Fingerprint dialog always dismisses when region outside the dialog is tapped
        return true;
    }

    @Override
    protected Drawable getAnimationForTransition(int oldState, int newState) {
        int iconRes;
        if (oldState == STATE_IDLE && newState == STATE_AUTHENTICATING) {
            iconRes = R.drawable.fingerprint_dialog_fp_to_error;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_ERROR) {
            iconRes = R.drawable.fingerprint_dialog_fp_to_error;
        } else if (oldState == STATE_ERROR && newState == STATE_AUTHENTICATING) {
            iconRes = R.drawable.fingerprint_dialog_error_to_fp;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
            // TODO(b/77328470): add animation when fingerprint is authenticated
            iconRes = R.drawable.fingerprint_dialog_fp_to_error;
        } else {
            return null;
        }
        return mContext.getDrawable(iconRes);
    }
}