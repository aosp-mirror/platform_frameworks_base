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

/**
 * Callback interface for dialog views. These should be implemented by the controller (e.g.
 * FingerprintDialogImpl) and passed into their views (e.g. FingerprintDialogView).
 */
public interface DialogViewCallback {
    /**
     * Invoked when the user cancels authentication by tapping outside the prompt, etc. The dialog
     * should be dismissed.
     */
    void onUserCanceled();

    /**
     * Invoked when an error is shown. The dialog should be dismissed after a set amount of time.
     */
    void onErrorShown();

    /**
     * Invoked when the negative button is pressed. The client should be notified and the dialog
     * should be dismissed.
     */
    void onNegativePressed();

    /**
     * Invoked when the positive button is pressed. The client should be notified and the dialog
     * should be dismissed.
     */
    void onPositivePressed();

    /**
     * Invoked when the "try again" button is pressed.
     */
    void onTryAgainPressed();
}
