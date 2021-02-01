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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for objects containing battery attribution data.
 *
 * @hide
 */
public abstract class BatteryConsumer {

    /**
     * Power usage component, describing the particular part of the system
     * responsible for power drain.
     *
     * @hide
     */
    @IntDef(prefix = {"POWER_COMPONENT_"}, value = {
            POWER_COMPONENT_USAGE,
            POWER_COMPONENT_CPU,
            POWER_COMPONENT_BLUETOOTH,
            POWER_COMPONENT_CAMERA,
            POWER_COMPONENT_AUDIO,
            POWER_COMPONENT_VIDEO,
            POWER_COMPONENT_FLASHLIGHT,
            POWER_COMPONENT_MOBILE_RADIO,
            POWER_COMPONENT_SYSTEM_SERVICES,
            POWER_COMPONENT_SENSORS,
            POWER_COMPONENT_GNSS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface PowerComponent {
    }

    public static final int POWER_COMPONENT_USAGE = 0;
    public static final int POWER_COMPONENT_CPU = 1;
    public static final int POWER_COMPONENT_BLUETOOTH = 2;
    public static final int POWER_COMPONENT_CAMERA = 3;
    public static final int POWER_COMPONENT_AUDIO = 4;
    public static final int POWER_COMPONENT_VIDEO = 5;
    public static final int POWER_COMPONENT_FLASHLIGHT = 6;
    public static final int POWER_COMPONENT_SYSTEM_SERVICES = 7;
    public static final int POWER_COMPONENT_MOBILE_RADIO = 8;
    public static final int POWER_COMPONENT_SENSORS = 9;
    public static final int POWER_COMPONENT_GNSS = 10;

    public static final int POWER_COMPONENT_COUNT = 11;

    public static final int FIRST_CUSTOM_POWER_COMPONENT_ID = 1000;
    public static final int LAST_CUSTOM_POWER_COMPONENT_ID = 9999;

    /**
     * Time usage component, describing the particular part of the system
     * that was used for the corresponding amount of time.
     *
     * @hide
     */
    @IntDef(prefix = {"TIME_COMPONENT_"}, value = {
            TIME_COMPONENT_USAGE,
            TIME_COMPONENT_CPU,
            TIME_COMPONENT_CPU_FOREGROUND,
            TIME_COMPONENT_BLUETOOTH,
            TIME_COMPONENT_CAMERA,
            TIME_COMPONENT_FLASHLIGHT,
            TIME_COMPONENT_MOBILE_RADIO,
            TIME_COMPONENT_SENSORS,
            TIME_COMPONENT_GNSS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface TimeComponent {
    }

    public static final int TIME_COMPONENT_USAGE = 0;
    public static final int TIME_COMPONENT_CPU = 1;
    public static final int TIME_COMPONENT_CPU_FOREGROUND = 2;
    public static final int TIME_COMPONENT_BLUETOOTH = 3;
    public static final int TIME_COMPONENT_CAMERA = 4;
    public static final int TIME_COMPONENT_AUDIO = 5;
    public static final int TIME_COMPONENT_VIDEO = 6;
    public static final int TIME_COMPONENT_FLASHLIGHT = 7;
    public static final int TIME_COMPONENT_MOBILE_RADIO = 8;
    public static final int TIME_COMPONENT_SENSORS = 9;
    public static final int TIME_COMPONENT_GNSS = 10;

    public static final int TIME_COMPONENT_COUNT = 11;

    public static final int FIRST_CUSTOM_TIME_COMPONENT_ID = 1000;
    public static final int LAST_CUSTOM_TIME_COMPONENT_ID = 9999;

    private final PowerComponents mPowerComponents;

    protected BatteryConsumer(@NonNull PowerComponents powerComponents) {
        mPowerComponents = powerComponents;
    }

    /**
     * Total power consumed by this consumer, in mAh.
     */
    public double getConsumedPower() {
        return mPowerComponents.getTotalPowerConsumed();
    }

    /**
     * Returns the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
     *
     * @param componentId The ID of the power component, e.g.
     *                    {@link BatteryConsumer#POWER_COMPONENT_CPU}.
     * @return Amount of consumed power in mAh.
     */
    public double getConsumedPower(@PowerComponent int componentId) {
        return mPowerComponents.getConsumedPower(componentId);
    }

    /**
     * Returns the amount of drain attributed to the specified custom drain type.
     *
     * @param componentId The ID of the custom power component.
     * @return Amount of consumed power in mAh.
     */
    public double getConsumedPowerForCustomComponent(int componentId) {
        return mPowerComponents.getConsumedPowerForCustomComponent(componentId);
    }

    /**
     * Returns the amount of time since BatteryStats reset used by the specified component, e.g.
     * CPU, WiFi etc.
     *
     * @param componentId The ID of the time component, e.g.
     *                    {@link UidBatteryConsumer#TIME_COMPONENT_CPU}.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationMillis(@TimeComponent int componentId) {
        return mPowerComponents.getUsageDurationMillis(componentId);
    }

    /**
     * Returns the amount of usage time attributed to the specified custom component
     * since BatteryStats reset.
     *
     * @param componentId The ID of the custom power component.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationForCustomComponentMillis(int componentId) {
        return mPowerComponents.getUsageDurationForCustomComponentMillis(componentId);
    }

    protected void writeToParcel(Parcel dest, int flags) {
        mPowerComponents.writeToParcel(dest, flags);
    }

    protected abstract static class BaseBuilder<T extends BaseBuilder<?>> {
        final PowerComponents.Builder mPowerComponentsBuilder;

        public BaseBuilder(int customPowerComponentCount, int customTimeComponentCount) {
            mPowerComponentsBuilder = new PowerComponents.Builder(customPowerComponentCount,
                    customTimeComponentCount);
        }

        /**
         * Sets the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
         *
         * @param componentId    The ID of the power component, e.g.
         *                       {@link BatteryConsumer#POWER_COMPONENT_CPU}.
         * @param componentPower Amount of consumed power in mAh.
         */
        @SuppressWarnings("unchecked")
        @NonNull
        public T setConsumedPower(@PowerComponent int componentId, double componentPower) {
            mPowerComponentsBuilder.setConsumedPower(componentId, componentPower);
            return (T) this;
        }

        /**
         * Sets the amount of drain attributed to the specified custom drain type.
         *
         * @param componentId    The ID of the custom power component.
         * @param componentPower Amount of consumed power in mAh.
         */
        @SuppressWarnings("unchecked")
        @NonNull
        public T setConsumedPowerForCustomComponent(int componentId, double componentPower) {
            mPowerComponentsBuilder.setConsumedPowerForCustomComponent(componentId, componentPower);
            return (T) this;
        }

        /**
         * Sets the amount of time used by the specified component, e.g. CPU, WiFi etc.
         *
         * @param componentId              The ID of the time component, e.g.
         *                                 {@link UidBatteryConsumer#TIME_COMPONENT_CPU}.
         * @param componentUsageTimeMillis Amount of time in microseconds.
         */
        @SuppressWarnings("unchecked")
        @NonNull
        public T setUsageDurationMillis(@UidBatteryConsumer.TimeComponent int componentId,
                long componentUsageTimeMillis) {
            mPowerComponentsBuilder.setUsageDurationMillis(componentId, componentUsageTimeMillis);
            return (T) this;
        }

        /**
         * Sets the amount of time used by the specified custom component.
         *
         * @param componentId              The ID of the custom power component.
         * @param componentUsageTimeMillis Amount of time in microseconds.
         */
        @SuppressWarnings("unchecked")
        @NonNull
        public T setUsageDurationForCustomComponentMillis(int componentId,
                long componentUsageTimeMillis) {
            mPowerComponentsBuilder.setUsageDurationForCustomComponentMillis(componentId,
                    componentUsageTimeMillis);
            return (T) this;
        }
    }
}
