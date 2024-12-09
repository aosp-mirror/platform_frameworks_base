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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * CDMA Service Category Program Data from SCPT (Service Category Programming Teleservice) SMS,
 * as defined in 3GPP2 C.S0015-B section 4.5.19.
 * <p>
 * The CellBroadcastReceiver app receives an Intent with action
 * {@link android.provider.Telephony.Sms.Intents#SMS_SERVICE_CATEGORY_PROGRAM_DATA_RECEIVED_ACTION}
 * containing an array of these objects to update its list of cell broadcast service categories
 * to display.
 *
 * {@hide}
 */
@SystemApi
public final class CdmaSmsCbProgramData implements Parcelable {

    /** Delete the specified service category from the list of enabled categories. */
    public static final int OPERATION_DELETE_CATEGORY   = 0;

    /** Add the specified service category to the list of enabled categories. */
    public static final int OPERATION_ADD_CATEGORY      = 1;

    /** Clear all service categories from the list of enabled categories. */
    public static final int OPERATION_CLEAR_CATEGORIES  = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"OPERATION_"},
            value = {
                    OPERATION_DELETE_CATEGORY,
                    OPERATION_ADD_CATEGORY,
                    OPERATION_CLEAR_CATEGORIES,
            })
    public @interface Operation {}

    // CMAS alert service category assignments, see 3GPP2 C.R1001 table 9.3.3-1
    /** Indicates a presidential-level alert */
    public static final int CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT  = 0x1000;

    /** Indicates an extreme threat to life and property */
    public static final int CATEGORY_CMAS_EXTREME_THREAT            = 0x1001;

    /** Indicates an severe threat to life and property */
    public static final int CATEGORY_CMAS_SEVERE_THREAT             = 0x1002;

    /** Indicates an AMBER child abduction emergency */
    public static final int CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY = 0x1003;

    /** Indicates a CMAS test message */
    public static final int CATEGORY_CMAS_TEST_MESSAGE              = 0x1004;

    /** The last reserved value of a CMAS service category according to 3GPP C.R1001 table
     * 9.3.3-1. */
    public static final int CATEGORY_CMAS_LAST_RESERVED_VALUE       = 0x10ff;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CATEGORY_"},
            value = {
                    CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT,
                    CATEGORY_CMAS_EXTREME_THREAT,
                    CATEGORY_CMAS_SEVERE_THREAT,
                    CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY,
                    CATEGORY_CMAS_TEST_MESSAGE,
                    CATEGORY_CMAS_LAST_RESERVED_VALUE,
            })
    public @interface Category {}

    /** Alert option: no alert. @hide */
    public static final int ALERT_OPTION_NO_ALERT               = 0;

    /** Alert option: default alert. @hide */
    public static final int ALERT_OPTION_DEFAULT_ALERT          = 1;

    /** Alert option: vibrate alert once. @hide */
    public static final int ALERT_OPTION_VIBRATE_ONCE           = 2;

    /** Alert option: vibrate alert - repeat. @hide */
    public static final int ALERT_OPTION_VIBRATE_REPEAT         = 3;

    /** Alert option: visual alert once. @hide */
    public static final int ALERT_OPTION_VISUAL_ONCE            = 4;

    /** Alert option: visual alert - repeat. @hide */
    public static final int ALERT_OPTION_VISUAL_REPEAT          = 5;

    /** Alert option: low-priority alert once. @hide */
    public static final int ALERT_OPTION_LOW_PRIORITY_ONCE      = 6;

    /** Alert option: low-priority alert - repeat. @hide */
    public static final int ALERT_OPTION_LOW_PRIORITY_REPEAT    = 7;

    /** Alert option: medium-priority alert once. @hide */
    public static final int ALERT_OPTION_MED_PRIORITY_ONCE      = 8;

    /** Alert option: medium-priority alert - repeat. @hide */
    public static final int ALERT_OPTION_MED_PRIORITY_REPEAT    = 9;

    /** Alert option: high-priority alert once. @hide */
    public static final int ALERT_OPTION_HIGH_PRIORITY_ONCE     = 10;

    /** Alert option: high-priority alert - repeat. @hide */
    public static final int ALERT_OPTION_HIGH_PRIORITY_REPEAT   = 11;

    /** Service category operation (add/delete/clear). */
    private final int mOperation;

    /** Service category to modify. */
    private final int mCategory;

    /** Language used for service category name (defined in BearerData.LANGUAGE_*). */
    private final int mLanguage;

    /** Maximum number of messages to store for this service category. */
    private final int mMaxMessages;

    /** Service category alert option. */
    private final int mAlertOption;

    /** Name of service category. */
    private final String mCategoryName;

    /**
     * Create a new CdmaSmsCbProgramData object with the specified values.
     * @hide
     */
    public CdmaSmsCbProgramData(@Operation int operation, @Category int category, int language,
            int maxMessages, int alertOption, @NonNull String categoryName) {
        mOperation = operation;
        mCategory = category;
        mLanguage = language;
        mMaxMessages = maxMessages;
        mAlertOption = alertOption;
        mCategoryName = categoryName;
    }

    /**
     * Create a new CdmaSmsCbProgramData object from a Parcel.
     * @hide
     */
    CdmaSmsCbProgramData(Parcel in) {
        mOperation = in.readInt();
        mCategory = in.readInt();
        mLanguage = in.readInt();
        mMaxMessages = in.readInt();
        mAlertOption = in.readInt();
        mCategoryName = in.readString();
    }

    /**
     * Flatten this object into a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written (ignored).
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mOperation);
        dest.writeInt(mCategory);
        dest.writeInt(mLanguage);
        dest.writeInt(mMaxMessages);
        dest.writeInt(mAlertOption);
        dest.writeString(mCategoryName);
    }

    /**
     * Returns the service category operation, e.g. {@link #OPERATION_ADD_CATEGORY}.
     *
     * @return the service category operation
     */
    public @Operation int getOperation() {
        return mOperation;
    }

    /**
     * Returns the CDMA service category to modify. See 3GPP2 C.S0015-B section 3.4.3.2 for more
     * information on the service category. Currently only CMAS service categories 0x1000 through
     * 0x10FF are supported.
     *
     * @return a 16-bit CDMA service category value
     */
    public @Category int getCategory() {
        return mCategory;
    }

    /**
     * Returns the CDMA language code for this service category.
     * @return one of the language values defined in BearerData.LANGUAGE_*
     * @hide
     */
    public int getLanguage() {
        return mLanguage;
    }

    /**
     * Returns the maximum number of messages to store for this service category.
     * @return the maximum number of messages to store for this service category
     * @hide
     */
    public int getMaxMessages() {
        return mMaxMessages;
    }

    /**
     * Returns the service category alert option, e.g. {@link #ALERT_OPTION_DEFAULT_ALERT}.
     * @return one of the {@code ALERT_OPTION_*} values
     * @hide
     */
    public int getAlertOption() {
        return mAlertOption;
    }

    /**
     * Returns the service category name, in the language specified by {@link #getLanguage()}.
     * @return an optional service category name
     * @hide
     */
    @NonNull
    public String getCategoryName() {
        return mCategoryName;
    }

    @Override
    public String toString() {
        return "CdmaSmsCbProgramData{operation=" + mOperation + ", category=" + mCategory
                + ", language=" + mLanguage + ", max messages=" + mMaxMessages
                + ", alert option=" + mAlertOption + ", category name=" + mCategoryName + '}';
    }

    /**
     * Describe the kinds of special objects contained in the marshalled representation.
     * @return a bitmask indicating this Parcelable contains no special objects
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Creator for unparcelling objects.
     */
    @NonNull
    public static final Parcelable.Creator<CdmaSmsCbProgramData>
            CREATOR = new Parcelable.Creator<CdmaSmsCbProgramData>() {
        @Override
        public CdmaSmsCbProgramData createFromParcel(Parcel in) {
            return new CdmaSmsCbProgramData(in);
        }

        @Override
        public CdmaSmsCbProgramData[] newArray(int size) {
            return new CdmaSmsCbProgramData[size];
        }
    };
}
