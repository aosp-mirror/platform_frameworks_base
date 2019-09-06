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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.view.WindowManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for the biometric dialog UI.
 */
public interface AuthDialog {
    String KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY = "try_agian_visibility";
    String KEY_BIOMETRIC_STATE = "state";
    String KEY_BIOMETRIC_INDICATOR_STRING = "indicator_string"; // error / help / hint
    String KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING = "error_is_temporary";
    String KEY_BIOMETRIC_INDICATOR_HELP_SHOWING = "hint_is_temporary";
    String KEY_BIOMETRIC_DIALOG_SIZE = "size";

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
    void show(WindowManager wm, @Nullable Bundle savedState);

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
    void onSaveState(@NonNull Bundle outState);

    /**
     * Get the client's package name
     */
    String getOpPackageName();
}
