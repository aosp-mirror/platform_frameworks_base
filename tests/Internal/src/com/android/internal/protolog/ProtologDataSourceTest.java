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

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.tracing.perfetto.CreateTlsStateArgs;
import android.util.proto.ProtoInputStream;

import com.android.internal.protolog.common.LogLevel;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import perfetto.protos.DataSourceConfigOuterClass;
import perfetto.protos.ProtologCommon;
import perfetto.protos.ProtologConfig;

public class ProtologDataSourceTest {
    @Before
    public void before() {
        assumeTrue(android.tracing.Flags.perfettoProtologTracing());
    }

    @Test
    public void noConfig() {
        final ProtoLogDataSource.TlsState tlsState = createTlsState(
                DataSourceConfigOuterClass.DataSourceConfig.newBuilder().build());

        Truth.assertThat(tlsState.getLogFromLevel("SOME_TAG")).isEqualTo(LogLevel.WTF);
        Truth.assertThat(tlsState.getShouldCollectStacktrace("SOME_TAG")).isFalse();
    }

    @Test
    public void defaultTraceMode() {
        final ProtoLogDataSource.TlsState tlsState = createTlsState(
                DataSourceConfigOuterClass.DataSourceConfig.newBuilder()
                        .setProtologConfig(
                                ProtologConfig.ProtoLogConfig.newBuilder()
                                        .setTracingMode(
                                                ProtologConfig.ProtoLogConfig.TracingMode
                                                        .ENABLE_ALL)
                                        .build()
                        ).build());

        Truth.assertThat(tlsState.getLogFromLevel("SOME_TAG")).isEqualTo(LogLevel.DEBUG);
        Truth.assertThat(tlsState.getShouldCollectStacktrace("SOME_TAG")).isFalse();
    }

    @Test
    public void allEnabledTraceMode() {
        final ProtoLogDataSource ds = new ProtoLogDataSource((c) -> {}, () -> {}, (c) -> {});

        final ProtoLogDataSource.TlsState tlsState = createTlsState(
                DataSourceConfigOuterClass.DataSourceConfig.newBuilder().setProtologConfig(
                        ProtologConfig.ProtoLogConfig.newBuilder()
                                .setTracingMode(
                                        ProtologConfig.ProtoLogConfig.TracingMode.ENABLE_ALL)
                                .build()
                ).build()
        );

        Truth.assertThat(tlsState.getLogFromLevel("SOME_TAG")).isEqualTo(LogLevel.DEBUG);
        Truth.assertThat(tlsState.getShouldCollectStacktrace("SOME_TAG")).isFalse();
    }

    @Test
    public void requireGroupTagInOverrides() {
        Exception exception = assertThrows(RuntimeException.class, () -> {
            createTlsState(DataSourceConfigOuterClass.DataSourceConfig.newBuilder()
                    .setProtologConfig(
                            ProtologConfig.ProtoLogConfig.newBuilder()
                                    .addGroupOverrides(
                                            ProtologConfig.ProtoLogGroup.newBuilder()
                                                    .setLogFrom(
                                                            ProtologCommon.ProtoLogLevel
                                                                    .PROTOLOG_LEVEL_WARN)
                                                    .setCollectStacktrace(true)
                                    )
                                    .build()
                    ).build());
        });

        Truth.assertThat(exception).hasMessageThat().contains("group override without a group tag");
    }

    @Test
    public void stackTraceCollection() {
        final ProtoLogDataSource.TlsState tlsState = createTlsState(
                DataSourceConfigOuterClass.DataSourceConfig.newBuilder().setProtologConfig(
                        ProtologConfig.ProtoLogConfig.newBuilder()
                                .addGroupOverrides(
                                        ProtologConfig.ProtoLogGroup.newBuilder()
                                                .setGroupName("SOME_TAG")
                                                .setCollectStacktrace(true)
                                )
                                .build()
                ).build());

        Truth.assertThat(tlsState.getShouldCollectStacktrace("SOME_TAG")).isTrue();
    }

    @Test
    public void groupLogFromOverrides() {
        final ProtoLogDataSource.TlsState tlsState = createTlsState(
                DataSourceConfigOuterClass.DataSourceConfig.newBuilder().setProtologConfig(
                        ProtologConfig.ProtoLogConfig.newBuilder()
                                .addGroupOverrides(
                                        ProtologConfig.ProtoLogGroup.newBuilder()
                                                .setGroupName("SOME_TAG")
                                                .setLogFrom(
                                                        ProtologCommon.ProtoLogLevel
                                                                .PROTOLOG_LEVEL_DEBUG)
                                                .setCollectStacktrace(true)
                                )
                                .addGroupOverrides(
                                        ProtologConfig.ProtoLogGroup.newBuilder()
                                                .setGroupName("SOME_OTHER_TAG")
                                                .setLogFrom(
                                                        ProtologCommon.ProtoLogLevel
                                                                .PROTOLOG_LEVEL_WARN)
                                )
                                .build()
                ).build());

        Truth.assertThat(tlsState.getLogFromLevel("SOME_TAG")).isEqualTo(LogLevel.DEBUG);
        Truth.assertThat(tlsState.getShouldCollectStacktrace("SOME_TAG")).isTrue();

        Truth.assertThat(tlsState.getLogFromLevel("SOME_OTHER_TAG")).isEqualTo(LogLevel.WARN);
        Truth.assertThat(tlsState.getShouldCollectStacktrace("SOME_OTHER_TAG")).isFalse();

        Truth.assertThat(tlsState.getLogFromLevel("UNKNOWN_TAG")).isEqualTo(LogLevel.WTF);
        Truth.assertThat(tlsState.getShouldCollectStacktrace("UNKNOWN_TAG")).isFalse();
    }

    private ProtoLogDataSource.TlsState createTlsState(
            DataSourceConfigOuterClass.DataSourceConfig config) {
        final ProtoLogDataSource ds =
                Mockito.spy(new ProtoLogDataSource((c) -> {}, () -> {}, (c) -> {}));

        ProtoInputStream configStream = new ProtoInputStream(config.toByteArray());
        final ProtoLogDataSource.Instance dsInstance = Mockito.spy(
                ds.createInstance(configStream, 8));
        Mockito.doNothing().when(dsInstance).release();
        final CreateTlsStateArgs mockCreateTlsStateArgs = Mockito.mock(CreateTlsStateArgs.class);
        Mockito.when(mockCreateTlsStateArgs.getDataSourceInstanceLocked()).thenReturn(dsInstance);
        return ds.createTlsState(mockCreateTlsStateArgs);
    }
}
