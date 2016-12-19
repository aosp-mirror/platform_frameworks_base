/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.drawer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.settingslib.TestConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class TileUtilsTest {

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mResources;
    @Mock
    private UserManager mUserManager;

    @Before
    public void setUp() throws NameNotFoundException {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getResourcesForApplication(anyString())).thenReturn(mResources);
    }

    @Test
    public void getTilesForIntent_shouldParseCategory() {
        final String testCategory = "category1";
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(true, testCategory));

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).category).isEqualTo(testCategory);
    }

    @Test
    public void getTilesForIntent_shouldParseKeyHintForSystemApp() {
        String keyHint = "key";
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, keyHint);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).key).isEqualTo(keyHint);
    }

    @Test
    public void getTilesForIntent_shouldSkipNonSystemApp() {
        final String testCategory = "category1";
        Intent intent = new Intent();
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(false, testCategory));

        when(mPackageManager.queryIntentActivitiesAsUser(eq(intent), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForIntent(mContext, UserHandle.CURRENT, intent, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */,
                false /* checkCategory */);

        assertThat(outTiles.isEmpty()).isTrue();
    }

    @Test
    public void getCategories_shouldHandleExtraIntentAction() {
        final String testCategory = "category1";
        final String testAction = "action1";
        Map<Pair<String, String>, Tile> cache = new ArrayMap<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(true, testCategory));
        Global.putInt(mContext.getContentResolver(), Global.DEVICE_PROVISIONED, 1);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        List<UserHandle> userHandleList = new ArrayList<>();
        userHandleList.add(UserHandle.CURRENT);
        when(mUserManager.getUserProfiles()).thenReturn(userHandleList);

        when(mPackageManager.queryIntentActivitiesAsUser(argThat(new ArgumentMatcher<Intent>() {
            public boolean matches(Object event) {
                return testAction.equals(((Intent) event).getAction());
            }
        }), anyInt(), anyInt())).thenReturn(info);

        List<DashboardCategory> categoryList = TileUtils.getCategories(
            mContext, cache, false /* categoryDefinedInManifest */, testAction);

        assertThat(categoryList.get(0).tiles.get(0).category).isEqualTo(testCategory);
    }

    private ResolveInfo newInfo(boolean systemApp, String category) {
        return newInfo(systemApp, category, null);
    }

    private ResolveInfo newInfo(boolean systemApp, String category, String keyHint) {
        ResolveInfo info = new ResolveInfo();
        info.system = systemApp;
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "abc";
        info.activityInfo.name = "123";
        info.activityInfo.metaData = new Bundle();
        info.activityInfo.metaData.putString("com.android.settings.category", category);
        if (keyHint != null) {
            info.activityInfo.metaData.putString("com.android.settings.keyhint", keyHint);
        }
        info.activityInfo.applicationInfo = new ApplicationInfo();
        if (systemApp) {
            info.activityInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        return info;
    }
}
