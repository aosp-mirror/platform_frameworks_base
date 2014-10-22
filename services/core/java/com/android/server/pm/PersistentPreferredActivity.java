/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.pm;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.util.Log;

import java.io.IOException;

class PersistentPreferredActivity extends IntentFilter {
    private static final String ATTR_NAME = "name"; // component name
    private static final String ATTR_FILTER = "filter"; // filter

    private static final String TAG = "PersistentPreferredActivity";

    private static final boolean DEBUG_FILTERS = false;

    final ComponentName mComponent;

    PersistentPreferredActivity(IntentFilter filter, ComponentName activity) {
        super(filter);
        mComponent = activity;
    }

    PersistentPreferredActivity(XmlPullParser parser) throws XmlPullParserException, IOException {
        String shortComponent = parser.getAttributeValue(null, ATTR_NAME);
        mComponent = ComponentName.unflattenFromString(shortComponent);
        if (mComponent == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: " +
                            "Bad activity name " + shortComponent +
                            " at " + parser.getPositionDescription());
        }
        int outerDepth = parser.getDepth();
        String tagName = parser.getName();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            tagName = parser.getName();
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            } else if (type == XmlPullParser.START_TAG) {
                if (tagName.equals(ATTR_FILTER)) {
                    break;
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element: " + tagName +
                            " at " + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        if (tagName.equals(ATTR_FILTER)) {
            readFromXml(parser);
        } else {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Missing element filter at " +
                    parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
        }
    }

    public void writeToXml(XmlSerializer serializer) throws IOException {
        serializer.attribute(null, ATTR_NAME, mComponent.flattenToShortString());
        serializer.startTag(null, ATTR_FILTER);
            super.writeToXml(serializer);
        serializer.endTag(null, ATTR_FILTER);
    }

    @Override
    public String toString() {
        return "PersistentPreferredActivity{0x" + Integer.toHexString(System.identityHashCode(this))
                + " " + mComponent.flattenToShortString() + "}";
    }
}
