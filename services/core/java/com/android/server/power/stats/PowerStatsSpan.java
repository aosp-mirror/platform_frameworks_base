/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Contains power stats of various kinds, aggregated over a time span.
 */
public class PowerStatsSpan {
    private static final String TAG = "PowerStatsStore";

    /**
     * Increment VERSION when the XML format of the store changes. Also, update
     * {@link #isCompatibleXmlFormat} to return true for all legacy versions
     * that are compatible with the new one.
     */
    private static final int VERSION = 1;

    private static final String XML_TAG_METADATA = "metadata";
    private static final String XML_ATTR_ID = "id";
    private static final String XML_ATTR_VERSION = "version";
    private static final String XML_TAG_TIMEFRAME = "timeframe";
    private static final String XML_ATTR_MONOTONIC = "monotonic";
    private static final String XML_ATTR_START_TIME = "start";
    private static final String XML_ATTR_DURATION = "duration";
    private static final String XML_TAG_SECTION = "section";
    private static final String XML_ATTR_SECTION_TYPE = "type";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    static class TimeFrame {
        public final long startMonotonicTime;
        @CurrentTimeMillisLong
        public final long startTime;
        @DurationMillisLong
        public final long duration;

        TimeFrame(long startMonotonicTime, @CurrentTimeMillisLong long startTime,
                @DurationMillisLong long duration) {
            this.startMonotonicTime = startMonotonicTime;
            this.startTime = startTime;
            this.duration = duration;
        }

        void write(TypedXmlSerializer serializer) throws IOException {
            serializer.startTag(null, XML_TAG_TIMEFRAME);
            serializer.attributeLong(null, XML_ATTR_START_TIME, startTime);
            serializer.attributeLong(null, XML_ATTR_MONOTONIC, startMonotonicTime);
            serializer.attributeLong(null, XML_ATTR_DURATION, duration);
            serializer.endTag(null, XML_TAG_TIMEFRAME);
        }

        static TimeFrame read(TypedXmlPullParser parser) throws XmlPullParserException {
            return new TimeFrame(
                    parser.getAttributeLong(null, XML_ATTR_MONOTONIC),
                    parser.getAttributeLong(null, XML_ATTR_START_TIME),
                    parser.getAttributeLong(null, XML_ATTR_DURATION));
        }

        /**
         * Prints the contents of this TimeFrame.
         */
        public void dump(IndentingPrintWriter pw) {
            StringBuilder sb = new StringBuilder();
            sb.append(DATE_FORMAT.format(Instant.ofEpochMilli(startTime)))
                    .append(" (monotonic=").append(startMonotonicTime).append(") ")
                    .append(" duration=");
            String durationString = TimeUtils.formatDuration(duration);
            if (durationString.startsWith("+")) {
                sb.append(durationString.substring(1));
            } else {
                sb.append(durationString);
            }
            pw.print(sb);
        }
    }

    static class Metadata {
        static final Comparator<Metadata> COMPARATOR = Comparator.comparing(Metadata::getId);

        private final long mId;
        private final List<TimeFrame> mTimeFrames = new ArrayList<>();
        private final List<String> mSections = new ArrayList<>();

        Metadata(long id) {
            mId = id;
        }

        public long getId() {
            return mId;
        }

        public List<TimeFrame> getTimeFrames() {
            return mTimeFrames;
        }

        public List<String> getSections() {
            return mSections;
        }

        void addTimeFrame(TimeFrame timeFrame) {
            mTimeFrames.add(timeFrame);
        }

        void addSection(String sectionType) {
            // The number of sections per span is small, so there is no need to use a Set
            if (!mSections.contains(sectionType)) {
                mSections.add(sectionType);
            }
        }

        void write(TypedXmlSerializer serializer) throws IOException {
            serializer.startTag(null, XML_TAG_METADATA);
            serializer.attributeLong(null, XML_ATTR_ID, mId);
            serializer.attributeInt(null, XML_ATTR_VERSION, VERSION);
            for (TimeFrame timeFrame : mTimeFrames) {
                timeFrame.write(serializer);
            }
            for (String section : mSections) {
                serializer.startTag(null, XML_TAG_SECTION);
                serializer.attribute(null, XML_ATTR_SECTION_TYPE, section);
                serializer.endTag(null, XML_TAG_SECTION);
            }
            serializer.endTag(null, XML_TAG_METADATA);
        }

        /**
         * Reads just the header of the XML file containing metadata.
         * Returns null if the file does not contain a compatible &lt;metadata&gt; element.
         */
        @Nullable
        public static Metadata read(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            Metadata metadata = null;
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT
                   && !(eventType == XmlPullParser.END_TAG
                        && parser.getName().equals(XML_TAG_METADATA))) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if (tagName.equals(XML_TAG_METADATA)) {
                        int version = parser.getAttributeInt(null, XML_ATTR_VERSION);
                        if (!isCompatibleXmlFormat(version)) {
                            Slog.e(TAG,
                                    "Incompatible version " + version + "; expected " + VERSION);
                            return null;
                        }

                        long id = parser.getAttributeLong(null, XML_ATTR_ID);
                        metadata = new Metadata(id);
                    } else if (metadata != null && tagName.equals(XML_TAG_TIMEFRAME)) {
                        metadata.addTimeFrame(TimeFrame.read(parser));
                    } else if (metadata != null && tagName.equals(XML_TAG_SECTION)) {
                        metadata.addSection(parser.getAttributeValue(null, XML_ATTR_SECTION_TYPE));
                    }
                }
                eventType = parser.next();
            }
            return metadata;
        }

        /**
         * Prints the metadata.
         */
        public void dump(IndentingPrintWriter pw) {
            dump(pw, true);
        }

        void dump(IndentingPrintWriter pw, boolean includeSections) {
            pw.print("Span ");
            if (mTimeFrames.size() > 0) {
                mTimeFrames.get(0).dump(pw);
                pw.println();
            }

            // Sometimes, when the wall clock is adjusted in the middle of a stats session,
            // we will have more than one time frame.
            for (int i = 1; i < mTimeFrames.size(); i++) {
                TimeFrame timeFrame = mTimeFrames.get(i);
                pw.print("     ");      // Aligned below "Span "
                timeFrame.dump(pw);
                pw.println();
            }

            if (includeSections) {
                pw.increaseIndent();
                for (String section : mSections) {
                    pw.print("section", section);
                    pw.println();
                }
                pw.decreaseIndent();
            }
        }

        @Override
        public String toString() {
            StringWriter sw = new StringWriter();
            IndentingPrintWriter ipw = new IndentingPrintWriter(sw);
            ipw.print("id", mId);
            for (int i = 0; i < mTimeFrames.size(); i++) {
                TimeFrame timeFrame = mTimeFrames.get(i);
                ipw.print("timeframe=[");
                timeFrame.dump(ipw);
                ipw.print("] ");
            }
            for (String section : mSections) {
                ipw.print("section", section);
            }
            ipw.flush();
            return sw.toString().trim();
        }
    }

    /**
     * Contains a specific type of aggregate power stats.  The contents type is determined by
     * the section type.
     */
    public abstract static class Section {
        private final String mType;

        Section(String type) {
            mType = type;
        }

        /**
         * Returns the section type, which determines the type of data stored in the corresponding
         * section of {@link PowerStatsSpan}
         */
        public String getType() {
            return mType;
        }

        abstract void write(TypedXmlSerializer serializer) throws IOException;

        /**
         * Prints the section type.
         */
        public void dump(IndentingPrintWriter ipw) {
            ipw.println(mType);
        }
    }

    /**
     * A universal XML parser for {@link PowerStatsSpan.Section}'s.  It is aware of all
     * supported section types as well as their corresponding XML formats.
     */
    public interface SectionReader {
        /**
         * Reads the contents of the section using the parser. The type of the object
         * read and the corresponding XML format are determined by the section type.
         */
        Section read(String sectionType, TypedXmlPullParser parser)
                throws IOException, XmlPullParserException;
    }

    private final Metadata mMetadata;
    private final List<Section> mSections = new ArrayList<>();

    public PowerStatsSpan(long id) {
        this(new Metadata(id));
    }

    private PowerStatsSpan(Metadata metadata) {
        mMetadata = metadata;
    }

    public Metadata getMetadata() {
        return mMetadata;
    }

    public long getId() {
        return mMetadata.mId;
    }

    void addTimeFrame(long monotonicTime, @CurrentTimeMillisLong long wallClockTime,
            @DurationMillisLong long duration) {
        mMetadata.mTimeFrames.add(new TimeFrame(monotonicTime, wallClockTime, duration));
    }

    void addSection(Section section) {
        mMetadata.addSection(section.getType());
        mSections.add(section);
    }

    @NonNull
    public List<Section> getSections() {
        return mSections;
    }

    private static boolean isCompatibleXmlFormat(int version) {
        return version == VERSION;
    }

    /**
     * Creates an XML file containing the persistent state of the power stats span.
     */
    @VisibleForTesting
    public void writeXml(OutputStream out, TypedXmlSerializer serializer) throws IOException {
        serializer.setOutput(out, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        mMetadata.write(serializer);
        for (Section section : mSections) {
            serializer.startTag(null, XML_TAG_SECTION);
            serializer.attribute(null, XML_ATTR_SECTION_TYPE, section.mType);
            section.write(serializer);
            serializer.endTag(null, XML_TAG_SECTION);
        }
        serializer.endDocument();
    }

    @Nullable
    static PowerStatsSpan read(InputStream in, TypedXmlPullParser parser,
            SectionReader sectionReader, String... sectionTypes)
            throws IOException, XmlPullParserException {
        Set<String> neededSections = Sets.newArraySet(sectionTypes);
        boolean selectSections = !neededSections.isEmpty();
        parser.setInput(in, StandardCharsets.UTF_8.name());

        Metadata metadata = Metadata.read(parser);
        if (metadata == null) {
            return null;
        }

        PowerStatsSpan span = new PowerStatsSpan(metadata);
        boolean skipSection = false;
        int nestingLevel = 0;
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (skipSection) {
                if (eventType == XmlPullParser.END_TAG
                        && parser.getName().equals(XML_TAG_SECTION)) {
                    nestingLevel--;
                    if (nestingLevel == 0) {
                        skipSection = false;
                    }
                } else if (eventType == XmlPullParser.START_TAG
                           && parser.getName().equals(XML_TAG_SECTION)) {
                    nestingLevel++;
                }
            } else if (eventType == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (tag.equals(XML_TAG_SECTION)) {
                    String sectionType = parser.getAttributeValue(null, XML_ATTR_SECTION_TYPE);
                    if (!selectSections || neededSections.contains(sectionType)) {
                        Section section = sectionReader.read(sectionType, parser);
                        if (section == null) {
                            if (selectSections) {
                                throw new XmlPullParserException(
                                        "Unsupported PowerStatsStore section type: " + sectionType);
                            } else {
                                section = new Section(sectionType) {
                                    @Override
                                    public void dump(IndentingPrintWriter ipw) {
                                        ipw.println("Unsupported PowerStatsStore section type: "
                                                    + sectionType);
                                    }

                                    @Override
                                    void write(TypedXmlSerializer serializer) {
                                    }
                                };
                            }
                        }
                        span.addSection(section);
                    } else {
                        skipSection = true;
                    }
                } else if (tag.equals(XML_TAG_METADATA)) {
                    Metadata.read(parser);
                }
            }
            eventType = parser.next();
        }
        return span;
    }

    /**
     * Prints the contents of this power stats span.
     */
    public void dump(IndentingPrintWriter ipw) {
        mMetadata.dump(ipw, /* includeSections */ false);
        for (Section section : mSections) {
            ipw.increaseIndent();
            ipw.println(section.mType);
            section.dump(ipw);
            ipw.decreaseIndent();
        }
    }
}
