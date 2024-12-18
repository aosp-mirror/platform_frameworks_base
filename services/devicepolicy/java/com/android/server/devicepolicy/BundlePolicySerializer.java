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

package com.android.server.devicepolicy;

import android.annotation.NonNull;
import android.app.admin.BundlePolicyValue;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

final class BundlePolicySerializer extends PolicySerializer<Bundle> {

    private static final String TAG = "BundlePolicySerializer";

    private static final String TAG_ENTRY = "entry";
    private static final String TAG_VALUE = "value";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_VALUE_TYPE = "type";
    private static final String ATTR_MULTIPLE = "m";

    private static final String ATTR_TYPE_STRING_ARRAY = "sa";
    private static final String ATTR_TYPE_STRING = "s";
    private static final String ATTR_TYPE_BOOLEAN = "b";
    private static final String ATTR_TYPE_INTEGER = "i";
    private static final String ATTR_TYPE_BUNDLE = "B";
    private static final String ATTR_TYPE_BUNDLE_ARRAY = "BA";

    @Override
    void saveToXml(TypedXmlSerializer serializer, @NonNull Bundle value) throws IOException {
        Objects.requireNonNull(value);
        writeBundle(value, serializer);
    }

    @Override
    BundlePolicyValue readFromXml(TypedXmlPullParser parser) {
        Bundle bundle = new Bundle();
        ArrayList<String> values = new ArrayList<>();
        try {
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                readBundle(bundle, values, parser);
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error parsing Bundle policy.", e);
            return null;
        }
        return new BundlePolicyValue(bundle);
    }

    private static void readBundle(Bundle restrictions, ArrayList<String> values,
            TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_ENTRY)) {
            String key = parser.getAttributeValue(null, ATTR_KEY);
            String valType = parser.getAttributeValue(null, ATTR_VALUE_TYPE);
            int count = parser.getAttributeInt(null, ATTR_MULTIPLE, -1);
            if (count != -1) {
                values.clear();
                while (count > 0 && (type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG
                            && parser.getName().equals(TAG_VALUE)) {
                        values.add(parser.nextText());
                        count--;
                    }
                }
                String [] valueStrings = new String[values.size()];
                values.toArray(valueStrings);
                restrictions.putStringArray(key, valueStrings);
            } else if (ATTR_TYPE_BUNDLE.equals(valType)) {
                restrictions.putBundle(key, readBundleEntry(parser, values));
            } else if (ATTR_TYPE_BUNDLE_ARRAY.equals(valType)) {
                final int outerDepth = parser.getDepth();
                ArrayList<Bundle> bundleList = new ArrayList<>();
                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    Bundle childBundle = readBundleEntry(parser, values);
                    bundleList.add(childBundle);
                }
                restrictions.putParcelableArray(key,
                        bundleList.toArray(new Bundle[bundleList.size()]));
            } else {
                String value = parser.nextText();
                if (ATTR_TYPE_BOOLEAN.equals(valType)) {
                    restrictions.putBoolean(key, Boolean.parseBoolean(value));
                } else if (ATTR_TYPE_INTEGER.equals(valType)) {
                    restrictions.putInt(key, Integer.parseInt(value));
                } else {
                    restrictions.putString(key, value);
                }
            }
        }
    }

    private static Bundle readBundleEntry(TypedXmlPullParser parser, ArrayList<String> values)
            throws IOException, XmlPullParserException {
        Bundle childBundle = new Bundle();
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            readBundle(childBundle, values, parser);
        }
        return childBundle;
    }

    private static void writeBundle(Bundle restrictions, TypedXmlSerializer serializer)
            throws IOException {
        for (String key : restrictions.keySet()) {
            Object value = restrictions.get(key);
            serializer.startTag(null, TAG_ENTRY);
            serializer.attribute(null, ATTR_KEY, key);

            if (value instanceof Boolean) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BOOLEAN);
                serializer.text(value.toString());
            } else if (value instanceof Integer) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_INTEGER);
                serializer.text(value.toString());
            } else if (value == null || value instanceof String) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_STRING);
                serializer.text(value != null ? (String) value : "");
            } else if (value instanceof Bundle) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE);
                writeBundle((Bundle) value, serializer);
            } else if (value instanceof Parcelable[]) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE_ARRAY);
                Parcelable[] array = (Parcelable[]) value;
                for (Parcelable parcelable : array) {
                    if (!(parcelable instanceof Bundle)) {
                        throw new IllegalArgumentException("bundle-array can only hold Bundles");
                    }
                    serializer.startTag(null, TAG_ENTRY);
                    serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE);
                    writeBundle((Bundle) parcelable, serializer);
                    serializer.endTag(null, TAG_ENTRY);
                }
            } else {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_STRING_ARRAY);
                String[] values = (String[]) value;
                serializer.attributeInt(null, ATTR_MULTIPLE, values.length);
                for (String choice : values) {
                    serializer.startTag(null, TAG_VALUE);
                    serializer.text(choice != null ? choice : "");
                    serializer.endTag(null, TAG_VALUE);
                }
            }
            serializer.endTag(null, TAG_ENTRY);
        }
    }
}
