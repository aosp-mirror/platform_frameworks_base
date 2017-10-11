/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.service.notification;

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents an option to be shown to users for snoozing a notification until a given context
 * instead of for a fixed amount of time.
 * @hide
 */
@SystemApi
@TestApi
public final class SnoozeCriterion implements Parcelable {
    private final String mId;
    private final CharSequence mExplanation;
    private final CharSequence mConfirmation;

    public SnoozeCriterion(String id, CharSequence explanation, CharSequence confirmation) {
        mId = id;
        mExplanation = explanation;
        mConfirmation = confirmation;
    }

    protected SnoozeCriterion(Parcel in) {
        if (in.readByte() != 0) {
            mId = in.readString();
        } else {
            mId = null;
        }
        if (in.readByte() != 0) {
            mExplanation = in.readCharSequence();
        } else {
            mExplanation = null;
        }
        if (in.readByte() != 0) {
            mConfirmation = in.readCharSequence();
        } else {
            mConfirmation = null;
        }
    }

    /**
     * Returns the id of this criterion.
     */
    public String getId() {
        return mId;
    }

    /**
     * Returns the user visible explanation of how long a notification will be snoozed if
     * this criterion is chosen.
     */
    public CharSequence getExplanation() {
        return mExplanation;
    }

    /**
     * Returns the user visible confirmation message shown when this criterion is chosen.
     */
    public CharSequence getConfirmation() {
        return mConfirmation;
    }

    public static final Creator<SnoozeCriterion> CREATOR = new Creator<SnoozeCriterion>() {
        @Override
        public SnoozeCriterion createFromParcel(Parcel in) {
            return new SnoozeCriterion(in);
        }

        @Override
        public SnoozeCriterion[] newArray(int size) {
            return new SnoozeCriterion[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mId != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mId);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mExplanation != null) {
            dest.writeByte((byte) 1);
            dest.writeCharSequence(mExplanation);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mConfirmation != null) {
            dest.writeByte((byte) 1);
            dest.writeCharSequence(mConfirmation);
        } else {
            dest.writeByte((byte) 0);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SnoozeCriterion that = (SnoozeCriterion) o;

        if (mId != null ? !mId.equals(that.mId) : that.mId != null) return false;
        if (mExplanation != null ? !mExplanation.equals(that.mExplanation)
                : that.mExplanation != null) {
            return false;
        }
        return mConfirmation != null ? mConfirmation.equals(that.mConfirmation)
                : that.mConfirmation == null;

    }

    @Override
    public int hashCode() {
        int result = mId != null ? mId.hashCode() : 0;
        result = 31 * result + (mExplanation != null ? mExplanation.hashCode() : 0);
        result = 31 * result + (mConfirmation != null ? mConfirmation.hashCode() : 0);
        return result;
    }
}
