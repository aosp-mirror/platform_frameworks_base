/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.window;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Stores information about a particular window that a {@link WindowContext} is attached to.
 * @hide
 */
public class WindowContextInfo implements Parcelable {

    /**
     * Configuration of the window.
     */
    @NonNull
    private final Configuration mConfiguration;

    /**
     * The display id that the window is attached to.
     */
    private final int mDisplayId;

    public WindowContextInfo(@NonNull Configuration configuration, int displayId) {
        mConfiguration = requireNonNull(configuration);
        mDisplayId = displayId;
    }

    @NonNull
    public Configuration getConfiguration() {
        return mConfiguration;
    }

    public int getDisplayId() {
        return mDisplayId;
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeInt(mDisplayId);
    }

    /** Reads from Parcel. */
    private WindowContextInfo(@NonNull Parcel in) {
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
        mDisplayId = in.readInt();
    }

    public static final @NonNull Creator<WindowContextInfo> CREATOR = new Creator<>() {
        public WindowContextInfo createFromParcel(Parcel in) {
            return new WindowContextInfo(in);
        }

        public WindowContextInfo[] newArray(int size) {
            return new WindowContextInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WindowContextInfo other = (WindowContextInfo) o;
        return Objects.equals(mConfiguration, other.mConfiguration)
                && mDisplayId == other.mDisplayId;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mConfiguration);
        result = 31 * result + mDisplayId;
        return result;
    }

    @Override
    public String toString() {
        return "WindowContextInfo{config=" + mConfiguration
                + ", displayId=" + mDisplayId
                + "}";
    }
}
