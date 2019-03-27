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
import android.content.LocusId;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines a condition for when content capture should be allowed.
 *
 * <p>See {@link ContentCaptureManager#getContentCaptureConditions()} for more.
 */
public final class ContentCaptureCondition implements Parcelable {

    /**
     * When set, package should use the {@link LocusId#getId()} as a regular expression.
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
     * @param flags either {@link ContentCaptureCondition#FLAG_IS_REGEX} or {@code 0}.
     */
    public ContentCaptureCondition(@NonNull LocusId locusId, @Flags int flags) {
        this.mLocusId = Preconditions.checkNotNull(locusId);
        this.mFlags = flags;
        // TODO(b/129267994): check flags, add test case for null and invalid flags
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
                    return new ContentCaptureCondition(parcel.readParcelable(null),
                            parcel.readInt());
                }

                @Override
                public ContentCaptureCondition[] newArray(int size) {
                    return new ContentCaptureCondition[size];
                }
    };
}
