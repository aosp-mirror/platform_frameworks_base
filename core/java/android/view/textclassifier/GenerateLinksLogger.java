/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.Nullable;
import android.metrics.LogMaker;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.Preconditions;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * A helper for logging calls to generateLinks.
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class GenerateLinksLogger {

    private static final String LOG_TAG = "GenerateLinksLogger";
    private static final String ZERO = "0";

    private final MetricsLogger mMetricsLogger;
    private final Random mRng;
    private final int mSampleRate;

    /**
     * @param sampleRate the rate at which log events are written. (e.g. 100 means there is a 0.01
     *                   chance that a call to logGenerateLinks results in an event being written).
     *                   To write all events, pass 1.
     */
    public GenerateLinksLogger(int sampleRate) {
        mSampleRate = sampleRate;
        mRng = new Random(System.nanoTime());
        mMetricsLogger = new MetricsLogger();
    }

    @VisibleForTesting
    public GenerateLinksLogger(int sampleRate, MetricsLogger metricsLogger) {
        mSampleRate = sampleRate;
        mRng = new Random(System.nanoTime());
        mMetricsLogger = metricsLogger;
    }

    /** Logs statistics about a call to generateLinks. */
    public void logGenerateLinks(CharSequence text, TextLinks links, String callingPackageName,
            long latencyMs) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(links);
        Preconditions.checkNotNull(callingPackageName);
        if (!shouldLog()) {
            return;
        }

        // Always populate the total stats, and per-entity stats for each entity type detected.
        final LinkifyStats totalStats = new LinkifyStats();
        final Map<String, LinkifyStats> perEntityTypeStats = new ArrayMap<>();
        for (TextLinks.TextLink link : links.getLinks()) {
            if (link.getEntityCount() == 0) continue;
            final String entityType = link.getEntity(0);
            if (entityType == null
                    || TextClassifier.TYPE_OTHER.equals(entityType)
                    || TextClassifier.TYPE_UNKNOWN.equals(entityType)) {
                continue;
            }
            totalStats.countLink(link);
            perEntityTypeStats.computeIfAbsent(entityType, k -> new LinkifyStats()).countLink(link);
        }

        final String callId = UUID.randomUUID().toString();
        writeStats(callId, callingPackageName, null, totalStats, text, latencyMs);
        for (Map.Entry<String, LinkifyStats> entry : perEntityTypeStats.entrySet()) {
            writeStats(callId, callingPackageName, entry.getKey(), entry.getValue(), text,
                       latencyMs);
        }
    }

    /**
     * Returns whether this particular event should be logged.
     *
     * Sampling is used to reduce the amount of logging data generated.
     **/
    private boolean shouldLog() {
        if (mSampleRate <= 1) {
            return true;
        } else {
            return mRng.nextInt(mSampleRate) == 0;
        }
    }

    /** Writes a log event for the given stats. */
    private void writeStats(String callId, String callingPackageName, @Nullable String entityType,
                            LinkifyStats stats, CharSequence text, long latencyMs) {
        final LogMaker log = new LogMaker(MetricsEvent.TEXT_CLASSIFIER_GENERATE_LINKS)
                .setPackageName(callingPackageName)
                .addTaggedData(MetricsEvent.FIELD_LINKIFY_CALL_ID, callId)
                .addTaggedData(MetricsEvent.FIELD_LINKIFY_NUM_LINKS, stats.mNumLinks)
                .addTaggedData(MetricsEvent.FIELD_LINKIFY_LINK_LENGTH, stats.mNumLinksTextLength)
                .addTaggedData(MetricsEvent.FIELD_LINKIFY_TEXT_LENGTH, text.length())
                .addTaggedData(MetricsEvent.FIELD_LINKIFY_LATENCY, latencyMs);
        if (entityType != null) {
            log.addTaggedData(MetricsEvent.FIELD_LINKIFY_ENTITY_TYPE, entityType);
        }
        mMetricsLogger.write(log);
        debugLog(log);
    }

    private static void debugLog(LogMaker log) {
        if (!Log.ENABLE_FULL_LOGGING) {
            return;
        }
        final String callId = Objects.toString(
                log.getTaggedData(MetricsEvent.FIELD_LINKIFY_CALL_ID), "");
        final String entityType = Objects.toString(
                log.getTaggedData(MetricsEvent.FIELD_LINKIFY_ENTITY_TYPE), "ANY_ENTITY");
        final int numLinks = Integer.parseInt(
                Objects.toString(log.getTaggedData(MetricsEvent.FIELD_LINKIFY_NUM_LINKS), ZERO));
        final int linkLength = Integer.parseInt(
                Objects.toString(log.getTaggedData(MetricsEvent.FIELD_LINKIFY_LINK_LENGTH), ZERO));
        final int textLength = Integer.parseInt(
                Objects.toString(log.getTaggedData(MetricsEvent.FIELD_LINKIFY_TEXT_LENGTH), ZERO));
        final int latencyMs = Integer.parseInt(
                Objects.toString(log.getTaggedData(MetricsEvent.FIELD_LINKIFY_LATENCY), ZERO));

        Log.v(LOG_TAG,
                String.format(Locale.US, "%s:%s %d links (%d/%d chars) %dms %s", callId, entityType,
                        numLinks, linkLength, textLength, latencyMs, log.getPackageName()));
    }

    /** Helper class for storing per-entity type statistics. */
    private static final class LinkifyStats {
        int mNumLinks;
        int mNumLinksTextLength;

        void countLink(TextLinks.TextLink link) {
            mNumLinks += 1;
            mNumLinksTextLength += link.getEnd() - link.getStart();
        }
    }
}
