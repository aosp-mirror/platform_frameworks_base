/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.backup;

import static com.android.server.backup.testing.TransportData.genericTransport;
import static com.android.server.backup.testing.TransportTestUtils.mockTransport;
import static com.android.server.backup.testing.TransportTestUtils.setUpTransportsForTransportManager;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.testng.Assert.expectThrows;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

import android.annotation.Nullable;
import android.app.backup.BackupManager;
import android.app.backup.BackupTransport;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.transport.OnTransportRegisteredListener;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportClientManager;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderPackages;
import com.android.server.testing.shadows.FrameworkShadowContextImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 26,
    shadows = {FrameworkShadowContextImpl.class}
)
@SystemLoaderPackages({"com.android.server.backup"})
@Presubmit
public class TransportManagerTest {
    private static final String PACKAGE_A = "some.package.a";
    private static final String PACKAGE_B = "some.package.b";

    @Mock private OnTransportRegisteredListener mListener;
    @Mock private TransportClientManager mTransportClientManager;
    private TransportData mTransportA1;
    private TransportData mTransportA2;
    private TransportData mTransportB1;
    private ShadowPackageManager mShadowPackageManager;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = RuntimeEnvironment.application;
        mShadowPackageManager = shadowOf(mContext.getPackageManager());

        mTransportA1 = genericTransport(PACKAGE_A, "TransportFoo");
        mTransportA2 = genericTransport(PACKAGE_A, "TransportBar");
        mTransportB1 = genericTransport(PACKAGE_B, "TransportBaz");
    }

    @Test
    public void testRegisterTransports() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpPackage(PACKAGE_B, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2, mTransportB1);
        TransportManager transportManager =
                createTransportManager(mTransportA1, mTransportA2, mTransportB1);

        transportManager.registerTransports();

        assertRegisteredTransports(
                transportManager, asList(mTransportA1, mTransportA2, mTransportB1));

        verify(mListener)
                .onTransportRegistered(mTransportA1.transportName, mTransportA1.transportDirName);
        verify(mListener)
                .onTransportRegistered(mTransportA2.transportName, mTransportA2.transportDirName);
        verify(mListener)
                .onTransportRegistered(mTransportB1.transportName, mTransportB1.transportDirName);
    }

    @Test
    public void
            testRegisterTransports_whenOneTransportUnavailable_doesNotRegisterUnavailableTransport()
                    throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        TransportData transport1 = mTransportA1.unavailable();
        TransportData transport2 = mTransportA2;
        setUpTransports(transport1, transport2);
        TransportManager transportManager = createTransportManager(transport1, transport2);

        transportManager.registerTransports();

        assertRegisteredTransports(transportManager, singletonList(transport2));
        verify(mListener, never())
                .onTransportRegistered(transport1.transportName, transport1.transportDirName);
        verify(mListener)
                .onTransportRegistered(transport2.transportName, transport2.transportDirName);
    }

    @Test
    public void testRegisterTransports_whenWhitelistIsEmpty_doesNotRegisterTransports()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(null);

        transportManager.registerTransports();

        assertRegisteredTransports(transportManager, emptyList());
        verify(mListener, never()).onTransportRegistered(any(), any());
    }

    @Test
    public void
            testRegisterTransports_whenOnlyOneTransportWhitelisted_onlyRegistersWhitelistedTransport()
                    throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(null, mTransportA1);

        transportManager.registerTransports();

        assertRegisteredTransports(transportManager, singletonList(mTransportA1));
        verify(mListener)
                .onTransportRegistered(mTransportA1.transportName, mTransportA1.transportDirName);
        verify(mListener, never())
                .onTransportRegistered(mTransportA2.transportName, mTransportA2.transportDirName);
    }

    @Test
    public void testRegisterTransports_whenAppIsNotPrivileged_doesNotRegisterTransports()
            throws Exception {
        // Note ApplicationInfo.PRIVATE_FLAG_PRIVILEGED is missing from flags
        setUpPackage(PACKAGE_A, 0);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager =
                createTransportManager(null, mTransportA1, mTransportA2);

        transportManager.registerTransports();

        assertRegisteredTransports(transportManager, emptyList());
        verify(mListener, never()).onTransportRegistered(any(), any());
    }

    @Test
    public void testRegisterTransports_passesRegistrationExtraToGetTransportClient()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1);

        transportManager.registerTransports();

        verify(mTransportClientManager)
                .getTransportClient(
                        eq(mTransportA1.getTransportComponent()),
                        argThat(bundle ->
                                bundle.getBoolean(BackupTransport.EXTRA_TRANSPORT_REGISTRATION)),
                        anyString());
    }

    @Test
    public void testOnPackageAdded_registerTransports() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1);

        transportManager.onPackageAdded(PACKAGE_A);

        assertRegisteredTransports(transportManager, asList(mTransportA1));
        verify(mListener)
                .onTransportRegistered(mTransportA1.transportName, mTransportA1.transportDirName);
    }

    @Test
    public void testOnPackageRemoved_unregisterTransports() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpPackage(PACKAGE_B, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportB1);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportB1);
        transportManager.registerTransports();

        transportManager.onPackageRemoved(PACKAGE_A);

        assertRegisteredTransports(transportManager, singletonList(mTransportB1));
    }

    @Test
    public void testOnPackageRemoved_whenUnknownPackage_nothingHappens() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1);
        transportManager.registerTransports();

        transportManager.onPackageRemoved(PACKAGE_A + "unknown");

        assertRegisteredTransports(transportManager, singletonList(mTransportA1));
    }

    @Test
    public void testOnPackageChanged_whenOneComponentChanged_onlyOneTransportReRegistered()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();
        // Reset listener to verify calls after registerTransports() above
        reset(mListener);

        transportManager.onPackageChanged(
                PACKAGE_A, mTransportA1.getTransportComponent().getClassName());

        assertRegisteredTransports(transportManager, asList(mTransportA1, mTransportA2));
        verify(mListener)
                .onTransportRegistered(mTransportA1.transportName, mTransportA1.transportDirName);
        verify(mListener, never())
                .onTransportRegistered(mTransportA2.transportName, mTransportA2.transportDirName);
    }

    @Test
    public void testOnPackageChanged_whenNoComponentsChanged_doesNotRegisterTransports()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1);
        transportManager.registerTransports();
        reset(mListener);

        transportManager.onPackageChanged(PACKAGE_A);

        assertRegisteredTransports(transportManager, singletonList(mTransportA1));
        verify(mListener, never()).onTransportRegistered(any(), any());
    }

    @Test
    public void testOnPackageChanged_whenUnknownComponentChanged_noTransportsRegistered()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1);
        transportManager.registerTransports();
        reset(mListener);

        transportManager.onPackageChanged(PACKAGE_A, PACKAGE_A + ".UnknownComponent");

        assertRegisteredTransports(transportManager, singletonList(mTransportA1));
        verify(mListener, never()).onTransportRegistered(any(), any());
    }

    @Test
    public void testOnPackageChanged_reRegisterTransports() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();
        reset(mListener);

        transportManager.onPackageChanged(
                PACKAGE_A,
                mTransportA1.getTransportComponent().getClassName(),
                mTransportA2.getTransportComponent().getClassName());

        assertRegisteredTransports(transportManager, asList(mTransportA1, mTransportA2));
        verify(mListener)
                .onTransportRegistered(mTransportA1.transportName, mTransportA1.transportDirName);
        verify(mListener)
                .onTransportRegistered(mTransportA2.transportName, mTransportA2.transportDirName);
    }

    @Test
    public void testRegisterAndSelectTransport_whenTransportRegistered() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(null, mTransportA1);
        transportManager.registerTransports();
        ComponentName transportComponent = mTransportA1.getTransportComponent();

        int result = transportManager.registerAndSelectTransport(transportComponent);

        assertThat(result).isEqualTo(BackupManager.SUCCESS);
        assertThat(transportManager.getRegisteredTransportComponents())
                .asList()
                .contains(transportComponent);
        assertThat(transportManager.getCurrentTransportName())
                .isEqualTo(mTransportA1.transportName);
    }

    @Test
    public void testRegisterAndSelectTransport_whenTransportNotRegistered() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(null, mTransportA1);
        ComponentName transportComponent = mTransportA1.getTransportComponent();

        int result = transportManager.registerAndSelectTransport(transportComponent);

        assertThat(result).isEqualTo(BackupManager.SUCCESS);
        assertThat(transportManager.getRegisteredTransportComponents())
                .asList()
                .contains(transportComponent);
        assertThat(transportManager.getTransportDirName(mTransportA1.transportName))
                .isEqualTo(mTransportA1.transportDirName);
        assertThat(transportManager.getCurrentTransportName())
                .isEqualTo(mTransportA1.transportName);
    }

    @Test
    public void testGetCurrentTransportName_whenSelectTransportNotCalled_returnsDefaultTransport()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();

        String currentTransportName = transportManager.getCurrentTransportName();

        assertThat(currentTransportName).isEqualTo(mTransportA1.transportName);
    }

    @Test
    public void testGetCurrentTransport_whenSelectTransportCalled_returnsSelectedTransport()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();
        transportManager.selectTransport(mTransportA2.transportName);

        String currentTransportName = transportManager.getCurrentTransportName();

        assertThat(currentTransportName).isEqualTo(mTransportA2.transportName);
    }

    @Test
    public void testGetTransportWhitelist() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);

        Set<ComponentName> transportWhitelist = transportManager.getTransportWhitelist();

        assertThat(transportWhitelist)
                .containsExactlyElementsIn(
                        asList(
                                mTransportA1.getTransportComponent(),
                                mTransportA2.getTransportComponent()));
    }

    @Test
    public void testSelectTransport() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager =
                createTransportManager(null, mTransportA1, mTransportA2);

        String transport1 = transportManager.selectTransport(mTransportA1.transportName);
        String transport2 = transportManager.selectTransport(mTransportA2.transportName);

        assertThat(transport1).isNull();
        assertThat(transport2).isEqualTo(mTransportA1.transportName);
    }

    @Test
    public void testGetTransportClient_forRegisteredTransport() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();

        TransportClient transportClient =
                transportManager.getTransportClient(mTransportA1.transportName, "caller");

        assertThat(transportClient.getTransportComponent())
                .isEqualTo(mTransportA1.getTransportComponent());
    }

    @Test
    public void testGetTransportClient_forOldNameOfTransportThatChangedName_returnsNull()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();
        transportManager.updateTransportAttributes(
                mTransportA1.getTransportComponent(),
                "newName",
                null,
                "destinationString",
                null,
                null);

        TransportClient transportClient =
                transportManager.getTransportClient(mTransportA1.transportName, "caller");

        assertThat(transportClient).isNull();
    }

    @Test
    public void testGetTransportClient_forNewNameOfTransportThatChangedName_returnsCorrectly()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();
        transportManager.updateTransportAttributes(
                mTransportA1.getTransportComponent(),
                "newName",
                null,
                "destinationString",
                null,
                null);

        TransportClient transportClient = transportManager.getTransportClient("newName", "caller");

        assertThat(transportClient.getTransportComponent())
                .isEqualTo(mTransportA1.getTransportComponent());
    }

    @Test
    public void testGetTransportName_forTransportThatChangedName_returnsNewName() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1, mTransportA2);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();
        transportManager.updateTransportAttributes(
                mTransportA1.getTransportComponent(),
                "newName",
                null,
                "destinationString",
                null,
                null);

        String transportName =
                transportManager.getTransportName(mTransportA1.getTransportComponent());

        assertThat(transportName).isEqualTo("newName");
    }

    @Test
    public void testIsTransportRegistered() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1, mTransportA2);
        transportManager.registerTransports();

        boolean isTransportA1Registered =
                transportManager.isTransportRegistered(mTransportA1.transportName);
        boolean isTransportA2Registered =
                transportManager.isTransportRegistered(mTransportA2.transportName);

        assertThat(isTransportA1Registered).isTrue();
        assertThat(isTransportA2Registered).isFalse();
    }

    @Test
    public void testGetTransportAttributes_forRegisteredTransport_returnsCorrectValues()
            throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1);
        transportManager.registerTransports();

        Intent configurationIntent =
                transportManager.getTransportConfigurationIntent(mTransportA1.transportName);
        Intent dataManagementIntent =
                transportManager.getTransportDataManagementIntent(mTransportA1.transportName);
        String dataManagementLabel =
                transportManager.getTransportDataManagementLabel(mTransportA1.transportName);
        String transportDirName = transportManager.getTransportDirName(mTransportA1.transportName);

        assertThat(configurationIntent).isEqualTo(mTransportA1.configurationIntent);
        assertThat(dataManagementIntent).isEqualTo(mTransportA1.dataManagementIntent);
        assertThat(dataManagementLabel).isEqualTo(mTransportA1.dataManagementLabel);
        assertThat(transportDirName).isEqualTo(mTransportA1.transportDirName);
    }

    @Test
    public void testGetTransportAttributes_forUnregisteredTransport_throws() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpTransports(mTransportA1);
        TransportManager transportManager = createTransportManager(mTransportA1);
        transportManager.registerTransports();

        expectThrows(
                TransportNotRegisteredException.class,
                () -> transportManager.getTransportConfigurationIntent(mTransportA2.transportName));
        expectThrows(
                TransportNotRegisteredException.class,
                () ->
                        transportManager.getTransportDataManagementIntent(
                                mTransportA2.transportName));
        expectThrows(
                TransportNotRegisteredException.class,
                () -> transportManager.getTransportDataManagementLabel(mTransportA2.transportName));
        expectThrows(
                TransportNotRegisteredException.class,
                () -> transportManager.getTransportDirName(mTransportA2.transportName));
    }

    @Test
    public void testGetRegisteredTransportNames() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpPackage(PACKAGE_B, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        TransportData[] transportsData = {mTransportA1, mTransportA2, mTransportB1};
        setUpTransports(transportsData);
        TransportManager transportManager =
                createTransportManager(mTransportA1, mTransportA2, mTransportB1);
        transportManager.registerTransports();

        String[] transportNames = transportManager.getRegisteredTransportNames();

        assertThat(transportNames)
                .asList()
                .containsExactlyElementsIn(
                        Stream.of(transportsData)
                                .map(transportData -> transportData.transportName)
                                .collect(toList()));
    }

    @Test
    public void testGetRegisteredTransportComponents() throws Exception {
        setUpPackage(PACKAGE_A, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        setUpPackage(PACKAGE_B, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        TransportData[] transportsData = {mTransportA1, mTransportA2, mTransportB1};
        setUpTransports(transportsData);
        TransportManager transportManager =
                createTransportManager(mTransportA1, mTransportA2, mTransportB1);
        transportManager.registerTransports();

        ComponentName[] transportNames = transportManager.getRegisteredTransportComponents();

        assertThat(transportNames)
                .asList()
                .containsExactlyElementsIn(
                        Stream.of(transportsData)
                                .map(TransportData::getTransportComponent)
                                .collect(toList()));
    }

    private List<TransportMock> setUpTransports(TransportData... transports) throws Exception {
        setUpTransportsForTransportManager(mShadowPackageManager, transports);
        List<TransportMock> transportMocks = new ArrayList<>(transports.length);
        for (TransportData transport : transports) {
            TransportMock transportMock = mockTransport(transport);
            when(mTransportClientManager.getTransportClient(
                            eq(transport.getTransportComponent()), any()))
                    .thenReturn(transportMock.transportClient);
            when(mTransportClientManager.getTransportClient(
                            eq(transport.getTransportComponent()), any(), any()))
                    .thenReturn(transportMock.transportClient);
            transportMocks.add(transportMock);
        }
        return transportMocks;
    }

    private void setUpPackage(String packageName, int flags) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.privateFlags = flags;
        mShadowPackageManager.addPackage(packageInfo);
    }

    private TransportManager createTransportManager(
            @Nullable TransportData selectedTransport, TransportData... transports) {
        Set<ComponentName> whitelist =
                concat(Stream.of(selectedTransport), Stream.of(transports))
                        .filter(Objects::nonNull)
                        .map(TransportData::getTransportComponent)
                        .collect(toSet());
        TransportManager transportManager =
                new TransportManager(
                        mContext,
                        whitelist,
                        selectedTransport != null ? selectedTransport.transportName : null,
                        mTransportClientManager);
        transportManager.setOnTransportRegisteredListener(mListener);
        return transportManager;
    }

    private void assertRegisteredTransports(
            TransportManager transportManager, List<TransportData> transports) {
        assertThat(transportManager.getRegisteredTransportComponents())
                .asList()
                .containsExactlyElementsIn(
                        transports
                                .stream()
                                .map(TransportData::getTransportComponent)
                                .collect(toList()));
        assertThat(transportManager.getRegisteredTransportNames())
                .asList()
                .containsExactlyElementsIn(
                        transports.stream().map(t -> t.transportName).collect(toList()));
    }
}
