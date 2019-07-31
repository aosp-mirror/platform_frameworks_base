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

import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.view.WindowManager;

import com.android.systemui.biometrics.ui.BiometricDialogView;

/**
 * Interface for the biometric dialog UI.
 */
public interface BiometricDialog {

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

    /**
     * Show the dialog.
     * @param wm
     * @param skipIntroAnimation
     */
    void show(WindowManager wm, boolean skipIntroAnimation);

    /**
     * Dismiss the dialog without sending a callback. Only used when the system detects a case
     * where the error won't come from the UI (e.g. task stack changed).
     * @param animate
     */
    void dismissWithoutCallback(boolean animate);

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
}
