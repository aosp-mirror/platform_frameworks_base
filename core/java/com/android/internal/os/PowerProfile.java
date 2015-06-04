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


import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;

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

    /**
     * No power consumption, or accounted for elsewhere.
     */
    public static final String POWER_NONE = "none";

    /**
     * Power consumption when CPU is in power collapse mode.
     */
    public static final String POWER_CPU_IDLE = "cpu.idle";

    /**
     * Power consumption when CPU is awake (when a wake lock is held).  This
     * should be 0 on devices that can go into full CPU power collapse even
     * when a wake lock is held.  Otherwise, this is the power consumption in
     * addition to POWERR_CPU_IDLE due to a wake lock being held but with no
     * CPU activity.
     */
    public static final String POWER_CPU_AWAKE = "cpu.awake";

    /**
     * Power consumption when CPU is in power collapse mode.
     */
    public static final String POWER_CPU_ACTIVE = "cpu.active";

    /**
     * Power consumption when WiFi driver is scanning for networks.
     */
    public static final String POWER_WIFI_SCAN = "wifi.scan";

    /**
     * Power consumption when WiFi driver is on.
     */
    public static final String POWER_WIFI_ON = "wifi.on";

    /**
     * Power consumption when WiFi driver is transmitting/receiving.
     */
    public static final String POWER_WIFI_ACTIVE = "wifi.active";

    //
    // Updated power constants. These are not estimated, they are real world
    // currents and voltages for the underlying bluetooth and wifi controllers.
    //

    public static final String POWER_WIFI_CONTROLLER_IDLE = "wifi.controller.idle";
    public static final String POWER_WIFI_CONTROLLER_RX = "wifi.controller.rx";
    public static final String POWER_WIFI_CONTROLLER_TX = "wifi.controller.tx";
    public static final String POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE = "wifi.controller.voltage";

    public static final String POWER_BLUETOOTH_CONTROLLER_IDLE = "bluetooth.controller.idle";
    public static final String POWER_BLUETOOTH_CONTROLLER_RX = "bluetooth.controller.rx";
    public static final String POWER_BLUETOOTH_CONTROLLER_TX = "bluetooth.controller.tx";
    public static final String POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE =
            "bluetooth.controller.voltage";

    /**
     * Power consumption when GPS is on.
     */
    public static final String POWER_GPS_ON = "gps.on";

    /**
     * Power consumption when Bluetooth driver is on.
     */
    public static final String POWER_BLUETOOTH_ON = "bluetooth.on";

    /**
     * Power consumption when Bluetooth driver is transmitting/receiving.
     */
    public static final String POWER_BLUETOOTH_ACTIVE = "bluetooth.active";

    /**
     * Power consumption when Bluetooth driver gets an AT command.
     */
    public static final String POWER_BLUETOOTH_AT_CMD = "bluetooth.at";


    /**
     * Power consumption when screen is on, not including the backlight power.
     */
    public static final String POWER_SCREEN_ON = "screen.on";

    /**
     * Power consumption when cell radio is on but not on a call.
     */
    public static final String POWER_RADIO_ON = "radio.on";

    /**
     * Power consumption when cell radio is hunting for a signal.
     */
    public static final String POWER_RADIO_SCANNING = "radio.scanning";

    /**
     * Power consumption when talking on the phone.
     */
    public static final String POWER_RADIO_ACTIVE = "radio.active";

    /**
     * Power consumption at full backlight brightness. If the backlight is at
     * 50% brightness, then this should be multiplied by 0.5
     */
    public static final String POWER_SCREEN_FULL = "screen.full";

    /**
     * Power consumed by the audio hardware when playing back audio content. This is in addition
     * to the CPU power, probably due to a DSP and / or amplifier.
     */
    public static final String POWER_AUDIO = "dsp.audio";

    /**
     * Power consumed by any media hardware when playing back video content. This is in addition
     * to the CPU power, probably due to a DSP.
     */
    public static final String POWER_VIDEO = "dsp.video";

    /**
     * Average power consumption when camera flashlight is on.
     */
    public static final String POWER_FLASHLIGHT = "camera.flashlight";

    /**
     * Average power consumption when the camera is on over all standard use cases.
     *
     * TODO: Add more fine-grained camera power metrics.
     */
    public static final String POWER_CAMERA = "camera.avg";

    public static final String POWER_CPU_SPEEDS = "cpu.speeds";

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

    static final HashMap<String, Object> sPowerMap = new HashMap<>();

    private static final String TAG_DEVICE = "device";
    private static final String TAG_ITEM = "item";
    private static final String TAG_ARRAY = "array";
    private static final String TAG_ARRAYITEM = "value";
    private static final String ATTR_NAME = "name";

    public PowerProfile(Context context) {
        // Read the XML file for the given profile (normally only one per
        // device)
        if (sPowerMap.size() == 0) {
            readPowerValuesFromXml(context);
        }
    }

    private void readPowerValuesFromXml(Context context) {
        int id = com.android.internal.R.xml.power_profile;
        final Resources resources = context.getResources();
        XmlResourceParser parser = resources.getXml(id);
        boolean parsingArray = false;
        ArrayList<Double> array = new ArrayList<Double>();
        String arrayName = null;

        try {
            XmlUtils.beginDocument(parser, TAG_DEVICE);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (parsingArray && !element.equals(TAG_ARRAYITEM)) {
                    // Finish array
                    sPowerMap.put(arrayName, array.toArray(new Double[array.size()]));
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
                            sPowerMap.put(name, value);
                        } else if (parsingArray) {
                            array.add(value);
                        }
                    }
                }
            }
            if (parsingArray) {
                sPowerMap.put(arrayName, array.toArray(new Double[array.size()]));
            }
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            parser.close();
        }

        // Now collect other config variables.
        int[] configResIds = new int[] {
                com.android.internal.R.integer.config_bluetooth_idle_cur_ma,
                com.android.internal.R.integer.config_bluetooth_rx_cur_ma,
                com.android.internal.R.integer.config_bluetooth_tx_cur_ma,
                com.android.internal.R.integer.config_bluetooth_operating_voltage_mv,
                com.android.internal.R.integer.config_wifi_idle_receive_cur_ma,
                com.android.internal.R.integer.config_wifi_active_rx_cur_ma,
                com.android.internal.R.integer.config_wifi_tx_cur_ma,
                com.android.internal.R.integer.config_wifi_operating_voltage_mv,
        };

        String[] configResIdKeys = new String[] {
                POWER_BLUETOOTH_CONTROLLER_IDLE,
                POWER_BLUETOOTH_CONTROLLER_RX,
                POWER_BLUETOOTH_CONTROLLER_TX,
                POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE,
                POWER_WIFI_CONTROLLER_IDLE,
                POWER_WIFI_CONTROLLER_RX,
                POWER_WIFI_CONTROLLER_TX,
                POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE,
        };

        for (int i = 0; i < configResIds.length; i++) {
            int value = resources.getInteger(configResIds[i]);
            if (value > 0) {
                sPowerMap.put(configResIdKeys[i], (double) value);
            }
        }
    }

    /**
     * Returns the average current in mA consumed by the subsystem, or the given
     * default value if the subsystem has no recorded value.
     * @param type the subsystem type
     * @param defaultValue the value to return if the subsystem has no recorded value.
     * @return the average current in milliAmps.
     */
    public double getAveragePowerOrDefault(String type, double defaultValue) {
        if (sPowerMap.containsKey(type)) {
            Object data = sPowerMap.get(type);
            if (data instanceof Double[]) {
                return ((Double[])data)[0];
            } else {
                return (Double) sPowerMap.get(type);
            }
        } else {
            return defaultValue;
        }
    }

    /**
     * Returns the average current in mA consumed by the subsystem
     * @param type the subsystem type
     * @return the average current in milliAmps.
     */
    public double getAveragePower(String type) {
        return getAveragePowerOrDefault(type, 0);
    }
    
    /**
     * Returns the average current in mA consumed by the subsystem for the given level.
     * @param type the subsystem type
     * @param level the level of power at which the subsystem is running. For instance, the
     *  signal strength of the cell network between 0 and 4 (if there are 4 bars max.)
     *  If there is no data for multiple levels, the level is ignored.
     * @return the average current in milliAmps.
     */
    public double getAveragePower(String type, int level) {
        if (sPowerMap.containsKey(type)) {
            Object data = sPowerMap.get(type);
            if (data instanceof Double[]) {
                final Double[] values = (Double[]) data;
                if (values.length > level && level >= 0) {
                    return values[level];
                } else if (level < 0 || values.length == 0) {
                    return 0;
                } else {
                    return values[values.length - 1];
                }
            } else {
                return (Double) data;
            }
        } else {
            return 0;
        }
    }

    /**
     * Returns the battery capacity, if available, in milli Amp Hours. If not available,
     * it returns zero.
     * @return the battery capacity in mAh
     */
    public double getBatteryCapacity() {
        return getAveragePower(POWER_BATTERY_CAPACITY);
    }

    /**
     * Returns the number of speeds that the CPU can be run at.
     * @return
     */
    public int getNumSpeedSteps() {
        Object value = sPowerMap.get(POWER_CPU_SPEEDS);
        if (value != null && value instanceof Double[]) {
            return ((Double[])value).length;
        }
        return 1; // Only one speed
    }
}
