/*
 * Copyright (C) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/license/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.location;

import android.hardware.location.IFusedLocationHardwareSink;
import android.location.FusedBatchOptions;

/**
 * Fused Location hardware interface.
 * This interface is the basic set of supported functionality by Fused Hardware
 * modules that offer Location batching capabilities.
 *
 * @hide
 */
interface IFusedLocationHardware {
    /**
     * Registers a sink with the Location Hardware object.
     *
     * @param eventSink     The sink to register.
     */
    void registerSink(in IFusedLocationHardwareSink eventSink) = 0;

    /**
     * Unregisters a sink with the Location Hardware object.
     *
     * @param eventSink     The sink to unregister.
     */
    void unregisterSink(in IFusedLocationHardwareSink eventSink) = 1;

    /**
     * Provides access to the batch size available in Hardware.
     *
     * @return The batch size the hardware supports.
     */
    int getSupportedBatchSize() = 2;

    /**
     * Requests the Hardware to start batching locations.
     *
     * @param id            An Id associated with the request.
     * @param batchOptions  The options required for batching.
     *
     * @throws RuntimeException if the request Id exists.
     */
    void startBatching(in int id, in FusedBatchOptions batchOptions) = 3;

    /**
     * Requests the Hardware to stop batching for the given Id.
     *
     * @param id    The request that needs to be stopped.
     * @throws RuntimeException if the request Id is unknown.
     */
    void stopBatching(in int id) = 4;

    /**
     * Updates a batching operation in progress.
     *
     * @param id                The Id of the operation to update.
     * @param batchOptions     The options to apply to the given operation.
     *
     * @throws RuntimeException if the Id of the request is unknown.
     */
    void updateBatchingOptions(in int id, in FusedBatchOptions batchOptions) = 5;

    /**
     * Requests the most recent locations available in Hardware.
     * This operation does not dequeue the locations, so still other batching
     * events will continue working.
     *
     * @param batchSizeRequested    The number of locations requested.
     */
    void requestBatchOfLocations(in int batchSizeRequested) = 6;

    /**
     * Flags if the Hardware supports injection of diagnostic data.
     *
     * @return True if data injection is supported, false otherwise.
     */
    boolean supportsDiagnosticDataInjection() = 7;

    /**
     * Injects diagnostic data into the Hardware subsystem.
     *
     * @param data  The data to inject.
     * @throws RuntimeException if injection is not supported.
     */
    void injectDiagnosticData(in String data) = 8;

    /**
     * Flags if the Hardware supports injection of device context information.
     *
     * @return True if device context injection is supported, false otherwise.
     */
    boolean supportsDeviceContextInjection() = 9;

    /**
     * Injects device context information into the Hardware subsystem.
     *
     * @param deviceEnabledContext  The context to inject.
     * @throws RuntimeException if injection is not supported.
     */
    void injectDeviceContext(in int deviceEnabledContext) = 10;

    /**
     * Requests all batched locations currently available in Hardware
     * and clears the buffer.  Any subsequent calls will not return any
     * of the locations returned in this call.
     */
    void flushBatchedLocations() = 11;

    /**
     * Returns the version of this FLP HAL implementation.
     */
    int getVersion() = 12;
}
