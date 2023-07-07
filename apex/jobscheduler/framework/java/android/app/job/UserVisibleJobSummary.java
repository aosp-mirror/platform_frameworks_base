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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Summary of a scheduled job that the user is meant to be aware of.
 *
 * @hide
 */
public class UserVisibleJobSummary implements Parcelable {
    private final int mCallingUid;
    @NonNull
    private final String mCallingPackageName;
    private final int mSourceUserId;
    @NonNull
    private final String mSourcePackageName;
    @Nullable
    private final String mNamespace;
    private final int mJobId;

    public UserVisibleJobSummary(int callingUid, @NonNull String callingPackageName,
            int sourceUserId, @NonNull String sourcePackageName,
            @Nullable String namespace, int jobId) {
        mCallingUid = callingUid;
        mCallingPackageName = callingPackageName;
        mSourceUserId = sourceUserId;
        mSourcePackageName = sourcePackageName;
        mNamespace = namespace;
        mJobId = jobId;
    }

    protected UserVisibleJobSummary(Parcel in) {
        mCallingUid = in.readInt();
        mCallingPackageName = in.readString();
        mSourceUserId = in.readInt();
        mSourcePackageName = in.readString();
        mNamespace = in.readString();
        mJobId = in.readInt();
    }

    @NonNull
    public String getCallingPackageName() {
        return mCallingPackageName;
    }

    public int getCallingUid() {
        return mCallingUid;
    }

    public int getJobId() {
        return mJobId;
    }

    @Nullable
    public String getNamespace() {
        return mNamespace;
    }

    public int getSourceUserId() {
        return mSourceUserId;
    }

    @NonNull
    public String getSourcePackageName() {
        return mSourcePackageName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserVisibleJobSummary)) return false;
        UserVisibleJobSummary that = (UserVisibleJobSummary) o;
        return mCallingUid == that.mCallingUid
                && mCallingPackageName.equals(that.mCallingPackageName)
                && mSourceUserId == that.mSourceUserId
                && mSourcePackageName.equals(that.mSourcePackageName)
                && Objects.equals(mNamespace, that.mNamespace)
                && mJobId == that.mJobId;
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + mCallingUid;
        result = 31 * result + mCallingPackageName.hashCode();
        result = 31 * result + mSourceUserId;
        result = 31 * result + mSourcePackageName.hashCode();
        if (mNamespace != null) {
            result = 31 * result + mNamespace.hashCode();
        }
        result = 31 * result + mJobId;
        return result;
    }

    @Override
    public String toString() {
        return "UserVisibleJobSummary{"
                + "callingUid=" + mCallingUid
                + ", callingPackageName='" + mCallingPackageName + "'"
                + ", sourceUserId=" + mSourceUserId
                + ", sourcePackageName='" + mSourcePackageName + "'"
                + ", namespace=" + mNamespace
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
        dest.writeString(mCallingPackageName);
        dest.writeInt(mSourceUserId);
        dest.writeString(mSourcePackageName);
        dest.writeString(mNamespace);
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
