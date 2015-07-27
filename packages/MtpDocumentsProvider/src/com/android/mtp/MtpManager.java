/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.Context;

import java.io.IOException;

/**
 * The model wrapping android.mtp API.
 */
class MtpManager {
    MtpManager(Context context) {
    }

    void openDevice(int deviceId) throws IOException {
        // TODO: Implement this method.
    }

    void closeDevice(int deviceId) throws IOException {
        // TODO: Implement this method.
    }

    int[] getOpenedDeviceIds() {
        // TODO: Implement this method.
        return null;
    }
}
