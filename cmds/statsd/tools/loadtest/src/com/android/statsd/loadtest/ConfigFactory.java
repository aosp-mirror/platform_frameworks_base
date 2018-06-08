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
package com.android.statsd.loadtest;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import com.android.internal.os.StatsdConfigProto.Predicate;
import com.android.internal.os.StatsdConfigProto.CountMetric;
import com.android.internal.os.StatsdConfigProto.DurationMetric;
import com.android.internal.os.StatsdConfigProto.MetricConditionLink;
import com.android.internal.os.StatsdConfigProto.EventMetric;
import com.android.internal.os.StatsdConfigProto.GaugeMetric;
import com.android.internal.os.StatsdConfigProto.ValueMetric;
import com.android.internal.os.StatsdConfigProto.FieldValueMatcher;
import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.SimplePredicate;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.TimeUnit;

import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates StatsdConfig protos for loadtesting.
 */
public class ConfigFactory {
    public static class ConfigMetadata {
        public final byte[] bytes;
        public final int numMetrics;

        public ConfigMetadata(byte[] bytes, int numMetrics) {
            this.bytes = bytes;
            this.numMetrics = numMetrics;
        }
    }

    public static final long CONFIG_ID = 123456789;

    private static final String TAG = "loadtest.ConfigFactory";

    private final StatsdConfig mTemplate;

    public ConfigFactory(Context context) {
        // Read the config template from the resoures.
        Resources res = context.getResources();
        byte[] template = null;
        StatsdConfig templateProto = null;
        try {
            InputStream inputStream = res.openRawResource(R.raw.loadtest_config);
            template = new byte[inputStream.available()];
            inputStream.read(template);
            templateProto = StatsdConfig.parseFrom(template);
        } catch (IOException e) {
            Log.e(TAG, "Unable to read or parse loadtest config template. Using an empty config.");
        }
        mTemplate = templateProto == null ? StatsdConfig.newBuilder().build() : templateProto;

        Log.d(TAG, "Loadtest template config: " + mTemplate);
    }

    /**
     * Generates a config.
     *
     * All configs are based on the same template.
     * That template is designed to make the most use of the set of atoms that {@code SequencePusher}
     * pushes, and to exercise as many of the metrics features as possible.
     * Furthermore, by passing a replication factor to this method, one can artificially inflate
     * the number of metrics in the config. One can also adjust the bucket size for aggregate
     * metrics.
     *
     * @param replication The number of times each metric is replicated in the config.
     *        If the config template has n metrics, the generated config will have n * replication
     *        ones
     * @param bucketMillis The bucket size, in milliseconds, for aggregate metrics
     * @param placebo If true, only return an empty config
     * @return The serialized config and the number of metrics.
     */
    public ConfigMetadata getConfig(int replication, TimeUnit bucket, boolean placebo,
            boolean includeCount, boolean includeDuration, boolean includeEvent,
            boolean includeValue, boolean includeGauge) {
        StatsdConfig.Builder config = StatsdConfig.newBuilder()
            .setId(CONFIG_ID);
        if (placebo) {
          replication = 0;  // Config will be empty, aside from a name.
        }
        int numMetrics = 0;
        for (int i = 0; i < replication; i++) {
            // metrics
            if (includeEvent) {
                for (EventMetric metric : mTemplate.getEventMetricList()) {
                    addEventMetric(metric, i, config);
                    numMetrics++;
                }
            }
            if (includeCount) {
                for (CountMetric metric : mTemplate.getCountMetricList()) {
                    addCountMetric(metric, i, bucket, config);
                    numMetrics++;
                }
            }
            if (includeDuration) {
                for (DurationMetric metric : mTemplate.getDurationMetricList()) {
                    addDurationMetric(metric, i, bucket, config);
                    numMetrics++;
                }
            }
            if (includeGauge) {
                for (GaugeMetric metric : mTemplate.getGaugeMetricList()) {
                    addGaugeMetric(metric, i, bucket, config);
                    numMetrics++;
                }
            }
            if (includeValue) {
                for (ValueMetric metric : mTemplate.getValueMetricList()) {
                    addValueMetric(metric, i, bucket, config);
                    numMetrics++;
                }
            }
            // predicates
            for (Predicate predicate : mTemplate.getPredicateList()) {
              addPredicate(predicate, i, config);
            }
            // matchers
            for (AtomMatcher matcher : mTemplate.getAtomMatcherList()) {
              addMatcher(matcher, i, config);
            }
        }

        Log.d(TAG, "Loadtest config is : " + config.build());
        Log.d(TAG, "Generated config has " + numMetrics + " metrics");

        return new ConfigMetadata(config.build().toByteArray(), numMetrics);
    }

    /**
     * Creates {@link MetricConditionLink}s that are identical to the one passed to this method,
     * except that the names are appended with the provided suffix.
     */
    private List<MetricConditionLink> getLinks(
        List<MetricConditionLink> links, int suffix) {
        List<MetricConditionLink> newLinks = new ArrayList();
        for (MetricConditionLink link : links) {
            newLinks.add(link.toBuilder()
                .setCondition(link.getCondition() + suffix)
                .build());
        }
        return newLinks;
    }

    /**
     * Creates an {@link EventMetric} based on the template. Makes sure that all names are appended
     * with the provided suffix. Then adds that metric to the config.
     */
    private void addEventMetric(EventMetric template, int suffix, StatsdConfig.Builder config) {
        EventMetric.Builder metric = template.toBuilder()
            .setId(template.getId() + suffix)
            .setWhat(template.getWhat() + suffix);
        if (template.hasCondition()) {
            metric.setCondition(template.getCondition() + suffix);
        }
        if (template.getLinksCount() > 0) {
            List<MetricConditionLink> links = getLinks(template.getLinksList(), suffix);
            metric.clearLinks();
            metric.addAllLinks(links);
        }
        config.addEventMetric(metric);
    }

    /**
     * Creates a {@link CountMetric} based on the template. Makes sure that all names are appended
     * with the provided suffix, and overrides the bucket size. Then adds that metric to the config.
     */
    private void addCountMetric(CountMetric template, int suffix, TimeUnit bucket,
        StatsdConfig.Builder config) {
        CountMetric.Builder metric = template.toBuilder()
            .setId(template.getId() + suffix)
            .setWhat(template.getWhat() + suffix);
        if (template.hasCondition()) {
            metric.setCondition(template.getCondition() + suffix);
        }
        if (template.getLinksCount() > 0) {
            List<MetricConditionLink> links = getLinks(template.getLinksList(), suffix);
            metric.clearLinks();
            metric.addAllLinks(links);
        }
        metric.setBucket(bucket);
        config.addCountMetric(metric);
    }

    /**
     * Creates a {@link DurationMetric} based on the template. Makes sure that all names are appended
     * with the provided suffix, and overrides the bucket size. Then adds that metric to the config.
     */
    private void addDurationMetric(DurationMetric template, int suffix, TimeUnit bucket,
        StatsdConfig.Builder config) {
        DurationMetric.Builder metric = template.toBuilder()
            .setId(template.getId() + suffix)
            .setWhat(template.getWhat() + suffix);
        if (template.hasCondition()) {
            metric.setCondition(template.getCondition() + suffix);
        }
        if (template.getLinksCount() > 0) {
            List<MetricConditionLink> links = getLinks(template.getLinksList(), suffix);
            metric.clearLinks();
            metric.addAllLinks(links);
        }
        metric.setBucket(bucket);
        config.addDurationMetric(metric);
    }

    /**
     * Creates a {@link GaugeMetric} based on the template. Makes sure that all names are appended
     * with the provided suffix, and overrides the bucket size. Then adds that metric to the config.
     */
    private void addGaugeMetric(GaugeMetric template, int suffix, TimeUnit bucket,
        StatsdConfig.Builder config) {
        GaugeMetric.Builder metric = template.toBuilder()
            .setId(template.getId() + suffix)
            .setWhat(template.getWhat() + suffix);
        if (template.hasCondition()) {
            metric.setCondition(template.getCondition() + suffix);
        }
        if (template.getLinksCount() > 0) {
            List<MetricConditionLink> links = getLinks(template.getLinksList(), suffix);
            metric.clearLinks();
            metric.addAllLinks(links);
        }
        metric.setBucket(bucket);
        config.addGaugeMetric(metric);
    }

    /**
     * Creates a {@link ValueMetric} based on the template. Makes sure that all names are appended
     * with the provided suffix, and overrides the bucket size. Then adds that metric to the config.
     */
    private void addValueMetric(ValueMetric template, int suffix, TimeUnit bucket,
        StatsdConfig.Builder config) {
        ValueMetric.Builder metric = template.toBuilder()
            .setId(template.getId() + suffix)
            .setWhat(template.getWhat() + suffix);
        if (template.hasCondition()) {
            metric.setCondition(template.getCondition() + suffix);
        }
        if (template.getLinksCount() > 0) {
            List<MetricConditionLink> links = getLinks(template.getLinksList(), suffix);
            metric.clearLinks();
            metric.addAllLinks(links);
        }
        metric.setBucket(bucket);
        config.addValueMetric(metric);
    }

    /**
     * Creates a {@link Predicate} based on the template. Makes sure that all names
     * are appended with the provided suffix. Then adds that predicate to the config.
     */
    private void addPredicate(Predicate template, int suffix, StatsdConfig.Builder config) {
        Predicate.Builder predicate = template.toBuilder()
            .setId(template.getId() + suffix);
        if (template.hasCombination()) {
            Predicate.Combination.Builder cb = template.getCombination().toBuilder()
                .clearPredicate();
            for (long child : template.getCombination().getPredicateList()) {
                cb.addPredicate(child + suffix);
            }
            predicate.setCombination(cb.build());
        }
        if (template.hasSimplePredicate()) {
            SimplePredicate.Builder sc = template.getSimplePredicate().toBuilder()
                .setStart(template.getSimplePredicate().getStart() + suffix)
                .setStop(template.getSimplePredicate().getStop() + suffix);
            if (template.getSimplePredicate().hasStopAll()) {
                sc.setStopAll(template.getSimplePredicate().getStopAll() + suffix);
            }
            predicate.setSimplePredicate(sc.build());
        }
        config.addPredicate(predicate);
    }

    /**
     * Creates a {@link AtomMatcher} based on the template. Makes sure that all names
     * are appended with the provided suffix. Then adds that matcher to the config.
     */
    private void addMatcher(AtomMatcher template, int suffix, StatsdConfig.Builder config) {
        AtomMatcher.Builder matcher = template.toBuilder()
            .setId(template.getId() + suffix);
        if (template.hasCombination()) {
            AtomMatcher.Combination.Builder cb = template.getCombination().toBuilder()
                .clearMatcher();
            for (long child : template.getCombination().getMatcherList()) {
                cb.addMatcher(child + suffix);
            }
            matcher.setCombination(cb);
        }
        config.addAtomMatcher(matcher);
    }
}
