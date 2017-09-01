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

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.testing.BackupTransportStub;
import com.android.server.backup.testing.DefaultPackageManagerWithQueryIntentServicesAsUser;
import com.android.server.backup.testing.ShadowBackupTransportStub;
import com.android.server.backup.testing.ShadowContextImplWithBindServiceAsUser;
import com.android.server.backup.testing.TransportBoundListenerStub;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.res.builder.RobolectricPackageManager;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 23,
        shadows = {
                ShadowContextImplWithBindServiceAsUser.class,
                ShadowBackupTransportStub.class
        }
)
@Presubmit
public class TransportManagerTest {
    private static final String PACKAGE_NAME = "some.package.name";
    private static final String TRANSPORT1_NAME = "transport1.name";
    private static final String TRANSPORT2_NAME = "transport2.name";
    private static final List<String> TRANSPORTS_NAMES = Arrays.asList(
            TRANSPORT1_NAME, TRANSPORT2_NAME);
    private static final ComponentName TRANSPORT1_COMPONENT_NAME = new ComponentName(PACKAGE_NAME,
            TRANSPORT1_NAME);
    private static final ComponentName TRANSPORT2_COMPONENT_NAME = new ComponentName(PACKAGE_NAME,
            TRANSPORT2_NAME);
    private static final List<ComponentName> TRANSPORTS_COMPONENT_NAMES = Arrays.asList(
            TRANSPORT1_COMPONENT_NAME, TRANSPORT2_COMPONENT_NAME);

    private RobolectricPackageManager mPackageManager;

    @Mock private IBinder mTransport1BinderMock;
    @Mock private IBinder mTransport2BinderMock;

    private final BackupTransportStub mTransport1Stub = new BackupTransportStub(TRANSPORT1_NAME);
    private final BackupTransportStub mTransport2Stub = new BackupTransportStub(TRANSPORT2_NAME);
    private final TransportBoundListenerStub mTransportBoundListenerStub =
            new TransportBoundListenerStub(true);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        ShadowLog.stream = System.out;
        mPackageManager = new DefaultPackageManagerWithQueryIntentServicesAsUser(
                RuntimeEnvironment.getAppResourceLoader());
        RuntimeEnvironment.setRobolectricPackageManager(mPackageManager);

        ShadowContextImplWithBindServiceAsUser.sComponentBinderMap.put(TRANSPORT1_COMPONENT_NAME,
                mTransport1BinderMock);
        ShadowContextImplWithBindServiceAsUser.sComponentBinderMap.put(TRANSPORT2_COMPONENT_NAME,
                mTransport2BinderMock);
        ShadowBackupTransportStub.sBinderTransportMap.put(mTransport1BinderMock, mTransport1Stub);
        ShadowBackupTransportStub.sBinderTransportMap.put(mTransport2BinderMock, mTransport2Stub);
    }

    @Test
    public void onPackageAdded_bindsToAllTransports() throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, TRANSPORTS_NAMES,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(TRANSPORTS_COMPONENT_NAMES),
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                TRANSPORTS_COMPONENT_NAMES);
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                TRANSPORTS_NAMES);
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1Stub)).isTrue();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2Stub)).isTrue();
    }

    @Test
    public void onPackageAdded_whitelistIsNull_doesNotBindToTransports() throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, TRANSPORTS_NAMES,
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
        setUpPackageWithTransports(PACKAGE_NAME, TRANSPORTS_NAMES,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(Collections.singleton(TRANSPORT2_COMPONENT_NAME)),
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                Collections.singleton(TRANSPORT2_COMPONENT_NAME));
        assertThat(transportManager.getBoundTransportNames()).asList().containsExactlyElementsIn(
                Collections.singleton(TRANSPORT2_NAME));
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport1Stub)).isFalse();
        assertThat(mTransportBoundListenerStub.isCalledForTransport(mTransport2Stub)).isTrue();
    }

    @Test
    public void onPackageAdded_appIsNotPrivileged_doesNotBindToTransports() throws Exception {
        setUpPackageWithTransports(PACKAGE_NAME, TRANSPORTS_NAMES, 0);

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(TRANSPORTS_COMPONENT_NAMES),
                null /* defaultTransport */,
                mTransportBoundListenerStub,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).isEmpty();
        assertThat(transportManager.getBoundTransportNames()).isEmpty();
        assertThat(mTransportBoundListenerStub.isCalled()).isFalse();
    }

    private void setUpPackageWithTransports(String packageName, List<String> transportNames,
            int flags) throws Exception {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.privateFlags = flags;

        mPackageManager.addPackage(packageInfo);

        List<ResolveInfo> transportsInfo = new ArrayList<>();
        for (String transportName : transportNames) {
            ResolveInfo info = new ResolveInfo();
            info.serviceInfo = new ServiceInfo();
            info.serviceInfo.packageName = packageName;
            info.serviceInfo.name = transportName;
            transportsInfo.add(info);
        }

        Intent intent = new Intent(TransportManager.SERVICE_ACTION_TRANSPORT_HOST);
        intent.setPackage(packageName);

        mPackageManager.addResolveInfoForIntent(intent, transportsInfo);
    }

}
