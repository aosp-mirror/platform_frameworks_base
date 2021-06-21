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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A class containing information about a pending system update.
 */
public final class SystemUpdateInfo implements Parcelable {
    private static final String TAG = "SystemUpdateInfo";

    /**
     * Represents it is unknown whether the system update is a security patch.
     */
    public static final int SECURITY_PATCH_STATE_UNKNOWN = 0;

    /**
     * Represents the system update is not a security patch.
     */
    public static final int SECURITY_PATCH_STATE_FALSE = 1;

    /**
     * Represents the system update is a security patch.
     */
    public static final int SECURITY_PATCH_STATE_TRUE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SECURITY_PATCH_STATE_" }, value = {
            SECURITY_PATCH_STATE_FALSE,
            SECURITY_PATCH_STATE_TRUE,
            SECURITY_PATCH_STATE_UNKNOWN
    })
    public @interface SecurityPatchState {}

    private static final String ATTR_RECEIVED_TIME = "received-time";
    private static final String ATTR_SECURITY_PATCH_STATE = "security-patch-state";
    // Tag used to store original build fingerprint to detect when the update is applied.
    private static final String ATTR_ORIGINAL_BUILD = "original-build";

    private final long mReceivedTime;
    @SecurityPatchState
    private final int mSecurityPatchState;

    private SystemUpdateInfo(long receivedTime, @SecurityPatchState int securityPatchState) {
        this.mReceivedTime = receivedTime;
        this.mSecurityPatchState = securityPatchState;
    }

    private SystemUpdateInfo(Parcel in) {
        mReceivedTime = in.readLong();
        mSecurityPatchState = in.readInt();
    }

    /** @hide */
    @Nullable
    public static SystemUpdateInfo of(long receivedTime) {
        return receivedTime == -1
                ? null : new SystemUpdateInfo(receivedTime, SECURITY_PATCH_STATE_UNKNOWN);
    }

    /** @hide */
    @Nullable
    public static SystemUpdateInfo of(long receivedTime, boolean isSecurityPatch) {
        return receivedTime == -1 ? null : new SystemUpdateInfo(receivedTime,
                isSecurityPatch ? SECURITY_PATCH_STATE_TRUE : SECURITY_PATCH_STATE_FALSE);
    }

    /**
     * Gets time when the update was first available in milliseconds since midnight, January 1,
     * 1970 UTC.
     * @return Time in milliseconds as given by {@link System#currentTimeMillis()}
     */
    public long getReceivedTime() {
        return mReceivedTime;
    }

    /**
     * Gets whether the update is a security patch.
     * @return {@link #SECURITY_PATCH_STATE_FALSE}, {@link #SECURITY_PATCH_STATE_TRUE}, or
     *         {@link #SECURITY_PATCH_STATE_UNKNOWN}.
     */
    @SecurityPatchState
    public int getSecurityPatchState() {
        return mSecurityPatchState;
    }

    public static final @android.annotation.NonNull Creator<SystemUpdateInfo> CREATOR =
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

    /** @hide */
    public void writeToXml(TypedXmlSerializer out, String tag) throws IOException {
        out.startTag(null, tag);
        out.attributeLong(null, ATTR_RECEIVED_TIME, mReceivedTime);
        out.attributeInt(null, ATTR_SECURITY_PATCH_STATE, mSecurityPatchState);
        out.attribute(null, ATTR_ORIGINAL_BUILD , Build.FINGERPRINT);
        out.endTag(null, tag);
    }

    /** @hide */
    @Nullable
    public static SystemUpdateInfo readFromXml(TypedXmlPullParser parser) {
        // If an OTA has been applied (build fingerprint has changed), discard stale info.
        final String buildFingerprint = parser.getAttributeValue(null, ATTR_ORIGINAL_BUILD );
        if (!Build.FINGERPRINT.equals(buildFingerprint)) {
            return null;
        }
        try {
            final long receivedTime =
                    parser.getAttributeLong(null, ATTR_RECEIVED_TIME);
            final int securityPatchState =
                    parser.getAttributeInt(null, ATTR_SECURITY_PATCH_STATE);
            return new SystemUpdateInfo(receivedTime, securityPatchState);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Load xml failed", e);
            return null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(getReceivedTime());
        dest.writeInt(getSecurityPatchState());
    }

    @Override
    public String toString() {
        return String.format("SystemUpdateInfo (receivedTime = %d, securityPatchState = %s)",
                mReceivedTime, securityPatchStateToString(mSecurityPatchState));
    }

    private static String securityPatchStateToString(@SecurityPatchState int state) {
        switch (state) {
            case SECURITY_PATCH_STATE_FALSE:
                return "false";
            case SECURITY_PATCH_STATE_TRUE:
                return "true";
            case SECURITY_PATCH_STATE_UNKNOWN:
                return "unknown";
            default:
                throw new IllegalArgumentException("Unrecognized security patch state: " + state);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SystemUpdateInfo that = (SystemUpdateInfo) o;
        return mReceivedTime == that.mReceivedTime
                && mSecurityPatchState == that.mSecurityPatchState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mReceivedTime, mSecurityPatchState);
    }
}
