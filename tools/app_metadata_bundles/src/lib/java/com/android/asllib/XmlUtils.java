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

package com.android.asllib;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class XmlUtils {
    public static final String HR_TAG_APP_METADATA_BUNDLES = "app-metadata-bundles";
    public static final String HR_TAG_SAFETY_LABELS = "safety-labels";
    public static final String HR_TAG_DATA_LABELS = "data-labels";
    public static final String HR_TAG_DATA_ACCESSED = "data-accessed";
    public static final String HR_TAG_DATA_COLLECTED = "data-collected";
    public static final String HR_TAG_DATA_SHARED = "data-shared";

    public static final String HR_ATTR_DATA_CATEGORY = "dataCategory";
    public static final String HR_ATTR_DATA_TYPE = "dataType";
    public static final String HR_ATTR_IS_COLLECTION_OPTIONAL = "isCollectionOptional";
    public static final String HR_ATTR_IS_SHARING_OPTIONAL = "isSharingOptional";
    public static final String HR_ATTR_EPHEMERAL = "ephemeral";
    public static final String HR_ATTR_PURPOSES = "purposes";
    public static final String HR_ATTR_VERSION = "version";

    public static final String OD_TAG_BUNDLE = "bundle";
    public static final String OD_TAG_PBUNDLE_AS_MAP = "pbundle_as_map";
    public static final String OD_TAG_BOOLEAN = "boolean";
    public static final String OD_TAG_INT_ARRAY = "int-array";
    public static final String OD_TAG_ITEM = "item";
    public static final String OD_ATTR_NAME = "name";
    public static final String OD_ATTR_VALUE = "value";
    public static final String OD_ATTR_NUM = "num";
    public static final String OD_NAME_SAFETY_LABELS = "safety_labels";
    public static final String OD_NAME_DATA_LABELS = "data_labels";
    public static final String OD_NAME_DATA_ACCESSED = "data_accessed";
    public static final String OD_NAME_DATA_COLLECTED = "data_collected";
    public static final String OD_NAME_DATA_SHARED = "data_shared";
    public static final String OD_NAME_PURPOSES = "purposes";
    public static final String OD_NAME_IS_COLLECTION_OPTIONAL = "is_collection_optional";
    public static final String OD_NAME_IS_SHARING_OPTIONAL = "is_sharing_optional";
    public static final String OD_NAME_EPHEMERAL = "ephemeral";

    public static final String TRUE_STR = "true";
    public static final String FALSE_STR = "false";

    /** Gets the single top-level {@link Element} having the {@param tagName}. */
    public static Element getSingleElement(Document doc, String tagName) {
        var elements = doc.getElementsByTagName(tagName);
        return getSingleElement(elements, tagName);
    }

    /**
     * Gets the single {@link Element} within {@param parentEle} and having the {@param tagName}.
     */
    public static Element getSingleElement(Element parentEle, String tagName) {
        var elements = parentEle.getElementsByTagName(tagName);
        return getSingleElement(elements, tagName);
    }

    /** Gets the single {@link Element} from {@param elements} and having the {@param tagName}. */
    public static Element getSingleElement(NodeList elements, String tagName) {
        if (elements.getLength() != 1) {
            throw new IllegalArgumentException(
                    String.format("Expected 1 %s but got %s.", tagName, elements.getLength()));
        }
        var elementAsNode = elements.item(0);
        if (!(elementAsNode instanceof Element)) {
            throw new IllegalStateException(String.format("%s was not an element.", tagName));
        }
        return ((Element) elementAsNode);
    }

    /** Gets the Boolean from the String value. */
    public static Boolean fromString(String s) {
        if (s == null) {
            return null;
        }
        if (s.equals(TRUE_STR)) {
            return true;
        } else if (s.equals(FALSE_STR)) {
            return false;
        }
        return null;
    }

    /** Creates an on-device PBundle DOM Element with the given attribute name. */
    public static Element createPbundleEleWithName(Document doc, String name) {
        var ele = doc.createElement(XmlUtils.OD_TAG_PBUNDLE_AS_MAP);
        ele.setAttribute(XmlUtils.OD_ATTR_NAME, name);
        return ele;
    }

    /** Create an on-device Boolean DOM Element with the given attribute name. */
    public static Element createOdBooleanEle(Document doc, String name, boolean b) {
        var ele = doc.createElement(XmlUtils.OD_TAG_BOOLEAN);
        ele.setAttribute(XmlUtils.OD_ATTR_NAME, name);
        ele.setAttribute(XmlUtils.OD_ATTR_VALUE, String.valueOf(b));
        return ele;
    }

    /** Returns whether the String is null or empty. */
    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
