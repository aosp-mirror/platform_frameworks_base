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

import android.app.Activity;
import androidx.lifecycle.Lifecycle.Event;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import android.content.Intent;

import android.os.SystemClock;
import com.android.internal.logging.nano.MetricsProto;

import static com.android.settingslib.core.instrumentation.Instrumentable.METRICS_CATEGORY_UNKNOWN;

/**
 * Logs visibility change of a fragment.
 */
public class VisibilityLoggerMixin implements LifecycleObserver {

    private static final String TAG = "VisibilityLoggerMixin";

    private final int mMetricsCategory;

    private MetricsFeatureProvider mMetricsFeature;
    private int mSourceMetricsCategory = MetricsProto.MetricsEvent.VIEW_UNKNOWN;
    private long mVisibleTimestamp;

    /**
     * The metrics category constant for logging source when a setting fragment is opened.
     */
    public static final String EXTRA_SOURCE_METRICS_CATEGORY = ":settings:source_metrics";

    private VisibilityLoggerMixin() {
        mMetricsCategory = METRICS_CATEGORY_UNKNOWN;
    }

    public VisibilityLoggerMixin(int metricsCategory, MetricsFeatureProvider metricsFeature) {
        mMetricsCategory = metricsCategory;
        mMetricsFeature = metricsFeature;
    }

    @OnLifecycleEvent(Event.ON_RESUME)
    public void onResume() {
        mVisibleTimestamp = SystemClock.elapsedRealtime();
        if (mMetricsFeature != null && mMetricsCategory != METRICS_CATEGORY_UNKNOWN) {
            mMetricsFeature.visible(null /* context */, mSourceMetricsCategory, mMetricsCategory);
        }
    }

    @OnLifecycleEvent(Event.ON_PAUSE)
    public void onPause() {
        mVisibleTimestamp = 0;
        if (mMetricsFeature != null && mMetricsCategory != METRICS_CATEGORY_UNKNOWN) {
            mMetricsFeature.hidden(null /* context */, mMetricsCategory);
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
        mSourceMetricsCategory = intent.getIntExtra(EXTRA_SOURCE_METRICS_CATEGORY,
                MetricsProto.MetricsEvent.VIEW_UNKNOWN);
    }

    /** Returns elapsed time since onResume() */
    public long elapsedTimeSinceVisible() {
        if (mVisibleTimestamp == 0) {
            return 0;
        }
        return SystemClock.elapsedRealtime() - mVisibleTimestamp;
    }
}
