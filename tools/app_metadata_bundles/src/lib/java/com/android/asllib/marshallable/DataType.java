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

package com.android.asllib.marshallable;

import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Data usage type representation. Types are specific to a {@link DataCategory} and contains
 * metadata related to the data usage purpose.
 */
public class DataType implements AslMarshallable {

    public enum Purpose {
        APP_FUNCTIONALITY(1),
        ANALYTICS(2),
        DEVELOPER_COMMUNICATIONS(3),
        FRAUD_PREVENTION_SECURITY(4),
        ADVERTISING(5),
        PERSONALIZATION(6),
        ACCOUNT_MANAGEMENT(7);

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
            throw new IllegalArgumentException("No Purpose enum for value: " + value);
        }

        /** Get the Purpose associated with the human-readable String. */
        public static Purpose forString(String s) {
            for (Purpose e : values()) {
                if (e.toString().equals(s)) {
                    return e;
                }
            }
            throw new IllegalArgumentException("No Purpose enum for str: " + s);
        }

        /** Human-readable String representation of Purpose. */
        public String toString() {
            return this.name().toLowerCase();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        DataType objAsDataType = (DataType) obj;
        return Objects.equals(this.mDataTypeName, objAsDataType.mDataTypeName)
                && Objects.equals(
                        new HashSet<>(this.mPurposes), new HashSet<>(objAsDataType.mPurposes))
                && Objects.equals(this.mIsCollectionOptional, objAsDataType.mIsCollectionOptional)
                && Objects.equals(this.mIsSharingOptional, objAsDataType.mIsSharingOptional)
                && Objects.equals(this.mEphemeral, objAsDataType.mEphemeral);
    }

    @Override
    public int hashCode() {
        int result = 1;
        int prime = 31;
        result =
                (prime * result) + (this.mDataTypeName != null ? this.mDataTypeName.hashCode() : 0);
        result =
                (prime * result)
                        + (this.mPurposes != null ? new HashSet<>(this.mPurposes).hashCode() : 0);
        result =
                (prime * result)
                        + (this.mIsCollectionOptional != null
                                ? this.mIsCollectionOptional.hashCode()
                                : 0);
        result =
                (prime * result)
                        + (this.mIsSharingOptional != null
                                ? this.mIsSharingOptional.hashCode()
                                : 0);
        result = (prime * result) + (this.mEphemeral != null ? this.mEphemeral.hashCode() : 0);
        return result;
    }

    private final String mDataTypeName;

    private final List<Purpose> mPurposes;
    private final Boolean mIsCollectionOptional;
    private final Boolean mIsSharingOptional;
    private final Boolean mEphemeral;

    public DataType(
            String dataTypeName,
            List<Purpose> purposes,
            Boolean isCollectionOptional,
            Boolean isSharingOptional,
            Boolean ephemeral) {
        this.mDataTypeName = dataTypeName;
        this.mPurposes = purposes;
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
    public List<Purpose> getPurposes() {
        return mPurposes;
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
        if (!this.getPurposes().isEmpty()) {
            dataTypeEle.appendChild(
                    XmlUtils.createOdArray(
                            doc,
                            XmlUtils.OD_TAG_INT_ARRAY,
                            XmlUtils.OD_NAME_PURPOSES,
                            this.getPurposes().stream()
                                    .map(p -> String.valueOf(p.getValue()))
                                    .toList()));
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
        return XmlUtils.listOf(dataTypeEle);
    }

    /** Creates the human-readable DOM elements from the AslMarshallable Java Object. */
    @Override
    public List<Element> toHrDomElements(Document doc) {
        throw new IllegalStateException(
                "Turning DataCategory or DataType into human-readable DOM elements requires"
                        + " visibility into parent elements. The logic resides in DataLabels.");
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
