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
import android.content.res.XmlResourceParser;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
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
     * Power consumption when CPU is running at normal speed.
     */
    public static final String POWER_CPU_NORMAL = "cpu.normal";

    /**
     * Power consumption when CPU is running at full speed.
     */
    public static final String POWER_CPU_FULL = "cpu.full";

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
     * Power consumption when screen is on, not including the backlight power.
     */
    public static final String POWER_SCREEN_ON = "screen.on";

    /**
     * Power consumption when cell radio is on but not on a call.
     */
    public static final String POWER_RADIO_ON = "radio.on";

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

    static final HashMap<String, Double> sPowerMap = new HashMap<String, Double>();

    private static final String TAG_DEVICE = "device";
    private static final String TAG_ITEM = "item";
    private static final String ATTR_NAME = "name";

    public PowerProfile(Context context, CharSequence profile) {
        // Read the XML file for the given profile (normally only one per
        // device)
        if (sPowerMap.size() == 0) {
            readPowerValuesFromXml(context, profile);
        }
    }

    private void readPowerValuesFromXml(Context context, CharSequence profile) {
        // FIXME
        //int id = context.getResources().getIdentifier(profile.toString(), "xml", 
        //        "com.android.internal");
        int id = com.android.internal.R.xml.power_profile_default;
        XmlResourceParser parser = context.getResources().getXml(id);

        try {
            XmlUtils.beginDocument(parser, TAG_DEVICE);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName(); 
                if (element == null || !(element.equals(TAG_ITEM))) {
                    break;
                }

                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (parser.next() == XmlPullParser.TEXT) {
                    String power = parser.getText();
                    double value = 0;
                    try {
                        value = Double.valueOf(power);
                    } catch (NumberFormatException nfe) {
                    }
                    sPowerMap.put(name, value);
                }
            }
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            parser.close();
        }
    }

    /**
     * Returns the average current in mA consumed by the subsystem 
     * @param type the subsystem type
     * @return the average current in milliAmps.
     */
    public double getAveragePower(String type) {
        if (sPowerMap.containsKey(type)) {
            return sPowerMap.get(type);
        } else {
            return 0;
        }
    }
}
