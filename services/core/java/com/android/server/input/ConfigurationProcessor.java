/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.input;

import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;


class ConfigurationProcessor {
    private static final String TAG = "ConfigurationProcessor";

    static List<String> processExcludedDeviceNames(InputStream xml) throws Exception {
        List<String> names = new ArrayList<>();
        try (InputStreamReader confReader = new InputStreamReader(xml)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(confReader);
            XmlUtils.beginDocument(parser, "devices");
            while (true) {
                XmlUtils.nextElement(parser);
                if (!"device".equals(parser.getName())) {
                    break;
                }
                String name = parser.getAttributeValue(null, "name");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Parse the configuration for input port associations.
     *
     * Configuration format:
     * <code>
     * &lt;ports>
     *     &lt;port display="0" input="usb-xhci-hcd.0.auto-1.4.3/input0" />
     *     &lt;port display="1" input="usb-xhci-hcd.0.auto-1.4.2/input0" />
     * &lt;/ports>
     * </code>
     *
     * In this example, any input device that has physical port of
     * "usb-xhci-hcd.0.auto-1.4.3/input0" will be associated with a display
     * that has the physical port "0". If such a display does not exist, the input device
     * will be disabled and no input events will be dispatched from that input device until a
     * matching display appears. Likewise, an input device that has port "..1.4.2.." will have
     * its input events forwarded to a display that has physical port of "1".
     *
     * Note: display port must be a numeric value, and this is checked at runtime for validity.
     * At the same time, it is specified as a string for simplicity.
     *
     * Note: do not confuse "display id" with "display port".
     * The "display port" is the physical port on which the display is connected. This could
     * be something like HDMI0, HDMI1, etc. For virtual displays, "display port" will be null.
     * The "display id" is a way to identify a particular display, and is not a stable API.
     * All displays, including virtual ones, will have a display id.
     *
     * Return the pairs of associations. The first item in the pair is the input port,
     * the second item in the pair is the display port.
     */
    @VisibleForTesting
    static List<Pair<String, String>> processInputPortAssociations(InputStream xml)
            throws Exception {
        List<Pair<String, String>> associations = new ArrayList<>();
        try (InputStreamReader confReader = new InputStreamReader(xml)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(confReader);
            XmlUtils.beginDocument(parser, "ports");

            while (true) {
                XmlUtils.nextElement(parser);
                String entryName = parser.getName();
                if (!"port".equals(entryName)) {
                    break;
                }
                String inputPort = parser.getAttributeValue(null, "input");
                String displayPort = parser.getAttributeValue(null, "display");
                if (TextUtils.isEmpty(inputPort) || TextUtils.isEmpty(displayPort)) {
                    // This is likely an error by an OEM during device configuration
                    Slog.wtf(TAG, "Ignoring incomplete entry");
                    continue;
                }
                try {
                    Integer.parseUnsignedInt(displayPort);
                } catch (NumberFormatException e) {
                    Slog.wtf(TAG, "Display port should be an integer");
                    continue;
                }
                associations.add(new Pair<>(inputPort, displayPort));
            }
        }
        return associations;
    }
}
