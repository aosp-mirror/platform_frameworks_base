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

/**
 * Holds tracing configuration information and predefined settings.
 */
public class TracingConfig {

    private final String mCustomCategoryPattern;
    private final @PresetCategories int mPresetCategories;
    private @TracingMode int mTracingMode;

    /** @hide */
    @IntDef({CATEGORIES_NONE, CATEGORIES_WEB_DEVELOPER, CATEGORIES_INPUT_LATENCY,
            CATEGORIES_RENDERING, CATEGORIES_JAVASCRIPT_AND_RENDERING, CATEGORIES_FRAME_VIEWER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PresetCategories {}

    /**
     * Indicates that there are no preset categories.
     */
    public static final int CATEGORIES_NONE = -1;

    /**
     * Predefined categories typically useful for web developers.
     * Typically includes blink, compositor, renderer.scheduler and v8 categories.
     */
    public static final int CATEGORIES_WEB_DEVELOPER = 0;

    /**
     * Predefined categories for analyzing input latency issues.
     * Typically includes input, renderer.scheduler categories.
     */
    public static final int CATEGORIES_INPUT_LATENCY = 1;

    /**
     * Predefined categories for analyzing rendering issues.
     * Typically includes blink, compositor and gpu categories.
     */
    public static final int CATEGORIES_RENDERING = 2;

    /**
     * Predefined categories for analyzing javascript and rendering issues.
     * Typically includes blink, compositor, gpu, renderer.schduler and v8 categories.
     */
    public static final int CATEGORIES_JAVASCRIPT_AND_RENDERING = 3;

    /**
     * Predefined categories for studying difficult rendering performance problems.
     * Typically includes blink, compositor, gpu, renderer.scheduler, v8 and
     * some other compositor categories which are disabled by default.
     */
    public static final int CATEGORIES_FRAME_VIEWER = 4;

    /** @hide */
    @IntDef({RECORD_UNTIL_FULL, RECORD_CONTINUOUSLY, RECORD_UNTIL_FULL_LARGE_BUFFER,
            RECORD_TO_CONSOLE})
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
     * Uses more memory than the other modes and may not be suitable on devices
     * with smaller RAM. Depending on the implementation typically allows up to
     * 512 million events to be stored.
     */
    public static final int RECORD_UNTIL_FULL_LARGE_BUFFER = 2;

    /**
     * Record trace events to console (logcat). The events are discarded and nothing
     * is sent back to the caller. Uses the least memory as compared to the other modes.
     */
    public static final int RECORD_TO_CONSOLE = 3;

    /**
     * Create config with the preset categories.
     * <p>
     * Example:
     *    TracingConfig(CATEGORIES_WEB_DEVELOPER) -- records trace events from the "web developer"
     *                                               preset categories.
     *
     * @param presetCategories preset categories to use, one of {@link #CATEGORIES_WEB_DEVELOPER},
     *                    {@link #CATEGORIES_INPUT_LATENCY}, {@link #CATEGORIES_RENDERING},
     *                    {@link #CATEGORIES_JAVASCRIPT_AND_RENDERING} or
     *                    {@link #CATEGORIES_FRAME_VIEWER}.
     *
     * Note: for specifying custom categories without presets use
     * {@link #TracingConfig(int, String, int)}.
     *
     */
    public TracingConfig(@PresetCategories int presetCategories) {
        this(presetCategories, "", RECORD_UNTIL_FULL);
    }

    /**
     * Create a configuration with both preset categories and custom categories.
     * Also allows to specify the tracing mode.
     *
     * Note that the categories are defined by the currently-in-use version of WebView. They live
     * in chromium code and are not part of the Android API. See
     * See <a href="https://www.chromium.org/developers/how-tos/trace-event-profiling-tool">
     * chromium documentation on tracing</a> for more details.
     *
     * <p>
     * Examples:
     *
     *  Preset category with a specified trace mode:
     *    TracingConfig(CATEGORIES_WEB_DEVELOPER, "", RECORD_UNTIL_FULL_LARGE_BUFFER);
     *  Custom categories:
     *    TracingConfig(CATEGORIES_NONE, "browser", RECORD_UNTIL_FULL)
     *      -- records only the trace events from the "browser" category.
     *    TraceConfig(CATEGORIES_NONE, "-input,-gpu", RECORD_UNTIL_FULL)
     *      -- records all trace events excluding the events from the "input" and 'gpu' categories.
     *    TracingConfig(CATEGORIES_NONE, "blink*,devtools*", RECORD_UNTIL_FULL)
     *      -- records only the trace events matching the "blink*" and "devtools*" patterns
     *         (e.g. "blink_gc" and "devtools.timeline" categories).
     *
     *  Combination of preset and additional custom categories:
     *    TracingConfig(CATEGORIES_WEB_DEVELOPER, "memory-infra", RECORD_CONTINUOUSLY)
     *      -- records events from the "web developer" categories and events from the "memory-infra"
     *         category to understand where memory is being used.
     *
     * @param presetCategories preset categories to use, one of {@link #CATEGORIES_WEB_DEVELOPER},
     *                    {@link #CATEGORIES_INPUT_LATENCY}, {@link #CATEGORIES_RENDERING},
     *                    {@link #CATEGORIES_JAVASCRIPT_AND_RENDERING} or
     *                    {@link #CATEGORIES_FRAME_VIEWER}.
     * @param customCategories a comma-delimited list of category wildcards. A category can
     *                         have an optional '-' prefix to make it an excluded category.
     * @param tracingMode tracing mode to use, one of {@link #RECORD_UNTIL_FULL},
     *                    {@link #RECORD_CONTINUOUSLY}, {@link #RECORD_UNTIL_FULL_LARGE_BUFFER}
     *                    or {@link #RECORD_TO_CONSOLE}.
     */
    public TracingConfig(@PresetCategories int presetCategories,
            @NonNull String customCategories, @TracingMode int tracingMode) {
        mPresetCategories = presetCategories;
        mCustomCategoryPattern = customCategories;
        mTracingMode = RECORD_UNTIL_FULL;
    }

    /**
     * Returns the custom category pattern for this configuration.
     *
     * @return empty string if no custom category pattern is specified.
     */
    @NonNull
    public String getCustomCategoryPattern() {
        return mCustomCategoryPattern;
    }

    /**
     * Returns the preset categories value of this configuration.
     */
    @PresetCategories
    public int getPresetCategories() {
        return mPresetCategories;
    }

    /**
     * Returns the tracing mode of this configuration.
     */
    @TracingMode
    public int getTracingMode() {
        return mTracingMode;
    }

}
