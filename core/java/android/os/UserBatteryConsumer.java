/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains power consumption data attributed to a {@link UserHandle}.
 *
 * {@hide}
 */
public class UserBatteryConsumer extends BatteryConsumer implements Parcelable {
    private final int mUserId;

    public int getUserId() {
        return mUserId;
    }

    private UserBatteryConsumer(@NonNull UserBatteryConsumer.Builder builder) {
        super(builder.mPowerComponentsBuilder.build());
        mUserId = builder.mUserId;
    }

    private UserBatteryConsumer(Parcel in) {
        super(new PowerComponents(in));
        mUserId = in.readInt();
    }

    /**
     * Writes the contents into a Parcel.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mUserId);
    }

    public static final Creator<UserBatteryConsumer> CREATOR =
            new Creator<UserBatteryConsumer>() {
                @Override
                public UserBatteryConsumer createFromParcel(Parcel in) {
                    return new UserBatteryConsumer(in);
                }

                @Override
                public UserBatteryConsumer[] newArray(int size) {
                    return new UserBatteryConsumer[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Builder for UserBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<Builder> {
        private final int mUserId;
        private List<UidBatteryConsumer.Builder> mUidBatteryConsumers;

        Builder(int customPowerComponentCount, int customTimeComponentCount,
                boolean includePowerModels, int userId) {
            super(customPowerComponentCount, customTimeComponentCount, includePowerModels);
            mUserId = userId;
        }

        /**
         * Add a UidBatteryConsumer to this UserBatteryConsumer.
         * <p>
         * Calculated power and duration components of the added UID battery consumers
         * are aggregated at the time the UserBatteryConsumer is built by the {@link #build()}
         * method.
         * </p>
         */
        public void addUidBatteryConsumer(UidBatteryConsumer.Builder uidBatteryConsumerBuilder) {
            if (mUidBatteryConsumers == null) {
                mUidBatteryConsumers = new ArrayList<>();
            }
            mUidBatteryConsumers.add(uidBatteryConsumerBuilder);
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public UserBatteryConsumer build() {
            if (mUidBatteryConsumers != null) {
                for (int i = mUidBatteryConsumers.size() - 1; i >= 0; i--) {
                    UidBatteryConsumer.Builder uidBatteryConsumer = mUidBatteryConsumers.get(i);
                    mPowerComponentsBuilder.addPowerAndDuration(
                            uidBatteryConsumer.mPowerComponentsBuilder);
                }
            }
            return new UserBatteryConsumer(this);
        }
    }
}
