/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.location;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.ILocationListener;
import android.location.LocationManagerInternal;
import android.location.LocationRequest;
import android.location.flags.Flags;
import android.location.provider.ProviderRequest;
import android.os.IBinder;
import android.os.PowerManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.location.fudger.LocationFudgerCache;
import com.android.server.location.injector.FakeUserInfoHelper;
import com.android.server.location.injector.TestInjector;
import com.android.server.location.provider.AbstractLocationProvider;
import com.android.server.location.provider.LocationProviderManager;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.Collections;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocationManagerServiceTest {
    private static final String PROVIDER_WITH_PERMISSION = "provider_with_permission";
    private static final String PROVIDER_WITHOUT_PERMISSION = "provider_without_permission";
    private static final int CURRENT_USER = FakeUserInfoHelper.DEFAULT_USERID;
    private static final String CALLER_PACKAGE = "caller_package";
    private static final String MISSING_PERMISSION = "missing_permission";
    private static final String ATTRIBUTION_TAG = "test_tag";

    private TestInjector mInjector;
    private LocationManagerService mLocationManagerService;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Spy private FakeAbstractLocationProvider mProviderWithPermission;
    @Spy private FakeAbstractLocationProvider mProviderWithoutPermission;
    @Mock private ProxyPopulationDensityProvider mPopulationDensityProvider;
    @Mock private ILocationListener mLocationListener;
    @Mock private IBinder mBinder;
    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private PackageManager mPackageManager;
    @Mock private AppOpsManager mAppOpsManager;
    @Mock private PowerManager mPowerManager;
    @Mock private PowerManager.WakeLock mWakeLock;
    @Mock private LegacyPermissionManagerInternal mPermissionManagerInternal;

    @Before
    public void setUp() {
        initMocks(this);

        doReturn(mContext).when(mContext).createAttributionContext(any());
        doReturn("android").when(mContext).getPackageName();
        doReturn(mResources).when(mContext).getResources();
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(mPowerManager).when(mContext).getSystemService(PowerManager.class);
        doReturn(mWakeLock).when(mPowerManager).newWakeLock(anyInt(), anyString());
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
        String[] packages = {CALLER_PACKAGE};
        doReturn(InstrumentationRegistry.getInstrumentation().getContext().getContentResolver())
                .when(mContext)
                .getContentResolver();
        doReturn(packages).when(mPackageManager).getPackagesForUid(anyInt());
        doReturn(mBinder).when(mLocationListener).asBinder();
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mContext)
                .checkCallingOrSelfPermission(MISSING_PERMISSION);

        mInjector = new TestInjector(mContext);
        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, true);

        LocalServices.addService(LegacyPermissionManagerInternal.class, mPermissionManagerInternal);

        mLocationManagerService = new LocationManagerService(mContext, mInjector);

        LocationProviderManager managerWithPermission =
                new LocationProviderManager(
                        mContext, mInjector, PROVIDER_WITH_PERMISSION, /* passiveManager= */ null);
        mLocationManagerService.addLocationProviderManager(
                managerWithPermission, mProviderWithPermission);
        LocationProviderManager managerWithoutPermission =
                new LocationProviderManager(
                        mContext,
                        mInjector,
                        PROVIDER_WITHOUT_PERMISSION,
                        /* passiveManager= */ null,
                        Collections.singletonList(MISSING_PERMISSION));
        mLocationManagerService.addLocationProviderManager(
                managerWithoutPermission, mProviderWithoutPermission);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(LegacyPermissionManagerInternal.class);
        LocalServices.removeServiceForTest(LocationManagerInternal.class);
    }

    @Test
    @Ignore("b/274432939") // Test is flaky for as of yet unknown reasons
    public void testRequestLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(0).build();
        mLocationManagerService.registerLocationListener(
                PROVIDER_WITH_PERMISSION,
                request,
                mLocationListener,
                CALLER_PACKAGE,
                ATTRIBUTION_TAG,
                "any_listener_id");
        verify(mProviderWithPermission).onSetRequestPublic(any());
    }

    @Test
    public void testRequestLocationUpdates_noPermission() {
        LocationRequest request = new LocationRequest.Builder(0).build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mLocationManagerService.registerLocationListener(
                                PROVIDER_WITHOUT_PERMISSION,
                                request,
                                mLocationListener,
                                CALLER_PACKAGE,
                                ATTRIBUTION_TAG,
                                "any_listener_id"));
    }

    @Test
    public void testHasProvider() {
        assertThat(mLocationManagerService.hasProvider(PROVIDER_WITH_PERMISSION)).isTrue();
    }

    @Test
    public void testSetLocationFudgerCache_withFeatureFlagDisabled_isNotCalled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        LocationProviderManager manager = mock(LocationProviderManager.class);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        mLocationManagerService.addLocationProviderManager(manager, /* provider = */ null);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        mLocationManagerService.setLocationFudgerCache(cache);

        verify(manager, never()).setLocationFudgerCache(any());
    }

    @Test
    public void testSetLocationFudgerCache_withFeatureFlagEnabled_isCalled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        LocationProviderManager manager = mock(LocationProviderManager.class);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        mLocationManagerService.addLocationProviderManager(manager, /* provider = */ null);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        mLocationManagerService.setLocationFudgerCache(cache);

        verify(manager).setLocationFudgerCache(cache);
    }

    @Test
    public void testHasProvider_noPermission() {
        assertThat(mLocationManagerService.hasProvider(PROVIDER_WITHOUT_PERMISSION)).isFalse();
    }

    @Test
    public void testGetAllProviders() {
        assertThat(mLocationManagerService.getAllProviders()).contains(PROVIDER_WITH_PERMISSION);
        assertThat(mLocationManagerService.getAllProviders())
                .doesNotContain(PROVIDER_WITHOUT_PERMISSION);
    }

    abstract static class FakeAbstractLocationProvider extends AbstractLocationProvider {
        FakeAbstractLocationProvider() {
            super(
                    MoreExecutors.directExecutor(),
                    /* identity= */ null,
                    /* properties= */ null,
                    /* extraAttributionTags= */ Collections.emptySet());
            setAllowed(true);
        }

        @Override
        protected void onSetRequest(ProviderRequest request) {
            // Call a public version of this method so mockito can verify.
            onSetRequestPublic(request);
        }

        public abstract void onSetRequestPublic(ProviderRequest request);
    }
}
