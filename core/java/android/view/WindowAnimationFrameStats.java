/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.view;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class contains window animation frame statistics. For example, a window
 * animation is usually performed when the application is transitioning from one
 * activity to another. The frame statistics are a snapshot for the time interval
 * from {@link #getStartTimeNano()} to {@link #getEndTimeNano()}.
 * <p>
 * The key idea is that in order to provide a smooth user experience the system should
 * run window animations at a specific time interval obtained by calling {@link
 * #getRefreshPeriodNano()}. If the system does not render a frame every refresh
 * period the user will see irregular window transitions. The time when the frame was
 * actually presented on the display by calling {@link #getFramePresentedTimeNano(int)}.
 */
public final class WindowAnimationFrameStats extends FrameStats implements Parcelable {
    /**
     * @hide
     */
    public WindowAnimationFrameStats() {
        /* do nothing */
    }

    /**
     * Initializes this isntance.
     *
     * @param refreshPeriodNano The display refresh period.
     * @param framesPresentedTimeNano The presented frame times.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void init(long refreshPeriodNano, long[] framesPresentedTimeNano) {
        mRefreshPeriodNano = refreshPeriodNano;
        mFramesPresentedTimeNano = framesPresentedTimeNano;
    }

    private WindowAnimationFrameStats(Parcel parcel) {
        mRefreshPeriodNano = parcel.readLong();
        mFramesPresentedTimeNano = parcel.createLongArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeLong(mRefreshPeriodNano);
        parcel.writeLongArray(mFramesPresentedTimeNano);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("WindowAnimationFrameStats[");
        builder.append("frameCount:" + getFrameCount());
        builder.append(", fromTimeNano:" + getStartTimeNano());
        builder.append(", toTimeNano:" + getEndTimeNano());
        builder.append(']');
        return builder.toString();
    }

    public static final @android.annotation.NonNull Creator<WindowAnimationFrameStats> CREATOR =
            new Creator<WindowAnimationFrameStats>() {
                @Override
                public WindowAnimationFrameStats createFromParcel(Parcel parcel) {
                    return new WindowAnimationFrameStats(parcel);
                }

                @Override
                public WindowAnimationFrameStats[] newArray(int size) {
                    return new WindowAnimationFrameStats[size];
                }
            };
}
