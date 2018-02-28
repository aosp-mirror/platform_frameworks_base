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

import static com.android.server.backup.testing.TestUtils.uncheck;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static java.util.stream.Collectors.toList;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.support.annotation.IntDef;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.TransportManager;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportNotAvailableException;
import com.android.server.backup.transport.TransportNotRegisteredException;

import org.robolectric.shadows.ShadowPackageManager;

import java.util.List;
import java.util.stream.Stream;

public class TransportTestUtils {
    /**
     * Differently from {@link #setUpTransports(TransportManager, TransportData...)}, which
     * configures {@link TransportManager}, this is meant to mock the environment for a real
     * TransportManager.
     */
    public static void setUpTransportsForTransportManager(
            ShadowPackageManager shadowPackageManager, TransportData... transports)
            throws Exception {
        for (TransportData transport : transports) {
            ComponentName transportComponent = transport.getTransportComponent();
            String packageName = transportComponent.getPackageName();
            ResolveInfo resolveInfo = resolveInfo(transportComponent);
            shadowPackageManager.addResolveInfoForIntent(transportIntent(), resolveInfo);
            shadowPackageManager.addResolveInfoForIntent(
                    transportIntent().setPackage(packageName), resolveInfo);
        }
    }

    private static Intent transportIntent() {
        return new Intent(TransportManager.SERVICE_ACTION_TRANSPORT_HOST);
    }

    private static ResolveInfo resolveInfo(ComponentName transportComponent) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = transportComponent.getPackageName();
        resolveInfo.serviceInfo.name = transportComponent.getClassName();
        return resolveInfo;
    }

    /** {@code transportName} has to be in the {@link ComponentName} format (with '/') */
    public static TransportMock setUpCurrentTransport(
            TransportManager transportManager, TransportData transport) throws Exception {
        TransportMock transportMock = setUpTransports(transportManager, transport).get(0);
        if (transportMock.transportClient != null) {
            when(transportManager.getCurrentTransportClient(any()))
                    .thenReturn(transportMock.transportClient);
        }
        return transportMock;
    }

    /** @see #setUpTransport(TransportManager, TransportData) */
    public static List<TransportMock> setUpTransports(
            TransportManager transportManager, TransportData... transports) throws Exception {
        return Stream.of(transports)
                .map(transport -> uncheck(() -> setUpTransport(transportManager, transport)))
                .collect(toList());
    }

    public static TransportMock setUpTransport(
            TransportManager transportManager, TransportData transport) throws Exception {
        int status = transport.transportStatus;
        String transportName = transport.transportName;
        ComponentName transportComponent = transport.getTransportComponent();
        String transportDirName = transport.transportDirName;

        TransportMock transportMock = mockTransport(transport);
        if (status == TransportStatus.REGISTERED_AVAILABLE
                || status == TransportStatus.REGISTERED_UNAVAILABLE) {
            // Transport registered
            when(transportManager.getTransportClient(eq(transportName), any()))
                    .thenReturn(transportMock.transportClient);
            when(transportManager.getTransportClientOrThrow(eq(transportName), any()))
                    .thenReturn(transportMock.transportClient);
            when(transportManager.getTransportName(transportComponent)).thenReturn(transportName);
            when(transportManager.getTransportDirName(eq(transportName)))
                    .thenReturn(transportDirName);
            when(transportManager.getTransportDirName(eq(transportComponent)))
                    .thenReturn(transportDirName);
            when(transportManager.isTransportRegistered(eq(transportName))).thenReturn(true);
            // TODO: Mock rest of description methods
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
            when(transportManager.isTransportRegistered(eq(transportName))).thenReturn(false);
        }
        return transportMock;
    }

    public static TransportMock mockTransport(TransportData transport) throws Exception {
        final TransportClient transportClientMock;
        int status = transport.transportStatus;
        ComponentName transportComponent = transport.getTransportComponent();
        if (status == TransportStatus.REGISTERED_AVAILABLE
                || status == TransportStatus.REGISTERED_UNAVAILABLE) {
            // Transport registered
            transportClientMock = mock(TransportClient.class);
            when(transportClientMock.getTransportComponent()).thenReturn(transportComponent);
            if (status == TransportStatus.REGISTERED_AVAILABLE) {
                // Transport registered and available
                IBackupTransport transportMock = mockTransportBinder(transport);
                when(transportClientMock.connectOrThrow(any())).thenReturn(transportMock);
                when(transportClientMock.connect(any())).thenReturn(transportMock);

                return new TransportMock(transportClientMock, transportMock);
            } else {
                // Transport registered but unavailable
                when(transportClientMock.connectOrThrow(any()))
                        .thenThrow(TransportNotAvailableException.class);
                when(transportClientMock.connect(any())).thenReturn(null);

                return new TransportMock(transportClientMock, null);
            }
        } else {
            // Transport not registered
            return new TransportMock(null, null);
        }
    }

    private static IBackupTransport mockTransportBinder(TransportData transport) throws Exception {
        IBackupTransport transportBinder = mock(IBackupTransport.class);
        try {
            when(transportBinder.name()).thenReturn(transport.transportName);
            when(transportBinder.transportDirName()).thenReturn(transport.transportDirName);
            when(transportBinder.configurationIntent()).thenReturn(transport.configurationIntent);
            when(transportBinder.currentDestinationString())
                    .thenReturn(transport.currentDestinationString);
            when(transportBinder.dataManagementIntent()).thenReturn(transport.dataManagementIntent);
            when(transportBinder.dataManagementLabel()).thenReturn(transport.dataManagementLabel);
        } catch (RemoteException e) {
            fail("RemoteException?");
        }
        return transportBinder;
    }

    public static class TransportMock {
        @Nullable public final TransportClient transportClient;
        @Nullable public final IBackupTransport transport;

        private TransportMock(
                @Nullable TransportClient transportClient, @Nullable IBackupTransport transport) {
            this.transportClient = transportClient;
            this.transport = transport;
        }
    }

    @IntDef({
        TransportStatus.REGISTERED_AVAILABLE,
        TransportStatus.REGISTERED_UNAVAILABLE,
        TransportStatus.UNREGISTERED
    })
    public @interface TransportStatus {
        int REGISTERED_AVAILABLE = 0;
        int REGISTERED_UNAVAILABLE = 1;
        int UNREGISTERED = 2;
    }

    private TransportTestUtils() {}
}
