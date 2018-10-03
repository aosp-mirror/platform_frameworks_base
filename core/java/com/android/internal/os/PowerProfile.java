/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.os;


import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Reports power consumption values for various device activities. Reads values from an XML file.
 * Customize the XML file for different devices.
 * [hidden]
 */
public class PowerProfile {

    /*
     * POWER_CPU_SUSPEND: Power consumption when CPU is in power collapse mode.
     * POWER_CPU_IDLE: Power consumption when CPU is awake (when a wake lock is held). This should
     *                 be zero on devices that can go into full CPU power collapse even when a wake
     *                 lock is held. Otherwise, this is the power consumption in addition to
     * POWER_CPU_SUSPEND due to a wake lock being held but with no CPU activity.
     * POWER_CPU_ACTIVE: Power consumption when CPU is running, excluding power consumed by clusters
     *                   and cores.
     *
     * CPU Power Equation (assume two clusters):
     * Total power = POWER_CPU_SUSPEND  (always added)
     *               + POWER_CPU_IDLE   (skip this and below if in power collapse mode)
     *               + POWER_CPU_ACTIVE (skip this and below if CPU is not running, but a wakelock
     *                                   is held)
     *               + cluster_power.cluster0 + cluster_power.cluster1 (skip cluster not running)
     *               + core_power.cluster0 * num running cores in cluster 0
     *               + core_power.cluster1 * num running cores in cluster 1
     */
    public static final String POWER_CPU_SUSPEND = "cpu.suspend";
    @UnsupportedAppUsage
    public static final String POWER_CPU_IDLE = "cpu.idle";
    @UnsupportedAppUsage
    public static final String POWER_CPU_ACTIVE = "cpu.active";

    /**
     * Power consumption when WiFi driver is scanning for networks.
     */
    @UnsupportedAppUsage
    public static final String POWER_WIFI_SCAN = "wifi.scan";

    /**
     * Power consumption when WiFi driver is on.
     */
    @UnsupportedAppUsage
    public static final String POWER_WIFI_ON = "wifi.on";

    /**
     * Power consumption when WiFi driver is transmitting/receiving.
     */
    @UnsupportedAppUsage
    public static final String POWER_WIFI_ACTIVE = "wifi.active";

    //
    // Updated power constants. These are not estimated, they are real world
    // currents and voltages for the underlying bluetooth and wifi controllers.
    //
    public static final String POWER_WIFI_CONTROLLER_IDLE = "wifi.controller.idle";
    public static final String POWER_WIFI_CONTROLLER_RX = "wifi.controller.rx";
    public static final String POWER_WIFI_CONTROLLER_TX = "wifi.controller.tx";
    public static final String POWER_WIFI_CONTROLLER_TX_LEVELS = "wifi.controller.tx_levels";
    public static final String POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE = "wifi.controller.voltage";

    public static final String POWER_BLUETOOTH_CONTROLLER_IDLE = "bluetooth.controller.idle";
    public static final String POWER_BLUETOOTH_CONTROLLER_RX = "bluetooth.controller.rx";
    public static final String POWER_BLUETOOTH_CONTROLLER_TX = "bluetooth.controller.tx";
    public static final String POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE =
            "bluetooth.controller.voltage";

    public static final String POWER_MODEM_CONTROLLER_SLEEP = "modem.controller.sleep";
    public static final String POWER_MODEM_CONTROLLER_IDLE = "modem.controller.idle";
    public static final String POWER_MODEM_CONTROLLER_RX = "modem.controller.rx";
    public static final String POWER_MODEM_CONTROLLER_TX = "modem.controller.tx";
    public static final String POWER_MODEM_CONTROLLER_OPERATING_VOLTAGE =
            "modem.controller.voltage";

    /**
     * Power consumption when GPS is on.
     */
    @UnsupportedAppUsage
    public static final String POWER_GPS_ON = "gps.on";

    /**
     * GPS power parameters based on signal quality
     */
    public static final String POWER_GPS_SIGNAL_QUALITY_BASED = "gps.signalqualitybased";
    public static final String POWER_GPS_OPERATING_VOLTAGE = "gps.voltage";

    /**
     * Power consumption when Bluetooth driver is on.
     *
     * @deprecated
     */
    @Deprecated
    @UnsupportedAppUsage
    public static final String POWER_BLUETOOTH_ON = "bluetooth.on";

    /**
     * Power consumption when Bluetooth driver is transmitting/receiving.
     *
     * @deprecated
     */
    @Deprecated
    public static final String POWER_BLUETOOTH_ACTIVE = "bluetooth.active";

    /**
     * Power consumption when Bluetooth driver gets an AT command.
     *
     * @deprecated
     */
    @Deprecated
    @UnsupportedAppUsage
    public static final String POWER_BLUETOOTH_AT_CMD = "bluetooth.at";

    /**
     * Power consumption when screen is in doze/ambient/always-on mode, including backlight power.
     */
    public static final String POWER_AMBIENT_DISPLAY = "ambient.on";

    /**
     * Power consumption when screen is on, not including the backlight power.
     */
    @UnsupportedAppUsage
    public static final String POWER_SCREEN_ON = "screen.on";

    /**
     * Power consumption when cell radio is on but not on a call.
     */
    @UnsupportedAppUsage
    public static final String POWER_RADIO_ON = "radio.on";

    /**
     * Power consumption when cell radio is hunting for a signal.
     */
    @UnsupportedAppUsage
    public static final String POWER_RADIO_SCANNING = "radio.scanning";

    /**
     * Power consumption when talking on the phone.
     */
    @UnsupportedAppUsage
    public static final String POWER_RADIO_ACTIVE = "radio.active";

    /**
     * Power consumption at full backlight brightness. If the backlight is at
     * 50% brightness, then this should be multiplied by 0.5
     */
    @UnsupportedAppUsage
    public static final String POWER_SCREEN_FULL = "screen.full";

    /**
     * Power consumed by the audio hardware when playing back audio content. This is in addition
     * to the CPU power, probably due to a DSP and / or amplifier.
     */
    public static final String POWER_AUDIO = "audio";

    /**
     * Power consumed by any media hardware when playing back video content. This is in addition
     * to the CPU power, probably due to a DSP.
     */
    public static final String POWER_VIDEO = "video";

    /**
     * Average power consumption when camera flashlight is on.
     */
    public static final String POWER_FLASHLIGHT = "camera.flashlight";

    /**
     * Power consumption when DDR is being used.
     */
    public static final String POWER_MEMORY = "memory.bandwidths";

    /**
     * Average power consumption when the camera is on over all standard use cases.
     *
     * TODO: Add more fine-grained camera power metrics.
     */
    public static final String POWER_CAMERA = "camera.avg";

    /**
     * Power consumed by wif batched scaning.  Broken down into bins by
     * Channels Scanned per Hour.  May do 1-720 scans per hour of 1-100 channels
     * for a range of 1-72,000.  Going logrithmic (1-8, 9-64, 65-512, 513-4096, 4097-)!
     */
    public static final String POWER_WIFI_BATCHED_SCAN = "wifi.batchedscan";

    /**
     * Battery capacity in milliAmpHour (mAh).
     */
    public static final String POWER_BATTERY_CAPACITY = "battery.capacity";

    /**
     * A map from Power Use Item to its power consumption.
     */
    static final HashMap<String, Double> sPowerItemMap = new HashMap<>();
    /**
     * A map from Power Use Item to an array of its power consumption
     * (for items with variable power e.g. CPU).
     */
    static final HashMap<String, Double[]> sPowerArrayMap = new HashMap<>();

    private static final String TAG_DEVICE = "device";
    private static final String TAG_ITEM = "item";
    private static final String TAG_ARRAY = "array";
    private static final String TAG_ARRAYITEM = "value";
    private static final String ATTR_NAME = "name";

    private static final Object sLock = new Object();

    @VisibleForTesting
    @UnsupportedAppUsage
    public PowerProfile(Context context) {
        this(context, false);
    }

    /**
     * For PowerProfileTest
     */
    @VisibleForTesting
    public PowerProfile(Context context, boolean forTest) {
        // Read the XML file for the given profile (normally only one per device)
        synchronized (sLock) {
            if (sPowerItemMap.size() == 0 && sPowerArrayMap.size() == 0) {
                readPowerValuesFromXml(context, forTest);
            }
            initCpuClusters();
        }
    }

    private void readPowerValuesFromXml(Context context, boolean forTest) {
        final int id = forTest ? com.android.internal.R.xml.power_profile_test :
                com.android.internal.R.xml.power_profile;
        final Resources resources = context.getResources();
        XmlResourceParser parser = resources.getXml(id);
        boolean parsingArray = false;
        ArrayList<Double> array = new ArrayList<>();
        String arrayName = null;

        try {
            XmlUtils.beginDocument(parser, TAG_DEVICE);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (parsingArray && !element.equals(TAG_ARRAYITEM)) {
                    // Finish array
                    sPowerArrayMap.put(arrayName, array.toArray(new Double[array.size()]));
                    parsingArray = false;
                }
                if (element.equals(TAG_ARRAY)) {
                    parsingArray = true;
                    array.clear();
                    arrayName = parser.getAttributeValue(null, ATTR_NAME);
                } else if (element.equals(TAG_ITEM) || element.equals(TAG_ARRAYITEM)) {
                    String name = null;
                    if (!parsingArray) name = parser.getAttributeValue(null, ATTR_NAME);
                    if (parser.next() == XmlPullParser.TEXT) {
                        String power = parser.getText();
                        double value = 0;
                        try {
                            value = Double.valueOf(power);
                        } catch (NumberFormatException nfe) {
                        }
                        if (element.equals(TAG_ITEM)) {
                            sPowerItemMap.put(name, value);
                        } else if (parsingArray) {
                            array.add(value);
                        }
                    }
                }
            }
            if (parsingArray) {
                sPowerArrayMap.put(arrayName, array.toArray(new Double[array.size()]));
            }
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            parser.close();
        }

        // Now collect other config variables.
        int[] configResIds = new int[]{
                com.android.internal.R.integer.config_bluetooth_idle_cur_ma,
                com.android.internal.R.integer.config_bluetooth_rx_cur_ma,
                com.android.internal.R.integer.config_bluetooth_tx_cur_ma,
                com.android.internal.R.integer.config_bluetooth_operating_voltage_mv,
        };

        String[] configResIdKeys = new String[]{
                POWER_BLUETOOTH_CONTROLLER_IDLE,
                POWER_BLUETOOTH_CONTROLLER_RX,
                POWER_BLUETOOTH_CONTROLLER_TX,
                POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE,
        };

        for (int i = 0; i < configResIds.length; i++) {
            String key = configResIdKeys[i];
            // if we already have some of these parameters in power_profile.xml, ignore the
            // value in config.xml
            if ((sPowerItemMap.containsKey(key) && sPowerItemMap.get(key) > 0)) {
                continue;
            }
            int value = resources.getInteger(configResIds[i]);
            if (value > 0) {
                sPowerItemMap.put(key, (double) value);
            }
        }
    }

    private CpuClusterKey[] mCpuClusters;

    private static final String CPU_PER_CLUSTER_CORE_COUNT = "cpu.clusters.cores";
    private static final String CPU_CLUSTER_POWER_COUNT = "cpu.cluster_power.cluster";
    private static final String CPU_CORE_SPEED_PREFIX = "cpu.core_speeds.cluster";
    private static final String CPU_CORE_POWER_PREFIX = "cpu.core_power.cluster";

    private void initCpuClusters() {
        if (sPowerArrayMap.containsKey(CPU_PER_CLUSTER_CORE_COUNT)) {
            final Double[] data = sPowerArrayMap.get(CPU_PER_CLUSTER_CORE_COUNT);
            mCpuClusters = new CpuClusterKey[data.length];
            for (int cluster = 0; cluster < data.length; cluster++) {
                int numCpusInCluster = (int) Math.round(data[cluster]);
                mCpuClusters[cluster] = new CpuClusterKey(
                        CPU_CORE_SPEED_PREFIX + cluster, CPU_CLUSTER_POWER_COUNT + cluster,
                        CPU_CORE_POWER_PREFIX + cluster, numCpusInCluster);
            }
        } else {
            // Default to single.
            mCpuClusters = new CpuClusterKey[1];
            int numCpus = 1;
            if (sPowerItemMap.containsKey(CPU_PER_CLUSTER_CORE_COUNT)) {
                numCpus = (int) Math.round(sPowerItemMap.get(CPU_PER_CLUSTER_CORE_COUNT));
            }
            mCpuClusters[0] = new CpuClusterKey(CPU_CORE_SPEED_PREFIX + 0,
                    CPU_CLUSTER_POWER_COUNT + 0, CPU_CORE_POWER_PREFIX + 0, numCpus);
        }
    }

    public static class CpuClusterKey {
        private final String freqKey;
        private final String clusterPowerKey;
        private final String corePowerKey;
        private final int numCpus;

        private CpuClusterKey(String freqKey, String clusterPowerKey,
                String corePowerKey, int numCpus) {
            this.freqKey = freqKey;
            this.clusterPowerKey = clusterPowerKey;
            this.corePowerKey = corePowerKey;
            this.numCpus = numCpus;
        }
    }

    @UnsupportedAppUsage
    public int getNumCpuClusters() {
        return mCpuClusters.length;
    }

    public int getNumCoresInCpuCluster(int cluster) {
        return mCpuClusters[cluster].numCpus;
    }

    @UnsupportedAppUsage
    public int getNumSpeedStepsInCpuCluster(int cluster) {
        if (cluster < 0 || cluster >= mCpuClusters.length) {
            return 0; // index out of bound
        }
        if (sPowerArrayMap.containsKey(mCpuClusters[cluster].freqKey)) {
            return sPowerArrayMap.get(mCpuClusters[cluster].freqKey).length;
        }
        return 1; // Only one speed
    }

    public double getAveragePowerForCpuCluster(int cluster) {
        if (cluster >= 0 && cluster < mCpuClusters.length) {
            return getAveragePower(mCpuClusters[cluster].clusterPowerKey);
        }
        return 0;
    }

    public double getAveragePowerForCpuCore(int cluster, int step) {
        if (cluster >= 0 && cluster < mCpuClusters.length) {
            return getAveragePower(mCpuClusters[cluster].corePowerKey, step);
        }
        return 0;
    }

    /**
     * Returns the number of memory bandwidth buckets defined in power_profile.xml, or a
     * default value if the subsystem has no recorded value.
     *
     * @return the number of memory bandwidth buckets.
     */
    public int getNumElements(String key) {
        if (sPowerItemMap.containsKey(key)) {
            return 1;
        } else if (sPowerArrayMap.containsKey(key)) {
            return sPowerArrayMap.get(key).length;
        }
        return 0;
    }

    /**
     * Returns the average current in mA consumed by the subsystem, or the given
     * default value if the subsystem has no recorded value.
     *
     * @param type         the subsystem type
     * @param defaultValue the value to return if the subsystem has no recorded value.
     * @return the average current in milliAmps.
     */
    public double getAveragePowerOrDefault(String type, double defaultValue) {
        if (sPowerItemMap.containsKey(type)) {
            return sPowerItemMap.get(type);
        } else if (sPowerArrayMap.containsKey(type)) {
            return sPowerArrayMap.get(type)[0];
        } else {
            return defaultValue;
        }
    }

    /**
     * Returns the average current in mA consumed by the subsystem
     *
     * @param type the subsystem type
     * @return the average current in milliAmps.
     */
    @UnsupportedAppUsage
    public double getAveragePower(String type) {
        return getAveragePowerOrDefault(type, 0);
    }

    /**
     * Returns the average current in mA consumed by the subsystem for the given level.
     *
     * @param type  the subsystem type
     * @param level the level of power at which the subsystem is running. For instance, the
     *              signal strength of the cell network between 0 and 4 (if there are 4 bars max.)
     *              If there is no data for multiple levels, the level is ignored.
     * @return the average current in milliAmps.
     */
    @UnsupportedAppUsage
    public double getAveragePower(String type, int level) {
        if (sPowerItemMap.containsKey(type)) {
            return sPowerItemMap.get(type);
        } else if (sPowerArrayMap.containsKey(type)) {
            final Double[] values = sPowerArrayMap.get(type);
            if (values.length > level && level >= 0) {
                return values[level];
            } else if (level < 0 || values.length == 0) {
                return 0;
            } else {
                return values[values.length - 1];
            }
        } else {
            return 0;
        }
    }

    /**
     * Returns the battery capacity, if available, in milli Amp Hours. If not available,
     * it returns zero.
     *
     * @return the battery capacity in mAh
     */
    @UnsupportedAppUsage
    public double getBatteryCapacity() {
        return getAveragePower(POWER_BATTERY_CAPACITY);
    }

    /**
     * Dump power constants into PowerProfileProto
     */
    public void writeToProto(ProtoOutputStream proto) {
        // cpu.suspend
        writePowerConstantToProto(proto, POWER_CPU_SUSPEND, PowerProfileProto.CPU_SUSPEND);

        // cpu.idle
        writePowerConstantToProto(proto, POWER_CPU_IDLE, PowerProfileProto.CPU_IDLE);

        // cpu.active
        writePowerConstantToProto(proto, POWER_CPU_ACTIVE, PowerProfileProto.CPU_ACTIVE);

        // cpu.clusters.cores
        // cpu.cluster_power.cluster
        // cpu.core_speeds.cluster
        // cpu.core_power.cluster
        for (int cluster = 0; cluster < mCpuClusters.length; cluster++) {
            final long token = proto.start(PowerProfileProto.CPU_CLUSTER);
            proto.write(PowerProfileProto.CpuCluster.ID, cluster);
            proto.write(PowerProfileProto.CpuCluster.CLUSTER_POWER,
                    sPowerItemMap.get(mCpuClusters[cluster].clusterPowerKey));
            proto.write(PowerProfileProto.CpuCluster.CORES, mCpuClusters[cluster].numCpus);
            for (Double speed : sPowerArrayMap.get(mCpuClusters[cluster].freqKey)) {
                proto.write(PowerProfileProto.CpuCluster.SPEED, speed);
            }
            for (Double corePower : sPowerArrayMap.get(mCpuClusters[cluster].corePowerKey)) {
                proto.write(PowerProfileProto.CpuCluster.CORE_POWER, corePower);
            }
            proto.end(token);
        }

        // wifi.scan
        writePowerConstantToProto(proto, POWER_WIFI_SCAN, PowerProfileProto.WIFI_SCAN);

        // wifi.on
        writePowerConstantToProto(proto, POWER_WIFI_ON, PowerProfileProto.WIFI_ON);

        // wifi.active
        writePowerConstantToProto(proto, POWER_WIFI_ACTIVE, PowerProfileProto.WIFI_ACTIVE);

        // wifi.controller.idle
        writePowerConstantToProto(proto, POWER_WIFI_CONTROLLER_IDLE,
                PowerProfileProto.WIFI_CONTROLLER_IDLE);

        // wifi.controller.rx
        writePowerConstantToProto(proto, POWER_WIFI_CONTROLLER_RX,
                PowerProfileProto.WIFI_CONTROLLER_RX);

        // wifi.controller.tx
        writePowerConstantToProto(proto, POWER_WIFI_CONTROLLER_TX,
                PowerProfileProto.WIFI_CONTROLLER_TX);

        // wifi.controller.tx_levels
        writePowerConstantArrayToProto(proto, POWER_WIFI_CONTROLLER_TX_LEVELS,
                PowerProfileProto.WIFI_CONTROLLER_TX_LEVELS);

        // wifi.controller.voltage
        writePowerConstantToProto(proto, POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE,
                PowerProfileProto.WIFI_CONTROLLER_OPERATING_VOLTAGE);

        // bluetooth.controller.idle
        writePowerConstantToProto(proto, POWER_BLUETOOTH_CONTROLLER_IDLE,
                PowerProfileProto.BLUETOOTH_CONTROLLER_IDLE);

        // bluetooth.controller.rx
        writePowerConstantToProto(proto, POWER_BLUETOOTH_CONTROLLER_RX,
                PowerProfileProto.BLUETOOTH_CONTROLLER_RX);

        // bluetooth.controller.tx
        writePowerConstantToProto(proto, POWER_BLUETOOTH_CONTROLLER_TX,
                PowerProfileProto.BLUETOOTH_CONTROLLER_TX);

        // bluetooth.controller.voltage
        writePowerConstantToProto(proto, POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE,
                PowerProfileProto.BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE);

        // modem.controller.sleep
        writePowerConstantToProto(proto, POWER_MODEM_CONTROLLER_SLEEP,
                PowerProfileProto.MODEM_CONTROLLER_SLEEP);

        // modem.controller.idle
        writePowerConstantToProto(proto, POWER_MODEM_CONTROLLER_IDLE,
                PowerProfileProto.MODEM_CONTROLLER_IDLE);

        // modem.controller.rx
        writePowerConstantToProto(proto, POWER_MODEM_CONTROLLER_RX,
                PowerProfileProto.MODEM_CONTROLLER_RX);

        // modem.controller.tx
        writePowerConstantArrayToProto(proto, POWER_MODEM_CONTROLLER_TX,
                PowerProfileProto.MODEM_CONTROLLER_TX);

        // modem.controller.voltage
        writePowerConstantToProto(proto, POWER_MODEM_CONTROLLER_OPERATING_VOLTAGE,
                PowerProfileProto.MODEM_CONTROLLER_OPERATING_VOLTAGE);

        // gps.on
        writePowerConstantToProto(proto, POWER_GPS_ON, PowerProfileProto.GPS_ON);

        // gps.signalqualitybased
        writePowerConstantArrayToProto(proto, POWER_GPS_SIGNAL_QUALITY_BASED,
                PowerProfileProto.GPS_SIGNAL_QUALITY_BASED);

        // gps.voltage
        writePowerConstantToProto(proto, POWER_GPS_OPERATING_VOLTAGE,
                PowerProfileProto.GPS_OPERATING_VOLTAGE);

        // bluetooth.on
        writePowerConstantToProto(proto, POWER_BLUETOOTH_ON, PowerProfileProto.BLUETOOTH_ON);

        // bluetooth.active
        writePowerConstantToProto(proto, POWER_BLUETOOTH_ACTIVE,
                PowerProfileProto.BLUETOOTH_ACTIVE);

        // bluetooth.at
        writePowerConstantToProto(proto, POWER_BLUETOOTH_AT_CMD,
                PowerProfileProto.BLUETOOTH_AT_CMD);

        // ambient.on
        writePowerConstantToProto(proto, POWER_AMBIENT_DISPLAY, PowerProfileProto.AMBIENT_DISPLAY);

        // screen.on
        writePowerConstantToProto(proto, POWER_SCREEN_ON, PowerProfileProto.SCREEN_ON);

        // radio.on
        writePowerConstantToProto(proto, POWER_RADIO_ON, PowerProfileProto.RADIO_ON);

        // radio.scanning
        writePowerConstantToProto(proto, POWER_RADIO_SCANNING, PowerProfileProto.RADIO_SCANNING);

        // radio.active
        writePowerConstantToProto(proto, POWER_RADIO_ACTIVE, PowerProfileProto.RADIO_ACTIVE);

        // screen.full
        writePowerConstantToProto(proto, POWER_SCREEN_FULL, PowerProfileProto.SCREEN_FULL);

        // audio
        writePowerConstantToProto(proto, POWER_AUDIO, PowerProfileProto.AUDIO);

        // video
        writePowerConstantToProto(proto, POWER_VIDEO, PowerProfileProto.VIDEO);

        // camera.flashlight
        writePowerConstantToProto(proto, POWER_FLASHLIGHT, PowerProfileProto.FLASHLIGHT);

        // memory.bandwidths
        writePowerConstantToProto(proto, POWER_MEMORY, PowerProfileProto.MEMORY);

        // camera.avg
        writePowerConstantToProto(proto, POWER_CAMERA, PowerProfileProto.CAMERA);

        // wifi.batchedscan
        writePowerConstantToProto(proto, POWER_WIFI_BATCHED_SCAN,
                PowerProfileProto.WIFI_BATCHED_SCAN);

        // battery.capacity
        writePowerConstantToProto(proto, POWER_BATTERY_CAPACITY,
                PowerProfileProto.BATTERY_CAPACITY);
    }

    // Writes items in sPowerItemMap to proto if exists.
    private void writePowerConstantToProto(ProtoOutputStream proto, String key, long fieldId) {
        if (sPowerItemMap.containsKey(key)) {
            proto.write(fieldId, sPowerItemMap.get(key));
        }
    }

    // Writes items in sPowerArrayMap to proto if exists.
    private void writePowerConstantArrayToProto(ProtoOutputStream proto, String key, long fieldId) {
        if (sPowerArrayMap.containsKey(key)) {
            for (Double d : sPowerArrayMap.get(key)) {
                proto.write(fieldId, d);
            }
        }
    }
}
