/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.os.Bundle;
import android.provider.Settings;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NetworkScorerAppManagerTest {
    private static String MOCK_SERVICE_LABEL = "Mock Service";
    private static String MOCK_OVERRIDEN_SERVICE_LABEL = "Mock Service Label Override";

    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPm;
    @Mock private Resources mResources;
    @Mock private NetworkScorerAppManager.SettingsFacade mSettingsFacade;
    private NetworkScorerAppManager mNetworkScorerAppManager;
    private List<ResolveInfo> mAvailableServices;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mAvailableServices = new ArrayList<>();
        when(mMockContext.getPackageManager()).thenReturn(mMockPm);
        when(mMockPm.queryIntentServices(Mockito.argThat(new ArgumentMatcher<Intent>() {
            @Override
            public boolean matches(Object object) {
                Intent intent = (Intent) object;
                return NetworkScoreManager.ACTION_RECOMMEND_NETWORKS.equals(intent.getAction());
            }
        }), eq(PackageManager.GET_META_DATA))).thenReturn(mAvailableServices);
        when(mMockContext.getResources()).thenReturn(mResources);

        mNetworkScorerAppManager = new NetworkScorerAppManager(mMockContext, mSettingsFacade);
    }

    @Test
    public void testGetActiveScorer_providerAvailable() throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecoPackageSetting(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(recoComponent, activeScorer.getRecommendationServiceComponent());
        assertEquals(924, activeScorer.packageUid);
        assertEquals(MOCK_SERVICE_LABEL, activeScorer.getRecommendationServiceLabel());
    }

    @Test
    public void testGetActiveScorer_providerAvailable_serviceLabelOverride() throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecoPackageSetting(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */,
                null /* enableUseOpenWifiPackageActivityPackage*/, true /* serviceLabelOverride */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(recoComponent, activeScorer.getRecommendationServiceComponent());
        assertEquals(924, activeScorer.packageUid);
        assertEquals(MOCK_OVERRIDEN_SERVICE_LABEL, activeScorer.getRecommendationServiceLabel());
    }

    @Test
    public void testGetActiveScorer_permissionMissing() throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecoPackageSetting(recoComponent.getPackageName());
        mockScoreNetworksDenied(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    @Test
    public void testGetActiveScorer_providerAvailable_enableUseOpenWifiActivityNotSet()
            throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecoPackageSetting(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */,
                null /* enableUseOpenWifiPackageActivityPackage*/);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(recoComponent, activeScorer.getRecommendationServiceComponent());
        assertEquals(924, activeScorer.packageUid);
        assertNull(activeScorer.getEnableUseOpenWifiActivity());
    }

    @Test
    public void testGetActiveScorer_providerAvailable_enableUseOpenWifiActivityNotResolved()
            throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecoPackageSetting(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */,
                "package2" /* enableUseOpenWifiPackageActivityPackage*/);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(recoComponent, activeScorer.getRecommendationServiceComponent());
        assertEquals(924, activeScorer.packageUid);
        assertNull(activeScorer.getEnableUseOpenWifiActivity());
    }

    @Test
    public void testGetActiveScorer_providerAvailable_enableUseOpenWifiActivityResolved()
            throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        final ComponentName enableUseOpenWifiComponent = new ComponentName("package2", "class2");
        setNetworkRecoPackageSetting(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */,
                enableUseOpenWifiComponent.getPackageName());
        mockEnableUseOpenWifiActivity(enableUseOpenWifiComponent);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(recoComponent, activeScorer.getRecommendationServiceComponent());
        assertEquals(924, activeScorer.packageUid);
        assertEquals(enableUseOpenWifiComponent, activeScorer.getEnableUseOpenWifiActivity());
    }

    @Test
    public void testGetActiveScorer_packageSettingIsNull()
            throws Exception {
        // NETWORK_RECOMMENDATIONS_PACKAGE is null

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    @Test
    public void testGetActiveScorer_packageSettingIsInvalid() throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setDefaultNetworkRecommendationPackage(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        // NETWORK_RECOMMENDATIONS_PACKAGE is set to a package that isn't a recommender.

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    @Test
    public void testSetActiveScorer_noChange() throws Exception {
        String packageName = "package";
        setNetworkRecoPackageSetting(packageName);

        assertTrue(mNetworkScorerAppManager.setActiveScorer(packageName));
        verify(mSettingsFacade, never()).putString(any(), any(), any());
    }

    @Test
    public void testSetActiveScorer_nullPackage() throws Exception {
        String packageName = "package";
        String defaultPackage = "defaultPackage";
        setNetworkRecoPackageSetting(packageName);
        setDefaultNetworkRecommendationPackage(defaultPackage);

        assertTrue(mNetworkScorerAppManager.setActiveScorer(null));
        verify(mSettingsFacade).putString(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE, defaultPackage);
    }

    @Test
    public void testSetActiveScorer_validPackage() throws Exception {
        String packageName = "package";
        String newPackage = "newPackage";
        setNetworkRecoPackageSetting(packageName);
        final ComponentName recoComponent = new ComponentName(newPackage, "class1");
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */, null);

        assertTrue(mNetworkScorerAppManager.setActiveScorer(newPackage));
        verify(mSettingsFacade).putString(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE, newPackage);
    }

    @Test
    public void testSetActiveScorer_invalidPackage() throws Exception {
        String packageName = "package";
        String newPackage = "newPackage";
        setNetworkRecoPackageSetting(packageName);
        // newPackage doesn't resolve to a valid recommender

        assertFalse(mNetworkScorerAppManager.setActiveScorer(newPackage));
        verify(mSettingsFacade, never()).putString(any(), any(), any());
    }


    @Test
    public void testRevertToDefaultIfNoActive_notActive() throws Exception {
        String defaultPackage = "defaultPackage";
        setDefaultNetworkRecommendationPackage(defaultPackage);

        mNetworkScorerAppManager.revertToDefaultIfNoActive();

        verify(mSettingsFacade).putString(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE, defaultPackage);
    }

    @Test
    public void testRevertToDefaultIfNoActive_active() throws Exception {
        String packageName = "package";
        setNetworkRecoPackageSetting(packageName);
        final ComponentName recoComponent = new ComponentName(packageName, "class1");
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */, null);

        mNetworkScorerAppManager.revertToDefaultIfNoActive();

        verify(mSettingsFacade, never()).putString(any(), any(), any());
    }

    private void setNetworkRecoPackageSetting(String packageName) {
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE)).thenReturn(packageName);
    }

    private void setDefaultNetworkRecommendationPackage(String name) {
        when(mResources.getString(R.string.config_defaultNetworkRecommendationProviderPackage))
                .thenReturn(name);
    }

    private void mockScoreNetworksGranted(String packageName) {
        when(mMockPm.checkPermission(permission.SCORE_NETWORKS, packageName))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
    }

    private void mockScoreNetworksDenied(String packageName) {
        when(mMockPm.checkPermission(permission.SCORE_NETWORKS, packageName))
                .thenReturn(PackageManager.PERMISSION_DENIED);
    }

    private void mockRecommendationServiceAvailable(final ComponentName compName, int packageUid) {
        mockRecommendationServiceAvailable(compName, packageUid, null, false);
    }

    private void mockRecommendationServiceAvailable(final ComponentName compName, int packageUid,
            String enableUseOpenWifiActivityPackage) {
        mockRecommendationServiceAvailable(
                compName, packageUid, enableUseOpenWifiActivityPackage, false);
    }

    private void mockRecommendationServiceAvailable(final ComponentName compName, int packageUid,
            String enableUseOpenWifiActivityPackage, boolean serviceLabelOverride) {
        final ResolveInfo serviceInfo = new ResolveInfo();
        serviceInfo.serviceInfo = new ServiceInfo();
        serviceInfo.serviceInfo.name = compName.getClassName();
        serviceInfo.serviceInfo.packageName = compName.getPackageName();
        serviceInfo.serviceInfo.applicationInfo = new ApplicationInfo();
        serviceInfo.serviceInfo.applicationInfo.uid = packageUid;
        if (enableUseOpenWifiActivityPackage != null) {
            serviceInfo.serviceInfo.metaData = new Bundle();
            serviceInfo.serviceInfo.metaData.putString(
                    NetworkScoreManager.USE_OPEN_WIFI_PACKAGE_META_DATA,
                    enableUseOpenWifiActivityPackage);
        }
        if (serviceLabelOverride) {
            if (serviceInfo.serviceInfo.metaData == null) {
                serviceInfo.serviceInfo.metaData = new Bundle();
            }
            serviceInfo.serviceInfo.metaData.putString(
                    NetworkScoreManager.RECOMMENDATION_SERVICE_LABEL_META_DATA,
                    MOCK_OVERRIDEN_SERVICE_LABEL);
        } else {
            serviceInfo.serviceInfo.nonLocalizedLabel = MOCK_SERVICE_LABEL;
        }

        final int flags = PackageManager.GET_META_DATA;
        when(mMockPm.resolveService(
                Mockito.argThat(new ArgumentMatcher<Intent>() {
                    @Override
                    public boolean matches(Object object) {
                        Intent intent = (Intent) object;
                        return NetworkScoreManager.ACTION_RECOMMEND_NETWORKS
                                .equals(intent.getAction())
                                && compName.getPackageName().equals(intent.getPackage());
                    }
                }), Mockito.eq(flags))).thenReturn(serviceInfo);

        mAvailableServices.add(serviceInfo);
    }

    private void mockEnableUseOpenWifiActivity(final ComponentName useOpenWifiComp) {
        final ResolveInfo resolveActivityInfo = new ResolveInfo();
        resolveActivityInfo.activityInfo = new ActivityInfo();
        resolveActivityInfo.activityInfo.name = useOpenWifiComp.getClassName();
        resolveActivityInfo.activityInfo.packageName = useOpenWifiComp.getPackageName();

        final int flags = 0;
        when(mMockPm.resolveActivity(
                Mockito.argThat(new ArgumentMatcher<Intent>() {
                    @Override
                    public boolean matches(Object object) {
                        Intent intent = (Intent) object;
                        return NetworkScoreManager.ACTION_CUSTOM_ENABLE.equals(intent.getAction())
                                && useOpenWifiComp.getPackageName().equals(intent.getPackage());
                    }
                }), Mockito.eq(flags))).thenReturn(resolveActivityInfo);
    }
}
