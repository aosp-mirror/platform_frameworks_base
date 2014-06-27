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
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.test.InstrumentationTestCase;
import android.util.Pair;

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
        Pair<ResolveInfo, ResolveInfo> package1 = buildResolveInfo("package1", true, true, false);

        // Package 2 - Receiver does not have BROADCAST_SCORE_NETWORKS permission.
        Pair<ResolveInfo, ResolveInfo> package2 = buildResolveInfo("package2", false, true, false);

        // Package 3 - App does not have SCORE_NETWORKS permission.
        Pair<ResolveInfo, ResolveInfo> package3 = buildResolveInfo("package3", true, false, false);

        // Package 4 - Valid scorer w/ optional config activity.
        Pair<ResolveInfo, ResolveInfo> package4 = buildResolveInfo("package4", true, true, true);

        List<Pair<ResolveInfo, ResolveInfo>> scorers = new ArrayList<>();
        scorers.add(package1);
        scorers.add(package2);
        scorers.add(package3);
        scorers.add(package4);
        setScorers(scorers);

        Iterator<NetworkScorerAppData> result =
                NetworkScorerAppManager.getAllValidScorers(mMockContext).iterator();

        assertTrue(result.hasNext());
        NetworkScorerAppData next = result.next();
        assertEquals("package1", next.mPackageName);
        assertNull(next.mConfigurationActivityClassName);

        assertTrue(result.hasNext());
        next = result.next();
        assertEquals("package4", next.mPackageName);
        assertEquals(".ConfigActivity", next.mConfigurationActivityClassName);

        assertFalse(result.hasNext());
    }

    private void setScorers(List<Pair<ResolveInfo, ResolveInfo>> scorers) {
        List<ResolveInfo> receivers = new ArrayList<>();
        for (final Pair<ResolveInfo, ResolveInfo> scorer : scorers) {
            receivers.add(scorer.first);
            if (scorer.second != null) {
                // This scorer has a config activity.
                Mockito.when(mMockPm.queryIntentActivities(
                        Mockito.argThat(new ArgumentMatcher<Intent>() {
                            @Override
                            public boolean matches(Object object) {
                                Intent intent = (Intent) object;
                                return NetworkScoreManager.ACTION_CUSTOM_ENABLE.equals(
                                        intent.getAction())
                                        && scorer.first.activityInfo.packageName.equals(
                                                intent.getPackage());
                            }
                        }), Mockito.eq(0))).thenReturn(Collections.singletonList(scorer.second));
            }
        }

        Mockito.when(mMockPm.queryBroadcastReceivers(
                Mockito.argThat(new ArgumentMatcher<Intent>() {
                    @Override
                    public boolean matches(Object object) {
                        Intent intent = (Intent) object;
                        return NetworkScoreManager.ACTION_SCORE_NETWORKS.equals(intent.getAction());
                    }
                }), Mockito.eq(0)))
                .thenReturn(receivers);
    }

    private Pair<ResolveInfo, ResolveInfo> buildResolveInfo(String packageName,
            boolean hasReceiverPermission, boolean hasScorePermission, boolean hasConfigActivity)
            throws Exception {
        Mockito.when(mMockPm.checkPermission(permission.SCORE_NETWORKS, packageName))
                .thenReturn(hasScorePermission ?
                        PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = packageName;
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        if (hasReceiverPermission) {
            resolveInfo.activityInfo.permission = permission.BROADCAST_SCORE_NETWORKS;
        }

        ResolveInfo configActivityInfo = null;
        if (hasConfigActivity) {
            configActivityInfo = new ResolveInfo();
            configActivityInfo.activityInfo = new ActivityInfo();
            configActivityInfo.activityInfo.name = ".ConfigActivity";
        }
        return Pair.create(resolveInfo, configActivityInfo);
    }
}
