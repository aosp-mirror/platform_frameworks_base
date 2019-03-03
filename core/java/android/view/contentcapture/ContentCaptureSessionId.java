/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;

/**
 * Identifier for a Content Capture session.
 */
public final class ContentCaptureSessionId implements Parcelable {

    private final @NonNull String mValue;

    /**
     * Creates a new instance.
     *
     * @param value The internal value.
     *
     * @hide
     */
    public ContentCaptureSessionId(@NonNull String value) {
        mValue = value;
    }

    /**
     * @hide
     */
    public String getValue() {
        return mValue;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mValue == null) ? 0 : mValue.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final ContentCaptureSessionId other = (ContentCaptureSessionId) obj;
        if (mValue == null) {
            if (other.mValue != null) return false;
        } else if (!mValue.equals(other.mValue)) {
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>NOTE: </b>this method is only useful for debugging purposes and is not guaranteed to
     * be stable, hence it should not be used to identify the session.
     */
    @Override
    public String toString() {
        return mValue;
    }

    /** @hide */
    // TODO(b/111276913): dump to proto as well
    public void dump(PrintWriter pw) {
        pw.print(mValue);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mValue);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ContentCaptureSessionId> CREATOR =
            new Parcelable.Creator<ContentCaptureSessionId>() {

        @Override
        @NonNull
        public ContentCaptureSessionId createFromParcel(Parcel parcel) {
            return new ContentCaptureSessionId(parcel.readString());
        }

        @Override
        @NonNull
        public ContentCaptureSessionId[] newArray(int size) {
            return new ContentCaptureSessionId[size];
        }
    };
}
