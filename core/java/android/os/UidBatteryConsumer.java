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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains power consumption data attributed to a specific UID.
 *
 * @hide
 */
public final class UidBatteryConsumer extends BatteryConsumer implements Parcelable {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            STATE_FOREGROUND,
            STATE_BACKGROUND
    })
    public @interface State {
    }

    /**
     * The state of an application when it is either running a foreground (top) activity
     * or a foreground service.
     */
    public static final int STATE_FOREGROUND = 0;

    /**
     * The state of an application when it is running in the background, including the following
     * states:
     *
     * {@link android.app.ActivityManager#PROCESS_STATE_IMPORTANT_BACKGROUND},
     * {@link android.app.ActivityManager#PROCESS_STATE_TRANSIENT_BACKGROUND},
     * {@link android.app.ActivityManager#PROCESS_STATE_BACKUP},
     * {@link android.app.ActivityManager#PROCESS_STATE_SERVICE},
     * {@link android.app.ActivityManager#PROCESS_STATE_RECEIVER}.
     */
    public static final int STATE_BACKGROUND = 1;

    private final int mUid;
    @Nullable
    private final String mPackageWithHighestDrain;
    private final long mTimeInForegroundMs;
    private final long mTimeInBackgroundMs;

    public int getUid() {
        return mUid;
    }

    @Nullable
    public String getPackageWithHighestDrain() {
        return mPackageWithHighestDrain;
    }

    /**
     * Returns the amount of time in milliseconds this UID spent in the specified state.
     */
    public long getTimeInStateMs(@State int state) {
        switch (state) {
            case STATE_BACKGROUND:
                return mTimeInBackgroundMs;
            case STATE_FOREGROUND:
                return mTimeInForegroundMs;
        }
        return 0;
    }

    private UidBatteryConsumer(@NonNull Builder builder) {
        super(builder.mPowerComponentsBuilder.build());
        mUid = builder.mUid;
        mPackageWithHighestDrain = builder.mPackageWithHighestDrain;
        mTimeInForegroundMs = builder.mTimeInForegroundMs;
        mTimeInBackgroundMs = builder.mTimeInBackgroundMs;
    }

    private UidBatteryConsumer(@NonNull Parcel source) {
        super(new PowerComponents(source));
        mUid = source.readInt();
        mPackageWithHighestDrain = source.readString();
        mTimeInForegroundMs = source.readLong();
        mTimeInBackgroundMs = source.readLong();
    }

    /**
     * Writes the contents into a Parcel.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mUid);
        dest.writeString(mPackageWithHighestDrain);
        dest.writeLong(mTimeInForegroundMs);
        dest.writeLong(mTimeInBackgroundMs);
    }

    @NonNull
    public static final Creator<UidBatteryConsumer> CREATOR = new Creator<UidBatteryConsumer>() {
        public UidBatteryConsumer createFromParcel(@NonNull Parcel source) {
            return new UidBatteryConsumer(source);
        }

        public UidBatteryConsumer[] newArray(int size) {
            return new UidBatteryConsumer[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Builder for UidBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<Builder> {
        private final BatteryStats.Uid mBatteryStatsUid;
        private final int mUid;
        private String mPackageWithHighestDrain;
        public long mTimeInForegroundMs;
        public long mTimeInBackgroundMs;
        private boolean mExcludeFromBatteryUsageStats;

        public Builder(int customPowerComponentCount, int customTimeComponentCount,
                boolean includePowerModels, @NonNull BatteryStats.Uid batteryStatsUid) {
            super(customPowerComponentCount, customTimeComponentCount, includePowerModels);
            mBatteryStatsUid = batteryStatsUid;
            mUid = batteryStatsUid.getUid();
        }

        @NonNull
        public BatteryStats.Uid getBatteryStatsUid() {
            return mBatteryStatsUid;
        }

        public int getUid() {
            return mUid;
        }

        /**
         * Sets the name of the package owned by this UID that consumed the highest amount
         * of power since BatteryStats reset.
         */
        @NonNull
        public Builder setPackageWithHighestDrain(@Nullable String packageName) {
            mPackageWithHighestDrain = packageName;
            return this;
        }

        /**
         * Sets the duration, in milliseconds, that this UID was active in a particular state,
         * such as foreground or background.
         */
        @NonNull
        public Builder setTimeInStateMs(@State int state, long timeInStateMs) {
            switch (state) {
                case STATE_FOREGROUND:
                    mTimeInForegroundMs = timeInStateMs;
                    break;
                case STATE_BACKGROUND:
                    mTimeInBackgroundMs = timeInStateMs;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported state: " + state);
            }
            return this;
        }

        /**
         * Marks the UidBatteryConsumer for exclusion from the result set.
         */
        public Builder excludeFromBatteryUsageStats() {
            mExcludeFromBatteryUsageStats = true;
            return this;
        }

        /**
         * Returns true if this UidBatteryConsumer must be excluded from the
         * BatteryUsageStats.
         */
        public boolean isExcludedFromBatteryUsageStats() {
            return mExcludeFromBatteryUsageStats;
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public UidBatteryConsumer build() {
            return new UidBatteryConsumer(this);
        }
    }
}
