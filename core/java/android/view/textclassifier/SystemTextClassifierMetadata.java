/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.util.Locale;
import java.util.Objects;

/**
 * SystemTextClassifier specific information.
 * <p>
 * This contains information requires for the TextClassificationManagerService to process the
 * requests from the application, e.g. user id, calling package name and etc. Centrialize the data
 * into this class helps to extend the scalability if we want to add new fields.
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PACKAGE)
public final class SystemTextClassifierMetadata implements Parcelable {

    /* The name of the package that sent the TC request. */
    @NonNull
    private final String mCallingPackageName;
    /* The id of the user that sent the TC request. */
    @UserIdInt
    private final int mUserId;
    /* Whether to use the default text classifier to handle the request. */
    private final boolean mUseDefaultTextClassifier;

    public SystemTextClassifierMetadata(@NonNull String packageName, @UserIdInt int userId,
            boolean useDefaultTextClassifier) {
        Objects.requireNonNull(packageName);
        mCallingPackageName = packageName;
        mUserId = userId;
        mUseDefaultTextClassifier = useDefaultTextClassifier;
    }

    /**
     * Returns the id of the user that sent the TC request.
     */
    @UserIdInt
    public int getUserId() {
        return mUserId;
    }

    /**
     * Returns the name of the package that sent the TC request.
     * This returns {@code null} if no calling package name is set.
     */
    @NonNull
    public String getCallingPackageName() {
        return mCallingPackageName;
    }

    /**
     * Returns whether to use the default text classifier to handle TC request.
     */
    public boolean useDefaultTextClassifier() {
        return mUseDefaultTextClassifier;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "SystemTextClassifierMetadata {callingPackageName=%s, userId=%d, "
                        + "useDefaultTextClassifier=%b}",
                mCallingPackageName, mUserId, mUseDefaultTextClassifier);
    }

    private static SystemTextClassifierMetadata readFromParcel(Parcel in) {
        final String packageName = in.readString();
        final int userId = in.readInt();
        final boolean useDefaultTextClassifier = in.readBoolean();
        return new SystemTextClassifierMetadata(packageName, userId, useDefaultTextClassifier);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mCallingPackageName);
        dest.writeInt(mUserId);
        dest.writeBoolean(mUseDefaultTextClassifier);
    }

    public static final @NonNull Creator<SystemTextClassifierMetadata> CREATOR =
            new Creator<SystemTextClassifierMetadata>() {
        @Override
        public SystemTextClassifierMetadata createFromParcel(Parcel in) {
            return readFromParcel(in);
        }

        @Override
        public SystemTextClassifierMetadata[] newArray(int size) {
            return new SystemTextClassifierMetadata[size];
        }
    };
}
