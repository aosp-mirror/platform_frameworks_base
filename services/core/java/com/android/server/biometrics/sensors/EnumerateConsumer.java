/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.hardware.biometrics.BiometricAuthenticator;

/**
 * Interface that {@link BaseClientMonitor} subclasses eligible/interested in enumerate callbacks
 * should implement.
 */
public interface EnumerateConsumer {
    /**
     * @param identifier Fingerprint, face, etc template that exists in the HAL.
     * @param remaining number of templates that exist but have not been reported to the
     *                  framework yet.
     */
    void onEnumerationResult(BiometricAuthenticator.Identifier identifier, int remaining);
}
