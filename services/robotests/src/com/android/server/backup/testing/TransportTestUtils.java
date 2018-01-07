/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.ComponentName;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.TransportManager;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportNotAvailableException;
import com.android.server.backup.transport.TransportNotRegisteredException;

import java.util.Arrays;
import java.util.List;

public class TransportTestUtils {
    public static final String[] TRANSPORT_NAMES = {
        "android/com.android.internal.backup.LocalTransport",
        "com.google.android.gms/.backup.migrate.service.D2dTransport",
        "com.google.android.gms/.backup.BackupTransportService"
    };

    public static final String TRANSPORT_NAME = TRANSPORT_NAMES[0];

    /** {@code transportName} has to be in the {@link ComponentName} format (with '/') */
    public static TransportData setUpCurrentTransport(
            TransportManager transportManager, String transportName) throws Exception {
        TransportData transport = setUpTransports(transportManager, transportName).get(0);
        when(transportManager.getCurrentTransportClient(any()))
                .thenReturn(transport.transportClientMock);
        return transport;
    }

    /** {@code transportName} has to be in the {@link ComponentName} format (with '/') */
    public static List<TransportData> setUpTransports(
            TransportManager transportManager, String... transportNames) throws Exception {
        return setUpTransports(
                transportManager,
                Arrays.stream(transportNames)
                        .map(TransportData::new)
                        .toArray(TransportData[]::new));
    }

    /** @see #setUpTransport(TransportManager, TransportData) */
    public static List<TransportData> setUpTransports(
            TransportManager transportManager, TransportData... transports) throws Exception {
        for (TransportData transport : transports) {
            setUpTransport(transportManager, transport);
        }
        return Arrays.asList(transports);
    }

    /**
     * Configures transport according to {@link TransportData}:
     *
     * <ul>
     *   <li>{@link TransportData#transportMock} {@code null} means transport not available.
     *   <li>{@link TransportData#transportClientMock} {@code null} means transport not registered.
     * </ul>
     */
    public static void setUpTransport(TransportManager transportManager, TransportData transport)
            throws Exception {
        String transportName = transport.transportName;
        String transportDirName = transportDirName(transportName);
        ComponentName transportComponent = transportComponentName(transportName);
        IBackupTransport transportMock = transport.transportMock;
        TransportClient transportClientMock = transport.transportClientMock;

        if (transportClientMock != null) {
            // Transport registered
            when(transportManager.getTransportClient(eq(transportName), any()))
                    .thenReturn(transportClientMock);
            when(transportManager.getTransportClientOrThrow(eq(transportName), any()))
                    .thenReturn(transportClientMock);
            when(transportManager.getTransportName(transportComponent)).thenReturn(transportName);
            when(transportManager.getTransportDirName(eq(transportName)))
                    .thenReturn(transportDirName);
            when(transportManager.getTransportDirName(eq(transportComponent)))
                    .thenReturn(transportDirName);
            when(transportClientMock.getTransportComponent()).thenReturn(transportComponent);

            if (transportMock != null) {
                // Transport registered and available
                when(transportClientMock.connectOrThrow(any())).thenReturn(transportMock);
                when(transportMock.name()).thenReturn(transportName);
                when(transportMock.transportDirName()).thenReturn(transportDirName);
            } else {
                // Transport registered but unavailable
                when(transportClientMock.connectOrThrow(any()))
                        .thenThrow(TransportNotAvailableException.class);
            }
        } else {
            // Transport not registered
            when(transportManager.getTransportClient(eq(transportName), any())).thenReturn(null);
            when(transportManager.getTransportClientOrThrow(eq(transportName), any()))
                    .thenThrow(TransportNotRegisteredException.class);
            when(transportManager.getTransportName(transportComponent))
                    .thenThrow(TransportNotRegisteredException.class);
            when(transportManager.getTransportDirName(eq(transportName)))
                    .thenThrow(TransportNotRegisteredException.class);
            when(transportManager.getTransportDirName(eq(transportComponent)))
                    .thenThrow(TransportNotRegisteredException.class);
        }
    }

    /** {@code transportName} has to be in the {@link ComponentName} format (with '/') */
    public static ComponentName transportComponentName(String transportName) {
        return ComponentName.unflattenFromString(transportName);
    }

    public static String transportDirName(String transportName) {
        return transportName + "_dir_name";
    }

    public static class TransportData {
        public final String transportName;
        @Nullable public final IBackupTransport transportMock;
        @Nullable public final TransportClient transportClientMock;

        public TransportData(
                String transportName,
                @Nullable IBackupTransport transportMock,
                @Nullable TransportClient transportClientMock) {
            this.transportName = transportName;
            this.transportMock = transportMock;
            this.transportClientMock = transportClientMock;
        }

        public TransportData(String transportName) {
            this(transportName, mock(IBackupTransport.class), mock(TransportClient.class));
        }
    }

    private TransportTestUtils() {}
}
