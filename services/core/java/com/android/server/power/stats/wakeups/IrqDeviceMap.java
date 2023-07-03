/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.power.stats.wakeups;

import android.annotation.XmlRes;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.LongSparseArray;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses irq_device_map.xml to store a mapping of devices that can send IRQs to the CPU to
 * subsystems that represent some logical work happening on the device that could need an IRQ.
 */
public class IrqDeviceMap {
    private static final String TAG_IRQ_DEVICE_MAP = "irq-device-map";
    private static final String TAG_DEVICE = "device";
    private static final String TAG_SUBSYSTEM = "subsystem";
    private static final String ATTR_NAME = "name";

    private static LongSparseArray<IrqDeviceMap> sInstanceMap = new LongSparseArray<>(1);

    private final ArrayMap<String, List<String>> mSubsystemsForDevice = new ArrayMap();

    private IrqDeviceMap(XmlResourceParser parser) {
        try {
            XmlUtils.beginDocument(parser, TAG_IRQ_DEVICE_MAP);

            int type;
            String currentDevice = null;
            final ArraySet<String> subsystems = new ArraySet<>();

            while ((type = parser.getEventType()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_DEVICE)) {
                    currentDevice = parser.getAttributeValue(null, ATTR_NAME);
                }
                if (currentDevice != null && type == XmlPullParser.END_TAG
                        && parser.getName().equals(TAG_DEVICE)) {
                    final int n = subsystems.size();
                    if (n > 0) {
                        mSubsystemsForDevice.put(currentDevice,
                                Collections.unmodifiableList(new ArrayList<>(subsystems)));
                    }
                    subsystems.clear();
                    currentDevice = null;
                }
                if (currentDevice != null && type == XmlPullParser.START_TAG
                        && parser.getName().equals(TAG_SUBSYSTEM)) {
                    parser.next();
                    if (parser.getEventType() == XmlPullParser.TEXT) {
                        subsystems.add(parser.getText());
                    }
                }
                parser.next();
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
     * Returns an instance of IrqDeviceMap initialzed with the given context and xml resource.
     * The xml resource should describe the mapping in a way similar to
     * core/res/res/xml/irq_device_map.xml.
     */
    public static IrqDeviceMap getInstance(Context context, @XmlRes int resId) {
        synchronized (IrqDeviceMap.class) {
            final int idx = sInstanceMap.indexOfKey(resId);
            if (idx >= 0) {
                return sInstanceMap.valueAt(idx);
            }
        }
        final XmlResourceParser parser = context.getResources().getXml(resId);
        final IrqDeviceMap irqDeviceMap = new IrqDeviceMap(parser);
        synchronized (IrqDeviceMap.class) {
            sInstanceMap.put(resId, irqDeviceMap);
        }
        return irqDeviceMap;
    }

    List<String> getSubsystemsForDevice(String device) {
        return mSubsystemsForDevice.get(device);
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Irq device map:");
        pw.increaseIndent();

        final LongSparseArray<IrqDeviceMap> instanceMap;
        synchronized (IrqDeviceMap.class) {
            instanceMap = sInstanceMap;
        }
        final int idx = instanceMap.indexOfValue(this);
        final String res = (idx >= 0) ? ("0x" + Long.toHexString(instanceMap.keyAt(idx))) : null;
        pw.println("Loaded from xml resource: " + res);

        pw.println("Map:");
        pw.increaseIndent();
        for (int i = 0; i < mSubsystemsForDevice.size(); i++) {
            pw.print(mSubsystemsForDevice.keyAt(i) + ": ");
            pw.println(mSubsystemsForDevice.valueAt(i));
        }
        pw.decreaseIndent();

        pw.decreaseIndent();
    }
}
