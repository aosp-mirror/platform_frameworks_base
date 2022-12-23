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

package android.app.job;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Summary of a scheduled job that the user is meant to be aware of.
 *
 * @hide
 */
public class UserVisibleJobSummary implements Parcelable {
    private final int mCallingUid;
    private final int mSourceUserId;
    @NonNull
    private final String mSourcePackageName;
    private final int mJobId;

    public UserVisibleJobSummary(int callingUid, int sourceUserId,
            @NonNull String sourcePackageName, int jobId) {
        mCallingUid = callingUid;
        mSourceUserId = sourceUserId;
        mSourcePackageName = sourcePackageName;
        mJobId = jobId;
    }

    protected UserVisibleJobSummary(Parcel in) {
        mCallingUid = in.readInt();
        mSourceUserId = in.readInt();
        mSourcePackageName = in.readString();
        mJobId = in.readInt();
    }

    public int getCallingUid() {
        return mCallingUid;
    }

    public int getJobId() {
        return mJobId;
    }

    public int getSourceUserId() {
        return mSourceUserId;
    }

    public String getSourcePackageName() {
        return mSourcePackageName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserVisibleJobSummary)) return false;
        UserVisibleJobSummary that = (UserVisibleJobSummary) o;
        return mCallingUid == that.mCallingUid
                && mSourceUserId == that.mSourceUserId
                && mSourcePackageName.equals(that.mSourcePackageName)
                && mJobId == that.mJobId;
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + mCallingUid;
        result = 31 * result + mSourceUserId;
        result = 31 * result + mSourcePackageName.hashCode();
        result = 31 * result + mJobId;
        return result;
    }

    @Override
    public String toString() {
        return "UserVisibleJobSummary{"
                + "callingUid=" + mCallingUid
                + ", sourceUserId=" + mSourceUserId
                + ", sourcePackageName='" + mSourcePackageName + "'"
                + ", jobId=" + mJobId
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCallingUid);
        dest.writeInt(mSourceUserId);
        dest.writeString(mSourcePackageName);
        dest.writeInt(mJobId);
    }

    public static final Creator<UserVisibleJobSummary> CREATOR =
            new Creator<UserVisibleJobSummary>() {
                @Override
                public UserVisibleJobSummary createFromParcel(Parcel in) {
                    return new UserVisibleJobSummary(in);
                }

                @Override
                public UserVisibleJobSummary[] newArray(int size) {
                    return new UserVisibleJobSummary[size];
                }
            };
}
