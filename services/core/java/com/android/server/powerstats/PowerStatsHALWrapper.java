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

package com.android.server.powerstats;

import android.annotation.Nullable;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.IPowerStats;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.StateResidencyResult;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.function.Supplier;

/**
 * PowerStatsHALWrapper is a wrapper class for the PowerStats HAL API calls.
 */
public final class PowerStatsHALWrapper {
    private static final String TAG = PowerStatsHALWrapper.class.getSimpleName();
    private static final boolean DEBUG = false;

    /**
     * IPowerStatsHALWrapper defines the interface to the PowerStatsHAL.
     */
    public interface IPowerStatsHALWrapper {
        /**
         * Returns information related to all supported PowerEntity(s) for which state residency
         * data is available.
         *
         * A PowerEntity is defined as a platform subsystem, peripheral, or power domain that
         * impacts the total device power consumption.
         *
         * @return List of information on each PowerEntity.
         */
        @Nullable
        android.hardware.power.stats.PowerEntity[] getPowerEntityInfo();

        /**
         * Reports the accumulated state residency for each requested PowerEntity.
         *
         * Each PowerEntity may reside in one of multiple states. It may also transition from one
         * state to another. StateResidency is defined as an accumulation of time that a
         * PowerEntity resided in each of its possible states, the number of times that each state
         * was entered, and a timestamp corresponding to the last time that state was entered.
         *
         * Data is accumulated starting at device boot.
         *
         * @param powerEntityIds List of IDs of PowerEntities for which data is requested.  Passing
         *                       an empty list will return state residency for all available
         *                       PowerEntities.  ID of each PowerEntity is contained in
         *                       PowerEntity.
         *
         * @return StateResidency since boot for each requested PowerEntity
         */
        @Nullable
        android.hardware.power.stats.StateResidencyResult[] getStateResidency(int[] powerEntityIds);

        /**
         * Returns the energy consumer info for all available energy consumers (power models) on the
         * device.  Examples of subsystems for which energy consumer results (power models) may be
         * available are GPS, display, wifi, etc.  The default list of energy consumers can be
         * found in the PowerStats HAL definition (EnergyConsumerType.aidl).  The availability of
         * energy consumer IDs is hardware dependent.
         *
         * @return List of EnergyConsumers all available energy consumers.
         */
        @Nullable
        android.hardware.power.stats.EnergyConsumer[] getEnergyConsumerInfo();

        /**
         * Returns the energy consumer result for all available energy consumers (power models).
         * Available consumers can be retrieved by calling getEnergyConsumerInfo().  The subsystem
         * corresponding to the energy consumer result is defined by the energy consumer ID.
         *
         * @param energyConsumerIds Array of energy consumer IDs for which energy consumed is being
         *                          requested.  Energy consumers available on the device can be
         *                          queried by calling getEnergyConsumerInfo().  Passing an empty
         *                          array will return results for all energy consumers.
         *
         * @return List of EnergyConsumerResult objects containing energy consumer results for all
         *         available energy consumers (power models).
         */
        @Nullable
        android.hardware.power.stats.EnergyConsumerResult[] getEnergyConsumed(
                int[] energyConsumerIds);

        /**
         * Returns channel info for all available energy meters.
         *
         * @return List of Channel objects containing channel info for all available energy
         *         meters.
         */
        @Nullable
        android.hardware.power.stats.Channel[] getEnergyMeterInfo();

        /**
         * Returns energy measurements for all available energy meters.  Available channels can be
         * retrieved by calling getEnergyMeterInfo().  Energy measurements and channel info can be
         * linked through the channelId field.
         *
         * @param channelIds Array of channel IDs for which energy measurements are being requested.
         *                   Channel IDs available on the device can be queried by calling
         *                   getEnergyMeterInfo().  Passing an empty array will return energy
         *                   measurements for all channels.
         *
         * @return List of EnergyMeasurement objects containing energy measurements for all
         *         available energy meters.
         */
        @Nullable
        android.hardware.power.stats.EnergyMeasurement[] readEnergyMeter(int[] channelIds);

        /**
         * Returns boolean indicating if connection to power stats HAL was established.
         *
         * @return true if connection to power stats HAL was correctly established.
         */
        boolean isInitialized();
    }

    /**
     * PowerStatsHALWrapper20Impl is the implementation of the IPowerStatsHALWrapper
     * used by the PowerStatsService on devices that support only PowerStats HAL 2.0.
     * Other implementations will be used by the testing framework and will be passed
     * into the PowerStatsService through an injector.
     */
    public static final class PowerStatsHAL20WrapperImpl implements IPowerStatsHALWrapper {
        private static Supplier<IPowerStats> sVintfPowerStats;

        public PowerStatsHAL20WrapperImpl() {
            Supplier<IPowerStats> service = new VintfHalCache();
            sVintfPowerStats = null;

            if (service.get() == null) {
                if (DEBUG) Slog.d(TAG, "PowerStats HAL 2.0 not available on this device.");
                sVintfPowerStats = null;
            } else {
                sVintfPowerStats = service;
            }
        }

        @Override
        public android.hardware.power.stats.PowerEntity[] getPowerEntityInfo() {
            android.hardware.power.stats.PowerEntity[] powerEntityHAL = null;

            if (sVintfPowerStats != null) {
                try {
                    powerEntityHAL = sVintfPowerStats.get().getPowerEntityInfo();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to get power entity info: ", e);
                }
            }

            return powerEntityHAL;
        }

        @Override
        public android.hardware.power.stats.StateResidencyResult[] getStateResidency(
                int[] powerEntityIds) {
            android.hardware.power.stats.StateResidencyResult[] stateResidencyResultHAL = null;

            if (sVintfPowerStats != null) {
                try {
                    stateResidencyResultHAL =
                        sVintfPowerStats.get().getStateResidency(powerEntityIds);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to get state residency: ", e);
                }
            }

            return stateResidencyResultHAL;
        }

        @Override
        public android.hardware.power.stats.EnergyConsumer[] getEnergyConsumerInfo() {
            android.hardware.power.stats.EnergyConsumer[] energyConsumerHAL = null;

            if (sVintfPowerStats != null) {
                try {
                    energyConsumerHAL = sVintfPowerStats.get().getEnergyConsumerInfo();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to get energy consumer info: ", e);
                }
            }

            return energyConsumerHAL;
        }

        @Override
        public android.hardware.power.stats.EnergyConsumerResult[] getEnergyConsumed(
                int[] energyConsumerIds) {
            android.hardware.power.stats.EnergyConsumerResult[] energyConsumedHAL = null;

            if (sVintfPowerStats != null) {
                try {
                    energyConsumedHAL =
                        sVintfPowerStats.get().getEnergyConsumed(energyConsumerIds);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to get energy consumer results: ", e);
                }
            }

            return energyConsumedHAL;
        }

        @Override
        public android.hardware.power.stats.Channel[] getEnergyMeterInfo() {
            android.hardware.power.stats.Channel[] energyMeterInfoHAL = null;

            if (sVintfPowerStats != null) {
                try {
                    energyMeterInfoHAL = sVintfPowerStats.get().getEnergyMeterInfo();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to get energy meter info: ", e);
                }
            }

            return energyMeterInfoHAL;
        }

        @Override
        public android.hardware.power.stats.EnergyMeasurement[] readEnergyMeter(int[] channelIds) {
            android.hardware.power.stats.EnergyMeasurement[] energyMeasurementHAL = null;

            if (sVintfPowerStats != null) {
                try {
                    energyMeasurementHAL =
                        sVintfPowerStats.get().readEnergyMeter(channelIds);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to get energy measurements: ", e);
                }
            }

            return energyMeasurementHAL;
        }

        @Override
        public boolean isInitialized() {
            return (sVintfPowerStats != null);
        }
    }

    /**
     * PowerStatsHALWrapper10Impl is the implementation of the IPowerStatsHALWrapper
     * used by the PowerStatsService on devices that support only PowerStats HAL 1.0.
     * Other implementations will be used by the testing framework and will be passed
     * into the PowerStatsService through an injector.
     */
    public static final class PowerStatsHAL10WrapperImpl implements IPowerStatsHALWrapper {
        private boolean mIsInitialized;

        // PowerStatsHAL 1.0 native functions exposed by JNI layer.
        private static native boolean nativeInit();
        private static native PowerEntity[] nativeGetPowerEntityInfo();
        private static native StateResidencyResult[] nativeGetStateResidency(int[] powerEntityIds);
        private static native Channel[] nativeGetEnergyMeterInfo();
        private static native EnergyMeasurement[] nativeReadEnergyMeters(int[] channelIds);

        public PowerStatsHAL10WrapperImpl() {
            if (nativeInit()) {
                mIsInitialized = true;
            } else {
                if (DEBUG) Slog.d(TAG, "PowerStats HAL 1.0 not available on this device.");
                mIsInitialized = false;
            }
        }

        @Override
        public android.hardware.power.stats.PowerEntity[] getPowerEntityInfo() {
            return nativeGetPowerEntityInfo();
        }

        @Override
        public android.hardware.power.stats.StateResidencyResult[] getStateResidency(
                int[] powerEntityIds) {
            return nativeGetStateResidency(powerEntityIds);
        }

        @Override
        public android.hardware.power.stats.EnergyConsumer[] getEnergyConsumerInfo() {
            if (DEBUG) Slog.d(TAG, "Energy consumer info is not supported");
            return new android.hardware.power.stats.EnergyConsumer[0];
        }

        @Override
        public android.hardware.power.stats.EnergyConsumerResult[] getEnergyConsumed(
                int[] energyConsumerIds) {
            if (DEBUG) Slog.d(TAG, "Energy consumer results are not supported");
            return new android.hardware.power.stats.EnergyConsumerResult[0];
        }

        @Override
        public android.hardware.power.stats.Channel[] getEnergyMeterInfo() {
            return nativeGetEnergyMeterInfo();
        }

        @Override
        public android.hardware.power.stats.EnergyMeasurement[] readEnergyMeter(int[] channelIds) {
            return nativeReadEnergyMeters(channelIds);
        }

        @Override
        public boolean isInitialized() {
            return mIsInitialized;
        }
    }

    /**
     * Returns an instance of an IPowerStatsHALWrapper.  If PowerStats HAL 2.0 is supported on the
     * device, return a PowerStatsHAL20WrapperImpl, else return a PowerStatsHAL10WrapperImpl.
     *
     * @return an instance of an IPowerStatsHALWrapper where preference is given to PowerStats HAL
     *         2.0.
     */
    public static IPowerStatsHALWrapper getPowerStatsHalImpl() {
        PowerStatsHAL20WrapperImpl powerStatsHAL20WrapperImpl = new PowerStatsHAL20WrapperImpl();
        if (powerStatsHAL20WrapperImpl.isInitialized()) {
            return powerStatsHAL20WrapperImpl;
        } else {
            return new PowerStatsHAL10WrapperImpl();
        }
    }

    private static class VintfHalCache implements Supplier<IPowerStats>, IBinder.DeathRecipient {
        @GuardedBy("this")
        private IPowerStats mInstance = null;

        @Override
        public synchronized IPowerStats get() {
            if (mInstance == null) {
                IBinder binder = Binder.allowBlocking(ServiceManager.waitForDeclaredService(
                        "android.hardware.power.stats.IPowerStats/default"));
                if (binder != null) {
                    mInstance = IPowerStats.Stub.asInterface(binder);
                    try {
                        binder.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to register DeathRecipient for " + mInstance);
                    }
                }
            }
            return mInstance;
        }

        @Override
        public synchronized void binderDied() {
            Slog.w(TAG, "PowerStats HAL died");
            mInstance = null;
        }
    }
}
