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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;

/**
 * Manages tracing of WebViews. In particular provides functionality for the app
 * to enable/disable tracing of parts of code and to collect tracing data.
 * This is useful for profiling performance issues, debugging and memory usage
 * analysis in production and real life scenarios.
 * <p>
 * The resulting trace data is sent back as a byte sequence in json format. This
 * file can be loaded in "chrome://tracing" for further analysis.
 * <p>
 * Note: All methods in this class must be called on the UI thread. All callbacks
 * are also called on the UI thread.
 * <p>
 * Example usage:
 * <pre class="prettyprint">
 * TracingController tracingController = TracingController.getInstance();
 * tracingController.start(new TraceConfig(CATEGORIES_WEB_DEVELOPER));
 * [..]
 * tracingController.stopAndFlush(new TraceFileOutput("trace.json"), null);
 * </pre></p>
 */
public abstract class TracingController {

    /**
     * Interface for capturing tracing data.
     */
    public interface TracingOutputStream {
        /**
         * Will be called to return tracing data in chunks.
         * Tracing data is returned in json format an array of bytes.
         */
        void write(byte[] chunk);

        /**
         * Called when tracing is finished and the data collection is over.
         * There will be no calls to #write after #complete is called.
         */
        void complete();
    }

    /**
     * Returns the default TracingController instance. At present there is
     * only one TracingController instance for all WebView instances,
     * however this restriction may be relaxed in the future.
     *
     * @return the default TracingController instance
     */
    @NonNull
    public static TracingController getInstance() {
        return WebViewFactory.getProvider().getTracingController();
    }

    /**
     * Starts tracing all webviews. Depeding on the trace mode in traceConfig
     * specifies how the trace events are recorded.
     *
     * For tracing modes {@link TracingConfig#RECORD_UNTIL_FULL},
     * {@link TracingConfig#RECORD_CONTINUOUSLY} and
     * {@link TracingConfig#RECORD_UNTIL_FULL_LARGE_BUFFER} the events are recorded
     * using an internal buffer and flushed to the outputStream when
     * {@link #stopAndFlush(TracingOutputStream, Handler)} is called.
     *
     * @param tracingConfig configuration options to use for tracing
     * @return false if the system is already tracing, true otherwise.
     */
    public abstract boolean start(TracingConfig tracingConfig);

    /**
     * Stops tracing and discards all tracing data.
     *
     * This method is particularly useful in conjunction with the
     * {@link TracingConfig#RECORD_TO_CONSOLE} tracing mode because tracing data is logged to
     * console and not sent to an outputStream as with
     * {@link #stopAndFlush(TracingOutputStream, Handler)}.
     *
     * @return false if the system was not tracing at the time of the call, true
     *         otherwise.
     */
    public abstract boolean stop();

    /**
     * Stops tracing and flushes tracing data to the specifid outputStream.
     *
     * Note that if the {@link TracingConfig#RECORD_TO_CONSOLE} tracing mode is used
     * nothing will be sent to the outputStream and no TracingOuputStream methods will be
     * called. In that case it is more convenient to just use {@link #stop()} instead.
     *
     * @param outputStream the output steam the tracing data will be sent to.
     * @param handler the {@link android.os.Handler} on which the outputStream callbacks
     *                will be invoked. If the handler is null the current thread's Looper
     *                will be used.
     * @return false if the system was not tracing at the time of the call, true
     *         otherwise.
     */
    public abstract boolean stopAndFlush(TracingOutputStream outputStream,
            @Nullable Handler handler);

    /** True if the system is tracing */
    public abstract boolean isTracing();

    // TODO: consider adding getTraceBufferUsage, percentage and approx event count.
    // TODO: consider adding String getCategories(), for obtaining the actual list
    // of categories used (given that presets are ints).

}
