/*
 * Copyright (C) 2021 The Android Open Source Project
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

/**
 * Listener for events related to modality changes during operations.
 *
 * Used by views such as {@link AuthBiometricFaceToFingerprintView} that support fallback style
 * authentication.
 */
public interface ModalityListener {

    /**
     * The modality has changed. Called after the transition has been fully completed.
     *
     * @param oldModality original modality
     * @param newModality current modality
     */
    default void onModalitySwitched(@Modality int oldModality, @Modality int newModality) {}
}
