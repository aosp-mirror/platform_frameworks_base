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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.shadow.api.Shadow.extract;
import static org.testng.Assert.expectThrows;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.testing.ShadowBackupTransportStub;
import com.android.server.backup.testing.ShadowContextImplForBackup;
import com.android.server.backup.testing.ShadowPackageManagerForBackup;
import com.android.server.backup.testing.TransportBoundListenerStub;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 26,
        shadows = {
                ShadowContextImplForBackup.class,
                ShadowBackupTransportStub.class,
                ShadowPackageManagerForBackup.class
        }
)
@SystemLoaderClasses({TransportManager.class})
@Presubmit
public class TransportManagerTest {
    private static final String PACKAGE_NAME = "some.package.name";
    private static final String ANOTHER_PACKAGE_NAME = "another.package.name";

    private TransportInfo mTransport1;
    private TransportInfo mTransport2;

    private ShadowPackageManager mPackageManagerShadow;

    private final TransportBoundListenerStub mTransportBoundListenerStub =
            new TransportBoundListenerStub(true);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        ShadowLog.stream = System.out;

        mPackageManagerShadow =
                (ShadowPackageManagerForBackup)
                        extract(RuntimeEnvironment.application.getPackageManager());

        mTransport1 = new TransportInfo(
                PACKAGE_NAME,
                "transport1.name",
                new Intent(),
                "currentDestinationString",
                new Intent(),
                "dataManagementLabel");
        mTransport2 = new TransportInfo(
                PACKAGE_NAME,
                "transport2.name",
                new Intent(),
                "currentDestinationString",
                new Intent(),
                "dataManagementLabel");

        ShadowContextImplForBackup.sComponentBinderMap.put(mTransport1.componentName,
                mTransport1.binder);
        ShadowContextImplForBackup.sComponentBinderMap.put(mTransport2.componentName,
                mTransport2.binder);
        ShadowBackupTransportStub.sBinderTransportMap.put(
                mTransport1.binder, mTransport1.binderInterface);
        ShadowBackupTransportStub.sBinderTransportMap.put(
                mTransport2.binder, mTransport2.binderInterface);
    }

    @After
    public void tearDown() throws Exception {
        ShadowContextImplForBackup.resetBackupShadowState();
    }

    @Test
    public void onPackageAdded_bindsToAllTransports() throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, Arrays.asList(mTransport1, mTransport2),
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(Arrays.asList(
                        mTransport1.componentName, mTransport2.componentName)),
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.componentName, mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.name, mTransport2.name));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1.binderInterface))
                .isTrue();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2.binderInterface))
                .isTrue();
    }

    @Test
    public void onPackageAdded_oneTransportUnavailable_bindsToOnlyOneTransport() throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, Arrays.asList(mTransport1, mTransport2),
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

        ShadowContextImplForBackup.sUnbindableComponents.add(mTransport1.componentName);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(Arrays.asList(
                        mTransport1.componentName, mTransport2.componentName)),
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Collections.singleton(mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Collections.singleton(mTransport2.name));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1.binderInterface))
                .isFalse();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2.binderInterface))
                .isTrue();
    }

    @Test
    public void onPackageAdded_whitelistIsNull_doesNotBindToTransports() throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, Arrays.asList(mTransport1, mTransport2),
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                null /* whitelist */,
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).isEmpty();
        assertThat(transportManager.getBoundTransportNames()).isEmpty();
        assertThat(mTransportBoundListenerStub.isCalled()).isFalse();
    }

    @Test
    public void onPackageAdded_onlyOneTransportWhitelisted_onlyConnectsToWhitelistedTransport()
            throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, Arrays.asList(mTransport1, mTransport2),
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(Collections.singleton(mTransport2.componentName)),
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Collections.singleton(mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Collections.singleton(mTransport2.name));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1.binderInterface))
                .isFalse();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2.binderInterface))
                .isTrue();
    }

    @Test
    public void onPackageAdded_appIsNotPrivileged_doesNotBindToTransports() throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, Arrays.asList(mTransport1, mTransport2), 0);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(Arrays.asList(
                        mTransport1.componentName, mTransport2.componentName)),
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).isEmpty();
        assertThat(transportManager.getBoundTransportNames()).isEmpty();
        assertThat(mTransportBoundListenerStub.isCalled()).isFalse();
    }

    @Test
    public void onPackageRemoved_transportsUnbound() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        transportManager.onPackageRemoved(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).isEmpty();
        assertThat(transportManager.getBoundTransportNames()).isEmpty();
    }

    @Test
    public void onPackageRemoved_incorrectPackageName_nothingHappens() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        transportManager.onPackageRemoved(ANOTHER_PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.componentName, mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.name, mTransport2.name));
    }

    @Test
    public void onPackageChanged_oneComponentChanged_onlyOneTransportRebound() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        transportManager.onPackageChanged(PACKAGE_NAME, new String[]{mTransport2.name});

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.componentName, mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.name, mTransport2.name));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1.binderInterface))
                .isFalse();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2.binderInterface))
                .isTrue();
    }

    @Test
    public void onPackageChanged_nothingChanged_noTransportsRebound() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        transportManager.onPackageChanged(PACKAGE_NAME, new String[0]);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.componentName, mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.name, mTransport2.name));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1.binderInterface))
                .isFalse();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2.binderInterface))
                .isFalse();
    }

    @Test
    public void onPackageChanged_unexpectedComponentChanged_noTransportsRebound() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        transportManager.onPackageChanged(PACKAGE_NAME, new String[]{"unexpected.component"});

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.componentName, mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.name, mTransport2.name));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1.binderInterface))
                .isFalse();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2.binderInterface))
                .isFalse();
    }

    @Test
    public void onPackageChanged_transportsRebound() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        transportManager.onPackageChanged(PACKAGE_NAME, new String[]{mTransport2.name});

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.componentName, mTransport2.componentName));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Arrays.asList(mTransport1.name, mTransport2.name));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1.binderInterface))
                .isFalse();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2.binderInterface))
                .isTrue();
    }

    @Test
    public void getTransportBinder_returnsCorrectBinder() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        assertThat(transportManager.getTransportBinder(mTransport1.name)).isEqualTo(
                mTransport1.binderInterface);
        assertThat(transportManager.getTransportBinder(mTransport2.name)).isEqualTo(
                mTransport2.binderInterface);
    }

    @Test
    public void getTransportBinder_incorrectTransportName_returnsNull() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        assertThat(transportManager.getTransportBinder("incorrect.transport")).isNull();
    }

    @Test
    public void getTransportBinder_oneTransportUnavailable_returnsCorrectBinder() throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(Collections.singletonList(mTransport2),
                        Collections.singletonList(mTransport1), mTransport1.name);

        assertThat(transportManager.getTransportBinder(mTransport1.name)).isNull();
        assertThat(transportManager.getTransportBinder(mTransport2.name)).isEqualTo(
                mTransport2.binderInterface);
    }

    @Test
    public void getCurrentTransport_selectTransportNotCalled_returnsDefaultTransport()
            throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        assertThat(transportManager.getCurrentTransportName()).isEqualTo(mTransport1.name);
    }

    @Test
    public void getCurrentTransport_selectTransportCalled_returnsCorrectTransport()
            throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        assertThat(transportManager.getCurrentTransportName()).isEqualTo(mTransport1.name);

        transportManager.selectTransport(mTransport2.name);

        assertThat(transportManager.getCurrentTransportName()).isEqualTo(mTransport2.name);
    }

    @Test
    public void getCurrentTransportBinder_returnsCorrectBinder() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        assertThat(transportManager.getCurrentTransportBinder())
                .isEqualTo(mTransport1.binderInterface);
    }

    @Test
    public void getCurrentTransportBinder_transportNotBound_returnsNull() throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(Collections.singletonList(mTransport2),
                        Collections.singletonList(mTransport1), mTransport2.name);

        transportManager.selectTransport(mTransport1.name);

        assertThat(transportManager.getCurrentTransportBinder()).isNull();
    }

    @Test
    public void getTransportName_returnsCorrectTransportName() throws Exception {
        TransportManager transportManager = createTransportManagerAndSetUpTransports(
                Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        assertThat(transportManager.getTransportName(mTransport1.binderInterface))
                .isEqualTo(mTransport1.name);
        assertThat(transportManager.getTransportName(mTransport2.binderInterface))
                .isEqualTo(mTransport2.name);
    }

    @Test
    public void getTransportName_transportNotBound_returnsNull() throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(Collections.singletonList(mTransport2),
                        Collections.singletonList(mTransport1), mTransport1.name);

        assertThat(transportManager.getTransportName(mTransport1.binderInterface)).isNull();
        assertThat(transportManager.getTransportName(mTransport2.binderInterface))
                .isEqualTo(mTransport2.name);
    }

    @Test
    public void getTransportWhitelist_returnsCorrectWhiteList() throws Exception {
        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(Arrays.asList(mTransport1.componentName, mTransport2.componentName)),
                mTransport1.name,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());

        assertThat(transportManager.getTransportWhitelist()).containsExactlyElementsIn(
                Arrays.asList(mTransport1.componentName, mTransport2.componentName));
    }

    @Test
    public void getTransportWhitelist_whiteListIsNull_returnsEmptyArray() throws Exception {
        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                null /* whitelist */,
                mTransport1.name,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());

        assertThat(transportManager.getTransportWhitelist()).isEmpty();
    }

    @Test
    public void selectTransport_setsTransportCorrectlyAndReturnsPreviousTransport()
            throws Exception {
        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                null /* whitelist */,
                mTransport1.name,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());

        assertThat(transportManager.selectTransport(mTransport2.name)).isEqualTo(mTransport1.name);
        assertThat(transportManager.selectTransport(mTransport1.name)).isEqualTo(mTransport2.name);
    }

    @Test
    public void getTransportClient_forRegisteredTransport_returnCorrectly() throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(
                        Arrays.asList(mTransport1, mTransport2), mTransport1.name);

        TransportClient transportClient =
                transportManager.getTransportClient(mTransport1.name, "caller");

        assertThat(transportClient.getTransportComponent()).isEqualTo(mTransport1.componentName);
    }

    @Test
    public void getTransportClient_forOldNameOfTransportThatChangedName_returnsNull()
            throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(
                        Arrays.asList(mTransport1, mTransport2), mTransport1.name);
        transportManager.updateTransportAttributes(
                mTransport1.componentName, "newName", null, "destinationString", null, null);

        TransportClient transportClient =
                transportManager.getTransportClient(mTransport1.name, "caller");

        assertThat(transportClient).isNull();
    }

    @Test
    public void getTransportClient_forNewNameOfTransportThatChangedName_returnsCorrectly()
            throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(
                        Arrays.asList(mTransport1, mTransport2), mTransport1.name);
        transportManager.updateTransportAttributes(
                mTransport1.componentName, "newName", null, "destinationString", null, null);

        TransportClient transportClient =
                transportManager.getTransportClient("newName", "caller");

        assertThat(transportClient.getTransportComponent()).isEqualTo(mTransport1.componentName);
    }

    @Test
    public void getTransportName_forTransportThatChangedName_returnsNewName()
            throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(
                        Arrays.asList(mTransport1, mTransport2), mTransport1.name);
        transportManager.updateTransportAttributes(
                mTransport1.componentName, "newName", null, "destinationString", null, null);

        String transportName = transportManager.getTransportName(mTransport1.componentName);

        assertThat(transportName).isEqualTo("newName");
    }

    @Test
    public void isTransportRegistered_returnsCorrectly() throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(
                        Collections.singletonList(mTransport1),
                        Collections.singletonList(mTransport2),
                        mTransport1.name);

        assertThat(transportManager.isTransportRegistered(mTransport1.name)).isTrue();
        assertThat(transportManager.isTransportRegistered(mTransport2.name)).isFalse();
    }

    @Test
    public void getTransportAttributes_forRegisteredTransport_returnsCorrectValues()
            throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(
                        Collections.singletonList(mTransport1),
                        mTransport1.name);

        assertThat(transportManager.getTransportConfigurationIntent(mTransport1.name))
                .isEqualTo(mTransport1.binderInterface.configurationIntent());
        assertThat(transportManager.getTransportDataManagementIntent(mTransport1.name))
                .isEqualTo(mTransport1.binderInterface.dataManagementIntent());
        assertThat(transportManager.getTransportDataManagementLabel(mTransport1.name))
                .isEqualTo(mTransport1.binderInterface.dataManagementLabel());
        assertThat(transportManager.getTransportDirName(mTransport1.name))
                .isEqualTo(mTransport1.binderInterface.transportDirName());
    }

    @Test
    public void getTransportAttributes_forUnregisteredTransport_throws()
            throws Exception {
        TransportManager transportManager =
                createTransportManagerAndSetUpTransports(
                        Collections.singletonList(mTransport1),
                        Collections.singletonList(mTransport2),
                        mTransport1.name);

        expectThrows(
                TransportNotRegisteredException.class,
                () -> transportManager.getTransportConfigurationIntent(mTransport2.name));
        expectThrows(
                TransportNotRegisteredException.class,
                () -> transportManager.getTransportDataManagementIntent(
                        mTransport2.name));
        expectThrows(
                TransportNotRegisteredException.class,
                () -> transportManager.getTransportDataManagementLabel(mTransport2.name));
        expectThrows(
                TransportNotRegisteredException.class,
                () -> transportManager.getTransportDirName(mTransport2.name));
    }

    private void setUpPackageWithTransports(String packageName, List<TransportInfo> transports,
            int flags) throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.privateFlags = flags;

        mPackageManagerShadow.addPackage(packageInfo);

        List<ResolveInfo> transportsInfo = new ArrayList<>();
        for (TransportInfo transport : transports) {
            ResolveInfo info = new ResolveInfo();
            info.serviceInfo = new ServiceInfo();
            info.serviceInfo.packageName = packageName;
            info.serviceInfo.name = transport.name;
            transportsInfo.add(info);
        }

        Intent intent = new Intent(TransportManager.SERVICE_ACTION_TRANSPORT_HOST);
        intent.setPackage(packageName);

        mPackageManagerShadow.addResolveInfoForIntent(intent, transportsInfo);
    }

    private TransportManager createTransportManagerAndSetUpTransports(
            List<TransportInfo> availableTransports, String defaultTransportName) throws Exception {
        return createTransportManagerAndSetUpTransports(availableTransports,
                Collections.<TransportInfo>emptyList(), defaultTransportName);
    }

    private TransportManager createTransportManagerAndSetUpTransports(
            List<TransportInfo> availableTransports, List<TransportInfo> unavailableTransports,
            String defaultTransportName)
            throws Exception {
        List<String> availableTransportsNames = new ArrayList<>();
        List<ComponentName> availableTransportsComponentNames = new ArrayList<>();
        for (TransportInfo transport : availableTransports) {
            availableTransportsNames.add(transport.name);
            availableTransportsComponentNames.add(transport.componentName);
        }

        List<ComponentName> allTransportsComponentNames = new ArrayList<>();
        allTransportsComponentNames.addAll(availableTransportsComponentNames);
        for (TransportInfo transport : unavailableTransports) {
            allTransportsComponentNames.add(transport.componentName);
        }

        for (TransportInfo transport : unavailableTransports) {
            ShadowContextImplForBackup.sUnbindableComponents.add(transport.componentName);
        }

        setUpPackageWithTransports(PACKAGE_NAME, Arrays.asList(mTransport1, mTransport2),
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(allTransportsComponentNames),
                defaultTransportName,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                availableTransportsComponentNames);
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                availableTransportsNames);
        for (TransportInfo transport : availableTransports) {
            assertThat(mTransportBoundListenerStub.isCalledForTransport(transport.binderInterface))
                    .isTrue();
        }
        for (TransportInfo transport : unavailableTransports) {
            assertThat(mTransportBoundListenerStub.isCalledForTransport(transport.binderInterface))
                    .isFalse();
        }

        mTransportBoundListenerStub.resetState();

        return transportManager;
    }

    private static class TransportInfo {
        public final String packageName;
        public final String name;
        public final ComponentName componentName;
        public final IBackupTransport binderInterface;
        public final IBinder binder;

        TransportInfo(
                String packageName,
                String name,
                @Nullable Intent configurationIntent,
                String currentDestinationString,
                @Nullable Intent dataManagementIntent,
                String dataManagementLabel) {
            this.packageName = packageName;
            this.name = name;
            this.componentName = new ComponentName(packageName, name);
            this.binder = mock(IBinder.class);
            IBackupTransport transport = mock(IBackupTransport.class);
            try {
                when(transport.name()).thenReturn(name);
                when(transport.configurationIntent()).thenReturn(configurationIntent);
                when(transport.currentDestinationString()).thenReturn(currentDestinationString);
                when(transport.dataManagementIntent()).thenReturn(dataManagementIntent);
                when(transport.dataManagementLabel()).thenReturn(dataManagementLabel);
            } catch (RemoteException e) {
                // Only here to mock methods that throw RemoteException
            }
            this.binderInterface = transport;
        }
    }

}
