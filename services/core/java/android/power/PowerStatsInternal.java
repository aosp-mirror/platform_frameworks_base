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

//import android.hardware.power.stats.EnergyConsumerId;
import android.hardware.power.stats.EnergyConsumerResult;

import java.util.concurrent.CompletableFuture;

/**
 * Power stats local system service interface.
 *
 * @hide Only for use within Android OS.
 */
public abstract class PowerStatsInternal {
    /**
     * Returns a CompletableFuture that will get an {@link EnergyConsumerResult} array for the
     * available requested energy consumers (power models).
     *
     * @param energyConsumerIds Array of {@link EnergyConsumerId} for which energy consumed is being
     *                          requested.
     *
     * @return A Future containing a list of {@link EnergyConsumerResult} objects containing energy
     *         consumer results for all listed {@link EnergyConsumerId}.
     */
    public abstract CompletableFuture<EnergyConsumerResult[]> getEnergyConsumedAsync(
            /*@EnergyConsumerId*/ int[] energyConsumerIds);
}
