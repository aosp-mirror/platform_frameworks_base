/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import static android.os.PerfettoTrace.Category;

import static com.google.common.truth.Truth.assertThat;

import static perfetto.protos.ChromeLatencyInfoOuterClass.ChromeLatencyInfo.LatencyComponentType.COMPONENT_INPUT_EVENT_LATENCY_BEGIN_RWH;
import static perfetto.protos.ChromeLatencyInfoOuterClass.ChromeLatencyInfo.LatencyComponentType.COMPONENT_INPUT_EVENT_LATENCY_SCROLL_UPDATE_ORIGINAL;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.ArraySet;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import perfetto.protos.ChromeLatencyInfoOuterClass.ChromeLatencyInfo;
import perfetto.protos.ChromeLatencyInfoOuterClass.ChromeLatencyInfo.ComponentInfo;
import perfetto.protos.DataSourceConfigOuterClass.DataSourceConfig;
import perfetto.protos.DebugAnnotationOuterClass.DebugAnnotation;
import perfetto.protos.DebugAnnotationOuterClass.DebugAnnotationName;
import perfetto.protos.InternedDataOuterClass.InternedData;
import perfetto.protos.SourceLocationOuterClass.SourceLocation;
import perfetto.protos.TraceConfigOuterClass.TraceConfig;
import perfetto.protos.TraceConfigOuterClass.TraceConfig.BufferConfig;
import perfetto.protos.TraceConfigOuterClass.TraceConfig.DataSource;
import perfetto.protos.TraceConfigOuterClass.TraceConfig.TriggerConfig;
import perfetto.protos.TraceConfigOuterClass.TraceConfig.TriggerConfig.Trigger;
import perfetto.protos.TraceOuterClass.Trace;
import perfetto.protos.TracePacketOuterClass.TracePacket;
import perfetto.protos.TrackDescriptorOuterClass.TrackDescriptor;
import perfetto.protos.TrackEventConfigOuterClass.TrackEventConfig;
import perfetto.protos.TrackEventOuterClass.EventCategory;
import perfetto.protos.TrackEventOuterClass.EventName;
import perfetto.protos.TrackEventOuterClass.TrackEvent;

import java.util.List;
import java.util.Set;

/**
 * This class is used to test the native tracing support. Run this test
 * while tracing on the emulator and then run traceview to view the trace.
 */
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = PerfettoTrace.class)
public class PerfettoTraceTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule(
                    InstrumentationRegistry.getInstrumentation().getUiAutomation());

    private static final String TAG = "PerfettoTraceTest";
    private static final String FOO = "foo";
    private static final String BAR = "bar";

    private static final Category FOO_CATEGORY = new Category(FOO);

    private final Set<String> mCategoryNames = new ArraySet<>();
    private final Set<String> mEventNames = new ArraySet<>();
    private final Set<String> mDebugAnnotationNames = new ArraySet<>();
    private final Set<String> mTrackNames = new ArraySet<>();

    static {
        try {
            System.loadLibrary("perfetto_trace_test_jni");
            Log.i(TAG, "Successfully loaded trace_test native library");
        } catch (UnsatisfiedLinkError ule) {
            Log.w(TAG, "Could not load trace_test native library");
        }
    }

    @Before
    public void setUp() {
        PerfettoTrace.register();
        nativeRegisterPerfetto();
        FOO_CATEGORY.register();

        mCategoryNames.clear();
        mEventNames.clear();
        mDebugAnnotationNames.clear();
        mTrackNames.clear();
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testDebugAnnotations() throws Exception {
        TraceConfig traceConfig = getTraceConfig(FOO);

        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrackEventExtra extra = PerfettoTrackEventExtra.builder()
                .addFlow(2)
                .addTerminatingFlow(3)
                .addArg("long_val", 10000000000L)
                .addArg("bool_val", true)
                .addArg("double_val", 3.14)
                .addArg("string_val", FOO)
                .build();
        PerfettoTrace.instant(FOO_CATEGORY, "event", extra);

        byte[] traceBytes = nativeStopTracing(ptr);

        Trace trace = Trace.parseFrom(traceBytes);

        boolean hasTrackEvent = false;
        boolean hasDebugAnnotations = false;
        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
                event = packet.getTrackEvent();

                if (TrackEvent.Type.TYPE_INSTANT.equals(event.getType())
                        && event.getDebugAnnotationsCount() == 4 && event.getFlowIdsCount() == 1
                        && event.getTerminatingFlowIdsCount() == 1) {
                    hasDebugAnnotations = true;

                    List<DebugAnnotation> annotations = event.getDebugAnnotationsList();

                    assertThat(annotations.get(0).getIntValue()).isEqualTo(10000000000L);
                    assertThat(annotations.get(1).getBoolValue()).isTrue();
                    assertThat(annotations.get(2).getDoubleValue()).isEqualTo(3.14);
                    assertThat(annotations.get(3).getStringValue()).isEqualTo(FOO);
                }
            }

            collectInternedData(packet);
        }

        assertThat(hasTrackEvent).isTrue();
        assertThat(hasDebugAnnotations).isTrue();
        assertThat(mCategoryNames).contains(FOO);

        assertThat(mDebugAnnotationNames).contains("long_val");
        assertThat(mDebugAnnotationNames).contains("bool_val");
        assertThat(mDebugAnnotationNames).contains("double_val");
        assertThat(mDebugAnnotationNames).contains("string_val");
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testDebugAnnotationsWithLamda() throws Exception {
        TraceConfig traceConfig = getTraceConfig(FOO);

        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrace.instant(FOO_CATEGORY, "event", e -> e.addArg("long_val", 123L));

        byte[] traceBytes = nativeStopTracing(ptr);

        Trace trace = Trace.parseFrom(traceBytes);

        boolean hasTrackEvent = false;
        boolean hasDebugAnnotations = false;
        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
                event = packet.getTrackEvent();

                if (TrackEvent.Type.TYPE_INSTANT.equals(event.getType())
                        && event.getDebugAnnotationsCount() == 1) {
                    hasDebugAnnotations = true;

                    List<DebugAnnotation> annotations = event.getDebugAnnotationsList();
                    assertThat(annotations.get(0).getIntValue()).isEqualTo(123L);
                }
            }
        }

        assertThat(hasTrackEvent).isTrue();
        assertThat(hasDebugAnnotations).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testNamedTrack() throws Exception {
        TraceConfig traceConfig = getTraceConfig(FOO);

        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrackEventExtra beginExtra = PerfettoTrackEventExtra.builder()
                .usingNamedTrack(FOO, PerfettoTrace.getProcessTrackUuid())
                .build();
        PerfettoTrace.begin(FOO_CATEGORY, "event", beginExtra);

        PerfettoTrackEventExtra endExtra = PerfettoTrackEventExtra.builder()
                .usingNamedTrack("bar", PerfettoTrace.getThreadTrackUuid(Process.myTid()))
                .build();
        PerfettoTrace.end(FOO_CATEGORY, endExtra);

        Trace trace = Trace.parseFrom(nativeStopTracing(ptr));

        boolean hasTrackEvent = false;
        boolean hasTrackUuid = false;
        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
                event = packet.getTrackEvent();

                if (TrackEvent.Type.TYPE_SLICE_BEGIN.equals(event.getType())
                        && event.hasTrackUuid()) {
                    hasTrackUuid = true;
                }

                if (TrackEvent.Type.TYPE_SLICE_END.equals(event.getType())
                        && event.hasTrackUuid()) {
                    hasTrackUuid &= true;
                }
            }

            collectInternedData(packet);
            collectTrackNames(packet);
        }

        assertThat(hasTrackEvent).isTrue();
        assertThat(hasTrackUuid).isTrue();
        assertThat(mCategoryNames).contains(FOO);
        assertThat(mTrackNames).contains(FOO);
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testCounter() throws Exception {
        TraceConfig traceConfig = getTraceConfig(FOO);

        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrackEventExtra intExtra = PerfettoTrackEventExtra.builder()
                .usingCounterTrack(FOO, PerfettoTrace.getProcessTrackUuid())
                .setCounter(16)
                .build();
        PerfettoTrace.counter(FOO_CATEGORY, intExtra);

        PerfettoTrackEventExtra doubleExtra = PerfettoTrackEventExtra.builder()
                .usingCounterTrack("bar", PerfettoTrace.getProcessTrackUuid())
                .setCounter(3.14)
                .build();
        PerfettoTrace.counter(FOO_CATEGORY, doubleExtra);

        Trace trace = Trace.parseFrom(nativeStopTracing(ptr));

        boolean hasTrackEvent = false;
        boolean hasCounterValue = false;
        boolean hasDoubleCounterValue = false;
        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
                event = packet.getTrackEvent();

                if (TrackEvent.Type.TYPE_COUNTER.equals(event.getType())
                        && event.getCounterValue() == 16) {
                    hasCounterValue = true;
                }

                if (TrackEvent.Type.TYPE_COUNTER.equals(event.getType())
                        && event.getDoubleCounterValue() == 3.14) {
                    hasDoubleCounterValue = true;
                }
            }

            collectTrackNames(packet);
        }

        assertThat(hasTrackEvent).isTrue();
        assertThat(hasCounterValue).isTrue();
        assertThat(hasDoubleCounterValue).isTrue();
        assertThat(mTrackNames).contains(FOO);
        assertThat(mTrackNames).contains(BAR);
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testProto() throws Exception {
        TraceConfig traceConfig = getTraceConfig(FOO);

        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrackEventExtra extra5 = PerfettoTrackEventExtra.builder()
                .beginProto()
                .beginNested(33L)
                .addField(4L, 2L)
                .addField(3, "ActivityManagerService.java:11489")
                .endNested()
                .addField(2001, "AIDL::IActivityManager")
                .endProto()
                .build();
        PerfettoTrace.instant(FOO_CATEGORY, "event_proto", extra5);

        byte[] traceBytes = nativeStopTracing(ptr);

        Trace trace = Trace.parseFrom(traceBytes);

        boolean hasTrackEvent = false;
        boolean hasSourceLocation = false;

        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
                event = packet.getTrackEvent();

                if (TrackEvent.Type.TYPE_INSTANT.equals(event.getType())
                        && event.hasSourceLocation()) {
                    SourceLocation loc = event.getSourceLocation();
                    if ("ActivityManagerService.java:11489".equals(loc.getFunctionName())
                            && loc.getLineNumber() == 2) {
                        hasSourceLocation = true;
                    }
                }
            }

            collectInternedData(packet);
        }

        assertThat(hasTrackEvent).isTrue();
        assertThat(hasSourceLocation).isTrue();
        assertThat(mCategoryNames).contains(FOO);
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testProtoNested() throws Exception {
        TraceConfig traceConfig = getTraceConfig(FOO);

        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrackEventExtra extra6 = PerfettoTrackEventExtra.builder()
                .beginProto()
                .beginNested(29L)
                .beginNested(4L)
                .addField(1L, 2)
                .addField(2L, 20000)
                .endNested()
                .beginNested(4L)
                .addField(1L, 1)
                .addField(2L, 40000)
                .endNested()
                .endNested()
                .endProto()
                .build();
        PerfettoTrace.instant(FOO_CATEGORY, "event_proto_nested", extra6);

        byte[] traceBytes = nativeStopTracing(ptr);

        Trace trace = Trace.parseFrom(traceBytes);

        boolean hasTrackEvent = false;
        boolean hasChromeLatencyInfo = false;

        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
                event = packet.getTrackEvent();

                if (TrackEvent.Type.TYPE_INSTANT.equals(event.getType())
                        && event.hasChromeLatencyInfo()) {
                    ChromeLatencyInfo latencyInfo = event.getChromeLatencyInfo();
                    if (latencyInfo.getComponentInfoCount() == 2) {
                        hasChromeLatencyInfo = true;
                        ComponentInfo cmpInfo1 = latencyInfo.getComponentInfo(0);
                        assertThat(cmpInfo1.getComponentType())
                                .isEqualTo(COMPONENT_INPUT_EVENT_LATENCY_SCROLL_UPDATE_ORIGINAL);
                        assertThat(cmpInfo1.getTimeUs()).isEqualTo(20000);

                        ComponentInfo cmpInfo2 = latencyInfo.getComponentInfo(1);
                        assertThat(cmpInfo2.getComponentType())
                                .isEqualTo(COMPONENT_INPUT_EVENT_LATENCY_BEGIN_RWH);
                        assertThat(cmpInfo2.getTimeUs()).isEqualTo(40000);
                    }
                }
            }

            collectInternedData(packet);
        }

        assertThat(hasTrackEvent).isTrue();
        assertThat(hasChromeLatencyInfo).isTrue();
        assertThat(mCategoryNames).contains(FOO);
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testActivateTrigger() throws Exception {
        TraceConfig traceConfig = getTriggerTraceConfig(FOO, FOO);

        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrackEventExtra extra = PerfettoTrackEventExtra.builder().build();
        PerfettoTrace.instant(FOO_CATEGORY, "event_trigger", extra);

        PerfettoTrace.activateTrigger(FOO, 1000);

        byte[] traceBytes = nativeStopTracing(ptr);

        Trace trace = Trace.parseFrom(traceBytes);

        boolean hasTrackEvent = false;
        boolean hasChromeLatencyInfo = false;

        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
            }

            collectInternedData(packet);
        }

        assertThat(mCategoryNames).contains(FOO);
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testMultipleExtras() throws Exception {
        boolean hasException = false;
        try {
            PerfettoTrackEventExtra.builder();

            // Unclosed extra will throw an exception here
            PerfettoTrackEventExtra.builder();
        } catch (Exception e) {
            hasException = true;
        }

        try {
            PerfettoTrackEventExtra.builder().build();

            // Closed extra but unused (reset hasn't been called internally) will throw an exception
            // here.
            PerfettoTrackEventExtra.builder();
        } catch (Exception e) {
            hasException &= true;
        }

        assertThat(hasException).isTrue();
    }

    @Test
    @RequiresFlagsEnabled(android.os.Flags.FLAG_PERFETTO_SDK_TRACING_V2)
    public void testRegister() throws Exception {
        TraceConfig traceConfig = getTraceConfig(BAR);

        Category barCategory = new Category(BAR);
        long ptr = nativeStartTracing(traceConfig.toByteArray());

        PerfettoTrackEventExtra beforeExtra = PerfettoTrackEventExtra.builder()
                .addArg("before", 1)
                .build();
        PerfettoTrace.instant(barCategory, "event", beforeExtra);

        barCategory.register();

        PerfettoTrackEventExtra afterExtra = PerfettoTrackEventExtra.builder()
                .addArg("after", 1)
                .build();
        PerfettoTrace.instant(barCategory, "event", afterExtra);

        byte[] traceBytes = nativeStopTracing(ptr);

        Trace trace = Trace.parseFrom(traceBytes);

        boolean hasTrackEvent = false;
        for (TracePacket packet: trace.getPacketList()) {
            TrackEvent event;
            if (packet.hasTrackEvent()) {
                hasTrackEvent = true;
                event = packet.getTrackEvent();
            }

            collectInternedData(packet);
        }

        assertThat(hasTrackEvent).isTrue();
        assertThat(mCategoryNames).contains(BAR);

        assertThat(mDebugAnnotationNames).contains("after");
        assertThat(mDebugAnnotationNames).doesNotContain("before");
    }

    private static native long nativeStartTracing(byte[] config);
    private static native void nativeRegisterPerfetto();
    private static native byte[] nativeStopTracing(long ptr);

    private TrackEvent getTrackEvent(Trace trace, int idx) {
        int curIdx = 0;
        for (TracePacket packet: trace.getPacketList()) {
            if (packet.hasTrackEvent()) {
                if (curIdx++ == idx) {
                    return packet.getTrackEvent();
                }
            }
        }

        return null;
    }

    private TraceConfig getTraceConfig(String cat) {
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

    private TraceConfig getTriggerTraceConfig(String cat, String triggerName) {
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
        Trigger trigger = Trigger.newBuilder().setName(triggerName).build();
        TriggerConfig triggerConfig = TriggerConfig
                .newBuilder()
                .setTriggerMode(TriggerConfig.TriggerMode.STOP_TRACING)
                .setTriggerTimeoutMs(1000)
                .addTriggers(trigger)
                .build();
        TraceConfig traceConfig = TraceConfig
                .newBuilder()
                .addBuffers(bufferConfig)
                .addDataSources(ds)
                .setTriggerConfig(triggerConfig)
                .build();
        return traceConfig;
    }

    private void collectInternedData(TracePacket packet) {
        if (!packet.hasInternedData()) {
            return;
        }

        InternedData data = packet.getInternedData();

        for (EventCategory cat : data.getEventCategoriesList()) {
            mCategoryNames.add(cat.getName());
        }
        for (EventName ev : data.getEventNamesList()) {
            mEventNames.add(ev.getName());
        }
        for (DebugAnnotationName dbg : data.getDebugAnnotationNamesList()) {
            mDebugAnnotationNames.add(dbg.getName());
        }
    }

    private void collectTrackNames(TracePacket packet) {
        if (!packet.hasTrackDescriptor()) {
            return;
        }
        TrackDescriptor desc = packet.getTrackDescriptor();
        mTrackNames.add(desc.getName());
    }
}
