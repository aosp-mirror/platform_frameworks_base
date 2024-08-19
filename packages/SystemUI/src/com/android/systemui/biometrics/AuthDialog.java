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

import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.view.WindowManager;

import com.android.systemui.Dumpable;
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel;

/**
 * Interface for the biometric dialog UI.
 *
 * TODO(b/287311775): remove along with legacy controller once flag is removed
 */
@Deprecated
public interface AuthDialog extends Dumpable {

    /**
     * Parameters used when laying out {@link AuthBiometricView}, its subclasses, and
     * {@link AuthPanelController}.
     */
    class LayoutParams {
        public final int mMediumHeight;
        public final int mMediumWidth;

        public LayoutParams(int mediumWidth, int mediumHeight) {
            mMediumWidth = mediumWidth;
            mMediumHeight = mediumHeight;
        }
    }

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
    void onAuthenticationSucceeded(@Modality int modality);

    /**
     * Authentication failed (reject, timeout). Dialog stays showing.
     * @param modality sensor modality that triggered the error
     * @param failureReason message
     */
    void onAuthenticationFailed(@Modality int modality, String failureReason);

    /**
     * Authentication rejected, or help message received.
     * @param modality sensor modality that triggered the help message
     * @param help message
     */
    void onHelp(@Modality int modality, String help);

    /**
     * Authentication failed. Dialog going away.
     * @param modality sensor modality that triggered the error
     * @param error message
     */
    void onError(@Modality int modality, String error);

    /** UDFPS pointer down event. */
    void onPointerDown();

    /**
     * Get the client's package name
     */
    String getOpPackageName();

    /**
     * Get the class name of ConfirmDeviceCredentialActivity. Returns null if the direct caller is
     * not ConfirmDeviceCredentialActivity.
     */
    String getClassNameIfItIsConfirmDeviceCredentialActivity();

    /** The requestId of the underlying operation within the framework. */
    long getRequestId();

    /**
     * Animate to credential UI. Typically called after biometric is locked out.
     */
    void animateToCredentialUI(boolean isError);

    /**
     * @return true if device credential is allowed.
     */
    boolean isAllowDeviceCredentials();

    /**
     * Called when the device's orientation changed and the dialog may need to do another
     * layout. This is most relevant to UDFPS since configuration changes are not sent by
     * the framework in equivalent cases (landscape to reverse landscape) but the dialog
     * must remain fixed on the physical sensor location.
     */
    void onOrientationChanged();

    PromptViewModel getViewModel();
}
