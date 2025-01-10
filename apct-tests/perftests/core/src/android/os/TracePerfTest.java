/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */


package android.os;

import static android.os.PerfettoTrace.Category;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.ShellHelper;

import androidx.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import perfetto.protos.DataSourceConfigOuterClass.DataSourceConfig;
import perfetto.protos.TraceConfigOuterClass.TraceConfig;
import perfetto.protos.TraceConfigOuterClass.TraceConfig.BufferConfig;
import perfetto.protos.TraceConfigOuterClass.TraceConfig.DataSource;
import perfetto.protos.TrackEventConfigOuterClass.TrackEventConfig;

@RunWith(AndroidJUnit4.class)
public class TracePerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final String FOO = "foo";
    private static final Category FOO_CATEGORY = new Category(FOO);
    private static PerfettoTrace.Session sPerfettoSession;

    @BeforeClass
    public static void startTracing() {
        ShellHelper.runShellCommandRaw("atrace -c --async_start -a *");
        PerfettoTrace.register(false /* isBackendInProcess */);
        FOO_CATEGORY.register();
        sPerfettoSession = new PerfettoTrace.Session(false /* isBackendInProcess */,
                                                      getTraceConfig(FOO).toByteArray());
    }

    @AfterClass
    public static void endTracing() {
        ShellHelper.runShellCommandRaw("atrace --async_stop");
        FOO_CATEGORY.unregister();
        sPerfettoSession.close();
    }

    @Before
    public void verifyTracingEnabled() {
        Assert.assertTrue(Trace.isEnabled());
    }

    @Test
    public void testEnabled() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Trace.isEnabled();
        }
    }

    @Test
    public void testBeginEndSection() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Trace.beginSection("testBeginEndSection");
            Trace.endSection();
        }
    }

    @Test
    public void testAsyncBeginEnd() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Trace.beginAsyncSection("testAsyncBeginEnd", 42);
            Trace.endAsyncSection("testAsyncBeginEnd", 42);
        }
    }

    @Test
    public void testCounter() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Trace.setCounter("testCounter", 123);
        }
    }

    @Test
    public void testInstant() {
        Trace.instant(Trace.TRACE_TAG_APP, "testInstantA");

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Trace.instant(Trace.TRACE_TAG_APP, "testInstantA");
        }
    }

    @Test
    public void testInstantPerfetto() {
        PerfettoTrace.instant(FOO_CATEGORY, "testInstantP").emit();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            PerfettoTrace.instant(FOO_CATEGORY, "testInstantP").emit();
        }
    }

    @Test
    public void testInstantPerfettoWithArgs() {
        PerfettoTrace.instant(FOO_CATEGORY, "testInstantP")
                .addArg("foo", "bar")
                .addFlow(1)
                .emit();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            PerfettoTrace.instant(FOO_CATEGORY, "testInstantP")
                    .addArg("foo", "bar")
                    .addFlow(1)
                    .emit();
        }
    }

    private static TraceConfig getTraceConfig(String cat) {
        BufferConfig bufferConfig = BufferConfig.newBuilder().setSizeKb(1024).build();
        TrackEventConfig trackEventConfig = TrackEventConfig
                .newBuilder()
                .addEnabledCategories(cat)
                .build();
        DataSourceConfig dsConfig = DataSourceConfig
                .newBuilder()
                .setName("track_event")
                .setTargetBuffer(0)
                .setTrackEventConfig(trackEventConfig)
                .build();
        DataSource ds = DataSource.newBuilder().setConfig(dsConfig).build();
        TraceConfig traceConfig = TraceConfig
                .newBuilder()
                .addBuffers(bufferConfig)
                .addDataSources(ds)
                .build();
        return traceConfig;
    }
}
