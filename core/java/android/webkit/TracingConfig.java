/*
 * Copyright 2017 The Android Open Source Project
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

package android.webkit;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Holds tracing configuration information and predefined settings.
 */
public class TracingConfig {

    private @PredefinedCategories int mPredefinedCategories;
    private final List<String> mCustomIncludedCategories = new ArrayList<String>();
    private @TracingMode int mTracingMode;

    /** @hide */
    @IntDef(flag = true, value = {CATEGORIES_NONE, CATEGORIES_WEB_DEVELOPER,
            CATEGORIES_INPUT_LATENCY, CATEGORIES_RENDERING, CATEGORIES_JAVASCRIPT_AND_RENDERING,
            CATEGORIES_FRAME_VIEWER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PredefinedCategories {}

    /**
     * Indicates that there are no predefined categories.
     */
    public static final int CATEGORIES_NONE = 0;

    /**
     * Predefined set of categories, includes all categories enabled by default in chromium.
     * Use with caution: this setting may produce large trace output.
     */
    public static final int CATEGORIES_ALL = 1 << 0;

    /**
     * Predefined set of categories typically useful for analyzing WebViews.
     * Typically includes android_webview and Java.
     */
    public static final int CATEGORIES_ANDROID_WEBVIEW = 1 << 1;

    /**
     * Predefined set of categories typically useful for web developers.
     * Typically includes blink, compositor, renderer.scheduler and v8 categories.
     */
    public static final int CATEGORIES_WEB_DEVELOPER = 1 << 2;

    /**
     * Predefined set of categories for analyzing input latency issues.
     * Typically includes input, renderer.scheduler categories.
     */
    public static final int CATEGORIES_INPUT_LATENCY = 1 << 3;

    /**
     * Predefined set of categories for analyzing rendering issues.
     * Typically includes blink, compositor and gpu categories.
     */
    public static final int CATEGORIES_RENDERING = 1 << 4;

    /**
     * Predefined set of categories for analyzing javascript and rendering issues.
     * Typically includes blink, compositor, gpu, renderer.scheduler and v8 categories.
     */
    public static final int CATEGORIES_JAVASCRIPT_AND_RENDERING = 1 << 5;

    /**
     * Predefined set of categories for studying difficult rendering performance problems.
     * Typically includes blink, compositor, gpu, renderer.scheduler, v8 and
     * some other compositor categories which are disabled by default.
     */
    public static final int CATEGORIES_FRAME_VIEWER = 1 << 6;

    /** @hide */
    @IntDef({RECORD_UNTIL_FULL, RECORD_CONTINUOUSLY, RECORD_UNTIL_FULL_LARGE_BUFFER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TracingMode {}

    /**
     * Record trace events until the internal tracing buffer is full. Default tracing mode.
     * Typically the buffer memory usage is between {@link #RECORD_CONTINUOUSLY} and the
     * {@link #RECORD_UNTIL_FULL_LARGE_BUFFER}. Depending on the implementation typically allows
     * up to 256k events to be stored.
     */
    public static final int RECORD_UNTIL_FULL = 0;

    /**
     * Record trace events continuously using an internal ring buffer. Overwrites
     * old events if they exceed buffer capacity. Uses less memory than both
     * {@link #RECORD_UNTIL_FULL} and {@link #RECORD_UNTIL_FULL_LARGE_BUFFER} modes.
     * Depending on the implementation typically allows up to 64k events to be stored.
     */
    public static final int RECORD_CONTINUOUSLY = 1;

    /**
     * Record trace events using a larger internal tracing buffer until it is full.
     * Uses significantly more memory than {@link #RECORD_UNTIL_FULL} and may not be
     * suitable on devices with smaller RAM.
     */
    public static final int RECORD_UNTIL_FULL_LARGE_BUFFER = 2;

    /**
     * @hide
     */
    public TracingConfig(@PredefinedCategories int predefinedCategories,
            @NonNull List<String> customIncludedCategories,
            @TracingMode int tracingMode) {
        mPredefinedCategories = predefinedCategories;
        mCustomIncludedCategories.addAll(customIncludedCategories);
        mTracingMode = tracingMode;
    }

    /**
     * Returns a bitmask of the predefined categories values of this configuration.
     */
    @PredefinedCategories
    public int getPredefinedCategories() {
        return mPredefinedCategories;
    }

    /**
     * Returns the list of included custom category patterns for this configuration.
     *
     * @return empty list if no custom category patterns are specified.
     */
    @NonNull
    public List<String> getCustomIncludedCategories() {
        return mCustomIncludedCategories;
    }

    /**
     * Returns the tracing mode of this configuration.
     */
    @TracingMode
    public int getTracingMode() {
        return mTracingMode;
    }

    /**
     * Builder used to create {@link TracingConfig} objects.
     *
     * Examples:
     *   new TracingConfig.Builder().build()
     *       -- creates a configuration with default options: {@link #CATEGORIES_NONE},
     *          {@link #RECORD_UNTIL_FULL}.
     *   new TracingConfig.Builder().addCategories(CATEGORIES_WEB_DEVELOPER).build()
     *       -- records trace events from the "web developer" predefined category sets.
     *   new TracingConfig.Builder().addCategories(CATEGORIES_RENDERING,
     *                                             CATEGORIES_INPUT_LATENCY).build()
     *       -- records trace events from the "rendering" and "input latency" predefined
     *          category sets.
     *   new TracingConfig.Builder().addCategories("browser").build()
     *       -- records only the trace events from the "browser" category.
     *   new TracingConfig.Builder().addCategories("blink*","renderer*").build()
     *       -- records only the trace events matching the "blink*" and "renderer*" patterns
     *          (e.g. "blink.animations", "renderer_host" and "renderer.scheduler" categories).
     *   new TracingConfig.Builder().addCategories(CATEGORIES_WEB_DEVELOPER)
     *                              .addCategories("disabled-by-default-v8.gc")
     *                              .setTracingMode(RECORD_CONTINUOUSLY).build()
     *       -- records events from the "web developer" predefined category set and events from
     *          the "disabled-by-default-v8.gc" category to understand where garbage collection
     *          is being triggered. Uses a ring buffer for internal storage during tracing.
     */
    public static class Builder {
        private @PredefinedCategories int mPredefinedCategories = CATEGORIES_NONE;
        private final List<String> mCustomIncludedCategories = new ArrayList<String>();
        private @TracingMode int mTracingMode = RECORD_UNTIL_FULL;

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Build {@link TracingConfig} using the current settings.
         */
        public TracingConfig build() {
            return new TracingConfig(mPredefinedCategories, mCustomIncludedCategories,
                    mTracingMode);
        }

        /**
         * Adds categories from a predefined set of categories to be included in the trace output.
         *
         * @param predefinedCategories list or bitmask of predefined category sets to use:
         *                    {@link #CATEGORIES_NONE}, {@link #CATEGORIES_ALL},
         *                    {@link #CATEGORIES_WEB_DEVELOPER}, {@link #CATEGORIES_INPUT_LATENCY},
         *                    {@link #CATEGORIES_RENDERING},
         *                    {@link #CATEGORIES_JAVASCRIPT_AND_RENDERING} or
         *                    {@link #CATEGORIES_FRAME_VIEWER}.
         * @return The builder to facilitate chaining.
         */
        public Builder addCategories(@PredefinedCategories int... predefinedCategories) {
            for (int categorySet : predefinedCategories) {
                mPredefinedCategories |= categorySet;
            }
            return this;
        }

        /**
         * Adds custom categories to be included in trace output.
         *
         * Note that the categories are defined by the currently-in-use version of WebView. They
         * live in chromium code and are not part of the Android API. See
         * See <a href="https://www.chromium.org/developers/how-tos/trace-event-profiling-tool">
         * chromium documentation on tracing</a> for more details.
         *
         * @param categories a list of category patterns. A category pattern can contain wilcards,
         *        e.g. "blink*" or full category name e.g. "renderer.scheduler".
         * @return The builder to facilitate chaining.
         */
        public Builder addCategories(String... categories) {
            for (String category: categories) {
                mCustomIncludedCategories.add(category);
            }
            return this;
        }

        /**
         * Adds custom categories to be included in trace output.
         *
         * Same as {@link #addCategories(String...)} but allows to pass a Collection as a parameter.
         *
         * @param categories a list of category patters.
         * @return The builder to facilitate chaining.
         */
        public Builder addCategories(Collection<String> categories) {
            mCustomIncludedCategories.addAll(categories);
            return this;
        }

        /**
         * Sets the tracing mode for this configuration.
         *
         * @param tracingMode tracing mode to use, one of {@link #RECORD_UNTIL_FULL},
         *                    {@link #RECORD_CONTINUOUSLY} or
         *                    {@link #RECORD_UNTIL_FULL_LARGE_BUFFER}.
         * @return The builder to facilitate chaining.
         */
        public Builder setTracingMode(@TracingMode int tracingMode) {
            mTracingMode = tracingMode;
            return this;
        }
    }

}
