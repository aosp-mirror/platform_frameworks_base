/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.display;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.hardware.display.AmbientBrightnessDayStats;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Class that stores stats of ambient brightness regions as histogram.
 */
public class AmbientBrightnessStatsTracker {

    private static final String TAG = "AmbientBrightnessStatsTracker";
    private static final boolean DEBUG = false;

    @VisibleForTesting
    static final float[] BUCKET_BOUNDARIES_FOR_NEW_STATS =
            {0, 0.1f, 0.3f, 1, 3, 10, 30, 100, 300, 1000, 3000, 10000};
    @VisibleForTesting
    static final int MAX_DAYS_TO_TRACK = 7;

    private final AmbientBrightnessStats mAmbientBrightnessStats;
    private final Timer mTimer;
    private final Injector mInjector;
    private final UserManager mUserManager;
    private float mCurrentAmbientBrightness;
    private @UserIdInt int mCurrentUserId;

    public AmbientBrightnessStatsTracker(UserManager userManager, @Nullable Injector injector) {
        mUserManager = userManager;
        if (injector != null) {
            mInjector = injector;
        } else {
            mInjector = new Injector();
        }
        mAmbientBrightnessStats = new AmbientBrightnessStats();
        mTimer = new Timer(() -> mInjector.elapsedRealtimeMillis());
        mCurrentAmbientBrightness = -1;
    }

    public synchronized void start() {
        mTimer.reset();
        mTimer.start();
    }

    public synchronized void stop() {
        if (mTimer.isRunning()) {
            mAmbientBrightnessStats.log(mCurrentUserId, mInjector.getLocalDate(),
                    mCurrentAmbientBrightness, mTimer.totalDurationSec());
        }
        mTimer.reset();
        mCurrentAmbientBrightness = -1;
    }

    public synchronized void add(@UserIdInt int userId, float newAmbientBrightness) {
        if (mTimer.isRunning()) {
            if (userId == mCurrentUserId) {
                mAmbientBrightnessStats.log(mCurrentUserId, mInjector.getLocalDate(),
                        mCurrentAmbientBrightness, mTimer.totalDurationSec());
            } else {
                if (DEBUG) {
                    Slog.v(TAG, "User switched since last sensor event.");
                }
                mCurrentUserId = userId;
            }
            mTimer.reset();
            mTimer.start();
            mCurrentAmbientBrightness = newAmbientBrightness;
        } else {
            if (DEBUG) {
                Slog.e(TAG, "Timer not running while trying to add brightness stats.");
            }
        }
    }

    public synchronized void writeStats(OutputStream stream) throws IOException {
        mAmbientBrightnessStats.writeToXML(stream);
    }

    public synchronized void readStats(InputStream stream) throws IOException {
        mAmbientBrightnessStats.readFromXML(stream);
    }

    public synchronized ArrayList<AmbientBrightnessDayStats> getUserStats(int userId) {
        return mAmbientBrightnessStats.getUserStats(userId);
    }

    public synchronized void dump(PrintWriter pw) {
        pw.println("AmbientBrightnessStats:");
        pw.print(mAmbientBrightnessStats);
    }

    /**
     * AmbientBrightnessStats tracks ambient brightness stats across users over multiple days.
     * This class is not ThreadSafe.
     */
    class AmbientBrightnessStats {

        private static final String TAG_AMBIENT_BRIGHTNESS_STATS = "ambient-brightness-stats";
        private static final String TAG_AMBIENT_BRIGHTNESS_DAY_STATS =
                "ambient-brightness-day-stats";
        private static final String ATTR_USER = "user";
        private static final String ATTR_LOCAL_DATE = "local-date";
        private static final String ATTR_BUCKET_BOUNDARIES = "bucket-boundaries";
        private static final String ATTR_BUCKET_STATS = "bucket-stats";

        private Map<Integer, Deque<AmbientBrightnessDayStats>> mStats;

        public AmbientBrightnessStats() {
            mStats = new HashMap<>();
        }

        public void log(@UserIdInt int userId, LocalDate localDate, float ambientBrightness,
                float durationSec) {
            Deque<AmbientBrightnessDayStats> userStats = getOrCreateUserStats(mStats, userId);
            AmbientBrightnessDayStats dayStats = getOrCreateDayStats(userStats, localDate);
            dayStats.log(ambientBrightness, durationSec);
        }

        public ArrayList<AmbientBrightnessDayStats> getUserStats(@UserIdInt int userId) {
            if (mStats.containsKey(userId)) {
                return new ArrayList<>(mStats.get(userId));
            } else {
                return null;
            }
        }

        public void writeToXML(OutputStream stream) throws IOException {
            TypedXmlSerializer out = Xml.resolveSerializer(stream);
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            final LocalDate cutOffDate = mInjector.getLocalDate().minusDays(MAX_DAYS_TO_TRACK);
            out.startTag(null, TAG_AMBIENT_BRIGHTNESS_STATS);
            for (Map.Entry<Integer, Deque<AmbientBrightnessDayStats>> entry : mStats.entrySet()) {
                for (AmbientBrightnessDayStats userDayStats : entry.getValue()) {
                    int userSerialNumber = mInjector.getUserSerialNumber(mUserManager,
                            entry.getKey());
                    if (userSerialNumber != -1 && userDayStats.getLocalDate().isAfter(cutOffDate)) {
                        out.startTag(null, TAG_AMBIENT_BRIGHTNESS_DAY_STATS);
                        out.attributeInt(null, ATTR_USER, userSerialNumber);
                        out.attribute(null, ATTR_LOCAL_DATE,
                                userDayStats.getLocalDate().toString());
                        StringBuilder bucketBoundariesValues = new StringBuilder();
                        StringBuilder timeSpentValues = new StringBuilder();
                        for (int i = 0; i < userDayStats.getBucketBoundaries().length; i++) {
                            if (i > 0) {
                                bucketBoundariesValues.append(",");
                                timeSpentValues.append(",");
                            }
                            bucketBoundariesValues.append(userDayStats.getBucketBoundaries()[i]);
                            timeSpentValues.append(userDayStats.getStats()[i]);
                        }
                        out.attribute(null, ATTR_BUCKET_BOUNDARIES,
                                bucketBoundariesValues.toString());
                        out.attribute(null, ATTR_BUCKET_STATS, timeSpentValues.toString());
                        out.endTag(null, TAG_AMBIENT_BRIGHTNESS_DAY_STATS);
                    }
                }
            }
            out.endTag(null, TAG_AMBIENT_BRIGHTNESS_STATS);
            out.endDocument();
            stream.flush();
        }

        public void readFromXML(InputStream stream) throws IOException {
            try {
                Map<Integer, Deque<AmbientBrightnessDayStats>> parsedStats = new HashMap<>();
                TypedXmlPullParser parser = Xml.resolvePullParser(stream);

                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && type != XmlPullParser.START_TAG) {
                }
                String tag = parser.getName();
                if (!TAG_AMBIENT_BRIGHTNESS_STATS.equals(tag)) {
                    throw new XmlPullParserException(
                            "Ambient brightness stats not found in tracker file " + tag);
                }

                final LocalDate cutOffDate = mInjector.getLocalDate().minusDays(MAX_DAYS_TO_TRACK);
                int outerDepth = parser.getDepth();
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }
                    tag = parser.getName();
                    if (TAG_AMBIENT_BRIGHTNESS_DAY_STATS.equals(tag)) {
                        int userSerialNumber = parser.getAttributeInt(null, ATTR_USER);
                        LocalDate localDate = LocalDate.parse(
                                parser.getAttributeValue(null, ATTR_LOCAL_DATE));
                        String[] bucketBoundaries = parser.getAttributeValue(null,
                                ATTR_BUCKET_BOUNDARIES).split(",");
                        String[] bucketStats = parser.getAttributeValue(null,
                                ATTR_BUCKET_STATS).split(",");
                        if (bucketBoundaries.length != bucketStats.length
                                || bucketBoundaries.length < 1) {
                            throw new IOException("Invalid brightness stats string.");
                        }
                        float[] parsedBucketBoundaries = new float[bucketBoundaries.length];
                        float[] parsedBucketStats = new float[bucketStats.length];
                        for (int i = 0; i < bucketBoundaries.length; i++) {
                            parsedBucketBoundaries[i] = Float.parseFloat(bucketBoundaries[i]);
                            parsedBucketStats[i] = Float.parseFloat(bucketStats[i]);
                        }
                        int userId = mInjector.getUserId(mUserManager, userSerialNumber);
                        if (userId != -1 && localDate.isAfter(cutOffDate)) {
                            Deque<AmbientBrightnessDayStats> userStats = getOrCreateUserStats(
                                    parsedStats, userId);
                            userStats.offer(
                                    new AmbientBrightnessDayStats(localDate,
                                            parsedBucketBoundaries, parsedBucketStats));
                        }
                    }
                }
                mStats = parsedStats;
            } catch (NullPointerException | NumberFormatException | XmlPullParserException |
                    DateTimeParseException | IOException e) {
                throw new IOException("Failed to parse brightness stats file.", e);
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<Integer, Deque<AmbientBrightnessDayStats>> entry : mStats.entrySet()) {
                for (AmbientBrightnessDayStats dayStats : entry.getValue()) {
                    builder.append("  ");
                    builder.append(entry.getKey()).append(" ");
                    builder.append(dayStats).append("\n");
                }
            }
            return builder.toString();
        }

        private Deque<AmbientBrightnessDayStats> getOrCreateUserStats(
                Map<Integer, Deque<AmbientBrightnessDayStats>> stats, @UserIdInt int userId) {
            if (!stats.containsKey(userId)) {
                stats.put(userId, new ArrayDeque<>());
            }
            return stats.get(userId);
        }

        private AmbientBrightnessDayStats getOrCreateDayStats(
                Deque<AmbientBrightnessDayStats> userStats, LocalDate localDate) {
            AmbientBrightnessDayStats lastBrightnessStats = userStats.peekLast();
            if (lastBrightnessStats != null && lastBrightnessStats.getLocalDate().equals(
                    localDate)) {
                return lastBrightnessStats;
            } else {
                // It is a new day, and we have available data, so log data. The daily boundary
                // might not be right if the user changes timezones but that is fine, since it
                // won't be that frequent.
                if (lastBrightnessStats != null) {
                    FrameworkStatsLog.write(FrameworkStatsLog.AMBIENT_BRIGHTNESS_STATS_REPORTED,
                            lastBrightnessStats.getStats(),
                            lastBrightnessStats.getBucketBoundaries());
                }
                AmbientBrightnessDayStats dayStats = new AmbientBrightnessDayStats(localDate,
                        BUCKET_BOUNDARIES_FOR_NEW_STATS);
                if (userStats.size() == MAX_DAYS_TO_TRACK) {
                    userStats.poll();
                }
                userStats.offer(dayStats);
                return dayStats;
            }
        }
    }

    @VisibleForTesting
    interface Clock {
        long elapsedTimeMillis();
    }

    @VisibleForTesting
    static class Timer {

        private final Clock clock;
        private long startTimeMillis;
        private boolean started;

        public Timer(Clock clock) {
            this.clock = clock;
        }

        public void reset() {
            started = false;
        }

        public void start() {
            if (!started) {
                startTimeMillis = clock.elapsedTimeMillis();
                started = true;
            }
        }

        public boolean isRunning() {
            return started;
        }

        public float totalDurationSec() {
            if (started) {
                return (float) ((clock.elapsedTimeMillis() - startTimeMillis) / 1000.0);
            }
            return 0;
        }
    }

    @VisibleForTesting
    static class Injector {
        public long elapsedRealtimeMillis() {
            return SystemClock.elapsedRealtime();
        }

        public int getUserSerialNumber(UserManager userManager, int userId) {
            return userManager.getUserSerialNumber(userId);
        }

        public int getUserId(UserManager userManager, int userSerialNumber) {
            return userManager.getUserHandle(userSerialNumber);
        }

        public LocalDate getLocalDate() {
            return LocalDate.now();
        }
    }
}