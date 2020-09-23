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

package com.android.server.biometrics;

import android.hardware.biometrics.BiometricManager;

/**
 * Parsed sensor config. See core/res/res/values/config.xml config_biometric_sensors
 */
public class SensorConfig {
    public final int id;
    final int modality;
    @BiometricManager.Authenticators.Types public final int strength;

    public SensorConfig(String config) {
        String[] elems = config.split(":");
        id = Integer.parseInt(elems[0]);
        modality = Integer.parseInt(elems[1]);
        strength = Integer.parseInt(elems[2]);
    }
}
