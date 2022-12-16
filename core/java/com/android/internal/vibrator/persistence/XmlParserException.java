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

package com.android.internal.vibrator.persistence;

import android.text.TextUtils;

import org.xmlpull.v1.XmlPullParserException;

/**
 * Represents an error while parsing a vibration XML input.
 *
 * @see XmlParser
 * @hide
 */
public final class XmlParserException extends Exception {

    /**
     * Creates a {@link XmlParserException} from a {@link XmlPullParserException}, with root cause
     * and default error message that includes the tag name.
     */
    public static XmlParserException createFromPullParserException(
            String tagName, XmlPullParserException cause) {
        return new XmlParserException("Error parsing " + tagName, cause);
    }

    /**
     * Creates a {@link XmlParserException} from a {@link XmlPullParserException}, with root cause
     * and default error message that includes the tag name, the attribute name and value.
     */
    public static XmlParserException createFromPullParserException(
            String tagName, String attributeName, String attributeValue,
            XmlPullParserException cause) {
        return new XmlParserException(TextUtils.formatSimple("Error parsing %s = %s in tag %s",
                attributeName, attributeValue, tagName), cause);
    }

    public XmlParserException(String message) {
        super(message);
    }

    public XmlParserException(String message, Throwable cause) {
        super(message, cause);
    }
}
