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

import android.annotation.NonNull;

/**
 * Interface that {@link ClientMonitor} subclasses eligible/interested in error callbacks should
 * implement.
 */
public interface Interruptable {
    /**
     * Requests to end the ClientMonitor's lifecycle.
     */
    void cancel();

    /**
     * Notifies the client of errors from the HAL.
     * @param errorCode defined by the HIDL interface
     * @param vendorCode defined by the vendor
     */
    void onError(int errorCode, int vendorCode);

    /**
     * Notifies the client that it needs to finish before
     * {@link ClientMonitor#start(ClientMonitor.FinishCallback)} was invoked. This usually happens
     * if the client is still waiting in the pending queue and got notified that a subsequent
     * operation is preempting it.
     * @param finishCallback invoked when the operation is completed.
     */
    void cancelWithoutStarting(@NonNull ClientMonitor.FinishCallback finishCallback);
}
