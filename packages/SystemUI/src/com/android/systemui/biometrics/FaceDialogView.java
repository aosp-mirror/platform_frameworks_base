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
public class FaceDialogView extends BiometricDialogView {

    private static final int HIDE_DIALOG_DELAY = 500; // ms

    public FaceDialogView(Context context,
            DialogViewCallback callback) {
        super(context, callback);
    }

    @Override
    protected int getHintStringResourceId() {
        return R.string.face_dialog_looking_for_face;
    }

    @Override
    protected int getAuthenticatedAccessibilityResourceId() {
        if (mRequireConfirmation) {
            return com.android.internal.R.string.face_authenticated_confirmation_required;
        } else {
            return com.android.internal.R.string.face_authenticated_no_confirmation_required;
        }
    }

    @Override
    protected int getIconDescriptionResourceId() {
        return R.string.accessibility_face_dialog_face_icon;
    }

    @Override
    protected boolean shouldAnimateForTransition(int oldState, int newState) {
        if (oldState == STATE_NONE && newState == STATE_AUTHENTICATING) {
            return false;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_ERROR) {
            return true;
        } else if (oldState == STATE_ERROR && newState == STATE_AUTHENTICATING) {
            return true;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_PENDING_CONFIRMATION) {
            return true;
        } else if (oldState == STATE_PENDING_CONFIRMATION && newState == STATE_AUTHENTICATED) {
            return true;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
            return true;
        }
        return false;
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return HIDE_DIALOG_DELAY;
    }

    @Override
    protected Drawable getAnimationForTransition(int oldState, int newState) {
        int iconRes;
        if (oldState == STATE_NONE && newState == STATE_AUTHENTICATING) {
            iconRes = R.drawable.face_dialog_face_to_error;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_ERROR) {
            iconRes = R.drawable.face_dialog_face_to_error;
        } else if (oldState == STATE_ERROR && newState == STATE_AUTHENTICATING) {
            iconRes = R.drawable.face_dialog_error_to_face;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_PENDING_CONFIRMATION) {
            iconRes = R.drawable.face_dialog_face_gray_to_face_blue;
        } else if (oldState == STATE_PENDING_CONFIRMATION && newState == STATE_AUTHENTICATED) {
            iconRes = R.drawable.face_dialog_face_blue_to_checkmark;
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
            iconRes = R.drawable.face_dialog_face_gray_to_checkmark;
        } else {
            return null;
        }
        return mContext.getDrawable(iconRes);
    }
}
