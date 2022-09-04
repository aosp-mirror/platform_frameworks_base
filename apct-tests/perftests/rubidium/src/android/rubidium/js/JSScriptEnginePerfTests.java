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

import static com.android.adservices.service.js.JSScriptArgument.arrayArg;
import static com.android.adservices.service.js.JSScriptArgument.numericArg;
import static com.android.adservices.service.js.JSScriptArgument.recordArg;
import static com.android.adservices.service.js.JSScriptArgument.stringArg;
import static com.android.adservices.service.js.JSScriptArgument.stringArrayArg;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.annotation.SuppressLint;
import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.adservices.service.js.IsolateSettings;
import com.android.adservices.service.js.JSScriptArgument;
import com.android.adservices.service.js.JSScriptArrayArgument;
import com.android.adservices.service.js.JSScriptEngine;
import com.android.adservices.service.js.JSScriptRecordArgument;
import com.android.adservices.service.profiling.JSScriptEngineLogConstants;
import com.android.adservices.service.profiling.Profiler;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** To run the unit tests for this class, run "atest RubidiumPerfTests:JSScriptEnginePerfTests" */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class JSScriptEnginePerfTests {
    private static final String TAG = JSScriptEngine.TAG;
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final ExecutorService sExecutorService = Executors.newFixedThreadPool(10);

    private static final JSScriptEngine sJSScriptEngine =
            JSScriptEngine.getInstanceForTesting(
                    sContext, Profiler.createInstance(JSScriptEngine.TAG));

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Before
    public void before() throws Exception {
        // Warm up the sandbox env.
        callJSEngine(
                "function test() { return \"hello world\";" + " }", ImmutableList.of(), "test");
    }

    @After
    public void after() {
        sJSScriptEngine.shutdown();
    }

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
            callJSEngine(jsTestFile, ImmutableList.of(adDataArgument), "generateBid");
        }
    }

    @Test
    public void evaluate_turtledoveSampleGenerateBid() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.pauseTiming();

        InputStream testJsInputStream = sContext.getAssets().open("turtledove_generate_bid.js");
        String jsTestFile = new String(testJsInputStream.readAllBytes(), StandardCharsets.UTF_8);
        // Initialize the environment with one call.
        callJSEngine(jsTestFile, ImmutableList.of(), "generateBid");

        state.resumeTiming();
        while (state.keepRunning()) {
            callJSEngine(jsTestFile, ImmutableList.of(), "generateBid");
        }
    }

    @Test
    public void evaluate_turtledoveSampleGenerateBid_parametrized_10Ads() throws Exception {
        runParametrizedTurtledoveScript(10);
    }

    @Test
    public void evaluate_turtledoveSampleGenerateBid_parametrized_25Ads() throws Exception {
        runParametrizedTurtledoveScript(25);
    }

    @Test
    public void evaluate_turtledoveSampleGenerateBid_parametrized_50Ads() throws Exception {
        runParametrizedTurtledoveScript(50);
    }

    @Test
    public void evaluate_turtledoveSampleGenerateBid_parametrized_75Ads() throws Exception {
        runParametrizedTurtledoveScript(75);
    }

    @SuppressLint("DefaultLocale")
    private void runParametrizedTurtledoveScript(int numAds) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.pauseTiming();
        InputStream testJsInputStream =
                sContext.getAssets().open("turtledove_parametrized_generateBid.js");
        String jsTestFile = new String(testJsInputStream.readAllBytes(), StandardCharsets.UTF_8);

        state.resumeTiming();
        while (state.keepRunning()) {
            int numInterestGroups = 1;
            String res =
                    callJSEngine(
                            sJSScriptEngine,
                            jsTestFile,
                            ImmutableList.of(
                                    buildSampleInterestGroupArg(numInterestGroups, numAds)),
                            "generateBid");

            // I modified the Turtledove script to have the total execution time
            // (across all IGs) as the last element in the response array.
            JSONArray obj = new JSONArray(res);
            long webviewExecTime = obj.getJSONObject(obj.length() - 1).getLong("generateBidTime");
            String webviewExecTimeLog =
                    String.format(
                            "(%s: %d)",
                            JSScriptEngineLogConstants.WEBVIEW_EXECUTION_TIME, webviewExecTime);
            // The listener picks up logs from JSScriptEngine, so simulate logging from there.
            Log.d("JSScriptEngine", webviewExecTimeLog);
        }
    }

    private JSScriptArrayArgument<JSScriptRecordArgument> buildSampleInterestGroupArg(
            int numCustomAudiences, int numAds) {
        JSScriptRecordArgument ad =
                recordArg(
                        "foo",
                        ImmutableList.of(
                                stringArg(
                                        "renderUrl",
                                        "https://googleads.g.doubleclick.net/ads/simple-ad.html?adg_id=52836427830&cr_id=310927197297&cv_id=4"),
                                stringArrayArg(
                                        "metadata",
                                        ImmutableList.of(
                                                "52836427830", "310927197297", "4", "608936333"))));

        JSScriptRecordArgument interestGroupArg =
                recordArg(
                        "foo",
                        stringArg("owner", "https://googleads.g.doubleclick.net/"),
                        stringArg("name", "1j115753478"),
                        stringArg("biddingLogicUrl", "https://googleads.g.doubleclick.net/td/bjs"),
                        stringArg(
                                "dailyUpdateUrl", "https://googleads.g.doubleclick.net/td/update"),
                        stringArg(
                                "trustedBiddingSignalsUrl",
                                "https://googleads.g.doubleclick.net/td/sjs"),
                        stringArrayArg(
                                "trustedBiddingSignalsKeys", ImmutableList.of("1j115753478")),
                        stringArrayArg("userBiddingSignals", ImmutableList.of()),
                        new JSScriptArrayArgument("ads", Collections.nCopies(numAds, ad)));

        return arrayArg("foo", Collections.nCopies(numCustomAudiences, interestGroupArg));
    }

    @Test
    public void evaluate_turtledoveWasm() throws Exception {
        assumeTrue(sJSScriptEngine.isWasmSupported().get(3, TimeUnit.SECONDS));

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.pauseTiming();

        String jsTestFile = readAsset("generate_bid_using_wasm.js");
        byte[] wasmTestFile = readBinaryAsset("generate_bid.wasm");
        JSScriptArgument[] inputBytes = new JSScriptArgument[200];
        Random rand = new Random();
        for (int i = 0; i < inputBytes.length; i++) {
            byte value = (byte) (rand.nextInt(2 * Byte.MAX_VALUE) - Byte.MIN_VALUE);
            inputBytes[i] = JSScriptArgument.numericArg("_", value);
        }
        JSScriptArgument adDataArgument =
                recordArg(
                        "ad",
                        stringArg("render_url", "http://google.com"),
                        recordArg("metadata", JSScriptArgument.arrayArg("input", inputBytes)));

        state.resumeTiming();
        while (state.keepRunning()) {
            callJSEngine(jsTestFile, wasmTestFile, ImmutableList.of(adDataArgument), "generateBid");
        }
    }

    private String callJSEngine(
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName)
            throws Exception {
        return callJSEngine(sJSScriptEngine, jsScript, args, functionName);
    }

    private String callJSEngine(
            @NonNull String jsScript,
            @NonNull byte[] wasmScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName)
            throws Exception {
        return callJSEngine(sJSScriptEngine, jsScript, wasmScript, args, functionName);
    }

    private static String callJSEngine(
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

    private String callJSEngine(
            @NonNull JSScriptEngine jsScriptEngine,
            @NonNull String jsScript,
            @NonNull byte[] wasmScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName)
            throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        ListenableFuture<String> futureResult =
                callJSEngineAsync(
                        jsScriptEngine, jsScript, wasmScript, args, functionName, resultLatch);
        resultLatch.await();
        return futureResult.get();
    }

    private static ListenableFuture<String> callJSEngineAsync(
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch) {
        return callJSEngineAsync(sJSScriptEngine, jsScript, args, functionName, resultLatch);
    }

    private static ListenableFuture<String> callJSEngineAsync(
            @NonNull JSScriptEngine engine,
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(resultLatch);
        ListenableFuture<String> result = engine.evaluate(
                jsScript,
                args,
                functionName,
                IsolateSettings.forMaxHeapSizeEnforcementDisabled());
        result.addListener(resultLatch::countDown, sExecutorService);
        return result;
    }

    private ListenableFuture<String> callJSEngineAsync(
            @NonNull JSScriptEngine engine,
            @NonNull String jsScript,
            @NonNull byte[] wasmScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(resultLatch);
        ListenableFuture<String> result = engine.evaluate(
                jsScript,
                wasmScript,
                args,
                functionName,
                IsolateSettings.forMaxHeapSizeEnforcementDisabled());
        result.addListener(resultLatch::countDown, sExecutorService);
        return result;
    }

    private byte[] readBinaryAsset(@NonNull String assetName) throws IOException {
        return sContext.getAssets().open(assetName).readAllBytes();
    }

    private String readAsset(@NonNull String assetName) throws IOException {
        return new String(readBinaryAsset(assetName), StandardCharsets.UTF_8);
    }
}
