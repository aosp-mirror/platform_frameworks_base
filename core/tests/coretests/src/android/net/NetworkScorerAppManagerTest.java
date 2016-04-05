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

import android.Manifest.permission;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.os.UserHandle;
import android.test.InstrumentationTestCase;

import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class NetworkScorerAppManagerTest extends InstrumentationTestCase {
    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPm;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Configuration needed to make mockito/dexcache work.
        System.setProperty("dexmaker.dexcache",
                getInstrumentation().getTargetContext().getCacheDir().getPath());
        ClassLoader newClassLoader = getInstrumentation().getClass().getClassLoader();
        Thread.currentThread().setContextClassLoader(newClassLoader);

        MockitoAnnotations.initMocks(this);
        Mockito.when(mMockContext.getPackageManager()).thenReturn(mMockPm);
    }

    public void testGetAllValidScorers() throws Exception {
        // Package 1 - Valid scorer.
        ResolveInfoHolder package1 = buildResolveInfo("package1", 1, true, true, false, false);

        // Package 2 - Receiver does not have BROADCAST_NETWORK_PRIVILEGED permission.
        ResolveInfoHolder package2 = buildResolveInfo("package2", 2, false, true, false, false);

        // Package 3 - App does not have SCORE_NETWORKS permission.
        ResolveInfoHolder package3 = buildResolveInfo("package3", 3, true, false, false, false);

        // Package 4 - Valid scorer w/ optional config activity.
        ResolveInfoHolder package4 = buildResolveInfo("package4", 4, true, true, true, false);

        // Package 5 - Valid scorer w/ optional service to bind to.
        ResolveInfoHolder package5 = buildResolveInfo("package5", 5, true, true, false, true);

        List<ResolveInfoHolder> scorers = new ArrayList<>();
        scorers.add(package1);
        scorers.add(package2);
        scorers.add(package3);
        scorers.add(package4);
        scorers.add(package5);
        setScorers(scorers);

        Iterator<NetworkScorerAppData> result =
                NetworkScorerAppManager.getAllValidScorers(mMockContext).iterator();

        assertTrue(result.hasNext());
        NetworkScorerAppData next = result.next();
        assertEquals("package1", next.mPackageName);
        assertEquals(1, next.mPackageUid);
        assertNull(next.mConfigurationActivityClassName);

        assertTrue(result.hasNext());
        next = result.next();
        assertEquals("package4", next.mPackageName);
        assertEquals(4, next.mPackageUid);
        assertEquals(".ConfigActivity", next.mConfigurationActivityClassName);

        assertTrue(result.hasNext());
        next = result.next();
        assertEquals("package5", next.mPackageName);
        assertEquals(5, next.mPackageUid);
        assertEquals(".ScoringService", next.mScoringServiceClassName);

        assertFalse(result.hasNext());
    }

    private void setScorers(List<ResolveInfoHolder> scorers) {
        List<ResolveInfo> receivers = new ArrayList<>();
        for (final ResolveInfoHolder scorer : scorers) {
            receivers.add(scorer.scorerResolveInfo);
            if (scorer.configActivityResolveInfo != null) {
                // This scorer has a config activity.
                Mockito.when(mMockPm.queryIntentActivities(
                        Mockito.argThat(new ArgumentMatcher<Intent>() {
                            @Override
                            public boolean matches(Object object) {
                                Intent intent = (Intent) object;
                                return NetworkScoreManager.ACTION_CUSTOM_ENABLE.equals(
                                        intent.getAction())
                                        && scorer.scorerResolveInfo.activityInfo.packageName.equals(
                                                intent.getPackage());
                            }
                        }), Mockito.eq(0))).thenReturn(
                                Collections.singletonList(scorer.configActivityResolveInfo));
            }

            if (scorer.serviceResolveInfo != null) {
                // This scorer has a service to bind to
                Mockito.when(mMockPm.resolveService(
                        Mockito.argThat(new ArgumentMatcher<Intent>() {
                            @Override
                            public boolean matches(Object object) {
                                Intent intent = (Intent) object;
                                return NetworkScoreManager.ACTION_SCORE_NETWORKS.equals(
                                        intent.getAction())
                                        && scorer.scorerResolveInfo.activityInfo.packageName.equals(
                                        intent.getPackage());
                            }
                        }), Mockito.eq(0))).thenReturn(scorer.serviceResolveInfo);
            }
        }

        Mockito.when(mMockPm.queryBroadcastReceiversAsUser(
                Mockito.argThat(new ArgumentMatcher<Intent>() {
                    @Override
                    public boolean matches(Object object) {
                        Intent intent = (Intent) object;
                        return NetworkScoreManager.ACTION_SCORE_NETWORKS.equals(intent.getAction());
                    }
                }), Mockito.eq(0), Mockito.eq(UserHandle.USER_SYSTEM)))
                .thenReturn(receivers);
    }

    private ResolveInfoHolder buildResolveInfo(String packageName, int packageUid,
            boolean hasReceiverPermission, boolean hasScorePermission, boolean hasConfigActivity,
            boolean hasServiceInfo) throws Exception {
        Mockito.when(mMockPm.checkPermission(permission.SCORE_NETWORKS, packageName))
                .thenReturn(hasScorePermission ?
                        PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = packageName;
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        resolveInfo.activityInfo.applicationInfo.uid = packageUid;
        if (hasReceiverPermission) {
            resolveInfo.activityInfo.permission = permission.BROADCAST_NETWORK_PRIVILEGED;
        }

        ResolveInfo configActivityInfo = null;
        if (hasConfigActivity) {
            configActivityInfo = new ResolveInfo();
            configActivityInfo.activityInfo = new ActivityInfo();
            configActivityInfo.activityInfo.name = ".ConfigActivity";
        }

        ResolveInfo serviceInfo = null;
        if (hasServiceInfo) {
            serviceInfo = new ResolveInfo();
            serviceInfo.serviceInfo = new ServiceInfo();
            serviceInfo.serviceInfo.name = ".ScoringService";
        }

        return new ResolveInfoHolder(resolveInfo, configActivityInfo, serviceInfo);
    }

    private static class ResolveInfoHolder {
        final ResolveInfo scorerResolveInfo;
        final ResolveInfo configActivityResolveInfo;
        final ResolveInfo serviceResolveInfo;

        public ResolveInfoHolder(ResolveInfo scorerResolveInfo,
                ResolveInfo configActivityResolveInfo, ResolveInfo serviceResolveInfo) {
            this.scorerResolveInfo = scorerResolveInfo;
            this.configActivityResolveInfo = configActivityResolveInfo;
            this.serviceResolveInfo = serviceResolveInfo;
        }
    }
}
