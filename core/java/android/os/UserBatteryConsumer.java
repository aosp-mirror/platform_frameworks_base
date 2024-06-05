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

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

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
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class UserBatteryConsumer extends BatteryConsumer {
    static final int CONSUMER_TYPE_USER = 2;

    private static final int COLUMN_INDEX_USER_ID = BatteryConsumer.COLUMN_COUNT;

    static final int COLUMN_COUNT = BatteryConsumer.COLUMN_COUNT + 1;

    UserBatteryConsumer(BatteryConsumerData data) {
        super(data);
    }

    private UserBatteryConsumer(@NonNull UserBatteryConsumer.Builder builder) {
        super(builder.mData, builder.mPowerComponentsBuilder.build());
    }

    public int getUserId() {
        return mData.getInt(COLUMN_INDEX_USER_ID);
    }

    @Override
    public void dump(PrintWriter pw, boolean skipEmptyComponents) {
        final double consumedPower = getConsumedPower();
        pw.print("User ");
        pw.print(getUserId());
        pw.print(": ");
        pw.print(BatteryStats.formatCharge(consumedPower));
        pw.print(" ( ");
        mPowerComponents.dump(pw, skipEmptyComponents  /* skipTotalPowerComponent */);
        pw.print(" ) ");
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
        private List<UidBatteryConsumer.Builder> mUidBatteryConsumers;

        Builder(BatteryConsumerData data, int userId, double minConsumedPowerThreshold) {
            super(data, CONSUMER_TYPE_USER, minConsumedPowerThreshold);
            data.putLong(COLUMN_INDEX_USER_ID, userId);
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
