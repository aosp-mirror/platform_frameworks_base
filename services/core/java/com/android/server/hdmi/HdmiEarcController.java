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

package com.android.server.hdmi;

import android.os.Handler;
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;

final class HdmiEarcController {
    private static final String TAG = "HdmiEarcController";

    // Handler instance to process HAL calls.
    private Handler mControlHandler;

    private final HdmiControlService mService;

    // Private constructor. Use HdmiEarcController.create().
    private HdmiEarcController(HdmiControlService service) {
        mService = service;
    }

    /**
     * A factory method to get {@link HdmiEarcController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     * @param service    {@link HdmiControlService} instance used to create internal handler
     *                   and to pass callback for incoming message or event.
     * @return {@link HdmiEarcController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiEarcController create(HdmiControlService service) {
        // TODO add the native wrapper and return null if eARC HAL is not present.
        HdmiEarcController controller = new HdmiEarcController(service);
        controller.init();
        return controller;
    }

    private void init() {
        mControlHandler = new Handler(mService.getServiceLooper());
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mControlHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    @VisibleForTesting
    void runOnServiceThread(Runnable runnable) {
        mControlHandler.post(new WorkSourceUidPreservingRunnable(runnable));
    }

    /**
     * Enable eARC in the HAL
     * @param enabled
     */
    @HdmiAnnotations.ServiceThreadOnly
    void setEarcEnabled(boolean enabled) {
        assertRunOnServiceThread();
        // Stub.
        // TODO: bind to native.
        // TODO: handle error return values here, with logging.
    }

    /**
     * Getter for the current eARC state.
     * @param portId the ID of the port on which to get the connection state
     * @return the current eARC state
     */
    @HdmiAnnotations.ServiceThreadOnly
    @Constants.EarcStatus
    int getState(int portId) {
        // Stub.
        // TODO: bind to native.
        return Constants.HDMI_EARC_STATUS_IDLE;
    }

     /**
     * Ask the HAL to report the last eARC capabilities that the connected audio system reported.
     * @return the raw eARC capabilities
     */
    @HdmiAnnotations.ServiceThreadOnly
    byte[] getLastReportedCaps() {
        // Stub. TODO: bind to native.
        return new byte[] {};
    }

    final class EarcCallback {
        public void onStateChange(@Constants.EarcStatus int status, int portId) {
            runOnServiceThread(
                    () -> mService.handleEarcStateChange(status, portId));
        }

        public void onCapabilitiesReported(byte[] rawCapabilities, int portId) {
            runOnServiceThread(
                    () -> mService.handleEarcCapabilitiesReported(rawCapabilities, portId));
        }
    }

    // TODO: bind to native.
}
