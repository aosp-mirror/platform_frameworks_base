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

package android.app.admin;

import android.annotation.Nullable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Objects;

/**
 * A class containing information about a pending system update.
 */
public final class SystemUpdateInfo implements Parcelable {
    private static final String ATTR_RECEIVED_TIME = "mReceivedTime";
    // Tag used to store original build fingerprint to detect when the update is applied.
    private static final String ATTR_ORIGINAL_BUILD = "originalBuild";
    private final long mReceivedTime;

    private SystemUpdateInfo(long receivedTime) {
        this.mReceivedTime = receivedTime;
    }

    private SystemUpdateInfo(Parcel in) {
        mReceivedTime = in.readLong();
    }

    /**
     * @hide
     */
    @Nullable
    public static SystemUpdateInfo of(long receivedTime) {
        return receivedTime == -1 ? null : new SystemUpdateInfo(receivedTime);
    }

    /**
     * Get time when the update was first available.
     * @return time as given by {@link System#currentTimeMillis()}
     */
    public long getReceivedTime() {
        return mReceivedTime;
    }

    public static final Creator<SystemUpdateInfo> CREATOR =
            new Creator<SystemUpdateInfo>() {
                @Override
                public SystemUpdateInfo createFromParcel(Parcel in) {
                    return new SystemUpdateInfo(in);
                }

                @Override
                public SystemUpdateInfo[] newArray(int size) {
                    return new SystemUpdateInfo[size];
                }
            };

    /**
     * @hide
     */
    public void writeToXml(XmlSerializer out, String tag) throws IOException {
        out.startTag(null, tag);
        out.attribute(null, ATTR_RECEIVED_TIME, String.valueOf(mReceivedTime));
        out.attribute(null, ATTR_ORIGINAL_BUILD , Build.FINGERPRINT);
        out.endTag(null, tag);
    }

    /**
     * @hide
     */
    @Nullable
    public static SystemUpdateInfo readFromXml(XmlPullParser parser) {
        // If an OTA has been applied (build fingerprint has changed), discard stale info.
        final String buildFingerprint = parser.getAttributeValue(null, ATTR_ORIGINAL_BUILD );
        if (!Build.FINGERPRINT.equals(buildFingerprint)) {
            return null;
        }
        final long receivedTime =
                Long.parseLong(parser.getAttributeValue(null, ATTR_RECEIVED_TIME));
        return of(receivedTime);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(getReceivedTime());
    }

    @Override
    public String toString() {
        return String.format("SystemUpdateInfo (receivedTime = %d)", mReceivedTime);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SystemUpdateInfo that = (SystemUpdateInfo) o;
        return mReceivedTime == that.mReceivedTime;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mReceivedTime);
    }
}
