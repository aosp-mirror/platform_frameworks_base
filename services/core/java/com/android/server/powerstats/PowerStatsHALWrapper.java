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

/**
 * PowerStatsHALWrapper is a wrapper class for the PowerStats HAL API calls.
 */
public final class PowerStatsHALWrapper {
    private static final String TAG = PowerStatsHALWrapper.class.getSimpleName();

    /**
     * IPowerStatsHALWrapper defines the interface to the PowerStatsHAL.
     */
    public interface IPowerStatsHALWrapper {
        /**
         * Returns rail info for all available ODPM rails.
         *
         * @return array of RailInfo objects containing rail info for all
         *         available rails.
         */
        PowerStatsData.RailInfo[] readRailInfo();

        /**
         * Returns energy data for all available ODPM rails.  Available rails can
         *         be retrieved by calling nativeGetRailInfo.  Energy data and
         *         rail info can be linked through the index field.
         *
         * @return array of EnergyData objects containing energy data for all
         *         available rails.
         */
        PowerStatsData.EnergyData[] readEnergyData();

        /**
         * Returns boolean indicating if connection to power stats HAL was
         *         established.
         *
         * @return true if connection to power stats HAL was correctly established
         *         and that energy data and rail info can be read from the interface.
         *         false otherwise
         */
        boolean initialize();
    }

    /**
     * PowerStatsHALWrapperImpl is the implementation of the IPowerStatsHALWrapper
     * used by the PowerStatsService.  Other implementations will be used by the testing
     * framework and will be passed into the PowerStatsService through an injector.
     */
    public static final class PowerStatsHALWrapperImpl implements IPowerStatsHALWrapper {
        private static native boolean nativeInit();
        private static native PowerStatsData.RailInfo[] nativeGetRailInfo();
        private static native PowerStatsData.EnergyData[] nativeGetEnergyData();

        /**
         * Returns rail info for all available ODPM rails.
         *
         * @return array of RailInfo objects containing rail info for all
         *         available rails.
         */
        @Override
        public PowerStatsData.RailInfo[] readRailInfo() {
            return nativeGetRailInfo();
        }

        /**
         * Returns energy data for all available ODPM rails.  Available rails can
         *         be retrieved by calling nativeGetRailInfo.  Energy data and
         *         rail info can be linked through the index field.
         *
         * @return array of EnergyData objects containing energy data for all
         *         available rails.
         */
        @Override
        public PowerStatsData.EnergyData[] readEnergyData() {
            return nativeGetEnergyData();
        }

        /**
         * Returns boolean indicating if connection to power stats HAL was
         *         established.
         *
         * @return true if connection to power stats HAL was correctly established
         *         and that energy data and rail info can be read from the interface.
         *         false otherwise
         */
        @Override
        public boolean initialize() {
            return nativeInit();
        }
    }
}
