/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.res;

import static android.content.res.Element.TAG_MANIFEST;

import android.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Validates manifest files by ensuring that tag counts and the length of string attributes are
 * restricted.
 *
 * {@hide}
 */
public class Validator {

    private static final int MAX_TAG_COUNT = 100000;

    private final ArrayDeque<Element> mElements = new ArrayDeque<>();
    private final Map<String, TagCounter> mTagCounters = new HashMap<>();

    private void cleanUp() {
        while (!mElements.isEmpty()) {
            mElements.pop().recycle();
        }
        Iterator<Map.Entry<String, TagCounter>> it = mTagCounters.entrySet().iterator();
        while (it.hasNext()) {
            it.next().getValue().recycle();
            it.remove();
        }
    }

    private void seen(String tag) throws XmlPullParserException {
        mTagCounters.putIfAbsent(tag, TagCounter.obtain(MAX_TAG_COUNT));
        TagCounter counter = mTagCounters.get(tag);
        counter.increment();
        if (!counter.isValid()) {
            throw new XmlPullParserException("The number of " + tag
                    + " tags exceeded " + MAX_TAG_COUNT);
        }
    }

    /**
     * Validates the elements and it's attributes as the XmlPullParser traverses the xml.
     */
    public void validate(@NonNull XmlPullParser parser) throws XmlPullParserException {
        int eventType = parser.getEventType();
        int depth = parser.getDepth();
        // The mElement size should equal to the parser depth-1 when the parser eventType is
        // START_TAG. If depth - mElement.size() is larger than 1 then that means
        // validation for the previous element was skipped so we should skip validation for all
        // descendant elements as well
        if (depth > mElements.size() + 1) {
            return;
        }
        if (eventType == XmlPullParser.START_TAG) {
            try {
                String tag = parser.getName();
                // only validate manifests
                if (depth == 0 && mElements.size() == 0 && !TAG_MANIFEST.equals(tag)) {
                    return;
                }
                Element parent = mElements.peek();
                if (parent == null || parent.hasChild(tag)) {
                    seen(tag);
                    Element element = Element.obtain(tag);
                    element.validateStringAttrs(parser);
                    if (parent != null) {
                        parent.seen(element);
                    }
                    mElements.push(element);
                }
            } catch (XmlPullParserException e) {
                cleanUp();
                throw e;
            }
        } else if (eventType == XmlPullParser.END_TAG && depth == mElements.size()) {
            mElements.pop().recycle();
        } else if (eventType == XmlPullParser.END_DOCUMENT) {
            cleanUp();
        }
    }

    /**
     * Validates the resource string of a manifest tag attribute.
     */
    public void validateAttr(@NonNull XmlPullParser parser, int index, CharSequence stringValue)
            throws XmlPullParserException {
        if (parser.getDepth() > mElements.size()) {
            return;
        }
        mElements.peek().validateResStringAttr(index, stringValue);
    }
}
