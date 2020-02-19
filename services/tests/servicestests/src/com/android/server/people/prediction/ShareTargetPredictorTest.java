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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.prediction.AppPredictionContext;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.server.people.data.ConversationInfo;
import com.android.server.people.data.DataManager;
import com.android.server.people.data.EventHistory;
import com.android.server.people.data.PackageData;
import com.android.server.people.prediction.ShareTargetPredictor.ShareTarget;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public final class ShareTargetPredictorTest {

    private static final String UI_SURFACE_SHARE = "share";
    private static final int NUM_PREDICTED_TARGETS = 5;
    private static final int USER_ID = 0;
    private static final String PACKAGE_1 = "pkg1";
    private static final String CLASS_1 = "cls1";
    private static final String PACKAGE_2 = "pkg2";
    private static final String CLASS_2 = "cls2";

    @Mock private Context mContext;
    @Mock private DataManager mDataManager;
    @Mock private PackageData mPackageData1;
    @Mock private PackageData mPackageData2;

    private List<ShareShortcutInfo> mShareShortcuts = new ArrayList<>();

    private ShareTargetPredictor mPredictor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mDataManager.getShareShortcuts(any(), anyInt())).thenReturn(mShareShortcuts);
        when(mDataManager.getPackage(PACKAGE_1, USER_ID)).thenReturn(mPackageData1);
        when(mDataManager.getPackage(PACKAGE_2, USER_ID)).thenReturn(mPackageData2);

        AppPredictionContext predictionContext = new AppPredictionContext.Builder(mContext)
                .setUiSurface(UI_SURFACE_SHARE)
                .setPredictedTargetCount(NUM_PREDICTED_TARGETS)
                .setExtras(new Bundle())
                .build();
        mPredictor = new ShareTargetPredictor(
                predictionContext, targets -> { }, mDataManager, USER_ID);
    }

    @Test
    public void testGetShareTargets() {
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc1"));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_1, CLASS_1, "sc2"));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc3"));
        mShareShortcuts.add(buildShareShortcut(PACKAGE_2, CLASS_2, "sc4"));

        when(mPackageData1.getConversationInfo("sc1")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData1.getConversationInfo("sc2")).thenReturn(mock(ConversationInfo.class));
        when(mPackageData2.getConversationInfo("sc3")).thenReturn(mock(ConversationInfo.class));
        // "sc4" does not have a ConversationInfo.

        when(mPackageData1.getEventHistory(anyString())).thenReturn(mock(EventHistory.class));
        when(mPackageData2.getEventHistory(anyString())).thenReturn(mock(EventHistory.class));

        List<ShareTarget> shareTargets = mPredictor.getShareTargets();

        assertEquals(4, shareTargets.size());

        assertEquals("sc1", shareTargets.get(0).getShareShortcutInfo().getShortcutInfo().getId());
        assertNotNull(shareTargets.get(0).getConversationData());

        assertEquals("sc2", shareTargets.get(1).getShareShortcutInfo().getShortcutInfo().getId());
        assertNotNull(shareTargets.get(1).getConversationData());

        assertEquals("sc3", shareTargets.get(2).getShareShortcutInfo().getShortcutInfo().getId());
        assertNotNull(shareTargets.get(2).getConversationData());

        assertEquals("sc4", shareTargets.get(3).getShareShortcutInfo().getShortcutInfo().getId());
        assertNull(shareTargets.get(3).getConversationData());
    }

    private ShareShortcutInfo buildShareShortcut(
            String packageName, String className, String shortcutId) {
        ShortcutInfo shortcutInfo = buildShortcut(packageName, shortcutId);
        ComponentName componentName = new ComponentName(packageName, className);
        return new ShareShortcutInfo(shortcutInfo, componentName);
    }

    private ShortcutInfo buildShortcut(String packageName, String shortcutId) {
        Context mockContext = mock(Context.class);
        when(mockContext.getPackageName()).thenReturn(packageName);
        when(mockContext.getUserId()).thenReturn(USER_ID);
        when(mockContext.getUser()).thenReturn(UserHandle.of(USER_ID));
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(mockContext, shortcutId)
                .setShortLabel(shortcutId)
                .setIntent(new Intent("TestIntent"));
        return builder.build();
    }
}
