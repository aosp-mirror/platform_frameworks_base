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

package com.android.internal.protolog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import static java.io.File.createTempFile;
import static java.nio.file.Files.createTempDirectory;

import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.tools.ScenarioBuilder;
import android.tools.Tag;
import android.tools.io.ResultArtifactDescriptor;
import android.tools.io.TraceType;
import android.tools.traces.TraceConfig;
import android.tools.traces.TraceConfigs;
import android.tools.traces.io.ResultReader;
import android.tools.traces.io.ResultWriter;
import android.tools.traces.monitors.PerfettoTraceMonitor;

import com.google.common.truth.Truth;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import perfetto.protos.Protolog.ProtoLogViewerConfig;
import perfetto.protos.ProtologCommon;
import perfetto.protos.TraceOuterClass.Trace;
import perfetto.protos.TracePacketOuterClass.TracePacket;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Test class for {@link ProtoLogImpl}.
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class ProtoLogConfigurationServiceTest {

    private static final String TEST_GROUP = "MY_TEST_GROUP";
    private static final String OTHER_TEST_GROUP = "MY_OTHER_TEST_GROUP";

    private static final ProtoLogViewerConfig VIEWER_CONFIG =
            ProtoLogViewerConfig.newBuilder()
                    .addGroups(
                            ProtoLogViewerConfig.Group.newBuilder()
                                    .setId(1)
                                    .setName(TEST_GROUP)
                                    .setTag(TEST_GROUP)
                    ).addMessages(
                            ProtoLogViewerConfig.MessageData.newBuilder()
                                    .setMessageId(1)
                                    .setMessage("My Test Debug Log Message %b")
                                    .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_DEBUG)
                                    .setGroupId(1)
                    ).addMessages(
                            ProtoLogViewerConfig.MessageData.newBuilder()
                                    .setMessageId(2)
                                    .setMessage("My Test Verbose Log Message %b")
                                    .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_VERBOSE)
                                    .setGroupId(1)
                    ).build();

    @Mock
    IProtoLogClient mMockClient;

    @Mock
    IProtoLogClient mSecondMockClient;

    @Mock
    IBinder mMockClientBinder;

    @Mock
    IBinder mSecondMockClientBinder;

    private final File mTracingDirectory = createTempDirectory("temp").toFile();

    private final ResultWriter mWriter = new ResultWriter()
            .forScenario(new ScenarioBuilder()
                    .forClass(createTempFile("temp", "").getName()).build())
            .withOutputDir(mTracingDirectory)
            .setRunComplete();

    private final TraceConfigs mTraceConfig = new TraceConfigs(
            new TraceConfig(false, true, false),
            new TraceConfig(false, true, false),
            new TraceConfig(false, true, false),
            new TraceConfig(false, true, false)
    );

    @Captor
    ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientArgumentCaptor;

    @Captor
    ArgumentCaptor<IBinder.DeathRecipient> mSecondDeathRecipientArgumentCaptor;

    private File mViewerConfigFile;

    public ProtoLogConfigurationServiceTest() throws IOException {
    }

    @Before
    public void setUp() {
        Mockito.when(mMockClient.asBinder()).thenReturn(mMockClientBinder);
        Mockito.when(mSecondMockClient.asBinder()).thenReturn(mSecondMockClientBinder);

        try {
            mViewerConfigFile = File.createTempFile("viewer-config", ".pb");
            try (var fos = new FileOutputStream(mViewerConfigFile);
                    BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                bos.write(VIEWER_CONFIG.toByteArray());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void canRegisterClientWithGroupsOnly() throws RemoteException {
        final ProtoLogConfigurationService service = new ProtoLogConfigurationServiceImpl();

        final ProtoLogConfigurationServiceImpl.RegisterClientArgs args =
                new ProtoLogConfigurationServiceImpl.RegisterClientArgs()
                        .setGroups(new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                                .GroupConfig(TEST_GROUP, true));
        service.registerClient(mMockClient, args);

        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isTrue();
        Truth.assertThat(service.getGroups()).asList().containsExactly(TEST_GROUP);
    }

    @Test
    public void willDumpViewerConfigOnlyOnceOnTraceStop()
            throws RemoteException, InvalidProtocolBufferException {
        final ProtoLogConfigurationService service = new ProtoLogConfigurationServiceImpl();

        final ProtoLogConfigurationServiceImpl.RegisterClientArgs args =
                new ProtoLogConfigurationServiceImpl.RegisterClientArgs()
                        .setGroups(new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                                .GroupConfig(TEST_GROUP, true))
                        .setViewerConfigFile(mViewerConfigFile.getAbsolutePath());
        service.registerClient(mMockClient, args);
        service.registerClient(mSecondMockClient, args);

        PerfettoTraceMonitor traceMonitor =
                PerfettoTraceMonitor.newBuilder().enableProtoLog().build();

        traceMonitor.start();
        traceMonitor.stop(mWriter);
        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final byte[] traceData = reader.getArtifact()
                .readBytes(new ResultArtifactDescriptor(TraceType.PERFETTO, Tag.ALL));

        final Trace trace = Trace.parseFrom(traceData);

        final List<TracePacket> configPackets = trace.getPacketList().stream()
                .filter(it -> it.hasProtologViewerConfig())
                // Exclude viewer configs from regular system tracing
                .filter(it ->
                        it.getProtologViewerConfig().getGroups(0).getName().equals(TEST_GROUP))
                .toList();
        Truth.assertThat(configPackets).hasSize(1);
        Truth.assertThat(configPackets.get(0).getProtologViewerConfig().toString())
                .isEqualTo(VIEWER_CONFIG.toString());
    }

    @Test
    public void willDumpViewerConfigOnLastClientDisconnected()
            throws RemoteException, FileNotFoundException {
        final ProtoLogConfigurationServiceImpl.ViewerConfigFileTracer tracer =
                Mockito.mock(ProtoLogConfigurationServiceImpl.ViewerConfigFileTracer.class);
        final ProtoLogConfigurationService service = new ProtoLogConfigurationServiceImpl(tracer);

        final ProtoLogConfigurationServiceImpl.RegisterClientArgs args =
                new ProtoLogConfigurationServiceImpl.RegisterClientArgs()
                        .setGroups(new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                                .GroupConfig(TEST_GROUP, true))
                        .setViewerConfigFile(mViewerConfigFile.getAbsolutePath());
        service.registerClient(mMockClient, args);
        service.registerClient(mSecondMockClient, args);

        Mockito.verify(mMockClientBinder)
                .linkToDeath(mDeathRecipientArgumentCaptor.capture(), anyInt());
        Mockito.verify(mSecondMockClientBinder)
                .linkToDeath(mSecondDeathRecipientArgumentCaptor.capture(), anyInt());

        mDeathRecipientArgumentCaptor.getValue().binderDied();
        Mockito.verify(tracer, never()).trace(any(), any());
        mSecondDeathRecipientArgumentCaptor.getValue().binderDied();
        Mockito.verify(tracer).trace(any(), eq(mViewerConfigFile.getAbsolutePath()));
    }

    @Test
    public void sendEnableLoggingToLogcatToClient() throws RemoteException {
        final var service = new ProtoLogConfigurationServiceImpl();

        final var args = new ProtoLogConfigurationServiceImpl.RegisterClientArgs()
                .setGroups(new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                        .GroupConfig(TEST_GROUP, false));
        service.registerClient(mMockClient, args);

        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isFalse();
        service.enableProtoLogToLogcat(TEST_GROUP);
        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isTrue();

        Mockito.verify(mMockClient).toggleLogcat(eq(true),
                Mockito.argThat(it -> it.length == 1 && it[0].equals(TEST_GROUP)));
    }

    @Test
    public void sendDisableLoggingToLogcatToClient() throws RemoteException {
        final ProtoLogConfigurationService service = new ProtoLogConfigurationServiceImpl();

        final ProtoLogConfigurationServiceImpl.RegisterClientArgs args =
                new ProtoLogConfigurationServiceImpl.RegisterClientArgs()
                        .setGroups(new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                                .GroupConfig(TEST_GROUP, true));
        service.registerClient(mMockClient, args);

        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isTrue();
        service.disableProtoLogToLogcat(TEST_GROUP);
        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isFalse();

        Mockito.verify(mMockClient).toggleLogcat(eq(false),
                Mockito.argThat(it -> it.length == 1 && it[0].equals(TEST_GROUP)));
    }

    @Test
    public void doNotSendLoggingToLogcatToClientWithoutRegisteredGroup() throws RemoteException {
        final ProtoLogConfigurationService service = new ProtoLogConfigurationServiceImpl();

        final ProtoLogConfigurationServiceImpl.RegisterClientArgs args =
                new ProtoLogConfigurationServiceImpl.RegisterClientArgs()
                        .setGroups(new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                                .GroupConfig(TEST_GROUP, false));
        service.registerClient(mMockClient, args);

        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isFalse();
        service.enableProtoLogToLogcat(OTHER_TEST_GROUP);
        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isFalse();

        Mockito.verify(mMockClient, never()).toggleLogcat(anyBoolean(), any());
    }

    @Test
    public void handlesToggleToLogcatBeforeClientIsRegistered() throws RemoteException {
        final ProtoLogConfigurationService service = new ProtoLogConfigurationServiceImpl();

        Truth.assertThat(service.getGroups()).asList().doesNotContain(TEST_GROUP);
        service.enableProtoLogToLogcat(TEST_GROUP);
        Truth.assertThat(service.isLoggingToLogcat(TEST_GROUP)).isTrue();

        final ProtoLogConfigurationServiceImpl.RegisterClientArgs args =
                new ProtoLogConfigurationServiceImpl.RegisterClientArgs()
                        .setGroups(new ProtoLogConfigurationServiceImpl.RegisterClientArgs
                                .GroupConfig(TEST_GROUP, false));
        service.registerClient(mMockClient, args);

        Mockito.verify(mMockClient).toggleLogcat(eq(true),
                Mockito.argThat(it -> it.length == 1 && it[0].equals(TEST_GROUP)));
    }
}
