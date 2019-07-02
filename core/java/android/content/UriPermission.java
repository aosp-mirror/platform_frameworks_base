/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.content;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Description of a single Uri permission grant. This grants may have been
 * created via {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}, etc when sending
 * an {@link Intent}, or explicitly through
 * {@link Context#grantUriPermission(String, android.net.Uri, int)}.
 *
 * @see ContentResolver#getPersistedUriPermissions()
 */
public final class UriPermission implements Parcelable {
    private final Uri mUri;
    private final int mModeFlags;
    private final long mPersistedTime;

    /**
     * Value returned when a permission has not been persisted.
     */
    public static final long INVALID_TIME = Long.MIN_VALUE;

    /** {@hide} */
    public UriPermission(Uri uri, int modeFlags, long persistedTime) {
        mUri = uri;
        mModeFlags = modeFlags;
        mPersistedTime = persistedTime;
    }

    /** {@hide} */
    public UriPermission(Parcel in) {
        mUri = in.readParcelable(null);
        mModeFlags = in.readInt();
        mPersistedTime = in.readLong();
    }

    /**
     * Return the Uri this permission pertains to.
     */
    public Uri getUri() {
        return mUri;
    }

    /**
     * Returns if this permission offers read access.
     */
    public boolean isReadPermission() {
        return (mModeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
    }

    /**
     * Returns if this permission offers write access.
     */
    public boolean isWritePermission() {
        return (mModeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
    }

    /**
     * Return the time when this permission was first persisted, in milliseconds
     * since January 1, 1970 00:00:00.0 UTC. Returns {@link #INVALID_TIME} if
     * not persisted.
     *
     * @see ContentResolver#takePersistableUriPermission(Uri, int)
     * @see System#currentTimeMillis()
     */
    public long getPersistedTime() {
        return mPersistedTime;
    }

    @Override
    public String toString() {
        return "UriPermission {uri=" + mUri + ", modeFlags=" + mModeFlags + ", persistedTime="
                + mPersistedTime + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mUri, flags);
        dest.writeInt(mModeFlags);
        dest.writeLong(mPersistedTime);
    }

    public static final @android.annotation.NonNull Creator<UriPermission> CREATOR = new Creator<UriPermission>() {
        @Override
        public UriPermission createFromParcel(Parcel source) {
            return new UriPermission(source);
        }

        @Override
        public UriPermission[] newArray(int size) {
            return new UriPermission[size];
        }
    };
}
