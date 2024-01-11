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
import android.util.proto.ProtoOutputStream;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Contains power consumption data across the entire device.
 *
 * {@hide}
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class AggregateBatteryConsumer extends BatteryConsumer {
    static final int CONSUMER_TYPE_AGGREGATE = 0;

    static final int COLUMN_INDEX_SCOPE = BatteryConsumer.COLUMN_COUNT;
    static final int COLUMN_INDEX_CONSUMED_POWER = COLUMN_INDEX_SCOPE + 1;
    static final int COLUMN_COUNT = BatteryConsumer.COLUMN_COUNT + 2;

    AggregateBatteryConsumer(BatteryConsumerData data) {
        super(data);
    }

    private AggregateBatteryConsumer(@NonNull Builder builder) {
        super(builder.mData, builder.mPowerComponentsBuilder.build());
    }

    int getScope() {
        return mData.getInt(COLUMN_INDEX_SCOPE);
    }

    @Override
    public void dump(PrintWriter pw, boolean skipEmptyComponents) {
        mPowerComponents.dump(pw, skipEmptyComponents);
    }

    @Override
    public double getConsumedPower() {
        return mData.getDouble(COLUMN_INDEX_CONSUMED_POWER);
    }

    /** Serializes this object to XML */
    void writeToXml(TypedXmlSerializer serializer,
            @BatteryUsageStats.AggregateBatteryConsumerScope int scope) throws IOException {
        serializer.startTag(null, BatteryUsageStats.XML_TAG_AGGREGATE);
        serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_SCOPE, scope);
        serializer.attributeDouble(null, BatteryUsageStats.XML_ATTR_POWER, getConsumedPower());
        mPowerComponents.writeToXml(serializer);
        serializer.endTag(null, BatteryUsageStats.XML_TAG_AGGREGATE);
    }

    /** Parses an XML representation and populates the BatteryUsageStats builder */
    static void parseXml(TypedXmlPullParser parser, BatteryUsageStats.Builder builder)
            throws XmlPullParserException, IOException {
        final int scope = parser.getAttributeInt(null, BatteryUsageStats.XML_ATTR_SCOPE);
        final Builder consumerBuilder = builder.getAggregateBatteryConsumerBuilder(scope);

        int eventType = parser.getEventType();
        if (eventType != XmlPullParser.START_TAG || !parser.getName().equals(
                BatteryUsageStats.XML_TAG_AGGREGATE)) {
            throw new XmlPullParserException("Invalid XML parser state");
        }

        consumerBuilder.setConsumedPower(
                parser.getAttributeDouble(null, BatteryUsageStats.XML_ATTR_POWER));

        while (!(eventType == XmlPullParser.END_TAG && parser.getName().equals(
                BatteryUsageStats.XML_TAG_AGGREGATE))
                && eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.getName().equals(BatteryUsageStats.XML_TAG_POWER_COMPONENTS)) {
                    PowerComponents.parseXml(parser, consumerBuilder.mPowerComponentsBuilder);
                }
            }
            eventType = parser.next();
        }
    }

    void writePowerComponentModelProto(@NonNull ProtoOutputStream proto) {
        for (int i = 0; i < POWER_COMPONENT_COUNT; i++) {
            final int powerModel = getPowerModel(i);
            if (powerModel == BatteryConsumer.POWER_MODEL_UNDEFINED) continue;

            final long token = proto.start(BatteryUsageStatsAtomsProto.COMPONENT_MODELS);
            proto.write(BatteryUsageStatsAtomsProto.PowerComponentModel.COMPONENT, i);
            proto.write(BatteryUsageStatsAtomsProto.PowerComponentModel.POWER_MODEL,
                    powerModelToProtoEnum(powerModel));
            proto.end(token);
        }
    }

    /**
     * Builder for DeviceBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<AggregateBatteryConsumer.Builder> {
        public Builder(BatteryConsumer.BatteryConsumerData data, int scope,
                double minConsumedPowerThreshold) {
            super(data, CONSUMER_TYPE_AGGREGATE, minConsumedPowerThreshold);
            data.putInt(COLUMN_INDEX_SCOPE, scope);
        }

        /**
         * Sets the total power included in this aggregate.
         */
        public Builder setConsumedPower(double consumedPowerMah) {
            mData.putDouble(COLUMN_INDEX_CONSUMED_POWER, consumedPowerMah);
            return this;
        }

        /**
         * Adds power and usage duration from the supplied AggregateBatteryConsumer.
         */
        public void add(AggregateBatteryConsumer aggregateBatteryConsumer) {
            setConsumedPower(mData.getDouble(COLUMN_INDEX_CONSUMED_POWER)
                    + aggregateBatteryConsumer.getConsumedPower());
            mPowerComponentsBuilder.addPowerAndDuration(aggregateBatteryConsumer.mPowerComponents);
        }

        /**
         * Creates a read-only object out of the Builder values.
         */
        @NonNull
        public AggregateBatteryConsumer build() {
            return new AggregateBatteryConsumer(this);
        }
    }
}
