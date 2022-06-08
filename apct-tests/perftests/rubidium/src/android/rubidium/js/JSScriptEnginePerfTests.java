/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.rubidium.js;

import static com.android.adservices.service.js.JSScriptArgument.numericArg;
import static com.android.adservices.service.js.JSScriptArgument.recordArg;
import static com.android.adservices.service.js.JSScriptArgument.stringArg;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.adservices.service.js.JSScriptArgument;
import com.android.adservices.service.js.JSScriptEngine;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class JSScriptEnginePerfTests {
    private static final String TAG = JSScriptEnginePerfTests.class.getSimpleName();
    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private final ExecutorService mExecutorService = Executors.newFixedThreadPool(10);
    private final JSScriptEngine mJSScriptEngine = new JSScriptEngine(sContext);

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void evaluate_helloWorld() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            String res =
                    callJSEngine(
                            "function test() { return \"hello world\";" + " }",
                            ImmutableList.of(),
                            "test");
            assertThat(res).isEqualTo("\"hello world\"");
        }
    }

    @Test
    public void evaluate_emptyGenerateBid() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.pauseTiming();

        InputStream testJsInputStream = sContext.getAssets().open("empty_generate_bid.js");
        String jsTestFile = new String(testJsInputStream.readAllBytes(), StandardCharsets.UTF_8);
        JSScriptArgument adDataArgument =
                recordArg(
                        "ad",
                        stringArg("render_url", "http://google.com"),
                        recordArg("metadata", numericArg("input", 10)));
        callJSEngine(jsTestFile, ImmutableList.of(adDataArgument), "generateBid");

        state.resumeTiming();
        while (state.keepRunning()) {
            callJSEngine(
                    jsTestFile, ImmutableList.of(adDataArgument), "generateBid");
        }
    }

    @Test
    public void evaluate_turtledoveSampleGenerateBid() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.pauseTiming();

        InputStream testJsInputStream = sContext.getAssets().open("turtledove_generate_bid.js");
        String jsTestFile =
                new String(testJsInputStream.readAllBytes(), StandardCharsets.UTF_8);
        // Initialize the environment with one call.
        callJSEngine(jsTestFile, ImmutableList.of(), "generateBid");

        state.resumeTiming();
        while (state.keepRunning()) {
            callJSEngine(jsTestFile, ImmutableList.of(), "generateBid");
        }
    }

    private String callJSEngine(
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName)
            throws Exception {
        return callJSEngine(mJSScriptEngine, jsScript, args, functionName);
    }

    private String callJSEngine(
            @NonNull JSScriptEngine jsScriptEngine,
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName)
            throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        ListenableFuture<String> futureResult =
                callJSEngineAsync(jsScriptEngine, jsScript, args, functionName, resultLatch);
        resultLatch.await();
        return futureResult.get();
    }

    private ListenableFuture<String> callJSEngineAsync(
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch) {
        return callJSEngineAsync(mJSScriptEngine, jsScript, args, functionName, resultLatch);
    }

    private ListenableFuture<String> callJSEngineAsync(
            @NonNull JSScriptEngine engine,
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(resultLatch);
        ListenableFuture<String> result = engine.evaluate(jsScript, args, functionName);
        result.addListener(resultLatch::countDown, mExecutorService);
        return result;
    }
}
