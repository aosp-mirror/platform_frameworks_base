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

package com.android.server.biometrics.sensors;

/**
 * Interface that {@link BaseClientMonitor} subclasses eligible/interested in error callbacks should
 * implement.
 */
public interface ErrorConsumer {
    /**
     * Notifies the client of errors from the HAL.
     * @param errorCode defined by the HIDL interface
     * @param vendorCode defined by the vendor
     */
    void onError(int errorCode, int vendorCode);
}
