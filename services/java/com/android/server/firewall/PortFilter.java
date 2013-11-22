/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.firewall;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class PortFilter implements Filter {
    private static final String ATTR_EQUALS = "equals";
    private static final String ATTR_MIN = "min";
    private static final String ATTR_MAX = "max";

    private static final int NO_BOUND = -1;

    // both bounds are inclusive
    private final int mLowerBound;
    private final int mUpperBound;

    private PortFilter(int lowerBound, int upperBound) {
        mLowerBound = lowerBound;
        mUpperBound = upperBound;
    }

    @Override
    public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
            int callerUid, int callerPid, String resolvedType, int receivingUid) {
        int port = -1;
        Uri uri = intent.getData();
        if (uri != null) {
            port = uri.getPort();
        }
        return port != -1 &&
                (mLowerBound == NO_BOUND || mLowerBound <= port) &&
                (mUpperBound == NO_BOUND || mUpperBound >= port);
    }

    public static final FilterFactory FACTORY = new FilterFactory("port") {
        @Override
        public Filter newFilter(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            int lowerBound = NO_BOUND;
            int upperBound = NO_BOUND;

            String equalsValue = parser.getAttributeValue(null, ATTR_EQUALS);
            if (equalsValue != null) {
                int value;
                try {
                    value = Integer.parseInt(equalsValue);
                } catch (NumberFormatException ex) {
                    throw new XmlPullParserException("Invalid port value: " + equalsValue,
                            parser, null);
                }
                lowerBound = value;
                upperBound = value;
            }

            String lowerBoundString = parser.getAttributeValue(null, ATTR_MIN);
            String upperBoundString = parser.getAttributeValue(null, ATTR_MAX);
            if (lowerBoundString != null || upperBoundString != null) {
                if (equalsValue != null) {
                    throw new XmlPullParserException(
                            "Port filter cannot use both equals and range filtering",
                            parser, null);
                }

                if (lowerBoundString != null) {
                    try {
                        lowerBound = Integer.parseInt(lowerBoundString);
                    } catch (NumberFormatException ex) {
                        throw new XmlPullParserException(
                                "Invalid minimum port value: " + lowerBoundString,
                                parser, null);
                    }
                }

                if (upperBoundString != null) {
                    try {
                        upperBound = Integer.parseInt(upperBoundString);
                    } catch (NumberFormatException ex) {
                        throw new XmlPullParserException(
                                "Invalid maximum port value: " + upperBoundString,
                                parser, null);
                    }
                }
            }

            // an empty port filter is explicitly allowed, and checks for the existence of a port
            return new PortFilter(lowerBound, upperBound);
        }
    };
}
