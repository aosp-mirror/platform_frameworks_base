/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os.storage;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.util.UUID;

/**
 * The CrateInfo describe the crate information.
 * <p>
 *      It describe the following items.
 *      <ul>
 *          <li>The crate id that is the name of the child directory in
 *          {@link Context#getCrateDir(String)}</li>
 *          <li>Label to provide human readable text for the users.</li>
 *          <li>Expiration information. When the crate is expired and the run .</li>
 *
 *      </ul>for the directory
 * </p>
 * @hide
 */
@TestApi
public final class CrateInfo implements Parcelable {
    private static final String TAG = "CrateInfo";

    /**
     * The following member fields whose value are set by apps and retrieved by system_server.
     */
    private CharSequence mLabel;
    @CurrentTimeMillisLong
    private long mExpiration;

    /**
     * The following member fields whose value are retrieved by installd.
     * <p>{@link android.app.usage.StorageStatsManager#queryCratesForUser(UUID, UserHandle)} query
     * all of crates for the specified UserHandle. That means the return crate list whose elements
     * may have the same userId but different package name. Each crate needs the information to tell
     * the caller from where package comes.
     * </p>
     */
    private int mUid;

    /**
     * The following member fields whose value are retrieved by installd.
     * <p>Both {@link StorageStatsManager#queryCratesForUid(UUID, int)} and
     * {@link android.app.usage.StorageStatsManager#queryCratesForUser(UUID, UserHandle)} query
     * all of crates for the specified uid or userId. That means the return crate list whose
     * elements may have the same uid or userId but different package name. Each crate needs the
     * information to tell the caller from where package comes.
     * </p>
     */
    @Nullable
    private String mPackageName;

    /**
     * The following member fields whose value are retrieved by system_server.
     * <p>
     *     The child directories in {@link Context#getCrateDir(String)} are crates. Each directories
     *     is a crate. The folder name is the crate id.
     * </p><p>
     *     Can't apply check if the path is validated or not because it need pass through the
     *     parcel.
     * </p>
     */
    @Nullable
    private String mId;

    private CrateInfo() {
        mExpiration = 0;
    }

    /**
     * To create the crateInfo by passing validated label.
     * @param label a display name for the crate
     * @param expiration It's positive integer. if current time is larger than the expiration, the
     *                  files under this crate will be considered to be deleted. Default value is 0.
     * @throws IllegalArgumentException cause IllegalArgumentException when label is null
     *      or empty string
     */
    public CrateInfo(@NonNull CharSequence label, @CurrentTimeMillisLong long expiration) {
        Preconditions.checkStringNotEmpty(label,
                "Label should not be either null or empty string");
        Preconditions.checkArgumentNonnegative(expiration,
                "Expiration should be non negative number");

        mLabel = label;
        mExpiration = expiration;
    }

    /**
     * To create the crateInfo by passing validated label.
     * @param label a display name for the crate
     * @throws IllegalArgumentException cause IllegalArgumentException when label is null
     *      or empty string
     */
    public CrateInfo(@NonNull CharSequence label) {
        this(label, 0);
    }

    /**
     * To get the meaningful text of the crate for the users.
     * @return the meaningful text
     */
    @NonNull
    public CharSequence getLabel() {
        if (TextUtils.isEmpty(mLabel)) {
            return mId;
        }
        return mLabel;
    }


    /**
     * To return the expiration time.
     * <p>
     *     If the current time is larger than expiration time, the crate files are considered to be
     *     deleted.
     * </p>
     * @return the expiration time
     */
    @CurrentTimeMillisLong
    public long getExpirationMillis() {
        return mExpiration;
    }

    /**
     * To set the expiration time.
     * @param expiration the expiration time
     * @hide
     */
    public void setExpiration(@CurrentTimeMillisLong long expiration) {
        Preconditions.checkArgumentNonnegative(expiration);
        mExpiration = expiration;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * To compare with crateinfo when selves' mId is validated.
     * <p>The validated crateinfo.mId must be validated the following items.
     * <ul>
     *     <li>mId is not null</li>
     *     <li>mId is not empty string</li>
     * </ul>
     * </p>
     * @param   obj   the reference object with which to compare.
     * @return true when selves's mId is validated and equal to crateinfo.mId.
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null) {
            return false;
        }

        if (obj instanceof CrateInfo) {
            CrateInfo crateInfo = (CrateInfo) obj;
            if (!TextUtils.isEmpty(mId)
                    && TextUtils.equals(mId, crateInfo.mId)) {
                return true;
            }
        }

        return super.equals(obj);
    }



    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@Nullable Parcel dest, int flags) {
        if (dest == null) {
            return;
        }

        dest.writeCharSequence(mLabel);
        dest.writeLong(mExpiration);

        dest.writeInt(mUid);
        dest.writeString(mPackageName);
        dest.writeString(mId);
    }

    /**
     * To read the data from parcel.
     * <p>
     *     It's called by StorageStatsService.
     * </p>
     * @hide
     */
    public void readFromParcel(@Nullable Parcel in) {
        if (in == null) {
            return;
        }

        mLabel = in.readCharSequence();
        mExpiration = in.readLong();

        mUid = in.readInt();
        mPackageName = in.readString();
        mId = in.readString();
    }

    @NonNull
    public static final Creator<CrateInfo> CREATOR = new Creator<CrateInfo>() {
        @NonNull
        @Override
        public CrateInfo createFromParcel(@NonNull Parcel in) {
            CrateInfo crateInfo = new CrateInfo();
            crateInfo.readFromParcel(in);
            return crateInfo;
        }

        @NonNull
        @Override
        public CrateInfo[] newArray(int size) {
            return new CrateInfo[size];
        }
    };

    /**
     * To copy the information from service into crateinfo.
     * <p>
     * This function is called in system_server. The copied information includes
     *     <ul>
     *         <li>uid</li>
     *         <li>package name</li>
     *         <li>crate id</li>
     *     </ul>
     * </p>
     * @param uid the uid that the crate belong to
     * @param packageName the package name that the crate belong to
     * @param id the crate dir
     * @return the CrateInfo instance
     * @hide
     */
    @TestApi
    @Nullable
    public static CrateInfo copyFrom(int uid, @Nullable String packageName, @Nullable String id) {
        if (!UserHandle.isApp(uid) || TextUtils.isEmpty(packageName) || TextUtils.isEmpty(id)) {
            return null;
        }

        CrateInfo crateInfo = new CrateInfo(id /* default label = id */, 0);
        crateInfo.mUid = uid;
        crateInfo.mPackageName = packageName;
        crateInfo.mId = id;
        return crateInfo;
    }
}
