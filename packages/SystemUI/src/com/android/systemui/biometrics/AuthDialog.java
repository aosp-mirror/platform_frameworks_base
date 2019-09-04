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

import android.annotation.IntDef;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.view.WindowManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for the biometric dialog UI.
 */
public interface AuthDialog {

    // TODO: Clean up save/restore state
    String[] KEYS_TO_BACKUP = {
            BiometricPrompt.KEY_TITLE,
            BiometricPrompt.KEY_USE_DEFAULT_TITLE,
            BiometricPrompt.KEY_SUBTITLE,
            BiometricPrompt.KEY_DESCRIPTION,
            BiometricPrompt.KEY_POSITIVE_TEXT,
            BiometricPrompt.KEY_NEGATIVE_TEXT,
            BiometricPrompt.KEY_REQUIRE_CONFIRMATION,
            BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL,
            BiometricPrompt.KEY_FROM_CONFIRM_DEVICE_CREDENTIAL,

            BiometricDialogView.KEY_TRY_AGAIN_VISIBILITY,
            BiometricDialogView.KEY_CONFIRM_VISIBILITY,
            BiometricDialogView.KEY_CONFIRM_ENABLED,
            BiometricDialogView.KEY_STATE,
            BiometricDialogView.KEY_ERROR_TEXT_VISIBILITY,
            BiometricDialogView.KEY_ERROR_TEXT_STRING,
            BiometricDialogView.KEY_ERROR_TEXT_IS_TEMPORARY,
            BiometricDialogView.KEY_ERROR_TEXT_COLOR,
    };

    int SIZE_UNKNOWN = 0;
    int SIZE_SMALL = 1;
    int SIZE_MEDIUM = 2;
    int SIZE_LARGE = 3;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SIZE_UNKNOWN, SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE})
    @interface DialogSize {}

    /**
     * Animation duration, e.g. small to medium dialog, icon translation, etc.
     */
    int ANIMATE_DURATION_MS = 150;

    /**
     * Show the dialog.
     * @param wm
     */
    void show(WindowManager wm);

    /**
     * Dismiss the dialog without sending a callback.
     */
    void dismissWithoutCallback(boolean animate);

    /**
     * Dismiss the dialog. Animate away.
     */
    void dismissFromSystemServer();

    /**
     * Biometric authenticated. May be pending user confirmation, or completed.
     */
    void onAuthenticationSucceeded();

    /**
     * Authentication failed (reject, timeout). Dialog stays showing.
     * @param failureReason
     */
    void onAuthenticationFailed(String failureReason);

    /**
     * Authentication rejected, or help message received.
     * @param help
     */
    void onHelp(String help);

    /**
     * Authentication failed. Dialog going away.
     * @param error
     */
    void onError(String error);

    /**
     * Save the current state.
     * @param outState
     */
    void onSaveState(Bundle outState);

    /**
     * Restore a previous state.
     * @param savedState
     */
    void restoreState(Bundle savedState);

    /**
     * Get the client's package name
     */
    String getOpPackageName();
}
