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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * Manages tracing of WebViews. In particular provides functionality for the app
 * to enable/disable tracing of parts of code and to collect tracing data.
 * This is useful for profiling performance issues, debugging and memory usage
 * analysis in production and real life scenarios.
 * <p>
 * The resulting trace data is sent back as a byte sequence in json format. This
 * file can be loaded in "chrome://tracing" for further analysis.
 * <p>
 * Example usage:
 * <pre class="prettyprint">
 * TracingController tracingController = TracingController.getInstance();
 * tracingController.start(new TraceConfig.Builder()
 *                  .addCategories(CATEGORIES_WEB_DEVELOPER).build());
 * [..]
 * tracingController.stop(new FileOutputStream("trace.json"),
 *                        Executors.newSingleThreadExecutor());
 * </pre></p>
 */
public abstract class TracingController {

    /**
     * Returns the default TracingController instance. At present there is
     * only one TracingController instance for all WebView instances,
     * however this restriction may be relaxed in a future Android release.
     *
     * @return the default TracingController instance
     */
    @NonNull
    public static TracingController getInstance() {
        return WebViewFactory.getProvider().getTracingController();
    }

    /**
     * Starts tracing all webviews. Depending on the trace mode in traceConfig
     * specifies how the trace events are recorded.
     *
     * For tracing modes {@link TracingConfig#RECORD_UNTIL_FULL},
     * {@link TracingConfig#RECORD_CONTINUOUSLY} and
     * {@link TracingConfig#RECORD_UNTIL_FULL_LARGE_BUFFER} the events are recorded
     * using an internal buffer and flushed to the outputStream when
     * {@link #stop(OutputStream, Executor)} is called.
     *
     * @param tracingConfig configuration options to use for tracing
     * @throws IllegalStateException if the system is already tracing.
     */
    public abstract void start(@NonNull TracingConfig tracingConfig);

    /**
     * Stops tracing and flushes tracing data to the specified outputStream.
     *
     * The data is sent to the specified output stream in json format typically
     * in chunks by invoking {@link java.io.OutputStream#write(byte[])}. On completion
     * the {@link java.io.OutputStream#close()} method is called.
     *
     * @param outputStream the output steam the tracing data will be sent to. If null
     *                     the tracing data will be discarded.
     * @param executor the {@link java.util.concurrent.Executor} on which the
     *        outputStream #write and #close methods will be invoked.
     * @return false if the system was not tracing at the time of the call, true
     *         otherwise.
     */
    public abstract boolean stop(@Nullable OutputStream outputStream,
            @NonNull @CallbackExecutor Executor executor);

    /** True if the system is tracing */
    public abstract boolean isTracing();

}
