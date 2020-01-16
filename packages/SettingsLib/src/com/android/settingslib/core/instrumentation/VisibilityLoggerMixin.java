/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.core.instrumentation;

import static com.android.settingslib.core.instrumentation.Instrumentable.METRICS_CATEGORY_UNKNOWN;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle.Event;
import androidx.lifecycle.OnLifecycleEvent;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnAttach;

/**
 * Logs visibility change of a fragment.
 */
public class VisibilityLoggerMixin implements LifecycleObserver, OnAttach {

    private static final String TAG = "VisibilityLoggerMixin";

    private final int mMetricsCategory;

    private MetricsFeatureProvider mMetricsFeature;
    private int mSourceMetricsCategory = MetricsProto.MetricsEvent.VIEW_UNKNOWN;
    private long mCreationTimestamp;
    private long mVisibleTimestamp;

    public VisibilityLoggerMixin(int metricsCategory, MetricsFeatureProvider metricsFeature) {
        mMetricsCategory = metricsCategory;
        mMetricsFeature = metricsFeature;
    }

    @Override
    public void onAttach() {
        mCreationTimestamp = SystemClock.elapsedRealtime();
    }

    @OnLifecycleEvent(Event.ON_RESUME)
    public void onResume() {
        if (mMetricsFeature == null || mMetricsCategory == METRICS_CATEGORY_UNKNOWN) {
            return;
        }
        mVisibleTimestamp = SystemClock.elapsedRealtime();
        if (mCreationTimestamp != 0L) {
            final int elapse = (int) (mVisibleTimestamp - mCreationTimestamp);
            mMetricsFeature.visible(null /* context */, mSourceMetricsCategory,
                    mMetricsCategory, elapse);
        } else {
            mMetricsFeature.visible(null /* context */, mSourceMetricsCategory,
                    mMetricsCategory, 0);
        }
    }

    @OnLifecycleEvent(Event.ON_PAUSE)
    public void onPause() {
        mCreationTimestamp = 0;
        if (mMetricsFeature != null && mMetricsCategory != METRICS_CATEGORY_UNKNOWN) {
            final int elapse = (int) (SystemClock.elapsedRealtime() - mVisibleTimestamp);
            mMetricsFeature.hidden(null /* context */, mMetricsCategory, elapse);
        }
    }

    /**
     * Logs the elapsed time from onAttach to calling {@link #writeElapsedTimeMetric(int, String)}.
     * @param action : The value of the Action Enums.
     * @param key : The value of special key string.
     */
    public void writeElapsedTimeMetric(int action, String key) {
        if (mMetricsFeature == null || mMetricsCategory == METRICS_CATEGORY_UNKNOWN) {
            return;
        }
        if (mCreationTimestamp != 0L) {
            final int elapse = (int) (SystemClock.elapsedRealtime() - mCreationTimestamp);
            mMetricsFeature.action(METRICS_CATEGORY_UNKNOWN, action, mMetricsCategory, key, elapse);
        }
    }

    /**
     * Sets source metrics category for this logger. Source is the caller that opened this UI.
     */
    public void setSourceMetricsCategory(Activity activity) {
        if (mSourceMetricsCategory != MetricsProto.MetricsEvent.VIEW_UNKNOWN || activity == null) {
            return;
        }
        final Intent intent = activity.getIntent();
        if (intent == null) {
            return;
        }
        mSourceMetricsCategory = intent.getIntExtra(
                MetricsFeatureProvider.EXTRA_SOURCE_METRICS_CATEGORY,
                MetricsProto.MetricsEvent.VIEW_UNKNOWN);
    }
}
