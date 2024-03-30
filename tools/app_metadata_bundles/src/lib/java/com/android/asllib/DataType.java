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

import java.util.List;
import java.util.Set;

/**
 * Data usage type representation. Types are specific to a {@link DataCategory} and contains
 * metadata related to the data usage purpose.
 */
public class DataType implements AslMarshallable {

    public enum Purpose {
        PURPOSE_APP_FUNCTIONALITY(1),
        PURPOSE_ANALYTICS(2),
        PURPOSE_DEVELOPER_COMMUNICATIONS(3),
        PURPOSE_FRAUD_PREVENTION_SECURITY(4),
        PURPOSE_ADVERTISING(5),
        PURPOSE_PERSONALIZATION(6),
        PURPOSE_ACCOUNT_MANAGEMENT(7);

        private static final String PURPOSE_PREFIX = "PURPOSE_";

        private final int mValue;

        Purpose(int value) {
            this.mValue = value;
        }

        /** Get the int value associated with the Purpose. */
        public int getValue() {
            return mValue;
        }

        /** Get the Purpose associated with the int value. */
        public static Purpose forValue(int value) {
            for (Purpose e : values()) {
                if (e.getValue() == value) {
                    return e;
                }
            }
            throw new IllegalArgumentException("No enum for value: " + value);
        }

        /** Get the Purpose associated with the human-readable String. */
        public static Purpose forString(String s) {
            for (Purpose e : values()) {
                if (e.toString().equals(s)) {
                    return e;
                }
            }
            throw new IllegalArgumentException("No enum for str: " + s);
        }

        /** Human-readable String representation of Purpose. */
        public String toString() {
            if (!this.name().startsWith(PURPOSE_PREFIX)) {
                return this.name();
            }
            return this.name().substring(PURPOSE_PREFIX.length()).toLowerCase();
        }
    }

    private final String mDataTypeName;

    private final Set<Purpose> mPurposeSet;
    private final Boolean mIsCollectionOptional;
    private final Boolean mIsSharingOptional;
    private final Boolean mEphemeral;

    public DataType(
            String dataTypeName,
            Set<Purpose> purposeSet,
            Boolean isCollectionOptional,
            Boolean isSharingOptional,
            Boolean ephemeral) {
        this.mDataTypeName = dataTypeName;
        this.mPurposeSet = purposeSet;
        this.mIsCollectionOptional = isCollectionOptional;
        this.mIsSharingOptional = isSharingOptional;
        this.mEphemeral = ephemeral;
    }

    public String getDataTypeName() {
        return mDataTypeName;
    }

    /**
     * Returns {@link Set} of valid {@link Integer} purposes for using the associated data category
     * and type
     */
    public Set<Purpose> getPurposeSet() {
        return mPurposeSet;
    }

    /**
     * For data-collected, returns {@code true} if data usage is user optional and {@code false} if
     * data usage is required. Should return {@code null} for data-accessed and data-shared.
     */
    public Boolean getIsCollectionOptional() {
        return mIsCollectionOptional;
    }

    /**
     * For data-shared, returns {@code true} if data usage is user optional and {@code false} if
     * data usage is required. Should return {@code null} for data-accessed and data-collected.
     */
    public Boolean getIsSharingOptional() {
        return mIsSharingOptional;
    }

    /**
     * For data-collected, returns {@code true} if data usage is user optional and {@code false} if
     * data usage is processed ephemerally. Should return {@code null} for data-shared.
     */
    public Boolean getEphemeral() {
        return mEphemeral;
    }

    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element dataTypeEle = XmlUtils.createPbundleEleWithName(doc, this.getDataTypeName());
        if (!this.getPurposeSet().isEmpty()) {
            Element purposesEle = doc.createElement(XmlUtils.OD_TAG_INT_ARRAY);
            purposesEle.setAttribute(XmlUtils.OD_ATTR_NAME, XmlUtils.OD_NAME_PURPOSES);
            purposesEle.setAttribute(
                    XmlUtils.OD_ATTR_NUM, String.valueOf(this.getPurposeSet().size()));
            for (DataType.Purpose purpose : this.getPurposeSet()) {
                Element purposeEle = doc.createElement(XmlUtils.OD_TAG_ITEM);
                purposeEle.setAttribute(XmlUtils.OD_ATTR_VALUE, String.valueOf(purpose.getValue()));
                purposesEle.appendChild(purposeEle);
            }
            dataTypeEle.appendChild(purposesEle);
        }

        maybeAddBoolToOdElement(
                doc,
                dataTypeEle,
                this.getIsCollectionOptional(),
                XmlUtils.OD_NAME_IS_COLLECTION_OPTIONAL);
        maybeAddBoolToOdElement(
                doc,
                dataTypeEle,
                this.getIsSharingOptional(),
                XmlUtils.OD_NAME_IS_SHARING_OPTIONAL);
        maybeAddBoolToOdElement(doc, dataTypeEle, this.getEphemeral(), XmlUtils.OD_NAME_EPHEMERAL);
        return List.of(dataTypeEle);
    }

    private static void maybeAddBoolToOdElement(
            Document doc, Element parentEle, Boolean b, String odName) {
        if (b == null) {
            return;
        }
        Element ele = XmlUtils.createOdBooleanEle(doc, odName, b);
        parentEle.appendChild(ele);
    }
}
