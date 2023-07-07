/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.ambientcontext;

import android.annotation.NonNull;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.os.RemoteCallback;

import java.io.PrintWriter;

/**
 * Interface for a remote service implementing Ambient Context Detection Service capabilities.
 */
interface RemoteAmbientDetectionService {
    /**
     * Asks the implementation to start detection.
     *
     * @param request The request with events to detect, and optional detection options.
     * @param packageName The app package that requested the detection
     * @param detectionResultCallback callback for detection results
     * @param statusCallback callback for service status
     */
    void startDetection(
            @NonNull AmbientContextEventRequest request, String packageName,
            RemoteCallback detectionResultCallback, RemoteCallback statusCallback);

    /**
     * Asks the implementation to stop detection.
     *
     * @param packageName stop detection for the given package
     */
    void stopDetection(String packageName);

    /**
     * Asks the implementation to return the event status for the package.
     */
    void queryServiceStatus(
            @AmbientContextEvent.EventCode int[] eventTypes,
            String packageName,
            RemoteCallback callback);

    /**
     * Dumps the RemoteAmbientDetectionService.
     */
    void dump(@NonNull String prefix, @NonNull PrintWriter pw);

    /**
     * Unbinds from the remote service.
     */
    void unbind();
}
