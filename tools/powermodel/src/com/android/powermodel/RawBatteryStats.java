/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.io.InputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class RawBatteryStats {
    /**
     * The factory objects for the records. Initialized in the static block.
     */
    private static HashMap<String,RecordFactory> sFactories
            = new HashMap<String,RecordFactory>();

    /**
     * The Record objects that have been parsed.
     */
    private ArrayList<Record> mRecords = new ArrayList<Record>();

    /**
     * The Record objects that have been parsed, indexed by type.
     *
     * Don't use this before {@link #indexRecords()} has been called.
     */
    private ImmutableMap<String,ImmutableList<Record>> mRecordsByType;

    /**
     * The attribution keys for which we have data (corresponding to UIDs we've seen).
     * <p>
     * Does not include the synthetic apps.
     * <p>
     * Don't use this before {@link #indexRecords()} has been called.
     */
    private ImmutableSet<AttributionKey> mApps;

    /**
     * The warnings that have been issued during parsing.
     */
    private ArrayList<Warning> mWarnings = new ArrayList<Warning>();

    /**
     * The version of the BatteryStats dumpsys that we are using.  This value
     * is set to -1 initially, and then when parsing the (hopefully) first
     * line, 'vers', it is set to the correct version.
     */
    private int mDumpsysVersion = -1;

    /**
     * Enum used in the Line annotation to mark whether a field is expected to be
     * system-wide or scoped to an app.
     */
    public enum Scope {
        SYSTEM,
        UID
    }

    /**
     * Enum used to indicated the expected number of results.
     */
    public enum Count {
        SINGLE,
        MULTIPLE
    }

    /**
     * Annotates classes that represent a line of CSV in the batterystats CSV
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Line {
        String tag();
        Scope scope();
        Count count();
    }

    /**
     * Annotates fields that should be parsed automatically from CSV
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Field {
        /**
         * The "column" of this field in the most recent version of the CSV.
         * When parsing old versions, fields that were added will be automatically
         * removed and the indices will be fixed up.
         *
         * The header fields (version, uid, category, type) will be automatically
         * handled for the base Line type.  The index 0 should start after those.
         */
        int index();

        /**
         * First version that this field appears in.
         */
        int added() default 0;
    }

    /**
     * Each line in the BatteryStats CSV is tagged with a category, that says
     * which of the time collection modes was used for the data.
     */
    public enum Category {
        INFO("i"),
        LAST("l"),
        UNPLUGGED("u"),
        CURRENT("c");

        public final String tag;

        Category(String tag) {
            this.tag = tag;
        }
    }

    /**
     * Base class for all lines in a batterystats CSV file.
     */
    public static class Record {
        /**
         * Whether all of the fields for the indicated version of this record
         * have been filled in.
         */
        public boolean complete;


        @Field(index=-4)
        public int lineVersion;

        @Field(index=-3)
        public int uid;

        @Field(index=-2)
        public Category category;

        @Field(index=-1)
        public String lineType;
    }

    @Line(tag="bt", scope=Scope.SYSTEM, count=Count.SINGLE)
    public static class Battery extends Record {
        // If which != STATS_SINCE_CHARGED, the csv will be "N/A" and we will get
        // a parsing warning.  Nobody uses anything other than STATS_SINCE_CHARGED.
        @Field(index=0)
        public int startCount;

        @Field(index=1)
        public long whichBatteryRealtimeMs;

        @Field(index=2)
        public long whichBatteryUptimeMs;

        @Field(index=3)
        public long totalRealtimeMs;

        @Field(index=4)
        public long totalUptimeMs;

        @Field(index=5)
        public long getStartClockTimeMs;

        @Field(index=6)
        public long whichBatteryScreenOffRealtimeMs;

        @Field(index=7)
        public long whichBatteryScreenOffUptimeMs;

        @Field(index=8)
        public long estimatedBatteryCapacityMah;

        @Field(index=9)
        public long minLearnedBatteryCapacityMah;

        @Field(index=10)
        public long maxLearnedBatteryCapacityMah;

        @Field(index=11)
        public long screenDozeTimeMs;
    }

    @Line(tag="gn", scope=Scope.SYSTEM, count=Count.SINGLE)
    public static class GlobalNetwork extends Record {
        @Field(index=0)
        public long mobileRxTotalBytes;

        @Field(index=1)
        public long mobileTxTotalBytes;

        @Field(index=2)
        public long wifiRxTotalBytes;

        @Field(index=3)
        public long wifiTxTotalBytes;

        @Field(index=4)
        public long mobileRxTotalPackets;

        @Field(index=5)
        public long mobileTxTotalPackets;

        @Field(index=6)
        public long wifiRxTotalPackets;

        @Field(index=7)
        public long wifiTxTotalPackets;

        @Field(index=8)
        public long btRxTotalBytes;

        @Field(index=9)
        public long btTxTotalBytes;
    }

    @Line(tag="gmcd", scope=Scope.SYSTEM, count=Count.SINGLE)
    public static class GlobalModemController extends Record {
        @Field(index=0)
        public long idleMs;

        @Field(index=1)
        public long rxTimeMs;

        @Field(index=2)
        public long powerMaMs;

        @Field(index=3)
        public long[] txTimeMs;
    }

    @Line(tag="m", scope=Scope.SYSTEM, count=Count.SINGLE)
    public static class Misc extends Record {
        @Field(index=0)
        public long screenOnTimeMs;

        @Field(index=1)
        public long phoneOnTimeMs;

        @Field(index=2)
        public long fullWakeLockTimeTotalMs;

        @Field(index=3)
        public long partialWakeLockTimeTotalMs;

        @Field(index=4)
        public long mobileRadioActiveTimeMs;

        @Field(index=5)
        public long mobileRadioActiveAdjustedTimeMs;

        @Field(index=6)
        public long interactiveTimeMs;

        @Field(index=7)
        public long powerSaveModeEnabledTimeMs;

        @Field(index=8)
        public int connectivityChangeCount;

        @Field(index=9)
        public long deepDeviceIdleModeTimeMs;

        @Field(index=10)
        public long deepDeviceIdleModeCount;

        @Field(index=11)
        public long deepDeviceIdlingTimeMs;

        @Field(index=12)
        public long deepDeviceIdlingCount;

        @Field(index=13)
        public long mobileRadioActiveCount;

        @Field(index=14)
        public long mobileRadioActiveUnknownTimeMs;

        @Field(index=15)
        public long lightDeviceIdleModeTimeMs;

        @Field(index=16)
        public long lightDeviceIdleModeCount;

        @Field(index=17)
        public long lightDeviceIdlingTimeMs;

        @Field(index=18)
        public long lightDeviceIdlingCount;

        @Field(index=19)
        public long lightLongestDeviceIdleModeTimeMs;

        @Field(index=20)
        public long deepLongestDeviceIdleModeTimeMs;
    }

    @Line(tag="nt", scope=Scope.UID, count=Count.SINGLE)
    public static class Network extends Record {
        @Field(index=0)
        public long mobileRxBytes;

        @Field(index=1)
        public long mobileTxBytes;

        @Field(index=2)
        public long wifiRxBytes;

        @Field(index=3)
        public long wifiTxBytes;

        @Field(index=4)
        public long mobileRxPackets;

        @Field(index=5)
        public long mobileTxPackets;

        @Field(index=6)
        public long wifiRxPackets;

        @Field(index=7)
        public long wifiTxPackets;

        // This is microseconds, because... batterystats.
        @Field(index=8)
        public long mobileRadioActiveTimeUs;

        @Field(index=9)
        public long mobileRadioActiveCount;

        @Field(index=10)
        public long btRxBytes;

        @Field(index=11)
        public long btTxBytes;

        @Field(index=12)
        public long mobileWakeupCount;

        @Field(index=13)
        public long wifiWakeupCount;

        @Field(index=14)
        public long mobileBgRxBytes;

        @Field(index=15)
        public long mobileBgTxBytes;

        @Field(index=16)
        public long wifiBgRxBytes;

        @Field(index=17)
        public long wifiBgTxBytes;

        @Field(index=18)
        public long mobileBgRxPackets;

        @Field(index=19)
        public long mobileBgTxPackets;

        @Field(index=20)
        public long wifiBgRxPackets;

        @Field(index=21)
        public long wifiBgTxPackets;
    }

    @Line(tag="sgt", scope=Scope.SYSTEM, count=Count.SINGLE)
    public static class SignalStrengthTime extends Record {
        @Field(index=0)
        public long[] phoneSignalStrengthTimeMs;
    }

    @Line(tag="sst", scope=Scope.SYSTEM, count=Count.SINGLE)
    public static class SignalScanningTime extends Record {
        @Field(index=0)
        public long phoneSignalScanningTimeMs;
    }

    @Line(tag="uid", scope=Scope.UID, count=Count.MULTIPLE)
    public static class Uid extends Record {
        @Field(index=0)
        public int uidKey;

        @Field(index=1)
        public String pkg;
    }

    @Line(tag="vers", scope=Scope.SYSTEM, count=Count.SINGLE)
    public static class Version extends Record {
        @Field(index=0)
        public int dumpsysVersion;

        @Field(index=1)
        public int parcelVersion;

        @Field(index=2)
        public String startPlatformVersion;

        @Field(index=3)
        public String endPlatformVersion;
    }

    /**
     * Codes for the warnings to classify warnings without parsing them.
     */
    public enum WarningId {
        /**
         * A row didn't have enough fields to match any records and let us extract
         * a line type.
         */
        TOO_FEW_FIELDS_FOR_LINE_TYPE,

        /**
         * We couldn't find a Record for the given line type.
         */
        NO_MATCHING_LINE_TYPE,

        /**
         * Couldn't set the value of a field. Usually this is because the
         * contents of a numeric type couldn't be parsed.
         */
        BAD_FIELD_TYPE,

        /**
         * There were extra field values in the input text.
         */
        TOO_MANY_FIELDS,

        /**
         * There were fields that we were expecting (for this version
         * of the dumpsys) that weren't provided in the CSV data.
         */
        NOT_ENOUGH_FIELDS,

        /**
         * The dumpsys version in the 'vers' CSV line couldn't be parsed.
         */
        BAD_DUMPSYS_VERSION
    }

    /**
     * A non-fatal problem detected during parsing.
     */
    public static class Warning {
        private int mLineNumber;
        private WarningId mId;
        private ArrayList<String> mFields;
        private String mMessage;
        private String[] mExtras;

        public Warning(int lineNumber, WarningId id, ArrayList<String> fields, String message,
                String[] extras) {
            mLineNumber = lineNumber;
            mId = id;
            mFields = fields;
            mMessage = message;
            mExtras = extras;
        }

        public int getLineNumber() {
            return mLineNumber;
        }

        public ArrayList<String> getFields() {
            return mFields;
        }

        public String getMessage() {
            return mMessage;
        }

        public String[] getExtras() {
            return mExtras;
        }
    }

    /**
     * Base class for classes to set fields on Record objects via reflection.
     */
    private abstract static class FieldSetter {
        private int mIndex;
        private int mAdded;
        protected java.lang.reflect.Field mField;

        FieldSetter(int index, int added, java.lang.reflect.Field field) {
            mIndex = index;
            mAdded = added;
            mField = field;
        }

        String getName() {
            return mField.getName();
        }

        int getIndex() {
            return mIndex;
        }

        int getAdded() {
            return mAdded;
        }

        boolean isArray() {
            return mField.getType().isArray();
        }

        abstract void setField(int lineNumber, Record object, String value) throws ParseException;
        abstract void setArray(int lineNumber, Record object, ArrayList<String> values,
                int startIndex, int endIndex) throws ParseException;

        @Override
        public String toString() {
            final String className = getClass().getName();
            int startIndex = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
            if (startIndex < 0) {
                startIndex = 0;
            } else {
                startIndex++;
            }
            return className.substring(startIndex) + "(index=" + mIndex + " added=" + mAdded
                    + " field=" + mField.getName()
                    + " type=" + mField.getType().getSimpleName()
                    + ")";
        }
    }

    /**
     * Sets int fields on Record objects using reflection.
     */
    private static class IntFieldSetter extends FieldSetter {
        IntFieldSetter(int index, int added, java.lang.reflect.Field field) {
            super(index, added, field);
        }

        void setField(int lineNumber, Record object, String value) throws ParseException {
            try {
                mField.setInt(object, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ex) {
                throw new ParseException(lineNumber, "can't parse as integer: " + value);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }

        void setArray(int lineNumber, Record object, ArrayList<String> values,
                int startIndex, int endIndex) throws ParseException {
            try {
                final int[] array = new int[endIndex-startIndex];
                for (int i=startIndex; i<endIndex; i++) {
                    final String value = values.get(startIndex+i);
                    try {
                        array[i] = Integer.parseInt(value.trim());
                    } catch (NumberFormatException ex) {
                        throw new ParseException(lineNumber, "can't parse field "
                                + i + " as integer: " + value);
                    }
                }
                mField.set(object, array);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Sets long fields on Record objects using reflection.
     */
    private static class LongFieldSetter extends FieldSetter {
        LongFieldSetter(int index, int added, java.lang.reflect.Field field) {
            super(index, added, field);
        }

        void setField(int lineNumber, Record object, String value) throws ParseException {
            try {
                mField.setLong(object, Long.parseLong(value.trim()));
            } catch (NumberFormatException ex) {
                throw new ParseException(lineNumber, "can't parse as long: " + value);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }

        void setArray(int lineNumber, Record object, ArrayList<String> values,
                int startIndex, int endIndex) throws ParseException {
            try {
                final long[] array = new long[endIndex-startIndex];
                for (int i=0; i<(endIndex-startIndex); i++) {
                    final String value = values.get(startIndex+i);
                    try {
                        array[i] = Long.parseLong(value.trim());
                    } catch (NumberFormatException ex) {
                        throw new ParseException(lineNumber, "can't parse field "
                                + i + " as long: " + value);
                    }
                }
                mField.set(object, array);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Sets String fields on Record objects using reflection.
     */
    private static class StringFieldSetter extends FieldSetter {
        StringFieldSetter(int index, int added, java.lang.reflect.Field field) {
            super(index, added, field);
        }

        void setField(int lineNumber, Record object, String value) throws ParseException {
            try {
                mField.set(object, value);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }

        void setArray(int lineNumber, Record object, ArrayList<String> values,
                int startIndex, int endIndex) throws ParseException {
            try {
                final String[] array = new String[endIndex-startIndex];
                for (int i=0; i<(endIndex-startIndex); i++) {
                    array[i] = values.get(startIndex+1);
                }
                mField.set(object, array);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Sets enum fields on Record objects using reflection.
     *
     * To be parsed automatically, enums must have a public final String tag
     * field, which is the string that will appear in the csv for that enum value.
     */
    private static class EnumFieldSetter extends FieldSetter {
        private final HashMap<String,Enum> mTags = new HashMap<String,Enum>();

        EnumFieldSetter(int index, int added, java.lang.reflect.Field field) {
            super(index, added, field);

            // Build the mapping of tags to values.
            final Class<?> fieldType = field.getType();

            java.lang.reflect.Field tagField = null;
            try {
                tagField = fieldType.getField("tag");
            } catch (NoSuchFieldException ex) {
                throw new RuntimeException("Missing tag field."
                        + " To be parsed automatically, enums must have"
                        + " a String field called tag.  Enum class: " + fieldType.getName()
                        + " Containing class: " + field.getDeclaringClass().getName()
                        + " Field: " + field.getName());

            }
            if (!String.class.equals(tagField.getType())) {
                throw new RuntimeException("Tag field is not string."
                        + " To be parsed automatically, enums must have"
                        + " a String field called tag.  Enum class: " + fieldType.getName()
                        + " Containing class: " + field.getDeclaringClass().getName()
                        + " Field: " + field.getName()
                        + " Tag field type: " + tagField.getType().getName());
            }

            for (final Object enumValue: fieldType.getEnumConstants()) {
                String tag = null;
                try {
                    tag = (String)tagField.get(enumValue);
                } catch (IllegalAccessException | IllegalArgumentException
                        | ExceptionInInitializerError ex) {
                    throw new RuntimeException(ex);
                }
                mTags.put(tag, (Enum)enumValue);
            }
        }

        void setField(int lineNumber, Record object, String value) throws ParseException {
            final Enum enumValue = mTags.get(value);
            if (enumValue == null) {
                throw new ParseException(lineNumber, "Could not find enum for field "
                        + getName() + " for tag: " + value);
            }
            try {
                mField.set(object, enumValue);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }

        void setArray(int lineNumber, Record object, ArrayList<String> values,
                int startIndex, int endIndex) throws ParseException {
            try {
                final Object array = Array.newInstance(mField.getType().getComponentType(),
                        endIndex-startIndex);
                for (int i=0; i<(endIndex-startIndex); i++) {
                    final String value = values.get(startIndex+i);
                    final Enum enumValue = mTags.get(value);
                    if (enumValue == null) {
                        throw new ParseException(lineNumber, "Could not find enum for field "
                                + getName() + " for tag: " + value);
                    }
                    Array.set(array, i, enumValue);
                }
                mField.set(object, array);
            } catch (IllegalAccessException | IllegalArgumentException
                    | ExceptionInInitializerError ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Factory for the record classes.  Uses reflection to create
     * the fields.
     */
    private static class RecordFactory {
        private String mTag;
        private Class<? extends Record> mSubclass;
        private ArrayList<FieldSetter> mFieldSetters;

        RecordFactory(String tag, Class<? extends Record> subclass,
                ArrayList<FieldSetter> fieldSetters) {
            mTag = tag;
            mSubclass = subclass;
            mFieldSetters = fieldSetters;
        }

        /**
         * Create an object of one of the subclasses of Record, and fill
         * in the fields marked with the Field annotation.
         *
         * @return a new Record with the fields filled in. If there are missing
         *      fields, the {@link Record.complete} field will be set to false.
         */
        Record create(RawBatteryStats bs, int dumpsysVersion, int lineNumber,
                ArrayList<String> fieldValues) {
            final boolean debug = false;
            Record record = null;
            try {
                if (debug) {
                    System.err.println("Creating object: " + mSubclass.getSimpleName());
                }
                record = mSubclass.newInstance();
            } catch (IllegalAccessException | InstantiationException
                    | ExceptionInInitializerError | SecurityException ex) {
                throw new RuntimeException("Exception creating " + mSubclass.getName()
                        + " for '" + mTag + "' record.", ex);
            }
            record.complete = true;
            int fieldIndex = 0;
            int setterIndex = 0;
            while (fieldIndex < fieldValues.size() && setterIndex < mFieldSetters.size()) {
                final FieldSetter setter = mFieldSetters.get(setterIndex);

                if (dumpsysVersion >= 0 && dumpsysVersion < setter.getAdded()) {
                    // The version being parsed doesn't have the field for this setter,
                    // so skip the setter but not the field.
                    setterIndex++;
                    continue;
                }

                final String value = fieldValues.get(fieldIndex);
                try {
                    if (debug) {
                        System.err.println("   setting field " + setter + " to: " + value);
                    }
                    if (setter.isArray()) {
                        setter.setArray(lineNumber, record, fieldValues,
                                fieldIndex, fieldValues.size());
                        // The rest of the fields have been consumed.
                        fieldIndex = fieldValues.size();
                        setterIndex = mFieldSetters.size();
                        break;
                    } else {
                        setter.setField(lineNumber, record, value);
                    }
                } catch (ParseException ex) {
                    bs.addWarning(lineNumber, WarningId.BAD_FIELD_TYPE, fieldValues,
                            ex.getMessage(), mTag, value);
                    record.complete = false;
                }

                fieldIndex++;
                setterIndex++;
            }

            // If there are extra fields, this record is complete, there are just
            // extra values, so we issue a warning but don't mark it incomplete.
            if (fieldIndex < fieldValues.size()) {
                bs.addWarning(lineNumber, WarningId.TOO_MANY_FIELDS, fieldValues,
                        "Line '" + mTag + "' has extra fields.",
                        mTag, Integer.toString(fieldIndex), Integer.toString(fieldValues.size()));
                if (debug) {
                    for (int i=0; i<mFieldSetters.size(); i++) {
                        System.err.println("    setter: [" + i + "] " + mFieldSetters.get(i));
                    }
                }
            }

            // If we have any fields that are missing, add a warning and return null.
            for (; setterIndex < mFieldSetters.size(); setterIndex++) {
                final FieldSetter setter = mFieldSetters.get(setterIndex);
                if (dumpsysVersion >= 0 && dumpsysVersion >= setter.getAdded()) {
                    bs.addWarning(lineNumber, WarningId.NOT_ENOUGH_FIELDS, fieldValues,
                            "Line '" + mTag + "' missing field: index=" + setterIndex
                                + " name=" + setter.getName(),
                            mTag, Integer.toString(setterIndex));
                    record.complete = false;
                }
            }

            return record;
        }
    }

    /**
     * Parse the input stream and return a RawBatteryStats object.
     */
    public static RawBatteryStats parse(InputStream input) throws ParseException, IOException {
        final RawBatteryStats result = new RawBatteryStats();
        result.parseImpl(input);
        return result;
    }

    /**
     * Get a record.
     * <p>
     * If multiple of that record are found, returns the first one.  There will already
     * have been a warning recorded if the count annotation did not match what was in the
     * csv.
     * <p>
     * Returns null if there are no records of that type.
     */
    public <T extends Record> T getSingle(Class<T> cl) {
        final List<Record> list = mRecordsByType.get(cl.getName());
        if (list == null) {
            return null;
        }
        // Notes:
        //   - List can never be empty because the list itself wouldn't have been added.
        //   - Cast is safe because list was populated based on class name (let's assume
        //     there's only one class loader involved here).
        return (T)list.get(0);
    }

    /**
     * Get a record.
     * <p>
     * If multiple of that record are found, returns the first one that matches that uid.
     * <p>
     * Returns null if there are no records of that type that match the given uid.
     */
    public <T extends Record> T getSingle(Class<T> cl, int uid) {
        final List<Record> list = mRecordsByType.get(cl.getName());
        if (list == null) {
            return null;
        }
        for (final Record record: list) {
            if (record.uid == uid) {
                // Cast is safe because list was populated based on class name (let's assume
                // there's only one class loader involved here).
                return (T)record;
            }
        }
        return null;
    }

    /**
     * Get all the records of the given type.
     */
    public <T extends Record> List<T> getMultiple(Class<T> cl) {
        final List<Record> list = mRecordsByType.get(cl.getName());
        if (list == null) {
            return ImmutableList.<T>of();
        }
        // Cast is safe because list was populated based on class name (let's assume
        // there's only one class loader involved here).
        return ImmutableList.copyOf((List<T>)list);
    }

    /**
     * Get the UIDs that are covered by this batterystats dump.
     */
    public Set<AttributionKey> getApps() {
        return mApps;
    }

    /**
     * No public constructor. Use {@link #parse}.
     */
    private RawBatteryStats() {
    }

    /**
     * Get the list of Record objects that were parsed from the csv.
     */
    public List<Record> getRecords() {
        return mRecords;
    }

    /**
     * Gets the warnings that were encountered during parsing.
     */
    public List<Warning> getWarnings() {
        return mWarnings;
    }

    /**
     * Implementation of the csv parsing.
     */
    private void parseImpl(InputStream input) throws ParseException, IOException {
        // Parse the csv
        CsvParser.parse(input, new CsvParser.LineProcessor() {
                    @Override
                    public void onLine(int lineNumber, ArrayList<String> fields)
                            throws ParseException {
                        handleCsvLine(lineNumber, fields);
                    }
                });

        // Gather the records by class name for the getSingle() and getMultiple() functions.
        indexRecords();

        // Gather the uids from all the places UIDs come from, for getApps().
        indexApps();
    }

    /**
     * Handle a line of CSV input, creating the right Record object.
     */
    private void handleCsvLine(int lineNumber, ArrayList<String> fields) throws ParseException {
        // The standard rows all have the 4 core fields. Anything less isn't what we're
        // looking for.
        if (fields.size() <= 4) {
            addWarning(lineNumber, WarningId.TOO_FEW_FIELDS_FOR_LINE_TYPE, fields,
                    "Line with too few fields (" + fields.size() + ")",
                    Integer.toString(fields.size()));
            return;
        }

        final String lineType = fields.get(3);

        // Handle the vers line specially, because we need the version number
        // to make the rest of the machinery work.
        if ("vers".equals(lineType)) {
            final String versionText = fields.get(4);
            try {
                mDumpsysVersion = Integer.parseInt(versionText);
            } catch (NumberFormatException ex) {
                addWarning(lineNumber, WarningId.BAD_DUMPSYS_VERSION, fields,
                        "Couldn't parse dumpsys version number: '" + versionText,
                        versionText);
            }
        }

        // Find the right factory.
        final RecordFactory factory = sFactories.get(lineType);
        if (factory == null) {
            addWarning(lineNumber, WarningId.NO_MATCHING_LINE_TYPE, fields,
                    "No Record for line type '" + lineType + "'",
                    lineType);
            return;
        }

        // Create the record.
        final Record record = factory.create(this, mDumpsysVersion, lineNumber, fields);
        mRecords.add(record);
    }

    /**
     * Add to the list of warnings.
     */
    private void addWarning(int lineNumber, WarningId id,
            ArrayList<String> fields, String message, String... extras) {
        mWarnings.add(new Warning(lineNumber, id, fields, message, extras));
        final boolean debug = false;
        if (debug) {
            final StringBuilder text = new StringBuilder("line ");
            text.append(lineNumber);
            text.append(": WARNING: ");
            text.append(message);
            text.append("\n    fields: ");
            for (int i=0; i<fields.size(); i++) {
                final String field = fields.get(i);
                if (field.indexOf('"') >= 0) {
                    text.append('"');
                    text.append(field.replace("\"", "\"\""));
                    text.append('"');
                } else {
                    text.append(field);
                }
                if (i != fields.size() - 1) {
                    text.append(',');
                }
            }
            text.append('\n');
            for (String extra: extras) {
                text.append("    extra: ");
                text.append(extra);
                text.append('\n');
            }
            System.err.print(text.toString());
        }
    }

    /**
     * Group records by class name.
     */
    private void indexRecords() {
        final HashMap<String,ArrayList<Record>> map = new HashMap<String,ArrayList<Record>>();

        // Iterate over all of the records
        for (Record record: mRecords) {
            final String className = record.getClass().getName();

            ArrayList<Record> list = map.get(className);
            if (list == null) {
                list = new ArrayList<Record>();
                map.put(className, list);
            }

            list.add(record);
        }

        // Make it immutable
        final HashMap<String,ImmutableList<Record>> result
                = new HashMap<String,ImmutableList<Record>>();
        for (HashMap.Entry<String,ArrayList<Record>> entry: map.entrySet()) {
            result.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
        }

        // Initialize here so uninitialized access will result in NPE.
        mRecordsByType = ImmutableMap.copyOf(result);
    }

    /**
     * Collect the UIDs from the csv.
     *
     * They come from two places.
     * <ul>
     *   <li>The uid to package name map entries ({@link #Uid}) at the beginning.
     *   <li>The uid fields of the rest of the entries, some of which might not
     *       have package names associated with them.
     * </ul>
     *
     * TODO: Is this where we should also do the logic about the special UIDs?
     */
    private void indexApps() {
        final HashMap<Integer,HashSet<String>> uids = new HashMap<Integer,HashSet<String>>();

        // The Uid rows, from which we get package names
        for (Uid record: getMultiple(Uid.class)) {
            HashSet<String> list = uids.get(record.uidKey);
            if (list == null) {
                list = new HashSet<String>();
                uids.put(record.uidKey, list);
            }
            list.add(record.pkg);
        }

        // The uid fields of everything
        for (Record record: mRecords) {
            // The 0 in the INFO records isn't really root, it's just unfilled data.
            // The root uid (0) will show up practically in every record, but don't force it.
            if (record.category != Category.INFO) {
                if (uids.get(record.uid) == null) {
                    // There is no other data about this UID, but it does exist!
                    uids.put(record.uid, new HashSet<String>());
                }
            }
        }

        // Turn our temporary lists of package names into AttributionKeys.
        final HashSet<AttributionKey> result = new HashSet<AttributionKey>();
        for (HashMap.Entry<Integer,HashSet<String>> entry: uids.entrySet()) {
            result.add(new AttributionKey(entry.getKey(), entry.getValue()));
        }

        // Initialize here so uninitialized access will result in NPE.
        mApps = ImmutableSet.copyOf(result);
    }

    /**
     * Init the factory classes.
     */
    static {
        for (Class<?> cl: RawBatteryStats.class.getClasses()) {
            final Line lineAnnotation = cl.getAnnotation(Line.class);
            if (lineAnnotation != null && Record.class.isAssignableFrom(cl)) {
                final ArrayList<FieldSetter> fieldSetters = new ArrayList<FieldSetter>();

                for (java.lang.reflect.Field field: cl.getFields()) {
                    final Field fa = field.getAnnotation(Field.class);
                    if (fa != null) {
                        final Class<?> fieldType = field.getType();
                        final Class<?> innerType = fieldType.isArray()
                                ? fieldType.getComponentType()
                                : fieldType;
                        if (Integer.TYPE.equals(innerType)) {
                            fieldSetters.add(new IntFieldSetter(fa.index(), fa.added(), field));
                        } else if (Long.TYPE.equals(innerType)) {
                            fieldSetters.add(new LongFieldSetter(fa.index(), fa.added(), field));
                        } else if (String.class.equals(innerType)) {
                            fieldSetters.add(new StringFieldSetter(fa.index(), fa.added(), field));
                        } else if (innerType.isEnum()) {
                            fieldSetters.add(new EnumFieldSetter(fa.index(), fa.added(), field));
                        } else {
                            throw new RuntimeException("Unsupported field type '"
                                    + fieldType.getName() + "' on "
                                    + cl.getName() + "." + field.getName());
                        }
                    }
                }
                // Sort by index
                Collections.sort(fieldSetters, new Comparator<FieldSetter>() {
                            @Override
                            public int compare(FieldSetter a, FieldSetter b) {
                                return a.getIndex() - b.getIndex();
                            }
                        });
                // Only the last one can be an array
                for (int i=0; i<fieldSetters.size()-1; i++) {
                    if (fieldSetters.get(i).isArray()) {
                        throw new RuntimeException("Only the last (highest index) @Field"
                                + " in class " + cl.getName() + " can be an array: "
                                + fieldSetters.get(i).getName());
                    }
                }
                // Add to the map
                sFactories.put(lineAnnotation.tag(), new RecordFactory(lineAnnotation.tag(),
                            (Class<Record>)cl, fieldSetters));
            }
        }
    }
}

