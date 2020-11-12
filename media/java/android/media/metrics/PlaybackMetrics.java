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

package android.media.metrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * This class is used to store playback data.
 * @hide
 */
public final class PlaybackMetrics implements Parcelable {
    private int mStreamSourceType;

    /**
     * Creates a new PlaybackMetrics.
     *
     * @hide
     */
    public PlaybackMetrics(int streamSourceType) {
        this.mStreamSourceType = streamSourceType;
    }

    public int getStreamSourceType() {
        return mStreamSourceType;
    }

    @Override
    public boolean equals(@Nullable Object o) {

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackMetrics that = (PlaybackMetrics) o;
        return mStreamSourceType == that.mStreamSourceType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStreamSourceType);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStreamSourceType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ PlaybackMetrics(@NonNull Parcel in) {
        int streamSourceType = in.readInt();
        this.mStreamSourceType = streamSourceType;
    }

    public static final @NonNull Parcelable.Creator<PlaybackMetrics> CREATOR =
            new Parcelable.Creator<PlaybackMetrics>() {
                @Override
                public PlaybackMetrics[] newArray(int size) {
                    return new PlaybackMetrics[size];
                }

                @Override
                public PlaybackMetrics createFromParcel(@NonNull Parcel in) {
                    return new PlaybackMetrics(in);
                }
            };
}
