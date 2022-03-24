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
package android.view.contentcapture;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.LocusId;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DebugUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Defines a condition for when content capture should be allowed.
 *
 * <p>See {@link ContentCaptureManager#getContentCaptureConditions()} for more.
 */
public final class ContentCaptureCondition implements Parcelable {

    /**
     * When set, package should use the {@link LocusId#getId()} as a regular expression (using the
     * {@link java.util.regex.Pattern} format).
     */
    public static final int FLAG_IS_REGEX = 0x2;

    /** @hide */
    @IntDef(prefix = { "FLAG" }, flag = true, value = {
            FLAG_IS_REGEX
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Flags {}

    private final @NonNull LocusId mLocusId;
    private final @Flags int mFlags;

    /**
     * Default constructor.
     *
     * @param locusId id of the condition, as defined by
     * {@link ContentCaptureContext#getLocusId()}.
     * @param flags either {@link ContentCaptureCondition#FLAG_IS_REGEX} (to use a regular
     * expression match) or {@code 0} (in which case the {@code LocusId} must be an exact match of
     * the {@code LocusId} used in the {@link ContentCaptureContext}).
     */
    public ContentCaptureCondition(@NonNull LocusId locusId, @Flags int flags) {
        this.mLocusId = Objects.requireNonNull(locusId);
        this.mFlags = flags;
    }

    /**
     * Gets the {@code LocusId} per se.
     */
    @NonNull
    public LocusId getLocusId() {
        return mLocusId;
    }

    /**
     * Gets the flags associates with this condition.
     *
     * @return either {@link ContentCaptureCondition#FLAG_IS_REGEX} or {@code 0}.
     */
    public @Flags int getFlags() {
        return mFlags;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mFlags;
        result = prime * result + ((mLocusId == null) ? 0 : mLocusId.hashCode());
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final ContentCaptureCondition other = (ContentCaptureCondition) obj;
        if (mFlags != other.mFlags) return false;
        if (mLocusId == null) {
            if (other.mLocusId != null) return false;
        } else {
            if (!mLocusId.equals(other.mLocusId)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder string = new StringBuilder(mLocusId.toString()); // LocusID is PII safe
        if (mFlags != 0) {
            string
                .append(" (")
                .append(DebugUtils.flagsToString(ContentCaptureCondition.class, "FLAG_", mFlags))
                .append(')');
        }
        return string.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeParcelable(mLocusId, flags);
        parcel.writeInt(mFlags);
    }

    public static final @NonNull Parcelable.Creator<ContentCaptureCondition> CREATOR =
            new Parcelable.Creator<ContentCaptureCondition>() {

                @Override
                public ContentCaptureCondition createFromParcel(@NonNull Parcel parcel) {
                    return new ContentCaptureCondition(parcel.readParcelable(null, android.content.LocusId.class),
                            parcel.readInt());
                }

                @Override
                public ContentCaptureCondition[] newArray(int size) {
                    return new ContentCaptureCondition[size];
                }
    };
}
