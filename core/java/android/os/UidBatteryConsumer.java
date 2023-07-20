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
import android.text.TextUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.os.PowerCalculator;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains power consumption data attributed to a specific UID.
 *
 * @hide
 */
public final class UidBatteryConsumer extends BatteryConsumer {

    static final int CONSUMER_TYPE_UID = 1;

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

    static final int COLUMN_INDEX_UID = BatteryConsumer.COLUMN_COUNT;
    static final int COLUMN_INDEX_PACKAGE_WITH_HIGHEST_DRAIN = COLUMN_INDEX_UID + 1;
    static final int COLUMN_INDEX_TIME_IN_FOREGROUND = COLUMN_INDEX_UID + 2;
    static final int COLUMN_INDEX_TIME_IN_BACKGROUND = COLUMN_INDEX_UID + 3;
    static final int COLUMN_COUNT = BatteryConsumer.COLUMN_COUNT + 4;

    UidBatteryConsumer(BatteryConsumerData data) {
        super(data);
    }

    private UidBatteryConsumer(@NonNull Builder builder) {
        super(builder.mData, builder.mPowerComponentsBuilder.build());
    }

    public int getUid() {
        return mData.getInt(COLUMN_INDEX_UID);
    }

    @Nullable
    public String getPackageWithHighestDrain() {
        return mData.getString(COLUMN_INDEX_PACKAGE_WITH_HIGHEST_DRAIN);
    }

    /**
     * Returns the amount of time in milliseconds this UID spent in the specified state.
     */
    public long getTimeInStateMs(@State int state) {
        switch (state) {
            case STATE_BACKGROUND:
                return mData.getInt(COLUMN_INDEX_TIME_IN_BACKGROUND);
            case STATE_FOREGROUND:
                return mData.getInt(COLUMN_INDEX_TIME_IN_FOREGROUND);
        }
        return 0;
    }

    @Override
    public void dump(PrintWriter pw, boolean skipEmptyComponents) {
        pw.print("UID ");
        UserHandle.formatUid(pw, getUid());
        pw.print(": ");
        PowerCalculator.printPowerMah(pw, getConsumedPower());

        if (mData.layout.processStateDataIncluded) {
            StringBuilder sb = new StringBuilder();
            appendProcessStateData(sb, BatteryConsumer.PROCESS_STATE_FOREGROUND,
                    skipEmptyComponents);
            appendProcessStateData(sb, BatteryConsumer.PROCESS_STATE_BACKGROUND,
                    skipEmptyComponents);
            appendProcessStateData(sb, BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE,
                    skipEmptyComponents);
            appendProcessStateData(sb, BatteryConsumer.PROCESS_STATE_CACHED,
                    skipEmptyComponents);
            pw.print(sb);
        }

        pw.print(" ( ");
        mPowerComponents.dump(pw, skipEmptyComponents  /* skipTotalPowerComponent */);
        pw.print(" ) ");
    }

    private void appendProcessStateData(StringBuilder sb, @ProcessState int processState,
            boolean skipEmptyComponents) {
        Dimensions dimensions = new Dimensions(POWER_COMPONENT_ANY, processState);
        final double power = mPowerComponents.getConsumedPower(dimensions);
        if (power == 0 && skipEmptyComponents) {
            return;
        }

        sb.append(" ").append(processStateToString(processState)).append(": ")
                .append(BatteryStats.formatCharge(power));
    }

    static UidBatteryConsumer create(BatteryConsumerData data) {
        return new UidBatteryConsumer(data);
    }

    /** Serializes this object to XML */
    void writeToXml(TypedXmlSerializer serializer) throws IOException {
        if (getConsumedPower() == 0) {
            return;
        }

        serializer.startTag(null, BatteryUsageStats.XML_TAG_UID);
        serializer.attributeInt(null, BatteryUsageStats.XML_ATTR_UID, getUid());
        final String packageWithHighestDrain = getPackageWithHighestDrain();
        if (!TextUtils.isEmpty(packageWithHighestDrain)) {
            serializer.attribute(null, BatteryUsageStats.XML_ATTR_HIGHEST_DRAIN_PACKAGE,
                    packageWithHighestDrain);
        }
        serializer.attributeLong(null, BatteryUsageStats.XML_ATTR_TIME_IN_FOREGROUND,
                getTimeInStateMs(STATE_FOREGROUND));
        serializer.attributeLong(null, BatteryUsageStats.XML_ATTR_TIME_IN_BACKGROUND,
                getTimeInStateMs(STATE_BACKGROUND));
        mPowerComponents.writeToXml(serializer);
        serializer.endTag(null, BatteryUsageStats.XML_TAG_UID);
    }

    /** Parses an XML representation and populates the BatteryUsageStats builder */
    static void createFromXml(TypedXmlPullParser parser, BatteryUsageStats.Builder builder)
            throws XmlPullParserException, IOException {
        final int uid = parser.getAttributeInt(null, BatteryUsageStats.XML_ATTR_UID);
        final UidBatteryConsumer.Builder consumerBuilder =
                builder.getOrCreateUidBatteryConsumerBuilder(uid);

        int eventType = parser.getEventType();
        if (eventType != XmlPullParser.START_TAG
                || !parser.getName().equals(BatteryUsageStats.XML_TAG_UID)) {
            throw new XmlPullParserException("Invalid XML parser state");
        }

        consumerBuilder.setPackageWithHighestDrain(
                parser.getAttributeValue(null, BatteryUsageStats.XML_ATTR_HIGHEST_DRAIN_PACKAGE));
        consumerBuilder.setTimeInStateMs(STATE_FOREGROUND,
                parser.getAttributeLong(null, BatteryUsageStats.XML_ATTR_TIME_IN_FOREGROUND));
        consumerBuilder.setTimeInStateMs(STATE_BACKGROUND,
                parser.getAttributeLong(null, BatteryUsageStats.XML_ATTR_TIME_IN_BACKGROUND));
        while (!(eventType == XmlPullParser.END_TAG
                && parser.getName().equals(BatteryUsageStats.XML_TAG_UID))
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
     * Builder for UidBatteryConsumer.
     */
    public static final class Builder extends BaseBuilder<Builder> {
        private static final String PACKAGE_NAME_UNINITIALIZED = "";
        private final BatteryStats.Uid mBatteryStatsUid;
        private final int mUid;
        private final boolean mIsVirtualUid;
        private String mPackageWithHighestDrain = PACKAGE_NAME_UNINITIALIZED;
        private boolean mExcludeFromBatteryUsageStats;

        public Builder(BatteryConsumerData data, @NonNull BatteryStats.Uid batteryStatsUid) {
            this(data, batteryStatsUid, batteryStatsUid.getUid());
        }

        public Builder(BatteryConsumerData data, int uid) {
            this(data, null, uid);
        }

        private Builder(BatteryConsumerData data, @Nullable BatteryStats.Uid batteryStatsUid,
                int uid) {
            super(data, CONSUMER_TYPE_UID);
            mBatteryStatsUid = batteryStatsUid;
            mUid = uid;
            mIsVirtualUid = mUid == Process.SDK_SANDBOX_VIRTUAL_UID;
            data.putLong(COLUMN_INDEX_UID, mUid);
        }

        @NonNull
        public BatteryStats.Uid getBatteryStatsUid() {
            if (mBatteryStatsUid == null) {
                throw new IllegalStateException(
                        "UidBatteryConsumer.Builder was initialized without a BatteryStats.Uid");
            }
            return mBatteryStatsUid;
        }

        public int getUid() {
            return mUid;
        }

        public boolean isVirtualUid() {
            return mIsVirtualUid;
        }

        /**
         * Sets the name of the package owned by this UID that consumed the highest amount
         * of power since BatteryStats reset.
         */
        @NonNull
        public Builder setPackageWithHighestDrain(@Nullable String packageName) {
            mPackageWithHighestDrain = TextUtils.nullIfEmpty(packageName);
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
                    mData.putLong(COLUMN_INDEX_TIME_IN_FOREGROUND, timeInStateMs);
                    break;
                case STATE_BACKGROUND:
                    mData.putLong(COLUMN_INDEX_TIME_IN_BACKGROUND, timeInStateMs);
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
         * Adds power and usage duration from the supplied UidBatteryConsumer.
         */
        public Builder add(UidBatteryConsumer consumer) {
            mPowerComponentsBuilder.addPowerAndDuration(consumer.mPowerComponents);

            setTimeInStateMs(STATE_FOREGROUND,
                    mData.getLong(COLUMN_INDEX_TIME_IN_FOREGROUND)
                            + consumer.getTimeInStateMs(STATE_FOREGROUND));
            setTimeInStateMs(STATE_BACKGROUND,
                    mData.getLong(COLUMN_INDEX_TIME_IN_BACKGROUND)
                            + consumer.getTimeInStateMs(STATE_BACKGROUND));

            if (mPackageWithHighestDrain == PACKAGE_NAME_UNINITIALIZED) {
                mPackageWithHighestDrain = consumer.getPackageWithHighestDrain();
            } else if (!TextUtils.equals(mPackageWithHighestDrain,
                    consumer.getPackageWithHighestDrain())) {
                // Consider combining two UidBatteryConsumers with this distribution
                // of power drain between packages:
                // (package1=100, package2=10) and (package1=100, package2=101).
                // Since we don't know the actual power distribution between packages at this
                // point, we have no way to correctly declare package1 as the winner.
                // The naive logic of picking the consumer with the higher total consumed
                // power would produce an incorrect result.
                mPackageWithHighestDrain = null;
            }
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
            if (mPackageWithHighestDrain == PACKAGE_NAME_UNINITIALIZED) {
                mPackageWithHighestDrain = null;
            }
            if (mPackageWithHighestDrain != null) {
                mData.putString(COLUMN_INDEX_PACKAGE_WITH_HIGHEST_DRAIN, mPackageWithHighestDrain);
            }
            return new UidBatteryConsumer(this);
        }
    }
}
