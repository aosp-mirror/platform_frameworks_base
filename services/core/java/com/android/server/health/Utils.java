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

package com.android.server.health;

/**
 * Utils for {@link om.android.server.BatteryService} to deal with health info structs.
 *
 * @hide
 */
public class Utils {
    private Utils() {}

    /**
     * Copy health info struct.
     *
     * @param dst destination
     * @param src source
     */
    public static void copy(
            android.hardware.health.V1_0.HealthInfo dst,
            android.hardware.health.V1_0.HealthInfo src) {
        dst.chargerAcOnline = src.chargerAcOnline;
        dst.chargerUsbOnline = src.chargerUsbOnline;
        dst.chargerWirelessOnline = src.chargerWirelessOnline;
        dst.maxChargingCurrent = src.maxChargingCurrent;
        dst.maxChargingVoltage = src.maxChargingVoltage;
        dst.batteryStatus = src.batteryStatus;
        dst.batteryHealth = src.batteryHealth;
        dst.batteryPresent = src.batteryPresent;
        dst.batteryLevel = src.batteryLevel;
        dst.batteryVoltage = src.batteryVoltage;
        dst.batteryTemperature = src.batteryTemperature;
        dst.batteryCurrent = src.batteryCurrent;
        dst.batteryCycleCount = src.batteryCycleCount;
        dst.batteryFullCharge = src.batteryFullCharge;
        dst.batteryChargeCounter = src.batteryChargeCounter;
        dst.batteryTechnology = src.batteryTechnology;
    }

    /**
     * Copy battery fields of {@link android.hardware.health.HealthInfo} V1. This excludes
     * non-battery fields like {@link android.hardware.health.HealthInfo#diskStats diskStats} and
     * {@link android.hardware.health.HealthInfo#storageInfos storageInfos}
     *
     * @param dst destination
     * @param src source
     */
    public static void copyV1Battery(
            android.hardware.health.HealthInfo dst, android.hardware.health.HealthInfo src) {
        dst.chargerAcOnline = src.chargerAcOnline;
        dst.chargerUsbOnline = src.chargerUsbOnline;
        dst.chargerWirelessOnline = src.chargerWirelessOnline;
        dst.maxChargingCurrentMicroamps = src.maxChargingCurrentMicroamps;
        dst.maxChargingVoltageMicrovolts = src.maxChargingVoltageMicrovolts;
        dst.batteryStatus = src.batteryStatus;
        dst.batteryHealth = src.batteryHealth;
        dst.batteryPresent = src.batteryPresent;
        dst.batteryLevel = src.batteryLevel;
        dst.batteryVoltageMillivolts = src.batteryVoltageMillivolts;
        dst.batteryTemperatureTenthsCelsius = src.batteryTemperatureTenthsCelsius;
        dst.batteryCurrentMicroamps = src.batteryCurrentMicroamps;
        dst.batteryCycleCount = src.batteryCycleCount;
        dst.batteryFullChargeUah = src.batteryFullChargeUah;
        dst.batteryChargeCounterUah = src.batteryChargeCounterUah;
        dst.batteryTechnology = src.batteryTechnology;
        dst.batteryCurrentAverageMicroamps = src.batteryCurrentAverageMicroamps;
        dst.batteryCapacityLevel = src.batteryCapacityLevel;
        dst.batteryChargeTimeToFullNowSeconds = src.batteryChargeTimeToFullNowSeconds;
        dst.batteryFullChargeDesignCapacityUah = src.batteryFullChargeDesignCapacityUah;
    }
}
