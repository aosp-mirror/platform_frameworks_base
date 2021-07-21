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

import android.annotation.Nullable;
import android.hardware.biometrics.BiometricAuthenticator;

/**
 * Interface that {@link BaseClientMonitor} subclasses eligible/interested in removal callbacks
 * should implement.
 */
public interface RemovalConsumer {
    /**
     * @param identifier Fingerprint, face, etc that was removed.
     * @param remaining number of templates that still need to be removed before the operation in
     *                  the HAL is complete (e.g. when removing all templates).
     */
    void onRemoved(@Nullable BiometricAuthenticator.Identifier identifier, int remaining);
}
