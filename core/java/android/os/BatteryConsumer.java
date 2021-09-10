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
import android.annotation.Nullable;
import android.database.CursorWindow;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for objects containing battery attribution data.
 *
 * @hide
 */
public abstract class BatteryConsumer {

    private static final String TAG = "BatteryConsumer";

    /**
     * Power usage component, describing the particular part of the system
     * responsible for power drain.
     *
     * @hide
     */
    @IntDef(prefix = {"POWER_COMPONENT_"}, value = {
            POWER_COMPONENT_SCREEN,
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
            POWER_COMPONENT_WIFI,
            POWER_COMPONENT_WAKELOCK,
            POWER_COMPONENT_MEMORY,
            POWER_COMPONENT_PHONE,
            POWER_COMPONENT_IDLE,
            POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface PowerComponent {
    }

    public static final int POWER_COMPONENT_SCREEN = OsProtoEnums.POWER_COMPONENT_SCREEN; // 0
    public static final int POWER_COMPONENT_CPU = OsProtoEnums.POWER_COMPONENT_CPU; // 1
    public static final int POWER_COMPONENT_BLUETOOTH = OsProtoEnums.POWER_COMPONENT_BLUETOOTH; // 2
    public static final int POWER_COMPONENT_CAMERA = OsProtoEnums.POWER_COMPONENT_CAMERA; // 3
    public static final int POWER_COMPONENT_AUDIO = OsProtoEnums.POWER_COMPONENT_AUDIO; // 4
    public static final int POWER_COMPONENT_VIDEO = OsProtoEnums.POWER_COMPONENT_VIDEO; // 5
    public static final int POWER_COMPONENT_FLASHLIGHT =
            OsProtoEnums.POWER_COMPONENT_FLASHLIGHT; // 6
    public static final int POWER_COMPONENT_SYSTEM_SERVICES =
            OsProtoEnums.POWER_COMPONENT_SYSTEM_SERVICES; // 7
    public static final int POWER_COMPONENT_MOBILE_RADIO =
            OsProtoEnums.POWER_COMPONENT_MOBILE_RADIO; // 8
    public static final int POWER_COMPONENT_SENSORS = OsProtoEnums.POWER_COMPONENT_SENSORS; // 9
    public static final int POWER_COMPONENT_GNSS = OsProtoEnums.POWER_COMPONENT_GNSS; // 10
    public static final int POWER_COMPONENT_WIFI = OsProtoEnums.POWER_COMPONENT_WIFI; // 11
    public static final int POWER_COMPONENT_WAKELOCK = OsProtoEnums.POWER_COMPONENT_WAKELOCK; // 12
    public static final int POWER_COMPONENT_MEMORY = OsProtoEnums.POWER_COMPONENT_MEMORY; // 13
    public static final int POWER_COMPONENT_PHONE = OsProtoEnums.POWER_COMPONENT_PHONE; // 14
    public static final int POWER_COMPONENT_AMBIENT_DISPLAY =
            OsProtoEnums.POWER_COMPONENT_AMBIENT_DISPLAY; // 15
    public static final int POWER_COMPONENT_IDLE = OsProtoEnums.POWER_COMPONENT_IDLE; // 16
    // Power that is re-attributed to other battery consumers. For example, for System Server
    // this represents the power attributed to apps requesting system services.
    // The value should be negative or zero.
    public static final int POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS =
            OsProtoEnums.POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS; // 17

    public static final int POWER_COMPONENT_COUNT = 18;

    public static final int FIRST_CUSTOM_POWER_COMPONENT_ID = 1000;
    public static final int LAST_CUSTOM_POWER_COMPONENT_ID = 9999;

    private static final String[] sPowerComponentNames = new String[POWER_COMPONENT_COUNT];

    static {
        // Assign individually to avoid future mismatch
        sPowerComponentNames[POWER_COMPONENT_SCREEN] = "screen";
        sPowerComponentNames[POWER_COMPONENT_CPU] = "cpu";
        sPowerComponentNames[POWER_COMPONENT_BLUETOOTH] = "bluetooth";
        sPowerComponentNames[POWER_COMPONENT_CAMERA] = "camera";
        sPowerComponentNames[POWER_COMPONENT_AUDIO] = "audio";
        sPowerComponentNames[POWER_COMPONENT_VIDEO] = "video";
        sPowerComponentNames[POWER_COMPONENT_FLASHLIGHT] = "flashlight";
        sPowerComponentNames[POWER_COMPONENT_SYSTEM_SERVICES] = "system_services";
        sPowerComponentNames[POWER_COMPONENT_MOBILE_RADIO] = "mobile_radio";
        sPowerComponentNames[POWER_COMPONENT_SENSORS] = "sensors";
        sPowerComponentNames[POWER_COMPONENT_GNSS] = "gnss";
        sPowerComponentNames[POWER_COMPONENT_WIFI] = "wifi";
        sPowerComponentNames[POWER_COMPONENT_WAKELOCK] = "wakelock";
        sPowerComponentNames[POWER_COMPONENT_MEMORY] = "memory";
        sPowerComponentNames[POWER_COMPONENT_PHONE] = "phone";
        sPowerComponentNames[POWER_COMPONENT_AMBIENT_DISPLAY] = "ambient_display";
        sPowerComponentNames[POWER_COMPONENT_IDLE] = "idle";
        sPowerComponentNames[POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS] = "reattributed";
    }

    /**
     * Identifiers of models used for power estimation.
     *
     * @hide
     */
    @IntDef(prefix = {"POWER_MODEL_"}, value = {
            POWER_MODEL_UNDEFINED,
            POWER_MODEL_POWER_PROFILE,
            POWER_MODEL_MEASURED_ENERGY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PowerModel {
    }

    /**
     * Unspecified power model.
     */
    public static final int POWER_MODEL_UNDEFINED = 0;

    /**
     * Power model that is based on average consumption rates that hardware components
     * consume in various states.
     */
    public static final int POWER_MODEL_POWER_PROFILE = 1;

    /**
     * Power model that is based on energy consumption measured by on-device power monitors.
     */
    public static final int POWER_MODEL_MEASURED_ENERGY = 2;

    static final int COLUMN_INDEX_BATTERY_CONSUMER_TYPE = 0;
    static final int COLUMN_COUNT = 1;

    protected final BatteryConsumerData mData;
    protected final PowerComponents mPowerComponents;

    protected BatteryConsumer(BatteryConsumerData data, @NonNull PowerComponents powerComponents) {
        mData = data;
        mPowerComponents = powerComponents;
    }

    public BatteryConsumer(BatteryConsumerData data) {
        mData = data;
        mPowerComponents = new PowerComponents(data);
    }

    /**
     * Total power consumed by this consumer, in mAh.
     */
    public double getConsumedPower() {
        return mPowerComponents.getConsumedPower();
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
     * Returns the ID of the model that was used for power estimation.
     *
     * @param componentId The ID of the power component, e.g.
     *                    {@link BatteryConsumer#POWER_COMPONENT_CPU}.
     */
    public @PowerModel int getPowerModel(@BatteryConsumer.PowerComponent int componentId) {
        return mPowerComponents.getPowerModel(componentId);
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

    public int getCustomPowerComponentCount() {
        return mData.layout.customPowerComponentCount;
    }

    /**
     * Returns the name of the specified power component.
     *
     * @param componentId The ID of the custom power component.
     */
    public String getCustomPowerComponentName(int componentId) {
        return mPowerComponents.getCustomPowerComponentName(componentId);
    }

    /**
     * Returns the amount of time since BatteryStats reset used by the specified component, e.g.
     * CPU, WiFi etc.
     *
     * @param componentId The ID of the power component, e.g.
     *                    {@link UidBatteryConsumer#POWER_COMPONENT_CPU}.
     * @return Amount of time in milliseconds.
     */
    public long getUsageDurationMillis(@PowerComponent int componentId) {
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

    /**
     * Returns the name of the specified component.  Intended for logging and debugging.
     */
    public static String powerComponentIdToString(@BatteryConsumer.PowerComponent int componentId) {
        return sPowerComponentNames[componentId];
    }

    /**
     * Returns the name of the specified power model.  Intended for logging and debugging.
     */
    public static String powerModelToString(@BatteryConsumer.PowerModel int powerModel) {
        switch (powerModel) {
            case BatteryConsumer.POWER_MODEL_MEASURED_ENERGY:
                return "measured energy";
            case BatteryConsumer.POWER_MODEL_POWER_PROFILE:
                return "power profile";
            default:
                return "";
        }
    }

    /**
     * Prints the stats in a human-readable format.
     */
    public void dump(PrintWriter pw) {
        dump(pw, true);
    }

    /**
     * Prints the stats in a human-readable format.
     *
     * @param skipEmptyComponents if true, omit any power components with a zero amount.
     */
    public abstract void dump(PrintWriter pw, boolean skipEmptyComponents);

    /** Returns whether there are any atoms.proto BATTERY_CONSUMER_DATA data to write to a proto. */
    boolean hasStatsProtoData() {
        return writeStatsProtoImpl(null, /* Irrelevant fieldId: */ 0);
    }

    /** Writes the atoms.proto BATTERY_CONSUMER_DATA for this BatteryConsumer to the given proto. */
    void writeStatsProto(@NonNull ProtoOutputStream proto, long fieldId) {
        writeStatsProtoImpl(proto, fieldId);
    }

    /**
     * Returns whether there are any atoms.proto BATTERY_CONSUMER_DATA data to write to a proto,
     * and writes it to the given proto if it is non-null.
     */
    private boolean writeStatsProtoImpl(@Nullable ProtoOutputStream proto, long fieldId) {
        final long totalConsumedPowerDeciCoulombs = convertMahToDeciCoulombs(getConsumedPower());

        if (totalConsumedPowerDeciCoulombs == 0) {
            // NOTE: Strictly speaking we should also check !mPowerComponents.hasStatsProtoData().
            // However, that call is a bit expensive (a for loop). And the only way that
            // totalConsumedPower can be 0 while mPowerComponents.hasStatsProtoData() is true is
            // if POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS (which is the only negative
            // allowed) happens to exactly equal the sum of all other components, which
            // can't really happen in practice.
            // So we'll just adopt the rule "if total==0, don't write any details".
            // If negative values are used for other things in the future, this can be revisited.
            return false;
        }
        if (proto == null) {
            // We're just asked whether there is data, not to actually write it. And there is.
            return true;
        }

        final long token = proto.start(fieldId);
        proto.write(
                BatteryUsageStatsAtomsProto.BatteryConsumerData.TOTAL_CONSUMED_POWER_DECI_COULOMBS,
                totalConsumedPowerDeciCoulombs);
        mPowerComponents.writeStatsProto(proto);
        proto.end(token);

        return true;
    }

    /** Converts charge from milliamp hours (mAh) to decicoulombs (dC). */
    static long convertMahToDeciCoulombs(double powerMah) {
        return (long) (powerMah * (10 * 3600 / 1000) + 0.5);
    }

    static class BatteryConsumerData {
        private final CursorWindow mCursorWindow;
        private final int mCursorRow;
        public final BatteryConsumerDataLayout layout;

        BatteryConsumerData(CursorWindow cursorWindow, int cursorRow,
                BatteryConsumerDataLayout layout) {
            mCursorWindow = cursorWindow;
            mCursorRow = cursorRow;
            this.layout = layout;
        }

        @Nullable
        static BatteryConsumerData create(CursorWindow cursorWindow,
                BatteryConsumerDataLayout layout) {
            int cursorRow = cursorWindow.getNumRows();
            if (!cursorWindow.allocRow()) {
                Slog.e(TAG, "Cannot allocate BatteryConsumerData: too many UIDs: " + cursorRow);
                cursorRow = -1;
            }
            return new BatteryConsumerData(cursorWindow, cursorRow, layout);
        }

        void putInt(int columnIndex, int value) {
            if (mCursorRow == -1) {
                return;
            }
            mCursorWindow.putLong(value, mCursorRow, columnIndex);
        }

        int getInt(int columnIndex) {
            if (mCursorRow == -1) {
                return 0;
            }
            return mCursorWindow.getInt(mCursorRow, columnIndex);
        }

        void putDouble(int columnIndex, double value) {
            if (mCursorRow == -1) {
                return;
            }
            mCursorWindow.putDouble(value, mCursorRow, columnIndex);
        }

        double getDouble(int columnIndex) {
            if (mCursorRow == -1) {
                return 0;
            }
            return mCursorWindow.getDouble(mCursorRow, columnIndex);
        }

        void putLong(int columnIndex, long value) {
            if (mCursorRow == -1) {
                return;
            }
            mCursorWindow.putLong(value, mCursorRow, columnIndex);
        }

        long getLong(int columnIndex) {
            if (mCursorRow == -1) {
                return 0;
            }
            return mCursorWindow.getLong(mCursorRow, columnIndex);
        }

        void putString(int columnIndex, String value) {
            if (mCursorRow == -1) {
                return;
            }
            mCursorWindow.putString(value, mCursorRow, columnIndex);
        }

        String getString(int columnIndex) {
            if (mCursorRow == -1) {
                return null;
            }
            return mCursorWindow.getString(mCursorRow, columnIndex);
        }
    }

    static class BatteryConsumerDataLayout {
        public final String[] customPowerComponentNames;
        public final int customPowerComponentCount;
        public final boolean powerModelsIncluded;

        public final int consumedPowerColumn;
        public final int firstConsumedPowerColumn;
        public final int firstCustomConsumedPowerColumn;
        public final int firstUsageDurationColumn;
        public final int firstCustomUsageDurationColumn;
        public final int firstPowerModelColumn;
        public final int columnCount;

        private BatteryConsumerDataLayout(int firstColumn, String[] customPowerComponentNames,
                boolean powerModelsIncluded) {
            this.customPowerComponentNames = customPowerComponentNames;
            this.customPowerComponentCount = customPowerComponentNames.length;
            this.powerModelsIncluded = powerModelsIncluded;

            int columnIndex = firstColumn;
            consumedPowerColumn = columnIndex++;

            firstConsumedPowerColumn = columnIndex;
            columnIndex += BatteryConsumer.POWER_COMPONENT_COUNT;

            firstCustomConsumedPowerColumn = columnIndex;
            columnIndex += customPowerComponentCount;

            firstUsageDurationColumn = columnIndex;
            columnIndex += BatteryConsumer.POWER_COMPONENT_COUNT;

            firstCustomUsageDurationColumn = columnIndex;
            columnIndex += customPowerComponentCount;

            if (powerModelsIncluded) {
                firstPowerModelColumn = columnIndex;
                columnIndex += BatteryConsumer.POWER_COMPONENT_COUNT;
            } else {
                firstPowerModelColumn = -1;
            }

            columnCount = columnIndex;
        }
    }

    static BatteryConsumerDataLayout createBatteryConsumerDataLayout(
            String[] customPowerComponentNames, boolean includePowerModels) {
        int columnCount = BatteryConsumer.COLUMN_COUNT;
        columnCount = Math.max(columnCount, AggregateBatteryConsumer.COLUMN_COUNT);
        columnCount = Math.max(columnCount, UidBatteryConsumer.COLUMN_COUNT);
        columnCount = Math.max(columnCount, UserBatteryConsumer.COLUMN_COUNT);

        return new BatteryConsumerDataLayout(columnCount, customPowerComponentNames,
                includePowerModels);
    }

    protected abstract static class BaseBuilder<T extends BaseBuilder<?>> {
        protected final BatteryConsumer.BatteryConsumerData mData;
        protected final PowerComponents.Builder mPowerComponentsBuilder;

        public BaseBuilder(BatteryConsumer.BatteryConsumerData data, int consumerType) {
            mData = data;
            data.putLong(COLUMN_INDEX_BATTERY_CONSUMER_TYPE, consumerType);

            mPowerComponentsBuilder = new PowerComponents.Builder(data);
        }

        /**
         * Sets the amount of drain attributed to the specified drain type, e.g. CPU, WiFi etc.
         *
         * @param componentId    The ID of the power component, e.g.
         *                       {@link BatteryConsumer#POWER_COMPONENT_CPU}.
         * @param componentPower Amount of consumed power in mAh.
         */
        @NonNull
        public T setConsumedPower(@PowerComponent int componentId, double componentPower) {
            return setConsumedPower(componentId, componentPower, POWER_MODEL_POWER_PROFILE);
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
        public T setConsumedPower(@PowerComponent int componentId, double componentPower,
                @PowerModel int powerModel) {
            mPowerComponentsBuilder.setConsumedPower(componentId, componentPower, powerModel);
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
         * @param componentId              The ID of the power component, e.g.
         *                                 {@link UidBatteryConsumer#POWER_COMPONENT_CPU}.
         * @param componentUsageTimeMillis Amount of time in microseconds.
         */
        @SuppressWarnings("unchecked")
        @NonNull
        public T setUsageDurationMillis(@UidBatteryConsumer.PowerComponent int componentId,
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

        /**
         * Returns the total power accumulated by this builder so far. It may change
         * by the time the {@code build()} method is called.
         */
        public double getTotalPower() {
            return mPowerComponentsBuilder.getTotalPower();
        }
    }
}
