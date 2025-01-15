/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 *  Bits and pieces copied from hidden API of
 *  frameworks/base/core/java/com/android/internal/util/XmlUtils.java
 *
 * @hide
 */
public class XmlUtils {

    /** @hide */
    public static final void beginDocument(XmlPullParser parser, String firstElementName)
            throws XmlPullParserException, IOException {
        int type;
        while ((type = parser.next()) != parser.START_TAG
            && type != parser.END_DOCUMENT) {
            // Do nothing
        }

        if (type != parser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }

        if (!parser.getName().equals(firstElementName)) {
            throw new XmlPullParserException("Unexpected start tag: found " + parser.getName()
                + ", expected " + firstElementName);
        }
    }

    /** @hide */
    public static boolean nextElementWithin(XmlPullParser parser, int outerDepth)
            throws IOException, XmlPullParserException {
        for (;;) {
            int type = parser.next();
            if (type == XmlPullParser.END_DOCUMENT
                    || (type == XmlPullParser.END_TAG && parser.getDepth() == outerDepth)) {
                return false;
            }
            if (type == XmlPullParser.START_TAG
                    && parser.getDepth() == outerDepth + 1) {
                return true;
            }
        }
    }
}
