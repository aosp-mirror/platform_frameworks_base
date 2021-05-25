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
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.os.PowerCalculator;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
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

    @Override
    public void dump(PrintWriter pw, boolean skipEmptyComponents) {
        final double consumedPower = getConsumedPower();
        pw.print("User ");
        pw.print(mUserId);
        pw.print(": ");
        PowerCalculator.printPowerMah(pw, consumedPower);
        pw.print(" ( ");
        mPowerComponents.dump(pw, skipEmptyComponents  /* skipTotalPowerComponent */);
        pw.print(" ) ");
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

    /** Serializes this object to XML */
    void writeToXml(TypedXmlSerializer serializer) throws IOException {
        if (getConsumedPower() == 0) {
            return;
        }

        serializer.startTag(null, BatteryUsageStats.XML_TAG_USER);
        serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_USER_ID, getUserId());
        mPowerComponents.writeToXml(serializer);
        serializer.endTag(null, BatteryUsageStats.XML_TAG_USER);
    }

    /** Parses an XML representation and populates the BatteryUsageStats builder */
    static void createFromXml(TypedXmlPullParser parser, BatteryUsageStats.Builder builder)
            throws XmlPullParserException, IOException {
        final int userId = parser.getAttributeInt(null, BatteryUsageStats.XML_ATTR_USER_ID);
        final UserBatteryConsumer.Builder consumerBuilder =
                builder.getOrCreateUserBatteryConsumerBuilder(userId);

        int eventType = parser.getEventType();
        if (eventType != XmlPullParser.START_TAG
                || !parser.getName().equals(BatteryUsageStats.XML_TAG_USER)) {
            throw new XmlPullParserException("Invalid XML parser state");
        }
        while (!(eventType == XmlPullParser.END_TAG
                && parser.getName().equals(BatteryUsageStats.XML_TAG_USER))
                && eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.getName().equals(BatteryUsageStats.XML_TAG_POWER_COMPONENTS)) {
                    PowerComponents.parseXml(parser, consumerBuilder.mPowerComponentsBuilder);
                }
            }
            eventType = parser.next();
        }
    }

    /**
     * Builder for UserBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<Builder> {
        private final int mUserId;
        private List<UidBatteryConsumer.Builder> mUidBatteryConsumers;

        Builder(@NonNull String[] customPowerComponentNames, boolean includePowerModels,
                int userId) {
            super(customPowerComponentNames, includePowerModels);
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
