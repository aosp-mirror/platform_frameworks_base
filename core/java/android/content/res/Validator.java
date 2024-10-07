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

import android.annotation.NonNull;
import android.annotation.StyleableRes;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.ArrayDeque;

/**
 * Validates manifest files by ensuring that tag counts and the length of string attributes are
 * restricted.
 *
 * {@hide}
 */
@RavenwoodKeepWholeClass
public class Validator {

    private final ArrayDeque<Element> mElements = new ArrayDeque<>();

    private void cleanUp() {
        while (!mElements.isEmpty()) {
            mElements.pop().recycle();
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
            String tag = parser.getName();
            if (Element.shouldValidate(tag)) {
                Element element = Element.obtain(tag);
                Element parent = mElements.peek();
                if (parent != null && parent.hasChild(tag)) {
                    try {
                        parent.seen(element);
                    } catch (SecurityException e) {
                        cleanUp();
                        throw e;
                    }
                }
                mElements.push(element);
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
    public void validateResStrAttr(@NonNull XmlPullParser parser, @StyleableRes int index,
            CharSequence stringValue) {
        if (parser.getDepth() > mElements.size()) {
            return;
        }
        mElements.peek().validateResStrAttr(index, stringValue);
        if (index == R.styleable.AndroidManifestMetaData_value) {
            validateComponentMetadata(stringValue.toString());
        }
    }

    /**
     * Validates the string of a manifest tag attribute by name.
     */
    public void validateStrAttr(@NonNull XmlPullParser parser, String attrName, String attrValue) {
        if (parser.getDepth() > mElements.size()) {
            return;
        }
        mElements.peek().validateStrAttr(attrName, attrValue);
        if (attrName.equals(Element.TAG_ATTR_VALUE)) {
            validateComponentMetadata(attrValue);
        }
    }

    private void validateComponentMetadata(String attrValue) {
        Element element = mElements.peek();
        // Meta-data values are evaluated on the parent element which is the next element in the
        // mElements stack after the meta-data element. The top of the stack is always the current
        // element being validated so check that the top element is meta-data.
        if (element.mTag.equals(Element.TAG_META_DATA) && mElements.size() > 1) {
            element = mElements.pop();
            mElements.peek().validateComponentMetadata(attrValue);
            mElements.push(element);
        }
    }
}
