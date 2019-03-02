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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
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

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NetworkScorerAppManagerTest {
    private static final int PACKAGE_UID = 924;
    private static String MOCK_SERVICE_LABEL = "Mock Service";
    private static String MOCK_OVERRIDEN_SERVICE_LABEL = "Mock Service Label Override";
    private static String MOCK_NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID =
            "Mock Network Available Notification Channel Id";
    private static final ComponentName RECO_COMPONENT = new ComponentName("package1", "class1");

    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPm;
    @Mock private Resources mResources;
    @Mock private NetworkScorerAppManager.SettingsFacade mSettingsFacade;
    @Mock private AppOpsManager mAppOpsManager;
    private NetworkScorerAppManager mNetworkScorerAppManager;
    private List<ResolveInfo> mAvailableServices;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mAvailableServices = new ArrayList<>();
        when(mMockContext.getPackageManager()).thenReturn(mMockPm);
        when(mMockPm.queryIntentServices(Mockito.argThat(
                intent -> NetworkScoreManager.ACTION_RECOMMEND_NETWORKS.equals(intent.getAction())),
                eq(PackageManager.GET_META_DATA))).thenReturn(mAvailableServices);
        when(mMockContext.getResources()).thenReturn(mResources);
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);

        mockLocationModeOn();
        mockLocationPermissionGranted(PACKAGE_UID, RECO_COMPONENT.getPackageName());

        mNetworkScorerAppManager = new NetworkScorerAppManager(mMockContext, mSettingsFacade);
    }

    @Test
    public void testGetActiveScorer_providerAvailable() throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(RECO_COMPONENT, activeScorer.getRecommendationServiceComponent());
        assertEquals(PACKAGE_UID, activeScorer.packageUid);
        assertEquals(MOCK_SERVICE_LABEL, activeScorer.getRecommendationServiceLabel());
    }

    @Test
    public void testGetActiveScorer_providerAvailable_serviceLabelOverride() throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                null /* enableUseOpenWifiPackageActivityPackage*/, true /* serviceLabelOverride */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(RECO_COMPONENT, activeScorer.getRecommendationServiceComponent());
        assertEquals(PACKAGE_UID, activeScorer.packageUid);
        assertEquals(MOCK_OVERRIDEN_SERVICE_LABEL, activeScorer.getRecommendationServiceLabel());
    }

    @Test
    public void testGetActiveScorer_scoreNetworksPermissionMissing() throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksDenied(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    @Test
    public void testGetActiveScorer_locationPermissionMissing() throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockLocationPermissionDenied(PACKAGE_UID, RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    @Test
    public void testGetActiveScorer_locationModeOff() throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockLocationPermissionGranted(PACKAGE_UID, RECO_COMPONENT.getPackageName());
        mockLocationModeOff();
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNull(activeScorer);
    }

    @Test
    public void testGetActiveScorer_providerAvailable_enableUseOpenWifiActivityNotSet()
            throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                null /* enableUseOpenWifiPackageActivityPackage*/);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(RECO_COMPONENT, activeScorer.getRecommendationServiceComponent());
        assertEquals(PACKAGE_UID, activeScorer.packageUid);
        assertNull(activeScorer.getEnableUseOpenWifiActivity());
    }

    @Test
    public void testGetActiveScorer_providerAvailable_enableUseOpenWifiActivityNotResolved()
            throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                "package2" /* enableUseOpenWifiPackageActivityPackage*/);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(RECO_COMPONENT, activeScorer.getRecommendationServiceComponent());
        assertEquals(PACKAGE_UID, activeScorer.packageUid);
        assertNull(activeScorer.getEnableUseOpenWifiActivity());
    }

    @Test
    public void testGetActiveScorer_providerAvailable_enableUseOpenWifiActivityResolved()
            throws Exception {
        final ComponentName enableUseOpenWifiComponent = new ComponentName("package2", "class2");
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                enableUseOpenWifiComponent.getPackageName());
        mockEnableUseOpenWifiActivity(enableUseOpenWifiComponent);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(RECO_COMPONENT, activeScorer.getRecommendationServiceComponent());
        assertEquals(PACKAGE_UID, activeScorer.packageUid);
        assertEquals(enableUseOpenWifiComponent, activeScorer.getEnableUseOpenWifiActivity());
        assertNull(activeScorer.getNetworkAvailableNotificationChannelId());
    }

    @Test
    public void testGetActiveScorer_providerAvailable_networkAvailableNotificationChannelIdSet() {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                null /* enableUseOpenWifiActivityPackage */, false /* serviceLabelOverride */,
                true /* setNotificationChannelId */);

        final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
        assertNotNull(activeScorer);
        assertEquals(RECO_COMPONENT, activeScorer.getRecommendationServiceComponent());
        assertEquals(PACKAGE_UID, activeScorer.packageUid);
        assertEquals(MOCK_NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID,
                activeScorer.getNetworkAvailableNotificationChannelId());
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
        setDefaultNetworkRecommendationPackage(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
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
    public void testSetActiveScorer_nullPackage_currentIsSet() throws Exception {
        setNetworkRecoPackageSetting("package");

        assertTrue(mNetworkScorerAppManager.setActiveScorer(null));
        verify(mSettingsFacade).putString(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE, null);
        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_FORCED_OFF);
    }

    @Test
    public void testSetActiveScorer_nullPackage_currentIsNull() throws Exception {
        setNetworkRecoPackageSetting(null);

        assertTrue(mNetworkScorerAppManager.setActiveScorer(null));
        verify(mSettingsFacade, never()).putString(any(), any(), any());
    }

    @Test
    public void testSetActiveScorer_validPackage() throws Exception {
        String newPackage = "newPackage";
        int newAppUid = 621;
        final ComponentName recoComponent = new ComponentName(newPackage, "class1");
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, newAppUid, null);
        mockLocationPermissionGranted(newAppUid, recoComponent.getPackageName());

        assertTrue(mNetworkScorerAppManager.setActiveScorer(newPackage));
        verify(mSettingsFacade).putString(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE, newPackage);
        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_ON);
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
    public void testUpdateState_recommendationsForcedOff() throws Exception {
        setRecommendationsEnabledSetting(NetworkScoreManager.RECOMMENDATIONS_ENABLED_FORCED_OFF);

        mNetworkScorerAppManager.updateState();

        verify(mSettingsFacade, never()).getString(any(),
                eq(Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE));
        verify(mSettingsFacade, never()).putInt(any(),
                eq(Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED), anyInt());
    }

    @Test
    public void testUpdateState_currentPackageValid() throws Exception {
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID , null);

        mNetworkScorerAppManager.updateState();

        verify(mSettingsFacade, never()).putString(any(),
                eq(Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE), any());
        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_ON);
    }

    @Test
    public void testUpdateState_currentPackageNotValid_validDefault() throws Exception {
        final String defaultPackage = "defaultPackage";
        final int defaultAppUid = 621;
        final ComponentName recoComponent = new ComponentName(defaultPackage, "class1");
        setDefaultNetworkRecommendationPackage(defaultPackage);
        mockScoreNetworksGranted(recoComponent.getPackageName());
        mockRecommendationServiceAvailable(recoComponent, defaultAppUid, null);
        mockLocationPermissionGranted(defaultAppUid, defaultPackage);

        mNetworkScorerAppManager.updateState();

        verify(mSettingsFacade).putString(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE, defaultPackage);
        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_ON);
    }

    @Test
    public void testUpdateState_currentPackageNotValid_invalidDefault() throws Exception {
        String defaultPackage = "defaultPackage";
        setDefaultNetworkRecommendationPackage(defaultPackage);
        setNetworkRecoPackageSetting("currentPackage");

        mNetworkScorerAppManager.updateState();

        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_OFF);
    }

    @Test
    public void testUpdateState_currentPackageNull_defaultNull() throws Exception {
        setDefaultNetworkRecommendationPackage(null);
        setNetworkRecoPackageSetting(null);

        mNetworkScorerAppManager.updateState();

        verify(mSettingsFacade, never()).putString(any(),
                eq(Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE), anyString());
        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_OFF);
    }

    @Test
    public void testUpdateState_currentPackageEmpty_defaultEmpty() throws Exception {
        setDefaultNetworkRecommendationPackage("");
        setNetworkRecoPackageSetting("");

        mNetworkScorerAppManager.updateState();

        verify(mSettingsFacade, never()).putString(any(),
                eq(Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE), anyString());
        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_OFF);
    }

    @Test
    public void testUpdateState_currentPackageNotValid_sameAsDefault() throws Exception {
        String defaultPackage = "defaultPackage";
        setDefaultNetworkRecommendationPackage(defaultPackage);
        setNetworkRecoPackageSetting(defaultPackage);

        mNetworkScorerAppManager.updateState();

        verify(mSettingsFacade).putInt(mMockContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                NetworkScoreManager.RECOMMENDATIONS_ENABLED_OFF);
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_networkScorerAppIsNull()
            throws Exception {
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP)).thenReturn(null);

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade, never()).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE), anyString());
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_networkScorerAppIsEmpty()
            throws Exception {
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP)).thenReturn("");

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade, never()).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE), anyString());
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_networkScorerIsNotActive()
            throws Exception {
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP)).thenReturn("com.foo.package");
        // Make getActiveScorer() return null.
        setRecommendationsEnabledSetting(NetworkScoreManager.RECOMMENDATIONS_ENABLED_FORCED_OFF);

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade, never()).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE), anyString());
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_useOpenWifiSettingIsNotEmpty()
            throws Exception {
        final ComponentName enableUseOpenWifiComponent = new ComponentName("package2", "class2");
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                enableUseOpenWifiComponent.getPackageName());
        mockEnableUseOpenWifiActivity(enableUseOpenWifiComponent);
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP))
                .thenReturn(enableUseOpenWifiComponent.getPackageName());
        // The setting has a value so the migration shouldn't touch it.
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.USE_OPEN_WIFI_PACKAGE))
                .thenReturn(enableUseOpenWifiComponent.getPackageName());

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade, never()).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE), anyString());
        verify(mSettingsFacade).putString(eq(mMockContext),
                eq(Settings.Global.NETWORK_SCORER_APP), eq(null));
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_useOpenWifiActivityNotAvail()
            throws Exception {
        final ComponentName enableUseOpenWifiComponent = new ComponentName("package2", "class2");
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        // The active component doesn't have an open wifi activity so the migration shouldn't
        // set USE_OPEN_WIFI_PACKAGE.
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                null /*useOpenWifiActivityPackage*/);
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP))
                .thenReturn(enableUseOpenWifiComponent.getPackageName());
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.USE_OPEN_WIFI_PACKAGE)).thenReturn(null);

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade, never()).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE), anyString());
        verify(mSettingsFacade).putString(eq(mMockContext),
                eq(Settings.Global.NETWORK_SCORER_APP), eq(null));
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_packageMismatch_activity()
            throws Exception {
        final ComponentName enableUseOpenWifiComponent = new ComponentName("package2", "class2");
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                enableUseOpenWifiComponent.getPackageName());
        mockEnableUseOpenWifiActivity(enableUseOpenWifiComponent);
        // The older network scorer app setting doesn't match the new use open wifi activity package
        // so the migration shouldn't set USE_OPEN_WIFI_PACKAGE.
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP))
                .thenReturn(enableUseOpenWifiComponent.getPackageName() + ".diff");
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.USE_OPEN_WIFI_PACKAGE)).thenReturn(null);

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade, never()).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE), anyString());
        verify(mSettingsFacade).putString(eq(mMockContext),
                eq(Settings.Global.NETWORK_SCORER_APP), eq(null));
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_packageMismatch_service()
            throws Exception {
        final ComponentName enableUseOpenWifiComponent = new ComponentName("package2", "class2");
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                enableUseOpenWifiComponent.getPackageName());
        mockEnableUseOpenWifiActivity(enableUseOpenWifiComponent);
        // The older network scorer app setting doesn't match the active package so the migration
        // shouldn't set USE_OPEN_WIFI_PACKAGE.
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP))
                .thenReturn(RECO_COMPONENT.getPackageName() + ".diff");
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.USE_OPEN_WIFI_PACKAGE)).thenReturn(null);

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade, never()).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE), anyString());
        verify(mSettingsFacade).putString(eq(mMockContext),
                eq(Settings.Global.NETWORK_SCORER_APP), eq(null));
    }

    @Test
    public void testMigrateNetworkScorerAppSettingIfNeeded_packageMatch_activity()
            throws Exception {
        final ComponentName enableUseOpenWifiComponent = new ComponentName("package2", "class2");
        setNetworkRecoPackageSetting(RECO_COMPONENT.getPackageName());
        mockScoreNetworksGranted(RECO_COMPONENT.getPackageName());
        mockRecommendationServiceAvailable(RECO_COMPONENT, PACKAGE_UID /* packageUid */,
                enableUseOpenWifiComponent.getPackageName());
        mockEnableUseOpenWifiActivity(enableUseOpenWifiComponent);
        // Old setting matches the new activity package, migration should happen.
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.NETWORK_SCORER_APP))
                .thenReturn(enableUseOpenWifiComponent.getPackageName());
        when(mSettingsFacade.getString(mMockContext,
                Settings.Global.USE_OPEN_WIFI_PACKAGE)).thenReturn(null);

        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();

        verify(mSettingsFacade).putString(eq(mMockContext),
                eq(Settings.Global.USE_OPEN_WIFI_PACKAGE),
                eq(enableUseOpenWifiComponent.getPackageName()));
        verify(mSettingsFacade).putString(eq(mMockContext),
                eq(Settings.Global.NETWORK_SCORER_APP), eq(null));
    }

    private void setRecommendationsEnabledSetting(int value) {
        when(mSettingsFacade.getInt(eq(mMockContext),
                eq(Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED), anyInt())).thenReturn(value);
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

    private void mockLocationModeOn() {
        mockLocationModeValue(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY);
    }

    private void mockLocationModeOff() {
        mockLocationModeValue(Settings.Secure.LOCATION_MODE_OFF);
    }

    private void mockLocationModeValue(int returnVal) {
        when(mSettingsFacade.getSecureInt(eq(mMockContext),
                eq(Settings.Secure.LOCATION_MODE), anyInt())).thenReturn(returnVal);
    }

    private void mockLocationPermissionGranted(int uid, String packageName) {
        when(mMockPm.checkPermission(permission.ACCESS_COARSE_LOCATION, packageName))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mAppOpsManager.noteOp(AppOpsManager.OP_COARSE_LOCATION, uid, packageName))
                .thenReturn(AppOpsManager.MODE_ALLOWED);
    }

    private void mockLocationPermissionDenied(int uid, String packageName) {
        when(mMockPm.checkPermission(permission.ACCESS_COARSE_LOCATION, packageName))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mAppOpsManager.noteOp(AppOpsManager.OP_COARSE_LOCATION, uid, packageName))
                .thenReturn(AppOpsManager.MODE_IGNORED);
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
        mockRecommendationServiceAvailable(compName, packageUid, enableUseOpenWifiActivityPackage,
                serviceLabelOverride, false);
    }

    private void mockRecommendationServiceAvailable(final ComponentName compName, int packageUid,
            String enableUseOpenWifiActivityPackage, boolean serviceLabelOverride,
            boolean setNotificationChannel) {
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
        if (setNotificationChannel) {
            if (serviceInfo.serviceInfo.metaData == null) {
                serviceInfo.serviceInfo.metaData = new Bundle();
            }
            serviceInfo.serviceInfo.metaData.putString(
                    NetworkScoreManager.NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID_META_DATA,
                    MOCK_NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID);
        }

        final int flags = PackageManager.GET_META_DATA;
        when(mMockPm.resolveService(
                Mockito.argThat(intent -> NetworkScoreManager.ACTION_RECOMMEND_NETWORKS
                        .equals(intent.getAction())
                        && compName.getPackageName().equals(intent.getPackage())),
                Mockito.eq(flags))).thenReturn(serviceInfo);

        mAvailableServices.add(serviceInfo);
    }

    private void mockEnableUseOpenWifiActivity(final ComponentName useOpenWifiComp) {
        final ResolveInfo resolveActivityInfo = new ResolveInfo();
        resolveActivityInfo.activityInfo = new ActivityInfo();
        resolveActivityInfo.activityInfo.name = useOpenWifiComp.getClassName();
        resolveActivityInfo.activityInfo.packageName = useOpenWifiComp.getPackageName();

        final int flags = 0;
        when(mMockPm.resolveActivity(
                Mockito.argThat(intent ->
                        NetworkScoreManager.ACTION_CUSTOM_ENABLE.equals(intent.getAction())
                                && useOpenWifiComp.getPackageName().equals(intent.getPackage())),
                Mockito.eq(flags))).thenReturn(resolveActivityInfo);
    }
}
