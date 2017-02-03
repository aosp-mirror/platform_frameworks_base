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

package android.net;

import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.provider.Settings;
import android.test.InstrumentationTestCase;

import com.android.internal.R;

import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;

public class NetworkScorerAppManagerTest extends InstrumentationTestCase {
    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPm;
    @Mock private Resources mResources;
    @Mock private ContentResolver mContentResolver;
    private Context mTargetContext;
    private NetworkScorerAppManager mNetworkScorerAppManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Configuration needed to make mockito/dexcache work.
        mTargetContext = getInstrumentation().getTargetContext();
        System.setProperty("dexmaker.dexcache", mTargetContext.getCacheDir().getPath());
        ClassLoader newClassLoader = getInstrumentation().getClass().getClassLoader();
        Thread.currentThread().setContextClassLoader(newClassLoader);

        MockitoAnnotations.initMocks(this);
        when(mMockContext.getPackageManager()).thenReturn(mMockPm);
        when(mMockContext.getResources()).thenReturn(mResources);
        when(mMockContext.getContentResolver()).thenReturn(mTargetContext.getContentResolver());
        mNetworkScorerAppManager = new NetworkScorerAppManager(mMockContext);
    }

    public void testGetPotentialRecommendationProviderPackages_emptyConfig() throws Exception {
        setNetworkRecommendationPackageNames(/*no configured packages*/);
        assertTrue(mNetworkScorerAppManager.getPotentialRecommendationProviderPackages().isEmpty());
    }

    public void testGetPotentialRecommendationProviderPackages_permissionNotGranted()
            throws Exception {
        setNetworkRecommendationPackageNames("package1");
        mockScoreNetworksDenied("package1");

        assertTrue(mNetworkScorerAppManager.getPotentialRecommendationProviderPackages().isEmpty());
    }

    public void testGetPotentialRecommendationProviderPackages_permissionGranted()
            throws Exception {
        setNetworkRecommendationPackageNames("package1");
        mockScoreNetworksGranted("package1");

        List<String> potentialProviderPackages =
                mNetworkScorerAppManager.getPotentialRecommendationProviderPackages();

        assertFalse(potentialProviderPackages.isEmpty());
        assertEquals("package1", potentialProviderPackages.get(0));
    }

    public void testGetPotentialRecommendationProviderPackages_multipleConfigured()
            throws Exception {
        setNetworkRecommendationPackageNames("package1", "package2");
        mockScoreNetworksDenied("package1");
        mockScoreNetworksGranted("package2");

        List<String> potentialProviderPackages =
                mNetworkScorerAppManager.getPotentialRecommendationProviderPackages();

        assertEquals(1, potentialProviderPackages.size());
        assertEquals("package2", potentialProviderPackages.get(0));
    }

    public void testGetNetworkRecommendationProviderData_noPotentialPackages() throws Exception {
        setNetworkRecommendationPackageNames(/*no configured packages*/);
        assertNull(mNetworkScorerAppManager.getNetworkRecommendationProviderData());
    }

    public void testGetNetworkRecommendationProviderData_serviceMissing() throws Exception {
        setNetworkRecommendationPackageNames("package1");
        mockScoreNetworksGranted("package1");

        assertNull(mNetworkScorerAppManager.getNetworkRecommendationProviderData());
    }

    public void testGetNetworkRecommendationProviderData_scoreNetworksNotGranted()
            throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecommendationPackageNames(recoComponent.getPackageName());
        mockScoreNetworksDenied(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */);

        assertNull(mNetworkScorerAppManager.getNetworkRecommendationProviderData());
    }

    public void testGetNetworkRecommendationProviderData_available() throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecommendationPackageNames(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */);

        NetworkScorerAppData appData =
                mNetworkScorerAppManager.getNetworkRecommendationProviderData();
        assertNotNull(appData);
        assertEquals(recoComponent, appData.getRecommendationServiceComponent());
        assertEquals(924, appData.packageUid);
    }

    public void testGetActiveScorer_providerAvailable() throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecommendationPackageNames(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */);

        ContentResolver cr = mTargetContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 1);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(recoComponent, activeScorer.getRecommendationServiceComponent());
        assertEquals(924, activeScorer.packageUid);
    }

    public void testGetActiveScorer_providerNotAvailable()
            throws Exception {
        ContentResolver cr = mTargetContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 1);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    public void testGetActiveScorer_recommendationsDisabled() throws Exception {
        final ComponentName recoComponent = new ComponentName("package1", "class1");
        setNetworkRecommendationPackageNames(recoComponent.getPackageName());
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, 924 /* packageUid */);
        ContentResolver cr = mTargetContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    private void setNetworkRecommendationPackageNames(String... names) {
        if (names == null) {
            names = new String[0];
        }
        when(mResources.getStringArray(R.array.config_networkRecommendationPackageNames))
                .thenReturn(names);
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
        final ResolveInfo serviceInfo = new ResolveInfo();
        serviceInfo.serviceInfo = new ServiceInfo();
        serviceInfo.serviceInfo.name = compName.getClassName();
        serviceInfo.serviceInfo.packageName = compName.getPackageName();
        serviceInfo.serviceInfo.applicationInfo = new ApplicationInfo();
        serviceInfo.serviceInfo.applicationInfo.uid = packageUid;

        final int flags = 0;
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
    }
}
