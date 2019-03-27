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

package com.android.internal.os;

import android.annotation.Nullable;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Range;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service that handles settings for {@link KernelCpuThreadReader}
 *
 * <p>N.B.: The `collected_uids` setting takes a string representation of what UIDs to collect data
 * for. A string representation is used as we will want to express UID ranges, therefore an integer
 * array could not be used. The format of the string representation is detailed here: {@link
 * UidPredicate#fromString}.
 *
 * @hide Only for use within the system server
 */
public class KernelCpuThreadReaderSettingsObserver extends ContentObserver {
    private static final String TAG = "KernelCpuThreadReaderSettingsObserver";

    /** The number of frequency buckets to report */
    private static final String NUM_BUCKETS_SETTINGS_KEY = "num_buckets";

    private static final int NUM_BUCKETS_DEFAULT = 8;

    /** List of UIDs to report data for */
    private static final String COLLECTED_UIDS_SETTINGS_KEY = "collected_uids";

    private static final String COLLECTED_UIDS_DEFAULT = "0-0;1000-1000";

    /** Minimum total CPU usage to report */
    private static final String MINIMUM_TOTAL_CPU_USAGE_MILLIS_SETTINGS_KEY =
            "minimum_total_cpu_usage_millis";

    private static final int MINIMUM_TOTAL_CPU_USAGE_MILLIS_DEFAULT = 10000;

    private final Context mContext;

    @Nullable private final KernelCpuThreadReader mKernelCpuThreadReader;

    /**
     * @return returns a created {@link KernelCpuThreadReader} that will be modified by any change
     *     in settings, returns null if creation failed
     */
    @Nullable
    public static KernelCpuThreadReader getSettingsModifiedReader(Context context) {
        // Create the observer
        KernelCpuThreadReaderSettingsObserver settingsObserver =
                new KernelCpuThreadReaderSettingsObserver(context);
        // Register the observer to listen for setting changes
        Uri settingsUri = Settings.Global.getUriFor(Settings.Global.KERNEL_CPU_THREAD_READER);
        context.getContentResolver()
                .registerContentObserver(
                        settingsUri, false, settingsObserver, UserHandle.USER_SYSTEM);
        // Return the observer's reader
        return settingsObserver.mKernelCpuThreadReader;
    }

    private KernelCpuThreadReaderSettingsObserver(Context context) {
        super(BackgroundThread.getHandler());
        mContext = context;
        mKernelCpuThreadReader =
                KernelCpuThreadReader.create(
                        NUM_BUCKETS_DEFAULT,
                        UidPredicate.fromString(COLLECTED_UIDS_DEFAULT),
                        MINIMUM_TOTAL_CPU_USAGE_MILLIS_DEFAULT);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri, int userId) {
        updateReader();
    }

    /** Update the reader with new settings */
    private void updateReader() {
        if (mKernelCpuThreadReader == null) {
            return;
        }

        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(
                    Settings.Global.getString(
                            mContext.getContentResolver(),
                            Settings.Global.KERNEL_CPU_THREAD_READER));
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Bad settings", e);
            return;
        }

        final UidPredicate uidPredicate;
        try {
            uidPredicate =
                    UidPredicate.fromString(
                            parser.getString(COLLECTED_UIDS_SETTINGS_KEY, COLLECTED_UIDS_DEFAULT));
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed to get UID predicate", e);
            return;
        }

        mKernelCpuThreadReader.setNumBuckets(
                parser.getInt(NUM_BUCKETS_SETTINGS_KEY, NUM_BUCKETS_DEFAULT));
        mKernelCpuThreadReader.setUidPredicate(uidPredicate);
        mKernelCpuThreadReader.setMinimumTotalCpuUsageMillis(
                parser.getInt(
                        MINIMUM_TOTAL_CPU_USAGE_MILLIS_SETTINGS_KEY,
                        MINIMUM_TOTAL_CPU_USAGE_MILLIS_DEFAULT));
    }

    /** Check whether a UID belongs to a set of UIDs */
    @VisibleForTesting
    public static class UidPredicate implements Predicate<Integer> {
        private static final Pattern UID_RANGE_PATTERN = Pattern.compile("([0-9]+)-([0-9]+)");
        private static final String UID_SPECIFIER_DELIMITER = ";";
        private final List<Range<Integer>> mAcceptedUidRanges;

        /**
         * Create a UID predicate from a string representing a list of UID ranges
         *
         * <p>UID ranges are a pair of integers separated by a '-'. If you want to specify a single
         * UID (e.g. UID 1000), you can use {@code 1000-1000}. Lists of ranges are separated by a
         * single ';'. For example, this would be a valid string representation: {@code
         * "1000-1999;2003-2003;2004-2004;2050-2060"}.
         *
         * <p>We do not use ',' to delimit as it is already used in separating different setting
         * arguments.
         *
         * @throws NumberFormatException if the input string is incorrectly formatted
         * @throws IllegalArgumentException if an UID range has a lower end than start
         */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public static UidPredicate fromString(String predicateString) throws NumberFormatException {
            final List<Range<Integer>> acceptedUidRanges = new ArrayList<>();
            for (String uidSpecifier : predicateString.split(UID_SPECIFIER_DELIMITER)) {
                final Matcher uidRangeMatcher = UID_RANGE_PATTERN.matcher(uidSpecifier);
                if (!uidRangeMatcher.matches()) {
                    throw new NumberFormatException(
                            "Failed to recognize as number range: " + uidSpecifier);
                }
                acceptedUidRanges.add(
                        Range.create(
                                Integer.parseInt(uidRangeMatcher.group(1)),
                                Integer.parseInt(uidRangeMatcher.group(2))));
            }
            return new UidPredicate(acceptedUidRanges);
        }

        private UidPredicate(List<Range<Integer>> acceptedUidRanges) {
            mAcceptedUidRanges = acceptedUidRanges;
        }

        @Override
        @SuppressWarnings("ForLoopReplaceableByForEach")
        public boolean test(Integer uid) {
            for (int i = 0; i < mAcceptedUidRanges.size(); i++) {
                if (mAcceptedUidRanges.get(i).contains(uid)) {
                    return true;
                }
            }
            return false;
        }
    }
}
