/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.prediction;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionManager;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.Range;

import com.android.internal.app.ChooserActivity;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.server.people.data.ConversationInfo;
import com.android.server.people.data.DataManager;
import com.android.server.people.data.EventHistory;
import com.android.server.people.data.EventIndex;
import com.android.server.people.data.PackageData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@RunWith(JUnit4.class)
public final class ShareTargetPredictorTest {

    private static final String UI_SURFACE_SHARE = "share";
    private static final int NUM_PREDICTED_TARGETS = 5;
    private static final int USER_ID = 0;
    private static final String PACKAGE_1 = "pkg1";
    private static final String PACKAGE_2 = "pkg2";
    private static final String PACKAGE_3 = "pkg3";
    private static final String CLASS_1 = "cls1";
    private static final String CLASS_2 = "cls2";
    private static final AppTargetEvent APP_TARGET_EVENT =
            new AppTargetEvent.Builder(
                    new AppTarget.Builder(
                        new AppTargetId("cls1#pkg1"), PACKAGE_1, UserHandle.of(USER_ID)).build(),
                        AppTargetEvent.ACTION_LAUNCH)
                    .setLaunchLocation(ChooserActivity.LAUNCH_LOCATION_DIRECT_SHARE)
                    .build();
    private static final IntentFilter INTENT_FILTER = IntentFilter.create("SEND", "text/plain");

    @Mock private Context mContext;
    @Mock private DataManager mDataManager;
    @Mock private Consumer<List<AppTarget>> mUpdatePredictionsMethod;
    @Mock private PackageData mPackageData1;
    @Mock private PackageData mPackageData2;
    @Mock private EventHistory mEventHistory1;
    @Mock private EventHistory mEventHistory2;
    @Mock private EventHistory mEventHistory3;
    @Mock private EventHistory mEventHistory4;
    @Mock private EventHistory mEventHistory5;
    @Mock private EventHistory mEventHistory6;

    @Mock private EventIndex mEventIndex1;
    @Mock private EventIndex mEventIndex2;
    @Mock private EventIndex mEventIndex3;
    @Mock private EventIndex mEventIndex4;
    @Mock private EventIndex mEventIndex5;
    @Mock private EventIndex mEventIndex6;
    @Captor private ArgumentCaptor<List<AppTarget>> mAppTargetCaptor;

    private List<ShareShortcutInfo> mShareShortcuts = new ArrayList<>();
    private ShareTargetPredictor mPredictor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mDataManager.getShareShortcuts(any(), anyInt())).thenReturn(mShareShortcuts);
        when(mDataManager.getPackage(PACKAGE_1, USER_ID)).thenReturn(mPackageData1);
        when(mDataManager.getPackage(PACKAGE_2, USER_ID)).thenReturn(mPackageData2);
        when(mContext.createContextAsUser(any(), any())).thenReturn(mContext);
        when(mContext.getSystemServiceName(AppPredictionManager.class)).thenReturn(
                Context.APP_PREDICTION_SERVICE);
        when(mContext.getSystemService(AppPredictionManager.class))
                .thenReturn(new AppPredictionManager(mContext));

        Bundle bundle = new Bundle();
        bundle.putObject(ChooserActivity.APP_PREDICTION_INTENT_FILTER_KEY, INTENT_FILTER);
        AppPredictionContext predictionContext = new AppPredictionContext.Builder(mContext)
                .setUiSurface(UI_SURFACE_SHARE)
                .setPredictedTargetCount(NUM_PREDICTED_TARGETS)
                .setExtras(bundle)
                .build();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DARK_LAUNCH_REMOTE_PREDICTION_SERVICE_ENABLED,
                Boolean.toString(true),
                true /* makeDefault*/);
        mPredictor = new ShareTargetPredictor(
                predictionContext, mUpdatePredictionsMethod, mDataManager, USER_ID, mContext);
    }

    @Test
    public void testReportAppTargetEvent() {
        mPredictor.reportAppTargetEvent(APP_TARGET_EVENT);

        verify(mDataManager, times(1))
                .reportShareTargetEvent(eq(APP_TARGET_EVENT), eq(INTENT_FILTER));
    }

    @Test
    public void testPredictTargets() {
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc1", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc2", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc3", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc4", 0));

        when(mPackageData1.getConversationInfo("sc1")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData1.getConversationInfo("sc2")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData2.getConversationInfo("sc3")).thenReturn(mock(ConversationInfo.class));
        // "sc4" does not have a ConversationInfo.

        when(mPackageData1.getEventHistory("sc1")).thenReturn(mEventHistory1);
        when(mPackageData1.getEventHistory("sc2")).thenReturn(mEventHistory2);
        when(mPackageData2.getEventHistory("sc3")).thenReturn(mEventHistory3);
        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory1.getEventIndex(anyInt())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anyInt())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anyInt())).thenReturn(mEventIndex3);
        when(mEventIndex1.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(1L, 2L));
        when(mEventIndex2.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(2L, 3L));
        when(mEventIndex3.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(3L, 4L));

        mPredictor.predictTargets();

        verify(mUpdatePredictionsMethod).accept(mAppTargetCaptor.capture());
        List<AppTarget> res = mAppTargetCaptor.getValue();
        assertEquals(4, res.size());

        assertEquals("sc3", res.get(0).getId().getId());
        assertEquals(CLASS_2, res.get(0).getClassName());
        assertEquals(PACKAGE_2, res.get(0).getPackageName());

        assertEquals("sc2", res.get(1).getId().getId());
        assertEquals(CLASS_1, res.get(1).getClassName());
        assertEquals(PACKAGE_1, res.get(1).getPackageName());

        assertEquals("sc1", res.get(2).getId().getId());
        assertEquals(CLASS_1, res.get(2).getClassName());
        assertEquals(PACKAGE_1, res.get(2).getPackageName());

        assertEquals("sc4", res.get(3).getId().getId());
        assertEquals(CLASS_2, res.get(3).getClassName());
        assertEquals(PACKAGE_2, res.get(3).getPackageName());
    }

    @Test
    public void testPredictTargets_reachTargetsLimit() {
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc1", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc2", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc3", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc4", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc5", 0));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc6", 0));

        when(mPackageData1.getConversationInfo("sc1")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData1.getConversationInfo("sc2")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData2.getConversationInfo("sc3")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData2.getConversationInfo("sc4")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData1.getConversationInfo("sc5")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData2.getConversationInfo("sc6")).thenReturn(mock(ConversationInfo.class));

        when(mPackageData1.getEventHistory("sc1")).thenReturn(mEventHistory1);
        when(mPackageData1.getEventHistory("sc2")).thenReturn(mEventHistory2);
        when(mPackageData2.getEventHistory("sc3")).thenReturn(mEventHistory3);
        when(mPackageData2.getEventHistory("sc4")).thenReturn(mEventHistory4);
        when(mPackageData1.getEventHistory("sc5")).thenReturn(mEventHistory5);
        when(mPackageData2.getEventHistory("sc6")).thenReturn(mEventHistory6);

        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anySet())).thenReturn(mEventIndex5);
        when(mEventHistory6.getEventIndex(anySet())).thenReturn(mEventIndex6);
        when(mEventHistory1.getEventIndex(anyInt())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anyInt())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anyInt())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anyInt())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anyInt())).thenReturn(mEventIndex5);
        when(mEventHistory6.getEventIndex(anyInt())).thenReturn(mEventIndex6);
        when(mEventIndex1.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(1L, 2L));
        when(mEventIndex2.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(2L, 3L));
        when(mEventIndex3.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(3L, 4L));
        when(mEventIndex4.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(4L, 5L));
        when(mEventIndex5.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(5L, 6L));
        when(mEventIndex6.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(6L, 7L));

        mPredictor.predictTargets();

        verify(mUpdatePredictionsMethod).accept(mAppTargetCaptor.capture());
        List<AppTarget> res = mAppTargetCaptor.getValue();
        assertEquals(5, res.size());

        assertEquals("sc6", res.get(0).getId().getId());
        assertEquals(CLASS_2, res.get(0).getClassName());
        assertEquals(PACKAGE_2, res.get(0).getPackageName());

        assertEquals("sc5", res.get(1).getId().getId());
        assertEquals(CLASS_1, res.get(1).getClassName());
        assertEquals(PACKAGE_1, res.get(1).getPackageName());

        assertEquals("sc4", res.get(2).getId().getId());
        assertEquals(CLASS_2, res.get(2).getClassName());
        assertEquals(PACKAGE_2, res.get(2).getPackageName());

        assertEquals("sc3", res.get(3).getId().getId());
        assertEquals(CLASS_2, res.get(3).getClassName());
        assertEquals(PACKAGE_2, res.get(3).getPackageName());

        assertEquals("sc2", res.get(4).getId().getId());
        assertEquals(CLASS_1, res.get(4).getClassName());
        assertEquals(PACKAGE_1, res.get(4).getPackageName());
    }

    @Test
    public void testPredictTargets_nullIntentFilter() {
        AppPredictionContext predictionContext = new AppPredictionContext.Builder(mContext)
                .setUiSurface(UI_SURFACE_SHARE)
                .setPredictedTargetCount(NUM_PREDICTED_TARGETS)
                .setExtras(new Bundle())
                .build();
        mPredictor = new ShareTargetPredictor(
                predictionContext, mUpdatePredictionsMethod, mDataManager, USER_ID, mContext);

        mPredictor.predictTargets();

        verify(mUpdatePredictionsMethod).accept(mAppTargetCaptor.capture());
        assertThat(mAppTargetCaptor.getValue()).isEmpty();
        verify(mDataManager, never()).getShareShortcuts(any(), anyInt());
    }

    @Test
    public void testPredictTargets_noSharingHistoryRankedByShortcutRank() {
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc1", 3));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc2", 2));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc3", 1));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc4", 0));

        when(mPackageData1.getConversationInfo("sc1")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData1.getConversationInfo("sc2")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData2.getConversationInfo("sc3")).thenReturn(mock(ConversationInfo.class));
        // "sc4" does not have a ConversationInfo.

        mPredictor.predictTargets();

        verify(mUpdatePredictionsMethod).accept(mAppTargetCaptor.capture());
        List<AppTarget> res = mAppTargetCaptor.getValue();
        assertEquals(4, res.size());

        assertEquals("sc4", res.get(0).getId().getId());
        assertEquals(CLASS_2, res.get(0).getClassName());
        assertEquals(PACKAGE_2, res.get(0).getPackageName());

        assertEquals("sc3", res.get(1).getId().getId());
        assertEquals(CLASS_2, res.get(1).getClassName());
        assertEquals(PACKAGE_2, res.get(1).getPackageName());

        assertEquals("sc2", res.get(2).getId().getId());
        assertEquals(CLASS_1, res.get(2).getClassName());
        assertEquals(PACKAGE_1, res.get(2).getPackageName());

        assertEquals("sc1", res.get(3).getId().getId());
        assertEquals(CLASS_1, res.get(3).getClassName());
        assertEquals(PACKAGE_1, res.get(3).getPackageName());
    }

    @Test
    public void testSortTargets() {
        AppTarget appTarget1 = new AppTarget.Builder(
                new AppTargetId("cls1#pkg1"), PACKAGE_1, UserHandle.of(USER_ID))
                .setClassName(CLASS_1)
                .build();
        AppTarget appTarget2 = new AppTarget.Builder(
                new AppTargetId("cls2#pkg1"), PACKAGE_1, UserHandle.of(USER_ID))
                .setClassName(CLASS_2)
                .build();
        AppTarget appTarget3 = new AppTarget.Builder(
                new AppTargetId("cls1#pkg2"), PACKAGE_2, UserHandle.of(USER_ID))
                .setClassName(CLASS_1)
                .build();
        AppTarget appTarget4 = new AppTarget.Builder(
                new AppTargetId("cls2#pkg2"), PACKAGE_2, UserHandle.of(USER_ID))
                .setClassName(CLASS_2)
                .build();
        AppTarget appTarget5 = new AppTarget.Builder(
                new AppTargetId("cls1#pkg3"), PACKAGE_3, UserHandle.of(USER_ID))
                .setClassName(CLASS_1)
                .build();

        when(mPackageData1.getClassLevelEventHistory(CLASS_1)).thenReturn(mEventHistory1);
        when(mPackageData1.getClassLevelEventHistory(CLASS_2)).thenReturn(mEventHistory2);
        when(mPackageData2.getClassLevelEventHistory(CLASS_1)).thenReturn(mEventHistory3);
        when(mPackageData2.getClassLevelEventHistory(CLASS_2)).thenReturn(mEventHistory4);
        // PackageData of PACKAGE_3 is empty.
        when(mDataManager.getPackage(PACKAGE_3, USER_ID)).thenReturn(null);

        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory1.getEventIndex(anyInt())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anyInt())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anyInt())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anyInt())).thenReturn(mEventIndex4);
        when(mEventIndex1.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(1L, 2L));
        when(mEventIndex2.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(2L, 3L));
        when(mEventIndex3.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(3L, 4L));
        when(mEventIndex4.getMostRecentActiveTimeSlot()).thenReturn(new Range<>(4L, 5L));

        mPredictor.sortTargets(
                List.of(appTarget1, appTarget2, appTarget3, appTarget4, appTarget5),
                mUpdatePredictionsMethod);

        verify(mUpdatePredictionsMethod).accept(mAppTargetCaptor.capture());
        List<AppTarget> res = mAppTargetCaptor.getValue();
        assertEquals(5, res.size());
        checkAppTarget(appTarget4, res.get(0));
        checkAppTarget(appTarget3, res.get(1));
        checkAppTarget(appTarget2, res.get(2));
        checkAppTarget(appTarget1, res.get(3));
        checkAppTarget(appTarget5, res.get(4));
    }

    @Test
    public void testSortTargets_nullIntentFilter() {
        AppPredictionContext predictionContext = new AppPredictionContext.Builder(mContext)
                .setUiSurface(UI_SURFACE_SHARE)
                .setPredictedTargetCount(NUM_PREDICTED_TARGETS)
                .setExtras(new Bundle())
                .build();
        mPredictor = new ShareTargetPredictor(
                predictionContext, mUpdatePredictionsMethod, mDataManager, USER_ID, mContext);
        AppTarget appTarget1 = new AppTarget.Builder(
                new AppTargetId("cls1#pkg1"), PACKAGE_1, UserHandle.of(USER_ID))
                .build();
        AppTarget appTarget2 = new AppTarget.Builder(
                new AppTargetId("cls2#pkg1"), PACKAGE_1, UserHandle.of(USER_ID))
                .build();
        AppTarget appTarget3 = new AppTarget.Builder(
                new AppTargetId("cls1#pkg2"), PACKAGE_2, UserHandle.of(USER_ID))
                .build();
        AppTarget appTarget4 = new AppTarget.Builder(
                new AppTargetId("cls2#pkg2"), PACKAGE_2, UserHandle.of(USER_ID))
                .build();
        AppTarget appTarget5 = new AppTarget.Builder(
                new AppTargetId("cls1#pkg3"), PACKAGE_3, UserHandle.of(USER_ID))
                .build();
        List<AppTarget> targets = List.of(appTarget1, appTarget2, appTarget3, appTarget4,
                appTarget5);

        mPredictor.sortTargets(targets, mUpdatePredictionsMethod);

        verify(mUpdatePredictionsMethod).accept(mAppTargetCaptor.capture());
        assertThat(mAppTargetCaptor.getValue()).containsExactlyElementsIn(targets);
        verify(mDataManager, never()).getPackage(any(), anyInt());
    }

    private static void checkAppTarget(AppTarget expected, AppTarget actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getClassName(), actual.getClassName());
        assertEquals(expected.getPackageName(), actual.getPackageName());
        assertEquals(expected.getUser(), actual.getUser());
    }

    private static ShareShortcutInfo buildShareShortcut(
            String packageName, String className, String shortcutId, int rank) {
        ShortcutInfo shortcutInfo = buildShortcut(packageName, shortcutId, rank);
        ComponentName componentName = new ComponentName(packageName, className);
        return new ShareShortcutInfo(shortcutInfo, componentName);
    }

    private static ShortcutInfo buildShortcut(String packageName, String shortcutId, int rank) {
        Context mockContext = mock(Context.class);
        when(mockContext.getPackageName()).thenReturn(packageName);
        when(mockContext.getUserId()).thenReturn(USER_ID);
        when(mockContext.getUser()).thenReturn(UserHandle.of(USER_ID));
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(mockContext, shortcutId)
                .setShortLabel(shortcutId)
                .setRank(rank)
                .setIntent(new Intent("TestIntent"));
        return builder.build();
    }
}
