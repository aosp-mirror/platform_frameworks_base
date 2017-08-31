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

import android.annotation.RequiresPermission;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.builder.DefaultPackageManager;
import org.robolectric.res.builder.RobolectricPackageManager;
import org.robolectric.shadows.ShadowContextImpl;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 23,
        shadows = {TransportManagerTest.ShadowContextImplWithBindServiceAsUser.class}
)
public class TransportManagerTest {
    private static final String PACKAGE_NAME = "some.package.name";
    private static final String TRANSPORT1_NAME = "transport1.name";
    private static final String TRANSPORT2_NAME = "transport2.name";
    private static final ComponentName TRANSPORT1_COMPONENT_NAME = new ComponentName(PACKAGE_NAME,
            TRANSPORT1_NAME);
    private static final ComponentName TRANSPORT2_COMPONENT_NAME = new ComponentName(PACKAGE_NAME,
            TRANSPORT2_NAME);
    private static final List<ComponentName> TRANSPORTS_COMPONENT_NAMES = Arrays.asList(
            TRANSPORT1_COMPONENT_NAME, TRANSPORT2_COMPONENT_NAME);

    private RobolectricPackageManager mPackageManager;

    @Mock private TransportManager.TransportBoundListener mTransportBoundListener;

    @Before
    public void setUp() {
        mPackageManager = new DefaultPackageManagerWithQueryIntentServicesAsUser(
                RuntimeEnvironment.getAppResourceLoader());
        RuntimeEnvironment.setRobolectricPackageManager(mPackageManager);
    }

    @Test
    public void onPackageAdded_bindsToAllTransports() {
        Intent intent = new Intent(TransportManager.SERVICE_ACTION_TRANSPORT_HOST);
        intent.setPackage(PACKAGE_NAME);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = PACKAGE_NAME;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;

        mPackageManager.addPackage(packageInfo);

        ResolveInfo transport1 = new ResolveInfo();
        transport1.serviceInfo = new ServiceInfo();
        transport1.serviceInfo.packageName = PACKAGE_NAME;
        transport1.serviceInfo.name = TRANSPORT1_NAME;

        ResolveInfo transport2 = new ResolveInfo();
        transport2.serviceInfo = new ServiceInfo();
        transport2.serviceInfo.packageName = PACKAGE_NAME;
        transport2.serviceInfo.name = TRANSPORT2_NAME;

        mPackageManager.addResolveInfoForIntent(intent, Arrays.asList(transport1, transport2));

        TransportManager transportManager = new TransportManager(
                RuntimeEnvironment.application.getApplicationContext(),
                new HashSet<>(TRANSPORTS_COMPONENT_NAMES),
                null,
                mTransportBoundListener,
                ShadowLooper.getMainLooper());
        transportManager.onPackageAdded(PACKAGE_NAME);

        assertThat(transportManager.getAllTransportComponents()).asList().containsExactlyElementsIn(
                TRANSPORTS_COMPONENT_NAMES);
    }

    private static class DefaultPackageManagerWithQueryIntentServicesAsUser extends
            DefaultPackageManager {

        /* package */ DefaultPackageManagerWithQueryIntentServicesAsUser(
                ResourceLoader appResourceLoader) {
            super(appResourceLoader);
        }

        @Override
        public List<ResolveInfo> queryIntentServicesAsUser(Intent intent, int flags, int userId) {
            return super.queryIntentServices(intent, flags);
        }
    }

    @Implements(className = ShadowContextImpl.CLASS_NAME)
    public static class ShadowContextImplWithBindServiceAsUser extends ShadowContextImpl {

        @Implementation
        public boolean bindServiceAsUser(@RequiresPermission Intent service, ServiceConnection conn,
                int flags, UserHandle user) {
            return true;
        }
    }
}
