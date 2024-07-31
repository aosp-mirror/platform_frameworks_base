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
 * limitations under the License.
 */

package com.android.internal.app;

import static junit.framework.Assert.assertEquals;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ResolverListController Test.
 */
@RunWith(AndroidJUnit4.class)
public class ResolverListControllerTest {

    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPackageManager;
    @Mock private Resources mMockResources;
    @Mock private IUsageStatsManager mMockService;

    private ResolverListController mController;
    private UsageStatsManager mUsm;
    private static final UserHandle PERSONAL_USER_HANDLE = InstrumentationRegistry
            .getInstrumentation().getTargetContext().getUser();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        Configuration config = new Configuration();
        config.locale = Locale.getDefault();
        List<ResolveInfo> services = new ArrayList<>();
        mUsm = new UsageStatsManager(mMockContext, mMockService);
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);
        when(mMockContext.getSystemService(Context.USAGE_STATS_SERVICE)).thenReturn(mUsm);
        when(mMockPackageManager.queryIntentServices(any(), anyInt())).thenReturn(services);
        when(mMockResources.getConfiguration()).thenReturn(config);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getPackageName()).thenReturn("android");
        when(mMockContext.getUserId()).thenReturn(54321);
        when(mMockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(null);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
    }

    @Test
    public void reportChooserSelection() throws InterruptedException, RemoteException {
        String annotation = "test_annotation";
        Intent sendIntent = createSendImageIntent(annotation);
        String refererPackage = "test_referer_package";
        String packageName = "test_package";
        String action = "test_action";
        final int initialCount = 1234;
        UsageStats packageStats = initStats(packageName, action, annotation, initialCount);
        UsageStats oneClickStats = initStats(packageName, action, annotation, 1);
        final List<UsageStats> slices = new ArrayList<>();
        slices.add(packageStats);
        ParceledListSlice<UsageStats> stats = new ParceledListSlice<>(slices);
        when(mMockService.queryUsageStats(anyInt(), anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(stats);
        Answer<Void> answer = new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                slices.add(oneClickStats);
                return null;
            }
        };
        doAnswer(answer).when(mMockService).reportChooserSelection(
                anyString(), anyInt(), anyString(), any(), anyString());
        when(mMockContext.getOpPackageName()).thenReturn(refererPackage);
        mController = new ResolverListController(mMockContext, mMockPackageManager, sendIntent,
                refererPackage, UserHandle.USER_CURRENT, /* userHandle */ UserHandle.SYSTEM,
                UserHandle.SYSTEM);
        mController.sort(new ArrayList<ResolvedComponentInfo>());
        long beforeReport = getCount(mUsm, packageName, action, annotation);
        mController.updateChooserCounts(packageName, UserHandle.SYSTEM, action);
        long afterReport = getCount(mUsm, packageName, action, annotation);
        assertThat(afterReport, is(beforeReport + 1l));
    }

    @Test
    public void topKEqualsToSort() {
        String annotation = "test_annotation";
        Intent sendIntent = createSendImageIntent(annotation);
        String refererPackage = "test_referer_package";
        List<ResolvedComponentInfo> resolvedComponents = createResolvedComponentsForTest(10);
        mController = new ResolverListController(mMockContext, mMockPackageManager, sendIntent,
                refererPackage, UserHandle.USER_CURRENT, /* userHandle */ UserHandle.SYSTEM,
                UserHandle.SYSTEM);
        List<ResolvedComponentInfo> topKList = new ArrayList<>(resolvedComponents);
        mController.topK(topKList, 5);
        List<ResolvedComponentInfo> sortList = new ArrayList<>(topKList);
        mController.sort(sortList);
        assertEquals("Top k elements should be sorted when input size greater than k.",
                sortList.subList(0, 5), topKList.subList(0, 5));
        mController.topK(topKList, 10);
        sortList = new ArrayList<>(topKList);
        mController.sort(sortList);
        assertEquals("All elements should be sorted when input size equals k.",
                sortList, topKList);
        mController.topK(topKList, 15);
        sortList = new ArrayList<>(topKList);
        mController.sort(sortList);
        assertEquals("All elements should be sorted when input size less than k.",
                sortList, topKList);
    }

    @Test
    public void getResolversForIntent_usesResultsFromPackageManager() {
        mockStats();
        List<ResolveInfo> infos = new ArrayList<>();
        infos.add(ResolverDataProvider.createResolveInfo(0, UserHandle.USER_CURRENT,
                PERSONAL_USER_HANDLE));
        when(mMockPackageManager.queryIntentActivitiesAsUser(any(), anyInt(),
                any(UserHandle.class))).thenReturn(infos);
        mController = new ResolverListController(mMockContext, mMockPackageManager,
                createSendImageIntent("test"), null, UserHandle.USER_CURRENT,
                /* userHandle= */ UserHandle.SYSTEM, UserHandle.SYSTEM);
        List<Intent> intents = new ArrayList<>();
        intents.add(createActionMainIntent());

        List<ResolvedComponentInfo> resolvers = mController
                .getResolversForIntent(
                        /* shouldGetResolvedFilter= */ true,
                        /* shouldGetActivityMetadata= */ true,
                        /* shouldGetOnlyDefaultActivities= */ true,
                        intents);

        assertThat(resolvers, hasSize(1));
        assertThat(resolvers.get(0).getResolveInfoAt(0), is(infos.get(0)));
    }

    @Test
    public void getResolversForIntent_shouldGetOnlyDefaultActivitiesTrue_addsFlag() {
        mockStats();
        List<ResolveInfo> infos = new ArrayList<>();
        infos.add(ResolverDataProvider.createResolveInfo(0, UserHandle.USER_CURRENT,
                PERSONAL_USER_HANDLE));
        when(mMockPackageManager.queryIntentActivitiesAsUser(any(), anyInt(),
                any(UserHandle.class))).thenReturn(infos);
        mController = new ResolverListController(mMockContext, mMockPackageManager,
                createSendImageIntent("test"), null, UserHandle.USER_CURRENT,
                /* userHandle= */ UserHandle.SYSTEM, UserHandle.SYSTEM);
        List<Intent> intents = new ArrayList<>();
        intents.add(createActionMainIntent());

        mController
                .getResolversForIntent(
                        /* shouldGetResolvedFilter= */ true,
                        /* shouldGetActivityMetadata= */ true,
                        /* shouldGetOnlyDefaultActivities= */ true,
                        intents);

        verify(mMockPackageManager).queryIntentActivitiesAsUser(any(),
                containsFlag(PackageManager.MATCH_DEFAULT_ONLY), any());
    }

    @Test
    public void getResolversForIntent_shouldGetOnlyDefaultActivitiesFalse_doesNotAddFlag() {
        mockStats();
        List<ResolveInfo> infos = new ArrayList<>();
        infos.add(ResolverDataProvider.createResolveInfo(0, UserHandle.USER_CURRENT,
                PERSONAL_USER_HANDLE));
        when(mMockPackageManager.queryIntentActivitiesAsUser(any(), anyInt(),
                any(UserHandle.class))).thenReturn(infos);
        mController = new ResolverListController(mMockContext, mMockPackageManager,
                createSendImageIntent("test"), null, UserHandle.USER_CURRENT,
                /* userHandle= */ UserHandle.SYSTEM, UserHandle.SYSTEM);
        List<Intent> intents = new ArrayList<>();
        intents.add(createActionMainIntent());

        mController
                .getResolversForIntent(
                        /* shouldGetResolvedFilter= */ true,
                        /* shouldGetActivityMetadata= */ true,
                        /* shouldGetOnlyDefaultActivities= */ false,
                        intents);

        verify(mMockPackageManager).queryIntentActivitiesAsUser(any(),
                doesNotContainFlag(PackageManager.MATCH_DEFAULT_ONLY), any());
    }


    @Test
    public void testResolveInfoWithNoUserHandle_isNotAddedToResults()
            throws Exception {
        List<ResolveInfo> infos = new ArrayList<>();
        infos.add(ResolverDataProvider.createResolveInfo(0, UserHandle.USER_CURRENT,
                PERSONAL_USER_HANDLE));
        infos.add(ResolverDataProvider.createResolveInfo(0, UserHandle.USER_CURRENT, null));
        when(mMockPackageManager.queryIntentActivitiesAsUser(any(), anyInt(),
                any(UserHandle.class))).thenReturn(infos);
        mController = new ResolverListController(mMockContext, mMockPackageManager,
                createSendImageIntent("test"), null, UserHandle.USER_CURRENT,
                /* userHandle= */ UserHandle.SYSTEM, UserHandle.SYSTEM);
        List<Intent> intents = new ArrayList<>();
        intents.add(createActionMainIntent());

        List<ResolverActivity.ResolvedComponentInfo> result = mController
                .getResolversForIntent(
                        /* shouldGetResolvedFilter= */ true,
                        /* shouldGetActivityMetadata= */ true,
                        /* shouldGetOnlyDefaultActivities= */ false,
                        intents);

        assertThat(result.size(), is(1));
    }

    private int containsFlag(int flag) {
        return intThat(new FlagMatcher(flag, /* contains= */ true));
    }

    private int doesNotContainFlag(int flag) {
        return intThat(new FlagMatcher(flag, /* contains= */ false));
    }

    public static class FlagMatcher implements ArgumentMatcher<Integer> {

        private final int mFlag;
        private final boolean mContains;

        public FlagMatcher(int flag, boolean contains) {
            mFlag = flag;
            mContains = contains;
        }

        @Override
        public boolean matches(Integer integer) {
            return ((integer & mFlag) != 0) == mContains;
        }
    }

    private UsageStats initStats(String packageName, String action,
                                 String annotation, int count) {
        ArrayMap<String, ArrayMap<String, Integer>> chooserCounts = new ArrayMap<>();
        ArrayMap<String, Integer> counts = new ArrayMap<>();
        counts.put(annotation, count);
        chooserCounts.put(action, counts);
        UsageStats packageStats = new UsageStats();
        packageStats.mPackageName = packageName;
        packageStats.mChooserCounts = chooserCounts;
        return packageStats;
    }

    private Intent createSendImageIntent(String annotation) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        sendIntent.setType("image/jpeg");
        ArrayList<String> annotations = new ArrayList<>();
        annotations.add(annotation);
        sendIntent.putStringArrayListExtra(Intent.EXTRA_CONTENT_ANNOTATIONS, annotations);
        return sendIntent;
    }

    private Intent createActionMainIntent() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_MAIN);
        sendIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        return sendIntent;
    }

    private void mockStats() {
        final List<UsageStats> slices = new ArrayList<>();
        ParceledListSlice<UsageStats> stats = new ParceledListSlice<>(slices);
        try {
            when(mMockService.queryUsageStats(anyInt(), anyLong(), anyLong(), anyString(),
                    anyInt())).thenReturn(stats);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private Integer getCount(
            UsageStatsManager usm, String packageName, String action, String annotation) {
        if (usm == null) {
            return 0;
        }
        Map<String, UsageStats> stats =
                usm.queryAndAggregateUsageStats(Long.MIN_VALUE, Long.MAX_VALUE);
        UsageStats packageStats = stats.get(packageName);
        if (packageStats == null || packageStats.mChooserCounts == null
                || packageStats.mChooserCounts.get(action) == null) {
            return 0;
        }
        return packageStats.mChooserCounts.get(action).getOrDefault(annotation, 0);
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTest(int numberOfResults) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfo(i, PERSONAL_USER_HANDLE));
        }
        return infoList;
    }
}
