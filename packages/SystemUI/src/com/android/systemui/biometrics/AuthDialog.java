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
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.os.Bundle;
import android.view.WindowManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for the biometric dialog UI.
 */
public interface AuthDialog {

    String KEY_CONTAINER_GOING_AWAY = "container_going_away";
    String KEY_BIOMETRIC_SHOWING = "biometric_showing";
    String KEY_CREDENTIAL_SHOWING = "credential_showing";

    String KEY_BIOMETRIC_CONFIRM_VISIBILITY = "confirm_visibility";
    String KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY = "try_agian_visibility";
    String KEY_BIOMETRIC_STATE = "state";
    String KEY_BIOMETRIC_INDICATOR_STRING = "indicator_string"; // error / help / hint
    String KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING = "error_is_temporary";
    String KEY_BIOMETRIC_INDICATOR_HELP_SHOWING = "hint_is_temporary";
    String KEY_BIOMETRIC_DIALOG_SIZE = "size";

    String KEY_BIOMETRIC_SENSOR_TYPE = "sensor_type";
    String KEY_BIOMETRIC_SENSOR_PROPS = "sensor_props";

    int SIZE_UNKNOWN = 0;
    /**
     * Minimal UI, showing only biometric icon.
     */
    int SIZE_SMALL = 1;
    /**
     * Normal-sized biometric UI, showing title, icon, buttons, etc.
     */
    int SIZE_MEDIUM = 2;
    /**
     * Full-screen credential UI.
     */
    int SIZE_LARGE = 3;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SIZE_UNKNOWN, SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE})
    @interface DialogSize {}

    /**
     * Parameters used when laying out {@link AuthBiometricView}, its subclasses, and
     * {@link AuthPanelController}.
     */
    class LayoutParams {
        final int mMediumHeight;
        final int mMediumWidth;

        LayoutParams(int mediumWidth, int mediumHeight) {
            mMediumWidth = mediumWidth;
            mMediumHeight = mediumHeight;
        }
    }

    /**
     * Animation duration, from small to medium dialog, including back panel, icon translation, etc
     */
    int ANIMATE_SMALL_TO_MEDIUM_DURATION_MS = 150;
    /**
     * Animation duration from medium to large dialog, including biometric fade out, back panel, etc
     */
    int ANIMATE_MEDIUM_TO_LARGE_DURATION_MS = 450;
    /**
     * Delay before notifying {@link AuthCredentialView} to start animating in.
     */
    int ANIMATE_CREDENTIAL_START_DELAY_MS = ANIMATE_MEDIUM_TO_LARGE_DURATION_MS * 2 / 3;
    /**
     * Animation duration when sliding in credential UI
     */
    int ANIMATE_CREDENTIAL_INITIAL_DURATION_MS = 150;

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
     * Save the current state.
     * @param outState
     */
    void onSaveState(@NonNull Bundle outState);

    /**
     * Get the client's package name
     */
    String getOpPackageName();

    /** The requestId of the underlying operation within the framework. */
    long getRequestId();

    /**
     * Animate to credential UI. Typically called after biometric is locked out.
     */
    void animateToCredentialUI();

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
}
