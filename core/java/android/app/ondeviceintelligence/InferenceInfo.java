/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class represents the information related to an inference event to track the resource usage
 * as a function of inference time.
 *
 * @hide
 */
public class InferenceInfo implements Parcelable {

    /**
     * Uid for the caller app.
     */
    private final int uid;

    /**
     * Inference start time (milliseconds from the epoch time).
     */
    private final long startTimeMs;

    /**
     * Inference end time (milliseconds from the epoch time).
     */
    private final long endTimeMs;

    /**
     * Suspended time in milliseconds.
     */
    private final long suspendedTimeMs;

    /**
     * Constructs an InferenceInfo object with the specified parameters.
     *
     * @param uid             Uid for the caller app.
     * @param startTimeMs     Inference start time (milliseconds from the epoch time).
     * @param endTimeMs       Inference end time (milliseconds from the epoch time).
     * @param suspendedTimeMs Suspended time in milliseconds.
     */
    public InferenceInfo(int uid, long startTimeMs, long endTimeMs,
            long suspendedTimeMs) {
        this.uid = uid;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.suspendedTimeMs = suspendedTimeMs;
    }

    /**
     * Constructs an InferenceInfo object from a Parcel.
     *
     * @param in The Parcel to read the object's data from.
     */
    protected InferenceInfo(Parcel in) {
        uid = in.readInt();
        startTimeMs = in.readLong();
        endTimeMs = in.readLong();
        suspendedTimeMs = in.readLong();
    }


    /**
     * Writes the object's data to the provided Parcel.
     *
     * @param dest The Parcel to write the object's data to.
     * @param flags Additional flags about how the object should be written.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeLong(startTimeMs);
        dest.writeLong(endTimeMs);
        dest.writeLong(suspendedTimeMs);
    }

    /**
     * Returns the UID for the caller app.
     *
     * @return the UID for the caller app.
     */
    public int getUid() {
        return uid;
    }

    /**
     * Returns the inference start time in milliseconds from the epoch time.
     *
     * @return the inference start time in milliseconds from the epoch time.
     */
    public long getStartTimeMs() {
        return startTimeMs;
    }

    /**
     * Returns the inference end time in milliseconds from the epoch time.
     *
     * @return the inference end time in milliseconds from the epoch time.
     */
    public long getEndTimeMs() {
        return endTimeMs;
    }

    /**
     * Returns the suspended time in milliseconds.
     *
     * @return the suspended time in milliseconds.
     */
    public long getSuspendedTimeMs() {
        return suspendedTimeMs;
    }

    @Override
    public int describeContents() {
        return 0;
    }


    public static final @android.annotation.NonNull Parcelable.Creator<InferenceInfo> CREATOR
            = new Parcelable.Creator<InferenceInfo>() {
        @Override
        public InferenceInfo[] newArray(int size) {
            return new InferenceInfo[size];
        }

        @Override
        public InferenceInfo createFromParcel(@android.annotation.NonNull Parcel in) {
            return new InferenceInfo(in);
        }
    };

    /**
     * Builder class for creating instances of {@link InferenceInfo}.
     */
    public static class Builder {
        private int uid;
        private long startTimeMs;
        private long endTimeMs;
        private long suspendedTimeMs;

        /**
         * Sets the UID for the caller app.
         *
         * @param uid the UID for the caller app.
         * @return the Builder instance.
         */
        public Builder setUid(int uid) {
            this.uid = uid;
            return this;
        }

        /**
         * Sets the inference start time in milliseconds from the epoch time.
         *
         * @param startTimeMs the inference start time in milliseconds from the epoch time.
         * @return the Builder instance.
         */
        public Builder setStartTimeMs(long startTimeMs) {
            this.startTimeMs = startTimeMs;
            return this;
        }

        /**
         * Sets the inference end time in milliseconds from the epoch time.
         *
         * @param endTimeMs the inference end time in milliseconds from the epoch time.
         * @return the Builder instance.
         */
        public Builder setEndTimeMs(long endTimeMs) {
            this.endTimeMs = endTimeMs;
            return this;
        }

        /**
         * Sets the suspended time in milliseconds.
         *
         * @param suspendedTimeMs the suspended time in milliseconds.
         * @return the Builder instance.
         */
        public Builder setSuspendedTimeMs(long suspendedTimeMs) {
            this.suspendedTimeMs = suspendedTimeMs;
            return this;
        }

        /**
         * Builds and returns an instance of {@link InferenceInfo}.
         *
         * @return an instance of {@link InferenceInfo}.
         */
        public InferenceInfo build() {
            return new InferenceInfo(uid, startTimeMs, endTimeMs,
                    suspendedTimeMs);
        }
    }
}
