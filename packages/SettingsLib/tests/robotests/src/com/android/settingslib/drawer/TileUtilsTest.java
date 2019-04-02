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

import static com.android.settingslib.drawer.TileUtils.IA_SETTINGS_ACTION;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY_URI;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.RuntimeEnvironment.application;

import android.app.ActivityManager;
import android.content.ContentResolver;
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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class TileUtilsTest {

    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mResources;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ContentResolver mContentResolver;

    private static final String URI_GET_SUMMARY = "content://authority/text/summary";
    private static final String URI_GET_ICON = "content://authority/icon/my_icon";

    @Before
    public void setUp() throws NameNotFoundException {
        mContext = spy(application);
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getResourcesForApplication(anyString())).thenReturn(mResources);
        when(mPackageManager.getResourcesForApplication((String) isNull())).thenReturn(mResources);
        when(mPackageManager.getApplicationInfo(eq("abc"), anyInt()))
                .thenReturn(application.getApplicationInfo());
        mContentResolver = spy(application.getContentResolver());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getPackageName()).thenReturn("com.android.settings");
    }

    @Test
    public void getTilesForIntent_shouldParseCategory() {
        final String testCategory = "category1";
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(true, testCategory));
        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).getCategory()).isEqualTo(testCategory);
    }

    @Test
    public void getTilesForIntent_shouldParseKeyHintForSystemApp() {
        String keyHint = "key";
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, keyHint);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* requiresSettings */);

        assertThat(outTiles).hasSize(1);
        assertThat(outTiles.get(0).getKey(mContext)).isEqualTo(keyHint);
    }

    @Test
    public void getTilesForIntent_shouldSkipNonSystemApp() {
        final String testCategory = "category1";
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(false, testCategory));

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);

        assertThat(outTiles).isEmpty();
    }

    @Test
    public void getCategories_withPackageName() {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        Map<Pair<String, String>, Tile> cache = new ArrayMap<>();
        Global.putInt(mContext.getContentResolver(), Global.DEVICE_PROVISIONED, 1);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        List<UserHandle> userHandleList = new ArrayList<>();

        userHandleList.add(new UserHandle(ActivityManager.getCurrentUser()));
        when(mUserManager.getUserProfiles()).thenReturn(userHandleList);

        TileUtils.getCategories(mContext, cache);
        verify(mPackageManager, atLeastOnce()).queryIntentActivitiesAsUser(
                intentCaptor.capture(), anyInt(), anyInt());

        assertThat(intentCaptor.getAllValues().get(0).getPackage())
                .isEqualTo(TileUtils.SETTING_PKG);
    }

    @Test
    public void getTilesForIntent_shouldReadMetadataTitleAsString() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, "my title", 0);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).getTitle(mContext)).isEqualTo("my title");
    }

    @Test
    public void getTilesForIntent_shouldReadMetadataTitleFromResource() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        when(mResources.getString(eq(123)))
                .thenReturn("my localized title");

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);
        assertThat(outTiles.size()).isEqualTo(1);
        assertThat(outTiles.get(0).getTitle(mContext)).isEqualTo("my localized title");
    }

    @Test
    public void getTilesForIntent_shouldNotTintIconIfInSettingsPackage() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123);
        resolveInfo.activityInfo.packageName = "com.android.settings";
        resolveInfo.activityInfo.applicationInfo.packageName = "com.android.settings";
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles.get(0).isIconTintable(mContext)).isFalse();
    }

    @Test
    public void getTilesForIntent_tileAlreadyInCache_shouldUpdateMetaData() {
        final Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        final List<Tile> outTiles = new ArrayList<>();
        final List<ResolveInfo> info = new ArrayList<>();
        final ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123);
        resolveInfo.activityInfo.packageName = "com.android.settings";
        resolveInfo.activityInfo.applicationInfo.packageName = "com.android.settings";
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles).hasSize(1);
        final Bundle oldMetadata = outTiles.get(0).getMetaData();

        resolveInfo.activityInfo.metaData = new Bundle(oldMetadata);
        resolveInfo.activityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON,
                com.android.internal.R.drawable.ic_phone);
        outTiles.clear();
        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles).hasSize(1);
        final Bundle newMetaData = outTiles.get(0).getMetaData();
        assertThat(newMetaData).isNotSameAs(oldMetadata);
    }


    @Test
    public void getTilesForIntent_shouldMarkIconTintableIfMetadataSet() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123);
        resolveInfo.activityInfo.metaData
                .putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, true);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles.get(0).isIconTintable(mContext)).isTrue();
    }

    @Test
    public void getTilesForIntent_shouldProcessUriContentForSystemApp() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.getTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles.size()).isEqualTo(1);
    }

    public static ResolveInfo newInfo(boolean systemApp, String category) {
        return newInfo(systemApp, category, null);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint) {
        return newInfo(systemApp, category, keyHint, null, null);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint,
            String iconUri, String summaryUri) {
        return newInfo(systemApp, category, keyHint, iconUri, summaryUri, null, 0);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint,
            String iconUri, String summaryUri, String title, int titleResId) {

        ResolveInfo info = new ResolveInfo();
        info.system = systemApp;
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "abc";
        info.activityInfo.name = "123";
        info.activityInfo.metaData = new Bundle();
        info.activityInfo.metaData.putString("com.android.settings.category", category);
        info.activityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON, 314159);
        info.activityInfo.metaData.putString(META_DATA_PREFERENCE_SUMMARY, "static-summary");
        if (keyHint != null) {
            info.activityInfo.metaData.putString(META_DATA_PREFERENCE_KEYHINT, keyHint);
        }
        if (iconUri != null) {
            info.activityInfo.metaData.putString(META_DATA_PREFERENCE_ICON_URI, iconUri);
        }
        if (summaryUri != null) {
            info.activityInfo.metaData.putString(META_DATA_PREFERENCE_SUMMARY_URI, summaryUri);
        }
        if (titleResId != 0) {
            info.activityInfo.metaData.putInt(TileUtils.META_DATA_PREFERENCE_TITLE, titleResId);
        } else if (title != null) {
            info.activityInfo.metaData.putString(TileUtils.META_DATA_PREFERENCE_TITLE, title);
        }
        info.activityInfo.applicationInfo = new ApplicationInfo();
        if (systemApp) {
            info.activityInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        return info;
    }
}
