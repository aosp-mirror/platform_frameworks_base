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
import static com.android.adservices.service.js.JSScriptArgument.jsonArg;
import static com.android.adservices.service.js.JSScriptArgument.numericArg;
import static com.android.adservices.service.js.JSScriptArgument.recordArg;
import static com.android.adservices.service.js.JSScriptArgument.stringArg;
import static com.android.adservices.service.js.JSScriptArgument.stringArrayArg;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdWithBid;
import android.adservices.common.AdData;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdTechIdentifier;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.CustomAudienceSignals;
import com.android.adservices.service.adselection.AdCounterKeyCopier;
import com.android.adservices.service.adselection.AdCounterKeyCopierNoOpImpl;
import com.android.adservices.service.adselection.AdDataArgumentUtil;
import com.android.adservices.service.adselection.AdSelectionConfigArgumentUtil;
import com.android.adservices.service.adselection.AdWithBidArgumentUtil;
import com.android.adservices.service.adselection.CustomAudienceBiddingSignalsArgumentUtil;
import com.android.adservices.service.adselection.CustomAudienceScoringSignalsArgumentUtil;
import com.android.adservices.service.common.NoOpRetryStrategyImpl;
import com.android.adservices.service.js.IsolateSettings;
import com.android.adservices.service.js.JSScriptArgument;
import com.android.adservices.service.js.JSScriptArrayArgument;
import com.android.adservices.service.js.JSScriptEngine;
import com.android.adservices.service.js.JSScriptRecordArgument;
import com.android.adservices.service.profiling.JSScriptEngineLogConstants;
import com.android.adservices.service.profiling.Profiler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** To run the unit tests for this class, run "atest RubidiumPerfTests:JSScriptEnginePerfTests" */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class JSScriptEnginePerfTests {
    private static final String TAG = JSScriptEngine.TAG;
    private static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final ExecutorService sExecutorService = Executors.newFixedThreadPool(10);

    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    private static final JSScriptEngine sJSScriptEngine =
            JSScriptEngine.getInstanceForTesting(
                    sContext, Profiler.createInstance(JSScriptEngine.TAG), sLogger);
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    private static final Instant ACTIVATION_TIME = CLOCK.instant();
    private static final Instant EXPIRATION_TIME = CLOCK.instant().plus(Duration.ofDays(1));
    private static final AdSelectionSignals CONTEXTUAL_SIGNALS = AdSelectionSignals.EMPTY;
    private static final AdCounterKeyCopier AD_COUNTER_KEY_COPIER_NO_OP =
            new AdCounterKeyCopierNoOpImpl();

    private final AdDataArgumentUtil mAdDataArgumentUtil =
            new AdDataArgumentUtil(AD_COUNTER_KEY_COPIER_NO_OP);
    private final AdWithBidArgumentUtil mAdWithBidArgumentUtil =
            new AdWithBidArgumentUtil(mAdDataArgumentUtil);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

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

    @Test
    public void evaluate_rubidiumGenerateBid_parametrized_1Ad() throws Exception {
        runParameterizedRubidiumGenerateBid(1);
    }

    @Test
    public void evaluate_rubidiumGenerateBid_parametrized_10Ads() throws Exception {
        runParameterizedRubidiumGenerateBid(10);
    }

    @Test
    public void evaluate_rubidiumGenerateBid_parametrized_25Ads() throws Exception {
        runParameterizedRubidiumGenerateBid(25);
    }

    @Test
    public void evaluate_rubidiumGenerateBid_parametrized_50Ads() throws Exception {
        runParameterizedRubidiumGenerateBid(50);
    }

    @Test
    public void evaluate_rubidiumGenerateBid_parametrized_75Ads() throws Exception {
        runParameterizedRubidiumGenerateBid(75);
    }

    @Test
    public void evaluate_rubidiumScoreAd_parametrized_1Ad() throws Exception {
        runParameterizedRubidiumScoreAd(1);
    }

    @Test
    public void evaluate_rubidiumScoreAd_parametrized_10Ads() throws Exception {
        runParameterizedRubidiumScoreAd(10);
    }

    @Test
    public void evaluate_rubidiumScoreAd_parametrized_25Ads() throws Exception {
        runParameterizedRubidiumScoreAd(25);
    }

    @Test
    public void evaluate_rubidiumScoreAd_parametrized_50Ads() throws Exception {
        runParameterizedRubidiumScoreAd(50);
    }

    @Test
    public void evaluate_rubidiumScoreAd_parametrized_75Ads() throws Exception {
        runParameterizedRubidiumScoreAd(75);
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
            Log.d(TAG, webviewExecTimeLog);
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
                                        "https://googleads.g.doubleclick.net/ads/simple-ad"
                                                + ".html?adg_id=52836427830&cr_id=310927197297"
                                                + "&cv_id=4"),
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
                IsolateSettings.forMaxHeapSizeEnforcementDisabled(),
                new NoOpRetryStrategyImpl());
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
                IsolateSettings.forMaxHeapSizeEnforcementDisabled(),
                new NoOpRetryStrategyImpl());
        result.addListener(resultLatch::countDown, sExecutorService);
        return result;
    }

    private byte[] readBinaryAsset(@NonNull String assetName) throws IOException {
        return sContext.getAssets().open(assetName).readAllBytes();
    }

    private String readAsset(@NonNull String assetName) throws IOException {
        return new String(readBinaryAsset(assetName), StandardCharsets.UTF_8);
    }

    public void runParameterizedRubidiumGenerateBid(int numOfAds) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.pauseTiming();
        List<AdData> adDataList = getSampleAdDataList(numOfAds, "https://ads.example/");
        ImmutableList.Builder<JSScriptArgument> adDataListArgument = new ImmutableList.Builder<>();
        for (AdData adData : adDataList) {
            adDataListArgument.add(mAdDataArgumentUtil.asScriptArgument("ignored", adData));
        }
        AdSelectionSignals perBuyerSignals = generatePerBuyerSignals(numOfAds);
        AdSelectionSignals auctionSignals = AdSelectionSignals.fromString("{\"auctionSignal1"
                + "\":\"auctionValue1\",\"auctionSignal2\":\"auctionValue2\"}");

        AdTechIdentifier buyer = AdTechIdentifier.fromString("https://example-dsp.com");
        AdSelectionSignals trustedBiddingSignals = AdSelectionSignals.fromString("{\"key1"
                + "\":\"tbs1\",\"key2\":{}}");
        CustomAudienceSignals customAudienceSignals = getSampleCustomAudienceSignals(buyer,
                "shoes-running");

        ImmutableList<JSScriptArgument> args = ImmutableList.<JSScriptArgument>builder()
                .add(arrayArg("ads", adDataListArgument.build()))
                .add(jsonArg("auctionSignals", auctionSignals))
                .add(jsonArg("perBuyerSignals", perBuyerSignals))
                .add(jsonArg("trustedBiddingSignals", trustedBiddingSignals))
                .add(jsonArg("contextualSignals", CONTEXTUAL_SIGNALS))
                .add(CustomAudienceBiddingSignalsArgumentUtil.asScriptArgument(
                        "customAudienceBiddingSignal", customAudienceSignals))
                .build();
        InputStream testJsInputStream = sContext.getAssets().open(
                "rubidium_bidding_logic_compiled.js");
        String jsTestFile = new String(testJsInputStream.readAllBytes(), StandardCharsets.UTF_8);
        //logging time taken to call JS
        state.resumeTiming();
        while (state.keepRunning()) {
            String res = callJSEngine(jsTestFile, args, "generateBidIterative");
            JSONObject jsonObject = new JSONObject(res);
            long webviewExecTime = jsonObject.getLong("duration");
            String webviewExecTimeLog =
                    String.format(Locale.ENGLISH,
                            "(%s: %d)",
                            JSScriptEngineLogConstants.WEBVIEW_EXECUTION_TIME,
                            webviewExecTime);
            // The listener picks up logs from JSScriptEngine, so simulate logging from there.
            Log.d(TAG, webviewExecTimeLog);
        }
    }

    public void runParameterizedRubidiumScoreAd(int numOfAds) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.pauseTiming();
        String adRenderUrl = "https://rtb.example/creative";
        List<AdWithBid> adWithBidList = getSampleAdDataWithBidList(numOfAds, adRenderUrl);
        ImmutableList.Builder<JSScriptArgument> adWithBidArrayArgument =
                new ImmutableList.Builder<>();
        for (AdWithBid adWithBid : adWithBidList) {
            adWithBidArrayArgument.add(
                    mAdWithBidArgumentUtil.asScriptArgument("adWithBid", adWithBid));
        }
        AdTechIdentifier seller = AdTechIdentifier.fromString("www.example-ssp.com");
        AdSelectionSignals sellerSignals = AdSelectionSignals.fromString("{\"signals\":[]}");
        String trustedScoringSignalJson = String.format(Locale.ENGLISH,
                "{\"renderUrl\":{\"%s\":[]}}", adRenderUrl);
        AdSelectionSignals trustedScoringSignalsJson = AdSelectionSignals.fromString(
                trustedScoringSignalJson);

        AdTechIdentifier buyer1 = AdTechIdentifier.fromString("https://example-dsp.com");
        AdSelectionSignals buyer1Signals = AdSelectionSignals.fromString("{\"https://example-dsp"
                + ".com:1\":\"value1\",\"https://example-dsp.com:2\":\"value2\"}");
        Map<AdTechIdentifier, AdSelectionSignals> perBuyerSignals = ImmutableMap.of(buyer1,
                buyer1Signals);

        AdSelectionConfig adSelectionConfig = getSampleAdSelectionConfig(seller, sellerSignals,
                perBuyerSignals);
        CustomAudienceSignals customAudienceSignals = getSampleCustomAudienceSignals(buyer1,
                "shoes-running");

        ImmutableList<JSScriptArgument> args = ImmutableList.<JSScriptArgument>builder()
                .add(arrayArg("adsWithBids", adWithBidArrayArgument.build()))
                .add(AdSelectionConfigArgumentUtil.asScriptArgument(adSelectionConfig,
                        "adSelectionConfig"))
                .add(jsonArg("sellerSignals", sellerSignals))
                .add(jsonArg("trustedScoringSignals", trustedScoringSignalsJson))
                .add(jsonArg("contextualSignals", CONTEXTUAL_SIGNALS))
                .add(CustomAudienceScoringSignalsArgumentUtil.asScriptArgument(
                        "customAudienceScoringSignal", customAudienceSignals))
                .build();
        InputStream testJsInputStream = sContext.getAssets().open(
                "rubidium_scoring_logic_compiled.js");
        String jsTestFile = new String(testJsInputStream.readAllBytes(), StandardCharsets.UTF_8);
        //logging time taken to call JS
        state.resumeTiming();
        while (state.keepRunning()) {
            String res = callJSEngine(jsTestFile, args, "scoreAdIterative");
            JSONObject jsonObject = new JSONObject(res);
            long webviewExecTime = jsonObject.getLong("duration");
            String webviewExecTimeLog =
                    String.format(Locale.ENGLISH,
                            "(%s: %d)",
                            JSScriptEngineLogConstants.WEBVIEW_EXECUTION_TIME,
                            webviewExecTime);
            // The listener picks up logs from JSScriptEngine, so simulate logging from there.
            Log.d(TAG, webviewExecTimeLog);
        }
    }

    private List<AdWithBid> getSampleAdDataWithBidList(int size, String baseUri) {
        double initialBid = 1.23;
        return IntStream.rangeClosed(1, size).mapToObj(iterator -> {
            Uri renderUri = Uri.parse(String.format(Locale.ENGLISH, "%s%d", baseUri, iterator));
            String metaDataJson = String.format(Locale.ENGLISH, "{\"metadata\":[\"%d\",\"123\"]}",
                    iterator);
            AdData adData = new AdData.Builder().setRenderUri(renderUri).setMetadata(
                    metaDataJson).build();
            return new AdWithBid(adData, initialBid + iterator);
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private List<AdData> getSampleAdDataList(int size, String baseUri) {
        return IntStream.rangeClosed(1, size).mapToObj(iterator -> {
            Uri renderUri = Uri.parse(String.format(Locale.ENGLISH, "%s%d", baseUri, iterator));
            String metaDataJson = String.format(Locale.ENGLISH, "{\"metadata\":[\"%d\",\"123\"]}",
                    iterator);
            return new AdData.Builder().setRenderUri(renderUri).setMetadata(
                    metaDataJson).build();
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private CustomAudienceSignals getSampleCustomAudienceSignals(AdTechIdentifier buyer,
            String name) {
        String owner = "www.example-dsp.com";
        AdSelectionSignals userBiddingSignals = AdSelectionSignals.fromString("{\"signals\":[]}");
        return new CustomAudienceSignals.Builder()
                .setOwner(owner)
                .setBuyer(buyer)
                .setActivationTime(ACTIVATION_TIME)
                .setExpirationTime(EXPIRATION_TIME)
                .setUserBiddingSignals(userBiddingSignals)
                .setName(name)
                .build();
    }

    private AdSelectionConfig getSampleAdSelectionConfig(AdTechIdentifier seller,
            AdSelectionSignals sellerSignals,
            Map<AdTechIdentifier, AdSelectionSignals> perBuyerSignals) {
        Uri decisionLogicUri = Uri.parse("https://www.example-ssp.com/decide.js");
        Uri trustedScoringSignalsUri = Uri.parse("https://www.example-ssp.com/signals");
        List<AdTechIdentifier> buyers = ImmutableList.copyOf(
                new ArrayList<>(perBuyerSignals.keySet()));
        return new AdSelectionConfig.Builder()
                .setSeller(seller)
                .setDecisionLogicUri(decisionLogicUri)
                .setCustomAudienceBuyers(buyers)
                .setAdSelectionSignals(AdSelectionSignals.EMPTY)
                .setSellerSignals(sellerSignals)
                .setPerBuyerSignals(perBuyerSignals)
                .setTrustedScoringSignalsUri(trustedScoringSignalsUri)
                .build();
    }

    private AdSelectionSignals generatePerBuyerSignals(int size) {
        String signalArrayFormat = "[\"%d\",\"123\",%d]";
        String signalArray = IntStream.rangeClosed(1, size)
                .mapToObj(i -> String.format(Locale.ENGLISH, signalArrayFormat, i, i))
                .collect(Collectors.joining(", ", "[", "]"));
        return AdSelectionSignals.fromString(
                String.format(Locale.ENGLISH, "{\"signals\":[null,%s,[null]]}",
                        signalArray));
    }
}
