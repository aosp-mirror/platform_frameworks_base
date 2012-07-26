/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.telephony.cdma;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * CDMA Service Category Program Results from SCPT teleservice SMS.
 *
 * {@hide}
 */
public class CdmaSmsCbProgramResults implements Parcelable {

    /** Program result: success. */
    public static final int RESULT_SUCCESS                  = 0;

    /** Program result: memory limit exceeded. */
    public static final int RESULT_MEMORY_LIMIT_EXCEEDED    = 1;

    /** Program result: limit exceeded. */
    public static final int RESULT_CATEGORY_LIMIT_EXCEEDED  = 2;

    /** Program result: category already opted in. */
    public static final int RESULT_CATEGORY_ALREADY_ADDED   = 3;

    /** Program result: category already opted in. */
    public static final int RESULT_CATEGORY_ALREADY_DELETED = 4;

    /** Program result: invalid MAX_MESSAGES. */
    public static final int RESULT_INVALID_MAX_MESSAGES     = 5;

    /** Program result: invalid ALERT_OPTION. */
    public static final int RESULT_INVALID_ALERT_OPTION     = 6;

    /** Program result: invalid service category name. */
    public static final int RESULT_INVALID_CATEGORY_NAME    = 7;

    /** Program result: unspecified programming failure. */
    public static final int RESULT_UNSPECIFIED_FAILURE      = 8;

    /** Service category to modify. */
    private final int mCategory;

    /** Language used for service category name (defined in BearerData.LANGUAGE_*). */
    private final int mLanguage;

    /** Result of service category programming for this category. */
    private final int mCategoryResult;

    /** Create a new CdmaSmsCbProgramResults object with the specified values. */
    public CdmaSmsCbProgramResults(int category, int language, int categoryResult) {
        mCategory = category;
        mLanguage = language;
        mCategoryResult = categoryResult;
    }

    /** Create a new CdmaSmsCbProgramResults object from a Parcel. */
    CdmaSmsCbProgramResults(Parcel in) {
        mCategory = in.readInt();
        mLanguage = in.readInt();
        mCategoryResult = in.readInt();
    }

    /**
     * Flatten this object into a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written (ignored).
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCategory);
        dest.writeInt(mLanguage);
        dest.writeInt(mCategoryResult);
    }

    /**
     * Returns the CDMA service category to modify.
     * @return a 16-bit CDMA service category value
     */
    public int getCategory() {
        return mCategory;
    }

    /**
     * Returns the CDMA language code for this service category.
     * @return one of the language values defined in BearerData.LANGUAGE_*
     */
    public int getLanguage() {
        return mLanguage;
    }

    /**
     * Returns the result of service programming for this category
     * @return the result of service programming for this category
     */
    public int getCategoryResult() {
        return mCategoryResult;
    }

    @Override
    public String toString() {
        return "CdmaSmsCbProgramResults{category=" + mCategory
                + ", language=" + mLanguage + ", result=" + mCategoryResult + '}';
    }

    /**
     * Describe the kinds of special objects contained in the marshalled representation.
     * @return a bitmask indicating this Parcelable contains no special objects
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Creator for unparcelling objects. */
    public static final Parcelable.Creator<CdmaSmsCbProgramResults>
            CREATOR = new Parcelable.Creator<CdmaSmsCbProgramResults>() {
        @Override
        public CdmaSmsCbProgramResults createFromParcel(Parcel in) {
            return new CdmaSmsCbProgramResults(in);
        }

        @Override
        public CdmaSmsCbProgramResults[] newArray(int size) {
            return new CdmaSmsCbProgramResults[size];
        }
    };
}
