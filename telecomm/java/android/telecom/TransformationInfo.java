/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.telecom;

import android.os.Parcelable;
import android.os.Parcel;

/**
 * A container class to hold information related to the Assisted Dialing operation. All member
 * variables must be set when constructing a new instance of this class.
 */
public final class TransformationInfo implements Parcelable {
    private String mOriginalNumber;
    private String mTransformedNumber;
    private String mUserHomeCountryCode;
    private String mUserRoamingCountryCode;
    private int mTransformedNumberCountryCallingCode;

    public TransformationInfo(String originalNumber,
                              String transformedNumber,
                              String userHomeCountryCode,
                              String userRoamingCountryCode,
                              int transformedNumberCountryCallingCode) {
        String missing = "";
        if (originalNumber == null) {
            missing += " mOriginalNumber";
        }
        if (transformedNumber == null) {
            missing += " mTransformedNumber";
        }
        if (userHomeCountryCode == null) {
            missing += " mUserHomeCountryCode";
        }
        if (userRoamingCountryCode == null) {
            missing += " mUserRoamingCountryCode";
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required properties:" + missing);
        }
        this.mOriginalNumber = originalNumber;
        this.mTransformedNumber = transformedNumber;
        this.mUserHomeCountryCode = userHomeCountryCode;
        this.mUserRoamingCountryCode = userRoamingCountryCode;
        this.mTransformedNumberCountryCallingCode = transformedNumberCountryCallingCode;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mOriginalNumber);
        out.writeString(mTransformedNumber);
        out.writeString(mUserHomeCountryCode);
        out.writeString(mUserRoamingCountryCode);
        out.writeInt(mTransformedNumberCountryCallingCode);
    }

    public static final Parcelable.Creator<TransformationInfo> CREATOR
            = new Parcelable.Creator<TransformationInfo>() {
        public TransformationInfo createFromParcel(Parcel in) {
            return new TransformationInfo(in);
        }

        public TransformationInfo[] newArray(int size) {
            return new TransformationInfo[size];
        }
    };

    private TransformationInfo(Parcel in) {
        mOriginalNumber = in.readString();
        mTransformedNumber = in.readString();
        mUserHomeCountryCode = in.readString();
        mUserRoamingCountryCode = in.readString();
        mTransformedNumberCountryCallingCode = in.readInt();
    }

    /**
     * The original number that underwent Assisted Dialing.
     */
    public String getOriginalNumber() {
        return mOriginalNumber;
    }

    /**
     * The number after it underwent Assisted Dialing.
     */
    public String getTransformedNumber() {
        return mTransformedNumber;
    }

    /**
     * The user's home country code that was used when attempting to transform the number.
     */
    public String getUserHomeCountryCode() {
        return mUserHomeCountryCode;
    }

    /**
     * The users's roaming country code that was used when attempting to transform the number.
     */
    public String getUserRoamingCountryCode() {
        return mUserRoamingCountryCode;
    }

    /**
     * The country calling code that was used in the transformation.
     */
    public int getTransformedNumberCountryCallingCode() {
        return mTransformedNumberCountryCallingCode;
    }
}
