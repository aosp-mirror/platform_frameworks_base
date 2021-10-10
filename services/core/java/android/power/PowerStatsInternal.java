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

package android.power;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.StateResidencyResult;

import java.util.concurrent.CompletableFuture;

/**
 * Power stats local system service interface.
 *
 * @hide Only for use within Android OS.
 */
public abstract class PowerStatsInternal {
    /**
     * Returns the energy consumer info for all available {@link EnergyConsumer}
     *
     * @return List of available {@link EnergyConsumer}, or null if {@link EnergyConsumer} not
     * supported
     */
    @Nullable
    public abstract EnergyConsumer[] getEnergyConsumerInfo();

    /**
     * Returns a CompletableFuture that will get an {@link EnergyConsumerResult} array for the
     * available requested energy consumers (power models).
     *
     * @param energyConsumerIds Array of {@link EnergyConsumerId} for which energy consumed is being
     *                          requested.
     *
     * @return A Future containing a list of {@link EnergyConsumerResult} objects containing energy
     *         consumer results for all listed {@link EnergyConsumerId}. null if
     *         {@link EnergyConsumer} not supported
     */
    @NonNull
    public abstract CompletableFuture<EnergyConsumerResult[]> getEnergyConsumedAsync(
            int[] energyConsumerIds);

    /**
     * Returns the power entity info for all available {@link PowerEntity}
     *
     * @return List of available {@link PowerEntity} or null if {@link PowerEntity} not
     * supported
     */
    @Nullable
    public abstract PowerEntity[] getPowerEntityInfo();

    /**
     * Returns a CompletableFuture that will get a {@link StateResidencyResult} array for the
     * available requested power entities.
     *
     * @param powerEntityIds Array of {@link PowerEntity.id} for which state residency is being
     *                          requested.
     *
     * @return A Future containing a list of {@link StateResidencyResult} objects containing state
     *         residency results for all listed {@link PowerEntity.id}. null if {@link PowerEntity}
     *         not supported
     */
    @NonNull
    public abstract CompletableFuture<StateResidencyResult[]> getStateResidencyAsync(
            int[] powerEntityIds);

    /**
     * Returns the channel info for all available {@link Channel}
     *
     * @return List of available {@link Channel} or null if {@link Channel} not supported
     */
    @Nullable
    public abstract Channel[] getEnergyMeterInfo();

    /**
     * Returns a CompletableFuture that will get a {@link EnergyMeasurement} array for the
     * available requested channels.
     *
     * @param channelIds Array of {@link Channel.id} for accumulated energy is being requested.
     *
     * @return A Future containing a list of {@link EnergyMeasurement} objects containing
     *         accumulated energy measurements for all listed {@link Channel.id}. null if
     *         {@link Channel} not supported
     */
    @NonNull
    public abstract CompletableFuture<EnergyMeasurement[]> readEnergyMeterAsync(
            int[] channelIds);
}
